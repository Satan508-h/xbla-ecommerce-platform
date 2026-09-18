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
 * 优惠券模板实体。对应 {@code coupon} 表。
 *
 * <p>注意区分：这是<b>券的定义</b>（"满3000减300"这个规则），
 * 某个用户持有的券在 {@link UserCoupon}。
 */
@Data
@TableName("coupon")
public class Coupon {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String couponNo;

    private String name;

    /** 券类型：1满减 2折扣 3立减 */
    private Integer type;

    /** 满减/立减的减免金额（type 为 1 或 3 时才有值） */
    private BigDecimal discountValue;

    /** 折扣率，如 0.85 表示 85 折（type 为 2 时才有值） */
    private BigDecimal discountRate;

    /** 使用门槛，订单满多少才能用。0 表示无门槛 */
    private BigDecimal thresholdAmount;

    /** 适用类目，null 表示全场通用 */
    private String applicableCategory;

    /** 发行总量 */
    private Integer totalCount;

    /** 已领取量，数据库有 CHECK 约束保证不超过 totalCount */
    private Integer issuedCount;

    private OffsetDateTime validFrom;

    private OffsetDateTime validTo;

    /** 1启用 0停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Integer deleted;
}
