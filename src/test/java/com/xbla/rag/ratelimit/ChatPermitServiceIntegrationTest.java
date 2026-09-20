package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatPermitService} —— 抢名额 / 释放 / 看门狗 / 清扫，对着<b>真的 Redis</b> 跑。
 *
 * <h2>★★ 为什么必须是真 Redis，不能用 mock</h2>
 *
 * <p>这个类存在的全部意义是「原子性」和「跨进程可见」——
 * 而 mock 出来的 {@code StringRedisTemplate} 两条都验证不了：
 * 它不会并发、不会过期、不会出现「读到的和写进去的不一样」。
 * <b>用 mock 测出来的绿，只是证明「我把参数传对了」。</b>
 *
 * <p>（同 {@code docs/10} 里 5.8 那条教训：桩造的对象验证不了桩自己是否照协议造的。）
 *
 * <h2>★★ Redis 没有事务回滚 —— {@code @Transactional} 在这里是【无效】的</h2>
 *
 * <p>本项目其它集成测试靠 {@code @Transactional} 自动回滚（{@code EntityMappingTest} 的做法）。
 * 但那套只对数据库有效：<b>Redis 的写入不受 Spring 事务管辖</b>，
 * 加了 {@code @Transactional} 也不会回滚，测试之间会互相污染，
 * 而且症状是「单独跑绿、一起跑红」这种最费时间的形态。
 *
 * <p>所以这里：① <b>用独立的 key 前缀</b>（{@code xbla:rl:{test}}，
 * 和应用默认的 {@code xbla:rl:{chat}} 物理隔离）；
 * ② {@link #cleanUp()} 每次跑完手动删干净。
 *
 * <h2>★ 前置条件</h2>
 * <p>需要 {@code docker compose up -d} 起的 Redis。<b>这是阶段 6 新增的依赖</b> ——
 * 在那之前 {@code ./mvnw test} 只需要 postgres。
 */
@SpringBootTest(properties = {
        "xbla.ratelimit.key-prefix=xbla:rl:{test}",
        "xbla.ratelimit.permits=2",
        "xbla.ratelimit.max-queue=3",
        "xbla.ratelimit.permit-ttl=45s"
})
@DisplayName("ChatPermitService · 名额与排队（真 Redis）")
class ChatPermitServiceIntegrationTest {

    @Autowired
    private ChatPermitService permits;

    @Autowired
    private RateLimitProperties props;

    @Autowired
    private StringRedisTemplate redis;

    /**
     * ★★ 这个类必须一起清 —— 它<b>没有</b>被 Redis 的清理顺带清掉。
     *
     * <p>{@link LocalPermitRegistry} 是 Spring 单例，生命周期是整个测试 JVM，
     * 而 {@link #cleanUp()} 只能清 Redis。少了这一行，
     * <b>上一个测试留在 {@code held} 里的 traceId 会在下一个测试调
     * {@code renewHeld()} 时被 ZADD 回名额表</b> —— 于是
     * 「释放后名额应该是 0」看到 3、「只该有 2 个名额」看到 4。
     *
     * <p>★ 这个症状的迷惑性在于：失败信息指向那个断言
     * （「名额被心跳复活了」），而真正的原因是<b>上一个测试没清干净</b>。
     * 第一次跑这个测试类时就是这么失败的。
     */
    @Autowired
    private LocalPermitRegistry registry;

    private RedisQueueKeys keys;

    private RedisQueueKeys keys() {
        if (keys == null) {
            keys = RedisQueueKeys.from(props);
        }
        return keys;
    }

    /**
     * ★ Redis 没有回滚，必须自己清。
     *
     * <p>不清理的后果不是「脏数据」那么轻 —— 它是
     * <b>下一个测试跑的时候名额表里还留着上一个测试的人</b>，
     * 于是它一上来就「名额已满」，然后排队的断言全部失败。
     * 而失败信息会指向那个断言，不会指向「上一个测试没清干净」。
     */
    @AfterEach
    void cleanUp() {
        redis.delete(keys().allKeys());
        // ★★ 两处都要清 —— 只清 Redis 的话，本地登记表会把名额「复活」回 Redis
        registry.clearAll();
    }

    // ============================================================
    // 辅助：直接观察 Redis，而不是问服务「你觉得怎么样」
    // ============================================================

    private long slotsCount() {
        Long n = redis.opsForZSet().zCard(keys().slots());
        return n == null ? 0 : n;
    }

    private long queueCount() {
        Long n = redis.opsForZSet().zCard(keys().queue());
        return n == null ? 0 : n;
    }

    /** 某个成员在 slots 里的 score（= 过期时间）。null 表示它不在名额表里 */
    private Double slotScore(String traceId) {
        return redis.opsForZSet().score(keys().slots(), traceId);
    }

    private Long rankInQueue(String traceId) {
        return redis.opsForZSet().rank(keys().queue(), traceId);
    }

    /** ★ 把 score 直接改到过去，模拟「租期已到」——不用 sleep，测试才确定性 */
    private void forceSlotExpired(String traceId) {
        redis.opsForZSet().add(keys().slots(), traceId, 1000);
    }

    // ============================================================
    // 一、抢名额的三种结果
    // ============================================================

    @Nested
    @DisplayName("一、抢名额")
    class Acquire {

        @Test
        @DisplayName("有空位 → 直接拿到，且不进队列")
        void grantsWhenCapacityAvailable() {
            PermitState state = permits.tryAcquire("req-1");

            assertEquals(PermitState.Outcome.GRANTED, state.outcome());
            assertEquals(1, slotsCount());
            assertEquals(0, queueCount());
        }

        @Test
        @DisplayName("★ 满了 → 排队，position 是【前面还有几个人】（第一个人是 0）")
        void queuesWithZeroBasedPosition() {
            permits.tryAcquire("req-1");
            permits.tryAcquire("req-2");   // 名额用完（permits=2）

            PermitState third = permits.tryAcquire("req-3");
            PermitState fourth = permits.tryAcquire("req-4");

            assertEquals(PermitState.Outcome.QUEUED, third.outcome());
            assertEquals(0, third.position(),
                    "第一个人前面【一个都没有】，position 必须是 0。"
                            + "如果这里是 1，用户会看到「你前面还有 1 位」——"
                            + "而这种 off-by-one 看起来只像「有点慢」，不像 bug");

            assertEquals(PermitState.Outcome.QUEUED, fourth.outcome());
            assertEquals(1, fourth.position());
        }

        @Test
        @DisplayName("★ 队列也满 → 如实拒绝，且【不】把它留在队列或登记表里")
        void rejectsWhenQueueIsFull() {
            permits.tryAcquire("req-1");
            permits.tryAcquire("req-2");
            permits.tryAcquire("req-3");   // 队列 1
            permits.tryAcquire("req-4");   // 队列 2
            permits.tryAcquire("req-5");   // 队列 3（满了，max-queue=3）

            PermitState rejected = permits.tryAcquire("req-6");

            assertEquals(PermitState.Outcome.QUEUE_FULL, rejected.outcome());
            assertEquals(3, rejected.queueSize(), "被拒时要告诉用户【现在有多少人在等】");
            assertEquals(3, queueCount(), "被拒的请求不能进入队列");
            assertNull(rankInQueue("req-6"), "被拒的请求不能在队列里留下痕迹");
        }

        @Test
        @DisplayName("★ 被拒绝的请求什么也占不住：不进队列、不占名额、不算进等待数")
        void rejectedRequestHoldsNothing() {
            permits.tryAcquire("req-1");
            permits.tryAcquire("req-2");
            for (int i = 3; i <= 5; i++) {
                permits.tryAcquire("req-" + i);   // 队列里 3 个
            }
            permits.tryAcquire("req-rejected");   // 队列满，被拒

            assertEquals(3, permits.localState().get("waitingHere"),
                    "本地登记表里应该正好是队列里那 3 个 —— 被拒的那个不该算进去");

            permits.renewHeld();   // 心跳跑一次

            assertNull(rankInQueue("req-rejected"));
            assertNull(slotScore("req-rejected"),
                    "被拒绝的请求不该在名额表里留下任何东西");

            // ⚠️ 诚实说明这个测试的边界（注入 bug 时实测到的）：
            //   把 ChatPermitService 里 reject 分支的 registry.forget(traceId)
            //   【注释掉】，这个测试【照样通过】。
            //   原因是那个 forget 在当前流程里无事可做 —— 被拒的请求从来没被
            //   markWaiting 过（只有 status==0 才登记），所以没有东西可忘。
            //   ★ 所以这个测试验证的是【不变量】，不是【那一行代码】。
            //     两者的区别很重要：不变量可能由别的地方保证，
            //     而「某一行代码被验证过」是更强的说法。
        }
    }

    // ============================================================
    // 二、位置稳定与幂等
    // ============================================================

    @Nested
    @DisplayName("二、★ 位置稳定与幂等")
    class Stability {

        @Test
        @DisplayName("★★ 排队者反复重试，position 必须【不变】—— 这是「保留原 score」的全部意义")
        void positionDoesNotDriftOnRetry() {
            permits.tryAcquire("req-1");
            permits.tryAcquire("req-2");
            permits.tryAcquire("req-a");
            permits.tryAcquire("req-b");

            PermitState first = permits.tryAcquire("req-a");
            assertEquals(0, first.position());

            // ★ 模拟被唤醒后的重试：连试 5 次
            for (int i = 0; i < 5; i++) {
                PermitState again = permits.tryAcquire("req-a");
                assertEquals(PermitState.Outcome.QUEUED, again.outcome());
                assertEquals(0, again.position(),
                        "重试让位置往后跳了 —— 说明每次重试都在 ZADD 一个新序号，"
                                + "用户会看到自己从「前面 0 人」变成「前面 1 人」，"
                                + "而前面其实只是它自己");
            }

            assertEquals(0, rankInQueue("req-a"));
        }

        @Test
        @DisplayName("★★ 已经持有名额的人再调一次，只续期、不能把自己排进队")
        void acquireIsIdempotentForHolder() {
            permits.tryAcquire("req-1");
            permits.tryAcquire("req-2");   // 名额满

            PermitState again = permits.tryAcquire("req-1");   // 它已经持有了

            assertEquals(PermitState.Outcome.GRANTED, again.outcome(),
                    "持有者再抢一次必须还是 GRANTED —— 否则它会一边占着名额一边排队");
            assertNull(rankInQueue("req-1"), "持有者绝不能出现在队列里");
            assertEquals(2, slotsCount(),
                    "应该是 req-1 和 req-2 各占一个。★ 幂等的意义就在这里："
                            + "第三次调用【没有】让占用数变成 3 —— 它只更新了 req-1 的过期时间");
        }

        @Test
        @DisplayName("★ 队列的 score 是【单调序号】不是时间戳 —— 同一毫秒入队也不会并列")
        void queueScoreIsSequenceNotTimestamp() {
            permits.tryAcquire("req-1");
            permits.tryAcquire("req-2");
            permits.tryAcquire("req-a");
            permits.tryAcquire("req-b");
            permits.tryAcquire("req-c");

            // 序号严格递增 —— 而如果 score 用的是毫秒时间戳，
            // 这三个值很可能完全相同，那么 ZRANK 的顺序就由成员字典序决定（≈随机）
            Double a = redis.opsForZSet().score(keys().queue(), "req-a");
            Double b = redis.opsForZSet().score(keys().queue(), "req-b");
            Double c = redis.opsForZSet().score(keys().queue(), "req-c");

            assertNotNull(a);
            assertNotNull(b);
            assertNotNull(c);
            assertTrue(a < b && b < c,
                    "队列 score 必须严格递增。相同的话 ZRANK 的并列顺序由成员字典序决定 —— "
                            + "而那等于随机（本项目在阶段 4 的 R8 踩过同一类坑）");
        }
    }

    // ============================================================
    // 三、释放
    // ============================================================

    @Nested
    @DisplayName("三、释放")
    class Release {

        @Test
        @DisplayName("释放后名额空出来，队首能抢到")
        void releaseFreesCapacityForQueueHead() {
            permits.tryAcquire("req-1");
            permits.tryAcquire("req-2");
            permits.tryAcquire("req-waiting");

            permits.release("req-1");

            assertEquals(1, slotsCount());
            PermitState head = permits.tryAcquire("req-waiting");
            assertEquals(PermitState.Outcome.GRANTED, head.outcome(),
                    "★ 「释放只广播、等待者自己抢」—— 但抢的动作必须真的能成功");
            assertEquals(2, slotsCount());
            assertNull(rankInQueue("req-waiting"), "抢到名额后必须从队列里摘掉");
        }

        @Test
        @DisplayName("重复释放是幂等的，且不会影响别人的名额")
        void releaseIsIdempotent() {
            permits.tryAcquire("req-1");
            permits.tryAcquire("req-2");

            permits.release("req-1");
            permits.release("req-1");              // 第二次
            permits.release("req-never-existed");  // 从来没抢到过的

            assertEquals(1, slotsCount(), "只有 req-2 还在");
            assertNotNull(slotScore("req-2"), "别人的名额不能被误删");
            assertNull(slotScore("req-1"));
        }
    }

    // ============================================================
    // 四、★★★ 复活陷阱
    // ============================================================

    @Nested
    @DisplayName("四、★★★ 看门狗不能复活已释放的名额")
    class Resurrection {

        @Test
        @DisplayName("★★★ 释放过的名额，心跳跑多少次都不能复活")
        void releasedPermitIsNotResurrectedByHeartbeat() {
            permits.tryAcquire("req-1");
            assertEquals(1, slotsCount());

            permits.release("req-1");
            assertEquals(0, slotsCount());

            // ★ 心跳跑三次 —— 它做的是 ZADD，而 ZADD 在成员不存在时会【创建】它
            permits.renewHeld();
            permits.renewHeld();
            permits.renewHeld();

            assertEquals(0, slotsCount(),
                    "★★★ 名额被心跳复活了。它是 ZADD 出来的，带着全新的 45 秒租期，"
                            + "而那个请求早已结束、没有任何人会来释放它 —— "
                            + "8 个名额里永久少一个，直到应用重启。"
                            + "根因是 release 时没把 traceId 从 LocalPermitRegistry 里删掉，"
                            + "或者删的顺序反了（先删 Redis 再删本地）");
        }

        /**
         * ★★ 正-反对照：证明上一条不是恒真的。
         *
         * <p>「renewHeld() 之后名额数还是 0」这个断言，如果 {@code renewHeld()} 本身
         * 什么都没干（比如提前 return 了），也一样会通过。
         * 所以必须另外证明<b>它对还没释放的名额确实做了事</b>。
         */
        @Test
        @DisplayName("★★ 反对照：没释放的名额，心跳【确实】会把它续期（证明上一条不是恒真）")
        void heldPermitIsActuallyRenewed() {
            permits.tryAcquire("req-1");

            // 把 score 压到过去，模拟「租期快到了」
            forceSlotExpired("req-1");
            assertEquals(1000.0, slotScore("req-1"));

            permits.renewHeld();

            Double renewed = slotScore("req-1");
            assertNotNull(renewed);
            assertTrue(renewed > System.currentTimeMillis(),
                    "没被释放的名额必须被续期到未来 —— "
                            + "否则说明 renewHeld() 什么都没做，"
                            + "那么「释放后不复活」那个测试就是恒真的，它测的是空气");
        }

        @Test
        @DisplayName("★★ 反对照：心跳之后名额数恰好是 2 —— 多一个都说明有东西被复活了")
        void heartbeatResurrectsNothing() {
            permits.tryAcquire("req-1");
            permits.tryAcquire("req-2");
            permits.tryAcquire("q1");
            permits.tryAcquire("q2");
            permits.tryAcquire("q3");
            permits.tryAcquire("req-rejected");   // 队列满，被拒

            permits.renewHeld();

            assertNull(slotScore("req-rejected"));
            assertEquals(2, slotsCount(),
                    "★ 这个数【多一个都错】。心跳是个 ZADD，任何在本 JVM 登记表里留下痕迹的"
                            + "traceId（释放过的、被拒的、排队的）都会被它造成一个真实名额。"
                            + "这里 2 是对的：只有 req-1 / req-2 在 held 里");
        }

        /**
         * ★★★ <b>整个复活 bug 的最小复现 —— 而且它是确定性的。</b>
         *
         * <h3>为什么上面那两条「不能复活」的测试挡不住这个 bug</h3>
         *
         * <p>它们走的是 <b>{@code release()}</b> 这条路 —— 而 {@code release()}
         * 会先 {@code forget} 再 {@code ZREM}，所以心跳根本看不到那个 traceId。
         * 它们验证的是<b>顺序</b>，不是<b>续期本身的语义</b>。
         *
         * <h3>★★★ 顺序保护不了它，因为窗口是【跨两步】的</h3>
         *
         * <pre>
         *   t0  心跳：heldSnapshot() → 快照里有 X      ← 快照是【旧的】
         *   t1  release(X)：forget(X)                  ← 本机忘了
         *   t2  release(X)：ZREM slots X               ← Redis 也删了
         *   t3  心跳：用【旧快照】去写 X               ← ★ 这一步决定了 bug 存不存在
         * </pre>
         *
         * <p>t1、t2 之后的状态正是本测试手工构造的：<b>registry 里没有 X，Redis 里也没有 X。</b>
         * 唯一还未知的就是 t3 —— 老实现（两条普通 {@code ZADD}）会把它造出来，
         * 因为 <b>ZADD 在成员不存在时会【创建】它</b>。
         *
         * <p>★ 真机上这个窗口 12 轮 × 100 并发只撞到 1 次（快照里 7 个孤儿条目的
         * score 完全相同，是一次心跳批量写入的指纹）。<b>靠撞窗口是钉不住的</b> ——
         * 所以这里直接把窗口的【结果】摆出来，让那一步无论如何都会被执行到。
         *
         * <p>⚠️ 反对照在 {@link #heldPermitIsActuallyRenewed()}：那边证明
         * 「没被释放的名额确实会被续期」。少了它，本测试对「续期什么都不做」
         * 的实现也照样通过。
         */
        @Test
        @DisplayName("★★★ 本机以为持有、Redis 里已经没了 → 续期【绝不能】把它凭空造出来")
        void renewalNeverCreatesAPermitFromNothing() {
            registry.markHeld("ghost");
            assertNull(slotScore("ghost"), "前置：Redis 里确实没有它");

            int renewed = permits.renewHeld();

            assertEquals(0, renewed,
                    "★ 返回值该是 0 —— 它明确地告诉我们「这个名额已经不属于你了」");
            assertNull(slotScore("ghost"),
                    "★★★ 续期把一个【不存在】的名额造了出来。ZADD 在成员不存在时会创建它 ——\n"
                            + "   于是名额表里多了一个没人认领、没人释放的条目，"
                            + "占着容量直到 TTL 到期，而且没有任何日志能看出来。\n"
                            + "   ★ 修法：续期走 renew.lua，先 ZSCORE 判定成员还在，才延长"
                            + "（所有租约系统的标准语义：keepalive 不能创建租约）。");
            assertEquals(0, slotsCount(), "名额表必须仍然是空的");
        }
    }

    // ============================================================
    // 五、僵尸清扫
    // ============================================================

    @Nested
    @DisplayName("五、僵尸清扫")
    class Cleanup {

        @Test
        @DisplayName("★ 过期的名额被清掉，未过期的留着")
        void sweepsExpiredPermitsOnly() {
            permits.tryAcquire("req-expired");
            permits.tryAcquire("req-alive");
            forceSlotExpired("req-expired");

            List<Long> swept = permits.cleanup();

            assertEquals(1L, swept.get(0), "应该恰好清掉 1 个过期名额");
            assertNull(slotScore("req-expired"));
            assertNotNull(slotScore("req-alive"), "没过期的名额不能被误清");
        }

        @Test
        @DisplayName("★ 停止心跳的排队者会在存活 TTL 之后被清出队列")
        void sweepsDeadWaiters() {
            permits.tryAcquire("req-1");
            permits.tryAcquire("req-2");
            permits.tryAcquire("req-waiter");

            // 模拟「那个等待者的线程死了」——心跳停掉
            redis.opsForZSet().add(keys().alive(), "req-waiter", 1000);

            permits.cleanup();

            assertNull(rankInQueue("req-waiter"),
                    "心跳停止的等待者必须被清出队列 —— 否则后面所有人的位置都会偏大，"
                            + "而验收标准 2 要求「位置准确」");
        }
    }

    // ============================================================
    // 六、开关
    // ============================================================

    @Nested
    @DisplayName("六、开关（这块是 A/B 用的）")
    class Disabled {

        @Test
        @DisplayName("关掉时一律放行，且 Redis 里【一个 key 都不该被创建】")
        void disabledMeansNoRedisAtAll() {
            // ★ 这条断言的是「什么都不做」，而不是「放行但仍在计数」。
            //   后者会让阶段 7 分不清「没开排队」和「开了但没排队」。
            RateLimitProperties off = new RateLimitProperties();
            off.setEnabled(false);

            ChatPermitService disabled = new ChatPermitService(
                    new LuaScripts(redis), redis, off, new LocalPermitRegistry());

            assertEquals(PermitState.Outcome.GRANTED, disabled.tryAcquire("req-1").outcome());
            assertEquals(PermitState.Outcome.GRANTED, disabled.tryAcquire("req-2").outcome());
            assertEquals(PermitState.Outcome.GRANTED, disabled.tryAcquire("req-3").outcome());

            assertEquals(0, slotsCount(), "关掉时不能在 Redis 里留下任何名额");
            assertEquals(0, queueCount(), "关掉时不能建出队列");
            assertEquals(0, disabled.renewHeld());
            assertEquals(List.of(0L, 0L), disabled.cleanup());
        }
    }

    // ============================================================
    // 七、观测
    // ============================================================

    @Test
    @DisplayName("★ redisState() 必须如实地把「已过期但还没被清」的名额单独报出来")
    void stateSeparatesRealLoadFromUnsweptZombies() {
        permits.tryAcquire("req-1");
        permits.tryAcquire("req-expired");
        forceSlotExpired("req-expired");

        var state = permits.redisState();

        assertEquals(2L, ((Number) state.get("permitsInUse")).longValue(),
                "ZCARD 把僵尸算在内 —— 这正是必须单独报出来的原因");
        assertEquals(1L, ((Number) state.get("expiredNotYetSwept")).longValue(),
                "★ 「僵尸还没清」和「真的满了」在 ZCARD 上长得一模一样，"
                        + "不分开报的话，排查时会把僵尸误判成真实负载");

        var config = permits.config();
        assertEquals(2, config.permits(), "config() 必须报告【真正生效】的值");
        assertTrue(config.enabled());
    }
}
