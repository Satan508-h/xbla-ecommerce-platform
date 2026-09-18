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
 * 对话消息实体。对应 {@code chat_message} 表。
 *
 * <p><b>只增不改不删</b>——没有 updated_at，也没有 deleted。
 * 消息一旦写入就是审计记录，阶段 7 评测要回溯对话原文。
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** 角色：1用户 2助手 3系统 */
    private Integer role;

    private String content;

    private Integer tokenCount;

    /** 识别出的意图（仅助手消息有） */
    private String intent;

    /** 意图置信度 0~1。低于阈值会触发澄清反问 */
    private BigDecimal intentConfidence;

    /**
     * 引用的知识片段：切片 ID + 相似度分数。
     *
     * <p>★ <b>注意注解里的双引号。</b>{@code REFERENCES} 是 SQL 标准保留字
     * （外键约束就用它），数据库建表时列名写的是 {@code "references"}（带引号）。
     * MyBatis-Plus 默认会用裸的 {@code references} 拼 SQL，
     * PostgreSQL 解析到它会直接语法报错。
     *
     * <p>{@code @TableField("\"references\"")} 让生成的 SQL 里带上双引号，
     * 和数据库里的列名精确匹配。
     *
     * <p>（对比 {@code app_user} 的处理：表名可以换一个更安全的名字，
     * 但列名换成 {@code refs} 之类的反而让语义变模糊，
     * 所以这里选择保留原名 + 加引号。代价是每一处引用都要记得带引号。）
     */
    @TableField("\"references\"")
    private String references;

    /** ★ 实际生效的供应商。降级后可能不是 P0，这是阶段 2 验收标准的直接证据 */
    private String provider;

    /** 实际使用的模型 */
    private String model;

    private Integer latencyMs;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
