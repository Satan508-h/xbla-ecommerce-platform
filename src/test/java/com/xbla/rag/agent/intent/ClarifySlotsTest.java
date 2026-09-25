package com.xbla.rag.agent.intent;

import com.xbla.rag.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 槽位词表与优先级的纯单测（阶段 9.4）。
 *
 * <p>不花钱、不起 Spring —— 它守的是<b>两道收尾</b>：
 * ① 模型自创的槽位名不许被问（{@code readMissing} 刻意不过滤，
 * 过滤在这里）；② 缺多个时问哪一个。
 *
 * <p>★ 每条都配了反对照 —— 只断言「自创的被丢了」不够，
 * 还要断言「认得的确实留下了」，否则一个「全部返回空」的实现也是绿的。
 */
@DisplayName("ClarifySlots · 槽位词表")
class ClarifySlotsTest {

    @Nested
    @DisplayName("一、knownOnly：认得的留下、自创的丢掉")
    class KnownOnly {

        @Test
        @DisplayName("① 自创的被丢掉，认得的按【模型给的顺序】留下")
        void keepsKnownInModelOrder() {
            assertThat(ClarifySlots.knownOnly(List.of("颜色", "budget", "purpose", "尺寸")))
                    .as("★ 顺序是模型的原始顺序，不是我们的优先级顺序 —— "
                            + "后者是 pickToAsk 的事，两者别混")
                    .containsExactly("budget", "purpose");

            // 反对照：同一个输入里认得的那个必须留下（否则上面那条可能恒真）
            assertThat(ClarifySlots.knownOnly(List.of("budget"))).containsExactly("budget");
        }

        @Test
        @DisplayName("② 去重：模型重复报同一个槽位只留一份")
        void deduplicates() {
            assertThat(ClarifySlots.knownOnly(List.of("product", "product", "product")))
                    .containsExactly("product");
        }

        @Test
        @DisplayName("③ null / 空 / 全是自创 → 空表（不抛）")
        void emptyAndNullAreSafe() {
            assertThat(ClarifySlots.knownOnly(null)).isEmpty();
            assertThat(ClarifySlots.knownOnly(List.of())).isEmpty();
            assertThat(ClarifySlots.knownOnly(List.of("颜色"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("二、pickToAsk：缺多个时问哪一个")
    class PickToAsk {

        @Test
        @DisplayName("④ ★★ 按固定优先级挑，与模型给的顺序无关")
        void picksByOurPriorityNotModelOrder() {
            // 模型把 budget 报在前面，我们仍然先问 product（最具体的那个）
            assertThat(ClarifySlots.pickToAsk(List.of("budget", "product")))
                    .isEqualTo(ClarifySlots.PRODUCT);
            assertThat(ClarifySlots.pickToAsk(List.of("budget", "category")))
                    .isEqualTo(ClarifySlots.CATEGORY);
            assertThat(ClarifySlots.pickToAsk(List.of("budget", "purpose")))
                    .isEqualTo(ClarifySlots.PURPOSE);

            // 反对照：只给 budget 时必须还是 budget ——
            // 否则上面三条可能只是「恒定返回 product」
            assertThat(ClarifySlots.pickToAsk(List.of("budget")))
                    .isEqualTo(ClarifySlots.BUDGET);
        }

        @Test
        @DisplayName("⑤ 优先级顺序与 KNOWN 的声明顺序一致（它们是同一个承诺）")
        void priorityFollowsDeclaredOrder() {
            // ★ 这条防的是「有人改了 KNOWN 的顺序却没意识到那同时改了优先级」
            for (int i = 0; i + 1 < ClarifySlots.KNOWN.size(); i++) {
                String higher = ClarifySlots.KNOWN.get(i);
                String lower = ClarifySlots.KNOWN.get(i + 1);
                assertThat(ClarifySlots.pickToAsk(List.of(lower, higher)))
                        .as("KNOWN 里 %s 排在 %s 前面 ⇒ 两者都缺时应当问前者", higher, lower)
                        .isEqualTo(higher);
            }
        }

        @Test
        @DisplayName("⑥ 一个都认不出 / 空 / null → null（调用方回落到固定文案）")
        void nothingKnownReturnsNull() {
            assertThat(ClarifySlots.pickToAsk(List.of("颜色"))).isNull();
            assertThat(ClarifySlots.pickToAsk(List.of())).isNull();
            assertThat(ClarifySlots.pickToAsk(null)).isNull();

            // 反对照
            assertThat(ClarifySlots.pickToAsk(List.of("颜色", "purpose"))).isNotNull();
        }
    }

    @Nested
    @DisplayName("三、★ 词表与配置的一致性")
    class ConfigAgreement {

        @Test
        @DisplayName("⑦ 每个认得的槽位都配了反问文案（少一个 = 那个槽位静默回落固定文案）")
        void everyKnownSlotHasAQuestion() {
            AgentProperties.Slots slots = new AgentProperties().getSlots();

            for (String slot : ClarifySlots.KNOWN) {
                assertThat(slots.getQuestions())
                        .as("★ 槽位 %s 没有配反问文案 —— 它会被问成那句固定的"
                                + "「你是想问哪款商品呢？」，而日志里没有任何迹象", slot)
                        .containsKey(slot);
                assertThat(slots.getQuestions().get(slot)).isNotBlank();
            }
        }

        @Test
        @DisplayName("⑧ 默认值里不多配（多配一个键 = 一个永远读不到的死配置）")
        void noExtraQuestions() {
            assertThat(new AgentProperties().getSlots().getQuestions().keySet())
                    .as("★ 配置里的键必须【恰好】是词表 —— 多出来的那个永远是死配置")
                    .containsExactlyInAnyOrderElementsOf(ClarifySlots.KNOWN);
        }

        @Test
        @DisplayName("⑨ 默认文案是【一句话】，不含换行（它要原样推给用户）")
        void questionsAreSingleLine() {
            for (String text : new AgentProperties().getSlots().getQuestions().values()) {
                assertThat(text).doesNotContain("\n");
                assertThat(text).as("太长的文案不像一句反问").hasSizeLessThan(120);
            }
        }

        @Test
        @DisplayName("⑩ KNOWN 没有重复项（重复会让 pickToAsk 的语义变得可疑）")
        void knownHasNoDuplicates() {
            assertThat(ClarifySlots.KNOWN)
                    .hasSameSizeAs(Arrays.stream(ClarifySlots.KNOWN.toArray()).distinct().toList());
        }
    }
}
