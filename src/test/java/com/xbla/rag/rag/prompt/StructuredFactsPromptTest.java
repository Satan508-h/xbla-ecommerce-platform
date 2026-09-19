package com.xbla.rag.rag.prompt;

import com.xbla.rag.rag.facts.StructuredFacts;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化事实那一节在 prompt 里的样子（阶段 5.9）。纯单测，不碰 Spring、不碰数据库。
 *
 * <h3>★★ 本类真正要证明的三件事</h3>
 *
 * <ol>
 *   <li><b>检索为空时，事实仍然在。</b>
 *       {@code build()} 在 {@code chunks} 为空时会 early return，
 *       而事实是拼进 {@code fixed} 的 —— 这个区别只有在
 *       「一条都没检索到、但政策表查到了」时才显形。
 *       ⚠️ 那正是<b>最需要硬数据</b>的时刻：知识库什么都没给，
 *       而用户问的是「退货要几天」。拼错位置的后果是
 *       模型没有任何依据，只能编</li>
 *   <li><b>顺序是「摘要 → 事实 → 资料」。</b>
 *       资料是每次都变的，必须在最后（前缀缓存）；
 *       而在摘要和事实之间，把<b>更常出现的</b>放前面 ——
 *       摘要是每轮都有，事实只有售后退换货才有</li>
 *   <li><b>换货天数为 null 时写「未规定」，不写「0 天」。</b>
 *       「换货 0 天」是一句<b>听起来像事实的胡说</b>，模型会原样转述</li>
 * </ol>
 */
@DisplayName("RagPromptBuilder · 结构化事实那一节")
class StructuredFactsPromptTest {

    private final RagPromptBuilder builder = new RagPromptBuilder();

    private static StructuredFacts facts(StructuredFacts.PolicyTerm... terms) {
        return new StructuredFacts(List.of(terms));
    }

    private static StructuredFacts.PolicyTerm term(String category, int ret, Integer exch) {
        return new StructuredFacts.PolicyTerm(category, "AS-TEST", ret, exch);
    }

    private static RetrievedChunk chunk(String content) {
        return new RetrievedChunk(1L, 2L, 0, content, "售后政策 > 退换货", 0.9);
    }

    private static final String HEADER = "【售后政策硬数据】";

    // ============================================================
    // 一、出现与不出现
    // ============================================================

    @Test
    @DisplayName("有事实时出现标题、天数、以及「以此为准」的说明")
    void sectionAppears() {
        String prompt = builder.build("你是客服", List.of(chunk("退货 7 天")),
                false, null, facts(term("手机", 7, 15)));

        assertThat(prompt).contains(HEADER);
        assertThat(prompt).contains("手机：退货 7 天 / 换货 15 天");
        assertThat(prompt).contains("回答时限时以此为准");
    }

    @Test
    @DisplayName("★ 没有事实时整节不出现 —— 不留空标题")
    void sectionAbsentWhenEmpty() {
        String prompt = builder.build("你是客服", List.of(chunk("退货 7 天")),
                false, null, StructuredFacts.EMPTY);

        assertThat(prompt).doesNotContain(HEADER);
    }

    // ============================================================
    // 二、★★ 和「检索为空」的早返回
    // ============================================================

    @Nested
    @DisplayName("★★ 检索一条都没召回时，事实必须【还在】")
    class EmptyRetrieval {

        @Test
        @DisplayName("★★ chunks 为空 + 有事实 → 事实那一节仍然出现")
        void factsSurviveEmptyChunks() {
            String prompt = builder.build("你是客服", List.of(),
                    false, null, facts(term("手机", 7, 15)));

            assertThat(prompt)
                    .as("★★ 拼错地方的后果：知识库什么都没给、政策表给了 7 天，"
                            + "而模型只看到「未检索到」—— 它只能编")
                    .contains(HEADER, "手机：退货 7 天 / 换货 15 天");
            assertThat(prompt)
                    .as("「没检索到」那句也要在 —— 它说的是【资料】那一半")
                    .contains("本次未从平台知识库中检索到相关内容");
        }

        @Test
        @DisplayName("★ 正-反对照：chunks 为空且【没有】事实时，两样都不该有")
        void neitherWhenBothEmpty() {
            String prompt = builder.build("你是客服", List.of(),
                    false, null, StructuredFacts.EMPTY);

            assertThat(prompt).doesNotContain(HEADER);
            assertThat(prompt).contains("本次未从平台知识库中检索到相关内容");
        }

        @Test
        @DisplayName("★ 传 null facts 等价于 EMPTY，不抛异常")
        void nullFactsTolerated() {
            String prompt = builder.build("你是客服", List.of(chunk("x")),
                    false, null, null);

            assertThat(prompt).doesNotContain(HEADER);
        }
    }

    // ============================================================
    // 三、★ 顺序
    // ============================================================

    @Nested
    @DisplayName("★ 顺序：固定指令 → 摘要 → 硬数据 → 资料（可变部分必须最后）")
    class Ordering {

        private static final String BASE = "你是电商客服助手";
        private static final String SUMMARY_TEXT = "用户想买个送长辈的手机";
        private static final String MATERIAL_TEXT = "自签收之日起 7 天内";

        private String prompt() {
            return builder.build(BASE,
                    List.of(chunk(MATERIAL_TEXT)),
                    true, SUMMARY_TEXT,
                    facts(term("手机", 7, 15)));
        }

        @Test
        @DisplayName("★ 硬数据在摘要【之后】")
        void factsAfterSummary() {
            String p = prompt();
            assertThat(p.indexOf(SUMMARY_TEXT))
                    .as("★ 摘要在硬数据之前。反过来的话，有事实的那次会把摘要往后推，"
                            + "而摘要是每轮都有的 —— 摘要那段缓存就全丢了")
                    .isLessThan(p.indexOf(HEADER));
        }

        @Test
        @DisplayName("★ 硬数据在【资料】之前 —— 资料是可变部分，必须最后")
        void factsBeforeMaterials() {
            String p = prompt();
            assertThat(p.indexOf(HEADER)).isLessThan(p.indexOf(MATERIAL_TEXT));
            assertThat(p.indexOf(HEADER)).isLessThan(p.indexOf("【知识库资料】"));
        }

        @Test
        @DisplayName("★ 基础指令在最前面（它才是每次都一样、能命中缓存的那段）")
        void baseFirst() {
            assertThat(prompt().indexOf(BASE)).isEqualTo(0);
        }
    }

    // ============================================================
    // 四、★ 边界值的写法
    // ============================================================

    @Nested
    @DisplayName("★ 拿不到的值不能兜成 0")
    class Nulls {

        @Test
        @DisplayName("★ 换货天数为 null → 「未规定」，不能写「换货 0 天」")
        void nullExchangeDays() {
            String prompt = builder.build("你是客服", List.of(chunk("x")),
                    false, null, facts(term("家电", 7, null)));

            assertThat(prompt).contains("换货天数未规定");
            assertThat(prompt)
                    .as("★「换货 0 天」读起来像一条事实，模型会原样告诉用户")
                    .doesNotContain("换货 0 天");
        }

        @Test
        @DisplayName("★ 类目为 null（通用政策）→ 写「通用」，不写 null 也不省略")
        void nullCategory() {
            String prompt = builder.build("你是客服", List.of(chunk("x")),
                    false, null, facts(term(null, 7, 15)));

            assertThat(prompt).contains("通用：退货 7 天 / 换货 15 天");
            assertThat(prompt).doesNotContain("null");
        }

        @Test
        @DisplayName("多条政策按给定顺序逐行出现")
        void multipleTerms() {
            String prompt = builder.build("你是客服", List.of(chunk("x")), false, null,
                    facts(term(null, 7, 15), term("手机", 7, 15), term("家电", 7, 30)));

            int generic = prompt.indexOf("通用：");
            int phone = prompt.indexOf("手机：");
            int appliance = prompt.indexOf("家电：");
            assertThat(generic).isLessThan(phone);
            assertThat(phone).isLessThan(appliance);
        }
    }
}
