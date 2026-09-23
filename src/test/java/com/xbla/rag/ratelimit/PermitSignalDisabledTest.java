package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;
import com.xbla.rag.service.CallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>把 Pub/Sub 整个关掉，排队限流必须照样跑通</b> —— 阶段 6 的验收标准之一。
 *
 * <h2>★★★ 为什么这条验收标准要用【一个开关】来表达</h2>
 *
 * <p>「Pub/Sub 是优化，轮询是正确性来源」这句话如果只是一个声明，
 * 它和「我们觉得应该是这样」没有区别。<b>而它其实是可以被证伪的</b>：
 *
 * <pre>
 *   如果轮询不是真正的兜底（比如有人「优化」时把它去掉了），
 *   那么关掉 Pub/Sub 之后，等待者会【永远等下去】——
 *   而这正是下面 secondTest 会失败的情况。
 * </pre>
 *
 * <p>★ 这个类就是那句话的执行版本。没有它，「关掉也能跑」只能靠改代码重启来验证，
 * 于是它必然会在某次重构之后悄悄失效，而<b>没有人会知道</b> ——
 * 因为默认是开着的，一切看起来都正常。
 *
 * <h2>★ 这里最容易搞混的一件事</h2>
 *
 * <p>{@code wakeup.enabled=false} <b>不等于</b> {@code xbla.ratelimit.enabled=false}：
 *
 * <pre>
 *   enabled=false        →  整个排队限流不工作（不抢名额、不排队、qa_log.queue_ms 记 NULL）
 *   wakeup.enabled=false →  排队限流【照常工作】，只是唤醒靠轮询（≤ poll-interval）
 * </pre>
 *
 * <p>所以这个类里的断言全都是「排队在正常工作」，而不是「什么都没发生」。
 *
 * <h2>★ 前置条件</h2>
 * <p>需要 {@code docker compose up -d} 起的 Redis —— <b>关掉 Pub/Sub 不等于关掉 Redis</b>，
 * 名额表、队列、Lua 脚本全都还在用它。
 */
@SpringBootTest(properties = {
        "xbla.ratelimit.wakeup.enabled=false",
        // ★ permits=1 + 默认的 200ms 轮询：让第二个请求一定排上队，
        //   而且它靠轮询就能在几百毫秒内被放行
        "xbla.ratelimit.permits=1",
        "xbla.ratelimit.queue-timeout=30s",
        "xbla.ratelimit.key-prefix=xbla:rl:{nowake}",
        // ★ 给它一个专门的频道名。虽然订阅根本没建，写出来是为了说明
        //   「这个上下文和别的上下文不共享任何 Redis 状态」——
        //   包括那个即使关掉也还存在的频道名
        "xbla.ratelimit.channel=xbla:rl:nowake-events"
})
@DisplayName("Pub/Sub 关掉时 · 排队限流照常工作（真 Redis）")
class PermitSignalDisabledTest {

    private static final String HOLDER = "holder-of-the-only-permit";

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

    /**
     * ★ 用 {@code ObjectProvider} 而不是直接 {@code @Autowired}：
     * 关掉时那个 bean <b>不存在</b>，直接注入会让整个上下文起不来，
     * 而这个类要测的恰恰是「它不存在的时候会怎么样」。
     */
    @Autowired
    private ObjectProvider<PermitSignalSubscriber> subscriberProvider;

    @Autowired
    private ObjectProvider<PermitSignalBus> busProvider;

    @AfterEach
    void cleanUp() {
        redis.delete(RedisQueueKeys.from(props).allKeys());
        registry.clearAll();
        bus.resetCounters();
    }

    private static final Consumer<CallContext> NO_WORK = ctx -> {
    };

    // ============================================================
    // 一、开关真的把它关掉了 —— 而且关得刚好只关掉它
    // ============================================================

    @Test
    @DisplayName("★★ 订阅者 bean 不存在；同样的查法能查到总线 —— 反对照在同一处完成")
    void subscriberIsAbsentButTheBusIsNot() {
        assertNull(subscriberProvider.getIfAvailable(),
                "★★ wakeup.enabled=false 时不该创建订阅者 —— "
                        + "它建起来的话，关掉开关就只是「不订阅」还是「照样订阅」就说不清了，"
                        + "而这个开关的全部意义就是让两者可区分");

        // ★★ 反对照，而且它在【同一个测试方法里】：
        //   证明 getIfAvailable() 这个查法在当前上下文里是【能返回非 null 的】。
        //   少了这一句，上面那条断言可能是恒真的 ——
        //   比如 ObjectProvider 用错了，它永远返回 null。
        assertNotNull(busProvider.getIfAvailable(),
                "★ 总线本身是无条件的（它不认识 Redis），必须还在。"
                        + "★ 这一条同时是上面那条的反对照：证明这个查法真的能查到东西");
    }

    @Test
    @DisplayName("★ 配置里那个开关，和 bean 在不在，是指向同一个事实的")
    void propertyAgreesWithTheBeanCondition() {
        // ★★ 这条守的是一个「两处读同一个属性」的隐患：
        //   @ConditionalOnProperty 直接从环境读 xbla.ratelimit.wakeup.enabled，
        //   而探针快照读的是 RateLimitProperties.Wakeup.enabled（绑定之后的）。
        //   两者属性名相同、都由 Spring 绑定，所以【不可能】不一致 ——
        //   但这个「不可能」以前只是一句推理，现在是断言。
        assertFalse(props.getWakeup().isEnabled(),
                "★ 绑定出来的值必须也是 false。它若是 true，说明两个读取点"
                        + "指向了不同的来源 —— 而现象会是「探针说开着，实际上没订阅」，"
                        + "这种「看的和发生的不是一回事」是最难查的一类问题");
    }

    // ============================================================
    // 二、★★★ 核心：关掉之后，排队照样跑完
    // ============================================================

    @Test
    @DisplayName("★★★ 关掉 Pub/Sub，一个真实的排队请求照样拿到名额 —— 轮询兜住了")
    void queueStillWorksWithoutPubSub() throws Exception {
        assertTrue(permits.tryAcquire(HOLDER).isGranted(), "前置：占住唯一的名额");

        RecordingQueueListener recording = new RecordingQueueListener();
        admission.submit(new ChatAdmissionService.Admission(
                "waiter-with-no-pubsub", "退货要几天", null, null), recording, NO_WORK);

        assertTrue(recording.awaitQueued(),
                "★★ 连排队都没进去 —— 这和 Pub/Sub 无关，说明排队层本身就坏了");

        long t0 = System.currentTimeMillis();
        permits.release(HOLDER);          // ★ 它【照样会 PUBLISH】，只是没人在听
        assertTrue(recording.awaitAdmitted(),
                "★★★ 关掉 Pub/Sub 之后等待者【永远】没拿到名额 —— "
                        + "这说明轮询不是真正的兜底，而这是【正确性】问题，不是性能问题。\n"
                        + "   检查 ChatAdmissionService.waitThenRun 的第 ⑤ 步："
                        + "它必须【无条件】地等一个有上限的时间（signals.await(pollInterval)），"
                        + "而不是「等一个信号」。");
        long elapsed = System.currentTimeMillis() - t0;

        // ★ 它靠轮询，所以耗时落在 [0, pollInterval] 区间里；给足余量
        assertTrue(elapsed < 3_000,
                "靠轮询放行用了 " + elapsed + "ms —— 而 poll-interval 是 "
                        + props.getPollInterval() + "，加一点调度余量也不该超过 3 秒。"
                        + "★ 这个数大得离谱的话，检查一下是不是【每个】等待者都在跑一个很长的循环");

        assertTrue(recording.queueMs >= 0, "queue_ms 是真实等待时长，不该是负数");
        assertTrue(recording.queuedAhead != null,
                "它排过队，所以入队位置不该是 null —— null 的语义是「从没进过队列」");
        assertFalse(recording.isAdmitted() && recording.queuedAhead == null,
                "★ 排过队 + 位置为 null 这个组合不该出现（见 V9 迁移的注释）");
    }

    @Test
    @DisplayName("★ 关掉之后 await 仍然是【有上限】的等待 —— 它退化成纯 sleep，不是「等着被叫醒」")
    void busStillHasABoundedWaitWhenDisabled() throws Exception {
        // ★ 为什么这条重要：如果关掉订阅时 await 的实现变成「无限等一个永远不来的信号」，
        //   排队层就会永久挂死。而这个错误【不会让上面的排队测试失败】——
        //   因为上面那个测试在 200ms 内就拿到名额了，根本走不到「等很久」那一步。
        long t0 = System.currentTimeMillis();
        boolean woken = bus.await(300);
        long elapsed = System.currentTimeMillis() - t0;

        assertFalse(woken, "没有订阅者，就不会有信号 —— 必须返回 false");
        assertTrue(elapsed >= 250 && elapsed < 1_000,
                "等了 " + elapsed + "ms。它必须睡满 300ms（没人叫醒它），"
                        + "但也不能超过太多 —— 后者意味着等待的上限不受控");

        // ★ 反对照：本上下文收到的广播数必须是 0。
        //   ★ 非 0 的话，说明某个上下文（比如别的测试类）订到了同一个频道上，
        //     那样「关掉之后靠轮询」这个结论就不成立了 —— 我们其实还是被叫醒的。
        assertEqualsZero(bus.signalsReceived());
    }

    private static void assertEqualsZero(long actual) {
        org.junit.jupiter.api.Assertions.assertEquals(0L, actual,
                "★ 本上下文不该收到任何广播：订阅根本没建。"
                        + "非 0 说明别的上下文订到了同一个频道 —— 那会让我们误以为"
                        + "「轮询能兜底」，其实是信号在起作用");
    }
}
