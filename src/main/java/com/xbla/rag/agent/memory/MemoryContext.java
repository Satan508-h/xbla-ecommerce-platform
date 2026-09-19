package com.xbla.rag.agent.memory;

import com.xbla.rag.client.dto.ChatRequest;

import java.util.List;

/**
 * 一次问答要用到的<b>全部</b>会话记忆（阶段 5.6）。
 *
 * <pre>
 *   送给模型的上下文 = 【更早对话的摘要】 + 【最近 N 轮原文】
 * </pre>
 *
 * <h2>★ 为什么摘要和窗口必须装在同一个对象里返回</h2>
 *
 * <p>因为它们<b>必须来自同一个时间点</b>。
 *
 * <p>摘要覆盖的是「窗口之前的消息」，窗口覆盖的是「最近 N 轮原文」——
 * 两者是<b>同一根时间轴上的相邻两段</b>，边界必须严丝合缝：
 *
 * <pre>
 *   ├──────── 摘要 ────────┼──────── 窗口原文 ────────┤
 *   msg 1 ......... msg N  │  msg N+1 ......... msg M
 *                          ↑
 *                    两者的接缝
 * </pre>
 *
 * <p>如果分两次读（先读摘要、再读窗口），中间可能有新的消息落库，
 * 接缝就会错位 —— 错位的方向只有两种，都不好：
 * <ul>
 *   <li><b>空洞</b>：接缝处的几条消息既不在摘要里、也不在窗口里，
 *       而且<b>永远不会被补上</b>（它们已经「掉出窗口」了）；</li>
 *   <li><b>重叠</b>：同一条消息既在摘要里又在窗口里，模型看到两遍。</li>
 * </ul>
 *
 * <p>本项目的做法是<b>一次调用同时产出两者</b>（{@link ConversationMemory#load}），
 * 并且读窗口在前、读摘要在后 —— 这样即使真有并发写入，
 * 最坏结果也只是「摘要偏旧」（重叠），不会出现空洞。
 * 见 {@link ConversationMemory} 的类注释第五节。
 *
 * @param sessionSummary 更早对话的摘要。没有时是 {@code null}（<b>不是空串</b> ——
 *                       「没有摘要」和「摘要是空的」是两件事，同 ADR-010 原则）
 * @param history        最近若干轮的原文，按时间正序，可直接塞进
 *                       {@code ChatRequest.history}。<b>永不为 null</b>
 */
public record MemoryContext(String sessionSummary, List<ChatRequest.Turn> history) {

    /** 什么都没有：新会话，或者历史读取失败 */
    public static final MemoryContext EMPTY = new MemoryContext(null, List.of());

    public MemoryContext {
        history = history == null ? List.of() : List.copyOf(history);
    }

    public boolean hasSummary() {
        return sessionSummary != null && !sessionSummary.isBlank();
    }

    public boolean hasHistory() {
        return !history.isEmpty();
    }

    /**
     * 有没有任何上下文可给。
     *
     * <p>调用方判断「要不要给 prompt 加会话历史约束」用的就是它 ——
     * <b>摘要也是历史</b>，所以不能只看 {@link #hasHistory()}。
     */
    public boolean hasAny() {
        return hasSummary() || hasHistory();
    }

    /** 日志用的一行摘要，不打印正文（摘要可能几百字） */
    public String summary() {
        return "memory[summary=" + (hasSummary() ? sessionSummary.length() + "字" : "无")
                + ", history=" + history.size() + "条]";
    }
}
