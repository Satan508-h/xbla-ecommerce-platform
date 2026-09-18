package com.xbla.rag.rag.retrieve;

import com.xbla.rag.client.EmbeddingClient;
import com.xbla.rag.client.dto.EmbeddingResult;
import com.xbla.rag.common.handler.VectorTypeHandler;
import com.xbla.rag.mapper.KbChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 最小可用的向量检索。
 *
 * <p><b>这个类在阶段 3 的定位</b>：只为了验证「向量确实写进去了、而且能按语义查出来」。
 * 阶段 4 的 {@code VectorRetriever} 会在它之上叠加意图过滤、
 * 多路召回、RRF 融合、重排序 —— <b>这个类会被保留为「纯向量那一路」的底座</b>，
 * 而不是被替换掉。
 *
 * <h2>★ 为什么相似度不能当阈值用</h2>
 *
 * <p>阶段 2 实测过一个反直觉的现象：bge-m3 在中文上的余弦相似度
 * <b>区间被压缩在 0.5~0.7 之间</b>，相关文档和不相关文档的分布<b>互相重叠</b>。
 *
 * <p>也就是说「相似度 > 0.6 就算相关」这种写法<b>在本项目里是错的</b>，
 * 它会把无关内容一起捞进来。正确的做法是<b>相对 Top-K 排序</b>：
 * 不管绝对值多少，永远取距离最近的 K 个。
 *
 * <p>这也正是重排序模型存在的理由 —— 向量负责「粗筛出一批候选」，
 * 重排负责「在这批候选里精确排序」。两个模型分工不同，
 * 不能指望向量一个模型把两件事都做好。
 *
 * <h2>★ 关于结果稳定性（验收标准 3）—— 一个被实测推翻的假设</h2>
 *
 * <p>「同一个问题检索两次，结果稳定一致」看起来是句废话，但拆开看有<b>两个</b>
 * 独立的环节，而且<b>其中一个并不成立</b>：
 *
 * <p><b>环节一：向量化是确定的吗？—— 实测答案：不是。</b>
 *
 * <p>最初的代码注释里写的是「同样的文本调两次 embedding 接口，返回的向量
 * 逐位相同，这一点由模型服务保证」。<b>这个假设是错的</b>，实测数据如下：
 *
 * <pre>
 *   同一个问题连续调用 6 次 /v1/embeddings，比较返回向量的前 5 个分量：
 *     第 1 次: [-0.034807, 0.014949, -0.012569, 0.00099,  -0.029749]
 *     第 2~6 次: [-0.034728, 0.014979, -0.012669, 0.000731, -0.02966 ]
 *   → 6 次调用出现了 2 种不同结果
 * </pre>
 *
 * <p>差异量级约 {@code 3e-4}（相对值 0.4% 左右），很可能是服务端
 * 浮点归约顺序、批处理分组或算子实现差异造成的。<b>这不是 bug，
 * 是 GPU 推理的固有性质</b> —— 分布式/并行计算里浮点加法不满足结合律。
 *
 * <p><b>环节二：排序是确定的吗？—— 靠代码保证，可以做到。</b>
 * HNSW 是近似索引，距离相同时返回顺序不保证，靠 SQL 里的
 * {@code ORDER BY distance, id} 兜底键解决。
 *
 * <p><b>那么「结果稳定一致」这条验收标准到底成不成立？</b>
 * <b>成立，但要理解它成立在哪个层面上</b>：
 * <ul>
 *   <li>✅ <b>命中的切片 ID 序列完全一致</b>（实测 5 次逐位相同）——
 *       这才是用户能感知到的东西，也是这条验收标准真正的含义</li>
 *   <li>✅ <b>分值稳定到小数点后 3 位</b>（同样的 5 次，最大漂移 3e-4）</li>
 *   <li>❌ <b>分值不会逐位相同</b>，别指望用 {@code equals} 比较两次检索的分数</li>
 * </ul>
 *
 * <p><b>什么时候这个漂移会真的咬人</b>：当两个切片的相似度差距小于 3e-4 时，
 * 它们的相对顺序可能在不同请求间翻转。本项目的实际数据里相邻名次的差距
 * 在 0.002 量级（是漂移的 6 倍以上），所以顺序稳定。
 * 但<b>如果将来切片数量级上来、语义高度集中，这个前提就不成立了</b> ——
 * 那时要靠固定随机种子或结果缓存来保证可复现性。
 *
 * <p>这条发现的实用价值在于：<b>别把「检索结果不稳定」当成 bug 去查</b>。
 * 先看 ID 序列是否一致 —— 一致就说明链路是好的，分数抖动属于正常范围。
 */
@Component
public class VectorSearcher {

    private static final Logger log = LoggerFactory.getLogger(VectorSearcher.class);

    /** 默认返回条数。阶段 7 会拿它做 A/B 调参 */
    public static final int DEFAULT_TOP_K = 5;

    /** 单次检索允许的最大 topK，防止有人传 1000000 把库拖垮 */
    private static final int MAX_TOP_K = 100;

    private final EmbeddingClient embeddingClient;
    private final KbChunkMapper chunkMapper;

    public VectorSearcher(EmbeddingClient embeddingClient, KbChunkMapper chunkMapper) {
        this.embeddingClient = embeddingClient;
        this.chunkMapper = chunkMapper;
    }

    /**
     * 用一句自然语言查询最相关的切片。
     *
     * @param query 用户问题
     * @param topK  返回条数，{@code null} 或非正数时用 {@link #DEFAULT_TOP_K}
     */
    public List<VectorHit> search(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return searchByVector(embedToLiteral(query), topK);
    }

    /**
     * 直接用向量检索。<b>复用已算好的向量时走这个重载</b> ——
     * 比如阶段 4 的多路召回里，一次查询重写会产生多个子问题，
     * 每个子问题的向量都已经算过了，不该再调一次接口。
     */
    public List<VectorHit> searchByVector(String vectorLiteral, Integer topK) {
        int k = normalizeTopK(topK);
        long start = System.nanoTime();
        List<VectorHit> hits = chunkMapper.searchByVector(vectorLiteral, k);
        log.debug("向量检索 topK={} 命中={} 耗时={}ms",
                k, hits.size(), (System.nanoTime() - start) / 1_000_000);
        return hits;
    }

    /** 把查询文本向量化，并转成 pgvector 的文本格式 */
    public String embedToLiteral(String query) {
        EmbeddingResult result = embeddingClient.embed(query);
        if (result.size() != 1) {
            // 单条查询必须恰好返回一条向量。不等说明接口行为异常，
            // 而这时候静默取第一条会把「A 问题的向量」用来查「B 问题」——
            // 检索结果完全错乱且不报错
            throw new IllegalStateException(
                    "单条查询向量化返回了 " + result.size() + " 条向量，期望 1 条");
        }
        return VectorTypeHandler.toLiteral(result.first());
    }

    private static int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }
}
