package com.xbla.rag.controller;

import com.xbla.rag.common.ApiResponse;
import com.xbla.rag.common.TraceId;
import com.xbla.rag.config.RateLimitProperties;
import com.xbla.rag.ratelimit.ChatAdmissionService;
import com.xbla.rag.ratelimit.ChatPermitService;
import com.xbla.rag.ratelimit.LocalPermitRegistry;
import com.xbla.rag.ratelimit.PermitSignalBus;
import com.xbla.rag.ratelimit.PermitSignalSubscriber;
import com.xbla.rag.ratelimit.RedisQueueKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 排队限流的调试探针（阶段 6.8）。
 *
 * <p>⚠️ {@code @Profile("local")} —— 和其它探针一样只在本地存在。
 * <b>它不调模型，不花钱</b>；但它是<b>可写</b>的（{@code leak} / {@code release} / {@code reset}
 * 会直接改 Redis），所以它比只读探针更依赖那层 {@code @Profile}。
 *
 * <h2>★★★ 为什么必须有「假流式端点」这种东西</h2>
 *
 * <p>阶段 6 要验的三条（无超卖、无死锁、名额不泄漏）<b>全都是并发性质</b>，
 * 而用真实问答去压它们有三个问题：
 *
 * <pre>
 *   ① 花钱，而且 100 并发是 <b>100~200 次 LLM 调用</b>
 *   ② 模型的延迟方差有 ±5%（见 docs/06 §1.4），
 *      <b>它会把我们要测的那些时序差异整个盖住</b>
 *   ③ 不可复现 —— 出了问题也不知道是排队错了还是模型慢了
 * </pre>
 *
 * <p>所以假端点的做法是：<b>把「占住一个名额多久」变成一个参数</b>，
 * 其余一律走真链路 —— 真的 {@code ChatAdmissionService.submit}、
 * 真的 Lua 抢名额、真的 ZSet 排队、真的 SSE 推送、真的 {@code queue-}/{@code answer-} 线程池、
 * 真的名额释放。
 *
 * <p>★ 唯一被替换掉的是<b>最后那一步</b>：不调 {@code ChatService}，
 * 只按 {@code holdMs} 把名额占着然后吐几个 delta。
 * 换句话说它测的是 <b>「排队层」本身</b>，而不是「排队层 + 模型」。
 * 后者由 S9 的真实 10 并发端到端负责（{@code probe_ratelimit.py --real}）。
 *
 * <h2>★ 编号和脚本的对应关系</h2>
 *
 * <p>{@code scripts/probe_ratelimit.py} 的八组断言是这样用这些端点的：
 *
 * <pre>
 *   组一 基础         fake-stream（单个）+ trace
 *   组二 无超卖       fake-stream × 100 + state（并发采样 permitsInUse）
 *   组三 无死锁       fake-stream × 100 + state（收尾查空）
 *   组四 位置准确     leak(permits=N) 冻住队列 → fake-stream × 4 + trace
 *   组五 队列上限     leak(permits=N, queue=maxQueue) → fake-stream 必须被如实拒绝
 *   组六 僵尸回收     leak(permits=N) → 等 TTL → fake-stream 才走得通
 *   组七 看门狗       fake-stream(holdMs &gt; TTL) 必须【不】被回收 ←→ 组六 被回收
 *   组八 拒绝路径     failed 事件而不是兜底 500 JSON
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/ratelimit")
@Profile("local")
public class RateLimitProbeController {

    /**
     * 假请求用的哨兵身份。
     *
     * <p>★ 为什么需要一个哨兵：假请求<b>被拒绝时也会写一行 qa_log</b>
     * （那是 {@code ChatAdmissionService.giveUp} 的正确行为 —— 被限流的请求
     * 在数据上不该消失）。而「假请求被拒绝」和「真用户被拒绝」是两回事，
     * 它们的行必须能被分开，否则<b>压测会污染真实的拒绝率</b>。
     *
     * <p>用 999_997 是因为 {@code app_user} 的 id 是个位数 ——
     * 它<b>不可能是真实用户</b>。（同 {@code ChatAdmissionBlockingTest} 里的 999_998。）
     */
    private static final long PROBE_USER_ID = 999_997L;

    /**
     * 假请求的问题文本前缀 —— 另一半可识别性。
     *
     * <p>★ 两半都要有：光靠 {@code user_id} 能定位「是哪一批」，
     * 光靠前缀能一眼看出「这一行不是真问题」。清理脚本两个条件都要用。
     */
    private static final String PROBE_QUESTION_PREFIX = "[probe] fake-stream ";

    /**
     * 占位时长上限 —— 纯粹是「别把自己玩坏」。
     *
     * <p>★ 为什么要有：{@code holdMs} 直接从 query 进来，而它决定的是
     * <b>一个名额被占多久</b>。一个手滑多打一个 0 的 {@code holdMs=800000}
     * 会把 8 个名额里的若干个锁 13 分钟 —— 而且看起来像「系统卡了」，
     * 不像「我传错参数了」。
     *
     * <p>★ 120 秒这个数不是拍的：它和 {@code queue-timeout} 取同一个值，
     * 也就是「一个人最多愿意等多久」。占位比它还久，那它占的那个名额
     * 已经超过任何等待者的耐心了。
     */
    private static final long MAX_HOLD_MS = 120_000L;

    /**
     * 假流式响应在「拿到名额」之后还留多少余量。
     *
     * <p>⚠️ 它和 {@code ChatController.ANSWER_BUDGET_MS} <b>不是同一个数，也不该是</b>：
     * 那边是「一次真实回答最多允许多久」，这里是「按参数占位最多允许多久」。
     * 硬要把它们合成一个常量，就是那个「两处各写一份、会漂移」的数
     * （见 {@code ChatAdmissionService.NON_STREAM_HARD_LIMIT_MS} 里同一段推理）。
     */
    private static final long FAKE_SLACK_MS = 10_000L;

    private final ChatAdmissionService admission;
    private final ChatPermitService permits;
    private final LocalPermitRegistry registry;
    private final PermitSignalBus signals;
    private final ObjectProvider<PermitSignalSubscriber> subscriber;
    private final RateLimitProperties props;
    private final StringRedisTemplate redis;
    private final ThreadPoolTaskExecutor queueExecutor;

    public RateLimitProbeController(ChatAdmissionService admission,
                                    ChatPermitService permits,
                                    LocalPermitRegistry registry,
                                    PermitSignalBus signals,
                                    // ★ ObjectProvider 而不是直接注入：订阅器带
                                    //   @ConditionalOnProperty(wakeup.enabled)。直接注入的话
                                    //   关掉唤醒会让【整个探针 Bean 创建失败】——
                                    //   而那时恰恰是最需要看探针的时候（正在验证「关掉它也正确」）。
                                    ObjectProvider<PermitSignalSubscriber> subscriber,
                                    RateLimitProperties props,
                                    StringRedisTemplate redis,
                                    @Qualifier("queueExecutor") ThreadPoolTaskExecutor queueExecutor) {
        this.admission = admission;
        this.permits = permits;
        this.registry = registry;
        this.signals = signals;
        this.subscriber = subscriber;
        this.props = props;
        this.redis = redis;
        this.queueExecutor = queueExecutor;
    }

    private RedisQueueKeys keys() {
        return RedisQueueKeys.from(props);
    }

    // ============================================================
    // 一、假流式端点
    // ============================================================

    /**
     * 走<b>完整排队链路</b>的假流式请求 —— 不调模型，只按参数占住名额。
     *
     * <pre>
     * curl -N "localhost:8080/api/debug/ratelimit/fake-stream?holdMs=3000"
     * </pre>
     *
     * <p>事件序列和真实流式<b>逐字对齐</b>（这是刻意的，见下）：
     *
     * <pre>
     *   event: queued     {"traceId":"…","position":3,"waitedMs":200}   ← 只在不排队时缺席
     *   event: admitted   {"traceId":"…","queueMs":600,"queuedAhead":3}
     *   event: meta       {"traceId":"…","sessionNo":"probe-fake",…}
     *   event: delta      {"v":"."}  × deltas
     *   event: done       {"traceId":"…","holdMs":3000,…}
     *   event: failed     {"message":"…"}     ← 被拒绝时才走这条
     * </pre>
     *
     * <p>★★ <b>为什么必须和真实流式的事件形状完全一样</b>：
     * 探针脚本断言的正是这些事件。如果假端点少推一个 {@code queued}、
     * 或者顺序和真的不一样，那么<b>压测通过这件事就不能推出真实链路也通过</b> ——
     * 而那正是 R17「假端点压测通过 ≠ 真实链路可用」说的那个风险。
     * 形状一致 + S9 的真实 10 并发，两件事一起才把那个风险关掉。
     *
     * @param holdMs 拿到名额之后占住多久。<b>0 表示立刻放手</b>
     * @param deltas 中间吐几段 delta（把 holdMs 切成几段，模拟打字机）
     * @param label  只回显，用来把并发中的某一条和日志对上
     */
    @GetMapping(value = "/fake-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter fakeStream(@RequestParam(name = "holdMs", defaultValue = "800") long holdMs,
                                 @RequestParam(name = "deltas", defaultValue = "4") int deltas,
                                 @RequestParam(name = "label", required = false) String label) {

        long hold = Math.min(Math.max(holdMs, 0L), MAX_HOLD_MS);
        int chunks = Math.min(Math.max(deltas, 1), 100);

        // ★ 超时是【算出来的】：排队预算 + 占位时长 + 余量。
        //   ⚠️ 它和 ChatController.sseTimeoutMs() 不是同一个数 —— 那边算的是
        //      「真实回答的预算」，这里是「按参数占位的预算」。见 FAKE_SLACK_MS。
        long queueBudget = props.isEnabled() ? props.getQueueTimeout().toMillis() : 0L;
        SseEmitter emitter = new SseEmitter(queueBudget + hold + FAKE_SLACK_MS);
        SseChannel channel = new SseChannel(emitter);

        String traceId = TraceId.newId();
        String question = PROBE_QUESTION_PREFIX + "holdMs=" + hold
                + (label == null ? "" : " label=" + label);

        admission.submit(
                // ★ 评测标记传 null（阶段 7）：探针流量**不是**评测流量。
                //   两者的区别是「谁在测谁」—— 探针测的是排队层自己（它连
                //   ChatService 都不调），而评测测的是问答链路。
                //   把探针标成评测会把「排队层的自检」混进 150 题的统计里。
                new ChatAdmissionService.Admission(traceId, question, PROBE_USER_ID, null),
                new QueueEventListener(channel, traceId),
                ctx -> runFake(channel, traceId, hold, chunks, label));

        return emitter;
    }

    /**
     * 假流式的「工作」—— 跑在 {@code answer-} 线程上。
     *
     * <p>★★ <b>它【不】管名额的释放</b>，那是 {@code ChatAdmissionService.runWithRelease}
     * 的 {@code finally} 干的事。这是刻意的：假端点如果自己释放，
     * 它就<b>绕开了被测试的那个机制</b> —— 而那正是 R1 说的「边跑边放名额」。
     *
     * <p>★ 每一段之间检查一次 {@link SseChannel#isClosed()}：
     * 客户端断开时立刻返回，让 {@code finally} 尽早释放。
     * 这<b>同时也验证了</b>「客户端断开 → 名额被及时归还」这条防线 ——
     * 而它是比「进程被强杀」常见得多的那种泄漏。
     */
    private void runFake(SseChannel channel, String traceId, long holdMs, int deltas, String label) {
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("traceId", traceId);
            // ★ sessionNo 用一个不可能与真实会话碰撞的值：假请求【不建会话】，
            //   给它一个真会话号会让人以为它进了会话表。
            meta.put("sessionNo", "probe-fake");
            meta.put("holdMs", holdMs);
            meta.put("deltas", deltas);
            meta.put("label", label);
            channel.send("meta", meta);

            long per = holdMs / deltas;
            for (int i = 0; i < deltas; i++) {
                if (channel.isClosed()) {
                    return;   // ★ 客户端走了 —— 直接返回，让 finally 去释放名额
                }
                if (per > 0) {
                    Thread.sleep(per);
                }
                channel.send("delta", Map.of("v", "."));
            }

            // ★ holdMs 除不尽时补上零头，让「占住多久」真的等于参数
            long spent = System.currentTimeMillis() - started;
            if (spent < holdMs) {
                Thread.sleep(holdMs - spent);
            }

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("traceId", traceId);
            done.put("holdMs", holdMs);
            done.put("deltas", deltas);
            done.put("label", label);
            done.put("startedAtMs", started);
            done.put("finishedAtMs", System.currentTimeMillis());
            done.put("serverNowMs", System.currentTimeMillis());
            channel.send("done", done);

        } catch (InterruptedException e) {
            // ★ 恢复中断位再退出：吞掉它会让线程池的关闭逻辑以为「没人在打断我」。
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // ★ 这里【必须】吞掉，而且不能吞得无声。
            //   它跑在 answer- 线程上，异常从 Runnable 里穿出去会变成
            //   ThreadPoolTaskExecutor 的一条「未捕获异常」堆栈 —— 而那时
            //   客户端断开是【完全正常的】，不该产生堆栈。
            log.debug("假流式中途结束 traceId={}：{}", traceId, e.getMessage());
        } finally {
            // ★ complete() 是幂等的：客户端已断开时它什么都不做，也不抛。
            channel.complete();
        }
    }

    /** 和 {@code ChatController.QueueEventListener} 同一形状 —— 见那处的注释 */
    private record QueueEventListener(SseChannel channel, String traceId)
            implements ChatAdmissionService.QueueListener {

        @Override
        public void onQueued(int position, long waitedMs) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", traceId);
            payload.put("position", position);
            payload.put("waitedMs", waitedMs);
            channel.sendQuietly("queued", payload);
        }

        @Override
        public void onAdmitted(long queueMs, Integer initialPosition) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", traceId);
            payload.put("queueMs", queueMs);
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

        @Override
        public boolean isCancelled() {
            return channel.isClosed();
        }
    }

    // ============================================================
    // 二、状态快照
    // ============================================================

    /**
     * 现在到底是什么样 —— <b>Redis 侧 + 本机侧 + 生效配置</b>，一次看全。
     *
     * <pre>
     * curl -s localhost:8080/api/debug/ratelimit/state | python -m json.tool
     * </pre>
     *
     * <p>★★ 压测的采样线程就是每 20ms 拉一次这里的 {@code redis.permitsInUse}，
     * 记下它的<b>最大值</b>。那个最大值 &gt; {@code permitLimit} 就是超卖 ——
     * 这是验收标准 1 的核心断言。
     *
     * <p>★ <b>为什么读 Redis 而不是读本地变量</b>：本地那个数只会说
     * 「我以为有几个名额在用」。真出问题时，<b>「我以为的」和「Redis 里的」之间的差
     * 就是全部线索</b> —— 所以两个都要打出来，让它们并排可见。
     * （{@code RedisQueueKeys} 的注释里有同一条推理。）
     */
    @GetMapping("/state")
    public ApiResponse<Map<String, Object>> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("redis", permits.redisState());
        out.put("local", permits.localState());
        out.put("config", permits.config());

        // ★ 唤醒链路的观测量。amplification（一次广播平均叫醒几个人）
        //   是「Pub/Sub 到底有没有在起作用」唯一可量化的出口。
        out.put("signals", signals.snapshot());

        PermitSignalSubscriber sub = subscriber.getIfAvailable();
        Map<String, Object> subscription = new LinkedHashMap<>();
        subscription.put("enabled", props.getWakeup().isEnabled());
        subscription.put("listening", sub != null && sub.isListening());
        // ★ 尝试次数 > 1 就说明「订阅断过、而且自愈过」。它是冷启动那个
        //   bug（容器 started 标志不重置）的直接证据 —— 见 PermitSignalSubscriber。
        subscription.put("attempts", sub == null ? null : sub.subscriptionAttempts());
        out.put("subscription", subscription);

        // ★★ 排队线程池 —— 这个数在阶段 6.9 之前【在探针上完全看不见】，
        //   而它是一次真实的踩坑：`queue-` 池被上一组打满之后，
        //   下一组的请求全部被【同步】拒绝（原因=排队线程池已满），
        //   而 /state 上一切正常（permitsInUse=0、queueSize=0）——
        //   ★ 于是「被拒了」这件事在仪表盘上没有任何对应物。
        //
        //   ⚠️ 看的是 active 而不是 poolSize：ThreadPoolTaskExecutor 的
        //      core 线程默认【不回收】，所以空闲时 poolSize 仍然是 32。
        //      用 poolSize 判断「忙不忙」会永远看到「忙」。
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("active", queueExecutor.getActiveCount());
        pool.put("poolSize", queueExecutor.getPoolSize());
        pool.put("max", props.getQueuePool().getMaxPoolSize());
        out.put("queuePool", pool);

        out.put("givenUpTotal", admission.givenUpTotal());
        return ApiResponse.ok(out);
    }

    /**
     * 某个 traceId 在<b>四个结构里各自的处境</b> —— 一次看全，不猜。
     *
     * <pre>
     * curl -s "localhost:8080/api/debug/ratelimit/trace?traceId=xxx" | python -m json.tool
     * </pre>
     *
     * <p>★★ 它存在的理由是<b>组四那条断言</b>：我们推给前端的位置
     * （{@code queued.position}）和 Redis 实际排行（{@code ZRANK}）
     * <b>必须逐字相等</b>。这两个数分别来自「Java 侧的一次读」和「推出去的一个事件」，
     * 中间隔了一整个 SSE 往返 —— <b>对不上就说明有一处在算别的东西</b>，
     * 而那种错在界面上看起来只是「等得有点久」。
     *
     * <p>★ {@code permitRemainingMs} 是名额还剩多久过期。
     * 它是<b>看门狗有没有在跑的可见证据</b>：一个真实占位的请求，
     * 这个数会一直在 {@code permit-ttl} 附近徘徊；
     * 而 {@code leak} 出来的那个会一路减少到 0 然后消失。
     */
    @GetMapping("/trace")
    public ApiResponse<Map<String, Object>> trace(@RequestParam("traceId") String traceId) {
        RedisQueueKeys k = keys();
        long now = System.currentTimeMillis();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traceId", traceId);

        // ① 名额表：在不在、什么时候过期
        Double slotExpiry = redis.opsForZSet().score(k.slots(), traceId);
        out.put("holdsPermit", slotExpiry != null);
        out.put("permitExpiresAtMs", slotExpiry == null ? null : slotExpiry.longValue());
        out.put("permitRemainingMs", slotExpiry == null ? null : slotExpiry.longValue() - now);

        // ② 排队表：位置 + 入队序号。
        //    ★ rank 【不 +1】—— 它就是推给前端的那个 position（「前面还有 N 位」）。
        //      这里 +1 的话就和前端看到的对不上了，而那正是本接口要查的东西。
        Long rank = redis.opsForZSet().rank(k.queue(), traceId);
        Double seq = redis.opsForZSet().score(k.queue(), traceId);
        out.put("queueRank", rank);
        out.put("queueSeq", seq == null ? null : seq.longValue());

        // ③ 存活表
        Double aliveAt = redis.opsForZSet().score(k.alive(), traceId);
        out.put("lastBeatAtMs", aliveAt == null ? null : aliveAt.longValue());
        // ★ 心跳的年龄。它超过 permit-ttl 就会被 cleanup 当成死人在队列里清掉 ——
        //   ⚠️ 注意它【只影响排队表】，名额表的清理由 score 自己决定（见 cleanup.lua）。
        out.put("beatAgeMs", aliveAt == null ? null : now - aliveAt.longValue());

        // ④ 本机登记表 —— 决定「谁会被续期」
        out.put("heldHere", registry.isHeld(traceId));
        out.put("waitingHere", registry.isWaiting(traceId));
        // ★★ 这一条是 leak 端点的照妖镜：leak 出来的 id 在 Redis 里存在，
        //    但这两个都是 false —— 于是【没人续期它】，它必然过期。
        out.put("renewedByThisProcess", registry.isHeld(traceId));

        out.put("nowMs", now);
        return ApiResponse.ok(out);
    }

    // ============================================================
    // 三、故意泄漏（组四 / 组五 / 组六 的前置）
    // ============================================================

    /**
     * 名额表里<b>到底有谁</b> —— 一个一个列出来，带过期时间。
     *
     * <pre>
     * curl -s "localhost:8080/api/debug/ratelimit/slots" | python -m json.tool
     * </pre>
     *
     * <h2>★★★ 它存在的理由：{@code ZCARD} 偏大时，你要知道多出来的是【谁】</h2>
     *
     * <p>{@code /state} 报的 {@code permitsInUse} 就是 {@code ZCARD(slots)}。
     * 「8 个上限，实测 14」这个现象有<b>三种成因，修法完全不同</b>：
     *
     * <pre>
     *   ① 已过期但还没被扫掉   →  修「什么时候扫」（R7），不是容量问题
     *   ② 心跳把已释放的复活了  →  修 release 的删除顺序
     *   ③ 分配逻辑真的超卖了    →  修 Lua
     * </pre>
     *
     * <p>★ {@code /state} 只能告诉你「多了 6 个」，<b>告诉不了你是哪一种</b>。
     * 而本项目已经在这三种之间反复猜过一次（那次猜了三轮），
     * 所以这里把「多出来的是谁」变成一次直接的读。
     *
     * <p>★ 判断方法是逐条看 {@code remainingMs} 和 {@code renewedByThisProcess}：
     *
     * <pre>
     *   remainingMs &lt; 0 且 renewed=false  →  ①  僵尸，等下一次 acquire 顺手清
     *   renewed=true 但持有人早就结束了  →  ②  心跳复活的，是本类注释警告过的那个坑
     *   renewed=true 且确实有这么多在跑  →  ③  真超卖
     * </pre>
     */
    @GetMapping("/slots")
    public ApiResponse<Map<String, Object>> slots() {
        RedisQueueKeys k = keys();
        long now = System.currentTimeMillis();

        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> entries =
                redis.opsForZSet().rangeWithScores(k.slots(), 0, -1);

        List<Map<String, Object>> members = new ArrayList<>();
        int expired = 0;
        int renewed = 0;
        for (var e : entries == null
                ? java.util.Collections.<org.springframework.data.redis.core.ZSetOperations
                        .TypedTuple<String>>emptySet()
                : entries) {
            long expiresAt = e.getScore() == null ? 0L : e.getScore().longValue();
            boolean isRenewed = registry.isHeld(e.getValue());
            if (expiresAt <= now) {
                expired++;
            }
            if (isRenewed) {
                renewed++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("traceId", e.getValue());
            m.put("remainingMs", expiresAt - now);
            m.put("expired", expiresAt <= now);
            m.put("renewedByThisProcess", isRenewed);
            members.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", members.size());
        out.put("permitLimit", props.getPermits());
        out.put("expiredNotSwept", expired);
        out.put("renewedHere", renewed);
        out.put("heldHere", registry.heldCount());
        out.put("members", members);
        return ApiResponse.ok(out);
    }

    /**
     * 造出<b>「有人占着、但没人续期」</b>的僵尸 —— 名额和排队槽位都能造。
     *
     * <pre>
     * # 占满全部名额（后面来的请求只能排队）
     * curl -s -X POST "localhost:8080/api/debug/ratelimit/leak?permits=8"
     * # 再把队列也填满（后面来的请求会被如实拒绝）
     * curl -s -X POST "localhost:8080/api/debug/ratelimit/leak?queue=500"
     * </pre>
     *
     * <h2>★★★ 它【必须绕过 ChatPermitService】—— 这是整个端点唯一的设计要点</h2>
     *
     * <p>走 {@code permits.tryAcquire()} 的话，那些 id 会被写进
     * {@link LocalPermitRegistry}，于是<b>本进程的看门狗会替它们续期</b> ——
     * 它们永远不会过期。后果是：
     *
     * <pre>
     *   组六「僵尸回收 ≤ TTL」会【必然通过】（因为根本没有僵尸要回收）
     *   组七「看门狗不被误抢」也会通过
     *   → 而这个端点就变成了「测试自己让自己通过」
     * </pre>
     *
     * <p>★ 这正是 R4。所以这里直接用 {@link StringRedisTemplate} 写 Redis，
     * <b>一个字都不碰登记表</b> —— 于是这些 id 在结构上等同于
     * 「另一个进程抢了名额然后被 kill -9」，那才是我们要模拟的东西。
     *
     * <h2>★ 为什么让 {@code alive} 也带新时间戳</h2>
     *
     * <p>队列里的槽位是靠 {@code cleanup.lua} 比对 {@code alive} 来清僵尸的
     * （{@code queue} 的 score 是序号，不自描述 —— 见 {@code RedisQueueKeys}）。
     * 一个<b>压根不在 {@code alive} 里</b>的队列成员反而清不掉：
     * {@code ZRANGEBYSCORE alive} 扫不到它。
     *
     * <p>★ 所以造出来的每个僵尸都要在 {@code alive} 里留一个<b>新的</b>时间戳 ——
     * 这样它看起来「刚刚还活着」，然后因为没人续期而在 TTL 之后被判定为死。
     * 少这一步，{@code leak} 出来的队列成员就是<b>永久僵尸</b>，
     * 而「永久」和「TTL 之后」的差别恰好是验收标准 3。
     *
     * @param permits 造几个「占着名额」的僵尸
     * @param queue   造几个「在排队」的僵尸
     */
    @PostMapping("/leak")
    public ApiResponse<Map<String, Object>> leak(@RequestParam(name = "permits", defaultValue = "1") int permits,
                                                 @RequestParam(name = "queue", defaultValue = "0") int queue) {
        if (!props.isEnabled()) {
            // ★ 关掉限流时造僵尸是没有意义的 —— 那时 acquire 根本不看这些表。
            //   如实说一句，而不是造一堆没人会读的数据。
            return ApiResponse.ok(notice("xbla.ratelimit.enabled=false，排队限流没在跑，"
                    + "leak 造出来的僵尸没有任何东西会去读它"));
        }

        RedisQueueKeys k = keys();
        long now = System.currentTimeMillis();
        long expiresAt = now + props.getPermitTtl().toMillis();

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < Math.max(permits, 0); i++) {
            String id = "leak-p-" + UUID.randomUUID();
            redis.opsForZSet().add(k.slots(), id, expiresAt);
            redis.opsForZSet().add(k.alive(), id, now);
            ids.add(id);
        }
        for (int i = 0; i < Math.max(queue, 0); i++) {
            String id = "leak-q-" + UUID.randomUUID();
            Long seq = redis.opsForValue().increment(k.seq());
            redis.opsForZSet().add(k.queue(), id, seq == null ? 0 : seq);
            redis.opsForZSet().add(k.alive(), id, now);
            ids.add(id);
        }

        log.info("探针制造僵尸：{} 个名额 + {} 个排队槽位（都不登记，{}ms 后自行过期）",
                permits, queue, props.getPermitTtl().toMillis());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leakedPermits", permits);
        out.put("leakedQueueSlots", queue);
        out.put("expiresAtMs", expiresAt);
        out.put("permitTtlMs", props.getPermitTtl().toMillis());
        // ★ 列表给全了也没关系，但它会很长（500 个 UUID ≈ 20KB），
        //   而组四只需要头几个。截断 + 明说截断了，比悄悄少给几个安全。
        int cap = 20;
        out.put("ids", ids.size() <= cap ? ids : List.copyOf(ids.subList(0, cap)));
        out.put("idsTruncated", ids.size() > cap);
        out.put("idCount", ids.size());
        out.put("registeredLocally", false);   // ★ 显式打出来：这个 false 是端点存在的理由
        return ApiResponse.ok(out);
    }

    // ============================================================
    // 四、释放 / 复位
    // ============================================================

    /**
     * 释放一个 traceId（包括 leak 造出来的），走<b>正式的</b> release 脚本。
     *
     * <pre>
     * curl -s -X POST "localhost:8080/api/debug/ratelimit/release?traceId=leak-p-…"
     * </pre>
     *
     * <p>★ 组四要靠它把冻住的队列「化开」：{@code leak} 占满名额 → 4 个请求排队
     * → 逐个 {@code release} → 观察那 4 个请求收到的 {@code position}
     * <b>只减不增</b>。少了这个端点，组四就只能测「静止时的位置对不对」，
     * 测不到「位置在动的时候会不会乱跳」。
     */
    @PostMapping("/release")
    public ApiResponse<Map<String, Object>> release(@RequestParam("traceId") String traceId) {
        permits.release(traceId);

        RedisQueueKeys k = keys();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traceId", traceId);
        // ★ 回读一遍而不是断言 release 成功了：release 是「尽力而为」的
        //   （失败只记日志，靠 TTL 兜底），所以「我说我释放了」和
        //   「它真的不在了」是两件事，这里给的是后者。
        out.put("stillHoldsPermit", redis.opsForZSet().score(k.slots(), traceId) != null);
        out.put("stillInQueue", redis.opsForZSet().rank(k.queue(), traceId) != null);
        return ApiResponse.ok(out);
    }

    /**
     * 清空四个 key + 本机登记表 —— <b>让下一组断言从一个已知状态开始</b>。
     *
     * <pre>
     * curl -s -X POST localhost:8080/api/debug/ratelimit/reset
     * </pre>
     *
     * <p>★★ <b>它是破坏性的，而且不区分「谁的」名额</b> ——
     * 正在跑的请求会静默失去名额。所以它只该在没有真实流量时调用，
     * 这也是它只存在于 {@code @Profile("local")} 的一部分理由。
     *
     * <p>★ 三个动作缺一不可：
     * <ul>
     *   <li>删 Redis 四个 key —— 否则上一组的 {@code leak} 残留会被下一组读到</li>
     *   <li>{@link LocalPermitRegistry#clearAll()} —— 否则<b>上一组留下的 traceId
     *       会被看门狗 {@code ZADD} 回名额表</b>（那个「名额复活」的坑，
     *       见 {@code LocalPermitRegistry} 类注释第二节）。
     *       只在测试里出现过一次的现象：断言看到 3 个名额，而失败信息指向断言。</li>
     *   <li>★★ {@link PermitSignalBus#resetCounters()} —— <b>这个是后补的，
     *       而少了它的后果很隐蔽</b>：那些计数器是<b>进程累计</b>的，
     *       于是「本次压测唤醒了几次」根本读不出来 —— 读到的是「这个 JVM 启动以来
     *       唤醒了几次」。实测：同样的配置、同样的组，第一次跑 {@code waitsTotal=3456}，
     *       在同一个 app 上再跑一次变成 {@code 9032}。
     *       ⚠️ 那个数在报告里是要拿来对比 A/B 的（开/关 Pub/Sub 各跑一遍），
     *      <b>一个会随「跑第几遍」变化的数没法对比任何东西</b>。</li>
     * </ul>
     *
     * <p>⚠️ 仍然有一个累计量清不掉：{@code ChatAdmissionService.givenUpTotal()}
     * （它没有 reset 方法）。它只出现在 {@code /state} 里供人看，
     * <b>本项目的探针不对它做断言</b> —— 如果将来要断言它，先给服务加一个 reset。
     *
     * <p>⚠️ {@code clearAll()} 的语义是「放弃续期」而不是「释放」——
     * 这里顺序是先删 Redis 再清本地，所以不存在「删了本地却留下 Redis 名额，
     * 而它因为没人续期而变成慢速僵尸」的窗口。这个顺序和
     * {@code ChatPermitService.release} 的<b>正好相反，而且是对的</b>：
     * 那边是「一次操作一个 id，必须防止心跳复活它」，
     * 这边是「整张表一起清掉，不存在复活的问题」。
     */
    @PostMapping("/reset")
    public ApiResponse<Map<String, Object>> reset() {
        RedisQueueKeys k = keys();
        Long deleted = redis.delete(k.allKeys());

        int held = registry.heldCount();
        int waiting = registry.waitingCount();
        registry.clearAll();

        // ★★ 计数器也要清 —— 否则「本次唤醒了几次」读不出来，
        //   读到的是「这个 JVM 启动以来唤醒了几次」。见方法注释第三节。
        signals.resetCounters();

        log.info("探针复位：删掉 {} 个 key，放弃对 {} 个持有 / {} 个等待的续期，信号计数器清零",
                deleted, held, waiting);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deletedKeys", deleted);
        out.put("abandonedHeld", held);
        out.put("abandonedWaiting", waiting);
        out.put("queueSizeAfter", redis.opsForZSet().zCard(k.queue()));
        out.put("permitsInUseAfter", redis.opsForZSet().zCard(k.slots()));
        out.put("signalsReset", true);
        return ApiResponse.ok(out);
    }

    private static Map<String, Object> notice(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skipped", true);
        out.put("reason", message);
        return out;
    }
}
