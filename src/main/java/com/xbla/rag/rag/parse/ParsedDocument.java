package com.xbla.rag.rag.parse;

import java.util.List;

/**
 * 一份文档解析后的完整产物。
 *
 * @param blocks   带层级的文本块序列，<b>保持原文顺序</b>。
 *                 这个顺序是后面切分和 {@code chunk_index} 的依据，不能乱。
 * @param title    文档标题。取的是 Tika 从文件元数据里读出来的 title
 *                 （如 Word 的 core properties / PDF 的 docInfo），
 *                 <b>读不到就是 null</b> —— 不用正文首行去猜，
 *                 猜错了会把一个普通段落永久地当成文档标题，比 null 更糟。
 * @param mimeType Tika 识别出的真实 MIME 类型，如 {@code application/pdf}。
 *                 注意是<b>按文件内容识别</b>的，不是按扩展名 ——
 *                 一个改名为 .pdf 的 Word 文件也能被正确识别。
 * @param charCount 正文总字符数（所有块文本长度之和）。
 *                  用于入库前的体量校验和日志，不参与业务逻辑。
 */
public record ParsedDocument(List<TextBlock> blocks, String title, String mimeType, int charCount) {

    public ParsedDocument {
        blocks = List.copyOf(blocks);   // 防御性拷贝，保证不可变
    }

    /** 解析结果里一个字都没有 */
    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /** 标题块的数量，用于日志和入库质量检查 */
    public long headingCount() {
        return blocks.stream().filter(TextBlock::isHeading).count();
    }
}
