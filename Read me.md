# 非分布式秒杀系统 并发情况下解决超卖问题

>  乐观锁防止超卖 / 令牌桶限流/ redis缓存 / 消息队列异步处理订单

```shell
#数据库表

drop table if exists `stock`;
create table `stock`(
	`id` int(11) unsigned not null auto_increment,
	`name` varchar(50) not null default '' comment '名称',
	`count` int(11) not null comment '库存',
	`sale` int(11) not null comment '已售',
	`version` int(11) not null comment '乐观锁,版本号',
	primary key(`id`)
)engine=innoDB default charset=utf8;

drop table if exists `stock_order`;
create table `stock_order`(
	`id` int(11) unsigned not null auto_increment,
	`sid` int(11) not null comment '库存id',
	`name` varchar(30) not null default '' comment '商品名称',
	`create_time` timestamp not null default current_timestamp on update current_timestamp comment '创建时间',
	primary key(`id`)
)engine=innoDB default charset=utf8;


```



```properties
server.port=8080
server.servlet.context-path=/

#数据库源
spring.datasource.type=com.alibaba.druid.pool.DruidDataSource
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.datasource.url=jdbc:mysql://localhost:3306/ms?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=root

#配置日志
mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.stdout.StdOutImpl

logging.level.root=info
logging.level.com.nmsl.dao=debug

#mybatis配置
mybatis.mapper-locations=classpath:com/nmsl/mapper/*.xml
mybatis.type-aliases-package=com.nmsl.entity
```





## 1.悲观锁

```java
@RestController
public class StockController {

    @Resource
    private OrderService orderService;

    @GetMapping("/kill")
    public String kill(Integer id){    //秒杀方法
        System.out.println("秒杀商品的id = " + id);
        //根据秒杀商品的id去调用秒杀业务
        try {
            synchronized (this){
                //放在控制器调用的地方使用synchronized
                int orderId = orderService.kill(id);
                return "秒杀成功,订单id为 = " + String.valueOf(orderId);
            }

        } catch (Exception e) {
            e.printStackTrace();
            return e.getMessage();
        }
    }


    
    
    
/**
 * @author Paracosm
 */
@Service
@Transactional
public class OrderServiceImpl implements OrderService {

    @Resource
    private StockDao stockDao;

    @Resource
    private OrderDao orderDao;

    
    //synchronized最好不要加在方法上,而是加在controller调用该方法的地方.
    //如果要加在方法上必须去掉事务注解@Transactional.
    //因为该方法还是被包含在事务内的, 事务并没有完成,还是有可能出现多提交的问题. 所以需要取消事务注解.
    //public synchronized int kill(Integer id) {   
    
    @Override
    public  int kill(Integer id) {   
        //1.根据商品id校验库存
        Stock stock = checkStock(id);

        //2.扣除库存
        updateSale(stock);

        //3.创建订单
        return createOrder(stock);
    }

    //1.校验库存
    private Stock checkStock (Integer id){
        Stock stock = stockDao.checkStock(id);;
        if (stock.getSale().equals(stock.getCount())){
            throw new RuntimeException("库存不足");
        }
        return stock;
    }

    //2.扣除库存
    private void updateSale(Stock stock){
        stock.setSale(stock.getSale() + 1);
        stockDao.updateSale(stock);
    }

    //3.创建订单
    private Integer createOrder(Stock stock){
        Order order = new Order();
        order.setSid(stock.getId()).setName(stock.getName()).setCreateDate(new Date());
        orderDao.createOrder(order);
        return order.getId();
    }
}

```

`同步代码块如果在大量并发的情况下抢当前的线程锁.synchronized只会允许一个线程进行业务调用,后面的调用都会处于阻塞状态. 1.线程阻塞 2.系统效率吞吐量受影响 3.用户体验不好`



## 2.乐观锁

> 主要是把并发压力都交给了数据库. 利用数据库中定义的version字段和事务来实现在并发情况下解决.超卖



更新方法改造

```java
    private void updateSale(Stock stock){
        //在sql层面完成 sale+1 和 version+1 并根据商品id和版本号同时查询更新的商品
        int i = stockDao.updateSale(stock);

        //没有更新成功
        if (i == 0){
            throw new RuntimeException("抢购失败,请重试");
        }
    }
```

```xml
<-- update stock set sale=#{sale} where id=#{id}-->

<!--根据商品id扣除库存-->
<update id="updateSale" parameterType="Stock">
        update stock
        set sale=sale + 1,
            version=version + 1
        where id = #{id}
          and version = #{version};
    </update>
```



## 3.接口限流

​	对某一时间窗口内的请求数进行限制,保持系统的可用性和稳定性,防止因为流量暴增导致的系统运行缓慢或者宕机.

> 如何解决接口限流?

- 令牌桶	`google开源项目Guava中的RateLimiter使用的就是令牌桶控制算法.`
  - 请求先进入漏桶中,漏桶以一定速度出水,当水流入速度过大会直接溢出,漏桶算法是强行限制数据的传输速率.
- 漏桶(漏斗算法)
  - 最初来源于计算机网络,在网络传输数据时,为了防止网络阻塞需要限制流出网络的流量,使流量以比较均衡的速度向外发送. 令牌桶实现这个功能,可以控制发送到网络上的数据的数目,并允许突发数据的发送, 大小固定的令牌桶课自行以恒定的速率源源不断的产生令牌,如果令牌不被消耗,或者被消耗的速度小于产生的速度,令牌就会不断地增多直到把桶填满.后面再产生的令牌就会从桶中溢出,最后桶中可以保存的最大令牌数永远不会超过桶的大小.这意味着面对瞬时大流量,该算法可以在短时间内请求拿到大量令牌,而且拿令牌的过程并不是消耗很大的事情. 

`开发高并发系统时候的三把保护系统的利器: 缓存,降级,限流`

1. 缓存: 提升系统访问速度和增大系统处理容量

2. 降级: 当服务器压力剧增的情况下,根据当前业务情况及流量对一些服务和页面有策略的降级,以此释放服务器资源以保证核心人物的正常运行.

3. 限流: 通过对并发访问/请求进行限速,或者对一个时间窗口内的请求进行限速来保护系统, 一旦达到限制速率可以拒绝服务,排队或者等待,降级等处理.

   #### 1.令牌桶

   > RateLimiter

```xml
1.引入依赖

 <!--谷歌开源工具类 - RateLimter 令牌桶的实现-->
        <dependency>
            <groupId>com.google.guava</groupId>
            <artifactId>guava</artifactId>
            <version>29.0-jre</version>
        </dependency>
```

```java
@RestController
@RequestMapping("/stock")
@Slf4j
public class StockController {

    @Resource
    private OrderService orderService;

    //创建令牌桶实例
    private RateLimiter rateLimiter = RateLimiter.create(10);//放行10个请求


    @GetMapping("/sale")
    public String sale(){
        //1.没有获取到token的请求一直阻塞，直到获取到token
        log.info("等待的时间"+rateLimiter.acquire());

        //2.设置一个等待时间，如果在等待的时间内获取到了token令牌，则处理业务。如果超时则抛弃
        if (!rateLimiter.tryAcquire(2, TimeUnit.SECONDS)){
            System.out.println("当前请求被限流，直接抛弃");
            return "抢购超时";
        }

        System.out.println("处理业务......");
        return "抢购成功";
    }
```

#### 2.令牌桶实现乐观锁+限流

```java
//创建令牌桶实例 每次放行10个token 限流
private RateLimiter rateLimiter = RateLimiter.create(10);

//令牌桶实现乐观锁+限流
    @GetMapping("/killtoken")
    public String killtoken(Integer id){    //秒杀方法
        System.out.println("秒杀商品的id = " + id);
        //根据秒杀商品的id去调用秒杀业务
        if (!rateLimiter.tryAcquire(2, TimeUnit.SECONDS)) {
            log.info("抛弃请求：抢购失败啦！");
           return "抢购超时，请重试！";
        }else {
            try {
                //放在控制器调用的地方使用synchronized
                int orderId = orderService.kill(id);
                return "秒杀成功,订单id为 = " + String.valueOf(orderId);
            } catch (Exception e) {
                e.printStackTrace();
                return e.getMessage();
            }
        }
    }
```



## 4.隐藏秒杀接口

1. 一定时间内执行秒杀处理吗,不能在任意时间接收秒杀请求,如何加入实践验证?		`限时抢购`	`redis记录时间,setex`
2. 如果遇到抓包获取接口地址,再通过脚本抢购的怎么办?         `抢购接口隐藏`     
3. 单用户限制频率 (单位时间内限制访问次数)



### 1.redis记录秒杀时间

```shell
#将秒杀商品放入redis并设置超时	setex kill+id time id
setex kill1 180 1


`定时任务需要加@EnableScheduling注解 具体可以看官方文档`
cron表达式是指定时任务触发时间的字符串表达式，分为 6 或 7 个域，每一个域代表一个含义
	语法：	
	Seconds Minutes Hours Day Month Week
```

```xml
<--加入redis依赖-->
		<dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
```

```properties
#redis配置
spring.redis.database=0
spring.redis.port=6379
spring.redis.host=localhost
```

```java
	@Resource
    private StringRedisTemplate stringRedisTemplate;

	public  int kill(Integer id) {
        //加入Redis限时处理,是否在redis中有效? 对秒杀过期的请求进行拒绝处理
        //校验redis中秒杀商品是否超时
        Boolean key = stringRedisTemplate.hasKey("kill" + id);
        if (!key){        //如果存在,说明还在秒杀的范围内.不存在说明超时了
            throw new RuntimeException("当前商品抢购活动已结束!");
        }

        //1.根据商品id校验库存
        Stock stock = checkStock(id);
        //2.扣除库存
        updateSale(stock);
        //3.创建订单
        return createOrder(stock);
    }
```



## 2.隐藏接口	(接口加盐)

- 每次点击秒杀按钮,先从服务器获取一个秒杀的验证值(接口内判断是否到秒杀时间)
- Redis以缓存用户id和商品id为key,秒杀地址为value缓存验证值
- 用户请求秒杀商品的时候要带上秒杀验证值进行校验.

```shell
#表结构

set names utf8mb4;
set foreign_key_checks = 0;


drop table if exists `user`;
create table `user`(
	`id` int(11) not null auto_increment comment '主键',
	`name` varchar(80) default null comment '用户名',
	`password` varchar(40) default null comment '用户密码',
    primary key(`id`)
)engine=InnoDB auto_increment=2 default charset=utf8;

set foreign_key_checks = 1 ;
```



```java
   //Controller层
	//获取md5
	@RequestMapping("/md5")
    public String getMd5(Integer id,Integer userId){
        String md5;
        try{
            md5 = orderService.getMd5(id,userId);
        }catch (Exception e){
            e.printStackTrace();
            return "获取md5失败" + e.getMessage();
        }
        return "获取md5的数值为: " + md5;
    }


	
    //实现类
	@Resource
    private UserDao userDao;

	@Override
    public String getMd5(Integer id, Integer userid) {
        //验证userid用户合法性
        User user = userDao.findById(userid);
        if (user==null) {
            throw new RuntimeException("用户信息不存在");
        }
        log.info("用户信息:[{}]",user.toString());
        //验证id 商品合法性
        Stock stock = stockDao.checkStock(id);
        if (stock==null) {
            throw new RuntimeException("商品信息不合法");
        }
        log.info("商品信息:[{}]",stock.toString());
        //生成hashkey
        String hashKey = "KEY_" + userid + "_" + id;
        //生成md5签名放入redis    这里!QS#是一个盐 随机生成
        String key = DigestUtils.md5DigestAsHex((userid + id + "!Q*jS#").getBytes());
        stringRedisTemplate.opsForValue().set(hashKey,key,3600, TimeUnit.SECONDS);
        log.info("redis写入:[{}][{}]", hashKey, key);

        return key;
    }


===========================================================================================

```

```sql
<select id="findById" parameterType="Integer" resultType="User">
        select id,name,password from user where id=#{id}
    </select>
```





​	Controller层代码

```java
//令牌桶实现乐观锁+限流+redismd5缓存
    @GetMapping("/killtokenmd5")
    public String killtokenMd5(Integer id,Integer userId,String md5){    //秒杀方法
        System.out.println("秒杀商品的id = " + id);
        //根据秒杀商品的id去调用秒杀业务
        if (!rateLimiter.tryAcquire(2, TimeUnit.SECONDS)) {
            log.info("抛弃请求：抢购失败啦！");
            return "抢购超时，请重试！";

        }else {
            try {
                //放在控制器调用的地方使用synchronized
                int orderId = orderService.kill(id,userId,md5);
                return "秒杀成功,订单id为 = " + String.valueOf(orderId);
            } catch (Exception e) {
                e.printStackTrace();
                return e.getMessage();
            }
        }
    }
	
```

​	Service层代码:

```java
//加了md5签名的
    @Override
    public int kill(Integer id, Integer userid, String md5) {
        //验证超时
        //Boolean key = stringRedisTemplate.hasKey("kill" + id);
        //如果存在,说明还在秒杀的范围内.不存在说明超时了
        //if (!key) {
        //    throw new RuntimeException("当前商品抢购活动已结束!");
        //}

        //验证签名 (开发中应该是在验证超时之后执行的)
        String hashKey = "KEY_" + userid + "_" + id;
        if (!md5.equals(stringRedisTemplate.opsForValue().get(hashKey))) {
            throw new RuntimeException("当前请求数据不合法,请稍后再试.");
        }

        //1.根据商品id校验库存
        Stock stock = checkStock(id);
        //2.扣除库存
        updateSale(stock);
        //3.创建订单
        return createOrder(stock);
    }
```



`其实也可以可以key在存入reids的同时记录一个访问次数,超过次数就拒绝访问`



## 单用户限制访问频率

service接口

```java
public interface UserService {
    //向redis写入用户访问次数
    int saveUserCount(Integer UserId);
    
    //判断单位时间内调用次数
    boolean getUserCount(Integer UserId);
}
```

实现类

```java
@Service
@Transactional
@Slf4j
public class UserServiceImpl implements UserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public int saveUserCount(Integer userId) {
        //根据不同用户id生成调用次数的key
        String limitKey = "LIMIT" + "_" + userId;
        //获取redis中指定key的调用次数
        String limitNum = stringRedisTemplate.opsForValue().get(limitKey);
        int limit = -1;
        if (limitNum == null) {
            //第一次调用时放入redis中设置为0
            stringRedisTemplate.opsForValue().set(limitKey,"0",60, TimeUnit.SECONDS);
        }else {
            //已经不是第一次调用,每次调用就+1
            limit = Integer.parseInt(limitNum) + 1;
            stringRedisTemplate.opsForValue().set(limitKey,String.valueOf(limit),60,TimeUnit.SECONDS);
        }
        return limit;
    }

    @Override
    public boolean getUserCount(Integer userId) {
        //根据用户id对应的key获取调用次数
        String limitKey = "LIMIT" + "_" + userId;
        //跟库用户调用次数的key获取redis中调用次数
        String limitNum = stringRedisTemplate.opsForValue().get(limitKey);
        if (limitNum == null) {
            //为空直接抛弃,说明key出现异常
            log.error("该用户没有访问申请md5验证值记录,疑似一场请求");
            return true;
        }
        //false代表没有超过,true代表超过
        return Integer.parseInt(limitKey) > 10;
    }
}

```



controller层

```java
     //令牌桶实现乐观锁+限流+redismd5缓存验证+隐藏接口+单用户限流
    @GetMapping("/killtokenmd5limit")
    public String killtokenmd5limit(Integer id, Integer userId, String md5) {
        //加入令牌桶限流措施
        if (!rateLimiter.tryAcquire(2, TimeUnit.SECONDS)) {
            log.info("抛弃请求：抢购失败啦！");
            return "抢购超时，请重试！";
        }else {
            try {
                //单用户调用接口频率限制
                int count = userService.saveUserCount(userId);
                log.info("用户截至该次的访问数量:[{}]", count);
                //进行调用次数的判断.如果为true购买失败,false可以继续执行
                boolean isBanned = userService.getUserCount(userId);
                if (isBanned) {
                    log.info("购买失败,超过频率限制");
                    return "购买失败,超过频率限制";
                }

                int orderId = orderService.kill(id,userId,md5);
                return "秒杀成功,订单id为 = " + String.valueOf(orderId);
            } catch (Exception e) {
                e.printStackTrace();
                return e.getMessage();
            }
        }
    }
```

​	`ps:根据不良人老师的视频学习的,原视频地址https://www.bilibili.com/video/BV13a4y1t7Wh`