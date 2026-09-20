package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code xbla.ratelimit.enabled=false} 时的行为 —— 开关的<b>反向</b>验证。
 *
 * <h2>★ 为什么单独一个测试类</h2>
 *
 * <p>它需要一份<b>不同的配置</b>（{@code enabled=false}），而 Spring 的测试上下文
 * 按配置缓存 —— 换个属性就是另一个上下文，只能单独开一个类。
 * 这是代价，但值得：证实「关掉时真的一点事都不做」只能在关掉的那个上下文里做。
 *
 * <h2>★ 这条断言防的是什么</h2>
 *
 * <p>「关掉」有两种实现方式，而它们看起来都像关掉了：
 *
 * <pre>
 *   ✗ 抢了名额、但立刻放行     →  Redis 里照样有名额表，只是永远不排队
 *   ✓ 完全不碰 Redis           →  Redis 里什么都不该有
 * </pre>
 *
 * <p>第一种的后果不在运行时，而在<b>阶段 7 的评测</b>：
 * {@code qa_log.queue_ms} 会有值（哪怕是 0），于是
 * 「没开排队」和「开了但没排队」变成两个<b>无法区分</b>的实验条件 ——
 * 而 A/B 对比的全部意义就是区分它们。（同 ADR-010「拿不到就记 NULL」。）
 *
 * <p>★ 所以这里断言的是「Redis 里一个 key 都没有」，
 * 而不是「请求都成功了」—— 后者在两种实现下都成立。
 */
@SpringBootTest(properties = {
        "xbla.ratelimit.enabled=false",
        "xbla.ratelimit.key-prefix=xbla:rl:{hboff}",
        "xbla.ratelimit.permit-ttl=200ms"
})
@DisplayName("QueueHeartbeat · enabled=false 时什么都不做")
class QueueHeartbeatDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ChatPermitService permits;

    @Autowired
    private RateLimitProperties props;

    @Autowired
    private StringRedisTemplate redis;

    private RedisQueueKeys keys() {
        return RedisQueueKeys.from(props);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(keys().allKeys());
    }

    @Test
    @DisplayName("★★ QueueHeartbeat 这个 Bean 根本不存在 —— 不是「存在但跳过」")
    void heartbeatBeanIsAbsent() {
        String[] names = context.getBeanNamesForType(QueueHeartbeat.class);

        assertEquals(0, names.length,
                "关掉时 QueueHeartbeat 不该被注册。★ 这一条比「任务不干活」更强 ——"
                        + "它证明 @ConditionalOnProperty 真的在拦，而不是靠 beat() 内部提前 return。"
                        + "两者的区别在于：后者的话，调度线程照样每 100ms 醒来一次");
    }

    @Test
    @DisplayName("★ 反复抢名额，Redis 里一个 key 都不该出现")
    void disabledModeTouchesNoRedisAtAll() {
        for (int i = 1; i <= 5; i++) {
            assertEquals(PermitState.Outcome.GRANTED, permits.tryAcquire("req-" + i).outcome(),
                    "关掉时任何人都不该被拦住");
        }

        assertEquals(0, permits.renewHeld());
        assertEquals(java.util.List.of(0L, 0L), permits.cleanup());

        assertTrue(keys().allKeys().stream().allMatch(k -> redis.hasKey(k) == false),
                "关掉时 Redis 里不该有任何 key —— 有的话阶段 7 就分不清"
                        + "「没开排队」和「开了但没排队」，那是两个不同的实验条件");
    }

    @Test
    @DisplayName("等一段时间，确认没有后台任务在偷偷碰 Redis")
    void noBackgroundTaskRuns() throws Exception {
        permits.tryAcquire("req-1");

        // 租期是 200ms、间隔会是 66ms —— 有任务的话这段时间够它跑好几轮了
        Thread.sleep(1_000);

        assertTrue(keys().allKeys().stream().allMatch(k -> redis.hasKey(k) == false),
                "等了一秒后 Redis 里出现了 key —— 说明有别的东西在后台跑");
    }
}
