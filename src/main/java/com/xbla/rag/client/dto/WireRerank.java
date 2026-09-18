package com.xbla.rag.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 重排序接口的请求与响应 —— 线格式。
 *
 * <p>对应 {@code POST /v1/rerank}，<b>只有硅基流动提供</b>。
 *
 * <p><b>实测报文</b>（2026-09-18，{@code BAAI/bge-reranker-v2-m3}）：
 * <pre>{@code
 * 请求： {"model":"BAAI/bge-reranker-v2-m3",
 *        "query":"refund",
 *        "documents":["seven day return","shipping time"]}
 *
 * 响应： {
 *   "id": "01a0b46dad3674f98ba1974bf6dbf812",
 *   "results": [
 *     { "index": 0, "document": null, "relevance_score": 0.24582946300506592 },
 *     { "index": 1, "document": null, "relevance_score": 0.000016740585124352947 }
 *   ],
 *   "meta": { "tokens": { "input_tokens": 15, "output_tokens": 0 } }
 * }
 * }</pre>
 *
 * <p><b>重排序是干什么的？</b>
 * 向量召回和关键词召回各自返回一批候选（比如各 20 条），
 * 融合去重后仍有 30+ 条。重排序模型会逐条计算
 * 「这条文档和问题的<b>相关程度</b>」，然后重新排序。
 *
 * <p>它比向量检索准得多，但<b>慢得多</b>——因为要对每个 (问题, 文档) 对
 * 做一次完整的模型前向计算。所以典型用法是
 * 「先用向量检索把 10 万条缩到 50 条，再用重排序精排到 5 条」。
 *
 * <p>实测得分很能说明问题：真正相关的「七天无理由退货」得 0.2458，
 * 而不相关的「发货时效」只有 0.0000167 —— 差了四个数量级。
 */
public final class WireRerank {

    private WireRerank() {
        // 纯容器类，不需要实例
    }

    /**
     * 重排序请求。
     */
    public record Request(

            String model,

            /** 用户的问题（原始问题或查询重写后的问题，阶段 4 会用到后者） */
            String query,

            /** 待排序的候选文档列表 */
            List<String> documents,

            /**
             * 只返回得分最高的前 N 条。
             *
             * <p>不传则返回全部（仍按得分降序）。
             * 实测硅基流动支持这个参数。
             */
            @JsonProperty("top_n") Integer topN,

            /**
             * 是否在结果里回带原文档内容。
             *
             * <p>默认 {@code false}，此时响应里的 {@code document} 字段是 null
             * （实测确认）。我们不需要它 —— 因为调用方手里本来就有原文，
             * 靠 {@code index} 回查即可，回带一份纯属浪费带宽。
             */
            @JsonProperty("return_documents") Boolean returnDocuments

    ) {
    }

    /**
     * 重排序响应。
     */
    public record Response(

            String id,

            /**
             * 排序结果，<b>已按 relevance_score 降序</b>。
             *
             * <p>★ 注意这里返回的是「下标 + 分数」，不是重新排列的文档列表。
             * 调用方要自己用 {@code index} 回原始 {@code documents} 列表取值 ——
             * 这样设计的好处是响应体小，且不会因为文档很长而白白传输一遍。
             */
            List<Result> results,

            /** 用量信息。字段名和 chat 接口不同，是 rerank 专有的 */
            Meta meta

    ) {

        /**
         * 单条排序结果。
         */
        public record Result(

                /** 对应请求里 {@code documents} 列表的下标 */
                Integer index,

                /** 原文档内容。只在请求带 {@code return_documents: true} 时才非 null */
                String document,

                /**
                 * 相关度得分，越大越相关。
                 *
                 * <p>★ <b>它不是 0~1 之间的概率</b>，而是一个无界的相关性分数
                 * （实测值 0.2458 和 0.0000167 都远小于 1）。
                 * 所以只能用来<b>排序</b>和<b>卡相对阈值</b>，
                 * 不要当成「相似度百分比」展示给用户。
                 */
                @JsonProperty("relevance_score") Double relevanceScore

        ) {
        }

        /** 用量信息 */
        public record Meta(Tokens tokens) {

            public record Tokens(
                    @JsonProperty("input_tokens") Integer inputTokens,
                    @JsonProperty("output_tokens") Integer outputTokens
            ) {
            }
        }
    }
}
