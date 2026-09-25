package com.xbla.rag.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 悬着的澄清状态的编解码单测（阶段 9.4）。
 *
 * <p>不花钱、不起 Spring。它守的是那条贯穿全库的纪律：
 * <b>「读不出来」和「读出来是空的」要能分开</b>，
 * 而且<b>读不出来时绝不能抛</b> —— 一个坏状态不该让整次问答失败
 * （它会退化成 9.3 的行为，而那是一个能用的行为）。
 */
@DisplayName("PendingClarify · 待澄清状态")
class PendingClarifyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PendingClarify sample() {
        return new PendingClarify("那个怎么样", List.of("颜色", "product"), "product",
                OffsetDateTime.parse("2026-09-25T20:00:00+08:00"));
    }

    @Nested
    @DisplayName("一、往返")
    class RoundTrip {

        @Test
        @DisplayName("① ★★ 写进去、读回来，逐字段相等")
        void roundTripKeepsEverything() {
            PendingClarify back = PendingClarify.read(MAPPER, PendingClarify.write(MAPPER, sample()));

            assertThat(back).isNotNull();
            assertThat(back.question()).isEqualTo("那个怎么样");
            assertThat(back.slots())
                    .as("★ 顺序保留 —— 它同时是落库的那一份（模型的原话）")
                    .containsExactly("颜色", "product");
            assertThat(back.asked()).isEqualTo("product");
            assertThat(back.askedAt()).isEqualTo(OffsetDateTime.parse("2026-09-25T20:00:00+08:00"));
        }

        @Test
        @DisplayName("② 键序固定（v 在最前）—— 人能直接 diff 两次输出")
        void keyOrderIsStable() {
            String json = PendingClarify.write(MAPPER, sample());

            assertThat(json).startsWith("{\"v\":1,\"question\":");
        }

        @Test
        @DisplayName("③ 全是空的那种（问了但没记下槽位）也能往返")
        void emptySlotsRoundTrip() {
            PendingClarify back = PendingClarify.read(MAPPER,
                    PendingClarify.write(MAPPER, PendingClarify.of("那个怎么样", List.of(), null)));

            assertThat(back).isNotNull();
            assertThat(back.slots()).isEmpty();
            assertThat(back.asked()).isNull();
            assertThat(back.askedAt()).as("★ of() 会补上当前时间").isNotNull();
        }
    }

    @Nested
    @DisplayName("二、★ 读不出来时一律 null，且绝不抛")
    class BadInput {

        @Test
        @DisplayName("④ NULL / 空白 = 没有待澄清（常态，不是异常）")
        void nullAndBlankAreNormal() {
            assertThat(PendingClarify.read(MAPPER, null)).isNull();
            assertThat(PendingClarify.read(MAPPER, "")).isNull();
            assertThat(PendingClarify.read(MAPPER, "   ")).isNull();
        }

        @Test
        @DisplayName("⑤ ★ 坏 JSON 不抛，返回 null —— 一个坏状态不该让整次问答失败")
        void brokenJsonDoesNotThrow() {
            assertThatCode(() -> {
                assertThat(PendingClarify.read(MAPPER, "{半截")).isNull();
                assertThat(PendingClarify.read(MAPPER, "not json at all")).isNull();
                assertThat(PendingClarify.read(MAPPER, "[1,2,3]")).isNull();
                assertThat(PendingClarify.read(MAPPER, "\"just a string\"")).isNull();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("⑥ ★ 缺 question = 没有锚 → 当作没有待澄清（宁可不注入）")
        void missingQuestionIsUnusable() {
            assertThat(PendingClarify.read(MAPPER, "{\"v\":1,\"slots\":[\"product\"]}")).isNull();
            assertThat(PendingClarify.read(MAPPER, "{\"v\":1,\"question\":\"  \"}")).isNull();

            // 反对照：同一个 JSON 补上 question 就能读出来（否则上面那条可能恒真）
            assertThat(PendingClarify.read(MAPPER, "{\"v\":1,\"question\":\"那个怎么样\"}"))
                    .isNotNull();
        }

        @Test
        @DisplayName("⑦ 时间读不出来不影响其它字段（它不参与任何判断）")
        void badTimeIsHarmless() {
            PendingClarify back = PendingClarify.read(MAPPER,
                    "{\"v\":1,\"question\":\"那个怎么样\",\"slots\":[\"product\"],"
                            + "\"asked\":\"product\",\"at\":\"昨天\"}");

            assertThat(back).isNotNull();
            assertThat(back.askedAt()).isNull();
            assertThat(back.asked()).isEqualTo("product");
        }

        @Test
        @DisplayName("⑧ slots 里混进非字符串/空白 → 只留字符串（与 readMissing 的规则一致）")
        void slotsAreSanitised() {
            PendingClarify back = PendingClarify.read(MAPPER,
                    "{\"v\":1,\"question\":\"那个怎么样\",\"slots\":[\"product\",3,null,\"  \",\"budget\"]}");

            assertThat(back).isNotNull();
            assertThat(back.slots()).containsExactly("product", "budget");
        }

        @Test
        @DisplayName("⑨ write(null) = 不写（返回 null）")
        void writeNullIsNothing() {
            assertThat(PendingClarify.write(MAPPER, null)).isNull();
        }
    }

    @Nested
    @DisplayName("三、★ 类不变式")
    class Invariants {

        @Test
        @DisplayName("⑩ 构造时就 defensive copy —— 传进来的 List 改不动它")
        void slotsAreDefensivelyCopied() {
            List<String> mutable = new java.util.ArrayList<>(List.of("product"));
            PendingClarify pending = new PendingClarify("那个怎么样", mutable, "product", null);

            mutable.add("budget");

            assertThat(pending.slots())
                    .as("★ 不 copy 的话，落库的那一份会随别人改 List 而变")
                    .containsExactly("product");
        }

        @Test
        @DisplayName("⑪ slots 传 null 也不炸（构造器补空表）")
        void nullSlotsBecomeEmpty() {
            assertThat(new PendingClarify("那个怎么样", null, null, null).slots()).isEmpty();
            assertThat(new PendingClarify("那个怎么样", null, null, null).describe())
                    .as("describe 是日志用的，不能因为它 NPE 把问答搞挂")
                    .contains("asked=null");
        }

        @Test
        @DisplayName("⑫ describe 会把原话压成一行（日志纪律：不让换行把日志打乱）")
        void describeIsOneLine() {
            PendingClarify pending = PendingClarify.of("第一行\n第二行", List.of("product"), "product");

            assertThat(pending.describe()).doesNotContain("\n");
            assertThat(Arrays.stream(pending.describe().split("\n")).count()).isEqualTo(1);
        }
    }
}
