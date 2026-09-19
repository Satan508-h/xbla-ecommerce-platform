package com.xbla.rag.service;

import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;

/**
 * 问答业务编排。
 *
 * <p>它负责把「一次提问」串成完整的一条链路：
 * <pre>
 * 建会话 → 存用户消息 → 调模型（含降级）→ 存助手消息 → 写 qa_log
 * </pre>
 *
 * <p><b>★ 本接口是同步的，不涉及任何异步/SSE 概念。</b>
 * 流式推送的线程调度由 controller 负责 ——
 * 这样 service 层就完全不用知道 {@code SseEmitter} 的存在，
 * 单元测试时也不用起 Web 环境。
 */
public interface ChatService {

    /**
     * 非流式问答。
     *
     * @param request 提问
     * @param userId  ★ 身份，来自 {@code X-Xbla-User-Id} 请求头，<b>可以为 null</b>。
     *                <p>它只被<b>工具那一条路</b>用到（查「我的订单」时得知道
     *                「我」是谁）。为 null 时其余路径一切照旧，
     *                而工具路径会回一句「需要先知道你是哪位」——
     *                <b>不是 401</b>，理由见 {@code ChatController.resolveUserId}。
     *                <p>⚠️ 它是个<b>方法参数而不是对象状态</b>，
     *                同 {@code McpToolGateway} 那条：身份是「每次调用都可能不同」
     *                的事实，变成字段就为「用错身份」开了口子，
     *                而那个错误的形态是并发下 A 看到 B 的数据、日志里一切正常。
     * @return 回答及本次调用的全部元信息
     * @throws com.xbla.rag.client.ModelCallException 模型全链路失败
     */
    ChatAskResponse ask(ChatAskRequest request, Long userId);

    /**
     * 流式问答。正文通过 {@code sink} 逐段推出。
     *
     * <p>⚠️ <b>它还没有 {@code userId} 参数 —— 而且这不是遗漏。</b>
     * 阶段 5.8 只把工具接进了非流式路径（用户拍板的边界），
     * 所以这条路上的 {@code retrieval = TOOL} 意图会走成一条
     * <b>没有工具的普通问答</b>。
     *
     * <p>★ 5.9 接流式时要做的第一件事就是<b>补上这个参数</b>。
     * 把它写在签名旁边，是因为「两条平行路径少改了一条」是本项目
     * 已经踩过的坑（见 {@code ADR-047}），而那种漏改
     * <b>编译能过、测试能绿、只有演示的时候才看得出来</b>。
     *
     * <p><b>方法返回时代表流程结束</b>（成功或失败），
     * 但结束的「通知」是通过 {@link ChatStreamSink#onComplete} /
     * {@link ChatStreamSink#onError} 发出的，而不是靠返回值或异常 ——
     * 因为调用方（controller 的推送线程）需要区分
     * 「正常收尾」和「异常收尾」来做不同的清理。
     *
     * @param request 提问
     * @param sink    事件接收器，由 controller 适配到 SseEmitter
     */
    void askStream(ChatAskRequest request, ChatStreamSink sink);

    /**
     * 流式事件的接收端。
     *
     * <p>抽成接口而不是直接用 {@code SseEmitter}，是为了让 service 层
     * 不依赖 Spring MVC —— 依赖方向保持为
     * {@code controller → service → client}，而不是反过来。
     *
     * <p><b>实现方注意</b>：这些方法会在<b>推送线程</b>上被调用，
     * 不是处理 HTTP 请求的那个线程。
     * 所以不要在里面用 ThreadLocal，也不要做重活（会阻塞上游读取）。
     */
    interface ChatStreamSink {

        /**
         * 开始。在调用模型<b>之前</b>触发，让前端立刻拿到 traceId，
         * 而不是对着空白页面等模型的首字节。
         */
        void onStart(String traceId, String sessionNo);

        /** 收到一段正文增量 */
        void onDelta(String delta);

        /** 正常结束，携带完整的元信息（用量、成本、耗时、降级轨迹） */
        void onComplete(ChatAskResponse summary);

        /**
         * 失败结束。
         *
         * @param message 面向用户的错误说明
         * @param traceId 链路 ID，方便用户报错时提供、我们反查 qa_log
         */
        void onError(String message, String traceId);
    }
}
