package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xbla.rag.common.handler.LongArrayTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 评测题实体（人工标注的评测集）。对应 {@code eval_question} 表。
 *
 * <p>CLAUDE.md 明确要求：<b>评测的标准答案由人工标注，不用另一个模型生成</b>——
 * 否则评测就变成了「模型给自己打分」。
 */
@Data
// ★ autoResultMap = true 不能漏！
//   Long[] 字段用了自定义 TypeHandler，而自定义 Handler 要生效，
//   MyBatis-Plus 必须为这个实体生成 resultMap。
//   不开这个开关的话：写入正常（走了 Handler），但读取时不会走 Handler，
//   表现为「存进去了但查出来是 null」——这种一半能跑的 bug 最难排查。
@TableName(value = "eval_question", autoResultMap = true)
public class EvalQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String questionNo;

    private String question;

    /** ★ 人工标注的正确意图，不是模型预测的 */
    private String intent;

    /** 人工撰写的标准答案 */
    private String expectedAnswer;

    /**
     * 应当被召回的切片 ID 列表，用于计算召回命中率。
     *
     * <p>数据库列类型是 PostgreSQL 数组 {@code BIGINT[]}，
     * Java 侧用 {@code Long[]} 对应。pgjdbc 驱动原生支持数组类型的读写，
     * MyBatis 会把它当 {@code java.sql.Array} 处理。
     *
     * <p>之所以用数组而不是建关联表：这里只是「一组 ID」，
     * 不需要携带额外属性（不像 user_coupon 要记领取时间、状态）。
     * 数组足够表达，查询也直观：{@code WHERE 12 = ANY(expected_chunk_ids)}
     *
     * <p>★ 必须指定 {@code typeHandler}：MyBatis 内置了 {@code Object[]} 的处理器
     * 但<b>没有 {@code Long[]} 的</b>，不指定会报
     * "Type handler was null ... for the javaType ([Ljava.lang.Long;)"。
     */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private Long[] expectedChunkIds;

    @TableField(typeHandler = LongArrayTypeHandler.class)
    private Long[] expectedDocIds;

    private String category;

    /** 难度：1易 2中 3难 */
    private Integer difficulty;

    /** 是否纳入基线评测集，用于 A/B 对比 */
    private Boolean isBaseline;

    /** 题目来源（真实提问 / 人工构造） */
    private String source;

    private String annotatedBy;

    private OffsetDateTime annotatedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
