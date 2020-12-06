package com.nmsl.service;

/**
 * @author 2020
 */
public interface UserService {
    //向redis写入用户访问次数
    int saveUserCount(Integer userId);

    //判断单位时间内调用次数
    boolean getUserCount(Integer userId);
}
