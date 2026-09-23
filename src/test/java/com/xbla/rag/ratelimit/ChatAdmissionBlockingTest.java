package com.xbla.rag.ratelimit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xbla.rag.config.RateLimitProperties;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.service.CallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ChatAdmissionService.submitAndWait} —— 非流式的阻塞受理（阶段 6.7）。
 *
 * <h2>★ 它和 HTTP 层那个测试（{@code ChatControllerQueueRejectedTest}）分工不同</h2>
 *
 * <pre>
 * ChatControllerQueueRejectedTest :  HTTP 层长什么样（状态码、Retry-After、消息）
 * 这个类                          :  受理层本身的行为，【不起 Web 环境】，
 *                                   所以能精确控制「谁占着名额、什么时候放」
 * </pre>
 *
 * <p>★ 分开的理由：把「名额怎么流转」和「HTTP 回什么」混在一个测试里，
 * 失败时看到的是一个 HTTP 断言，而真正的原因可能在排队逻辑 ——
 * 两处的修法完全不同。
 *
 * <h2>★★★ 一个踩过的坑：不要手搓 {@code RateLimitProperties} 的拷贝</h2>
 *
 * <p>第一版为了「同一组依赖、不同的排队预算」，手动 {@code new} 了一个
 * {@code RateLimitProperties} 拷贝塞进 {@code ChatAdmissionService}。它<b>看起来</b>很好用 ——
 * 超时那条测试确实按拷贝里的 600ms 走了。
 *
 * <p>★ 但它<b>只对一半的配置生效</b>，而且失败信息完全指不到这里：
 *
 * <pre>
 *   nonStreamQueueTimeout → ChatAdmissionService 自己读   → 拷贝生效 ✅
 *   maxQueue              → ChatPermitService 读它【自己的】props → 拷贝无效 ❌
 * </pre>
 *
 * <p>现象是：把拷贝里的 {@code maxQueue} 设成 0，请求却<b>照常进了队列</b>，
 * 然后在超时之后带着一句「排队等待超过 N 秒」失败 ——
 * 而那条测试断言的是「排队人数过多」，于是失败信息指向消息文案，
 * <b>而真正的原因在「配置根本没送到」</b>。
 *
 * <p>★ 教训：<b>配置的读取点分散在多个 bean 里时，任何「改一份拷贝」的手法都是
 * 半有效的</b>。所以这里全部改用类级 {@code @SpringBootTest(properties = ...)} ——
 * 它走的是完整的绑定链，每个 bean 拿到的都是同一份。
 *
 * <h2>★ 前置条件</h2>
 * <p>需要 {@code docker compose up -d} 起的 Redis 和 postgres。
 */
@SpringBootTest(properties = {
        "xbla.ratelimit.permits=1",
        "xbla.ratelimit.poll-interval=100ms",
        "xbla.ratelimit.permit-ttl=45s",
        // ★★ 见类注释第二节：全部走类级配置，不手搓拷贝。
        //   1.2 秒的排队预算让「超时」和「队列满」都能快速测出来；
        //   而 happy path 要么不排队、要么 600ms 内就放行，留了 2 倍余量。
        "xbla.ratelimit.non-stream-queue-timeout=1200ms",
        // ★ 1 而不是 0：0 意味着谁都进不了队，那样两个拒绝用例就分不开了。
        //   1 让我们能用一个「占位请求」把队列填满，从而精确测到 QUEUE_FULL。
        "xbla.ratelimit.max-queue=1",
        "xbla.ratelimit.key-prefix=xbla:rl:{blocking}",
        "xbla.ratelimit.channel=xbla:rl:blocking-events"
})
@DisplayName("ChatAdmissionService · 非流式阻塞受理（真 Redis）")
class ChatAdmissionBlockingTest {

    private static final String HOLDER = "holder-of-the-only-permit";

    /**
     * 哨兵身份 —— <b>只为了能把自己写的那行 qa_log 删干净</b>。
     *
     * <p>★ {@code qa_log} 是阶段 7 评测数据的来源，测试留下的行会进评测集。
     * 所以它必须靠一个「不可能是真实用户」的值来定位。
     */
    private static final long SENTINEL_USER_ID = 999_998L;

    @Autowired
    private ChatAdmissionService admission;

    @Autowired
    private ChatPermitService permits;

    @Autowired
    private LocalPermitRegistry registry;

    @Autowired
    private PermitSignalBus bus;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RateLimitProperties props;

    @Autowired
    private QaLogMapper qaLogMapper;

    @AfterEach
    void cleanUp() {
        redis.delete(RedisQueueKeys.from(props).allKeys());
        registry.clearAll();
        bus.resetCounters();
        qaLogMapper.delete(new LambdaQueryWrapper<QaLog>().eq(QaLog::getUserId, SENTINEL_USER_ID));
    }

    private static ChatAdmissionService.Admission admission(String traceId) {
        return new ChatAdmissionService.Admission(traceId, "退货要几天", SENTINEL_USER_ID, null);
    }

    /**
     * 等一行 qa_log 出现。
     *
     * <p>★★ <b>不是「为了稳妥多等一会儿」，而是一个真实的时序：</b>
     * {@code giveUp()} 是<b>先通知用户、再写库</b>的（那个顺序是刻意的，见它的注释：
     * 写库可能失败，而用户不该因为这个再等）。所以 future 一完成、
     * 调用方拿到异常之后，那一行<b>可能还没写进去</b>。
     *
     * <p>★ 用轮询而不是 {@code Thread.sleep(200)}：sleep 是「希望够了」，
     * 轮询是「够了就继续」。前者在慢机器上会偶发变红，而偶发红的测试比没有测试更糟。
     */
    private QaLog awaitQaLog(String traceId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            QaLog row = qaLogMapper.selectOne(
                    new LambdaQueryWrapper<QaLog>().eq(QaLog::getTraceId, traceId));
            if (row != null) {
                return row;
            }
            Thread.sleep(20);
        }
        return null;
    }

    /** 提交一个「只排队、不干活」的占位请求，把队列填满 */
    private RecordingQueueListener occupyQueueSlot(String traceId) throws InterruptedException {
        RecordingQueueListener dummy = new RecordingQueueListener();
        admission.submit(admission(traceId), dummy, ctx -> {
        });
        assertTrue(dummy.awaitQueued(),
                "占位请求没能进入队列 —— 前置条件不成立，后面的断言都没有意义");
        return dummy;
    }

    // ============================================================
    // 一、跑通
    // ============================================================

    @Nested
    @DisplayName("一、跑通")
    class HappyPath {

        @Test
        @DisplayName("★ 有名额立刻跑，work 的返回值原样传回，且【没有排队痕迹】")
        void freePermitRunsImmediately() {
            AtomicReference<CallContext> seen = new AtomicReference<>();

            String answer = admission.submitAndWait(admission("free-permit"), ctx -> {
                seen.set(ctx);
                return "答案";
            });

            assertEquals("答案", answer, "work 的返回值必须原样传回来");
            assertNotNull(seen.get(), "work 必须被跑到");
            assertNotNull(seen.get().traceId());
            // ★ 从没排过队 → queueMs 是 null（不是 0）。见 V9 与 CallContext 的注释。
            assertNull(seen.get().queueMs(),
                    "★ 没排队时 queue_ms 必须是 null。记 0 的话「平均等待时长」会被稀释成接近 0 ——"
                            + "因为绝大多数请求都不排队，那个平均值会看起来像【排队根本没生效】");
            assertNull(seen.get().queuePosition());
        }

        @Test
        @DisplayName("★★★ 正-反对照：名额被别人占着时，work【一次都不该跑】")
        void waitsForThePermitInsteadOfBypassingIt() throws Exception {
            assertTrue(permits.tryAcquire(HOLDER).isGranted(), "前置：占住唯一的名额");

            AtomicBoolean ran = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<CallContext> seen = new AtomicReference<>();

            Thread caller = new Thread(() -> {
                try {
                    admission.submitAndWait(admission("must-wait"), ctx -> {
                        seen.set(ctx);
                        ran.set(true);
                        return "跑到了";
                    });
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            caller.setDaemon(true);
            caller.start();
            caller.join(700);          // ★ 远小于 1200ms 的排队预算，所以它还在等

            // ★★★ 这一条是整节的承重墙。
            assertFalse(ran.get(),
                    "★★★ 名额被别人占着时 work【一次都不该跑】。\n"
                            + "   它跑了意味着 submitAndWait 绕过了名额表 —— 而那正是限流的全部意义，\n"
                            + "   并且这种绕过【不会报错】：并发悄悄超过 permits，只有压测才看得出来");

            // ── 反：放开名额，它必须跑完 ──
            permits.release(HOLDER);
            caller.join(10_000);

            assertNull(failure.get(), "不该抛异常，实际：" + failure.get());
            assertTrue(ran.get(), "★ 名额释放之后它必须拿到名额并跑完 —— "
                    + "★ 这一半是让上面那半有意义的那个：少了它，「没跑」可能只是「永远跑不了」");
            assertNotNull(seen.get().queueMs(), "它排过队，queue_ms 必须非 null");
        }

        @Test
        @DisplayName("★★ 排过队时，ctx 里带的是【刚入队那一刻】的位置")
        void queuedContextCarriesInitialPosition() throws Exception {
            assertTrue(permits.tryAcquire(HOLDER).isGranted(), "前置：占住唯一的名额");

            AtomicReference<CallContext> seen = new AtomicReference<>();
            Thread caller = new Thread(() -> seen.set(
                    admission.submitAndWait(admission("position-check"), ctx -> {
                        seen.set(ctx);
                        return ctx;
                    })));
            caller.setDaemon(true);
            caller.start();

            // ★ 等它真的排上队再释放 —— 否则释放可能发生在它入队之前，
            //   那样它压根没排过队，位置会是 null，而失败信息会指向错误的方向。
            Thread.sleep(400);
            permits.release(HOLDER);
            caller.join(10_000);

            CallContext ctx = seen.get();
            assertNotNull(ctx, "work 没跑起来");
            assertNotNull(ctx.queueMs(), "★ 排过队，queue_ms 必须非 null");
            assertTrue(ctx.queueMs() >= 0);
            assertNotNull(ctx.queuePosition(),
                    "★★ 排过队就必须有【入队时的位置】。★ 记「拿到名额时」的位置的话它恒为 0 ——"
                            + "一整列 0 看起来像数据，实际不携带任何信息（见 V9 的注释）");
        }
    }

    // ============================================================
    // 二、被拒绝：抛领域异常，并且留下 qa_log
    // ============================================================

    @Nested
    @DisplayName("二、被拒绝")
    class Rejected {

        @Test
        @DisplayName("★★ 等太久 → 抛 QueueRejectedException，并且写了一行 qa_log status=4")
        void timeoutThrowsDomainExceptionAndLeavesATrace() throws Exception {
            assertTrue(permits.tryAcquire(HOLDER).isGranted(), "前置：占住唯一的名额");

            QueueRejectedException thrown = assertThrows(QueueRejectedException.class,
                    () -> admission.submitAndWait(admission("will-timeout"), ctx -> "永远跑不到"));

            // ★★ 断言的是【具体的类型】而不是 RuntimeException：
            //   GlobalExceptionHandler 按类型匹配，抛一个包装过的异常，
            //   HTTP 层就认不出它，会回 500 而不是 503。
            //   ★ 它和 ChatControllerErrorMappingTest 是同一件事的两端：
            //     那边测「类型到 HTTP 层还认得吗」，这边测「抛出来的类型对不对」。
            assertTrue(thrown.getMessage().contains("排队"),
                    "消息要是那句给用户看的人话，实际：" + thrown.getMessage());
            assertTrue(thrown.queueMs() >= 1_000,
                    "★ 它是等满预算之后被拒的，所以 queue_ms 该接近 1200ms，实际 " + thrown.queueMs());

            // ★★ 被拒绝的请求在数据上【不能消失】：
            //   它根本没进 ChatService，不写这一行就一行都不留。
            //   越忙越需要数据，恰恰越忙丢得越多。
            QaLog row = awaitQaLog("will-timeout");   // ★ 见 awaitQaLog 的注释：这里有一个真实的时序
            assertNotNull(row, "被拒绝的请求必须留下一行 qa_log —— 否则它在数据上完全不存在");
            assertEquals(QaLog.STATUS_RATE_LIMITED, row.getStatus(),
                    "★ status=4 是「被限流拒绝」的专用值 —— 它不能被混进 status=2（模型失败），"
                            + "否则阶段 7 的评测会把「太忙了」算成「模型不行」");
            assertNotNull(row.getQueueMs(), "★ 被拒绝也是【排过队的】，queue_ms 不该是 null");
            assertNotNull(row.getErrorMsg(), "要说清是「人太多」还是「等太久」");
        }

        @Test
        @DisplayName("★ 队列满 → 也是领域异常；判据是 error_msg 的原因串，不是 queue_ms")
        void queueFullIsDistinguishableFromTimeout() throws Exception {
            assertTrue(permits.tryAcquire(HOLDER).isGranted(), "前置：占住唯一的名额");
            occupyQueueSlot("queue-filler");   // ★ 把唯一的那个队列位置占掉

            QueueRejectedException thrown = assertThrows(QueueRejectedException.class,
                    () -> admission.submitAndWait(admission("queue-is-full"), ctx -> "跑不到"));

            // ★ 两种拒绝给用户的话必须不同：
            //   「人太多」的下一步动作是「等一会儿再来」，
            //   「等太久」的下一步是「换个时间」。用同一句话糊过去，用户就不知道该做什么。
            assertTrue(thrown.getMessage().contains("人"),
                    "消息该说的是「排队人数过多」，实际：" + thrown.getMessage());

            // ════════════════════════════════════════════════════════════
            // ★★★ 判「是哪一种拒绝」的据是 error_msg 的原因串，【不是】queue_ms
            // ════════════════════════════════════════════════════════════
            //
            // ★ 这个断言在这里改过三次，每次错的都是同一件事 ——
            //   **用「等了多久」去判「被什么拒的」**，而时长根本不携带那个信息：
            //
            //     ① assertEquals(0, queueMs)   假设「立刻拒绝 ⇒ 0」→ 实测 2 → 红
            //     ② assertTrue(queueMs >= 1)   假设「走过排队 ⇒ 至少 1ms」→ 实测 0 → 红
            //        ⚠️ ② 一开始是绿的，只是因为量到的通常是 2 毫秒。
            //           全量测试跑第二遍时它红了，而那次红是【对的】：
            //           整个路径完全可以在同一毫秒内跑完。
            //
            // ★★ 实测数据（qa_log，status=4，阶段 6 压测）：
            //
            //     排队线程池已满：等待 0ms    × 34
            //     队列已满：等待 2ms           × 2
            //     队列已满：等待 1ms           × 1
            //     队列已满：等待 0ms           × 1   ← ★ 和「池满」撞在同一个数上
            //     排队超时：等待 3138ms        × 1
            //
            //   最后一行是判决：**queue_ms = 0 不属于任何一种拒绝**。
            //   四种拒绝各自对症一个旋钮（maxQueue / queuePool.maxPoolSize /
            //   answerExecutor 容量 / permits），而它们的 queue_ms 可以完全相同。
            //
            // ★ 所以分开它们的只有 giveUp 里那个 reason 参数 ——
            //   它本来就是为了这个才存在的，只是之前没人（包括我）去断言它。
            QaLog row = awaitQaLog("queue-is-full");
            assertNotNull(row, "被拒绝的请求必须留下一行 qa_log —— 和超时那条一样");
            assertTrue(row.getErrorMsg() != null && row.getErrorMsg().startsWith("队列已满"),
                    "★★ 判据是 error_msg 的原因串，不是 queue_ms。\n"
                            + "   「队列已满」和「排队线程池已满」是两个不同的容量问题，\n"
                            + "   对症的旋钮不同（maxQueue vs queuePool.maxPoolSize），\n"
                            + "   而它们的 queue_ms 实测都可能是 0。实际：" + row.getErrorMsg());

            // ★ queue_ms 仍然有一个它能回答的问题：**量级**。
            //   它必须【明显小于】排队预算 —— 证明它没等满 1200ms，
            //   也就是走的不是「超时」那条路。
            //   ⚠️ 但这一条本身不足以定性（池满也是 0），所以上面那条才是主判据。
            assertTrue(thrown.queueMs() >= 0
                            && thrown.queueMs() < props.getNonStreamQueueTimeout().toMillis() / 4,
                    "★ 它该是【很快】被拒的：queue_ms 明显小于排队预算。实际 "
                            + thrown.queueMs() + "ms（预算 "
                            + props.getNonStreamQueueTimeout().toMillis() + "ms）");
        }
    }

    // ============================================================
    // 三、★★★ 「每条出口都要通知」这条不变量
    // ============================================================

    @Nested
    @DisplayName("三、★★ 出口一条都不能是静默的")
    class EveryExitNotifies {

        @Test
        @DisplayName("★★★ 客户端已断开 → onAbandoned 必须被调用（否则阻塞的那个线程永远挂住）")
        void cancelledWaiterStillNotifies() throws Exception {
            // ★★★ 这条守的是一个【真的会让 Tomcat 线程永远挂住】的洞：
            //
            //   waitThenRun 的 isCancelled() 那一支原来是 release 完就 return，
            //   【不通知任何人】—— 对流式路径完全正确（客户端都走了，推给谁看？）。
            //   但非流式路径有一个阻塞在 future 上的线程，它【只能靠回调被唤醒】。
            //
            //   少了这条通知 → future 永不完成 → 线程永远阻塞 →
            //   请求永远不返回，而客户端看到的是「连接挂死」：
            //   没有异常、没有日志、没有状态码。
            AtomicReference<String> abandoned = new AtomicReference<>();
            AtomicBoolean workRan = new AtomicBoolean();

            admission.submit(admission("client-gone"),
                    new RecordingQueueListener() {
                        @Override
                        public void onAbandoned(String reason) {
                            abandoned.set(reason);
                        }

                        @Override
                        public boolean isCancelled() {
                            return true;      // ★ 模拟「客户端已经断开」
                        }
                    },
                    ctx -> workRan.set(true));

            // ★ 排队是在 queue- 线程上跑的，不是同步的 —— 轮询等它。
            long deadline = System.currentTimeMillis() + 5_000;
            while (abandoned.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            assertNotNull(abandoned.get(),
                    "★★★ 客户端已断开时【必须】调用 onAbandoned。\n"
                            + "   少了它，非流式那条路上阻塞在 future 上的线程会永远挂住 ——\n"
                            + "   而那【不会报错】：只是那个请求永远不返回。");
            assertFalse(workRan.get(),
                    "客户端都走了，work 当然不该跑 —— 那会白占一个名额跑一整轮问答");
            assertEquals("客户端已断开", abandoned.get(), "原因要能说清是哪一种放弃");
        }

        @Test
        @DisplayName("★ 反对照：正常拿到名额的请求不该被叫「已放弃」")
        void healthyWaiterIsNotAbandoned() throws Exception {
            // ★★ 这条是让上面那条有意义的那个：
            //   如果实现里把 onAbandoned 放在一个「无论如何都会走到」的位置，
            //   上面那条照样通过 —— 但它就变成了「每次都会调」，
            //   而非流式那边的 future 会在还没拿到名额时就被异常完成。
            AtomicReference<String> abandoned = new AtomicReference<>();
            AtomicBoolean workRan = new AtomicBoolean();

            RecordingQueueListener listener = new RecordingQueueListener() {
                @Override
                public void onAbandoned(String reason) {
                    abandoned.set(reason);
                }
            };

            admission.submit(admission("client-alive"), listener, ctx -> workRan.set(true));
            assertTrue(listener.awaitAdmitted(), "名额空着，它应该直接拿到");

            Thread.sleep(300);   // 给「万一有人调用它」留出时间

            assertNull(abandoned.get(),
                    "★★ 正常拿到名额的请求不该被叫「已放弃」—— 那会让阻塞方提前放开，"
                            + "而 work 还在跑，于是调用方拿到一个异常、结果却被丢弃");
            assertTrue(workRan.get(), "它该正常跑完");
        }
    }
}
