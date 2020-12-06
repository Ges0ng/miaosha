package com.nmsl.dao;

import com.nmsl.entity.Stock;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author 2020
 */

public interface StockDao {
    //根据商品id查询库存信息方法
    Stock checkStock(Integer id);

    //根据商品id扣除库存
    int updateSale(Stock stock);

    //悲观锁根据商品id扣除库存
    int updateSalebeiguan(Stock stock);
}
