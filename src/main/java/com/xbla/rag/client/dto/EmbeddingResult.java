package com.xbla.rag.client.dto;

import java.util.List;

/**
 * 向量化结果 —— 领域对象。
 *
 * <p><b>为什么用 {@code float[]} 而不是 {@code List<Double>}？</b>
 * 线格式（{@link WireEmbedding.Response.EmbeddingData#embedding()}）用
 * {@code List<Double>} 是因为 Jackson 没法直接把 JSON 数组映射到基本类型数组。
 * 但在业务侧，向量最终要写进 pgvector 的 {@code vector(1024)} 列，
 * 而 JDBC 驱动接受的就是 {@code float[]}。在这里转一次，
 * 阶段 3 灌知识库时就不用反复拆装箱了。
 *
 * <p>另外 {@code float} 是 4 字节、{@code double} 是 8 字节。
 * 10 万条切片 × 1024 维，用 double 会多占 400MB 内存 ——
 * 对嵌入向量来说 float 的精度绰绰有余（模型输出的有效位数本来就在 7 位以内）。
 *
 * @param vectors      向量列表，顺序与输入文本一一对应
 * @param promptTokens 消耗的 token 数。可能为 null
 */
public record EmbeddingResult(

        List<float[]> vectors,

        Integer promptTokens

) {

    /**
     * 取第一条向量。
     *
     * <p>单条向量化场景下的便捷方法。返回的数组是<b>共享引用</b>，
     * 不要修改它的内容。
     */
    public float[] first() {
        return vectors == null || vectors.isEmpty() ? null : vectors.get(0);
    }

    /** 向量条数 */
    public int size() {
        return vectors == null ? 0 : vectors.size();
    }

    /** 维度。空结果时返回 0 */
    public int dimension() {
        float[] v = first();
        return v == null ? 0 : v.length;
    }
}
