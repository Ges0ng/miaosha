package com.nmsl.controller;

import com.google.common.util.concurrent.RateLimiter;
import com.nmsl.service.OrderService;
import com.nmsl.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * @author 2020
 */
@RestController
@RequestMapping("/stock")
@Slf4j
public class StockController {

    @Resource
    private OrderService orderService;

    @Resource
    private UserService userService;

    //创建令牌桶实例 放行10个请求
    private RateLimiter rateLimiter = RateLimiter.create(10);


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


    //令牌桶实现乐观锁+限流+redismd5缓存+隐藏接口
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



    //令牌桶简单实现
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

    //乐观锁
    @GetMapping("/kill")
    public String kill(Integer id){    //秒杀方法
        System.out.println("秒杀商品的id = " + id);
        //根据秒杀商品的id去调用秒杀业务
        try {
            //放在控制器调用的地方使用synchronized
            int orderId = orderService.kill(id);
            return "秒杀成功,订单id为 = " + String.valueOf(orderId);
        } catch (Exception e) {
            e.printStackTrace();
            return e.getMessage();
        }
    }

    //悲观锁
    @GetMapping("/killbeiguan")
    public String killbeiguan(Integer id){    //秒杀方法
        System.out.println("秒杀商品的id = " + id);
        //根据秒杀商品的id去调用秒杀业务

        try {
            //放在控制器调用的地方使用synchronized
            synchronized (this) {
                int orderId = orderService.killbeiguan(id);
                return "秒杀成功,订单id为 = " + String.valueOf(orderId);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return e.getMessage();
        }
    }


}
