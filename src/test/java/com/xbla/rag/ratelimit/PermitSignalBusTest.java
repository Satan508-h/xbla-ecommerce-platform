package com.xbla.rag.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link PermitSignalBus} 的纯单测 —— <b>不起 Spring，不起 Redis</b>。
 *
 * <h2>★ 为什么它必须是纯的</h2>
 *
 * <p>要被验证的性质和 Redis<b>一点关系都没有</b>：
 * 「信号能不能叫醒等待者」「超时有没有上限」「一次广播醒几个」——
 * 这些都是一个 {@code Object} 和几个 {@code AtomicLong} 的事。
 *
 * <p>而「订阅到底接上了没有」是<b>另一件事</b>，由
 * {@code PermitSignalWiringTest} 单独验证。
 * ★ 把两者混在一个测试里是有害的：那样「总线逻辑错了」和「订阅没接上」
 * 会给出<b>一模一样的失败信息</b>，而它们的修法完全不同。
 *
 * <h2>★★ 这里的每一个「正」都配了一个「反」</h2>
 *
 * <p>本项目的硬性约定：<b>纯单测必须写正-反对照</b>。
 * 下面「提前返回」那条尤其需要 —— 如果 {@code await} 的实现里
 * 根本没睡（比如误写成直接 return），断言「信号来了它返回 true」
 * <b>照样通过</b>。所以必须同时断言「没人发信号时它确实睡满了」。
 */
@DisplayName("PermitSignalBus · 唤醒总线（纯单测）")
class PermitSignalBusTest {

    /**
     * ★ 每个测试用【自己的】实例，而不是共享一个 ——
     * 这个类没有依赖，所以 {@code new} 是最干净的隔离。
     * （{@code @SpringBootTest} 里它才是单例，那时要靠 {@code resetCounters()}。）
     */
    private final PermitSignalBus bus = new PermitSignalBus();

    /** 超时给得很大，好让「提前返回」和「睡满」的区别不可能是噪声 */
    private static final long LONG_ENOUGH_MS = 30_000L;

    /**
     * 等一个线程真的进入 {@code Object.wait()}。
     *
     * <p>★★ 必须用 {@code TIMED_WAITING} 而不是 {@code WAITING}：
     * {@code Object.wait()} <b>不带超时</b>是 WAITING，
     * <b>带超时</b>是 TIMED_WAITING —— 而 {@link PermitSignalBus#await} 一定是带超时的
     * （那正是「有上限的等待」的实现）。
     *
     * <p>★ 为什么这个判断是<b>确定</b>的，不是「睡一会儿碰运气」：
     * 线程进入 wait 之前<b>先读了 {@code generation}</b>（在同一个 synchronized 块里），
     * 而状态变成 TIMED_WAITING 发生在那之后。
     * 所以「我们看到 TIMED_WAITING」⟹「它已经读过了 generation」——
     * 此时再 {@code signal()} 一定会被它看见。
     *
     * <p>⚠️ 前提是被等的线程<b>只做这一件事</b>（直接调 await），
     * 否则一次 {@code Thread.sleep} 也是 TIMED_WAITING，会让我们提前发信号。
     */
    private static void awaitUntilWaiting(List<Thread> threads) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (threads.stream().allMatch(t -> t.getState() == Thread.State.TIMED_WAITING)) {
                return;
            }
            Thread.sleep(2);
        }
        fail("线程没有进入 TIMED_WAITING —— 它们可能根本没走到 await()");
    }

    /** 起一个「只调 await」的线程。★ 只做一件事，见 {@link #awaitUntilWaiting} */
    private static Thread awaitThread(PermitSignalBus bus, long maxMs, AtomicBoolean result) {
        Thread thread = new Thread(() -> {
            try {
                result.set(bus.await(maxMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    // ============================================================
    // 一、能不能醒，以及不醒的时候是不是真的在睡
    // ============================================================

    @Nested
    @DisplayName("一、信号 → 提前返回")
    class Wakeup {

        @Test
        @DisplayName("★★ 正-反对照：有信号就提前醒；没信号就睡满，且返回 false")
        void signalCutsTheWaitShort() throws Exception {
            // ── 正：有人在 200ms 后广播 ──
            AtomicBoolean woken = new AtomicBoolean();
            Thread waiter = awaitThread(bus, LONG_ENOUGH_MS, woken);

            Thread.sleep(200);
            long t0 = System.currentTimeMillis();
            bus.signal();
            waiter.join(5_000);
            long wokenAfter = System.currentTimeMillis() - t0;

            assertTrue(woken.get(), "收到信号后 await 必须返回 true");
            assertTrue(wokenAfter < 1_000,
                    "信号发出后应该【立刻】返回，实际等了 " + wokenAfter + "ms。"
                            + "★ 这一条如果失败，说明 await 根本没在监听信号 —— "
                            + "那 Pub/Sub 就只是个摆设，而等待者会在毫不知情的情况下"
                            + "退化回纯轮询（功能仍然对，所以【不会有别的测试失败】）");

            // ── 反：同样的调用，但【没有人广播】──
            //   ★★ 这一半才是让上面那半有意义的那个。少了它，
            //      「await 直接 return true」也能让上面的断言通过。
            AtomicBoolean timedOutResult = new AtomicBoolean(true);
            Thread lonely = awaitThread(bus, 300, timedOutResult);

            long t1 = System.currentTimeMillis();
            lonely.join(5_000);
            long waitedFull = System.currentTimeMillis() - t1;

            assertFalse(timedOutResult.get(),
                    "★★ 没有信号时必须返回 false。★ 它返回 true 的话，"
                            + "调用方会以为「有人释放了名额」而多跑一次抢名额 —— "
                            + "那不会出错，但「被唤醒」这个观测值就永远失真了");
            assertTrue(waitedFull >= 250,
                    "★ 没有信号时必须真的睡满 300ms，实际只等了 " + waitedFull + "ms。"
                            + "这一条失败说明 await 没有真的在等 —— 于是上面那条「提前返回」"
                            + "断言就是恒真的（它测的是一个永远立刻返回的方法）");
        }

        @Test
        @DisplayName("★ 一次广播叫醒【全部】等待者 —— 本机惊群的形态")
        void signalWakesEveryLocalWaiter() throws Exception {
            List<AtomicBoolean> results = new ArrayList<>();
            List<Thread> waiters = new ArrayList<>();

            for (int i = 0; i < 3; i++) {
                AtomicBoolean result = new AtomicBoolean();
                results.add(result);
                waiters.add(awaitThread(bus, LONG_ENOUGH_MS, result));
            }
            awaitUntilWaiting(waiters);

            // ★ 只广播一次
            bus.signal();
            for (Thread waiter : waiters) {
                waiter.join(5_000);
            }

            for (int i = 0; i < 3; i++) {
                assertTrue(results.get(i).get(),
                        "第 " + i + " 个等待者没被叫醒。★ 一次广播必须叫醒【全部】—— "
                                + "这是「一个公共频道 + 本机惊群」的代价，也是它的定义。"
                                + "只醒一个的话，剩下的要靠 200ms 轮询兜底，"
                                + "而我们就没有兑现「Pub/Sub 把延迟降到 ~1ms」这句话");
            }
            assertEquals(3, bus.waitsWokenBySignal(),
                    "三个等待者都该被记为「信号唤醒」");
        }

        @Test
        @DisplayName("★ 信号比等待【先到】时它就丢了 —— 这是刻意的，不是 bug")
        void signalArrivingBeforeTheWaitIsLost() throws Exception {
            // ★★ 为什么这一条值得钉住：它是「轮询是正确性来源」的形态。
            //
            //   等待者的循环是【先抢一次、再去 await】。所以信号在
            //   「抢」和「睡」之间到达时确实会丢 —— 而后果只是睡满 pollInterval，
            //   【和没有 Pub/Sub 时一模一样】。
            //
            //   有人可能会想「加一个待处理标记就不丢了」。那会把
            //   「await 的上限永远不超过 pollInterval」这条简单的不变量，
            //   换成一个需要推理「这个标记被谁消费了」的共享状态 ——
            //   收益是几毫秒，代价是一个新的失效模式。所以不做。
            bus.signal();

            long t0 = System.currentTimeMillis();
            boolean woken = bus.await(300);
            long elapsed = System.currentTimeMillis() - t0;

            assertFalse(woken, "先到的信号不该被「存起来」等到下次 await 才兑现");
            assertTrue(elapsed >= 250,
                    "★ 它必须睡满 300ms（实际 " + elapsed + "ms）—— "
                            + "这个「丢了就是丢了」的行为，正是「Pub/Sub 只能让等待变短、"
                            + "不可能让它变长」这句话的来源");
        }
    }

    // ============================================================
    // 二、超时必须有上限 —— Object.wait(0) 是【无限等待】
    // ============================================================

    @Nested
    @DisplayName("二、上限")
    class Bounds {

        @Test
        @DisplayName("★★ await(0) 不能变成无限等待 —— Object.wait(0) 的语义是【永远】")
        void zeroTimeoutDoesNotWaitForever() throws Exception {
            // ★★ 这是一个真实存在的陷阱，不是假想的：
            //   Thread.sleep(0) = 「让一下 CPU」，
            //   Object.wait(0)  = 「等到天荒地老」。
            //   两个都在同一个语言里，而且参数长得一模一样。
            //
            //   而 maxMs 来自配置项 xbla.ratelimit.poll-interval ——
            //   谁把它写成 0s（一个看起来很像「不等待、立刻重试」的值），
            //   排队线程就会永久挂死在这里，现象是「请求全部卡在排队中直到超时」。
            long t0 = System.currentTimeMillis();
            boolean woken = bus.await(0);
            long elapsed = System.currentTimeMillis() - t0;

            assertFalse(woken);
            assertTrue(elapsed < 1_000,
                    "await(0) 必须在毫秒级返回，实际花了 " + elapsed + "ms。"
                            + "★ 花了很久说明它退化成了 Object.wait(0)（无限等待）—— "
                            + "那会让 poll-interval 配成 0 时整个排队层卡死");
        }

        @Test
        @DisplayName("★ 负数同理 —— 配置里写 -1 不该比写 0 更糟")
        void negativeTimeoutIsAlsoBounded() throws Exception {
            long t0 = System.currentTimeMillis();
            assertFalse(bus.await(-5), "负数一样得当成一个很小的正数");
            assertTrue(System.currentTimeMillis() - t0 < 1_000);
        }
    }

    // ============================================================
    // 三、观测量
    // ============================================================

    @Nested
    @DisplayName("三、观测量")
    class Observability {

        @Test
        @DisplayName("★ 广播次数 / 等待次数 / 被唤醒次数 —— 三个数各自记对了")
        void countersAreRecordedSeparately() throws Exception {
            // 两次广播，本机没人在等 —— ★ 这不是异常情况，是常态
            bus.signal();
            bus.signal();
            assertEquals(2, bus.signalsReceived());
            assertEquals(0.0, bus.amplification(), 1e-9,
                    "★ 有广播但没人被叫醒时，放大系数是 0 —— 不是 NaN、不是异常。" +
                            "「本机此刻没人在等」是完全正常的状态");

            // 两次等待，都没人广播
            assertFalse(bus.await(5));
            assertFalse(bus.await(5));
            assertEquals(2, bus.waitsTotal());
            assertEquals(0, bus.waitsWokenBySignal());

            // 第三次：这次有人广播
            AtomicBoolean woken = new AtomicBoolean();
            Thread waiter = awaitThread(bus, LONG_ENOUGH_MS, woken);
            Thread.sleep(100);
            bus.signal();
            waiter.join(5_000);

            assertTrue(woken.get());
            assertEquals(3, bus.waitsTotal(), "被唤醒的那次也要计入总等待次数");
            assertEquals(1, bus.waitsWokenBySignal(),
                    "★★ 三个数里只有这个是「真的被信号叫醒」的次数 —— " +
                            "和 waitsTotal 的差就是「睡满超时」的次数。" +
                            "★ 这个差值是判断『Pub/Sub 到底有没有在起作用』的唯一直接证据");
            assertEquals(1.0 / 3.0, bus.amplification(), 1e-9,
                    "3 次广播里只有 1 次叫醒了人（另外两次本机没人等）—— " +
                            "所以放大系数是 1/3，而不是「一次广播唤醒一个人」");
        }

        @Test
        @DisplayName("★ resetCounters 清计数，但【不唤醒任何人】")
        void resetDoesNotWakeAnyone() throws Exception {
            // ★★ 这条守的是一个很容易写出来的错误实现：
            //   把 reset 实现成「signal 一下再清」或者顺手推进 generation。
            //   那会让重置计数器 = 唤醒全部等待者，而现象是
            //   「测试里一清计数，排队就突然通了」—— 极难归因。
            AtomicBoolean woken = new AtomicBoolean();
            Thread waiter = awaitThread(bus, LONG_ENOUGH_MS, woken);
            awaitUntilWaiting(List.of(waiter));

            bus.resetCounters();
            Thread.sleep(100);

            assertEquals(Thread.State.TIMED_WAITING, waiter.getState(),
                    "★★ resetCounters 之后等待者必须【还在等】。"
                            + "它醒了的话，说明 reset 意外推进了 generation —— "
                            + "而「清计数器」这个动作不该有任何副作用");
            assertFalse(woken.get());

            // 收尾：放它走
            bus.signal();
            waiter.join(5_000);
        }
    }

    // ============================================================
    // 四、★ 这一条是纯单测，所以能验证「无限等待」这种 mock 测不到的东西
    // ============================================================

    @Test
    @DisplayName("★★ 正-反对照的另一半：await 的上限是【硬】的，和有没有信号无关")
    void waitIsAlwaysBoundedByTheGivenTimeout() throws Exception {
        // ★ 这条断言的是本节最核心的那句话：不管信号来不来，等待都不会超过上限。
        //   它是「Pub/Sub 只能让等待变短、不可能变长」的【可执行版本】。
        for (int i = 0; i < 3; i++) {
            long t0 = System.currentTimeMillis();
            bus.await(120);
            long elapsed = System.currentTimeMillis() - t0;

            assertTrue(elapsed < 1_000,
                    "第 " + i + " 次：等 120ms 却花了 " + elapsed + "ms");
            assertTrue(elapsed >= 100,
                    "第 " + i + " 次：等 120ms 却只花了 " + elapsed + "ms —— "
                            + "「提前返回」只允许由信号引起，不允许无条件成立");
        }
    }
}
