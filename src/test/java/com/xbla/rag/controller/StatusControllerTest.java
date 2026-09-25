package com.xbla.rag.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对外状态端点（阶段 8）—— <b>它给的字段是一份白名单，这个类就是那份白名单的强制</b>。
 *
 * <h2>★★★ 为什么「不给什么」需要单独的测试</h2>
 *
 * <p>{@code /api/status/ratelimit} 是从 {@code /api/debug/ratelimit/state}
 * 的应用数据里挑出来的一个子集。挑的时候<b>最容易发生的事</b>是
 * 「将来加字段时忘了这里也有一份」—— 于是某个内部字段悄悄上了公网。
 *
 * <p>而那类改动的症状是：<b>没有任何测试会红</b>。
 * 状态页多出一栏，没人会注意到；它看起来只是「信息更丰富了」。
 *
 * <h2>★★ 判据用 {@code JsonNode.has()}，【不用】{@code jsonPath(...).exists()}</h2>
 *
 * <p>因为这两者在<b>「键不存在」和「键存在但值是 null」</b>上给出<b>相同</b>的答案 ——
 * 都报「没有值」。而这个类全部的价值就是分辨「有没有」：
 *
 * <pre>
 *   将来有人给 RateLimitStatus 加了一个值为 null 的字段
 *       → jsonPath("$.data.那个字段").doesNotExist()  【照样通过】
 *       → 而它其实已经在响应体里了
 *
 *   JsonNode.has("那个字段")  ← 问的正是「这个键在不在」，null 也算在
 * </pre>
 *
 * <p>★ 这不是理论问题：本类第一版就是用 {@code exists()} 写的，
 * 而 {@code $.data.redis.seq} 当场红了 —— 那个字段在 debug 端点里
 * <b>键存在、值是 null</b>（队列还没跑过，Redis 里没有 seq 这个键）。
 * 那次失败暴露的正是这个盲区。
 *
 * <p>★ 也不用「响应体里含不含某个子串」：那在本项目已经咬过三次
 * （{@code docs/10} 坑 15）。
 *
 * <h2>★★ 正-反对照：同一个数据源，一个有一个没有</h2>
 *
 * <p>{@code queueHead}（队列里的 traceId 列表）在 debug 端点是<b>有</b>的 ——
 * 那个端点要用来验「位置算得对不对」。少了这条对照，
 * 「status 里没有 queueHead」可能只是因为<b>它压根不存在于任何地方</b>
 * （比如字段被改名了），而那样这个测试就在验一个空集。
 */
@SpringBootTest(properties = {
        // ★ 打开限流，让状态里的数字是「真的在跑」的样子而不是一堆 0
        "xbla.ratelimit.enabled=true",
        "xbla.ratelimit.permits=8"
})
@AutoConfigureMockMvc
@DisplayName("对外状态端点 · 白名单")
class StatusControllerTest {

    /** ★ {@code RateLimitStatus} 承诺过的字段 —— 白名单的唯一出处 */
    private static final String[] WHITELIST = {
            "enabled", "permits", "permitsInUse", "queueSize", "maxQueue",
            "permitTtlMs", "queuePoolActive", "queuePoolMax", "givenUpTotal"
    };

    /**
     * ★★ 刻意<b>不</b>给出去的 —— 每一条都要能说出为什么。
     * 这是一份受审的清单，不是一句「其余都忽略」。
     */
    private static final String[] EXCLUDED = {
            "queueHead",     // ★★ 队列里的 traceId 列表 —— 那是【别人的】标识符
            "keyPrefix",     // Redis 键名前缀，基础设施细节
            "channel",       // Pub/Sub 频道名
            "seq",           // 内部单调序号
            "aliveSize",     // 只在排查僵尸名额时有意义
            "heldHere",      // 本机登记表内部计数
            "hardCapReclaims"
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode bodyOf(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ============================================================
    // 一、该给的给了
    // ============================================================

    @Test
    @DisplayName("★ 200 + code 0，白名单里的字段一个不少")
    void whitelistFieldsArePresent() throws Exception {
        JsonNode body = bodyOf("/api/status/ratelimit");

        assertThat(body.get("code").asInt()).isEqualTo(0);

        JsonNode data = body.get("data");
        for (String field : WHITELIST) {
            assertThat(data.has(field))
                    .as("★ 白名单字段 %s 必须存在 —— 少了它，状态页会静默缺一栏", field)
                    .isTrue();
        }
        assertThat(data.get("enabled").asBoolean()).isTrue();
        assertThat(data.get("permits").asInt()).isEqualTo(8);
    }

    // ============================================================
    // 二、★★ 不该给的确实没给
    // ============================================================

    @Test
    @DisplayName("★★ 内部字段一个都没漏出去（白名单生效）")
    void internalFieldsAreNotLeaked() throws Exception {
        JsonNode data = bodyOf("/api/status/ratelimit").get("data");

        for (String field : EXCLUDED) {
            assertThat(data.has(field))
                    .as("★★ 字段 %s 不该出现在公网端点上 —— "
                            + "它要么是别人的标识符，要么是内部实现细节", field)
                    .isFalse();
        }
    }

    // ============================================================
    // 三、★★ 对照：同一个数在 debug 端点里是【在】的
    // ============================================================

    @Test
    @DisplayName("★★ 对照：debug 端点里这些字段都存在 —— 证明上面那条不是空集")
    void debugEndpointStillHasTheExcludedFields() throws Exception {
        JsonNode data = bodyOf("/api/debug/ratelimit/state").get("data");

        JsonNode redis = data.get("redis");
        JsonNode local = data.get("local");

        assertThat(redis.has("queueHead")).isTrue();
        assertThat(redis.has("keyPrefix")).isTrue();
        assertThat(redis.has("channel")).isTrue();
        // ★ 这个键的值此刻是 null（队列没跑过，Redis 里没有 seq）——
        //   而 has() 照样报 true。上面那段注释说的盲区就是它。
        assertThat(redis.has("seq")).isTrue();
        assertThat(local.has("heldHere")).isTrue();
        assertThat(local.has("hardCapReclaims")).isTrue();
    }
}
