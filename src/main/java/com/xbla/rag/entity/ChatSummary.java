package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 会话摘要实体。对应 {@code chat_summary} 表。
 *
 * <p>这是<b>会话记忆的摘要层</b>。策略是双层：
 * <pre>
 *   最近 3 轮对话  → 保留原文（细节不能丢，比如"我要买红色的"）
 *   更早的对话     → 压缩成摘要（保留语义，省 token）
 * </pre>
 *
 * <p>为什么两层都要：只保留摘要会丢细节；只保留原文的话，
 * 聊 20 轮后每轮都要把全部历史塞给模型，token 爆炸、变慢变贵。
 * 两层是精度和成本的折中。
 */
@Data
@TableName("chat_summary")
public class ChatSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** 1段落级摘要 2会话级摘要 */
    private Integer summaryLevel;

    /**
     * 摘要覆盖的消息区间 [startMessageId, endMessageId]。
     *
     * <p>记录区间是为了保证：原文窗口往前滚动时，
     * 新纳入摘要的消息不会和已有摘要重复计算。
     */
    private Long startMessageId;

    private Long endMessageId;

    private String summary;

    private Integer tokenCount;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
