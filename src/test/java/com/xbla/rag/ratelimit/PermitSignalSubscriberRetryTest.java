package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>连不上 Redis 时，它会不会一直重试</b> —— 以及停下来之后还能不能停下。
 *
 * <h2>★★★ 这个测试是【一个真实 bug】逼出来的</h2>
 *
 * <p>第一版实现里有这么一行日志：
 *
 * <pre>
 *   「容器会每 5000ms 自动重试，Redis 恢复后自动接上」
 * </pre>
 *
 * <p>它是从 {@code RedisMessageListenerContainer} 的默认
 * {@code FixedBackOff(5000, Long.MAX_VALUE)} 推出来的 —— 那个配置读起来
 * 就是「无限重试」。<b>而实测结果是：</b>
 *
 * <pre>
 *   Redis 关着 → 应用起来 → 订阅抛异常
 *   Redis 启回来 → 等 20 秒 → PUBSUB NUMSUB 仍然是 0，日志里没有第二次尝试
 * </pre>
 *
 * <p>★ 原因：{@code start()} 先把内部 {@code started} 置成 true、<b>然后才</b>订阅。
 * 订阅抛异常时那个标志没被退回去，于是之后每一次 {@code start()} 都在
 * {@code compareAndSet(false, true)} 那里短路 ——
 * <b>订阅永久死亡，而 {@code isRunning()} 一直回答 true。</b>
 *
 * <h2>★★ 所以这个测试守的是「重试这件事真的在发生」</h2>
 *
 * <p>而它最容易被写坏的方式，恰恰是「日志里说会重试，代码里没有」——
 * 那种错误<b>没有任何功能测试抓得到</b>：应用照常跑，排队照常工作，
 * 只是唤醒慢了一点（退化成纯轮询）。而纯轮询本来就是正确性来源，
 * 所以一切看起来都对。
 *
 * <h2>★ 它为什么不需要 Spring、也不需要真 Redis</h2>
 *
 * <p>「重试」这个行为的本体是「隔一段时间再试一次」，而
 * <b>「试一次」失败得够快</b>就够了 —— 指向一个<b>必然连不上</b>的端口
 * （{@code localhost:1}，特权端口，本机一定没有服务在听）就能构造出
 * 「每次都失败」的稳定环境。
 *
 * <p>★ 这比「把 docker redis 停掉」好得多：后者会让<b>同一台机器上
 * 别的测试</b>一起失败，而且没法在测试里安全地恢复。
 * 用死端口，这个测试和整个世界隔离。
 */
@DisplayName("PermitSignalSubscriber · 连不上时一直在重试（真连接失败，无 Redis）")
class PermitSignalSubscriberRetryTest {

    /** 重试间隔。★ 调到 100ms 就是为了让这个测试又快又确定 */
    private static final Duration RETRY_INTERVAL = Duration.ofMillis(100);

    /**
     * 一个<b>必然连不上</b>的连接工厂。
     *
     * <p>端口 1 是特权端口，本机一定没有服务在听 —— 连接会被<b>立刻拒绝</b>
     * （不是超时），所以每次尝试都很快，整个测试不用等网络超时。
     */
    private LettuceConnectionFactory deadFactory;

    private PermitSignalSubscriber subscriber;

    @BeforeEach
    void setUp() {
        deadFactory = new LettuceConnectionFactory("127.0.0.1", 1);
        deadFactory.afterPropertiesSet();

        RateLimitProperties props = new RateLimitProperties();
        props.getWakeup().setRetryInterval(RETRY_INTERVAL);
        props.setChannel("xbla:rl:retry-test-events");

        subscriber = new PermitSignalSubscriber(deadFactory, new PermitSignalBus(), props);
    }

    @AfterEach
    void tearDown() {
        subscriber.stop();
        deadFactory.destroy();
    }

    // ============================================================

    @Test
    @DisplayName("★★★ 正-反对照：尝试次数随时【增长】，而不是试一次就放弃")
    void keepsRetryingInsteadOfGivingUp() throws Exception {
        subscriber.start();

        // ── 反：先记下短时间内的次数 ──
        Thread.sleep(250);
        long early = subscriber.subscriptionAttempts();

        // ── 正：再等久一点，次数必须【长上去】 ──
        Thread.sleep(500);
        long later = subscriber.subscriptionAttempts();

        assertTrue(early >= 1,
                "启动之后至少该尝试过一次，实际 " + early + " 次 —— " +
                        "0 说明监督线程根本没跑起来");

        // ★★ 这两条要一起看才有意义：
        //   `later > early` 证明它在【持续】重试，而不是试一次就放弃；
        //   `early >= 1` 证明 later 那个大数不是「一上来就涨上去然后停住」。
        assertTrue(later > early,
                "★★ 尝试次数从 " + early + " 涨到了 " + later + " —— 没有继续重试。\n"
                        + "   这正是那个 bug 的形态：容器自己的「无限重试」覆盖不了\n"
                        + "   「从一开始就没连上」，而它的日志里却写着会重试。\n"
                        + "   ★ 少了这个断言，这个 bug 不会有任何测试发现 ——\n"
                        + "     因为重试与否【不影响任何功能】：退化成纯轮询之后，\n"
                        + "     排队照常工作，只是唤醒慢一点，而纯轮询本来就是正确性来源。");

        assertTrue(later >= 3,
                "500ms 里（重试间隔 " + RETRY_INTERVAL.toMillis() + "ms）只试了 " + later
                        + " 次 —— 太少了。★ 检查重试间隔是不是真的从配置读到了");
    }

    @Test
    @DisplayName("★ 一直连不上时 isListening 必须答 false —— 不然日志会说谎")
    void reportsNotListeningWhenItNeverConnected() throws Exception {
        subscriber.start();
        Thread.sleep(250);

        // ★ 这条守的是「拿『没抛异常』当成功证据」这个错误。
        //   Redis 不通时 start() 的行为是抛异常（实测），
        //   但另一条路（内部等注册超时）是【正常返回】——
        //   那时如果实现里记的是「没抛异常 = 成功」，日志里会写着
        //   「订阅已建立」而实际上一直在纯轮询。
        assertFalse(subscriber.isListening(),
                "★★ 一次都没连上过，isListening() 必须答 false。"
                        + "答 true 的话，探针和日志都会说「Pub/Sub 在工作」，"
                        + "而那是一句没人能反驳的假话");
    }

    @Test
    @DisplayName("★ 还没订阅成功就关闭，不能抛异常 —— container 为 null 的那条路")
    void stopBeforeAnySubscriptionIsSafe() {
        // ★ 这条看着平平无奇，但它覆盖的是一个真实存在的空指针来源：
        //   container 字段在「从没订阅成功过」时是 null，
        //   而 stop() 会去动它。少了那个 null 判断，
        //   现象是「应用在 Redis 不通时关不掉」，而那是比启动慢难查得多的问题。
        //
        //   ⚠️ 它也能抓出「stop() 里没判空」这个错 —— 而那个错在
        //     【Redis 一切正常】的开发机上永远不会出现。
        subscriber.start();
        // ★ 写成块 lambda 而不是方法引用 subscriber::stop ——
        //   方法引用对 assertDoesNotThrow 的两个重载是【有二义的】，
        //   编译期就报「对 assertDoesNotThrow 的引用不明确」。
        assertDoesNotThrow(() -> {
            subscriber.stop();
        }, "从没订阅成功过时调用 stop() 不该抛 —— 那会让应用关不掉");
    }
}
