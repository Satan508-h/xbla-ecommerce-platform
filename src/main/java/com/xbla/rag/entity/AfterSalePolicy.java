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
 *       ★★ 由 {@code rag.facts.PolicyFactProvider} 读出来，注入 prompt</li>
 * </ul>
 * 两者服务不同场景，缺一不可。
 *
 * <h2>★★ 修正（阶段 5.9，2026-09-20）</h2>
 *
 * <p>上面那句原来是「供 <b>MCP 工具</b>精确查询」—— <b>那是意图，不是事实</b>。
 * 真做的时候才发现路不通：{@code retrieval} 是<b>顶层</b>意图的性质（叶子继承），
 * 售后服务是 {@code retrieval: KB} —— <b>模型在那个意图下根本拿不到任何工具</b>。
 *
 * <p>改成了<b>结构化注入</b>：意图树里 {@code AFTER_SALE.RETURN_EXCHANGE} 声明
 * {@code structured_facts: POLICY}，知识库路径组装 prompt 时按类目查这张表，
 * 把天数拼进 system prompt。完整推理见 {@code docs/08} ADR-067。
 *
 * <p>★ 只注入 {@code returnDays} / {@code exchangeDays}，
 * <b>不注入 {@code conditions}</b> —— 后者<b>已经在知识库里</b>
 * （实测 12 条「附加条件」切片），带进来就是让 prompt 有两份一样的文字。
 * 而天数在 {@code content} 里是<b>散文</b>
 * （「自签收之日起 7 天内支持无理由退货，15 天内支持换货」），
 * 模型从散文里读数字可能把换货的 15 天读成退货的 15 天 ——
 * <b>而那句话读起来完全通顺</b>。这才是这份硬数据存在的全部价值。
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
