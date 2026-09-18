package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 商品参数实体（如"屏幕尺寸 6.7英寸"）。
 *
 * <p>注意区分：这是<b>参数</b>（描述性信息），
 * 和 {@link ProductSku}（可购买的具体型号）是两回事。
 *
 * <p>对应 {@code product_attribute} 表。<b>这张表没有 deleted 字段</b>——
 * 参数改了就改了，不需要保留历史版本。实体类和表结构必须严格一致，
 * 多声明一个不存在的字段，INSERT 时会报「列不存在」。
 */
@Data
@TableName("product_attribute")
public class ProductAttribute {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    /** 参数分组，如"基本参数"、"屏幕"、"摄像头" */
    private String attrGroup;

    /** 参数名，如"屏幕尺寸" */
    private String attrName;

    /** 参数值，如"6.7 英寸" */
    private String attrValue;

    /** 组内排序 */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
