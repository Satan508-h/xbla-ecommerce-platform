package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 库存实体。一个 SKU 对应一条记录。对应 {@code inventory} 表。
 *
 * <p>这张表没有 {@code deleted} —— 库存记录不做软删除，SKU 没了记录也该没。
 */
@Data
@TableName("inventory")
public class Inventory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skuId;

    private Integer totalStock;

    /** 可售库存。MCP 工具「库存查询」读的就是这个字段 */
    private Integer availableStock;

    /** 锁定库存（已下单未付款）。付款后扣减，取消订单则归还到可售 */
    private Integer lockedStock;

    private String warehouse;

    /**
     * 乐观锁版本号。
     *
     * <p>阶段 6 做并发扣减时，SQL 会写成：
     * <pre>{@code
     * UPDATE inventory SET available_stock = available_stock - 1, version = version + 1
     * WHERE id = ? AND version = ?
     * }</pre>
     * 如果两条并发请求拿到同一个 version，只有一条能更新成功（影响行数为 1），
     * 另一条影响行数为 0，说明「有人抢先改了」，重试即可。这样避免覆盖丢失。
     *
     * <p><b>注意</b>：这里没有加 {@code @Version} 注解。
     * MyBatis-Plus 的 {@code @Version} 需要额外注册 {@code OptimisticLockerInnerInterceptor}
     * 才生效，而本项目要在阶段 6 手写这条 SQL（为了讲清楚原理、也为了能自己控制重试）。
     * 到阶段 6 再决定是加拦截器还是手写。
     */
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
