package com.xbla.rag.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 向量化接口的请求与响应 —— 线格式。
 *
 * <p>对应 {@code POST /v1/embeddings}，<b>只有硅基流动提供</b>
 * （DeepSeek 官方没有向量化能力，这是双供应商架构的根本原因）。
 *
 * <p><b>实测报文</b>（2026-09-18，{@code BAAI/bge-m3}）：
 * <pre>{@code
 * 请求： {"model":"BAAI/bge-m3","input":"商品支持七天无理由退货吗"}
 *
 * 响应： {
 *   "object": "list",
 *   "data": [ { "object":"embedding", "index":0, "embedding":[-0.0267, -0.0009, ...] } ],
 *   "model": "BAAI/bge-m3",
 *   "usage": { "prompt_tokens": 12, "completion_tokens": 0, "total_tokens": 12 }
 * }
 * }</pre>
 *
 * <p>实测确认 {@code embedding} 数组长度<b>恰好是 1024</b>，
 * 与建表时的 {@code kb_chunk.embedding vector(1024)} 完全一致。
 */
public final class WireEmbedding {

    private WireEmbedding() {
        // 纯容器类，不需要实例
    }

    /**
     * 向量化请求。
     *
     * <p>{@code input} 既可以传单个字符串，也可以传字符串数组。
     * 这里统一声明成 {@code List<String>}，批量场景（阶段 3 灌知识库）
     * 一次能发几十条，比逐条请求快得多，也省往返开销。
     * 单条时构造一个只有一项的 List 即可，不要为两种形态写两套 DTO。
     */
    public record Request(

            String model,

            /** 待向量化的文本。批量时按顺序返回，靠 {@code index} 对齐 */
            List<String> input

    ) {
    }

    /**
     * 向量化响应。
     */
    public record Response(

            /** 结果列表，顺序不保证与输入一致 —— ★ 必须靠每项的 {@code index} 对齐 */
            List<EmbeddingData> data,

            String model,

            Usage usage

    ) {

        /**
         * 单条向量化结果。
         */
        public record EmbeddingData(

                /**
                 * 对应输入列表中的下标。
                 *
                 * <p>★ 不要假设 {@code data} 的顺序等于输入顺序。批量接口
                 * 在服务端并发处理时顺序可能变化，靠下标对齐才是稳的。
                 */
                Integer index,

                /**
                 * 向量本体。
                 *
                 * <p>用 {@link List}{@code <Double>} 而不是 {@code float[]}：
                 * Jackson 无法直接把 JSON 数组映射到 Java 基本类型数组，
                 * 需要自定义反序列化器。
                 * 换 List 后由转换代码统一处理成 {@code float[]}
                 * （pgvector 写入用 float[]，见阶段 3）。
                 */
                List<Double> embedding

        ) {
        }

        /** 向量化也有 token 消耗，虽然通常很便宜 */
        public record Usage(
                @JsonProperty("prompt_tokens") Integer promptTokens,
                @JsonProperty("total_tokens") Integer totalTokens
        ) {
        }
    }
}
