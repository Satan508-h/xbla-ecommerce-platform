package com.xbla.rag.ratelimit;

import com.xbla.rag.common.EvalMark;
import com.xbla.rag.config.RateLimitProperties;
import com.xbla.rag.service.CallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Autowired
    private ChatAdmissionService admission;

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

    // ============================================================
    // ★★ 阶段 7：评测标记在这条【最容易被漏掉】的路上也要活着
    // ============================================================

    /**
     * 关掉排队时，{@code ChatAdmissionService} 走的是<b>另一条分支</b>
     * （{@code submitWithBudget} 里第一个 if），它自己构造 {@code CallContext}。
     *
     * <p>★★ 这条分支是全链路里最容易漏掉评测标记的地方，原因是：
     *
     * <pre>
     *   它【不排队】—— 所以没有任何排队日志、没有任何 SSE 事件、
     *   探针的 /state 也看不出区别。漏了标记之后一切照常，
     *   只是那一轮评测的几百行数据全变成了「真实用户」。
     * </pre>
     *
     * <p>★ 而且它只在<b>关掉排队的那个上下文</b>里才跑得到 ——
     * 别的测试类起的是 enabled=true 的上下文，覆盖不到这里。
     * 这就是为什么这条断言必须写在这个类里。
     *
     * <p>★ 这也是对本类主题的一个旁证：{@code Context} 里的排队两列
     * 仍然是 null（「没开排队」该有的样子），而评测标记<b>照样在</b> ——
     * 两件事互不影响，因为它们各自回答的是不同的问题。
     *
     * <h3>★ 关掉排队 ≠ 关掉线程池</h3>
     *
     * <p>第一版这里写的是「关掉时 {@code submit} 是同步的，返回时 work 一定跑完了」——
     * <b>实测是错的</b>。{@code submitWithBudget} 的 disabled 分支虽然不排队，
     * 但它照样把 work 交给 {@code runWithRelease}，而那个方法<b>无论如何都
     * {@code answerExecutor.execute(...)}</b>（那里是「名额归还的唯一出口」，
     * 释放绑在 work 的 {@code finally} 上）。
     *
     * <p>所以这条路径也是异步的，测试必须等 —— 不等的话会拿到一个 null，
     * 而失败信息会指向「标记丢了」，指向一个根本没发生的问题。
     */
    @Test
    @DisplayName("★★ 关掉排队时，评测标记仍然要传到 CallContext")
    void evalMarkSurvivesOnTheDisabledPath() throws Exception {
        EvalMark mark = EvalMark.of("run-disabled", "X-009");
        java.util.concurrent.atomic.AtomicReference<CallContext> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

        admission.submit(new ChatAdmissionService.Admission(
                        "t-eval-disabled", "退货要几天", null, mark),
                new RecordingQueueListener(),
                ctx -> {
                    captured.set(ctx);
                    done.countDown();
                });

        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "work 在 5 秒内没有跑 —— 关掉排队时它也是异步的"
                        + "（交给 answer- 线程池），所以这里必须等");

        CallContext ctx = captured.get();
        assertNotNull(ctx, "拿到锁存器却拿不到上下文 —— 这条路径的行为变了");
        assertNotNull(ctx.eval(),
                "★★ 关掉排队时评测标记被丢了 —— 那一轮评测的每一行都会伪装成真实用户流量，\n"
                        + "   而这条路径【不排队】，所以没有任何日志或事件能看出它被丢了。\n"
                        + "   检查 ChatAdmissionService.submitWithBudget 的第一个 if：\n"
                        + "   它必须走 CallContext.fresh(traceId, admission.eval())，\n"
                        + "   而不是 CallContext.fresh(traceId)");
        assertEquals("run-disabled", ctx.eval().runId());
        assertEquals("X-009", ctx.eval().questionNo());

        // ★ 反对照：排队两列仍然是 null —— 「没开排队」和「评测」是两件事，
        //   前者不该因为后者而被填上一个值
        assertTrue(ctx.queueMs() == null,
                "关掉排队时 queue_ms 必须是 null（不是 0）—— 见 ADR-010 与 V9 的注释");
        assertTrue(ctx.queuePosition() == null);
    }
}
