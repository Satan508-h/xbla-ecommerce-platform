package com.xbla.rag.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalPermitRegistry} —— 纯内存态，<b>不需要 Spring、不需要 Redis</b>。
 *
 * <h2>★ 阶段 7 起它多了一件事：记住「什么时候拿到的」</h2>
 *
 * <p>原来它只有一个 {@code Set<String>}。绝对持有上限需要知道<b>起点</b>，
 * 所以 {@code held} 变成了 {@code Map<String, Long>}。这个改动很小，
 * 但它带来两个<b>不会报错</b>的错法，所以值得单独的测试：
 *
 * <pre>
 *   ① markHeld 写成 put(...)  → 重复调用把起点推后 → 上限永远够不到
 *   ② holdStartedAt 对未知 id 返回 0 而不是 null → 「取不到起点」被判成
 *      「持有最久」，于是【最不该回收的那个】反而优先被回收
 * </pre>
 *
 * <p>①的护栏在 {@code PermitHardCapTest.StartPointIsStable} 里（需要真 Redis）；
 * 这里测②，顺带把①的前提钉住。
 */
@DisplayName("LocalPermitRegistry · 本机名额登记表")
class LocalPermitRegistryTest {

    private final LocalPermitRegistry registry = new LocalPermitRegistry();

    @AfterEach
    void cleanUp() {
        registry.clearAll();
    }

    // ============================================================

    @Test
    @DisplayName("★★ 未知的 traceId 返回 null —— 不能返回 0")
    void unknownTraceIdHasNoStartPoint() {
        assertNull(registry.holdStartedAt("从来没持有过"),
                "★ 缺失必须像缺失。返回 0 的后果不是「少一点信息」，是【反过来的判断】：");

        // ★★ 这就是那句「后果」的算术 —— 把它算出来，而不是写成一句注释。
        long now = System.currentTimeMillis();
        long anyReasonableCap = Duration.ofMinutes(30).toMillis();
        assertTrue(now - 0 > anyReasonableCap,
                "★ now 减去 0 是 1970 年至今的毫秒数，它恒大于任何上限 —— "
                        + "于是 renewHeld 的判据 `now - startedAt > maxHold` 会把"
                        + "「取不到起点」的 id 判成【持有最久】的那个，优先强制回收它。"
                        + "而 renewHeld 里那行 `startedAt != null` 的判空正是因为这个");
    }

    @Test
    @DisplayName("★ 反对照：持有中的 traceId 必须【能】拿到起点")
    void heldTraceIdHasAStartPoint() {
        registry.markHeld("held-1");

        Long startedAt = registry.holdStartedAt("held-1");
        assertNotNull(startedAt,
                "★ 上面那条的反对照：如果 holdStartedAt 永远返回 null，"
                        + "「未知返回 null」会照样成立 —— 而绝对上限【一次都不会触发】。"
                        + "两条一起才说明这个方法在区分，而不是恒返回 null");

        long now = System.currentTimeMillis();
        assertTrue(now - startedAt < Duration.ofMinutes(1).toMillis(),
                "起点必须是【真实时钟读数】而不是哨兵值 —— 实测 " + (now - startedAt) + "ms 前");
    }

    // ============================================================

    @Test
    @DisplayName("★★ 重复 markHeld 不能把起点往后推（putIfAbsent 的护栏）")
    void remarkingKeepsTheOriginalStartPoint() throws Exception {
        registry.markHeld("idem");
        Long first = registry.holdStartedAt("idem");

        Thread.sleep(120);
        registry.markHeld("idem");   // ★ 幂等调用 —— 这一步是陷阱所在
        Long second = registry.holdStartedAt("idem");

        assertEquals(first, second,
                "★ 起点必须是【第一次】拿到的时刻。写成 put(...) 的话这里会变新，"
                        + "于是绝对持有上限永远够不到 —— "
                        + "而那道防线看起来还在（代码在、逻辑在、日志里什么都没有）");
    }

    @Test
    @DisplayName("★ 释放在【先】、ZREM 在后 —— forget 必须能立刻被看到")
    void forgetRemovesBothTheEntryAndItsStartPoint() {
        registry.markHeld("gone");
        assertTrue(registry.isHeld("gone"));

        registry.forget("gone");

        assertFalse(registry.isHeld("gone"), "forget 之后不该还登记着");
        assertNull(registry.holdStartedAt("gone"),
                "★ 起点也要一起没 —— 留着它会让 renewHeld 在快照过期的那一瞬间"
                        + "又把它当成一个持有者判一次");
    }

    @Test
    @DisplayName("markHeld 与 markWaiting 是同一个 traceId 的状态迁移，两个集合不能同时有它")
    void heldAndWaitingAreMutuallyExclusive() {
        registry.markHeld("m");
        assertTrue(registry.isHeld("m"));
        assertFalse(registry.isWaiting("m"));

        registry.markWaiting("m");
        assertTrue(registry.isWaiting("m"));
        assertFalse(registry.isHeld("m"), "「从持有变成排队」是一个状态迁移，"
                + "两边各自增删的话迟早会有一个 id 同时在两个集合里");
    }
}
