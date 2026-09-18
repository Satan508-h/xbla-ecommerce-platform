package com.xbla.rag.rag;

import com.xbla.rag.rag.chunk.HeadingAwareChunker;
import com.xbla.rag.rag.chunk.TextChunk;
import com.xbla.rag.rag.chunk.TextChunkingOptions;
import com.xbla.rag.rag.parse.ParsedDocument;
import com.xbla.rag.rag.parse.TextBlock;
import com.xbla.rag.rag.parse.TikaDocumentParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语料端到端测试：用 {@code scripts/generate_corpus.py} 生成的<b>真实文档</b>
 * 跑完整的「解析 → 切分」，把每一步的中间产物打出来。
 *
 * <p><b>这个测试补的是什么</b>
 *
 * <p>{@code TikaDocumentParserTest} 用的是手工准备的小样本，
 * 验证的是「某一个格式的某一条路径」。而这里是<b>真实体量的文档</b> ——
 * 一份几千字的政策汇编、一份二十行的 FAQ 表 ——
 * 能暴露小样本暴露不了的问题：切片长度分布是否合理、
 * 标题路径会不会因为文档变长而错乱、Excel 的大表会不会被拼成一条巨长的文本。
 *
 * <p><b>为什么语料不存在时跳过而不是失败</b>：{@code data/corpus/} 在
 * {@code .gitignore} 里（语料是可重新生成的产物，不该进 git）。
 * 别人克隆仓库后没跑生成脚本就执行 {@code mvn test}，
 * 这个测试应该安静跳过，而不是报一堆「文件不存在」。
 * 用 {@link Assumptions#assumeTrue} 表达这个语义。
 *
 * <p>运行方式：
 * <pre>
 *   python scripts/generate_corpus.py
 *   ./mvnw test -Dtest=CorpusPipelineTest
 * </pre>
 */
@DisplayName("语料端到端 —— 真实文档的解析与切分")
class CorpusPipelineTest {

    private static final Path CORPUS_DIR = Path.of("data", "corpus");

    private final TikaDocumentParser parser = new TikaDocumentParser();
    private final HeadingAwareChunker chunker = new HeadingAwareChunker();

    private static TextChunkingOptions options() {
        // 和 application.yml 里的默认值保持一致
        return new TextChunkingOptions(500, 80, 80, 700, "。！？；!?;\n", true);
    }

    private static List<Path> corpusFiles() throws Exception {
        try (Stream<Path> stream = Files.list(CORPUS_DIR)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    @Test
    @DisplayName("★ 逐份解析并切分，打印全部中间产物")
    void parseAndChunkAll() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(CORPUS_DIR),
                "语料目录不存在，先执行 python scripts/generate_corpus.py");

        List<Path> files = corpusFiles();
        Assumptions.assumeTrue(!files.isEmpty(), "语料目录为空，先执行生成脚本");

        int totalChunks = 0;
        int totalHeadings = 0;

        for (Path file : files) {
            ParsedDocument parsed;
            try (InputStream in = Files.newInputStream(file)) {
                parsed = parser.parse(in, file.getFileName().toString());
            }
            List<TextChunk> chunks = chunker.chunk(parsed.blocks(), options(), parsed.title());

            totalChunks += chunks.size();
            totalHeadings += parsed.headingCount();

            System.out.println("\n========================================================");
            System.out.printf("文件   : %s%n", file.getFileName());
            System.out.printf("MIME   : %s%n", parsed.mimeType());
            System.out.printf("标题   : %s%n", parsed.title());
            System.out.printf("文本块 : %d（其中标题 %d）%n", parsed.blocks().size(), parsed.headingCount());
            System.out.printf("字符数 : %d%n", parsed.charCount());
            System.out.printf("切片数 : %d%n", chunks.size());

            System.out.println("--- 识别出的标题结构 ---");
            for (TextBlock block : parsed.blocks()) {
                if (block.isHeading()) {
                    System.out.printf("  %s %s%n", "  ".repeat(block.level() - 1), block.text());
                }
            }

            System.out.println("--- 切片一览 ---");
            for (TextChunk chunk : chunks) {
                String preview = chunk.content().replace("\n", "⏎");
                if (preview.length() > 70) {
                    preview = preview.substring(0, 70) + "…";
                }
                System.out.printf("  [%2d] %4d字 路径=%-40s %s%n",
                        chunk.index(), chunk.charCount(),
                        abbreviate(chunk.headingPath(), 40), preview);
            }

            // ---- 通用断言：任何一份文档都该满足 ----
            assertThat(parsed.isEmpty()).as("%s 解析出了内容", file.getFileName()).isFalse();
            assertThat(chunks).as("%s 切分出了切片", file.getFileName()).isNotEmpty();

            for (TextChunk chunk : chunks) {
                assertThat(chunk.charCount())
                        .as("%s 的切片不该超过 maxChars + 标题路径的长度", file.getFileName())
                        .isLessThanOrEqualTo(500 + 200);
            }
        }

        System.out.println("\n========================================================");
        System.out.printf("合计：%d 份文档，%d 个标题，%d 个切片%n", files.size(), totalHeadings, totalChunks);

        assertThat(totalChunks).isGreaterThan(files.size());
    }

    @Test
    @DisplayName("★ PDF 的标题必须靠文档大纲提取出来（否则会退化成无结构纯文本）")
    void pdfHeadingsComeFromOutline() throws Exception {
        Path pdf = CORPUS_DIR.resolve("售后政策汇编.pdf");
        Assumptions.assumeTrue(Files.isRegularFile(pdf), "PDF 语料未生成");

        ParsedDocument parsed;
        try (InputStream in = Files.newInputStream(pdf)) {
            parsed = parser.parse(in, pdf.getFileName().toString());
        }

        // ★ 这条断言是「策略② 确实在工作」的证据。
        //   PDF 正文里一个 <h1> 都没有（Tika 不会为 PDF 生成标题标签），
        //   如果大纲提取失效，headingCount 会是 0，
        //   于是整份文档退化成定长切分 —— 而且【不会报任何错】
        assertThat(parsed.headingCount())
                .as("PDF 标题应该从文档大纲里提取出来")
                .isGreaterThan(5);

        assertThat(parsed.blocks().stream().filter(TextBlock::isHeading).map(TextBlock::text))
                .contains("第一章 总则")
                .contains("第二章 七天无理由退货");
    }

    @Test
    @DisplayName("★ Markdown 的标题必须靠行首 # 号提取（Tika 没有 Markdown 解析器）")
    void markdownHeadingsComeFromHashPattern() throws Exception {
        Path md = CORPUS_DIR.resolve("商品导购指南.md");
        Assumptions.assumeTrue(Files.isRegularFile(md), "Markdown 语料未生成");

        ParsedDocument parsed;
        try (InputStream in = Files.newInputStream(md)) {
            parsed = parser.parse(in, md.getFileName().toString());
        }

        assertThat(parsed.headingCount()).as("Markdown 标题应该被行首模式识别").isGreaterThan(3);

        // 标题文本里不该残留 # 号
        assertThat(parsed.blocks().stream().filter(TextBlock::isHeading).map(TextBlock::text))
                .noneMatch(t -> t.startsWith("#"))
                .contains("一、按使用场景选购");
    }

    @Test
    @DisplayName("★ Excel 的问答表要拆成可检索的行，而不是一整坨")
    void excelRowsBecomeSeparateBlocks() throws Exception {
        Path xlsx = CORPUS_DIR.resolve("售后FAQ.xlsx");
        Assumptions.assumeTrue(Files.isRegularFile(xlsx), "Excel 语料未生成");

        ParsedDocument parsed;
        try (InputStream in = Files.newInputStream(xlsx)) {
            parsed = parser.parse(in, xlsx.getFileName().toString());
        }

        // 20 行问答 + 表头，每行一个文本块
        assertThat(parsed.blocks()).hasSizeGreaterThan(15);
        assertThat(parsed.blocks().stream().map(TextBlock::text))
                .as("每一行问答要成为独立的文本块，这样检索才能精确命中单条 FAQ")
                .anyMatch(t -> t.contains("退货需要多长时间") && t.contains("三个工作日"));
    }

    /** 用于打印的截断，不影响断言 */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "(无)";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
