package com.xbla.rag.rag.ingest;

import com.xbla.rag.common.util.DigestUtil;
import com.xbla.rag.config.KbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文档原件的落盘存储。
 *
 * <h2>一、为什么要把文件存下来，而不是解析完就丢</h2>
 *
 * <p>入库流水线的第一步是解析，看起来「解析完就不需要原文件了」。但存下来有三个理由：
 * <ol>
 *   <li><b>可重放</b>：切分参数调了、解析器升级了，要重新入库。没有原件就只能让用户重传。</li>
 *   <li><b>可溯源</b>：阶段 7 评测发现某个切片质量差时，要能翻回原文件看是哪一页出的问题。</li>
 *   <li><b>可异步</b>：上传接口立刻返回，真正的活在线程池里跑 ——
 *       那时候 HTTP 请求早就结束了，{@code MultipartFile} 也已经失效。
 *       <b>原件必须先落盘，后台线程才有东西可读。</b></li>
 * </ol>
 *
 * <h2>二、文件名的生成规则</h2>
 * <pre>
 *   data/uploads/2026-09-18/{uuid8}_{原文件名sanitized}.pdf
 *                 ↑ 按日期分目录         ↑ 见 sanitize()
 * </pre>
 *
 * <p><b>为什么按日期分目录</b>：所有文件摊在一个目录里，
 * 几天之后 {@code ls} 就要滚屏，运维排查时找一个文件很痛苦。
 * 按日期分目录是成本最低的组织方式。
 *
 * <p><b>为什么加 uuid 前缀</b>：防止重名覆盖。两个用户上传同名的
 * {@code 退货政策.pdf}，不加前缀的话后一个会覆盖前一个，
 * 而前一个文档在数据库里还指着那个路径 —— 它的切片内容会变得和文件名对不上。
 */
@Component
public class DocumentStore {

    private static final Logger log = LoggerFactory.getLogger(DocumentStore.class);

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 单个上传文件的大小上限：50 MB */
    public static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    private final KbProperties kbProperties;

    public DocumentStore(KbProperties kbProperties) {
        this.kbProperties = kbProperties;
    }

    /**
     * 一份已落盘的文件。
     *
     * @param fileName 存盘时使用的文件名（已 sanitize，含 uuid 前缀）
     * @param filePath <b>相对路径</b>，相对于应用启动目录。直接写进 {@code kb_document.file_path}
     * @param fileSize 字节数
     * @param fileHash 内容的 SHA-256，用于重复上传去重
     */
    public record StoredFile(String fileName, String filePath, long fileSize, String fileHash) {
    }

    /**
     * 保存上传的文件。
     *
     * <p><b>不关闭传进来的流</b> —— 由调用方（Spring 的 MultipartFile）管理。
     *
     * <p>实现上是「边写临时文件边算摘要」的：{@code DigestUtil.sha256(in)}
     * 和 {@code Files.copy(in, ...)} 都需要读一遍流，而流只能读一次。
     * 所以这里手工分块读，一份数据同时完成「写盘」和「算摘要」两件事 ——
     * 只读一遍流，只写一遍盘。
     */
    public StoredFile save(InputStream in, String originalFileName) throws IOException {
        Path target = allocatePath(originalFileName);

        var digest = DigestUtil.newSha256();
        long size = 0;
        try (var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                size += read;
                if (size > MAX_FILE_SIZE) {
                    // 超大文件不能继续写下去 —— 磁盘会被填满。
                    // 关掉输出流再删文件：Windows 上文件被打开时删不掉
                    out.close();
                    Files.deleteIfExists(target);
                    throw new IOException("文件超过 " + (MAX_FILE_SIZE / 1024 / 1024) + " MB 上限");
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }

        String relative = toRelativePath(target);
        String hash = DigestUtil.toHex(digest.digest());
        log.debug("文件已落盘 path={} size={} hash={}", relative, size, hash);
        return new StoredFile(target.getFileName().toString(), relative, size, hash);
    }

    /**
     * 保存一段文本内容（数据库同步用）。
     *
     * <p><b>为什么数据库同步也要落盘</b>：为了让入库<b>只有一条代码路径</b>。
     * 如果数据库来源的文档直接走「内存里的字符串 → 解析」，
     * 那入库流水线就要分叉成两条：一条从文件读、一条从内存读。
     * 两条路径意味着两套测试、两处可能不一致的状态处理。
     *
     * <p>落盘之后，{@code DocumentIngestWorker} 只需要认识
     * 「{@code kb_document.file_path} 指向一个存在的文件」这一件事。
     * 代价是一份几百 KB 的文本文件，换来的是代码路径唯一。
     *
     * <p>另外这也带来了可重放性 —— 阶段 7 重新入库不用重新查源表。
     *
     * @param content  已经渲染好的文本内容
     * @param baseName 用于生成文件名的基名（如政策编号、商品编号）
     */
    public StoredFile saveText(String content, String baseName) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_SIZE) {
            throw new IOException("文本内容超过 " + (MAX_FILE_SIZE / 1024 / 1024) + " MB 上限");
        }

        Path target = allocatePath(baseName + ".md");
        Files.write(target, bytes);

        String relative = toRelativePath(target);
        return new StoredFile(target.getFileName().toString(), relative, bytes.length,
                DigestUtil.sha256(bytes));
    }

    /** 读回一份已落盘的文件。路径不存在时抛 {@link IOException}（而不是返回 null）—— 静默的 null 会让问题在更远的地方爆出来 */
    public InputStream open(String relativePath) throws IOException {
        Path path = Path.of(relativePath);
        if (!Files.isReadable(path)) {
            throw new IOException("文件不存在或不可读：" + relativePath);
        }
        return Files.newInputStream(path);
    }

    // ================================================================
    // 内部
    // ================================================================

    /** 在今天的日期目录下分配一个唯一路径，必要时创建目录 */
    private Path allocatePath(String originalFileName) throws IOException {
        Path dir = Path.of(kbProperties.getStorageDir(), LocalDate.now().format(DATE_DIR));
        Files.createDirectories(dir);

        String safeName = sanitize(originalFileName);
        String unique = UUID.randomUUID().toString().substring(0, 8) + "_" + safeName;
        return dir.resolve(unique);
    }

    /**
     * 清洗文件名，防止路径穿越。
     *
     * <p>★ <b>这是一处安全边界</b>。上传的文件名完全由用户控制，
     * 如果直接拿它拼路径，恶意的 {@code ../../../application-local.yml}
     * 会让我们把文件写到项目配置目录里去（甚至覆盖它）。
     *
     * <p>清洗做两件事：
     * <ol>
     *   <li>只保留最后一段路径 —— {@code Path.getFileName()} 会把
     *       {@code ../../etc/passwd} 变成 {@code passwd}</li>
     *   <li>把剩下的非法字符换成下划线（Windows 不允许 {@code \ / : * ? " < > |}）</li>
     * </ol>
     *
     * <p>清洗后如果什么都不剩（比如文件名是 {@code ../}），兜底成 {@code upload}。
     */
    private static String sanitize(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "upload";
        }
        // 取最后一段，挡掉 ../ 穿越
        String name = Path.of(originalFileName).getFileName().toString();
        name = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
        // 挡掉 Windows 的保留名（CON / PRN / AUX / NUL / COM1 等）
        if (name.isEmpty() || name.matches("(?i)(CON|PRN|AUX|NUL|COM\\d|LPT\\d)(\\..*)?")) {
            return "upload";
        }
        // 名字太长会让某些文件系统直接报错，截断到 100 字符
        if (name.length() > 100) {
            int dot = name.lastIndexOf('.');
            String ext = (dot > 0 && name.length() - dot <= 10) ? name.substring(dot) : "";
            name = name.substring(0, 100 - ext.length()) + ext;
        }
        return name;
    }

    /** 把绝对/相对路径统一成「相对应用启动目录」的形式，存进数据库 */
    private static String toRelativePath(Path path) {
        // 统一用正斜杠：Windows 上 Path.toString() 给的是反斜杠，
        // 而反斜杠在 YAML / JSON / SQL 里都是转义字符，存进数据库之后
        // 再读出来会变成乱七八糟的东西。存正斜杠，读的时候
        // Path.of() 在 Windows 上照样能正确解析
        return path.toString().replace('\\', '/');
    }
}
