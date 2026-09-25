package com.xbla.rag.common.handler;

import com.xbla.rag.client.ModelCallException;
import com.xbla.rag.client.ModelErrorKind;
import com.xbla.rag.common.ApiResponse;
import com.xbla.rag.config.RateLimitProperties;
import com.xbla.rag.ratelimit.QueueRejectedException;
import com.xbla.rag.service.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理 —— 把异常统一转成 {@link ApiResponse}。
 *
 * <p>有了它，controller 里就不用写 try-catch，保持「只做参数校验和响应封装」
 * 这个约定。
 *
 * <h3>★ 为什么要区分 HTTP 状态码</h3>
 *
 * <p>很多国内接口习惯「无论什么错都返回 HTTP 200，靠 body 里的 code 区分」。
 * 这里<b>不采用</b>那种做法，理由：
 * <ul>
 *   <li>监控告警靠 HTTP 状态码 —— 全是 200 的话，错误率永远是 0，
 *       线上炸了都不知道</li>
 *   <li>Nginx / 网关 / 熔断器的一堆机制都基于状态码，
 *       全返回 200 等于自废武功</li>
 *   <li>前端和测试工具（curl、Postman）都按状态码判断成败，
 *       全 200 会让调试变难</li>
 * </ul>
 *
 * <p>所以这里<b>既设置合理的 HTTP 状态码，又保留 {@link ApiResponse} 的 body 结构</b>——
 * 两者都要。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * ★ 只为了算 {@code Retry-After} —— 它不是业务逻辑，是「告诉客户端该等多久」。
     *
     * <p>⚠️ 这里读的是<b>配置类</b>而不是去问 {@code ChatPermitService}：
     * handler 只在被拒绝时跑，那时去碰 Redis 可能又失败一次，
     * 而「回一个错误响应还要再依赖一个正在出问题的组件」是最不该有的耦合。
     */
    private final RateLimitProperties rateLimitProperties;

    public GlobalExceptionHandler(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * 模型调用失败。
     *
     * <p>这种错误要<b>分成两类</b>告诉客户端，因为客户端的应对完全不同：
     * <ul>
     *   <li><b>服务端暂时不可用</b>（限流、超时、5xx、余额不足）——
     *       返回 {@code 503}，客户端知道了应该重试</li>
     *   <li><b>请求本身有问题</b>（400）—— 返回 {@code 400}，
     *       客户端重试多少次都一样，得改请求</li>
     * </ul>
     *
     * <p>★ <b>绝不能把异常的原始消息直接返回给客户端</b> ——
     * 它可能包含上游服务的错误详情、内部模型 ID 等信息。
     * 完整信息打日志，对外只给一句可读的说明。
     */
    @ExceptionHandler(ModelCallException.class)
    public ResponseEntity<ApiResponse<Void>> handleModelCall(ModelCallException e) {
        log.error("模型调用失败: {}", e.getMessage(), e);

        ModelErrorKind kind = e.kind();
        HttpStatus status = switch (kind) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case AUTH, QUOTA_EXHAUSTED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };

        String message = switch (kind) {
            case BAD_REQUEST -> "请求参数不被模型接受，请检查输入内容";
            case AUTH -> "模型服务认证失败，请联系管理员检查密钥配置";
            case QUOTA_EXHAUSTED -> "模型服务账户余额不足，请联系管理员充值";
            case PARTIAL_STREAM -> "回答生成中途中断，请重试";
            default -> "智能助手暂时不可用，请稍后重试";
        };

        return ResponseEntity.status(status).body(ApiResponse.fail(message));
    }

    /**
     * 参数校验失败（{@code @Valid} 没通过）。
     *
     * <p>把每个字段的校验消息拼起来返回，方便调用方定位 ——
     * 只回一句「参数错误」的话，前端得自己猜是哪个字段。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));

        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, detail.isEmpty() ? "参数校验失败" : detail));
    }

    /** 业务参数非法（比如传了链路里不存在的 modelKey） */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数非法: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, e.getMessage()));
    }

    /**
     * 「按某个键去查，但那条记录不存在」—— 阶段 8 的只读回看接口。
     *
     * <h3>★ 注册在【基类】上，所以新增一个子类就自动被覆盖</h3>
     *
     * <p>它现在覆盖两个：{@code ChatSessionNotFoundException}（会话号找不到）
     * 和 {@code ChatTraceNotFoundException}（traceId 找不到）。
     * 如果每个子类各写一个 handler，那么「加一个新的 not-found 场景」
     * 就变成了「记得也加一个 handler」—— 而忘了加的症状是那个接口回
     * <b>500「服务内部错误」</b>。挂一个会漂移的钩子，不如挂一个不会的。
     *
     * <h3>★★ 这个 handler 存在的唯一理由是：兜底 handler 会把它吃掉</h3>
     *
     * <p>如果不注册这一条，{@code ResourceNotFoundException}（一个
     * {@code RuntimeException}）会一路落到下面那个
     * {@code @ExceptionHandler(Exception.class)}，变成
     * <b>HTTP 500「服务内部错误」</b>。
     *
     * <p>★ 顺带说明一件容易被误会的事：<b>换成 Spring 自带的
     * {@code ResponseStatusException(HttpStatus.NOT_FOUND)} 也没用</b>。
     * Spring 解析异常的顺序是
     * {@code ExceptionHandlerExceptionResolver}（本类的
     * {@code @ExceptionHandler}）→ {@code ResponseStatusExceptionResolver}
     * → {@code DefaultHandlerExceptionResolver}，所以自带 404 语义的异常
     * <b>在到达 Spring 自己的解析器之前就被兜底分支接走了</b>。
     * 想要特定状态码，只能显式注册。
     *
     * <h3>为什么是 404</h3>
     *
     * <p>见 {@link ResourceNotFoundException} 的类注释 ——
     * 一句话：<b>不能把「没有这条记录」和「这条记录是空的」
     * 渲染成同一个响应</b>，因为这两件事的修法完全相反。
     *
     * <p>★ 这里 {@code ApiResponse} 的 {@code code} 用 <b>404</b> 而不是
     * {@code CODE_SUCCESS}（那是 0）。两个数字都要对：
     * HTTP 状态码给网关和监控看，body 里的 code 给前端看，
     * <b>只对一个的接口会让其中一方得出相反的结论</b>。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException e) {
        log.warn("{}不存在: {}", e.getKind(), e.getId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(HttpStatus.NOT_FOUND.value(), e.getMessage()));
    }

    /**
     * 排队被拒绝 —— 队列满了，或者等太久了（阶段 6.7）。
     *
     * <h3>★ 为什么是 503 而不是 500</h3>
     *
     * <p>因为它<b>不是故障</b>：它恰恰是限流在正常工作的证据。
     *
     * <pre>
     *   500 → 「系统坏了」  → 用户截图报障、怀疑数据出问题
     *   503 → 「暂时忙」    → 用户知道重试就行
     * </pre>
     *
     * <p>★ 而且这里有个具体的风险：不加这个 handler 的话，异常会落到下面的兜底分支，
     * 于是「因为太忙所以没答上」被报成「服务内部错误」——
     * 而那会让人去查日志找一个不存在的 bug。
     *
     * <h3>★ {@code Retry-After} 是 503 能告诉客户端的最有用的一件事</h3>
     *
     * <p>没有它，客户端只知道「失败了」；有了它，客户端知道<b>该等多久</b>。
     * 取值用当前的 {@code poll-interval} —— 那是「名额周转」的时间尺度，
     * 而不是拍一个 60 秒。
     *
     * <p>⚠️ <b>不能回 {@code ApiResponse.fail(...)} 里的默认 500</b>：
     * 那个 code 是给「真的出错了」用的，和 HTTP 503 放在一起会自相矛盾。
     */
    @ExceptionHandler(QueueRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleQueueRejected(QueueRejectedException e) {
        // ★ INFO 而不是 ERROR：被限流是【预期内】的正常结果，
        //   打 ERROR 会让「日志里全是错误」而真正的故障被淹没。
        //   ★ 但它也不是 DEBUG —— 它是一次用户没得到回答的请求。
        log.info("排队拒绝: {}（等了 {}ms）", e.getMessage(), e.queueMs());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds()))
                .body(ApiResponse.fail(HttpStatus.SERVICE_UNAVAILABLE.value(), e.getMessage()));
    }

    /**
     * 建议客户端多久之后重试。
     *
     * <p>★ 用 {@code poll-interval}（名额周转的时间尺度）换算，而不是拍一个数。
     * 它<b>至少 1 秒</b> —— {@code Retry-After} 的单位是秒，
     * 而 {@code poll-interval} 默认是 200ms，直接换算会得到 0。
     * 告诉客户端「马上重试」比不说更糟：它会让一群客户端在同一瞬间涌回来。
     */
    private long retryAfterSeconds() {
        long ms = rateLimitProperties.getPollInterval().toMillis();
        return Math.max(1L, (ms + 999) / 1000);
    }

    /**
     * 客户端在响应写完之前断开了 —— <b>这不是错误，是正常事件</b>。
     *
     * <h3>★★★ 为什么它必须有一个自己的 handler</h3>
     *
     * <p>用户在回答生成到一半时关掉页面，Spring 的异步派发会把这件事
     * <b>当成「handler 方法抛了一个异常」</b>递上来：
     *
     * <pre>
     *   AsyncRequestNotUsableException: Servlet container error notification for disconnected client
     *     ← Caused by: java.io.IOException: Connection reset by peer
     * </pre>
     *
     * <p>没有这一条的话，它会落到下面的兜底分支，于是<b>一次关标签页会产出三条日志</b>
     * （实测，一次 RST 连接就够复现）：
     *
     * <pre>
     *   ERROR  未预期的异常                    + 一整屏 60 行堆栈
     *   WARN   Failure in @ExceptionHandler …#handleOther(Exception)
     * </pre>
     *
     * <p>★ 第二条尤其糟：兜底分支要写一个 500 的响应体，而连接已经死了 ——
     * <b>于是一个「处理异常的方法」自己又抛了一次异常</b>。
     *
     * <h3>★ 为什么这不是「顺手加的防御」，而是和 {@link #handleQueueRejected} 同一类</h3>
     *
     * <p>本项目已经因为同一件事踩过两次（阶段 6.7 的 {@code ExecutionException} 包装、
     * 这一次）：<b>一个完全预期之内的条件，被归到「未预期的异常」里</b>。
     * 后果不是功能坏了，而是<b>信号被淹没</b> ——
     * 监控按 ERROR 计数的话，「用户关页面」和「数据库连不上」长得一模一样，
     * 而前者每天几千次、后者一年一次。
     *
     * <p>★ 返回 {@code void} 是刻意的：<b>什么都不写</b>。
     * 连接都没了，写任何东西都只会再炸一次 —— 而那正是上面那条 WARN 的来源。
     * Spring 对 {@code @ResponseBody} + 空返回值会标记「已处理」并跳过序列化，
     * 所以这里连响应体都不会去构造。
     *
     * <p>⚠️ 记 DEBUG 而不是 INFO：它每次关页面都会发生，INFO 也能刷屏。
     * 它<u>不是</u>一个值得告警的信号 —— 想统计「有多少人在回答完成前离开」
     * 的话，那是产品指标，应该来自 {@code SseChannel} 那层，不是异常处理器。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnected(AsyncRequestNotUsableException e) {
        log.debug("客户端在响应写完之前断开（正常事件）: {}", e.getMessage());
    }

    /**
     * 请求了一个<b>不存在的路径</b> —— 回 404，不是 500。
     *
     * <h3>★★★ 这个 handler 守的是一个「404 全被渲染成 500」的真 bug</h3>
     *
     * <p>2026-09-24 阶段 8 实测（容器里打不存在的路径）：
     *
     * <pre>
     *   $ curl -u user:pw http://host/api/debug/ratelimit/state
     *   500
     *
     *   ERROR GlobalExceptionHandler : 未预期的异常
     *   org.springframework.web.servlet.resource.NoResourceFoundException:
     *       No static resource api/debug/ratelimit/state.
     * </pre>
     *
     * <p>机制：Spring Boot 3.2 起，<b>未匹配的路径不是「机械地回 404」</b>，
     * 而是抛 {@code NoResourceFoundException}（静态资源处理器是链上最后一个，
     * 它用异常报告「找不到」）。而下面的兜底
     * {@code @ExceptionHandler(Exception.class)} 把它接走了 → 500。
     *
     * <p>★★ <b>后果有三条，都不是「功能坏了」而是「信号坏了」</b>：
     *
     * <pre>
     *   ① 任何拼错的 URL 都回 500 —— 客户端分不清「没有这个接口」和「服务炸了」
     *   ② 每一次扫描器探测都写一行 ERROR 级日志（"未预期的异常" + 堆栈）
     *      → ★ 而本项目刚刚在【上面那段注释里】说过：
     *        「用户关页面和数据库连不上长得一模一样，而前者每天几千次」
     *        —— 这里正在制造同一个问题
     *   ③ 监控按 ERROR 计数时，错误率被探测流量污染
     * </pre>
     *
     * <p>★ 它还是<b>验收判据的一部分</b>：阶段 8 要证明
     * 「公网访问 <code>/api/debug/**</code> 返回 404」——
     * 而 500 让那条判据<b>无法成立</b>（你没法区分「控制器没注册」和「注册了但炸了」）。
     *
     * <h3>★ 这是本项目的【第三次】同一个形态</h3>
     *
     * <p>{@code handleClientDisconnected} 的注释里写着「已经踩过两次」：
     * 阶段 6.7 的 {@code ExecutionException} 包装、以及客户端断开。
     * 这是第三次，而且这一次是<b>兜底 handler 自己在制造它</b> ——
     * 那一大段解释为什么「别把预期内的事归到未预期」的注释，
     * 正贴在造成第三个反例的代码上面。
     *
     * <h3>为什么记 DEBUG 而不是 ERROR</h3>
     *
     * <p>因为「有人请求了一个不存在的路径」是<b>完全正常</b>的事 ——
     * 浏览器要 favicon、爬虫扫路径、用户拼错地址。
     * 它和 {@code handleClientDisconnected} 是同一类：<b>不该告警的事件。</b>
     *
     * <p>⚠️ 真正想看的「有没有人在扫我的站」应该来自 Nginx 的访问日志
     * （那里有 IP、User-Agent、扫描模式），不是靠 Spring 的异常处理器。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        log.debug("请求了不存在的路径（正常事件）: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(HttpStatus.NOT_FOUND.value(), "接口不存在"));
    }

    /**
     * 兜底。
     *
     * <p>★ 对外只回一句笼统的话，<b>不返回异常堆栈或原始消息</b> ——
     * 那些可能泄露内部实现细节（表名、类名、第三方服务地址）。
     * 完整信息在日志里，靠时间戳和前面的请求日志去对。
     *
     * <p>⚠️ <b>挂在这里的每一条都要能说出「为什么它真的不可预期」</b>。
     * 上面那个 {@link #handleNoResource} 就是反例：一个完全预期的条件
     * 在这里待了很久，把 ERROR 日志刷成了噪声。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        // ★ 走到这里说明有一条【真的没想过】的异常。这句话本身就该被看见，
        //   而不是和「404」混在一起 —— 那正是上面那个 handler 存在的理由。
        log.error("未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("服务内部错误，请稍后重试"));
    }
}
