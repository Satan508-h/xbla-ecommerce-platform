package com.xbla.rag.client;

import com.xbla.rag.client.dto.RerankResult;

import java.util.List;

/**
 * 重排序客户端契约。
 *
 * <p>和 {@link EmbeddingClient} 一样，<b>只有硅基流动提供</b>，
 * 所以没有降级链。
 *
 * <h3>重排序在整条链路里的位置</h3>
 *
 * <pre>
 * 用户提问
 *   → 向量召回 20 条 + 关键词召回 20 条      （快，但不够准）
 *   → RRF 融合去重，得到约 30 条
 *   → ★ 重排序精排，取前 5 条                 （准，但慢）
 *   → 喂给大模型生成回答
 * </pre>
 *
 * <p>为什么不能直接用重排序、跳过向量召回？
 * 因为重排序要对<b>每一个 (问题, 文档) 对</b>做一次完整的模型前向计算。
 * 10 万条切片逐条算，一次问答要几分钟。所以它的定位是
 * 「在小候选集上做精排」，而向量检索负责「从海量里快速粗筛」。
 *
 * @see SiliconFlowRerankClient
 */
public interface RerankClient {

    /**
     * 对候选文档按与查询的相关度重新排序。
     *
     * @param query     用户问题（阶段 4 之后可能是查询重写后的问题）
     * @param documents 候选文档文本列表
     * @param topN      只返回前 N 条；传 null 返回全部
     * @return 按相关度降序的结果，元素是「原始下标 + 得分」
     * @throws ModelCallException 调用失败
     */
    RerankResult rerank(String query, List<String> documents, Integer topN);

    /** 返回全部结果的便捷重载 */
    default RerankResult rerank(String query, List<String> documents) {
        return rerank(query, documents, null);
    }
}
