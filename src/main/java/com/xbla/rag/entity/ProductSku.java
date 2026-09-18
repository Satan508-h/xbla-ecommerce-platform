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
 * 商品 SKU 实体（可购买的具体型号，如"iPhone 17 256G 星空黑"）。
 *
 * <p>对应 {@code product_sku} 表。
 */
@Data
@TableName("product_sku")
public class ProductSku {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属商品（外键 → product.id） */
    private Long productId;

    /** SKU 编号，对外展示用 */
    private String skuNo;

    /** 规格描述，如"256G 星空黑" */
    private String specName;

    /**
     * 结构化规格，如 {@code {"颜色":"星空黑","存储":"256G"}}。
     *
     * <p><b>为什么类型是 String 而不是 Map？</b>
     * 数据库列是 {@code JSONB}。Java 侧可以有三种选择：
     * <ul>
     *   <li>{@code String} —— 最简单，读写时自行序列化/反序列化。本阶段用这个。</li>
     *   <li>{@code Map<String,Object>} —— 需要额外配置 JacksonTypeHandler，
     *       并在 {@code @TableField} 上声明 {@code typeHandler}，否则会报类型转换错误。</li>
     *   <li>自定义 Java 对象 —— 需要自定义 TypeHandler。</li>
     * </ul>
     * 阶段 1 只是建表和灌数据，用 String 最直接。
     * 阶段 4 做规格对比需要按 key 取值时，再换成 Map + JacksonTypeHandler。
     */
    private String specJson;

    private BigDecimal price;

    private String barcode;

    /** 1可售 0停售 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Integer deleted;
}
