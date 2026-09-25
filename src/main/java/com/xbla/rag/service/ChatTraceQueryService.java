package com.xbla.rag.service;

import com.xbla.rag.dto.TraceDetail;

/**
 * 一次问答的<b>技术细节</b> —— 供阶段 8 前端的「技术细节」面板使用。
 *
 * <h3>★ 它是只读的，而且【零成本】</h3>
 *
 * <p>只查 {@code qa_log} 一行、解析几个 JSONB 列。<b>不调模型、不写库、不改状态</b>。
 * 所以它可以被前端在每一条回答之后调一次。
 *
 * <h3>★★ 它和 {@link ChatHistoryQueryService} 是两件事</h3>
 *
 * <pre>
 *   ChatHistoryQueryService  读 chat_session / chat_message —— 「对话本身」
 *   （本类）                  读 qa_log                     —— 「可观测性记录」
 * </pre>
 *
 * <p>两张表、两种口径。本项目的纪律是<b>同一个概念一条路</b>：
 * 会话正文只在 {@code chat_message} 里，链路细节只在 {@code qa_log} 里，
 * 谁也不去抄对方的字段。所以两个 service 各自读一张表，不合并。
 *
 * <h3>★★★ 为什么不用 {@code /api/debug/mcp/qa-log?traceId=}</h3>
 *
 * <p>因为那是 {@code @Profile("local")} 的 —— 生产 profile 下<b>根本不存在</b>。
 * 而那些端点能无条件消耗 API 额度、能改状态，绝不能跟着公网隧道出去。
 * 所以这里是一个<b>新的、只读的、白名单的</b>出口。
 * 取舍写在 {@link TraceDetail} 的类注释里（没有 {@code errorMsg} 之类）。
 *
 * <h3>★ 前端应该在什么时候调它</h3>
 *
 * <p>流式问答的 {@code done} 事件里带着 {@code traceId}。而
 * {@code qa_log} 的落库在 {@code sink.onComplete} <b>之前</b>
 * （{@code ChatServiceImpl} 里 {@code saveQaLogStreamSuccess} 在
 * {@code onComplete} 之前一行，2026-09-24 读代码核实），
 * 所以拿到 {@code done} 之后<b>立刻</b>来查，那行已经在了。
 *
 * <p>⚠️ 但那条顺序是流程的自然顺序，不是显式契约 ——
 * 所以前端仍要能容忍 {@link ChatTraceNotFoundException}。
 */
public interface ChatTraceQueryService {

    /**
     * 按 {@code traceId} 取这次问答的技术细节。
     *
     * @throws ChatTraceNotFoundException {@code qa_log} 里没有这个 traceId
     */
    TraceDetail traceDetail(String traceId);
}
