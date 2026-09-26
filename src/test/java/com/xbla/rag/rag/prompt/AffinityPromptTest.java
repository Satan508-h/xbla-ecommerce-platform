package com.xbla.rag.rag.prompt;

import com.xbla.rag.rag.facts.StructuredFacts;
import com.xbla.rag.rag.profile.UserAffinity;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 偏好块（阶段 9.5）—— 纯单测，不连库、不花钱。
 *
 * <h2>★★ 它盯的三件事</h2>
 *
 * <ol>
 *   <li><b>渲染的是事实，不是偏好</b> —— 这一段里不能出现「你喜欢」这种断言。
 *       模型对 prompt 里的话照单全收，写成偏好它就会当成既定事实去回答，
 *       而实测样本只有几笔订单（{@code UserAffinity} 类注释里有那张表）</li>
 *   <li><b>没有偏好时，prompt 与不传它【逐字节相同】</b> ——
 *       这是「关掉/没有 = 与上一阶段一致」这条项目纪律的落点。
 *       写成一个「总是多一段」的实现，会让 9.5 之前所有关于 prompt 的结论失效</li>
 *   <li><b>检索为空时偏好段仍然在</b> —— 它是拼进 {@code fixed} 的，
 *       这个 early return 是 {@code RagPromptBuilder} 最经典的一个坑
 *       （硬数据踩过一次，{@code StructuredFactsPromptTest} 里有一条对偶测试）</li>
 * </ol>
 */
@DisplayName("RagPromptBuilder · 偏好块（9.5）")
class AffinityPromptTest {

    private final RagPromptBuilder builder = new RagPromptBuilder();

    private static final String BASE = "你是休伯利安平台的客服助手。";

    private static final List<RetrievedChunk> CHUNKS = List.of(
            new RetrievedChunk(1L, 1L, 1, "自签收之日起 7 天内支持无理由退货。", "退货政策", 0.9));

    // ============================================================
    // 夹具
    // ============================================================

    private static UserAffinity affinity(List<UserAffinity.Count> categories,
                                         List<UserAffinity.Count> brands,
                                         BigDecimal min, BigDecimal max, BigDecimal avg,
                                         Integer memberLevel) {
        return new UserAffinity(7, 7, 180, categories, brands, min, max, avg, memberLevel);
    }

    private static UserAffinity typical() {
        return affinity(
                List.of(new UserAffinity.Count("家用电器", 3),
                        new UserAffinity.Count("笔记本电脑", 2),
                        new UserAffinity.Count("耳机", 1)),
                List.of(new UserAffinity.Count("华为", 2), new UserAffinity.Count("联想", 1)),
                new BigDecimal("484"), new BigDecimal("15065"), new BigDecimal("5431"),
                2);
    }

    // ============================================================
    // 一、渲染
    // ============================================================

    @Nested
    @DisplayName("一、渲染出来的是事实")
    class Render {

        @Test
        @DisplayName("★ 样本量与计数都在，而且件数/类目数/品牌是能对上的")
        void sampleSizeAndCounts() {
            String text = RagPromptBuilder.affinitySection(typical());

            assertThat(text)
                    .contains("【这位用户的购买记录】")
                    .as("★ 样本大小必须写出来 —— 模型要靠它决定把这几行看多重")
                    .contains("近 180 天内 7 笔已付款订单（共 7 件商品）")
                    .contains("涉及 3 个类目：家用电器×3、笔记本电脑×2、耳机×1")
                    .contains("买过的品牌：华为×2、联想×1")
                    .contains("成交单价 484 ~ 15065 元，平均 5431 元")
                    .contains("会员等级：银卡");
        }

        @Test
        @DisplayName("★★ 措辞里不出现「偏好」—— 它写的是事实，不是结论")
        void neverSaysPreference() {
            String text = RagPromptBuilder.affinitySection(typical());

            assertThat(text)
                    .as("★★ 写成「你偏好 X」就是一句关于这位用户的假话："
                            + "实测最活跃的用户也只有 7 笔订单、5 个品牌一个都不重。"
                            + "而模型会把 prompt 里的断言原样转述给用户")
                    .doesNotContain("偏好")
                    .doesNotContain("你喜欢的")
                    .as("★ 正面那半：它必须说清自己是历史事实")
                    .contains("历史订单")
                    .contains("样本很小");
        }

        @Test
        @DisplayName("★★ 会员等级拿不到时写「未记录」，不兜成默认等级")
        void unknownMemberLevelIsSaidOutLoud() {
            String text = RagPromptBuilder.affinitySection(
                    affinity(List.of(), List.of(), null, null, null, null));

            assertThat(text)
                    .as("★★ 兜成「普通会员」会把「我们不知道」写成一个具体事实，"
                            + "而模型分不出它和真值的区别 —— "
                            + "X-Xbla-User-Id: 999999 这类请求真的会走到这里")
                    .contains("会员等级：未记录")
                    .doesNotContain("普通");
            // ★ 反面对照：同一段代码在拿到等级时必须写等级，否则上面那条
            //   「不出现普通」可能只是因为这段渲染从来不输出会员等级
            assertThat(RagPromptBuilder.affinitySection(
                    affinity(List.of(), List.of(), null, null, null, 1)))
                    .contains("会员等级：普通");
        }

        @Test
        @DisplayName("★ 三个价格一起缺时整行不出现 —— 不留一个「0 ~ 0 元」")
        void missingPricesOmitTheLine() {
            String text = RagPromptBuilder.affinitySection(
                    affinity(List.of(), List.of(), null, null, null, 3));

            assertThat(text).doesNotContain("成交单价").contains("会员等级：金卡");
        }

        @Test
        @DisplayName("★★★ 截断必须说破 —— 类目和品牌【都要】")
        void truncatedListIsMarkedAsTruncated() {
            UserAffinity many = affinity(
                    List.of(new UserAffinity.Count("A类目", 3),
                            new UserAffinity.Count("B类目", 3),
                            new UserAffinity.Count("C类目", 2),
                            new UserAffinity.Count("D类目", 1),
                            new UserAffinity.Count("E类目", 1)),
                    List.of(new UserAffinity.Count("甲牌", 2),
                            new UserAffinity.Count("乙牌", 1),
                            new UserAffinity.Count("丙牌", 1),
                            new UserAffinity.Count("丁牌", 1),
                            new UserAffinity.Count("戊牌", 1),
                            new UserAffinity.Count("己牌", 1)),
                    null, null, null, 3);

            String text = RagPromptBuilder.affinitySection(many);

            assertThat(text)
                    .as("★★ 一共几个类目【永远确切】（数据层没截断），"
                            + "截断的只是后面那个枚举 —— 而它必须被标记成样本，"
                            + "否则读的人会以为一共就这三个（ADR-094 那条纪律）")
                    .contains("涉及 5 个类目：")
                    .contains("A类目×3、B类目×3、C类目×2（其余略）")
                    .doesNotContain("D类目");
            assertThat(text)
                    .as("★★★ 品牌这一行【实测漏过一次】（2026-09-25 活体验收：user 8 有 6 个品牌，"
                            + "正文里只出现 5 个，而读起来像「就这五个」）。"
                            + "单测抓不到是因为当时的夹具只有 2 个品牌 —— "
                            + "所以这条用例的品牌列表必须是【超过上限】的")
                    .contains("买过的品牌：")
                    .contains("甲牌×2、乙牌×1、丙牌×1、丁牌×1、戊牌×1（其余略）")
                    .doesNotContain("己牌");
            // ★ 反面对照：没超上限时不该出现「（其余略）」——那会让人以为还有没列出来的
            assertThat(RagPromptBuilder.affinitySection(typical()))
                    .contains("涉及 3 个类目：")
                    .contains("买过的品牌：华为×2、联想×1")
                    .doesNotContain("其余略");
        }
    }

    // ============================================================
    // 二、拼进 prompt
    // ============================================================

    @Nested
    @DisplayName("二、拼进 prompt 的位置与条件")
    class Placement {

        @Test
        @DisplayName("★★ 没有偏好时，prompt 与不传它逐字节相同")
        void absentAffinityChangesNothing() {
            String withoutNewArg = builder.build(BASE, CHUNKS, true, "更早的摘要",
                    StructuredFacts.EMPTY);
            String withNull = builder.build(BASE, CHUNKS, true, "更早的摘要",
                    StructuredFacts.EMPTY, null);
            String withBlank = builder.build(BASE, CHUNKS, true, "更早的摘要",
                    StructuredFacts.EMPTY, "   ");

            assertThat(withNull)
                    .as("★★ 这一条是「没有偏好 = 与 9.4 逐字节相同」的凭据。"
                            + "不成立的话，9.5 之前所有关于 prompt 的结论都要重测")
                    .isEqualTo(withoutNewArg);
            assertThat(withBlank)
                    .as("★ 空白不算「有偏好」—— 空标题比不写更糟（类注释第三节）")
                    .isEqualTo(withoutNewArg);
        }

        @Test
        @DisplayName("★ 有偏好时它出现，且排在硬数据【之前】")
        void affinityComesBeforeFacts() {
            String affinitySection = RagPromptBuilder.affinitySection(typical());
            StructuredFacts facts = new StructuredFacts(List.of(
                    new StructuredFacts.PolicyTerm(null, "AS-001", 7, 15)));

            String prompt = builder.build(BASE, CHUNKS, false, null, facts, affinitySection);

            assertThat(prompt).contains(affinitySection).contains("【售后政策硬数据】");
            assertThat(prompt.indexOf("【这位用户的购买记录】"))
                    .as("★★ 判据是「谁更常在谁在前」：偏好几乎每轮都在，"
                            + "硬数据只有售后退换货那一类问题才有。反过来的话，"
                            + "有硬数据的那次会把偏好往后推，偏好那段缓存前缀全丢")
                    .isLessThan(prompt.indexOf("【售后政策硬数据】"));
        }

        @Test
        @DisplayName("★★ 检索为空时偏好段仍然在（early return 的对偶测试）")
        void affinitySurvivesEmptyChunks() {
            String affinitySection = RagPromptBuilder.affinitySection(typical());

            String prompt = builder.build(BASE, List.of(), false, null,
                    StructuredFacts.EMPTY, affinitySection);

            assertThat(prompt)
                    .as("★★ 它拼进的是 fixed 段，不是 chunks 那一段。"
                            + "拼错地方的话这个 bug 只在「检索失败 + 恰好这个人有偏好」时显形 —— "
                            + "而那正是最需要偏好兜底的时刻")
                    .contains("【这位用户的购买记录】")
                    .contains("本次未从平台知识库中检索到相关内容");
        }

        @Test
        @DisplayName("★★ 反面对照：剪掉偏好那一段之后，与没有它时逐字节相等")
        void onlyTheAffinityBlockDiffers() {
            StructuredFacts facts = new StructuredFacts(List.of(
                    new StructuredFacts.PolicyTerm(null, "AS-001", 7, 15)));
            String affinitySection = RagPromptBuilder.affinitySection(typical());

            String withOut = builder.build(BASE, CHUNKS, true, "摘要", facts, null);
            String withIn = builder.build(BASE, CHUNKS, true, "摘要", facts, affinitySection);

            assertThat(withIn).contains(affinitySection);
            assertThat(withIn.replace("\n\n" + affinitySection, ""))
                    .as("★★ 判据是「剪掉之后逐字节相等」，不是「它以某段开头」——"
                            + "插入点在【硬数据之前】，所以它不是任何东西的前缀。"
                            + "这一条同时钉住了三件事：注入的是一整块、"
                            + "其余部分一个字没动、以及「有偏好」和「没偏好」"
                            + "只差这一段（9.4 用的是同一条对偶判据）")
                    .isEqualTo(withOut);
        }
    }
}
