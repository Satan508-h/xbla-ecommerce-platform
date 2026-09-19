package com.xbla.rag.controller;

import com.xbla.rag.common.ApiResponse;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.mcp.protocol.McpProtocol;
import com.xbla.rag.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 问答接口。
 *
 * <p>按 CLAUDE.md 的约定，controller 层<b>只做参数校验和响应封装</b>，
 * 业务编排全部在 {@link ChatService} 里。这里唯一稍微复杂的东西是
 * SSE 的线程调度 —— 那属于 Web 层职责，不该下沉到 service。
 *
 * <h3>两个接口</h3>
 * <ul>
 *   <li>{@code POST /api/chat} —— 非流式，一次拿回完整回答</li>
 *   <li>{@code GET /api/chat/stream} —— 流式，打字机效果</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    /** SSE 连接的最长存活时间。★ 不能省，见下面 chatStream 的说明 */
    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final ChatService chatService;
    private final ThreadPoolTaskExecutor sseExecutor;

    public ChatController(ChatService chatService,
                          @Qualifier("sseExecutor") ThreadPoolTaskExecutor sseExecutor) {
        this.chatService = chatService;
        this.sseExecutor = sseExecutor;
    }

    // ============================================================
    // 非流式
    // ============================================================

    /**
     * 非流式问答。
     *
     * <p>{@code @Valid} 让 {@link ChatAskRequest} 上的校验注解生效，
     * 校验失败会抛 {@code MethodArgumentNotValidException}，
     * 由 {@code GlobalExceptionHandler} 统一转成 {@link ApiResponse#fail}。
     */
    @PostMapping("/chat")
    public ApiResponse<ChatAskResponse> chat(@Valid @RequestBody ChatAskRequest request,
                                             HttpServletRequest http) {
        return ApiResponse.ok(chatService.ask(request, resolveUserId(http)));
    }

    /**
     * 从请求头取身份 —— ★ 和 {@code /mcp} 用的是<b>同一个头名</b>。
     *
     * <h3>为什么身份走头，不走请求体</h3>
     *
     * <p>走 body 的话，同一个身份就有了两个来源（{@code /api/chat} 的 body 字段
     * 和 {@code /mcp} 的请求头），两边不一致时以谁为准会变成一个新问题 ——
     * 而那个问题的正确答案永远是「头」，因为头是<b>传输层</b>的，
     * 请求体是<b>调用方可以随便填</b>的。
     *
     * <p>★ 这里是「客户端 → 我们」，{@code McpController} 那边是
     * 「我们 → 工具服务」。两跳用同一个头名，意味着
     * {@code ChatServiceImpl} 和 {@code SdkMcpToolGateway} 之间
     * <b>不需要任何身份转换</b> —— 少一次转换就少一个出错的地方。
     *
     * <h3>⚠️ 它的边界</h3>
     *
     * <p>和 {@code McpToolContext} 里写的一样：<b>这个头是明文未签名的，不是认证。</b>
     * 做到的是「身份不进模型的可控范围」，另一半（验证它是真的）属于认证。
     *
     * <h3>★ 为什么「没有身份」不是 401</h3>
     *
     * <p>因为<b>只有工具那一条路需要身份</b>。知识库问答、意图分类、会话记忆
     * 都不需要知道你是谁 —— 对它们回 401 会把整个服务变成需要登录的，
     * 而演示页和现有的每一个 curl 命令都没有带头。
     *
     * <p>所以：<b>头可以缺席，缺了只是查不了「我的」东西</b>，
     * 由 {@code ToolLoop} 在那条路上回一句诚实的说明。
     * <b>必需的校验放在真正需要它的地方</b>，而不是入口处一刀切。
     */
    private static Long resolveUserId(HttpServletRequest http) {
        String raw = http.getHeader(McpProtocol.HEADER_USER_ID);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ============================================================
    // 流式
    // ============================================================

    /**
     * 流式问答。
     *
     * <h3>为什么用 GET 而不是 POST？</h3>
     * 因为浏览器原生的 {@code EventSource} 只支持 GET。
     * 用 GET 能让前端演示页只有十几行代码，把注意力放在「打字机效果」
     * 这个真正要验证的东西上。
     *
     * <p>代价是问题文本要放进 URL query，受长度限制（中文会被百分号编码成
     * 3 倍字节）。阶段 2 的问题是短句，够用。
     * <b>生产环境应该换成 POST + fetch + ReadableStream 手写解析</b>，
     * 这个取舍已记录在 {@code docs/08-技术决策记录(ADR).md}。
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam("question") String question,
                                 @RequestParam(value = "sessionNo", required = false)
                                 String sessionNo) {

        // ★★ 超时参数不能省 ★★
        //    Tomcat 的异步请求超时默认是 30 秒。不显式指定的话，
        //    任何超过 30 秒的流式回答都会在中间被无声掐断，
        //    日志里只有一句 "Async request timed out"，没有异常栈。
        //    application.yml 里还有一处全局兜底（spring.mvc.async.request-timeout）。
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        ChatAskRequest request = new ChatAskRequest(sessionNo, question, null);

        // ★★ 必须用专用线程池 ★★
        //    绝不能用 CompletableFuture.runAsync 而不传 executor ——
        //    那会用 ForkJoinPool.commonPool()，并行度只有「核数-1」，
        //    几个并发的阻塞式模型调用就能把它占满，导致整个 JVM
        //    所有依赖 commonPool 的功能一起僵死。详见 AsyncConfig。
        sseExecutor.execute(() -> runStream(emitter, request));

        return emitter;
    }

    /** 在推送线程上执行的流式流程 */
    private void runStream(SseEmitter emitter, ChatAskRequest request) {
        // 客户端断开的标志。一旦置位就停止一切推送 ——
        // 上游流也会随之关闭，模型那边不再产生 token，计费停止。
        AtomicBoolean closed = new AtomicBoolean(false);

        ChatService.ChatStreamSink sink = new ChatService.ChatStreamSink() {

            @Override
            public void onStart(String traceId, String sessionNo) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("traceId", traceId);
                meta.put("sessionNo", sessionNo);
                // ★ 立刻推一个 meta 事件，让前端马上有反馈，
                //   而不是对着空白页等模型的首字节（可能好几秒）
                send("meta", meta);
            }

            @Override
            public void onDelta(String delta) {
                send("delta", Map.of("v", delta));
            }

            @Override
            public void onComplete(ChatAskResponse summary) {
                send("done", summary);
                if (!closed.get()) {
                    emitter.complete();
                }
            }

            @Override
            public void onError(String message, String traceId) {
                send("failed", Map.of("message", message, "traceId", traceId));
                if (!closed.get()) {
                    emitter.complete();
                }
            }

            /**
             * 统一的发送出口。
             *
             * <p>★ 推送失败时<b>必须往外抛</b>，不能默默吞掉。
             * 因为抛出去才能让调用链层层展开：
             * {@code client} 的 onDelta 捕获到异常 → 包装成 CLIENT_ABORTED
             * → 关闭上游流 → 模型停止产出 → 停止计费。
             *
             * <p>如果在这里把异常吞了，上游会继续读到流结束 ——
             * 用户早已关闭页面，我们还在为一个没人看的回答付钱。
             */
            private void send(String event, Object payload) {
                if (closed.get()) {
                    throw new IllegalStateException("SSE 连接已关闭");
                }
                try {
                    // ★ payload 用对象 + APPLICATION_JSON，不用纯 String。
                    //   纯 String 会走 StringHttpMessageConverter，它的默认
                    //   字符集不是 UTF-8，中文会变成问号；
                    //   而且正文里的换行符会破坏 SSE 的分帧结构。
                    //   用 JSON 一次解决这两个问题。
                    emitter.send(SseEmitter.event()
                            .name(event)
                            .data(payload, MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    closed.set(true);
                    throw new IllegalStateException("SSE 推送失败：" + e.getMessage(), e);
                }
            }
        };

        try {
            chatService.askStream(request, sink);
        } catch (Exception e) {
            // service 内部已经把失败写进 qa_log 并调用过 onError 了，
            // 走到这里通常是「连 onError 都发不出去」，记日志即可
            log.debug("流式流程异常退出: {}", e.getMessage());
            if (!closed.get()) {
                emitter.completeWithError(e);
            }
        }
    }
}
