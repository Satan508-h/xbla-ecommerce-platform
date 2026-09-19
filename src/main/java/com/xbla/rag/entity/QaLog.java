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
 * ★★★ 问答全链路日志。本项目最重要的实体。★★★
 *
 * <p>对应 {@code qa_log} 表。CLAUDE.md 第 4 条：所有对数据库的写操作要能追溯到
 * qa_log，因为<b>评测数据来源于此</b>。阶段 7 的所有指标都从这张表算出来。
 *
 * <p><b>只增不改不删</b>——没有 updatedAt，没有 deleted。
 */
@Data
@TableName("qa_log")
public class QaLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 链路追踪 ID，串联一次问答的所有环节 */
    private String traceId;

    private Long sessionId;

    private Long userId;

    /** 原始提问 */
    private String question;

    /** 查询重写后的问题（阶段 4） */
    private String rewrittenQuestion;

    private String intent;

    private BigDecimal intentConfidence;

    /**
     * ★ 完整召回链路中间结果。阶段 4 验收标准第 1 条要用。
     *
     * <p>结构大致是：
     * <pre>{@code
     * {
     *   "vector_hits":  [{"chunk_id":12,"score":0.87}, ...],        向量召回原始结果
     *   "keyword_hits": [{"chunk_id":45,"score":3.2},  ...],        关键字召回原始结果
     *   "fused":        [{"chunk_id":12,"rrf_score":0.032}, ...],   RRF 融合后
     *   "reranked":     [{"chunk_id":45,"rerank_score":0.95}, ...], 重排后
     *   "final_top_k":  [45, 12, 8]                                最终送进 prompt 的
     * }
     * }</pre>
     *
     * <p><b>没有这个字段，整个评测体系就无从谈起</b>——你无法回答
     * "召回失败是因为向量检索没找到，还是重排排错了，还是切分粒度不对"。
     * 把中间结果落库，是让检索质量「可归因」的前提。
     */
    private String retrievalDetail;

    private String finalAnswer;

    /** 引用的切片。列名是保留字，处理方式见 {@link ChatMessage#references} */
    @TableField("\"references\"")
    private String references;

    /** 实际生效的供应商（降级后可能不是 P0） */
    private String provider;

    private String model;

    /**
     * ★ 降级事件记录。阶段 2 验收标准第 2 条查的就是这个字段。
     *
     * <p>结构：{@code [{"from":"deepseek-p0","to":"siliconflow-p1","reason":"circuit_open","at":"..."}]}
     */
    private String degradationEvents;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /** 本次调用成本 */
    private BigDecimal cost;

    /**
     * ★ 延迟拆成四段。
     *
     * <p>为什么不全记成一个 total：只记 total 的话，P95 变慢了你也只能猜。
     * 拆开之后既能量化总延迟，也能说明<b>慢在哪一段</b>——
     * 是检索慢、重排慢，还是模型生成慢，优化方向完全不同。
     */
    private Integer retrievalLatencyMs;

    private Integer rerankLatencyMs;

    private Integer llmLatencyMs;

    private Integer totalLatencyMs;

    /**
     * 本次问答实际发生的 MCP 工具调用（阶段 5.8），JSONB 数组，按发生顺序。
     *
     * <p>元素形状：{@code {"round":1,"tool":"query_order_status","isError":false,"detail":"chars=87"}}
     *
     * <p>★★ <b>{@code NULL} 表示「一次都没调」，不是空数组。</b>
     * 和 {@code references} / {@code degradation_events} 同一条约定。
     *
     * <p>★ 为什么它必须是独立的一列：没有它，
     * 「工具意图答错了」和「工具根本没被调用」<b>在数据上完全一样</b> ——
     * 都是 {@code status=1}、有 {@code provider}、有 {@code final_answer}。
     * 而这两种情况的排查方向正相反。详见 {@code V8} 迁移的注释。
     *
     * <p>⚠️ 它<b>不存工具返回的内容</b>：那是订单数据，没有理由在这里
     * 再存一份；要复现就按同一个参数重调一次（工具是只读的）。
     */
    private String toolCalls;

    // ================================================================
    // status 的三个取值（与 V5 / V7 的 CHECK 约束一致）
    //
    // ★ 用常量而不是裸数字：这三个值决定阶段 7 能不能正确地筛出
    //   「可评测的问答」，写错一个数字不会报错，只会让统计悄悄偏。
    //   （同 KbDocument.STATUS_* 的做法。）
    // ================================================================

    /** 成功生成了答案。★ 阶段 7 的检索类与答案质量类指标<b>只统计这一种</b> */
    public static final int STATUS_SUCCESS = 1;

    /** 模型链路失败 */
    public static final int STATUS_FAILED = 2;

    /**
     * 澄清反问（阶段 5.3）。
     *
     * <p>既不是成功也不是失败：系统判定用户那句话信息不足，
     * <b>没有检索、也没有调生成模型</b>，直接反问了一句。
     *
     * <p>补这个取值的原因是它两边都不属于 —— 塞进任何一个都会污染统计：
     * <ul>
     *   <li>塞进「成功」→ {@code final_answer} 里躺着一句反问，
     *       答案质量指标会给它打分，检索指标会把它算成「没召回到」</li>
     *   <li>塞进「失败」→ 阶段 7 会把「系统正确识别出该反问」
     *       统计成「模型挂了」</li>
     * </ul>
     * 详见 V7 迁移的注释。
     */
    public static final int STATUS_CLARIFY = 3;

    /** 1成功生成答案 2模型链路失败 3澄清反问。取值见上面的常量 */
    private Integer status;

    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
