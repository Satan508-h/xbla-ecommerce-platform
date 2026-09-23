package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 名额的<b>绝对持有上限</b>（{@link ChatPermitService#renewHeld} 里那段）—— 对着真 Redis 跑。
 *
 * <h2>★★★ 它防的是哪一类 bug</h2>
 *
 * <p>{@code permitTtl} 答的是「<b>最多能失联多久</b>」：只要持有者还在续期，
 * 名额就永不过期。这对「进程被强杀」是对的（没人续 → 45 秒后回收），
 * 但它有一个<b>没有上界的洞</b>：
 *
 * <pre>
 *   线程卡在出站调用上（socket 永不返回）
 *     → 那个请求的 finally 永远不执行 → 名额不释放
 *     → 而看门狗【只问「本机表里还登记着吗」】，登记着就续期
 *     → 名额续到进程重启为止
 * </pre>
 *
 * <p>2026-09-21 实测：一次跑批里两个出站调用挂死 300 秒以上，
 * 8 个名额当场掉到 6 个，而且再没恢复。
 * 它是 ADR-076（「续期不能创建名额」）的<b>镜像形态</b> ——
 * 那次是「续期把一个已释放的名额复活了」，这次是「续期把一个已死的持有者续到了永远」。
 *
 * <h2>★★ 上限的【逻辑】和它的【取值】是两件事</h2>
 *
 * <p>生产默认 30 分钟（正常路径最坏耗时的两倍余量，见 {@code application.yml}）。
 * 用 30 分钟来测等于 sleep 半小时 —— 那不叫测试。
 * 所以这个类把 {@code max-hold} 压到 <b>400 毫秒</b>，测的是<b>判定本身</b>：
 * 「超了就停、没超就续」。取值对不对是配置审查的事，不是这个测试的事。
 *
 * <h2>⚠️ 计数器是【累计】的，所以断言必须用增量</h2>
 *
 * <p>{@code ChatPermitService} 是 Spring 单例，而同一个测试类里的测试<b>共享上下文</b>。
 * 直接断言 {@code hardCapReclaims() == 1} 会在第二个测试里失败 ——
 * 而失败信息指向那个断言，不指向「你忘了取增量」。
 * 所以每条都先取一次基线。
 *
 * <p>★ 前置条件：需要 {@code docker compose up -d} 起的 Redis。
 */
@SpringBootTest(properties = {
        // ★ 独立的 key 前缀：和应用的 {chat}、以及另一个集成测试的 {test} 物理隔离
        "xbla.ratelimit.key-prefix=xbla:rl:{testcap}",
        "xbla.ratelimit.permits=2",
        "xbla.ratelimit.permit-ttl=45s",
        "xbla.ratelimit.max-hold=400ms"
})
@DisplayName("ChatPermitService · 绝对持有上限（真 Redis）")
class PermitHardCapTest {

    @Autowired
    private ChatPermitService permits;

    @Autowired
    private RateLimitProperties props;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private LocalPermitRegistry registry;

    private RedisQueueKeys keys;

    private RedisQueueKeys keys() {
        if (keys == null) {
            keys = RedisQueueKeys.from(props);
        }
        return keys;
    }

    /** ★ Redis 没有回滚（{@code @Transactional} 对 Redis 无效），必须手动清两个地方 */
    @AfterEach
    void cleanUp() {
        redis.delete(keys().allKeys());
        registry.clearAll();
    }

    private Double slotScore(String traceId) {
        return redis.opsForZSet().score(keys().slots(), traceId);
    }

    private long maxHoldMs() {
        return props.getMaxHold().toMillis();
    }

    // ============================================================

    @Nested
    @DisplayName("一、超过上限之后")
    class OverCap {

        @Test
        @DisplayName("★ 不再续期、从本机表里摘掉、计数器 +1")
        void stopsRenewingOncePastTheCap() throws Exception {
            String id = "stuck-thread";
            permits.tryAcquire(id);

            Double before = slotScore(id);
            assertEquals(1, registry.heldCount(), "前提：它已经在本机登记表里");

            long baseline = permits.hardCapReclaims();
            Thread.sleep(maxHoldMs() + 200);

            int renewed = permits.renewHeld();

            assertEquals(0, renewed, "超期的名额不该出现在「成功续期」的计数里");
            assertFalse(registry.isHeld(id),
                    "★ 必须从本机表里摘掉。不摘的话下一轮心跳会再判一次、再打一条 WARN —— "
                            + "每 15 秒一条、永远打下去，而「一条永远在刷的日志」等于没有日志");
            assertEquals(baseline + 1, permits.hardCapReclaims(), "这个事件必须被计数 —— "
                    + "它的形状是「容量静默变少」，只能靠一个计数器浮出来");
            assertEquals(before, slotScore(id),
                    "★ 我们只是【不再续期】，score 不该动。"
                            + "这里【不 ZREM】是刻意的：万一「它死了」的判断是错的，"
                            + "主动删就是主动制造超卖；放任过期最坏只是晚 45 秒回收");
        }

        @Test
        @DisplayName("★ 反对照：没超期的名额【照常】续期")
        void keepsRenewingBelowTheCap() throws Exception {
            String id = "healthy-request";
            permits.tryAcquire(id);

            Double before = slotScore(id);
            long baseline = permits.hardCapReclaims();
            Thread.sleep(maxHoldMs() / 4);

            int renewed = permits.renewHeld();
            Double after = slotScore(id);

            assertEquals(1, renewed, "没超期的名额必须照常续期");
            assertTrue(after > before,
                    "★ score 必须往前走 —— 否则这道上限就成了【误杀】，"
                            + "而误杀 = 名额被回收但请求还在跑 = 真超卖，"
                            + "那比泄漏严重得多。两个方向的代价不对称，所以取值必须往大取");
            assertTrue(registry.isHeld(id), "没超期就该继续登记着");
            assertEquals(baseline, permits.hardCapReclaims(),
                    "★ 上面那条的反对照：如果上限【永远触发】，"
                            + "「超期就停」会照样成立 —— 那样它测的是空气");
        }
    }

    // ============================================================

    @Nested
    @DisplayName("二、★★ 起点不能被重复 markHeld 推后")
    class StartPointIsStable {

        /**
         * ★★★ 这个测试类里最重要的一条 —— 它守的是 {@code LocalPermitRegistry#markHeld}
         * 里那个 {@code putIfAbsent}。
         *
         * <p>{@code markHeld} 是<b>幂等</b>的（重复调用只是续期，见
         * {@link ChatPermitService} 的说明）。如果它写成 {@code held.put(...)}，
         * 那么每被调一次，「拿到名额的时刻」就被重置成现在 ——
         * 于是绝对上限<b>永远够不到</b>，那道防线退化成恒真条件，
         * <b>而它看起来还在正常工作</b>（代码在、逻辑在、日志里什么都没有）。
         *
         * <p>这正是本项目最怕的那一类：<b>一个看起来成立的断言</b>。
         */
        @Test
        @DisplayName("★ 分两次 markHeld 累计超期 → 必须照样触发")
        void remarkingDoesNotPushTheStartForward() throws Exception {
            String id = "remarked";
            long cap = maxHoldMs();
            long firstLeg = cap * 3 / 4;      // 例如 300ms（< 400ms）
            long secondLeg = cap / 2;         // 例如 200ms

            registry.markHeld(id);
            Thread.sleep(firstLeg);
            // ★ 第二次 markHeld —— 这一步就是陷阱所在
            registry.markHeld(id);
            Thread.sleep(secondLeg);

            // ★★ 反对照：先证明这条断言不是恒真的。
            //    把这两个数摆出来，它们说明「只看第二段是够不到上限的」——
            //    所以下面那个 assert 只在【起点没被重置】时才可能成立。
            assertTrue(secondLeg < cap,
                    "第二段单独看只有 %dms，不到上限 %dms —— 什么都不该触发".formatted(secondLeg, cap));
            assertTrue(firstLeg + secondLeg > cap,
                    "两段加起来 %dms 超过了上限 %dms —— 按【第一次】的起点算就该触发"
                            .formatted(firstLeg + secondLeg, cap));

            long baseline = permits.hardCapReclaims();
            permits.renewHeld();

            assertEquals(baseline + 1, permits.hardCapReclaims(),
                    "★ 没有触发 → 说明起点被第二次 markHeld 推后了。"
                            + "检查 LocalPermitRegistry#markHeld 是不是写成了 held.put(...)："
                            + "它会把「拿到名额的时刻」重置成现在，"
                            + "于是绝对上限永远够不到，而那道防线看起来还在");
            assertFalse(registry.isHeld(id));
        }

        @Test
        @DisplayName("★ 反对照：没重复 markHeld 时，同样的累计时长【也】应该触发")
        void theSameTotalElapsedAlsoFiresWithoutRemarking() throws Exception {
            String id = "not-remarked";
            long cap = maxHoldMs();

            registry.markHeld(id);
            Thread.sleep(cap + 200);

            long baseline = permits.hardCapReclaims();
            permits.renewHeld();

            assertEquals(baseline + 1, permits.hardCapReclaims(),
                    "★ 上面那条的对照：同一个时长，不重复 markHeld 也触发 —— "
                            + "两条一起才说明「触发与否」只取决于起点，不取决于调了几次 markHeld");
            assertFalse(registry.isHeld(id));
        }
    }
}
