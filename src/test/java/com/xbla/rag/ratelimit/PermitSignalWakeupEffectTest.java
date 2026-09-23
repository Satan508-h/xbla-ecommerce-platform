package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;
import com.xbla.rag.service.CallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Pub/Sub 到底值不值</b> —— 量出「有广播」和「没广播」两种情况下，排队者拿到名额的时间。
 *
 * <h2>★★★ 为什么这一条不能靠「跑一遍看看」</h2>
 *
 * <p>不量化的话，我们只知道「有了 Pub/Sub，唤醒变快了」—— 而那是一个<b>无法反驳、
 * 也无法验证</b>的说法。真正要回答的是两个具体问题：
 *
 * <pre>
 *   ① Pub/Sub 真的把那段时间降下来了吗？（还是说循环里本来就没什么等待？）
 *   ② 它坏掉的时候会怎样？（降级成什么？还正确吗？）
 * </pre>
 *
 * <h2>★★ 方法：故意把 {@code poll-interval} 拉到 2 秒</h2>
 *
 * <p>默认的 200ms 太小了 —— 小到「靠信号」和「靠轮询」的差别会被测量噪声淹没。
 * 把它拉到 <b>2 秒</b>之后，两者的差距从「不好说」变成<b>一个数量级</b>，
 * 而一个数量级的差距是不需要统计检验就能断言的。
 *
 * <h2>★★★ 对照组是怎么做的 —— 这是本测试最需要解释的一处</h2>
 *
 * <p>最直觉的做法是「再起一个 {@code wakeup.enabled=false} 的 Spring 上下文」。
 * <b>那样做有个隐蔽的毛病</b>：两个上下文是两次不同的 JVM 状态
 * （连接池、线程池、Redis 连接、类加载都可能不同），
 * 于是「差别是 Pub/Sub 造成的」这个结论里混进了一堆别的变量。
 *
 * <p>所以这里用<b>同一个上下文</b>，只改一件事：<b>有没有发生广播</b>。
 *
 * <pre>
 *   正组：permits.release("holder")      → release.lua 会 ZREM + 【PUBLISH】
 *   反组：直接把 holder 从 slots 里 ZREM  → 【没有 PUBLISH】
 * </pre>
 *
 * <p>★ 而 {@code release.lua} 里那句 {@code PUBLISH} 是<b>唯一的差异</b> ——
 * 名额同样被释放、等待者同样能抢到、唯一不同的是它<b>知不知道</b>。
 * 于是「快了多少」这个问题，答案里不会混进第二个变量。
 *
 * <p>⚠️ 反组<b>必须绕过 {@code ChatPermitService.release()}</b> ——
 * 那个方法一定会广播（那正是它的职责）。绕过它会跳过本地登记表的清理，
 * 所以这里手动补一句 {@code registry.forget}，见 {@code pollIsTheFallback}。
 *
 * <h2>★ 前置条件</h2>
 * <p>需要 {@code docker compose up -d} 起的 Redis。
 */
@SpringBootTest(properties = {
        // ★ permits=1：让第二个请求【一定】排上队，没有第二种可能
        "xbla.ratelimit.permits=1",
        // ★★ 故意拉大：默认 200ms 太小，量不出「信号 vs 轮询」的差别。见类注释第二节
        "xbla.ratelimit.poll-interval=2s",
        "xbla.ratelimit.queue-timeout=30s",
        "xbla.ratelimit.key-prefix=xbla:rl:{wakeeffect}",
        "xbla.ratelimit.channel=xbla:rl:wakeeffect-events"
})
@DisplayName("Pub/Sub 唤醒 · 对排队等待时长的影响（真 Redis）")
class PermitSignalWakeupEffectTest {

    /** 占着唯一名额的那个人 */
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

    @AfterEach
    void cleanUp() {
        redis.delete(RedisQueueKeys.from(props).allKeys());
        registry.clearAll();
        bus.resetCounters();
    }

    /** 什么都不干的活 —— 本测试只关心「什么时候拿到名额」，不关心拿到之后跑什么 */
    private static final Consumer<CallContext> NO_WORK = ctx -> {
    };

    /**
     * 让一个请求排上队，返回它的事件记录器。
     *
     * <p>★ 会等它的 {@code onQueued}，也就是<b>确认它真的在排队了</b>才返回。
     * 不等的话，「释放名额」可能发生在它还没入队之前 —— 那时它会直接拿到名额，
     * 而我们从一次「根本没排过队」的运行里得出关于排队的结论。
     */
    private RecordingQueueListener submitQueued(String traceId) throws InterruptedException {
        RecordingQueueListener recording = new RecordingQueueListener();
        admission.submit(new ChatAdmissionService.Admission(traceId, "退货要几天", null, null),
                recording, NO_WORK);

        assertTrue(recording.awaitQueued(),
                "请求在 10 秒内没有进入队列 —— 前置条件没成立，"
                        + "后面的时间测量无论得出什么结论都没有意义");
        // ★ 再给 100 微秒级都不够的余量：onQueued 是在【推完位置之后】调的，
        //   而它紧接着就会去 await。给 100ms 让那个转换一定完成 ——
        //   否则「释放」可能正好落在「抢」和「睡」之间，那个信号会丢（见 PermitSignalBus），
        //   于是正组也会测出 2 秒，而失败信息会指向「Pub/Sub 没工作」这个错误方向。
        Thread.sleep(100);
        return recording;
    }

    // ============================================================
    // 正-反对照
    // ============================================================

    @Test
    @DisplayName("★★ 正：走了广播的释放，等待者在毫秒级拿到名额")
    void broadcastCutsTheQueueLatency() throws Exception {
        assertTrue(permits.tryAcquire(HOLDER).isGranted(), "前置：占住唯一的名额");
        RecordingQueueListener recording = submitQueued("waiter-with-broadcast");

        long t0 = System.currentTimeMillis();
        permits.release(HOLDER);                    // ★ 这一步里面有 PUBLISH
        assertTrue(recording.awaitAdmitted(), "释放之后 20 秒都没拿到名额 —— 那是死锁");

        long elapsed = System.currentTimeMillis() - t0;

        // ★★ 量出来的数【每次都进断言消息】。
        //   「Pub/Sub 把唤醒延迟降到 ~1ms」这句话如果没有一个每次都算出来的数字，
        //   过几周就会退化成一句没人验证过的宣传语 —— 而它本身是对的，
        //   只是没人知道那个「1ms」现在还是不是 1ms。
        assertTrue(bus.signalsReceived() > 0,
                "★★ 本上下文一次广播都没收到 —— 那这 " + elapsed + "ms 是哪来的？\n"
                        + "   ★ 这一条是上面那个时间的前提：没有它，"
                        + "『广播让等待变快』这个结论可能就是假的（我们只是量到了别的东西）");

        assertTrue(elapsed < 800,
                "★★ 广播之后等了 " + elapsed + "ms 才拿到名额，而 poll-interval 是 2 秒。\n"
                        + "   超过 800ms 说明【是轮询把它叫醒的，不是信号】。\n"
                        + "   ⚠️ 功能上完全正确 —— 那条路本来就有人兜底 —— 但 Pub/Sub 这一块是死的。\n"
                        + "   请先跑 PermitSignalWiringTest，它专门判断订阅有没有接上。");

        assertTrue(recording.queueMs >= 0, "queue_ms 该是真实的等待时长");
        assertTrue(recording.queuedAhead != null,
                "它排过队，所以入队位置【不该】是 null —— " +
                        "null 的语义是「从没进过队列」");
    }

    @Test
    @DisplayName("★★ 反：没有广播时，它靠轮询兜底 —— 慢一个数量级，但照样拿到名额")
    void pollIsTheFallback() throws Exception {
        assertTrue(permits.tryAcquire(HOLDER).isGranted(), "前置：占住唯一的名额");
        RecordingQueueListener recording = submitQueued("waiter-without-broadcast");

        // ★★ 对照组：把名额拿掉，但【不广播】。
        //
        //   这就是「Pub/Sub 没工作」时发生的事 —— 名额确实空出来了，
        //   而等待者不知道，只能等自己下一次轮询。
        //
        //   ⚠️ 为什么不用 permits.release(HOLDER)：那个方法一定会 PUBLISH
        //      （那是它的职责），用了它两组就没有差别了。
        redis.opsForZSet().remove(RedisQueueKeys.from(props).slots(), HOLDER);
        // ★ 顺带忘掉本地登记 —— 真实场景里 release 会做这件事。
        //   不做的话心跳有可能把它 ZADD 回名额表（它每 15 秒一次，本测试跑不到，
        //   但这行代码的意义是「让对照组和真实释放的【唯一】差别就是有没有广播」）
        registry.forget(HOLDER);

        long t0 = System.currentTimeMillis();
        assertTrue(recording.awaitAdmitted(), "轮询兜底也必须最终拿到名额 —— 那是「正确性来源」的定义");
        long elapsed = System.currentTimeMillis() - t0;


        assertTrue(elapsed >= 1_000,
                "★★ 没有广播时它只等了 " + elapsed + "ms —— 太快了，快到不可能是轮询。\n"
                        + "   而 poll-interval 是 2 秒，所以它至少该等 1 秒以上。\n"
                        + "   ★ 这条失败通常意味着上一条（正组）测的不是 Pub/Sub：\n"
                        + "     如果等待者本来就秒醒，那『广播让它变快』就是个假结论。\n"
                        + "   ⚠️ 也检查一下 PermitSignalWiringTest 是不是把频道订错了 ——\n"
                        + "     订到公共频道上的话，【别的测试的释放】会跑来叫醒这里的人。");

        assertTrue(elapsed <= 5_000,
                "等了 " + elapsed + "ms 还没到 —— 轮询兜底失效了，而这是【正确性】问题："
                        + "Pub/Sub 挂了不该导致任何人拿不到名额");

        assertTrue(recording.queuedAhead != null, "它同样排过队，位置不该是 null");
    }
}
