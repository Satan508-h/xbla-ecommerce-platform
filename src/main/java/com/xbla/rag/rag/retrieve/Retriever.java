package com.xbla.rag.rag.retrieve;

import java.util.List;

/**
 * 一路召回。返回<b>按相关度降序</b>的候选切片。
 *
 * <h2>为什么定义成接口</h2>
 *
 * <p>两个理由：
 *
 * <ol>
 *   <li><b>双路召回的结构需要它</b>。向量路和关键词路的实现完全不同，
 *       但编排层要对它们一视同仁（并行调用、各自的原始结果分别记进 trace、
 *       一起丢给 RRF 融合）。</li>
 *   <li><b>阶段 7 的 A/B 对比需要它</b>。基线报告要能回答
 *       「只用向量路是什么水平」「只用关键词路是什么水平」「两路融合提升多少」——
 *       这是证明 RRF 有价值的最直接证据。换实现类或调整组合就能对比。</li>
 * </ol>
 *
 * <h2>★ 实现类必须遵守的两条约定</h2>
 *
 * <ol>
 *   <li><b>排序必须是全序的</b>。同一批数据、同一个查询，两次调用必须返回
 *       <b>逐位相同</b>的顺序。两个实现都靠 SQL 里的 {@code ORDER BY score DESC, id}
 *       兜底 —— 少了那个 {@code , id}，分值并列时顺序由 PostgreSQL 自由决定，
 *       现象是「同一问题两次检索结果不一致」，看起来像 bug 其实只是排序不唯一。
 *       （详见 {@code VectorSearcher} 类注释里那段被实测推翻的稳定性假设。）</li>
 *   <li><b>失败要抛出，不要返回空列表</b>。空列表是「查了，但没有匹配」，
 *       异常是「没查成」—— 这两件事在编排层的处理完全不同
 *       （一个照常融合，一个要降级到另一路）。用返回值表达失败会把它们混在一起。</li>
 * </ol>
 */
public interface Retriever {

    /**
     * 本路的名字，用于日志、trace 和 {@code retrieval_detail} 的分组。
     *
     * <p>取值：{@code "vector"} / {@code "keyword"}。
     */
    String name();

    /**
     * 召回。
     *
     * @param query 查询文本。<b>原样传入，各实现自己决定要不要分词</b> ——
     *              向量路直接把原文送去向量化，关键词路先分词再拼 tsquery。
     *              在编排层统一预处理反而会让两路拿到不该拿的东西
     * @param topK  最多返回条数
     * @return 按相关度降序的候选。没有匹配时返回空列表，<b>不返回 null</b>
     * @throws RuntimeException 调用失败（向量化接口挂了 / 数据库出错）
     */
    List<RetrievedChunk> retrieve(String query, int topK);
}
