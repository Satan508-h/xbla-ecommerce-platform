package com.xbla.rag.controller;

import com.xbla.rag.common.ApiResponse;
import com.xbla.rag.common.TraceId;
import com.xbla.rag.config.RateLimitProperties;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.mcp.protocol.McpProtocol;
import com.xbla.rag.ratelimit.ChatAdmissionService;
import com.xbla.rag.service.CallContext;
import com.xbla.rag.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * <b>回答阶段</b>的预算：从「拿到名额」到「最后一段正文」最多允许多久。
     *
     * <p>★ 阶段 6 起它<b>不再是整个 SSE 连接的超时</b> —— 见 {@link #sseTimeoutMs()}。
     */
    private static final long ANSWER_BUDGET_MS = 180_000L;

    private final ChatService chatService;
    private final ChatAdmissionService admission;
    private final RateLimitProperties rateLimitProperties;

    public ChatController(ChatService chatService,
                          ChatAdmissionService admission,
                          RateLimitProperties rateLimitProperties) {
        this.chatService = chatService;
        this.admission = admission;
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * SSE 连接的总超时 —— <b>必须覆盖「排队 + 回答」两段</b>。
     *
     * <h3>★★ 为什么不能沿用固定的 180 秒</h3>
     *
     * <p>阶段 6 之前，180 秒就是「一次回答最多能有多久」，够用。
     * 有了排队之后这条连接里发生了两件事：
     *
     * <pre>
     *   排队上限 120 秒  +  回答预算 180 秒  =  最坏 300 秒
     * </pre>
     *
     * <p>如果连接超时仍然是 180 秒，那么一个<b>排满 120 秒、然后回答正常需要 90 秒</b>
     * 的请求，会在第 180 秒被 Tomcat <b>无声掐断</b> ——
     * 用户看到的是「答到一半突然没了」，日志里只有一句
     * {@code Async request timed out}，<b>没有任何异常栈</b>。
     * 而这恰恰是最容易发生在「系统正忙」的时候，也就是最需要它别出问题的时候。
     *
     * <p>★ 所以超时是<b>算出来的</b>，不是拍的：排队预算 + 回答预算。
     * 关掉限流时排队那段是 0，它就退化成原来的 180 秒 —— 和阶段 6 之前一致。
     */
    private long sseTimeoutMs() {
        long queueBudget = rateLimitProperties.isEnabled()
                ? rateLimitProperties.getQueueTimeout().toMillis()
                : 0L;
        return queueBudget + ANSWER_BUDGET_MS;
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
     *
     * <h3>★★★ 阶段 6.7：它和流式走【同一套排队】</h3>
     *
     * <p>加这个之前，非流式是<b>唯一一条绕过限流的路</b>：它直接调
     * {@code chatService.ask(...)}，不排队、不抢名额 —— 而它压的是
     * <b>同一批下游</b>（同一个模型供应商、同一个 Hikari 连接池）。
     *
     * <pre>
     *   8 个流式请求占满了名额，同时来 50 个非流式请求
     *       → 旧行为：50 个直接冲向下游 → 429 / 连接池 30 秒超时
     *       → 新行为：50 个排队等名额，等不到的收到一句「前面人太多」
     * </pre>
     *
     * <p>★ <b>共用还有第二个理由：否则限流就没有意义了。</b>
     * 一条能绕过的限流，只是「对配合的调用方生效的限流」。
     *
     * <h3>★ 它和流式的三点不同，都是刻意的</h3>
     *
     * <pre>
     *   ① 阻塞等待，不推位置 —— HTTP 请求-响应没有通道可以推
     *   ② 排队预算 30 秒（不是流式的 120 秒）—— 因为它只能转圈，见 nonStreamQueueTimeout
     *   ③ 超时/队列满时抛异常 → HTTP 503，而不是推一个 failed 事件
     * </pre>
     *
     * <p>★ 但<b>「什么算被拒绝」的判据是同一个</b>（都来自
     * {@code ChatAdmissionService.giveUp()}），所以两条路在 qa_log 里
     * 留下的痕迹完全一致：一样的 {@code status=4}、一样的 {@code queue_ms}。
     *
     * <h3>★ 校验在排队【之前】</h3>
     *
     * <p>{@code @Valid} 由 Spring 在进入方法体之前执行，所以一个参数非法的请求
     * <b>不会占用任何队列位置</b>。这个顺序是对的：为一个注定要 400 的请求
     * 去排队，既浪费名额也浪费用户的时间。
     *
     * @throws com.xbla.rag.ratelimit.QueueRejectedException 队列满 / 等太久 →
     *        由 {@code GlobalExceptionHandler} 转成 HTTP 503 + {@code Retry-After}
     */
    @PostMapping("/chat")
    public ApiResponse<ChatAskResponse> chat(@Valid @RequestBody ChatAskRequest request,
                                             HttpServletRequest http) {
        // ★★ traceId 在【排队之前】生成，和流式路径同一个理由 ——
        //    「排队等了 20 秒」和「回答花了 3 秒」必须记在同一条记录上。
        String traceId = TraceId.newId();
        Long userId = resolveUserId(http);

        ChatAskResponse response = admission.submitAndWait(
                new ChatAdmissionService.Admission(traceId, request.question(), userId),
                // ★ 三参重载：沿用排队层那个 traceId，并把 queue_ms / queue_position
                //   一起带进 ChatService 落进 qa_log。
                ctx -> chatService.ask(request, userId, ctx));

        return ApiResponse.ok(response);
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
                                 String sessionNo,
                                 HttpServletRequest http) {

        // ★★ 超时参数不能省 ★★
        //    Tomcat 的异步请求超时默认是 30 秒。不显式指定的话，
        //    任何超过 30 秒的流式回答都会在中间被无声掐断，
        //    日志里只有一句 "Async request timed out"，没有异常栈。
        //    application.yml 里还有一处全局兜底（spring.mvc.async.request-timeout）。
        //    ★ 阶段 6 起这个值是「排队预算 + 回答预算」，见 sseTimeoutMs()。
        SseEmitter emitter = new SseEmitter(sseTimeoutMs());
        SseChannel channel = new SseChannel(emitter);

        // ★★ traceId 在【这里】生成，而不是在 ChatServiceImpl 里 ——
        //    因为排队比问答先发生，而排队期间推给前端的位置事件里就得有它。
        //    同一个 id 一路用到 qa_log.trace_id，见 TraceId 类注释。
        String traceId = TraceId.newId();
        ChatAskRequest request = new ChatAskRequest(sessionNo, question, null);

        // ★ 身份在这里解析（和 /api/chat 同一个头），带进排队层只为
        //   在被拒绝时能写出一行完整的 qa_log —— 见 Admission 的说明。
        admission.submit(
                new ChatAdmissionService.Admission(traceId, question, resolveUserId(http)),
                new QueueEventListener(channel, traceId),
                // ★★ 这段 work 跑在 answer- 线程上，而【名额的释放在它外面的 finally 里】——
                //    由 ChatAdmissionService 保证，所以这里不需要（也不该）管名额。
                //
                // ★ 参数是 CallContext 而不是 traceId：排队时长和初始位置只有
                //   ChatAdmissionService 知道，由它构造好传进来，再原样交给
                //   ChatService 落进 qa_log.queue_ms / queue_position。
                ctx -> runStream(channel, request, ctx));

        return emitter;
    }

    /**
     * 把排队事件推到 SSE。
     *
     * <p>★ 用 {@link SseChannel#sendQuietly} 而不是 {@code send}：
     * 这个监听器跑在 <b>queue- 线程</b>上，而推送失败在那里只意味着
     * 「客户端断开了，该退出排队了」—— 不该让异常从轮询里穿出去。
     * 见 {@link SseChannel} 类注释第二节。
     */
    private record QueueEventListener(SseChannel channel, String traceId)
            implements ChatAdmissionService.QueueListener {

        @Override
        public void onQueued(int position, long waitedMs) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", traceId);
            // ★ position 是「前面还有几个人」（0-based）。★ 这里【不要】+1：
            //   前端要显示的那句话就是「你前面还有 N 位」，而 N 就是它。
            payload.put("position", position);
            payload.put("waitedMs", waitedMs);
            channel.sendQuietly("queued", payload);
        }

        @Override
        public void onAdmitted(long queueMs, Integer initialPosition) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", traceId);
            payload.put("queueMs", queueMs);
            // ★ 前端能借此显示「你进来时前面有 N 人」——
            //   它是 null 就表示没排队，不是 0
            payload.put("queuedAhead", initialPosition);
            channel.sendQuietly("admitted", payload);
        }

        @Override
        public void onGivenUp(String message, long queueMs) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", message);
            payload.put("traceId", traceId);
            payload.put("queueMs", queueMs);
            channel.sendQuietly("failed", payload);
            channel.complete();
        }

        /**
         * ★★ 排队期间客户端关掉页面了吗？
         *
         * <p>这是<b>最常见的名额浪费</b>的防线 —— 比「进程被强杀」常见得多，
         * 而后者有 TTL 兜底，前者只能靠及时察觉。
         * 检测靠的是 {@code onCompletion/onTimeout/onError} 三个回调，
         * <b>不能靠「推送失败」</b>：排队期可能几十秒不推一次东西，
         * 而推送失败要等到下一次真的写才会发生。详见 {@link SseChannel}。
         */
        @Override
        public boolean isCancelled() {
            return channel.isClosed();
        }
    }

    /** 在 answer- 线程上执行的流式流程 */
    private void runStream(SseChannel channel, ChatAskRequest request, CallContext ctx) {
        ChatService.ChatStreamSink sink = new ChatService.ChatStreamSink() {

            @Override
            public void onStart(String traceId, String sessionNo) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("traceId", traceId);
                meta.put("sessionNo", sessionNo);
                // ★ 立刻推一个 meta 事件，让前端马上有反馈，
                //   而不是对着空白页等模型的首字节（可能好几秒）
                channel.send("meta", meta);
            }

            @Override
            public void onDelta(String delta) {
                channel.send("delta", Map.of("v", delta));
            }

            @Override
            public void onComplete(ChatAskResponse summary) {
                channel.send("done", summary);
                channel.complete();
            }

            @Override
            public void onError(String message, String traceId) {
                channel.send("failed", Map.of("message", message, "traceId", traceId));
                channel.complete();
            }
        };

        try {
            // ★ 三参重载：沿用排队层那个 traceId，让「排队 90 秒」和
            //   「回答 3 秒」在日志和 qa_log 里是同一条记录。
            //   同时把 queue_ms / queue_position 带进去落库。
            chatService.askStream(request, sink, ctx);
        } catch (Exception e) {
            // service 内部已经把失败写进 qa_log 并调用过 onError 了，
            // 走到这里通常是「连 onError 都发不出去」，记日志即可
            log.debug("流式流程异常退出: {}", e.getMessage());
            if (!channel.isClosed()) {
                channel.complete();
            }
        }
    }
}
