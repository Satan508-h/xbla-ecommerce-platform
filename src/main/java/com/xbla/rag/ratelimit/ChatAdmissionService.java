package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.service.CallContext;
import com.xbla.rag.service.QaLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 排队受理 —— <b>「排队 → 拿到名额 → 交出去 → 归还」这条链路唯一的地方</b>（阶段 6.5）。
 *
 * <h2>★★★ 这个类存在的全部理由：把一条不变量做成结构上无法违反的</h2>
 *
 * <p>阶段 6 最容易写错、而且<b>错了之后现象最像「偶发」</b>的一处是：
 *
 * <pre>
 *   ✗  submit() 里拿到名额 → 提交给 answerExecutor → 【在这里 release】→ 返回
 *                            ↑ 此时问答还没开始跑！
 *      → 名额在「已经归还」的状态下被使用
 *      → 8 个名额实际能跑 16 个、24 个……
 *      → 而这不会报错、不会打日志，只是并发悄悄超过上限
 *
 *   ✓  拿到名额 → 提交给 answerExecutor → 在【那个任务】的 finally 里 release
 * </pre>
 *
 * <p>两条路的差别只有几行，而<b>第一种看起来完全正常</b> ——
 * 它甚至更「对称」：acquire 和 release 在同一个方法里，读起来像一对。
 * 所以这里不靠注释提醒，而是把 release 写进 {@link #runWithRelease} 的
 * {@code finally}，<b>让调用方根本没有机会写错</b>：
 * {@link #submit} 里没有任何一处单独调用 {@code release}。
 *
 * <h2>★ 谁在哪个线程上跑</h2>
 *
 * <pre>
 *   Tomcat 线程 :  submit() 立刻返回（它只往 queueExecutor 丢一个任务）
 *   queue- 线程 :  推 queued 事件 → 轮询抢名额 → 推 admitted →
 *                  ★ 提交给 answerExecutor 后【立刻结束】，不在这里等问答跑完
 *   answer- 线程   :  跑 work（真正的问答）→ finally 里 release
 * </pre>
 *
 * <p>中间那一跳不能省：如果让 queue- 线程直接跑问答，
 * 那么「在等的」和「在跑的」会共用同一个池，而那个池的容量就得
 * 同时覆盖两者（100 并发 + 8 个在跑）。分开之后，
 * queue- 池只需要装得下「等待中的人」，而 answer- 池保持它原有的隔离语义。
 *
 * <h2>★ 三种结束方式，都要有明确的交代</h2>
 *
 * <ul>
 *   <li><b>拿到名额</b> → 推 {@code admitted}，跑 work</li>
 *   <li><b>队列满了</b> → 推 give-up（「当前 N 人在等」），写 {@code qa_log status=4}</li>
 *   <li><b>等太久了</b> → 推 give-up（「等待超过 N 秒」），写 {@code qa_log status=4}</li>
 * </ul>
 *
 * <p>★ 后两种<b>必须写库</b>：它们根本没进 {@code ChatService}，
 * 不写的话就<b>一行都不留</b> —— 100 个人来问、30 个被拒，
 * {@code qa_log} 里只有 70 行，而那 30 个在任何地方都没有记录。
 * <b>越忙越需要数据，恰恰越忙丢得越多</b>（见 {@code V9} 迁移的注释）。
 */
@Service
public class ChatAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(ChatAdmissionService.class);

    private final ChatPermitService permits;
    private final PermitSignalBus signals;
    private final RateLimitProperties props;
    private final QaLogService qaLogService;
    private final ThreadPoolTaskExecutor queueExecutor;
    private final ThreadPoolTaskExecutor answerExecutor;

    /** 被限流挡掉的累计次数 —— 出在探针上，长时间增长说明容量该调了 */
    private final AtomicLong givenUpTotal = new AtomicLong();

    public ChatAdmissionService(ChatPermitService permits,
                                PermitSignalBus signals,
                                RateLimitProperties props,
                                QaLogService qaLogService,
                                @Qualifier("queueExecutor") ThreadPoolTaskExecutor queueExecutor,
                                @Qualifier("answerExecutor") ThreadPoolTaskExecutor answerExecutor) {
        this.permits = permits;
        this.signals = signals;
        this.props = props;
        this.qaLogService = qaLogService;
        this.queueExecutor = queueExecutor;
        this.answerExecutor = answerExecutor;
    }

    // ============================================================
    // 入参与回调
    // ============================================================

    /**
     * 一次求受理的请求。
     *
     * <p>★ {@code question} 和 {@code userId} 在这里的原因只有一个：
     * <b>被拒绝时也要能写出一行 qa_log</b>，而那时 {@code ChatService} 还没被调用过，
     * 没有别的地方能提供它们。
     *
     * @param traceId 链路 ID。<b>排队层生成，一路用到 {@code qa_log.trace_id}</b>
     * @param question 用户原话，只为写那一行记录
     * @param userId  身份，可为 null（流式路径目前不带身份）
     */
    public record Admission(String traceId, String question, Long userId) {
    }

    /**
     * 排队过程的回调 —— <b>由 controller 实现，把事件推到 SSE</b>。
     *
     * <p>抽成接口而不是直接收一个 {@code SseEmitter}，理由和
     * {@code ChatService.ChatStreamSink} 完全一样：<b>让 ratelimit 层不依赖 Spring MVC</b>。
     */
    public interface QueueListener {

        /**
         * 正在排队。<b>第一次入队时调一次，之后每次位置变化再调</b>。
         *
         * @param position 前面还有几个人（0-based，队首是 0）
         * @param waitedMs 已经等了多久
         */
        void onQueued(int position, long waitedMs);

        /**
         * 拿到名额了，马上开始真正干活。
         *
         * @param queueMs       真实等待毫秒。<b>从没排过队时它也是真实值（几毫秒）</b> ——
         *                      「不排队」这件事由 {@code initialPosition == null} 表达
         * @param initialPosition 刚入队时前面有几个人；<b>null = 从没进过队列</b>
         */
        void onAdmitted(long queueMs, Integer initialPosition);

        /**
         * 放弃：队列满了，或者等太久了。
         *
         * @param message 面向用户的说明。<b>要说清楚是「人太多」还是「等太久」</b> ——
         *                它们对用户的下一步动作提示不同
         */
        void onGivenUp(String message, long queueMs);

        /**
         * 客户端还在吗？返回 {@code false} 表示已断开，别再等了。
         *
         * <p>★★ 这是**最常见的名额浪费**的防线，比「进程被强杀」常见得多。
         * 用户关掉页面之后，那个等待线程还在傻傻地轮询；等它拿到名额，
         * 已经没有人在看这个回答了 —— 而名额要白占一整个问答的时间。
         *
         * <p>⚠️ 不能用「SSE 推送失败」来判断：{@code SseEmitter.send()} 在客户端断开后
         * <b>不一定立刻抛异常</b>（要等下一次真的写）。必须靠
         * {@code onCompletion / onTimeout / onError} 回调置位。
         *
         * <p>默认 {@code false}：不实现的调用方（测试、探针）当作一直在线。
         */
        default boolean isCancelled() {
            return false;
        }

        /**
         * 不再等了，而且<b>没有一句解释要给人</b> —— 客户端已经不在了，或者应用正在关闭。
         *
         * <h3>★ 它和 {@link #onGivenUp} 的区别是「有没有人该收到那句话」</h3>
         *
         * <pre>
         *   onGivenUp   「我们决定不等了」→ 有人在听，要给他一句能看懂的说明
         *   onAbandoned 「没人在听了」    → 没有话要说，但【必须有人知道这件事结束了】
         * </pre>
         *
         * <h3>★★ 为什么它必须存在 —— 一个会让线程永远挂住的洞</h3>
         *
         * <p>{@code waitThenRun} 一共有<b>六条出口</b>，其中两条是静默的：
         * {@code isCancelled()} 那一支和 {@code InterruptedException} 那一支。
         * 流式路径下这完全没问题 —— 那条链接收端是 SSE，客户端都走了，推给谁看？
         *
         * <p>但<b>非流式路径接管了这件事的后果</b>：它有一个
         * <b>阻塞在 {@code CompletableFuture} 上的 Tomcat 线程</b>，
         * 而那个线程<b>只能靠一个回调被唤醒</b>。
         *
         * <pre>
         *   少了这条回调 → future 永远不完成 → Tomcat 线程永远阻塞
         *                → 请求永远不返回（客户端那边是「连接挂死」）
         *                → 而且它只在【应用关闭】时发生，所以演示时永远看不到
         * </pre>
         *
         * <p>★ 所以这里把一条不变量写成了代码：
         * <b>每一次 {@code submit} 都必然以「work 跑了 / 被拒绝 / 被放弃」之一结束。</b>
         * 没有第四种。
         *
         * @param reason 放弃的原因，只为日志。<b>它不会展示给用户</b> ——
         *               要展示给用户的话走 {@link #onGivenUp}。
         *               默认什么都不做：实现了 SSE 的调用方不需要它。
         */
        default void onAbandoned(String reason) {
        }
    }

    // ============================================================
    // 主流程
    // ============================================================

    /**
     * 受理一次请求。<b>立刻返回</b>，真正的等待和问答都在别的线程上。
     *
     * @param work 真正要干的活（跑问答）。
     *             ★ <b>不要在里面 release 名额</b> —— 由本类负责，见类注释第一节。
     *             ⚠️ 它会跑在 {@code answer-} 线程上，所以流式路径里它能安全地阻塞。
     */
    public void submit(Admission admission, QueueListener listener, Consumer<CallContext> work) {
        // ★ 流式的排队预算：120 秒。为什么它和非流式不一样，见
        //   RateLimitProperties.nonStreamQueueTimeout 的注释 ——
        //   一句话：SSE 能推「你前面还有 N 位」，HTTP 只能转圈。
        submitWithBudget(admission, listener, props.getQueueTimeout(), work);
    }

    /**
     * 真正干活的那个 —— <b>排队预算由调用方给</b>。
     *
     * <p>★ 为什么预算要当参数传，而不是在 {@link #waitThenRun} 里读配置：
     * 那个循环是<b>两条路共用的</b>，而两条路的上限不同。
     * 在里面写死一个，就等于让另一条路拿错预算 ——
     * 而错的后果很具体：非流式会持有一个 Tomcat 线程 120 秒，
     * 而客户端 60 秒就放弃了，<b>我们精心写的那句提示永远送不到</b>。
     */
    private void submitWithBudget(Admission admission, QueueListener listener,
                                  Duration queueTimeout, Consumer<CallContext> work) {
        if (!props.isEnabled()) {
            // ★ 关掉时【不推任何排队事件】，而且上下文里排队两列是 null ——
            //   这正是「没开排队」该有的样子。记 0 的话，阶段 7 分不清
            //   「没开排队」和「开了但没排队」，而那是两个不同的实验条件。
            //   （同 ADR-010「拿不到就记 NULL」、以及 rewritten_question 的处置。）
            runWithRelease(admission, listener, CallContext.fresh(admission.traceId()), work);
            return;
        }

        try {
            queueExecutor.execute(() -> waitThenRun(admission, listener, queueTimeout, work));
        } catch (RejectedExecutionException e) {
            // queue- 池满了（超过 maxPoolSize 个人在等）—— 如实拒绝。
            // ★ 注意这里【没有】名额可释放：还没拿到过。
            log.warn("排队线程池已满（{} 人在等），拒绝 traceId={}",
                    props.getQueuePool().getMaxPoolSize(), admission.traceId());
            // ★ initialPosition = null：它连队列都没进得去（是 queue- 池满，
            //   不是 Redis 队列满）—— 两种情况都「没进过队列」，但原因不同，
            //   所以 reason 字符串要能分开它们。
            giveUp(admission, listener, 0, null,
                    "当前排队人数过多，请稍后重试。", "排队线程池已满");
        }
    }

    // ============================================================
    // 非流式：阻塞直到有结果（阶段 6.7）
    // ============================================================

    /**
     * <b>非流式</b>的硬上限 —— 纯粹是「永远不要挂死」的兜底，<b>正常情况下永远不会触发</b>。
     *
     * <h3>★★ 为什么它【不是】从 queueTimeout + 回答预算算出来的</h3>
     *
     * <p>算得出来的话当然更好。但「一次回答最多允许多久」那个数在
     * {@code ChatController.ANSWER_BUDGET_MS} 里，而排队层看不到它 ——
     * 为了这个兜底去把那个常量搬过来，就会得到<b>两处各写一份、会漂移</b>的那个数。
     * 而漂移的后果是：兜底比真实预算小，于是它<b>在正常请求上触发</b>，
     * 把一个本来能成的请求杀掉。
     *
     * <p>★ 所以这里取一个明显超出任何合法请求的值（10 分钟）。
     * 它的作用不是「限制耗时」，而是<b>「如果那个不变量坏了，至少线程会被放开」</b>。
     *
     * <p>⚠️ 它一旦触发就说明 {@link #waitThenRun} 多了一条静默的出口 ——
     * 那时日志里会有一条 ERROR，而不是一个永远不返回的请求。
     */
    private static final long NON_STREAM_HARD_LIMIT_MS = 600_000L;

    /**
     * 受理一次<b>非流式</b>请求：<b>阻塞直到有结果，或者被拒绝</b>。
     *
     * <h2>★★ 它和 {@link #submit} 只差一件事：结果要还给调用方</h2>
     *
     * <p>流式路径没有「返回值」这个概念 —— 正文一段段推给 SSE 就完了，
     * 所以 {@code submit} 返回 {@code void}。
     * 非流式是 HTTP 请求-响应，调用方必须拿到那个对象，
     * 于是这里多了一个 {@link CompletableFuture} 做交接。
     *
     * <p>★★★ <b>但两条路走的是同一套排队逻辑</b>（同一个 {@code submit}、同一个
     * {@code waitThenRun}、同一个 {@code runWithRelease}）。这是刻意的：
     *
     * <pre>
     *   名额的释放在哪里 —— 一处（runWithRelease 的 finally）
     *   被拒绝写不写 qa_log —— 一处（giveUp）
     *   拒绝的判据是什么 —— 一处（tryAcquire 的返回值）
     * </pre>
     *
     * <p>非流式如果自己写一遍排队循环，上面每一条都会变成两份，而<b>两份会漂移</b>。
     * 本项目已经因为「两条平行路径改了一条」踩过 ADR-047。
     *
     * <h2>★ 线程账：一个非流式请求占 3 个线程</h2>
     *
     * <pre>
     *   Tomcat 线程 :  阻塞在这个 future 上（HTTP 请求-响应模型下它本来就得占着）
     *   queue- 线程 :  跑等待循环（和流式路径共用同一个池，上限 128）
     *   answer- 线程:  真正跑 work
     * </pre>
     *
     * <p>★ 看起来浪费，但它换来的是「等待人数有一个<b>我们配置的</b>上限（128）」。
     * 如果让 Tomcat 线程自己去跑等待循环，上限就变成了 Tomcat 的
     * {@code maxThreads}（默认 200）—— 那个数不归我们管，而且会把
     * <b>整个 Web 容器</b>堵住，连 {@code /actuator/health} 都进不来。
     *
     * <h2>⚠️ 一个诚实的缺口：客户端断线检测不到</h2>
     *
     * <p>流式路径靠 {@code SseEmitter} 的 {@code onCompletion/onTimeout/onError}
     * 知道「人走了」。同步的 HTTP 请求<b>没有等价的回调</b> ——
     * {@code HttpServletRequest} 不告诉你对面还在不在。
     *
     * <p>所以非流式的 {@link QueueListener#isCancelled()} <b>永远返回 false</b>，
     * 一个断开的客户端会把它在队列里的位置占到 {@code nonStreamQueueTimeout} 为止。
     *
     * <p>★ 影响是有界的，而且比流式那边<b>小</b>：断线的流式请求会占住一个<b>名额</b>
     * （整个问答时长），而断线的非流式请求只占一个<b>队列位置</b>（不占名额），
     * 并且会在排队超时后自己摘出去。要彻底解决得上异步 Servlet
     * （{@code DeferredResult}），那是另一个量级的改动，见 {@code docs/10}。
     *
     * @param work 真正要干的活。<b>它跑在 {@code answer-} 线程上</b>，
     *             所以里面可以安全地阻塞（模型调用本来就是阻塞的）。
     *             ⚠️ 不要在里面 release 名额 —— 由本类负责。
     * @return work 的返回值
     * @throws QueueRejectedException 队列满 / 等太久 / 被放弃。
     *                                ★ 它是<b>领域异常</b>，会被
     *                                {@code GlobalExceptionHandler} 转成 HTTP 503
     * @throws RuntimeException       work 自己抛出来的异常，<b>原样抛出</b>（见 {@link #unwrap}）
     */
    public <T> T submitAndWait(Admission admission, Function<CallContext, T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();

        submitWithBudget(admission, new BlockingListener(admission, future),
                props.getNonStreamQueueTimeout(), ctx -> {
                    try {
                        future.complete(work.apply(ctx));
                    } catch (Throwable t) {
                        // ★ 连 Error 也接住：future 只有被完成，那个 Tomcat 线程才会放开。
                        //   漏掉任何一种 Throwable 的后果都一样 —— 线程永远挂住。
                        future.completeExceptionally(t);
                    }
                });

        return awaitFuture(admission, future);
    }

    /**
     * 等结果 —— <b>唯一一个会把非流式请求放回去的地方</b>。
     *
     * <p>★ 带超时（见 {@link #NON_STREAM_HARD_LIMIT_MS}）：一个永不完成的 future
     * 会让 Tomcat 线程永远阻塞，而那种错误的表现是「连接挂死」——
     * 既没有异常、也没有日志。<b>宁可有一个会误报的兜底，也不要一个会挂死的等待。</b>
     */
    private static <T> T awaitFuture(Admission admission, CompletableFuture<T> future) {
        try {
            return future.get(NON_STREAM_HARD_LIMIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // ★ ERROR 而不是 WARN：它【不应该】发生。发生时说明
            //   waitThenRun 多了一条静默的出口，而那是要被修掉的东西。
            log.error("★ 非流式请求在 {}ms 内没有被任何回调结束 traceId={} —— "
                            + "这是「submit 的每一条出口都必须通知 listener」这条不变量被打破的信号，"
                            + "请检查 waitThenRun 的全部 return 分支",
                    NON_STREAM_HARD_LIMIT_MS, admission.traceId());
            throw new QueueRejectedException("请求处理超时，请稍后重试。", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QueueRejectedException("请求已被中断，请稍后重试。", 0);
        } catch (ExecutionException e) {
            throw unwrap(e, admission);
        }
    }

    /**
     * ★★★ <b>把 work 抛出来的异常原样送回 HTTP 层 —— 这一层包装必须剥掉。</b>
     *
     * <p>因为 {@code @ExceptionHandler} 是<b>按异常类型匹配</b>的：
     *
     * <pre>
     *   work 里抛出 ModelCallException（比如「余额不足」）
     *       → future 里存的是 ExecutionException(cause = ModelCallException)
     *       → 直接往外抛 → Spring 找不到匹配的 handler
     *       → 落到兜底分支 → 500「服务内部错误」
     *
     *   ✗ 用户看到的是一句没头没脑的 500
     *   ✗ 而 GlobalExceptionHandler 里那条「余额不足 → 503 + 一句人话」的规则【白写了】
     * </pre>
     *
     * <p>★ 这个 bug 的形态很隐蔽：<b>它不会让任何单元测试失败</b> ——
     * 那些测试直接调 service，压根不经过 HTTP 层；
     * 也不会有异常漏出来，因为 500 看起来就是个「合理的失败」。
     * 只有真的发一个会触发领域异常的请求，才会看到那句提示变成了「服务内部错误」。
     *
     * <p>★ 非 {@code RuntimeException} 的（受检异常、{@code Error}）没法原样抛出，
     * 只能包一层 —— 它们本来就没有对应的 handler，包不包都是兜底 500。
     */
    private static RuntimeException unwrap(ExecutionException e, Admission admission) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        log.error("非流式问答以非运行时异常结束 traceId={}", admission.traceId(), cause);
        return new IllegalStateException(
                "非流式问答以 " + (cause == null ? "未知异常" : cause.getClass().getName()) + " 结束");
    }

    /**
     * 非流式路径的回调 —— <b>没有 SSE 可推，所以它只做两件事</b>：
     * 记日志、以及<b>在结束或放弃时把那个 future 放开</b>。
     *
     * <p>★ {@code onGivenUp} 和 {@code onAbandoned} <b>都</b>要放开 future，
     * 这是「每一条出口都要通知」那条不变量的落点。少了 {@code onAbandoned}，
     * 应用关闭时那些阻塞着的 Tomcat 线程会一直等到硬上限。
     *
     * <p>★ 重复调用是安全的：{@code CompletableFuture.complete*} 在已经完成之后
     * 返回 {@code false} 而不抛异常。所以即使将来多出一条出口重复通知，
     * 也<b>不会把已经拿到的结果覆盖掉</b>。
     */
    private record BlockingListener(Admission admission, CompletableFuture<?> future)
            implements QueueListener {

        @Override
        public void onQueued(int position, long waitedMs) {
            // ★ 没有通道可以推给谁，只能记日志。
            //   ⚠️ DEBUG 而不是 INFO：100 个并发等待者会让 INFO 刷屏，
            //      而「谁等了多久」在 qa_log.queue_ms 里有更准确的记录。
            log.debug("非流式请求排队中 traceId={} 前面 {} 人，已等 {}ms",
                    admission.traceId(), position, waitedMs);
        }

        @Override
        public void onAdmitted(long queueMs, Integer initialPosition) {
            // ★ 空实现是【对的】，不是漏了：这一跳对非流式唯一的意义就是
            //   「即将开始干活」，而接下来马上就是 work 本身 ——
            //   没有任何人需要一个独立的通知。
            //   ⚠️ 排队时长会随 CallContext 一路进 qa_log 和响应体，
            //     所以它没有丢失，只是不需要在这里处理。
        }

        @Override
        public void onGivenUp(String message, long queueMs) {
            // ★ message 直接就是那句能展示给用户的话（giveUp 里为两条路分别写好的），
            //   这里【不再加工】——「人太多」和「等太久」对用户下一步动作的提示不同。
            future.completeExceptionally(new QueueRejectedException(message, queueMs));
        }

        @Override
        public void onAbandoned(String reason) {
            // ★ 走到这里时【没人该收到解释】（客户端没了 / 应用在关闭），
            //   但 future 必须被放开 —— 否则线程挂住。
            future.completeExceptionally(
                    new QueueRejectedException("请求未能完成（" + reason + "），请稍后重试。", 0));
        }
    }

    /**
     * 等待循环 —— 跑在 {@code queue-} 线程上。
     *
     * <p>★ 它<b>不释放名额</b>（除了「排队中途放弃」那两处，那时还没拿到名额，
     * 释放只是把自己从队列里摘掉）。
     */
    private void waitThenRun(Admission admission, QueueListener listener,
                             Duration queueTimeout, Consumer<CallContext> work) {
        String traceId = admission.traceId();
        long start = System.currentTimeMillis();

        // ★★ 第一次入队时的位置，一路带到结束。
        //   它必须在这里单独存着，因为【后面每次重试拿到的 position 都在变小】
        //   （前面的人一个个被服务完），而我们要记的是「刚进来时前面有多少人」——
        //   那个数才说明「当时有多挤」。
        //   ⚠️ 记最后一次拿到的话它恒为 0（轮到你时你必然是队首），
        //      一整列 0 看起来像数据，实际不携带任何信息。见 V9 的注释。
        Integer initialPosition = null;

        // ★★ 上一次【真的推给前端】的位置，-1 = 还没推过。
        //   用 -1 当哨兵是安全的：position 来自 ZRANK，永远 >= 0。
        //   见 ③ 处关于「为什么不再每次轮询都推」的说明。
        int lastReportedPosition = -1;

        try {
            while (true) {
                // ① 客户端还在吗？—— 最常见的名额浪费，见 isCancelled 的说明
                if (listener.isCancelled()) {
                    permits.release(traceId);   // 只是把自己从队列里摘掉，没占名额
                    log.debug("排队期间客户端断开，已退出队列 traceId={}", traceId);
                    // ★★ 这一句不能省：非流式路径有一个阻塞在 future 上的线程，
                    //    它只能靠回调被唤醒。少一句就是「线程永远挂住」。
                    //    见 onAbandoned 的注释 —— 六条出口，一条都不能是静默的。
                    listener.onAbandoned("客户端已断开");
                    return;
                }

                // ② 试一次
                PermitState state = permits.tryAcquire(traceId);
                long waited = System.currentTimeMillis() - start;

                if (state.isGranted()) {
                    listener.onAdmitted(waited, initialPosition);
                    runWithRelease(admission, listener, contextOf(admission, waited, initialPosition), work);
                    return;
                }

                if (state.outcome() == PermitState.Outcome.QUEUE_FULL) {
                    // ★ 从没进过队列，所以 initialPosition 保持 null
                    giveUp(admission, listener, waited, initialPosition,
                            "当前排队人数过多（" + state.queueSize() + " 人在等），请稍后重试。",
                            "队列已满");
                    return;
                }

                // ③ 在排队 —— 报位置
                // ★ 第一次入队时的位置，一路带到结束。它必须单独存着：
                //   后面每次重试拿到的 position 都在变小（前面的人在一个个被服务完），
                //   而我们要记的是「刚进来时前面有多少人」——那个数才说明当时有多挤。
                if (initialPosition == null) {
                    initialPosition = state.position();
                }

                // ★★ 只在位置【变了】的时候才推 —— 首次必推。
                //
                //   阶段 6.4 之前是每次轮询都推（每 200ms 一次）。加了 Pub/Sub 之后
                //   这个做法会失控：一次名额释放会叫醒【本机全部】等待者，
                //   而它们醒来后会各推一次 —— 于是 SSE 事件量随「等待人数」放大，
                //   而其中绝大多数事件的 position 和上一条一模一样，不携带任何信息。
                //
                //   ⚠️ 副作用：payload 里的 waitedMs 会变陈旧。
                //      这不是问题 —— 前端本来就该自己算（它手上的时钟差 = 经过时间 + 网络延迟），
                //      而 waitedMs 唯一不可替代的用途是「第一眼就看到大概要等多久」，
                //      那一条恰好是首发事件，永远是最新的。
                int position = state.position();
                if (position != lastReportedPosition) {
                    lastReportedPosition = position;
                    listener.onQueued(position, waited);
                }

                // ④ 等太久了就放弃
                // ★ 预算是调用方给的（流式 120s / 非流式 30s），不是在这里读配置 ——
                //   见 submitWithBudget 的注释。
                long timeoutMs = queueTimeout.toMillis();
                if (waited >= timeoutMs) {
                    giveUp(admission, listener, waited, initialPosition,
                            "排队等待超过 " + (timeoutMs / 1000) + " 秒，请稍后重试。",
                            "排队超时");
                    permits.release(traceId);   // 把自己从队列里摘掉
                    return;
                }

                // ⑤ 等一个唤醒信号，最多 pollInterval。
                //
                // ★★★ 这一步的语义必须理解对：它是【有上限的等待】，不是【条件等待】。
                //
                //   有信号  → 提前醒来，立刻再抢一次（延迟 ~1ms）
                //   没信号  → 睡满 pollInterval 再抢一次（和阶段 6.4 之前完全一样）
                //   信号丢了 / 订阅断了 / 应用刚起还没订阅上
                //           → 【同样只是睡满 pollInterval】
                //
                //   所以 Pub/Sub 只能让等待变短、不可能让它变长 ——
                //   这就是「消息丢了怎么办」的答案：不怎么办。
                //   详见 PermitSignalBus 的类注释。
                signals.await(props.getPollInterval().toMillis());
            }
        } catch (InterruptedException e) {
            // 应用正在关闭（线程池的 shutdownNow）。★ 必须恢复中断位 ——
            // 吞掉它会让更上层的代码以为「没人在打断我」。
            Thread.currentThread().interrupt();
            permits.release(traceId);
            log.debug("排队线程被中断（应用正在关闭？）traceId={}", traceId);
            // ★★ 同样是「六条出口里不能有静默的那一条」，见 onAbandoned 的注释。
            //    ⚠️ 这里【不能】换成 onGivenUp：那会给用户发一句「请稍后重试」，
            //       而应用正在关闭 —— 用户重试也连不上，那句话是假的。
            listener.onAbandoned("应用正在关闭");
        } catch (Exception e) {
            // 排队层自己出问题，不能让用户一直挂着
            log.warn("排队过程出现未预期异常 traceId={}: {}", traceId, e.getMessage(), e);
            permits.release(traceId);
            listener.onGivenUp("服务繁忙，请稍后重试。", System.currentTimeMillis() - start);
        }
    }

    /**
     * ★★★ <b>名额归还的唯一出口。</b>
     *
     * <p>{@code release} 写在 {@code finally} 里，而 {@code work.run()}
     * 和它<b>在同一个线程、同一个栈帧里</b> —— 所以「名额被释放时问答已经跑完」
     * 这件事是<b>结构保证</b>，不是靠调用顺序自觉。
     *
     * <p>★ 也正因为如此，{@link #submit} 和 {@link #waitThenRun} 里
     * <b>一处 {@code release} 都不会出现</b>（除了「放弃排队」那两处，
     * 那两处根本没拿到名额）。
     *
     * <p>⚠️ 提交给 {@code answerExecutor} 也可能被拒绝（池满）。那时名额已经拿到了
     * 而 work 永远不跑，{@code finally} 也不会执行 —— 所以这一处要显式释放。
     * 这是全类唯一需要「手动释放」的地方，因此单独写在这里而不是散在调用点。
     */
    private void runWithRelease(Admission admission, QueueListener listener,
                                CallContext ctx, Consumer<CallContext> work) {
        String traceId = admission.traceId();
        try {
            answerExecutor.execute(() -> {
                try {
                    work.accept(ctx);
                } finally {
                    permits.release(traceId);
                }
            });
        } catch (RejectedExecutionException e) {
            // answer- 池满了。★ 正常情况下不会发生：能同时跑的最多就是 permits 个（8），
            //   而 answer- 池有 32+16=48 的容量。但 ModelProbeController 也在用这个池，
            //   所以留一条防线 —— 不防的话名额会一直占到 TTL 过期。
            //
            // ★ 它也应该留下 status=4 的一行：用户在数据上不该因为
            //   「挡他的是 sse 池而不是 Redis 队列」就消失。
            permits.release(traceId);
            log.warn("sse 线程池已满，无法开始问答，已归还名额 traceId={}", traceId);
            giveUp(admission, listener, 0, null, "服务繁忙，请稍后重试。", "sse 线程池已满");
        }
    }

    // ============================================================
    // 放弃：通知 + 留痕
    // ============================================================

    /**
     * 放弃一个请求：通知用户，<b>并写一行 {@code qa_log status=4}</b>。
     *
     * <p>★ 写这一行是这个方法存在的主要理由。见类注释第二节的说明 ——
     * 不写的话，被限流的请求<b>在数据上完全不存在</b>。
     */
    private void giveUp(Admission admission, QueueListener listener,
                        long queueMs, Integer initialPosition, String userMessage, String reason) {
        givenUpTotal.incrementAndGet();
        log.info("限流拒绝 traceId={} 原因={} 等待={}ms 初始位置={}",
                admission.traceId(), reason, queueMs, initialPosition);

        // ★ 先通知用户，再写库。
        //   写库可能失败（数据库抖动），而用户不该因为这个再等 ——
        //   他已经在等了。所以把「让他知道发生了什么」放在前面。
        try {
            listener.onGivenUp(userMessage, queueMs);
        } catch (Exception e) {
            // 客户端可能已经断了，推不出去很正常
            log.debug("推送拒绝事件失败（客户端可能已断开）traceId={}: {}",
                    admission.traceId(), e.getMessage());
        }

        // ★ 关掉限流时不写这一行。
        //   理由和「关掉时 qa_log.queue_ms 记 NULL」是同一条：
        //   阶段 7 必须能分清「没开排队」和「开了但被挡了」——
        //   后者是容量问题，前者只是一次没启用的实验。
        //   ⚠️ 但【通知】仍然要发：关掉限流不代表 sse 池不会满，
        //      用户该收到一句说明而不是干等。
        if (!props.isEnabled()) {
            return;
        }

        try {
            qaLogService.save(rateLimitedLog(admission, queueMs, initialPosition, reason));
        } catch (Exception e) {
            // ★ 写库失败【不能】影响用户 —— 他已经被拒绝了，不该再收到一个
            //   「服务内部错误」。但这条要留痕，因为它是数据盲区的来源。
            log.warn("写限流 qa_log 失败 traceId={}: {}", admission.traceId(), e.getMessage());
        }
    }

    /**
     * 组装被限流那一行的 qa_log。
     *
     * <p>★★ <b>形状和澄清反问那行一致：没有发生的事就留空。</b>
     * {@code provider} / {@code model} / {@code tokens} / {@code cost} /
     * {@code final_answer} 全为 NULL —— 不是 0、不是空对象、不是空串。
     *
     * <p>★ {@code sessionId} 也是 NULL，而且<b>这是对的</b>：
     * 这次请求根本没走到「解析会话」那一步，会话都没被创建。
     * 为被拒绝的请求建一个会话会污染会话数据 ——
     * 那些会话永远不会有一条消息。
     *
     * @param initialPosition <b>刚入队时</b>前面有几个人；<b>为 null 表示从没进过队列</b>
     *                        （被「队列满」直接拒了，或者 queue- 池本身就满了）
     */
    private QaLog rateLimitedLog(Admission admission, long queueMs,
                                 Integer initialPosition, String reason) {
        QaLog log = new QaLog();
        log.setTraceId(admission.traceId());
        log.setUserId(admission.userId());
        log.setQuestion(admission.question());
        log.setStatus(QaLog.STATUS_RATE_LIMITED);

        // ════════════════════════════════════════════════════════════
        // ★★★ queue_ms 【不是】「哪一种拒绝」的判据 —— reason 才是
        // ════════════════════════════════════════════════════════════
        //
        // ★ 这个字段的注释在本项目里改过三次，每次错的都是同一件事：
        //   **用「等了多久」去回答「被什么拒的」**，而时长不携带那个信息。
        //
        //   ①（阶段 6.5）「立刻拒绝时它是 0」
        //        → 对「队列满」错（它是 currentTimeMillis 的差值，实测 2）
        //   ②（阶段 6.7）「它不可能是 0」
        //        → 对「池满」错（那两处 giveUp 传的就是字面量 0）
        //   ③（阶段 6.8）「0 = 池满，>0 = 队列满」
        //        → 全错。实测 queue_ms=0 在【两种原因下都出现过】：
        //
        //          排队线程池已满：等待 0ms   × 34
        //          队列已满：等待 2ms          × 2
        //          队列已满：等待 1ms          × 1
        //          队列已满：等待 0ms          × 1   ← ★ 判决性的一行
        //          排队超时：等待 3138ms       × 1
        //
        // ★ 三次的教训不是「这次要写对」，而是：**这一列根本不区分原因**。
        //   四种拒绝各自对症一个旋钮 ——
        //     「队列已满」→ maxQueue；「排队线程池已满」→ queuePool.maxPoolSize；
        //     「sse 线程池已满」→ answerExecutor 容量；「排队超时」→ permits。
        //   而它们的 queue_ms 可以完全相同。要分开它们，只能看 {@code error_msg}
        //   的原因串（下面的 {@code reason} 参数就是为它准备的）。
        //
        // ★ queue_ms 仍然能回答【量级】这一个问题：
        //     0 ~ 个位数     → 几乎是立刻就拒了
        //     接近排队上限   → 等满了预算才拒（只有「排队超时」是这样）
        //   ⚠️ 量级只能用来交叉验证，不能用来定性 —— 上面那张表就是反例。
        //
        // ⚠️ 还有第四种形状：{@code NULL} = 压根没走排队逻辑
        //   （{@code xbla.ratelimit.enabled=false}）。见 V9 的注释。
        log.setQueueMs((int) queueMs);

        // ★ null = 从没进过队列；0 = 进过、而且是队首。两者是两回事。
        log.setQueuePosition(initialPosition);

        log.setErrorMsg(reason + "：等待 " + queueMs + "ms"
                + (initialPosition == null ? "（未进队列）" : "（初始前面 " + initialPosition + " 人）"));
        return log;
    }

    /**
     * 把「等了多久」和「刚入队时的位置」翻译成 {@link CallContext}。
     *
     * <p>★★ <b>{@code queueMs} 只在【真的排过队】时才有值，否则是 null。</b>
     *
     * <p>这一点很容易写反（写成「总是记真实耗时」），而写反的后果很隐蔽：
     *
     * <pre>
     *   绝大多数请求都不会排队（名额够用），它们的 queue_ms 会是 1~5 毫秒
     *   → 「平均等待时长」被稀释到接近 0
     *   → 看起来像【排队功能根本没生效】
     * </pre>
     *
     * <p>而按「没排队 = null」来记，阶段 7 就能问出两个不同的问题：
     * <pre>
     *   有多少比例的人排过队   →  count(queue_ms) / count(*)
     *   排队的人平均等多久     →  avg(queue_ms) WHERE queue_ms IS NOT NULL
     * </pre>
     * 两个都比「所有人平均等 1.2 毫秒」有意义。
     *
     * @param initialPosition null = 从没进过队列
     */
    private static CallContext contextOf(Admission admission, long waited, Integer initialPosition) {
        Integer queueMs = initialPosition == null ? null : (int) waited;
        return new CallContext(admission.traceId(), queueMs, initialPosition);
    }

    /** 被限流挡掉的累计次数 —— 出在探针上 */
    public long givenUpTotal() {
        return givenUpTotal.get();
    }
}
