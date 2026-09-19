package com.xbla.rag.rag.tokenize;

/**
 * 重新分词时需要的一行数据：切片 ID + 正文。
 *
 * <h2>为什么不直接用 {@code KbChunk} 实体</h2>
 *
 * <p>{@code KbChunk} 有一个 1024 维的 {@code float[] embedding} 字段。
 * 用它做查询结果类型，即使 SQL 里只 {@code SELECT id, content}，
 * 代码读起来也像是「拿到了一整个切片」，下次有人改成
 * {@code selectList(...)} 就会把 1652 × 1024 × 4B ≈ <b>6.8MB</b> 的向量
 * 白白读进内存 —— 而这件事<b>不会报错，只会变慢</b>。
 *
 * <p>用一个只含两个字段的 record，这种误用<b>在类型层面就写不出来</b>。
 * 同样思路见 {@code KbChunkMapper.selectIdsByDocumentId} 只返回 {@code List<Long>}。
 *
 * @param id      切片主键
 * @param content 切片正文，重新分词的输入
 */
public record SearchTextSource(Long id, String content) {
}
