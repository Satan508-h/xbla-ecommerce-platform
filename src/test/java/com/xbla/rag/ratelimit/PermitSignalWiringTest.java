package com.xbla.rag.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>订阅到底接上了没有</b> —— 对着真 Redis 发一条消息，看等待者会不会被叫醒。
 *
 * <h2>★★★ 为什么必须单独有这么一条测试</h2>
 *
 * <p>因为「Pub/Sub 其实一直没在工作」这件事，<b>在其它所有测试里都是绿的</b>。
 *
 * <pre>
 *   订阅断了 / 频道名写错了 / 容器根本没起来
 *        → 没有信号
 *        → 等待者睡满 poll-interval 再重试
 *        → 【照样拿到名额、照样返回 correct 的结果、qa_log 照样完整】
 *        → 没有任何一条功能测试会失败
 * </pre>
 *
 * <p>这正是「Pub/Sub 是优化，轮询是正确性来源」的代价：<b>优化失效不会表现为错误，
 * 只表现为变慢</b>。而变慢是不会让测试变红的。
 *
 * <p>所以必须有一条测试<b>直接断言那条通道本身</b>：
 * 往频道里发一条消息，等待者必须在毫秒级醒来。
 *
 * <h2>★ 为什么用【独立的频道名】，而不是应用默认的 {@code xbla:rl:events}</h2>
 *
 * <p>因为 {@code @SpringBootTest} 会为不同的属性组合建<b>多个上下文</b>，
 * 每个上下文各有一个 {@link PermitSignalBus} 单例，但它们可能订到同一个频道上。
 * 那样 <b>A 上下文里的一次释放会唤醒 B 上下文里的等待者</b> ——
 * 而 B 的断言会看到一个「凭空出现的信号」，失败信息指向 B，原因在 A。
 *
 * <p>★ 这和后端测试里用独立 key 前缀（{@code xbla:rl:{test}}）是同一条理由：
 * <b>Redis 没有事务回滚，隔离只能靠自己划</b>。
 *
 * <h2>★ 前置条件</h2>
 * <p>需要 {@code docker compose up -d} 起的 Redis。
 */
@SpringBootTest(properties = {
        "xbla.ratelimit.channel=xbla:rl:wiring-test-events",
        "xbla.ratelimit.key-prefix=xbla:rl:{wiring}"
})
@DisplayName("PermitSignalSubscriber · 订阅确实接到了 Redis（真 Redis）")
class PermitSignalWiringTest {

    /** 订阅建立的上限。★ 给 5 秒：容器在【自己的线程】上启动，和主线程是并行的 */
    private static final long LISTENING_TIMEOUT_MS = 5_000;

    @Autowired
    private PermitSignalBus bus;

    @Autowired
    private PermitSignalSubscriber subscriber;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private com.xbla.rag.config.RateLimitProperties props;

    @BeforeEach
    @AfterEach
    void resetCounters() {
        bus.resetCounters();
    }

    /**
     * 等订阅真的建立起来。
     *
     * <p>★★ 不能省这一步：{@code PermitSignalSubscriber.start()} 只是
     * <b>起了一个线程就返回</b>（那正是它的设计目的 —— 不让主线程等 Redis）。
     * 所以「上下文起来了」和「订阅接上了」之间有一个真实的窗口。
     * 直接发消息会因为撞上那个窗口而偶发失败 —— 而偶发失败的测试比没有测试更糟。
     */
    private boolean awaitListening() throws InterruptedException {
        long deadline = System.currentTimeMillis() + LISTENING_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (subscriber.isListening()) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private void requireListening() throws InterruptedException {
        assertTrue(awaitListening(),
                "★★ 订阅在 " + LISTENING_TIMEOUT_MS + "ms 内没有建立起来。请检查：\n"
                        + "  ① docker redis 在跑吗？（docker compose ps）\n"
                        + "  ② xbla.ratelimit.wakeup.enabled 是 true 吗？\n"
                        + "  ③ xbla.ratelimit.channel 是什么？本测试用的是 "
                        + props.getChannel() + "\n"
                        + "⚠️ 注意：即使它一直建立不起来，【应用的其它功能仍然完全正常】——\n"
                        + "   会退化成纯轮询，只是唤醒延迟从 ~1ms 回到 poll-interval。\n"
                        + "   所以别的测试不会因此失败，这一条是唯一会告诉你『Pub/Sub 没在工作』的地方。");
    }

    // ============================================================
    // 正-反对照
    // ============================================================

    @Test
    @DisplayName("★★ 正：往频道发一条消息，等待者必须在毫秒级被叫醒")
    void publishedMessageWakesTheWaiter() throws Exception {
        requireListening();

        redis.convertAndSend(props.getChannel(), "someone-released-a-permit");

        long t0 = System.currentTimeMillis();
        boolean woken = bus.await(3_000);
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(woken,
                "★★ 消息发出去了，等待者没醒 —— 说明订阅【没有真的接上】。\n"
                        + "   这一条失败不会影响任何功能测试（轮询兜住了），\n"
                        + "   但它意味着 Pub/Sub 这一整块是死的，而没人知道。");
        assertTrue(elapsed < 1_000,
                "被叫醒用了 " + elapsed + "ms —— 本地 Redis 的 Pub/Sub 应该是几毫秒。"
                        + "★ 这个数如果接近 poll-interval，说明信号没起作用，"
                        + "是轮询把等待者叫醒的（结果一样，但机制不同）");
    }

    /**
     * ★★ 反对照 —— <b>这条才是让上面那条有意义的那个</b>。
     *
     * <p>上面那条断言「有消息 → 醒来」。如果 {@code await} 因为任何<b>别的原因</b>
     * 提前返回（比如实现里忘了真的 wait），它照样通过。
     * 所以这里发到<b>一个别的频道</b>：消息确实被 Redis 投递了、
     * 我们的订阅确实「该收到别的消息时能收到」，但<b>这一条不是给我们的</b> ——
     * 于是等待者必须睡满。
     *
     * <p>而且这里额外断言 {@code signalsReceived() == 0}，
     * 把「没醒来」的原因钉死成「信号根本没到达」，
     * 而不是「信号到了但是弄丢了」——<b>两种情况的修法完全不同</b>。
     */
    @Test
    @DisplayName("★★ 反：发到别的频道不算数 —— 证明上面那条不是因为「等待者总会醒」")
    void messageOnAnotherChannelDoesNotWake() throws Exception {
        requireListening();

        redis.convertAndSend(props.getChannel() + "-not-ours", "someone-else");

        long t0 = System.currentTimeMillis();
        boolean woken = bus.await(300);
        long elapsed = System.currentTimeMillis() - t0;

        assertFalse(woken,
                "★★ 别的频道的消息把我们叫醒了 —— 那是订阅订错了频道，"
                        + "或者消息路由出了问题。这会让我们【在完全没有名额释放的时候】"
                        + "全员惊醒去抢一次名额，白烧 Redis 的 QPS");
        assertTrue(elapsed >= 250,
                "它必须真的等满 300ms，实际 " + elapsed + "ms");
        assertEquals(0, bus.signalsReceived(),
                "★ 这一条把上面的「没醒」定死成『消息压根没到』。"
                        + "非 0 的话，说明消息到了但被丢了 —— 那是另一个 bug，"
                        + "而且修法完全不同");
    }

    @Test
    @DisplayName("★ 健康环境下【第一次】就该连上 —— 别把「连了十次才成功」当成正常")
    void healthyEnvironmentConnectsOnTheFirstAttempt() throws Exception {
        requireListening();

        // ★ 为什么这条值得单独写：监督循环会一直重试，所以「最终连上了」
        //   这个断言【恒真】—— 它连一个彻底坏掉的订阅方式都区分不出来。
        //   真正有信息量的是「试了几次」。
        //
        // ⚠️ 允许 2 次而不是 1 次，是因为有一个合法的边界情况：
        //   start() 内部的「等订阅注册完成」超时（默认 2 秒）时它会正常返回但不监听，
        //   于是我们会在下一次循环里重建一个 —— 而那个「没等到注册」的订阅
        //   可能随后就成功了。这个场景在本地 Redis 上几乎不会出现，
        //   但把它划成失败会让测试偶发变红，而偶发红的测试比没有测试更糟。
        assertTrue(subscriber.subscriptionAttempts() <= 2,
                "★★ 订阅试了 " + subscriber.subscriptionAttempts() + " 次才连上。\n"
                        + "   Redis 就在本机、一切正常，所以第一次就该成功 ——\n"
                        + "   试很多次说明每次尝试都以失败告终，只是碰巧有一次成了。\n"
                        + "   ★ 那种状态最危险的地方在于：如果监督循环哪天被人去掉，\n"
                        + "     它就变成了「订阅根本连不上」，而【没有任何别的测试会失败】。");
    }

    @Test
    @DisplayName("★ 连续两次广播必须能【连续】叫醒两次 —— 代次不会卡住")
    void consecutiveSignalsBothWake() throws Exception {
        // ★ 这条守的是「用 generation 判断是否被唤醒」这个实现的边界：
        //   如果实现写成 `if (generation > 0)`（而不是「和进去前比，变了没有」），
        //   那么【第一次广播之后，每一次 await 都会立刻返回】——
        //   于是等待者再也不真的等待，循环退化成忙等。
        //   那不会报错，只会把 Redis 的 QPS 打上去。
        requireListening();

        redis.convertAndSend(props.getChannel(), "first");
        assertTrue(bus.await(3_000), "第一次广播应该叫醒");

        redis.convertAndSend(props.getChannel(), "second");
        assertTrue(bus.await(3_000),
                "★★ 第二次广播也必须叫醒。★ 这一条失败通常意味着代次的比较写错了 —— "
                        + "比如写成了「大于 0 就算被唤醒」，那样第一次之后每次 await 都会立刻返回，"
                        + "排队循环变成忙等，而 Redis 的 QPS 会悄悄涨上去");
    }
}
