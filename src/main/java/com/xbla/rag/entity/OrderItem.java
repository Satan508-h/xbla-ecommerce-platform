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
 * 订单明细实体。对应 {@code order_item} 表。
 *
 * <p><b>注意这张表只有 created_at，没有 updated_at 和 deleted。</b>
 * 订单明细一旦生成就不会修改（要改就是整单取消重下），
 * 所以不需要更新时间和软删除。实体类严格照着表来，不要「顺手」加上这两个字段——
 * MyBatis-Plus 会照着实体生成 SQL，多出来的字段会导致「列不存在」错误。
 */
@Data
@TableName("order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long productId;

    private Long skuId;

    /**
     * ★ 快照字段：下单时的商品名。
     *
     * <p>为什么冗余存一份：商品是会变的。如果这里只有 productId，
     * 回看历史订单时 join 到的是「现在的」商品信息——
     * 用户会看到"我明明 99 买的，怎么显示 199"。
     * productId 用于关联查询，快照字段用于展示，两者用途不同，都要保留。
     */
    private String productName;

    /** ★ 快照：下单时的规格 */
    private String specName;

    /** ★ 快照：下单时的单价。商品调价不影响历史订单 */
    private BigDecimal price;

    private Integer quantity;

    /** 小计 = price × quantity */
    private BigDecimal subtotal;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
