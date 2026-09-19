package com.xbla.rag.rag.fuse;

import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 融合的单元测试。
 *
 * <p>纯计算测试，不起 Spring，直接 {@code new RrfFuser()}。
 *
 * <p>本类里有两组 {@code // ★ 对照组}，它们证明正向断言<b>真的有区分力</b>：
 * 一组证明「只用名次」确实与「按原始分数相加」得到不同的排序；
 * 一组证明 k 值确实在削弱名次差异。
 */
@DisplayName("RrfFuser · 倒数排名融合")
class RrfFuserTest {

    private final RrfFuser fuser = new RrfFuser();

    private static final int K = 60;

    /** 造一条命中，只需要 id 和分数 */
    private static RetrievedChunk chunk(long id, double score) {
        return new RetrievedChunk(id, 1L, (int) id, "内容" + id, null, score);
    }

    private static List<Long> idsOf(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::id).toList();
    }

    // ============================================================
    // 一、基本公式
    // ============================================================

    @Nested
    @DisplayName("一、基本公式")
    class Formula {

        @Test
        @DisplayName("单路：第 1 名得 1/(k+1)，第 n 名得 1/(k+n)")
        void singleLegFormula() {
            List<RetrievedChunk> fused = fuser.fuse(
                    List.of(RrfFuser.Leg.of("vector", List.of(chunk(1, 0.9), chunk(2, 0.8)))),
                    K);

            assertThat(fused.get(0).score())
                    .as("名次从 1 开始 —— 从 0 开始的话这里会是 1/60 而不是 1/61")
                    .isCloseTo(1.0 / (K + 1), org.assertj.core.data.Offset.offset(1e-12));
            assertThat(fused.get(1).score())
                    .isCloseTo(1.0 / (K + 2), org.assertj.core.data.Offset.offset(1e-12));
        }

        @Test
        @DisplayName("★ 高分差被抹平：第 1 名得 0.9 和第 2 名得 0.1，融合后只差 13%")
        void scoreGapIsFlattened() {
            List<RetrievedChunk> fused = fuser.fuse(
                    List.of(RrfFuser.Leg.of("vector", List.of(chunk(1, 0.9), chunk(2, 0.1)))),
                    K);

            double top = fused.get(0).score();
            double second = fused.get(1).score();

            assertThat(second / top)
                    .as("原始分数差 9 倍，融合后只差约 13% —— RRF 完全丢弃了分数，只看名次")
                    .isGreaterThan(0.8);
        }

        @Test
        @DisplayName("两路都命中的切片得分累加，排在只被一路命中的前面")
        void multiLegConsensusWins() {
            List<RetrievedChunk> fused = fuser.fuse(List.of(
                    RrfFuser.Leg.of("vector", List.of(chunk(10, 0.7), chunk(20, 0.6))),
                    RrfFuser.Leg.of("keyword", List.of(chunk(10, 0.03), chunk(30, 0.02)))), K);

            assertThat(idsOf(fused))
                    .as("10 被两路都命中 → 得分是两路贡献之和，排第一")
                    .startsWith(10L);
            assertThat(fused.get(0).score())
                    .as("1/(61) + 1/(61)")
                    .isCloseTo(2.0 / 61, org.assertj.core.data.Offset.offset(1e-12));
        }

        @Test
        @DisplayName("权重生效：把某一路权重设为 0，它就完全不参与")
        void weightIsApplied() {
            List<RetrievedChunk> fused = fuser.fuse(List.of(
                    new RrfFuser.Leg("vector", 0.0, List.of(chunk(1, 0.9))),
                    RrfFuser.Leg.of("keyword", List.of(chunk(2, 0.02)))), K);

            assertThat(fused).as("权重 0 的那条仍有条目，但得分为 0").hasSize(2);
            assertThat(fused.get(0).id()).as("得分 0 排最后，1/(61) 排第一").isEqualTo(2L);
            assertThat(fused.get(1).score()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("空输入：全是空列表时返回空，不抛异常")
        void emptyInputs() {
            assertThat(fuser.fuse(List.of(), K)).isEmpty();
            assertThat(fuser.fuse(List.of(
                    RrfFuser.Leg.of("vector", List.of()),
                    RrfFuser.Leg.of("keyword", List.of())), K)).isEmpty();
            assertThat(fuser.fuse(List.of(
                    RrfFuser.Leg.of("vector", null),
                    RrfFuser.Leg.of("keyword", null)), K)).isEmpty();
        }

        @Test
        @DisplayName("k <= 0 时回落到默认值 60，不抛除零")
        void invalidKFallsBack() {
            List<RetrievedChunk> fused = fuser.fuse(
                    List.of(RrfFuser.Leg.of("vector", List.of(chunk(1, 0.9)))), 0);

            assertThat(fused.get(0).score())
                    .as("k=0 会让 1/(0+1)=1，名次差异被放大到 10 倍，不是想要的行为")
                    .isCloseTo(1.0 / (RrfFuser.DEFAULT_K + 1), org.assertj.core.data.Offset.offset(1e-12));
        }
    }

    // ============================================================
    // 二、★ 对照组：RRF 与「按原始分数相加」结果不同
    // ============================================================

    @Nested
    @DisplayName("二、★ 对照组：为什么不能用原始分数")
    class VersusScoreSum {

        @Test
        @DisplayName("★ 同一组输入，RRF 与「原始分数相加」给出【不同】的排序")
        void rrfDiffersFromRawScoreSum() {
            // 两路的分数量纲差两个数量级，这正是本项目的真实情况
            List<RetrievedChunk> vector = List.of(chunk(1, 0.65), chunk(2, 0.60));
            List<RetrievedChunk> keyword = List.of(chunk(3, 0.030), chunk(1, 0.020));

            List<Long> rrfOrder = idsOf(fuser.fuse(List.of(
                    RrfFuser.Leg.of("vector", vector),
                    RrfFuser.Leg.of("keyword", keyword)), K));

            List<Long> rawSumOrder = idsOf(rawScoreSum(vector, keyword));

            // 把原始分数直接相加：
            //   1 号 = 0.65 + 0.020 = 0.670
            //   2 号 = 0.60          = 0.600
            //   3 号 =        0.030  = 0.030
            //   → 顺序 [1, 2, 3]
            assertThat(rawSumOrder)
                    .as("对照组的排序")
                    .containsExactly(1L, 2L, 3L);

            // RRF：
            //   1 号 = 1/61 + 1/62 = 0.03252   （两路都命中）
            //   2 号 = 1/62        = 0.01613   （只有向量路，且排第 2）
            //   3 号 = 1/61        = 0.01639   （只有关键词路，但排第 1）
            //   → 顺序 [1, 3, 2]
            assertThat(rrfOrder)
                    .as("★ RRF 把 3 号提到了 2 号前面 —— 因为 3 号是关键词路的第 1 名，"
                            + "而 2 号只是向量路的第 2 名。RRF 只看名次，不看分数差多少。"
                            + "而原始分数相加会让向量的 0.6 把关键词的 0.03 彻底淹没，"
                            + "关键词这一路等于白做")
                    .containsExactly(1L, 3L, 2L);

            assertThat(rrfOrder)
                    .as("这两种排序必须不同，否则本对照组成立不了，"
                            + "上面那条 containsExactly 也就没有说服力")
                    .isNotEqualTo(rawSumOrder);
        }

        /** 对照组实现：把两路的原始分数直接相加（本项目【不这么做】，只为证明差异存在） */
        private static List<RetrievedChunk> rawScoreSum(List<RetrievedChunk>... legs) {
            java.util.Map<Long, RetrievedChunk> byId = new java.util.LinkedHashMap<>();
            java.util.Map<Long, Double> sums = new java.util.HashMap<>();
            for (List<RetrievedChunk> leg : legs) {
                for (RetrievedChunk c : leg) {
                    byId.putIfAbsent(c.id(), c);
                    sums.merge(c.id(), c.score(), Double::sum);
                }
            }
            List<RetrievedChunk> out = new ArrayList<>();
            for (RetrievedChunk c : byId.values()) {
                out.add(c.withScore(sums.get(c.id())));
            }
            out.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
            return out;
        }
    }

    // ============================================================
    // 三、★ k 值的作用
    // ============================================================

    @Nested
    @DisplayName("三、★ k 值在削弱名次差异")
    class KEffect {

        @Test
        @DisplayName("★ 对照组：k=1 时第 1 名碾压第 10 名；k=60 时两者几乎持平")
        void kFlattensRankGap() {
            // ⚠️ 这里用小 k 而不是 k=0 做对比：k=0 会被 RrfFuser 判定为非法值
            //    并回落到默认的 60（那条行为有单独的测试），所以拿 k=0 测不出极端情况
            List<RetrievedChunk> tenChunks = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                tenChunks.add(chunk(i, 1.0 - i * 0.01));
            }
            List<RrfFuser.Leg> legs = List.of(RrfFuser.Leg.of("vector", tenChunks));

            double ratioSmallK = ratioOf(legs, 1);
            double ratioK60 = ratioOf(legs, 60);

            assertThat(ratioSmallK)
                    .as("k=1：第 1 名得 1/2、第 10 名得 1/11 → 差 5.5 倍")
                    .isCloseTo(5.5, org.assertj.core.data.Offset.offset(0.01));
            assertThat(ratioK60)
                    .as("k=60：第 1 名得 1/61、第 10 名得 1/70 → 只差约 15%。"
                            + "这正是 RRF 想要的效果：让「在某一路排第 1」"
                            + "不至于压倒「在两路都排第 5」")
                    .isLessThan(1.2);
            assertThat(ratioK60)
                    .as("两者必须显著不同，否则这条对照没有区分力")
                    .isLessThan(ratioSmallK);
        }

        private static double ratioOf(List<RrfFuser.Leg> legs, int k) {
            List<RetrievedChunk> fused = new RrfFuser().fuse(legs, k);
            return fused.get(0).score() / fused.get(9).score();
        }
    }

    // ============================================================
    // 四、确定性
    // ============================================================

    @Nested
    @DisplayName("四、确定性")
    class Determinism {

        @Test
        @DisplayName("★ 得分并列时按 id 升序兜底，两次调用逐位一致")
        void tiesBreakById() {
            // 两路给出一条切片完全对称的名次 → 得分必然相等
            List<RetrievedChunk> vector = List.of(chunk(5, 0.9), chunk(7, 0.8));
            List<RetrievedChunk> keyword = List.of(chunk(7, 0.03), chunk(5, 0.02));

            List<RrfFuser.Leg> legs = List.of(
                    RrfFuser.Leg.of("vector", vector),
                    RrfFuser.Leg.of("keyword", keyword));

            List<Long> first = idsOf(fuser.fuse(legs, K));
            List<Long> second = idsOf(fuser.fuse(legs, K));

            // 5 号 = 1/61(向量第1) + 1/62(关键词第2)
            // 7 号 = 1/62(向量第2) + 1/61(关键词第1)  → 完全相同
            assertThat(fuser.fuse(legs, K).get(0).score())
                    .as("构造出来的确实是并列分")
                    .isEqualTo(fuser.fuse(legs, K).get(1).score());

            assertThat(first)
                    .as("★ 并列时按 id 升序 —— 少了兜底键，"
                            + "同分切片的顺序由排序算法自由决定，"
                            + "现象是「同一问题两次检索顺序不一样」")
                    .containsExactly(5L, 7L);
            assertThat(second).containsExactlyElementsOf(first);
        }

        @Test
        @DisplayName("输入路的先后顺序不影响输出")
        void legOrderDoesNotMatter() {
            List<RetrievedChunk> vector = List.of(chunk(1, 0.9), chunk(2, 0.8));
            List<RetrievedChunk> keyword = List.of(chunk(2, 0.03), chunk(3, 0.02));

            List<Long> a = idsOf(fuser.fuse(List.of(
                    RrfFuser.Leg.of("vector", vector), RrfFuser.Leg.of("keyword", keyword)), K));
            List<Long> b = idsOf(fuser.fuse(List.of(
                    RrfFuser.Leg.of("keyword", keyword), RrfFuser.Leg.of("vector", vector)), K));

            assertThat(a).isEqualTo(b);
        }
    }
}
