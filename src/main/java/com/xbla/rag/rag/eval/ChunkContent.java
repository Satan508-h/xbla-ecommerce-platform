package com.xbla.rag.rag.eval;

/**
 * 一条切片的正文 —— 只给评测用。
 *
 * <p>★ 为什么不直接复用 {@code KbChunk} 实体：那个实体带着 {@code embedding}
 * 字段（{@code vector(1024)}），MyBatis 会把它当成一个 1024 维的字符串读出来。
 * 评测只需要正文，让 130 行 × 1024 维的向量白白过一次网络没有意义。
 *
 * <p>★ 为什么不在 SQL 里就截断到 {@code MAX_CHARS_PER_CHUNK}：
 * 截断的规则属于 {@code RagPromptBuilder}（它决定了模型看到什么），
 * 把规则复制进 SQL 就是<b>第二个事实来源</b>。这里取全文，
 * 由调用方走 {@code RagPromptBuilder.truncate} —— 和 prompt 里逐字一致。
 *
 * @param chunkId {@code kb_chunk.id}
 * @param content 切片正文（<b>未截断</b>）
 */
public record ChunkContent(long chunkId, String content) {
}
