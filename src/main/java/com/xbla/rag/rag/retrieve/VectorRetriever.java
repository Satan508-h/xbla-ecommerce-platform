package com.xbla.rag.rag.retrieve;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量召回这一路。
 *
 * <p><b>本类只做一次类型转换，不含任何检索逻辑</b> ——
 * 真正的活是 {@link VectorSearcher} 干的（阶段 3 写的），
 * SQL 在 {@code KbChunkMapper.searchByVector} 里。
 *
 * <h2>为什么不直接把 {@code VectorSearcher} 当 {@code Retriever} 用</h2>
 *
 * <p>技术上可以让 {@code VectorSearcher} 直接 {@code implements Retriever}
 * （方法签名几乎一样，只要换个返回类型）。没有这么做的原因：
 *
 * <ul>
 *   <li>{@code VectorSearcher} 是<b>阶段 3 的契约</b>。
 *       {@code KbProbeController} 的 {@code /search} 和 {@code /stability} 两个接口
 *       直接依赖它的 {@code List<VectorHit>} 返回值，
 *       而 {@code VectorHit} 的注释里带着「余弦相似度不能当阈值用」
 *       和「结果稳定性成立在 ID 序列层面」这两条<b>实测结论</b>。
 *       改它的签名会把这些结论的落点一起挪走。</li>
 *   <li>{@code VectorSearcher} 还多暴露了 {@code searchByVector} 和
 *       {@code embedToLiteral} 两个方法，它们是「复用已算好的向量」
 *       和「调试时看查询向量维度」用的，属于检索链路之外的关注点。</li>
 * </ul>
 *
 * <p>所以这里做的是<b>适配</b>而不是改造：一条 3 行的映射，
 * 换来阶段 3 的代码和测试一行不动。
 *
 * <h2>★ 这一路的固有弱点</h2>
 *
 * <p>向量检索擅长语义泛化（「送长辈」能匹配到「适合老年人」），
 * 但对<b>字面精确的东西不敏感</b>：型号 {@code 星辰X1}、订单号、SKU 编码
 * 这类「符号」在向量空间里没有语义，可能和别的内容挤在一起。
 *
 * <p>所以需要关键词路做互补 —— 这也是双路召回存在的根本原因，
 * 而不是「多一路总比少一路好」。
 */
@Component
public class VectorRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(VectorRetriever.class);

    /** 本路在日志和 trace 里的名字 */
    public static final String NAME = "vector";

    private final VectorSearcher vectorSearcher;

    public VectorRetriever(VectorSearcher vectorSearcher) {
        this.vectorSearcher = vectorSearcher;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, RetrievalOptions options) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // ★ docTypes 直接透传给 SQL，不在 Java 里后置筛 —— 理由见 Retriever 的类注释：
        //   4.1% 的选择率下，「先取 20 条再筛」平均只剩不到 1 条
        List<RetrievedChunk> hits = vectorSearcher
                .search(query, options.topK(), options.docTypesLiteral())
                .stream()
                .map(RetrievedChunk::from)
                .toList();
        log.debug("向量召回 topK={} docTypes={} 命中={}",
                options.topK(), options.docTypesLiteral(), hits.size());
        return hits;
    }
}
