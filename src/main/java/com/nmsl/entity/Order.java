package com.nmsl.entity;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;
import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Accessors(chain = true)    //允许链式调用
public class Order {
    private Integer id;
    private Integer sid;
    private String name;
    private Date createDate;
}
