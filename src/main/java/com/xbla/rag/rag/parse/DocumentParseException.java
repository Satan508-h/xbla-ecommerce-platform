package com.xbla.rag.rag.parse;

/**
 * 文档解析失败。
 *
 * <p>继承 {@link RuntimeException} 而不是 {@code Exception}，理由有两条：
 *
 * <ol>
 *   <li><b>解析失败是「这一份文档不行」，不是「整个程序不行」。</b>
 *       入库是批量操作，几十份文档里有一份损坏的，应该把这份标成失败、
 *       继续处理剩下的，而不是让整批中断。</li>
 *   <li><b>调用链上有四层（解析 → 切分 → 向量化 → 写库），
 *       每层都在签名上写 {@code throws} 会让编排代码被 try-catch 淹没。</b>
 *       非受检异常让「正常路径」保持清爽，异常统一在最外层
 *       （入库编排处）捕获并落成 {@code kb_document.status = 4}</li>
 * </ol>
 *
 * <p>{@code fileName} 单独存一个字段：批量入库失败时，
 * 日志里必须能直接看出<b>是哪一份文件</b>出的问题。
 * 只把文件名拼进 message 也能用，但那样上层的监控告警就没法按文件名聚合了。
 */
public class DocumentParseException extends RuntimeException {

    private final String fileName;

    public DocumentParseException(String fileName, String message, Throwable cause) {
        super("解析文档失败 [" + fileName + "]：" + message, cause);
        this.fileName = fileName;
    }

    public DocumentParseException(String fileName, String message) {
        this(fileName, message, null);
    }

    public String getFileName() {
        return fileName;
    }
}
