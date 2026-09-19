package com.xbla.rag.rag.fuse;

import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）—— 把多路召回的结果合成一个排名。
 *
 * <h2>一、公式</h2>
 *
 * <pre>
 *   score(d) = Σ   weight_L / (k + rank_L(d))
 *             L∈路
 * </pre>
 *
 * <p>其中 {@code rank_L(d)} 是切片 d 在第 L 路里的名次（<b>从 1 开始</b>），
 * {@code k} 是常数（默认 60，RRF 原论文的推荐值）。
 *
 * <h2>二、★ 为什么是 RRF，不是加权求和</h2>
 *
 * <p>最直觉的做法是「把两路的分数归一化后加权相加」。<b>在本项目里这是错的</b>，
 * 因为两路的分值<b>量纲完全不同、而且不可比</b>：
 *
 * <pre>
 *   向量路：余弦相似度     实测区间 0.5 ~ 0.7（bge-m3 在中文上的分布被压缩得很窄）
 *   关键词路：ts_rank      实测区间 0.01 ~ 0.03
 * </pre>
 *
 * <p>差了两个数量级。要加权求和就得先归一化，而归一化需要知道分数的分布 ——
 * 这个分布<b>随语料变化</b>，今天调好的归一化参数明天就不对了。
 * 而且向量相似度的绝对值<b>根本不能当阈值用</b>（相关和不相关的分布互相重叠，
 * 详见 {@code VectorSearcher} 的注释），归一化的分母本身就是不可靠的。
 *
 * <p>RRF <b>只用名次，完全丢弃分数</b>，一举绕开上面所有问题。
 * 代价是丢掉了「第一名比第二名领先多少」这个信息 ——
 * 但那点信息本来就被分数分布的噪声淹没了。
 *
 * <h2>三、★ k=60 在做什么</h2>
 *
 * <p>{@code k} 的作用是<b>削弱名次差异</b>：
 *
 * <pre>
 *   k=60 时：第 1 名得 1/61 = 0.01639    第 10 名得 1/70 = 0.01429    只差 13%
 *   k=0  时：第 1 名得 1/1  = 1.0        第 10 名得 1/10 = 0.1        差 10 倍
 * </pre>
 *
 * <p>这让「在某一路排第 1」不至于压倒「在两路都排第 5」。
 * 换句话说，<b>RRF 奖励的是「被多路共同认可」，而不是单路的极端高分</b> ——
 * 这正是双路召回想要的：两路都认为相关的切片，比只有一路力挺的更可信。
 *
 * <h2>四、纯计算，不依赖 Spring 容器</h2>
 *
 * <p>标了 {@code @Component} 以便注入，但类里<b>没有任何注入点</b>，
 * 所有参数都是方法参数，单测可以直接 {@code new RrfFuser()}。
 * （和 {@code HeadingAwareChunker} 同一个路数。）
 *
 * @see <a href="https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf">
 *      Cormack, Clarke &amp; Buettcher (2009), Reciprocal Rank Fusion</a>
 */
@Component
public class RrfFuser {

    /** RRF 原论文推荐的 k 值。配置里的默认值也是它 */
    public static final int DEFAULT_K = 60;

    /**
     * 一路召回的输入。
     *
     * @param name  路名（{@code "vector"} / {@code "keyword"}），只用于日志和排查
     * @param weight 这一路的权重。1.0 表示等权
     * @param hits  这一路的召回结果，<b>必须已按相关度降序</b> ——
     *              RRF 只看名次，如果输入没排序，名次就是错的，
     *              而结果会<b>看起来完全正常</b>
     */
    public record Leg(String name, double weight, List<RetrievedChunk> hits) {

        public Leg {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }

        public static Leg of(String name, List<RetrievedChunk> hits) {
            return new Leg(name, 1.0, hits);
        }
    }

    /**
     * 融合多路召回结果。
     *
     * @param legs 各路结果，顺序不影响输出（每路的权重由 {@link Leg#weight()} 带）
     * @param k    公式里的常数。小于等于 0 时用 {@link #DEFAULT_K}
     * @return 按 RRF 得分降序的切片列表。所有路都是空时返回空列表
     */
    public List<RetrievedChunk> fuse(List<Leg> legs, int k) {
        int kk = k > 0 ? k : DEFAULT_K;

        // 按切片 id 归并。用 HashMap 而不是 LinkedHashMap —— 可以，
        // 因为下面排序时带了 id 兜底键，输出顺序与 map 的迭代顺序无关。
        // 用 LinkedHashMap 只能「看起来更确定」，实际是把确定性寄托在
        // 一个隐式的插入顺序上，反而掩盖了兜底键的作用
        Map<Long, Accumulator> merged = new HashMap<>();

        for (Leg leg : legs) {
            List<RetrievedChunk> hits = leg.hits();
            for (int i = 0; i < hits.size(); i++) {
                RetrievedChunk hit = hits.get(i);
                int rank = i + 1;                       // ★ 名次从 1 开始，不是 0
                double contribution = leg.weight() / (kk + rank);

                merged.computeIfAbsent(hit.id(), id -> new Accumulator(hit))
                        .add(contribution);
            }
        }

        List<RetrievedChunk> result = new ArrayList<>(merged.size());
        for (Accumulator acc : merged.values()) {
            result.add(acc.toChunk());
        }

        // ★ 排序必须带 id 兜底键。
        //   两路都召回同一条切片时，多路贡献会累加，得分一般不同；
        //   但权重为 0 的路、或者两路恰好给出对称名次时，得分可能完全相等。
        //   少了兜底键，同分切片的相对顺序由排序算法自由决定 ——
        //   现象是「同一个问题两次检索顺序不一样」。这个坑在
        //   KbChunkMapper 的两条检索 SQL 里已经处理过，这里是第三处。
        result.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                .thenComparing(RetrievedChunk::id));

        return result;
    }

    /** 便捷重载：等权、默认 k */
    public List<RetrievedChunk> fuse(List<RetrievedChunk> vectorHits, List<RetrievedChunk> keywordHits) {
        return fuse(List.of(Leg.of("vector", vectorHits), Leg.of("keyword", keywordHits)), DEFAULT_K);
    }

    /**
     * 归并过程中的累加器。
     *
     * <p>保留「第一次见到这条切片时」的元数据（正文、标题路径等）——
     * 同一条切片在两路里的这些字段必然相同（它们来自同一行），
     * 所以取哪一路的都一样。
     *
     * <p>只累加得分，<b>不记录「这条来自哪几路」</b>：
     * 每一路的原始结果在 {@code RetrievalTrace} 里本来就是分开的列表，
     * 做归因时查那两个列表即可。在这里再存一份就成了会与列表不一致的冗余状态。
     */
    private static final class Accumulator {

        private final RetrievedChunk firstSeen;
        private double score;

        Accumulator(RetrievedChunk firstSeen) {
            this.firstSeen = firstSeen;
        }

        void add(double contribution) {
            this.score += contribution;
        }

        RetrievedChunk toChunk() {
            return firstSeen.withScore(score);
        }
    }
}
