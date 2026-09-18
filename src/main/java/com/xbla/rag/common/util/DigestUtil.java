package com.xbla.rag.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要工具。目前只提供 SHA-256 十六进制字符串。
 *
 * <p><b>本项目用摘要做两件事，都不是安全用途</b>：
 * <ol>
 *   <li>{@code kb_document.file_hash} —— <b>文件去重</b>。
 *       同一个文件重复上传时，先算摘要查一下，命中就直接跳过，
 *       省掉一次完整的解析 + 向量化（向量化是<b>花钱</b>和花时间的操作）。</li>
 *   <li>{@code kb_chunk.content_hash} —— <b>切片去重</b>。
 *       文档局部改动后重新入库时，未变的部分不应该重新向量化。</li>
 * </ol>
 *
 * <p>⚠️ <b>这里不是在做密码学保护</b>。SHA-256 抗碰撞的性质在这里是「顺带的好处」，
 * 真正需要的是「内容变了摘要必变、内容没变摘要必同」。
 * 不要把它当成签名或完整性校验来用 —— 那种场景需要 HMAC 或数字签名。
 */
public final class DigestUtil {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private DigestUtil() {
    }

    /** 字符串的 SHA-256，输出 64 位小写十六进制 */
    public static String sha256(String text) {
        if (text == null) {
            return null;
        }
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 字节数组的 SHA-256 */
    public static String sha256(byte[] data) {
        if (data == null) {
            return null;
        }
        MessageDigest digest = newDigest();
        return toHex(digest.digest(data));
    }

    /**
     * 输入流的 SHA-256，<b>算完不关闭流</b>。
     *
     * <p>流的所有权属于调用方 —— 和 {@code DocumentParser} 的约定一致。
     *
     * <p>分块读取而不是 {@code readAllBytes()}：一份几十 MB 的 PDF 全读进内存
     * 只为算个摘要，是很不必要的内存压力，而流式读取的代码只多三行。
     */
    public static String sha256(InputStream in) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return toHex(digest.digest());
    }

    /**
     * 取一个 SHA-256 的 {@link MessageDigest} 实例，供调用方<b>增量更新</b>。
     *
     * <p><b>为什么要把这个暴露出去</b>：有些场景需要「读一遍数据、
     * 同时完成两件事」—— 典型的是一边把上传的文件写进磁盘、一边算它的摘要。
     * 这时候没法调用 {@link #sha256(InputStream)}（那会把流读完，
     * 后面的写盘就没数据了），只能自己分块读、拿到一块就
     * {@code digest.update(block)} 一次。
     *
     * <p>公开这个方法，是为了让 {@code DocumentStore} 不必自己
     * 再写一遍 {@code MessageDigest.getInstance("SHA-256")} 和十六进制转换 ——
     * 同一份逻辑散在两处，迟早改漏一处。
     */
    public static MessageDigest newSha256() {
        return newDigest();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法（Java 规范强制要求），
            // 走不到这里。真走到了说明 JVM 环境已经损坏到无法继续
            throw new IllegalStateException("JVM 不支持 SHA-256，环境异常", e);
        }
    }

    /**
     * 手工转十六进制，不用 {@code String.format("%02x", b)}。
     *
     * <p>后者每个字节都要走一次格式化解析（约 100 倍慢），
     * 而这里是按「每个切片一次」的频度调用的 ——
     * 一份文档几百个切片，差异虽然仍不大，但没有理由选慢的那个。
     */
    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
