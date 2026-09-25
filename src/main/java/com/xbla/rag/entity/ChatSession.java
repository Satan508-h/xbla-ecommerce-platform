package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 对话会话实体。对应 {@code chat_session} 表。
 */
@Data
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionNo;

    /** 可为空，支持匿名会话 */
    private Long userId;

    /** 会话标题，由首轮问题生成 */
    private String title;

    private Integer messageCount;

    private OffsetDateTime lastActiveAt;

    /** 1进行中 2已结束 */
    private Integer status;

    /**
     * 悬着的澄清反问状态（阶段 9.4）——
     * 「上一轮我们问了什么、还没拿到回答」。
     *
     * <p>数据库列是 {@code JSONB}，Java 侧是 {@code String} ——
     * 和 {@code qa_log.intent_plan} / {@code eval_question.turns}
     * 是同一条约定（JSONB 存、String 进出，靠 Jackson 手工编解码）。
     * ★ 刻意<b>不</b>加 {@code @TableName(autoResultMap = true)} 换成 Map：
     * 那会同时改变本表<b>所有</b>查询的 ResultMap 形态（{@code KbChunk} 记过这个坑）。
     *
     * <p>★★ <b>null = 没有待澄清</b>（常态），不是空对象 ——
     * 同 {@code tool_calls} / {@code intent_plan} 的约定。见 V15 迁移第四节。
     *
     * <p>★★ 生命周期是<b>读后即清</b>：澄清轮写入，紧接着的下一轮
     * 读出来并立刻清空 ⇒ 它是一次性的，不存在「坏状态永久卡住会话」
     * （ADR-049 那个形态）。
     *
     * <p>★ 它只在<b>分类那一次调用</b>里被用（拼进 prompt 的一小段结构化状态），
     * 不进生成 prompt、不进检索 —— 见 {@code PendingClarify} 的类注释。
     */
    private String pendingClarify;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Integer deleted;
}
