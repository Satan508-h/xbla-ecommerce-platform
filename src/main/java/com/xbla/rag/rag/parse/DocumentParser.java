package com.xbla.rag.rag.parse;

import java.io.InputStream;

/**
 * 文档解析器：把任意格式的文档字节流，变成统一的「带层级文本块序列」。
 *
 * <p>定义成接口而不是直接用具体类，是为了给阶段 7 的 A/B 对比留位置 ——
 * 如果将来要比较「Tika 解析」和「自研 PDF 解析」对检索质量的影响，
 * 换一个实现类就行，切分和入库的代码一行都不用动。
 *
 * <p><b>实现必须满足的两条约定</b>
 * <ol>
 *   <li><b>不能关闭传进来的 InputStream</b>。流的所有权属于调用方，
 *       由调用方用 try-with-resources 管理。实现类如果自己关掉了，
 *       调用方在外层再关一次会拿到「流已关闭」异常。</li>
 *   <li><b>不抛受检异常</b>。解析失败统一抛
 *       {@link DocumentParseException}（非受检），
 *       这样入库链路上的编排代码不用到处写 try-catch。</li>
 * </ol>
 */
public interface DocumentParser {

    /**
     * 解析一份文档。
     *
     * @param in       文档字节流。<b>本方法不负责关闭它</b>
     * @param fileName 原始文件名。某些解析器需要靠它辅助判断格式
     *                 （比如 Office 的 OLE2 格式和 ZIP 格式前缀相同，
     *                 单看字节流分不出来）
     * @return 解析产物，永不为 null
     * @throws DocumentParseException 解析失败（文件损坏、格式不支持、内容超限等）
     */
    ParsedDocument parse(InputStream in, String fileName);
}
