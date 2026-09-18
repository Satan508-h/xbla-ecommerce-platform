package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 用户持有的优惠券实体。对应 {@code user_coupon} 表。
 *
 * <p>这是一张<b>多对多关系表</b>（用户 × 券模板），
 * {@code status} 字段驱动了完整的券生命周期。
 *
 * <p>MCP 工具「优惠券查询」的典型查询是：
 * <pre>{@code
 * WHERE user_id = ? AND status = 1 AND expired_at > now()
 * }</pre>
 * 数据库上建的复合索引 {@code idx_user_coupon_user_status} 正好覆盖它。
 */
@Data
@TableName("user_coupon")
public class UserCoupon {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long couponId;

    /** 券状态：1未使用 2已使用 3已过期 */
    private Integer status;

    /** 用在哪笔订单上（未使用时为 null） */
    private Long orderId;

    private OffsetDateTime receivedAt;

    private OffsetDateTime usedAt;

    private OffsetDateTime expiredAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
