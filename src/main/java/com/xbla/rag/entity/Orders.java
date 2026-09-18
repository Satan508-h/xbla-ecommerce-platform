package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 订单实体。对应 {@code orders} 表（表名带 s，因为 {@code order} 是 SQL 关键字）。
 *
 * <p><b>类名为什么是 Orders 而不是 Order？</b>
 * Spring 框架里已经有一个 {@code org.springframework.core.annotation.Order} 注解，
 * 类名撞名会让 import 很容易搞错。直接跟表名走叫 {@code Orders}，省心。
 */
@Data
@TableName("orders")
public class Orders {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long userId;

    private BigDecimal totalAmount;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    /** 订单状态：10待付款 20待发货 30已发货 40已完成 50已取消 */
    private Integer status;

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private String logisticsNo;

    private String logisticsCompany;

    /** 以下四个是各状态的时间点，未流转到的状态为 null */
    private OffsetDateTime paidAt;

    private OffsetDateTime shippedAt;

    private OffsetDateTime completedAt;

    private OffsetDateTime cancelledAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Integer deleted;
}
