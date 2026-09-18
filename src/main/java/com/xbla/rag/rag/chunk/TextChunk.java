package com.xbla.rag.rag.chunk;

/**
 * 一个文本切片 —— 知识库的最小检索单位，最终会变成 {@code kb_chunk} 里的一行。
 *
 * @param index       在<b>整份文档</b>内的序号，从 0 开始。
 *                    对应 {@code kb_chunk.chunk_index}。
 *                    <b>必须全局连续</b>：阶段 7 做评测时可能会用「命中切片的前后各一片」
 *                    来补充上下文，序号断了这个功能就没法做。
 * @param content     切片正文。这一段文字会被送去向量化，也是最终塞进 prompt 的原文
 * @param headingPath 标题层级路径，如 {@code "售后政策 > 退货 > 七天无理由"}。
 *                    <b>没有标题时是 {@code null}</b>，不是空串 ——
 *                    「这份文档没有标题结构」和「标题路径是空的」是两件事，
 *                    用 null 表示前者，运维查询才能写 {@code WHERE heading_path IS NULL}
 *                    一眼筛出所有没识别出结构的文档。
 * @param charCount   正文长度。冗余存一份是因为算它比读 {@code content.length()}
 *                    更直观，而且阶段 7 要统计「切片长度分布」时会频繁用到
 */
public record TextChunk(int index, String content, String headingPath, int charCount) {

    public TextChunk {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("切片正文不能为空");
        }
    }

    public TextChunk(int index, String content) {
        this(index, content, null, content.length());
    }
}
