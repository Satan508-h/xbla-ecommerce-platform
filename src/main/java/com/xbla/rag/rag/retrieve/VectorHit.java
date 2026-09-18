package com.xbla.rag.rag.retrieve;

/**
 * 一次向量召回命中的切片。
 *
 * @param id          切片 ID（{@code kb_chunk.id}）
 * @param documentId  所属文档 ID
 * @param chunkIndex  在文档内的序号。阶段 7 可以靠它取「命中切片的相邻切片」
 *                    来补充上下文
 * @param content     切片正文
 * @param headingPath 标题层级路径，可为 null
 * @param score       <b>余弦相似度</b>，取值 -1 ~ 1，越大越相似。
 *
 *                    <p>⚠️ 注意这里是<b>相似度</b>不是<b>距离</b>。
 *                    pgvector 的 {@code <=>} 算子返回的是余弦<b>距离</b>
 *                    （0 表示完全一致、2 表示完全相反），
 *                    代码里做了 {@code 1 - distance} 的转换。
 *                    两者关系：{@code 相似度 = 1 - 距离}。
 *
 *                    <p><b>为什么统一转成相似度</b>：因为人对「0.87 很相关」
 *                    有直觉，对「距离 0.13 很相关」没有。
 *                    而且阶段 4 的 RRF 融合、阶段 7 的阈值调参都要拿它做比较 ——
 *                    距离是「越小越好」、相似度是「越大越好」，
 *                    混用迟早会把排序条件写反。
 *
 *                    <p>⚠️ 这个绝对值<b>不能当阈值用</b>：实测 bge-m3 在中文上
 *                    的相似度区间被压缩在 0.5~0.7 之间，相关和不相关的文档
 *                    会重叠。阶段 4 必须用相对 Top-K 排序，不能卡绝对阈值。
 *                    详见 docs/10 阶段 2 验收记录。
 */
public record VectorHit(
        Long id,
        Long documentId,
        Integer chunkIndex,
        String content,
        String headingPath,
        double score) {
}
