package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisQueueKeys} —— key 名派生与 <b>hash tag 不变量</b>。
 *
 * <p>纯单测：不起 Spring，不连 Redis。它验证的是一段纯粹的字符串推导逻辑。
 *
 * <h2>★ 这里真正在防的是什么</h2>
 *
 * <p>不是「拼出来的字符串对不对」（那太琐碎），而是
 * <b>「四个 key 是否共享同一个 Cluster hash tag」</b>。
 *
 * <p>这个不变量被破坏的方式有好几种，而且<b>没有一种会在本地报错</b>：
 * <ul>
 *   <li>有人把前缀改成 {@code xbla:rl:chat} —— 花括号掉了</li>
 *   <li>有人给其中一个 key 单独加了 tag —— 看起来更「规范」，实际跨了 slot</li>
 *   <li>有人把 {@link RedisQueueKeys#channel} 也拼上前缀 —— 频道根本不是 key，
 *       但它长得像，很容易顺手拼上</li>
 * </ul>
 *
 * <p>前两种的症状都只在<b>换到 Redis Cluster 部署的那天</b>出现：
 * 三个 Lua 脚本会直接返回 {@code CROSSSLOT} 错误。而单机模式下它们完全正常 ——
 * 所以开发、测试、演示全都测不出来。
 */
@DisplayName("RedisQueueKeys · key 名派生与 hash tag")
class RedisQueueKeysTest {

    /** 建一个只改了前缀的配置，其余用默认值 */
    private static RedisQueueKeys keysOf(String prefix) {
        RateLimitProperties props = new RateLimitProperties();
        props.setKeyPrefix(prefix);
        return RedisQueueKeys.from(props);
    }

    /**
     * 从 {@code Redis Cluster} 的 key 里取出 hash tag（花括号之间的那段）。
     *
     * <p>没有花括号时返回 {@code null} —— <b>这本身就是「这个 key 没有 tag」</b>，
     * 而 Cluster 会用它自己算的 CRC16 决定 slot。
     *
     * <p>⚠️ Redis 的实际规则是「取<b>第一对</b>非空花括号」，这里简化成了
     * 「第一个 { 到后一个 } 之间」，因为我们的 key 名里不会有嵌套花括号。
     */
    private static String hashTag(String key) {
        int open = key.indexOf('{');
        if (open < 0) {
            return null;
        }
        int close = key.indexOf('}', open + 1);
        if (close < 0 || close == open + 1) {
            return null;
        }
        return key.substring(open + 1, close);
    }

    // ============================================================
    // 正：默认配置下，四个 key 共享一个 tag
    // ============================================================

    @Test
    @DisplayName("★ 四个 key 共享同一个 hash tag（默认前缀）")
    void allKeysShareOneHashTag() {
        RedisQueueKeys keys = keysOf("xbla:rl:{chat}");

        Set<String> tags = Stream.of(keys.slots(), keys.queue(), keys.alive(), keys.seq())
                .map(RedisQueueKeysTest::hashTag)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("chat"), tags,
                "四个 key 必须共享同一个 hash tag，否则 Cluster 下 Lua 会报 CROSSSLOT");
    }

    @Test
    @DisplayName("四个 key 的具体名字 —— 改名等于改数据，钉住")
    void keyNamesAreStable() {
        RedisQueueKeys keys = keysOf("xbla:rl:{chat}");

        assertEquals("xbla:rl:{chat}:slots", keys.slots());
        assertEquals("xbla:rl:{chat}:queue", keys.queue());
        assertEquals("xbla:rl:{chat}:alive", keys.alive());
        assertEquals("xbla:rl:{chat}:seq", keys.seq());
    }

    @Test
    @DisplayName("★ 频道【不】加前缀 —— 它不是 key，DEL 和 KEYS 都不该碰它")
    void channelIsNotAPrefixedKey() {
        RedisQueueKeys keys = keysOf("xbla:rl:{chat}");

        assertEquals("xbla:rl:events", keys.channel());
        assertTrue(keys.allKeys().stream().noneMatch(keys.channel()::equals),
                "channel 不该出现在 allKeys() 里 —— allKeys() 是给测试清理用的，"
                        + "把频道混进去会让「删干净」这件事的含义变得不清楚");
    }

    @Test
    @DisplayName("allKeys() 恰好四个，且都在 keyPrefix 下")
    void allKeysCoversExactlyTheFourKeys() {
        RedisQueueKeys keys = keysOf("xbla:rl:{chat}");

        List<String> all = keys.allKeys();
        assertEquals(4, all.size());
        assertTrue(all.stream().allMatch(k -> k.startsWith("xbla:rl:{chat}:")),
                "四个 key 都要带前缀，否则测试清理会漏删或者误删别人的数据");
    }

    // ============================================================
    // ★★ 反：去掉花括号，上面那条不变量【确实】会不成立
    //
    //   没有这一段的话，allKeysShareOneHashTag 可能是恒真的 ——
    //   比如 hashTag() 实现错了（永远返回 "chat"），上面那条也照样绿。
    //   对照组证明「这条断言测的是空气还是事实」。
    // ============================================================

    @Test
    @DisplayName("★★ 反对照：前缀去掉花括号后，四个 key 一个 tag 都提取不到")
    void withoutBracesThereIsNoTagAtAll() {
        RedisQueueKeys keys = keysOf("xbla:rl:chat");

        // 正对照里 tags 恰好是 {"chat"}；这里必须【一个都提取不到】——
        // 两边的差异只来自前缀里有没有那对花括号
        assertTrue(
                Stream.of(keys.slots(), keys.queue(), keys.alive(), keys.seq())
                        .map(RedisQueueKeysTest::hashTag)
                        .allMatch(Objects::isNull),
                "没有花括号时 hashTag() 必须返回 null —— 否则说明它是在瞎猜，"
                        + "那 allKeysShareOneHashTag 就是恒真的");

        assertNull(hashTag(keys.slots()));
    }

    @Test
    @DisplayName("★★ 反对照：空的 {} 不算 tag —— Redis 的规则是「第一对【非空】花括号」")
    void emptyBracesAreNotATag() {
        RedisQueueKeys keys = keysOf("xbla:rl:{}");

        // ★ 这条特别值得钉：{} 看起来像是加了 tag，但 Redis 会忽略它，
        //   于是四个 key 仍然落在 CRC16 算出来的不同 slot 上 ——
        //   「看起来加了」比「没加」更危险。
        assertNull(hashTag(keys.slots()),
                "{} 是空的，Redis 会跳过它去找下一对非空花括号（没有则不加 tag）");
    }

    @Test
    @DisplayName("★★ 反对照：只有一个 { 没有 } 时也不算 tag")
    void unclosedBraceIsNotATag() {
        assertNull(hashTag("xbla:rl:{chat:slots"),
                "只有左花括号 —— Redis 找不到配对的 }，这个 key 没有 tag");
    }

    // ============================================================
    // 边界
    // ============================================================

    @Test
    @DisplayName("空前缀也能派生出 key，只是会以冒号开头 —— 不抛异常")
    void emptyPrefixDoesNotThrow() {
        // 它显然是个配错的配置，但不该【在这里】抛 ——
        // 配置校验是 RateLimitProperties 的事（ignoreUnknownFields 管拼写，
        // 值的合法性要靠探针启动时打出来看），key 派生只管拼字符串。
        RedisQueueKeys keys = keysOf("");

        assertEquals(":slots", keys.slots());
    }

    @Test
    @DisplayName("allKeys() 不可变 —— 防止调用方顺手 add 一个进去")
    void allKeysIsImmutable() {
        RedisQueueKeys keys = keysOf("xbla:rl:{chat}");

        assertThrows(UnsupportedOperationException.class,
                () -> keys.allKeys().add("xbla:rl:{chat}:extra"));
    }
}
