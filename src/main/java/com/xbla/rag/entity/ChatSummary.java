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
 * <p>这是<b>会话记忆的摘要层</b>（阶段 5.6 起才第一次真正写入 ——
 * 本表从 V5 建好到那时一直是 0 行）。策略是双层：
 * <pre>
 *   最近 N 轮对话  → 保留原文（细节不能丢，比如"我要买红色的"）
 *   更早的对话     → 压缩成摘要（保留语义，省 token）
 * </pre>
 *
 * <p>为什么两层都要：只保留摘要会丢细节；只保留原文的话，
 * 聊 20 轮后每轮都要把全部历史塞给模型，token 爆炸、变慢变贵。
 * 两层是精度和成本的折中。
 *
 * <p>★ 两者的接缝由 {@link #endMessageId} 这个<b>游标</b>保证：
 * 摘要覆盖 {@code [startMessageId, endMessageId]}，
 * 窗口原文从它的下一条开始。<b>中间不能有谁都不覆盖的消息</b> ——
 * 那些消息会永远消失，而且没有任何报错。详见 ADR-048。
 */
@Data
@TableName("chat_summary")
public class ChatSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /**
     * 1段落级摘要 2会话级摘要。
     *
     * <p>⚠️ <b>5.6 实际恒写 1，level 2 是已知不用的死列。</b>
     * 单行滚动意味着每个会话永远只有一行摘要，没有第二层可言。
     *
     * <p>为什么不删这一列：删列要动 V5 迁移，而 V1–V5 一个字都不能改
     * （{@code validate-on-migrate: true}）。保留但在此说清实情 ——
     * 不留一个「看起来以后会用」的坑。见 ADR-051。
     */
    private Integer summaryLevel;

    /**
     * 摘要覆盖区间的起点。<b>一经写入就不再变</b> ——
     * 摘要是<b>累积</b>的（新摘要 = 合并(旧摘要, 新增对话)），
     * 所以它始终覆盖 {@code [start, 游标]} 这一整段。
     */
    private Long startMessageId;

    /**
     * ★★ <b>摘要游标</b> —— 覆盖到哪一条为止。不是「写了多少字」的计数器。
     *
     * <p>它唯一的工作是让「还有没有没压的」变成一次纯比较：
     * <pre>
     *   窗口起点 W  &gt;  endMessageId + 1   →  中间那段谁都不覆盖，该压了
     *   窗口起点 W  == endMessageId + 1   →  严丝合缝
     * </pre>
     *
     * <p>⚠️ {@code (endMessageId, W)} 之间的消息<b>永远不会被补上</b> ——
     * 它们已经掉出了窗口。所以压缩的终点必须锚在「窗口起点 - 1」，
     * 而不是「溢出几条压几条」。见 ADR-048。
     */
    private Long endMessageId;

    private String summary;

    /**
     * ⚠️ <b>5.6 实际恒写 NULL</b>，这是刻意的。
     *
     * <p>这一列的含义是「这段摘要占多少 prompt 预算」，
     * 而 {@code usage.completionTokens} <b>包含推理 token</b>
     * （实测 {@code deepseek-flash} 上占输出 87%）—— 存进去会让它系统性偏大，
     * 变成一个「有值但用途错」的列，比 NULL 更危险。同 ADR-010 原则。
     *
     * <p>所以 {@code WHERE token_count IS NULL} <b>不是</b>故障信号。
     * 这次调用的真实花费在 {@code SessionSummarizer} 的 DEBUG 日志里。
     */
    private Integer tokenCount;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
