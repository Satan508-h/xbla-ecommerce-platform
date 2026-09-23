package com.xbla.rag.rag.eval;

/**
 * 一行「某个切片属于哪种文档类型」。
 *
 * <p>阶段 7 的<b>过度检索率</b>要回答的是这个：
 *
 * <pre>
 *   最终送进 prompt 的那 5 条上下文里，有几条压根不在
 *   「这道题的答案该待的地方」——也就是越界的噪声
 * </pre>
 *
 * <p>而「越界」这个词只有对着一个<b>期望的 doc_type 集合</b>才有意义。
 * 那个集合有两个可能的口径（见 {@code EvalReportService}）：
 * 标注叶子的、分类叶子的。无论用哪个，判据都是同一个：
 *
 * <pre>
 *   retrieval_detail.final_top_k 里的每一个 chunk_id
 *     → 到这张表里查它的 doc_type
 *     → 不在期望集合里就是越界
 * </pre>
 *
 * <p>★ <b>为什么不复用 {@code retrieval_detail} 里已有的分数</b>：
 * 那里只有 id 和分值，<b>没有 doc_type</b>。而 doc_type 是过滤的判据、
 * 也是过度检索的定义 —— 少了它，「检索准不准」和「检索偏不偏」这两件事
 * 就只能合成一个数。这正是 {@code docs/06} §1.4 说的那种：
 * <b>两个数字的差值本身携带信息，合成一个就把它丢了。</b>
 *
 * @param chunkId {@code kb_chunk.id}
 * @param docType {@code kb_chunk.doc_type}，取值 1~5
 *                （1商品详情 2售后政策 3促销规则 4FAQ 5说明书）
 */
public record ChunkDocType(long chunkId, int docType) {
}
