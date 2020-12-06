package com.nmsl.service;

/**
 * @author 2020
 */
public interface OrderService {

    int kill(Integer id);   //处理秒杀的下单方法,并返回订单id

    int killbeiguan(Integer id);

    //用来生成md5签名的方法
    String getMd5(Integer id, Integer userId);

    //用来处理秒杀的下单方法 (并返回订单id ) +md5签名,接口隐藏
    int kill(Integer id, Integer userId, String md5);
}
