package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 售后政策实体。对应 {@code after_sale_policy} 表。
 *
 * <p>这张表是<b>「结构化 + 非结构化双轨」的典型例子</b>：
 * <ul>
 *   <li>{@code content}（自由文本）→ 阶段 3 会切分向量化进知识库，
 *       回答"我拆封了还能退吗"这类答案藏在正文里的问题</li>
 *   <li>{@code returnDays} / {@code exchangeDays}（结构化字段）→
 *       供 MCP 工具精确查询，"退货政策是几天"直接 SELECT，比向量检索更准更快</li>
 * </ul>
 * 两者服务不同场景，缺一不可。
 */
@Data
@TableName("after_sale_policy")
public class AfterSalePolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String policyNo;

    /** 适用类目，null 表示通用 */
    private String category;

    private String title;

    /** 政策正文，阶段 3 会被切分向量化进 kb_chunk */
    private String content;

    /** 可退货天数 */
    private Integer returnDays;

    /** 可换货天数 */
    private Integer exchangeDays;

    /** 附加条件，如"拆封后不支持无理由退货" */
    private String conditions;

    private OffsetDateTime effectiveFrom;

    private OffsetDateTime effectiveTo;

    /** 版本号。政策改版时新增记录而非覆盖，便于评测追溯 */
    private Integer version;

    /** 1生效 0失效 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Integer deleted;
}
