package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QueueHeartbeat} —— 看门狗真的在后台跑，间隔真的从租期派生。
 *
 * <h2>★ 这个测试类唯一不可替代的价值</h2>
 *
 * <p>{@link ChatPermitServiceIntegrationTest} 已经测过 {@code renewHeld()} 的<b>逻辑</b>。
 * 但那只是「我把它叫醒它会干活」，而整个阶段 6 最要紧的那一环是
 * <b>「没人叫它，它自己会醒」</b>。
 *
 * <p>这两件事之间的差距，正好是三个可能静默失效的地方：
 *
 * <pre>
 *   ① @EnableScheduling 没生效        → SchedulingConfigurer 根本不被咨询
 *   ② @ConditionalOnProperty 写反了   → Bean 存在但任务没注册
 *   ③ 间隔算错了（比如算成租期的 3 倍）→ 任务在跑，但租期早过了
 * </pre>
 *
 * <p>三者都<b>不会报错</b>：应用正常启动、日志一切正常，
 * 只是名额不再被续期。所以这里用「把 score 压到过去、然后<b>什么都不做、只等</b>」
 * 来测 —— 如果后台真的在续期，score 会自己回到未来。
 *
 * <p>★ 用 {@code permit-ttl=300ms}（间隔 100ms）把等待压到秒级。
 * 用默认的 45s 的话这个测试要跑 15 秒以上，那就没人愿意跑了。
 */
@SpringBootTest(properties = {
        "xbla.ratelimit.key-prefix=xbla:rl:{hb}",
        "xbla.ratelimit.permits=2",
        "xbla.ratelimit.permit-ttl=300ms"
})
@DisplayName("QueueHeartbeat · 看门狗（真调度、真 Redis）")
class QueueHeartbeatIntegrationTest {

    @Autowired
    private ChatPermitService permits;

    @Autowired
    private QueueHeartbeat heartbeat;

    @Autowired
    private RateLimitProperties props;

    @Autowired
    private LocalPermitRegistry registry;

    @Autowired
    private StringRedisTemplate redis;

    private RedisQueueKeys keys() {
        return RedisQueueKeys.from(props);
    }

    private Double slotScore(String traceId) {
        return redis.opsForZSet().score(keys().slots(), traceId);
    }

    private void forceSlotExpired(String traceId) {
        redis.opsForZSet().add(keys().slots(), traceId, 1000);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(keys().allKeys());
        registry.clearAll();
    }

    // ============================================================
    // ★★ 后台调度真的在跑
    // ============================================================

    @Test
    @DisplayName("★★★ 什么都不做，只等 —— 名额会自己从「已过期」变回「未过期」")
    void schedulerRenewsInTheBackground() throws Exception {
        permits.tryAcquire("req-1");
        forceSlotExpired("req-1");
        assertEquals(1000.0, slotScore("req-1"), "前提：score 已经被压到过去了");

        // ★ 这一段是全部要点：不调 beat()、不碰服务，只是等。
        //   唯一能让 score 动起来的东西，就是后台那个调度线程。
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Double score = slotScore("req-1");
            if (score != null && score > System.currentTimeMillis()) {
                return;   // ✅ 后台确实在续期
            }
            Thread.sleep(25);
        }

        throw new AssertionError(
                "等了 5 秒，名额没有被续期。可能的原因（都不会报错，所以只能这样测）："
                        + "① @EnableScheduling 没生效；"
                        + "② QueueHeartbeat 的 @ConditionalOnProperty 没匹配上；"
                        + "③ SchedulingConfigurer 没被咨询到；"
                        + "④ 间隔算错了。租期是 " + props.getPermitTtl());
    }

    @Test
    @DisplayName("★ 心跳线程的名字是 ratelimit- 开头 —— 它在 jstack 里是唯一的身份")
    void heartbeatThreadIsNamed() throws Exception {
        // ★ 这条不是洁癖：线程名叫 pool-1-thread-1 的话，
        //   排查「谁在每 15 秒碰一次 Redis」时没有任何线索。
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            boolean found = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> t.getName().startsWith("ratelimit-"));
            if (found) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("没找到 ratelimit- 开头的线程，调度器可能没起来");
    }

    // ============================================================
    // 间隔必须【派生】自租期
    // ============================================================

    @Test
    @DisplayName("★ 间隔 = 租期 / 3")
    void intervalIsOneThirdOfTtl() {
        assertEquals(Duration.ofMillis(100), heartbeat.interval(),
                "300ms 的租期，间隔应该是 100ms");
    }

    /**
     * ★★ 正-反对照：证明上面那条<b>不是</b>「配置里恰好写了 100ms」。
     *
     * <p>如果 {@code interval()} 的实现是「返回一个写死的常量」，
     * 只要那个常量和测试期望的值一样，上面那条断言照样通过。
     * 这里换一个完全不同的租期，看间隔<b>跟着变</b>——
     * 那才是「派生」和「碰巧」的区别。
     */
    @Test
    @DisplayName("★★ 反对照：改租期，间隔必须跟着变（证明它是派生而不是写死的）")
    void intervalFollowsTtlChange() {
        RateLimitProperties other = new RateLimitProperties();
        other.setPermitTtl(Duration.ofSeconds(90));

        QueueHeartbeat derived = new QueueHeartbeat(permits, other);

        assertEquals(Duration.ofSeconds(30), derived.interval(),
                "90 秒的租期应该给出 30 秒的间隔。"
                        + "如果这里还是 100ms，说明 interval() 返回的是一个常量，"
                        + "那么「间隔 = 租期/3」那条断言就是恒真的");

        // ★ 顺带钉住那个真正的约束：间隔必须显著小于租期，
        //   否则「容忍两次连续失联」这个设计意图就落空了。
        assertTrue(derived.interval().multipliedBy(3).equals(other.getPermitTtl()),
                "间隔 × 3 必须恰好等于租期 —— 这是容忍两次失联的数学来源");
    }

    // ============================================================
    // 手动跑一次 beat()
    // ============================================================

    @Test
    @DisplayName("beat() 一次做完两件事：续期 + 清扫")
    void beatRenewsAndSweeps() {
        permits.tryAcquire("req-alive");
        forceSlotExpired("req-alive");

        // 造一个真的死掉的排队者：心跳很旧，且没人再重试
        redis.opsForZSet().add(keys().alive(), "req-ghost", 1000);
        redis.opsForZSet().add(keys().queue(), "req-ghost", 1);

        heartbeat.beat();

        assertNotNull(slotScore("req-alive"));
        assertTrue(slotScore("req-alive") > System.currentTimeMillis(),
                "活着的名额要被续到未来");
        assertEquals(null, redis.opsForZSet().rank(keys().queue(), "req-ghost"),
                "心跳停止的排队者要被清出队列");
    }

    @Test
    @DisplayName("★ beat() 不抛异常 —— Redis 的问题不该让调度线程刷堆栈")
    void beatSwallowsExceptions() {
        // ★ 这里没法定向地让 Redis 报错（关掉容器太重）。
        //   能验证的是「正常情况下它安静地跑完」——
        //   而 catch 的必要性写在 QueueHeartbeat.beat() 的注释里。
        heartbeat.beat();
        heartbeat.beat();
    }
}
