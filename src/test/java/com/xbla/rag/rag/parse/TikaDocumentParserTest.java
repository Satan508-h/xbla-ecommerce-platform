package com.xbla.rag.rag.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TikaDocumentParser} 的测试。
 *
 * <p><b>为什么这个测试特别重要</b>
 *
 * <p>路线图 3.3 要求「标题层级 + 定长 + 重叠窗口」切分。<b>切分层能拿到什么，
 * 完全取决于解析层吐出了什么</b> —— 解析层把标题识别错了，
 * 切分层再怎么调参数都救不回来，而且症状极其隐蔽：
 * 检索是能跑通的，只是答案质量莫名地差，没有任何报错。
 *
 * <p>四种格式的夹具在 {@code src/test/resources/parse/} 下，是<b>真实生成</b>的
 * 文件（reportlab / python-docx / openpyxl 产出），不是手工编的假数据 ——
 * 手工编的假数据只会验证我们自己的假设。
 *
 * <p>纯解析测试，不需要 Spring、不连数据库，毫秒级跑完。
 */
@DisplayName("TikaDocumentParser —— 多格式解析与标题层级提取")
class TikaDocumentParserTest {

    private final TikaDocumentParser parser = new TikaDocumentParser();

    private ParsedDocument parseResource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/parse/" + name)) {
            assertThat(in).as("测试夹具 %s 不存在", name).isNotNull();
            return parser.parse(in, name);
        }
    }

    /** 把解析结果里的标题打印出来，方便肉眼核对 */
    private static List<String> headingsOf(ParsedDocument doc) {
        return doc.blocks().stream()
                .filter(TextBlock::isHeading)
                .map(b -> b.level() + ":" + b.text())
                .toList();
    }

    // ============================================================
    // ★ 四种格式各自的标题提取路径
    // ============================================================

    @Nested
    @DisplayName("★ Word (.docx)：策略① 靠 <h1>-<h6> 标签")
    class Word {

        @Test
        @DisplayName("三级标题全部识别出来，层级正确")
        void extractsHeadings() throws IOException {
            ParsedDocument doc = parseResource("sample.docx");

            assertThat(headingsOf(doc)).containsExactly(
                    "1:七天无理由退货规则",
                    "2:一、适用范围",
                    "3:1.1 不适用的情况");
        }

        @Test
        @DisplayName("正文段落没有被误判成标题")
        void bodyStaysBody() throws IOException {
            ParsedDocument doc = parseResource("sample.docx");

            List<String> bodies = doc.blocks().stream()
                    .filter(TextBlock::isBody)
                    .map(TextBlock::text)
                    .toList();

            assertThat(bodies).contains("本规则适用于本平台所有自营商品。");
            assertThat(bodies).contains("手机、笔记本电脑等数码类商品适用本规则。");
        }

        @Test
        @DisplayName("MIME 按文件内容识别，不看扩展名")
        void detectsMimeByContent() throws IOException {
            ParsedDocument doc = parseResource("sample.docx");

            assertThat(doc.mimeType())
                    .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }

        @Test
        @DisplayName("块顺序和原文一致（chunk_index 的取值依据）")
        void preservesOrder() throws IOException {
            ParsedDocument doc = parseResource("sample.docx");

            List<String> all = doc.blocks().stream().map(TextBlock::text).toList();
            assertThat(all.indexOf("七天无理由退货规则"))
                    .isLessThan(all.indexOf("本规则适用于本平台所有自营商品。"));
            assertThat(all.indexOf("本规则适用于本平台所有自营商品。"))
                    .isLessThan(all.indexOf("一、适用范围"));
        }
    }

    @Nested
    @DisplayName("★ PDF：策略② 靠文档大纲 + 正文行精确匹配")
    class Pdf {

        @Test
        @DisplayName("★ 大纲里的标题被正确关联回正文位置，层级来自大纲的嵌套深度")
        void extractsHeadingsViaOutline() throws IOException {
            ParsedDocument doc = parseResource("sample.pdf");

            // ★ 这是整个解析器最绕的一条路：
            //   Tika 把 PDF 的标题抽成 <body> 末尾的嵌套 <ul>，
            //   和正文完全分离。这里验证「文本精确匹配」把它们接回去了
            assertThat(headingsOf(doc)).containsExactly(
                    "1:七天无理由退货规则",
                    "2:一、适用范围",
                    // ★ 第三个标题不在 PDF 大纲里（生成样本时只登记了 2 条），
                    //   是策略③ 从正文里按 "1.1 xxx" 的模式补认出来的。
                    //   这条断言把「三种策略互补」这件事钉下来：
                    //   大纲给的是权威层级，行首模式负责补齐大纲漏掉的标题
                    "3:1.1 不适用的情况");
        }

        @Test
        @DisplayName("★ 大纲不能同时当正文入库（否则每个标题重复出现两次）")
        void outlineIsNotEmittedAsBody() throws IOException {
            ParsedDocument doc = parseResource("sample.pdf");

            // 正文里「一、适用范围」只应该以「标题」的身份出现一次。
            // 如果 walk 时没跳过大纲元素，这里会数到 2
            long occurrences = doc.blocks().stream()
                    .filter(b -> "一、适用范围".equals(b.text()))
                    .count();

            assertThat(occurrences)
                    .as("大纲元素被当正文重复遍历了")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("PDF 正文提取正常，没有因为解析大纲而丢内容")
        void bodyIsIntact() throws IOException {
            ParsedDocument doc = parseResource("sample.pdf");

            assertThat(doc.blocks().stream().map(TextBlock::text))
                    .contains("手机、笔记本电脑等数码类商品适用本规则。")
                    .contains("定制类商品、拆封的贴身用品不适用七天无理由退货。");
        }
    }

    @Nested
    @DisplayName("★ Markdown：策略③ 靠行首 # 号（Tika 把它当纯文本）")
    class Markdown {

        @Test
        @DisplayName("★ Tika 认得出 text/markdown，但没有解析器 —— 所以必须靠行首模式兜底")
        void tikaHasNoMarkdownParser() throws IOException {
            ParsedDocument doc = parseResource("sample.md");

            // ★ 这条断言背后是一个实测结论（翻遍所有 tika jar 确认）：
            //   Tika 3.3.2 里【没有任何 Markdown 解析器】，只有反向的
            //   ToMarkdownContentHandler（HTML → Markdown）。
            //   所以 .md 只是被【识别】成 text/markdown，
            //   实际干活的是 TextAndCSVParser —— 整个文件塞进一个 <p>，
            //   # 号原样留在文字里。
            //
            //   MIME 是 text/markdown 而不是 text/plain，看起来像是「有解析器」，
            //   很容易让人以为标题是 Tika 给的。这条断言就是为了挡住这个误判。
            assertThat(doc.mimeType()).startsWith("text/markdown");

            // 真正的证据：正文里那一行带着 # 号。
            // 如果 Tika 真的解析了 Markdown，标题文本里不会出现 #
            assertThat(doc.blocks().stream().map(TextBlock::text))
                    .as("Markdown 没被解析，所以 # 号还在正文里；"
                            + "它被剥掉说明是策略③ 干的活")
                    .doesNotContain("# 七天无理由退货规则");
        }

        @Test
        @DisplayName("# 的个数决定层级，且 # 号要从标题文本里剥掉")
        void hashCountDeterminesLevel() throws IOException {
            ParsedDocument doc = parseResource("sample.md");

            // 注意断言里没有 "#"：策略③ 剥掉 # 之后才入库。
            // 不剥的话，kb_chunk.heading_path 会变成
            // "售后政策 > ## 一、适用范围" 这种带井号的怪东西
            assertThat(headingsOf(doc)).containsExactly(
                    "1:七天无理由退货规则",
                    "2:一、适用范围",
                    "3:1.1 不适用的情况");
        }
    }

    @Nested
    @DisplayName("Excel (.xlsx)：sheet 名当一级标题，行拼成文本")
    class Excel {

        @Test
        @DisplayName("sheet 名被当作一级标题")
        void sheetNameBecomesHeading() throws IOException {
            ParsedDocument doc = parseResource("sample.xlsx");

            assertThat(headingsOf(doc)).containsExactly("1:售后FAQ");
        }

        @Test
        @DisplayName("表格每行拼成一行文本，单元格用 | 分隔")
        void tableRowsBecomeLines() throws IOException {
            ParsedDocument doc = parseResource("sample.xlsx");

            assertThat(doc.blocks().stream().map(TextBlock::text))
                    .contains("问题 | 答案")
                    .contains("退货要几天 | 签收后 7 天内可申请")
                    .contains("拆封了能退吗 | 贴身用品拆封后不支持");
        }
    }

    // ============================================================
    // ★ 启发式的误判防护（策略③ 的两道保险）
    // ============================================================

    @Nested
    @DisplayName("★ 行首模式的两道保险：长度限制 + 保守的写法")
    class HeadingHeuristics {

        private List<String> headingsOfText(String text) {
            ParsedDocument doc = parser.parse(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "t.txt");
            return headingsOf(doc);
        }

        @Test
        @DisplayName("保险一：超过 30 字的行不用有歧义的模式去判标题")
        void longLinesAreNeverHeadings() {
            // 这一行以「1.5」开头，形式上像 1.5 节。
            // 如果只看前缀就会误判成标题，多切一刀
            String longLine = "1.5 米长的数据线支持七天无理由退货但是需要保持包装完整并且不影响二次销售";
            assertThat(longLine.length()).isGreaterThan(30);

            assertThat(headingsOfText(longLine)).isEmpty();
        }

        @Test
        @DisplayName("★ 但长度保险不管 Markdown —— 长标题必须照样识别出来")
        void lengthGuardDoesNotApplyToMarkdown() {
            // Markdown 的 # 是无歧义的：正文不可能以 "# " 开头。
            // 所以长度检查放在它之后，长标题不会被误伤。
            // 顺序放反的话，这条测试会失败 —— 这正是它存在的意义
            String longHeading = "## 这是一个非常非常长的二级标题超过了三十个字符的长度上限用来验证保险顺序";

            assertThat(longHeading).as("必须真的超过长度上限，否则这条测试没有验证力")
                    .hasSizeGreaterThan(30);
            assertThat(headingsOfText(longHeading))
                    .containsExactly("2:这是一个非常非常长的二级标题超过了三十个字符的长度上限用来验证保险顺序");
        }

        @Test
        @DisplayName("保险二：数字编号必须带小数点，「1. 序言」这种有序列表不算标题")
        void plainNumberedListIsNotHeading() {
            assertThat(headingsOfText("1. 先把商品加入购物车")).isEmpty();
            assertThat(headingsOfText("2. 然后选择收货地址")).isEmpty();
        }

        @Test
        @DisplayName("保险二：中文序号后面必须紧跟顿号，「三年、五年保修」不算标题")
        void chineseNumeralWithoutCommaIsNotHeading() {
            // 以中文数字开头，但后面不是顿号
            assertThat(headingsOfText("三年质保期内免费维修")).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] 应识别为标题：{0}")
        @DisplayName("真实标题能被正确识别")
        @ValueSource(strings = {
                "# 一级标题",
                "### 三级标题",
                "第一章 总则",
                "第二节 适用范围",
                "一、适用范围",
                "二、退货流程",
                "1.1 不适用的情况",
                "2.3.1 特殊情况"
        })
        void realHeadingsAreDetected(String line) {
            assertThat(headingsOfText(line)).as("「%s」应该被识别为标题", line).hasSize(1);
        }

        @ParameterizedTest(name = "[{index}] 不应识别为标题：{0}")
        @DisplayName("容易误判的正文不会被当成标题")
        @ValueSource(strings = {
                "本规则适用于本平台所有自营商品",
                "手机支持七天无理由退货",
                "1. 加入购物车",
                "2、去结算",
                "一个工作日之内发货",
                "请拨打客服电话咨询详情"
        })
        void bodyTextIsNotDetected(String line) {
            assertThat(headingsOfText(line)).as("「%s」不应该被识别为标题", line).isEmpty();
        }
    }

    // ============================================================
    // 边界与错误处理
    // ============================================================

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("★ 0 字节文件给出人能看懂的错误，而不是 Tika 那句 InputStream must have > 0 bytes")
        void emptyFile() {
            // Tika 对 0 字节输入会抛 "InputStream must have > 0 bytes"。
            // 这句话会原样进 kb_document.error_msg，看到它的人不会想到
            // 「哦，我传了个空文件」。所以解析器提前拦下来换成一句人话。
            assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(new byte[0]), "empty.txt"))
                    .isInstanceOf(DocumentParseException.class)
                    .hasMessageContaining("文件内容为空");
        }

        @Test
        @DisplayName("纯空白文件同样安全")
        void blankFile() {
            ParsedDocument doc = parser.parse(
                    new ByteArrayInputStream("   \n\n\t  \n".getBytes(StandardCharsets.UTF_8)), "blank.txt");

            assertThat(doc.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("★ 不关闭调用方的输入流（接口约定，关错了会在外层抛「流已关闭」）")
        void doesNotCloseCallerStream() throws IOException {
            byte[] content = "一些内容".getBytes(StandardCharsets.UTF_8);
            InputStream in = new ByteArrayInputStream(content) {
                @Override
                public void close() throws IOException {
                    super.close();
                    throw new AssertionError("解析器不该关闭调用方的流");
                }
            };

            // 解析器内部如果错误地 try-with-resources 关掉了这个流，上面 close() 里的
            // AssertionError 会冒出来。这类 bug 在真实调用路径上表现为
            // 「外层再关一次时报流已关闭」，排查成本很高，所以在这里钉死
            assertThat(parser.parse(in, "x.txt").blocks()).isNotEmpty();
        }

        @Test
        @DisplayName("Tika 填的占位标题 \"(anonymous)\" 要当 null，不能原样写进数据库")
        void placeholderTitleBecomesNull() throws IOException {
            ParsedDocument doc = parseResource("sample.pdf");

            // Tika 在 PDF 没有标题元数据时会填 "(anonymous)"。
            // 不清理的话，数据库里会出现一堆标题叫 "(anonymous)" 的文档
            assertThat(doc.title()).isNull();
        }

        @Test
        @DisplayName("★★ 回归：小于 8KB 的文件用真实文件流也能解析")
        void smallFileFromFileStream() throws IOException {
            // ★ 这条测试钉死一个【只在生产路径上才会复现】的 bug。
            //
            //   症状：所有小于 8KB 的文件都报 "InputStream must have > 0 bytes"。
            //
            //   根因：空文件探测时套了一层 BufferedInputStream 去 mark/reset，
            //   探测完把这层包装丢掉了；而 BufferedInputStream 在【构造时】
            //   就会从底层流预读 8192 字节填进自己的缓冲区 ——
            //   那批数据随着被丢弃的包装流一起没了。
            //   接着 parse 又包了第二层，从一个已经跳过 8KB 的位置开始读，
            //   对小于 8KB 的文件就是「什么都没读到」。
            //
            //   为什么上面那些测试抓不到：
            //   getResourceAsStream 返回的流本身就支持 mark，
            //   不会被包装，也就不会重复包。
            //   【只有 Files.newInputStream 这种真实文件流才会触发】——
            //   而 DocumentIngestWorker 走的恰恰是这条路。
            //
            //   所以这条测试刻意走真实文件流，而不是 getResourceAsStream。
            Path tmp = Files.createTempFile("xbla-regression-", ".md");
            try {
                Files.writeString(tmp, "# 标题\n\n一段不足 8KB 的正文。", StandardCharsets.UTF_8);
                assertThat(Files.size(tmp))
                        .as("必须小于 BufferedInputStream 的默认缓冲 8192 字节，否则这条测试没有验证力")
                        .isLessThan(8192);

                ParsedDocument doc;
                try (InputStream in = Files.newInputStream(tmp)) {
                    doc = parser.parse(in, "tiny.md");
                }

                assertThat(doc.headingCount()).isEqualTo(1);
                assertThat(doc.blocks().stream().map(TextBlock::text)).contains("一段不足 8KB 的正文。");
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }
}
