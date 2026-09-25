package com.xbla.rag.mcp.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打分规则的<b>地基</b>：什么叫「字面重合」——
 * {@link RecommendProductsTool#longestCommonSubstring}（阶段 9.3）。
 *
 * <p><b>不花一分钱</b>：纯函数，没有 Spring、没有数据库。
 *
 * <h2>★ 为什么值得单独一个类</h2>
 *
 * <p>「哪个商品更适合」是<b>用户唯一在意的那件事</b>，而本项目的答案全部建立在这一个函数上。
 * 它错了的话，上面所有的排序都在错 —— 而且<b>错得非常安静</b>：
 * 顺序看起来永远合理（毕竟是按某种重合度排的），
 * 只是排的不是用户说的那件事。所以这里把它单独拎出来测，而不是塞进集成测试里顺带跑。
 *
 * <h2>★★ 那条最要紧的界线：连续，不是「字都在」</h2>
 *
 * <pre>
 *   「送长辈」 vs 「长送辈」   →  只算 1 个字（"送"）
 *   按【字】拆开看的话这里会命中 3 个字，于是 200 个商品全能得满分，
 *   排序退化成「按商品编号」—— 而正文会说「按字面重合度排序」，两句话都是真的，
 *   合起来是假的
 * </pre>
 *
 * <p>所以「子串」这个选择是刻意的：它要求那几个字<b>连在一起出现过</b>。
 */
@DisplayName("RecommendProductsTool · 字面重合度（纯函数）")
class RecommendProductsToolScoreTest {

    @Nested
    @DisplayName("一、命中的定义")
    class Definition {

        @Test
        @DisplayName("整段都在 → 返回那一段")
        void findsTheWholeRun() {
            assertThat(RecommendProductsTool.longestCommonSubstring("送长辈", "适合送长辈、商务人士"))
                    .isEqualTo("送长辈");
        }

        @Test
        @DisplayName("★★ 反面对照：字都在、但【不连着】→ 只算 1 个字")
        void nonContiguousCharsDoNotCount() {
            String hit = RecommendProductsTool.longestCommonSubstring("送长辈", "长送辈");

            assertThat(hit)
                    .as("★★ 这一条是上面那条的反面：它证明判据是【连续子串】，"
                            + "不是「这些字出现过没有」")
                    .hasSize(1);
            assertThat(hit).isEqualTo("送");
            // ★ 而 1 个字 < MIN_HIT_CHARS（2）→ 在工具里【不算命中】。
            //   阈值那一段的理由见 RecommendProductsTool.MIN_HIT_CHARS：
            //   单个汉字的偶然重合太多，1 字命中会让几乎所有商品都得一分
            assertThat(hit.length()).isLessThan(2);
        }

        @Test
        @DisplayName("★ 取【最长】的那一段，不是最先碰到的那一段")
        void takesTheLongestRun() {
            // 公共子串有 ab / ba / aba / bab 四种，最长是 3
            assertThat(RecommendProductsTool.longestCommonSubstring("abab", "baba"))
                    .as("按顺序贪心地取前两个字符的话，这里会得到 2")
                    .hasSize(3);
        }

        @Test
        @DisplayName("一个公共字符都没有 → 空串（不是 null）")
        void noOverlapGivesEmpty() {
            assertThat(RecommendProductsTool.longestCommonSubstring("甲乙丙", "丁戊己")).isEmpty();
        }
    }

    @Nested
    @DisplayName("二、脏输入不许抛")
    class DirtyInput {

        @Test
        @DisplayName("★ null / 空串 → 空串。★ 商品那三个字段本来就可为 null")
        void nullAndEmptyAreSafe() {
            assertThat(RecommendProductsTool.longestCommonSubstring(null, "送长辈")).isEmpty();
            assertThat(RecommendProductsTool.longestCommonSubstring("送长辈", null)).isEmpty();
            assertThat(RecommendProductsTool.longestCommonSubstring("", "送长辈")).isEmpty();
            assertThat(RecommendProductsTool.longestCommonSubstring("送长辈", "")).isEmpty();
        }

        @Test
        @DisplayName("★ 要求中的 need 是空串 → 同样空串（0 分，于是走「没匹配上」那条路）")
        void emptyNeedScoresNothing() {
            assertThat(RecommendProductsTool.longestCommonSubstring("", "适合送长辈")).isEmpty();
        }
    }
}
