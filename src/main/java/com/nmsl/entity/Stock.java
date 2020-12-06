package com.nmsl.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * @author 2020
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Accessors(chain = true)    //允许链式调用
public class Stock {
    private Integer id;
    private String name;
    private Integer count;
    private Integer sale;
    private Integer version;
}
