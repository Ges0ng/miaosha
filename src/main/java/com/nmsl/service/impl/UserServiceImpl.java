package com.nmsl.service.impl;

import com.nmsl.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

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
        //根据用户id对应的key获取调用i次数
        String limitKey = "LIMIT" + "_" + userId;
        //跟库用户调用次数的key获取redis中调用次数
        String limitNum = stringRedisTemplate.opsForValue().get(limitKey);
        if (limitNum == null) {
            //为空直接抛弃,说明key出现异常
            log.error("该用户没有访问申请md5验证值记录,疑似异常请求");
            return true;
        }
        //false代表没有超过,true代表超过
        return Integer.parseInt(limitNum) > 10;
    }
}
