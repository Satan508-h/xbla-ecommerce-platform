package com.xbla.rag.service;

/**
 * 按 {@code traceId} 找一次问答的链路记录，但 {@code qa_log} 里没有。
 *
 * <h3>★ 正常情况下它【不该发生】—— 这决定了前端该怎么对待它</h3>
 *
 * <p>流式问答里 {@code qa_log} 的落库在 {@code sink.onComplete} <b>之前</b>
 * （{@code ChatServiceImpl} 里 {@code saveQaLogStreamSuccess} 在
 * {@code onComplete} 之前一行），所以前端拿到 {@code done} 事件、
 * 拿着里面的 {@code traceId} 立刻来查，<b>那行一定已经在了</b>。
 *
 * <p>⚠️ 但那条顺序是<b>流程自然顺序的副产品，不是显式写下来的契约</b> ——
 * 这段话是 2026-09-24 读代码核实的（{@code docs/07} 记了这次核实）。
 * 所以前端<b>仍然要能容忍它</b>：拿到 404 就在面板上写一句
 * 「这次的技术细节暂不可得」，而不是把整个回答渲染成失败。
 *
 * <p>★ 换句话说：<b>服务端把「没有」说清楚（404 + 一句人话），
 * 客户端决定怎么降级。</b>服务端不该为了「让前端好看」而回一个空对象 ——
 * 那会把「记录没写成功」伪装成「这次什么技术细节都没有」。
 */
public class ChatTraceNotFoundException extends ResourceNotFoundException {

    public ChatTraceNotFoundException(String traceId) {
        super("链路详情", traceId);
    }

    /** 找不到的那个 traceId */
    public String getTraceId() {
        return getId();
    }
}
