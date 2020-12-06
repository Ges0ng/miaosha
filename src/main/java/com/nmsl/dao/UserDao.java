package com.nmsl.dao;

import com.nmsl.entity.User;
import org.apache.ibatis.annotations.Mapper;


@Mapper
public interface UserDao {
    User findById(Integer id);
}
