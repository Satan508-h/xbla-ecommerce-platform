package com.xbla.rag.rag.chunk;

import com.xbla.rag.rag.parse.TextBlock;

import java.util.List;

/**
 * 文本切分器：把解析出的「带层级文本块序列」切成一批可检索的片段。
 *
 * <p>和 {@code DocumentParser} 一样定义成接口，是为了给阶段 7 的 A/B 对比留位置 ——
 * 「定长切分」「按标题切分」「语义切分（用 LLM 判断边界）」的效果差异
 * 是本项目评测报告里最有说服力的一段，换实现类就能对比。
 */
public interface TextChunker {

    /**
     * 切分。
     *
     * @param blocks      解析层产出的文本块，<b>必须保持原文顺序</b>
     * @param options     切分参数
     * @param documentTitle 文档标题。会作为<b>标题路径的第一层</b>补进去。
     *                     传 null 表示这份文档没有标题，
     *                     此时路径完全由正文里的标题块决定。
     * @return 切片列表，{@code index} 从 0 连续编号。文档为空时返回空列表
     */
    List<TextChunk> chunk(List<TextBlock> blocks, TextChunkingOptions options, String documentTitle);

    default List<TextChunk> chunk(List<TextBlock> blocks, TextChunkingOptions options) {
        return chunk(blocks, options, null);
    }
}
