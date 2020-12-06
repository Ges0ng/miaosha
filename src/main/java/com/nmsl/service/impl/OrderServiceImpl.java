package com.nmsl.service.impl;

import com.nmsl.dao.OrderDao;
import com.nmsl.dao.StockDao;
import com.nmsl.dao.UserDao;
import com.nmsl.entity.Order;
import com.nmsl.entity.Stock;
import com.nmsl.entity.User;
import com.nmsl.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * @author Paracosm
 */
@Service
@Transactional
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Resource
    private StockDao stockDao;

    @Resource
    private OrderDao orderDao;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserDao userDao;

    //synchronized最好不要加在方法上,而是加在controller调用该方法的地方.
    //如果要加在方法上必须去掉事务注解@Transactional.
    //因为该方法还是被包含在事务内的, 事务并没有完成,还是有可能出现多提交的问题. 所以需要取消事务注解.
    //public synchronized int kill(Integer id) {

    @Override
    public  int kill(Integer id) {
        //加入Redis限时处理,是否在redis中有效? 对秒杀过期的请求进行拒绝处理

        //校验redis中秒杀商品是否超时
        Boolean key = stringRedisTemplate.hasKey("kill" + id);

        //如果存在,说明还在秒杀的范围内.不存在说明超时了
        if (!key){
            throw new RuntimeException("当前商品抢购活动已结束!");
        }

        //1.根据商品id校验库存
        Stock stock = checkStock(id);
        //2.扣除库存
        updateSale(stock);
        //3.创建订单
        return createOrder(stock);
    }

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


    //悲观锁的kill
    @Override
    public  int killbeiguan(Integer id) {
        //1.根据商品id校验库存
        Stock stock = checkStock(id);

        //2.扣除库存
        updateSalebeiguan(stock);

        //3.创建订单
        return createOrder(stock);
    }

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
        stringRedisTemplate.opsForValue().set(hashKey,key,60, TimeUnit.SECONDS);
        log.info("redis写入:[{}][{}]", hashKey, key);

        return key;
    }



    //1.校验库存
    private Stock checkStock (Integer id){
        Stock stock = stockDao.checkStock(id);;
        if (stock.getSale().equals(stock.getCount())){
            throw new RuntimeException("库存不足");
        }
        return stock;
    }

    //2.扣除库存 (乐观锁)
    private void updateSale(Stock stock){
        //在sql层面完成 sale+1 和 version+1 并根据商品id和版本号同时查询更新的商品
        int i = stockDao.updateSale(stock);
        //没有更新成功
        if (i == 0){
            throw new RuntimeException("抢购失败,请重试");
        }
    }

    //3.创建订单
    private Integer createOrder(Stock stock){
        Order order = new Order();
        order.setSid(stock.getId()).setName(stock.getName()).setCreateDate(new Date());
        orderDao.createOrder(order);
        return order.getId();
    }





    //2.扣除库存 (悲观锁)
    private void updateSalebeiguan(Stock stock){
        stock.setSale(stock.getSale() + 1);
        stockDao.updateSalebeiguan(stock);
    }
}
