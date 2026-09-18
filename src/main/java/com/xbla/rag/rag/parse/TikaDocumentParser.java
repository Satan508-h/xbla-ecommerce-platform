package com.xbla.rag.rag.parse;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.ToXMLContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 Apache Tika 的文档解析器。PDF / Word / Excel / Markdown / 纯文本统一入口。
 *
 * <h2>一、总体流程</h2>
 * <pre>
 *   字节流 ──Tika──> XHTML 字符串 ──jsoup──> DOM ──遍历──> List&lt;TextBlock&gt;
 *           ①                      ②            ③
 * </pre>
 *
 * <p><b>为什么不直接从 Tika 拿纯文本（{@code BodyContentHandler}）？</b>
 * 因为那样标题结构就丢了。而路线图 3.3 要求「标题层级 + 定长 + 重叠窗口」切分，
 * 没有标题层级就只能退化成定长切分，不同章节的内容会被切进同一个切片。
 * 所以这里刻意用 {@code ToXMLContentHandler} 保留结构，再用 jsoup 解析。
 *
 * <p><b>为什么用 jsoup 而不是正则？</b> 因为 PDF 的大纲是<b>嵌套</b>的
 * {@code <ul><li>…<ul><li>…}，层级靠嵌套深度表达。
 * 正则处理嵌套结构要么写不对，要么写完没人看得懂。
 * 而 jsoup 已经在依赖树里（Tika 的 HTML 模块传递引入），用它零新增依赖。
 *
 * <h2>二、★ 四种格式的标题形态完全不同（实测定论，不是推测）</h2>
 *
 * <p>这一节是照着 2026-09-18 的真实 dump 结果写的。四种格式走的是三条不同的路：
 *
 * <table border="1">
 *   <caption>实测的 Tika 输出形态</caption>
 *   <tr><th>格式</th><th>Tika 吐出来的样子</th><th>怎么提取标题</th></tr>
 *   <tr>
 *     <td>{@code .docx}</td>
 *     <td>正文里直接内联 {@code <h1>} {@code <h2>} {@code <h3>}</td>
 *     <td><b>策略①</b> 直接读标签名</td>
 *   </tr>
 *   <tr>
 *     <td>{@code .pdf}</td>
 *     <td><b>正文里一个 h 标签都没有</b>。标题被抽成 {@code <body>} 末尾
 *         一个嵌套 {@code <ul><li>}（PDF 大纲），<b>和正文完全分离</b></td>
 *     <td><b>策略②</b> 先从大纲建「标题文本 → 层级」映射表，
 *         再回正文按行精确匹配定位</td>
 *   </tr>
 *   <tr>
 *     <td>{@code .md}</td>
 *     <td><b>认得出、但不会解析</b>：{@code Content-Type} 是 {@code text/markdown}，
 *         可 Tika 3.3.2 <b>根本没有 Markdown 解析器</b>（翻遍所有 tika jar 确认过），
 *         实际干活的是 {@code TextAndCSVParser} ——
 *         <b>整个文件塞进一个 {@code <p>}</b>，{@code #} 号原样留在文本里</td>
 *     <td><b>策略③</b> 按行首模式（{@code #} / {@code 第X章} / {@code 一、} / {@code 1.1}）判断</td>
 *   </tr>
 *   <tr>
 *     <td>{@code .xlsx}</td>
 *     <td>sheet 名变成 {@code <h1>}，数据在 {@code <table><tr><td>}</td>
 *     <td>sheet 名当一级标题；每行拼成一行文本</td>
 *   </tr>
 * </table>
 *
 * <h2>三、PDF 那条路为什么这么绕</h2>
 *
 * <p>PDF 格式本身就<b>不存储「这是标题」这个信息</b> —— 它只记录「在 (x,y) 画一个
 * 12 号字体的字符串」。标题和正文的区别只体现在字号上，而 Tika 的 XHTML
 * 输出不带字号。所以能从 PDF 里拿到结构信息的唯一来源就是<b>文档大纲</b>
 * （用阅读器时左侧那个目录树）。
 *
 * <p>但大纲只告诉你「有哪些标题、各是几级」，<b>不告诉你它们在正文的哪个位置</b>。
 * 所以这里用「标题文本精确匹配」把两者关联起来 —— 扫描正文的每一行，
 * 如果某行的文字恰好等于大纲里的某个标题，就认为它是标题。
 *
 * <p><b>这个方法的已知失效场景</b>（诚实标注，不假装它万能）：
 * <ul>
 *   <li>PDF 没有大纲（绝大多数 PDF 都没有，尤其是扫描件）→ 提不出任何标题，
 *       退化为纯定长切分。这是信息论上的限制，不是实现缺陷。</li>
 *   <li>正文里有一句话<b>恰好</b>等于某个标题文本 → 会被误判成标题。
 *       影响可控：只是多切一刀，不会丢内容。</li>
 *   <li>同名标题出现在不同层级 → 取<b>先出现的那个层级</b>（见
 *       {@code outline.putIfAbsent}）。</li>
 * </ul>
 *
 * <h2>四、安全边界</h2>
 *
 * <p>解析的是<b>用户上传的文件</b>，必须假定它是恶意的。主要的攻击面是
 * 「压缩炸弹」（一个几 KB 的 docx 解压出几十 GB 文本，撑爆内存）。
 * 这里用 {@link WriteOutContentHandler} 给输出字符数设硬上限，超了直接抛异常。
 *
 * <p>没用 Tika 自带的 {@code SecureContentHandler}，原因见
 * {@link #MAX_CHARS} 的说明。
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentParser.class);

    /**
     * 单份文档解析出的字符数上限（约 200 万字符）。
     *
     * <p>超了会抛 {@link WriteLimitReachedException}，这份文档被判为入库失败。
     *
     * <p><b>为什么不用 Tika 自带的 {@code SecureContentHandler}</b>：
     * 它的构造器要求传入 {@code TikaInputStream}，而 {@code TikaInputStream}
     * 被关闭时会<b>连带关闭它包装的那个流</b>。这与
     * {@link DocumentParser} 「不接管调用方流所有权」的约定冲突 ——
     * 一旦在解析器里 try-with-resources 关掉它，调用方外层再关一次就会拿到
     * 「流已关闭」异常，而且这种 bug 只在特定调用路径下才复现，极难排查。
     *
     * <p>{@code SecureContentHandler} 的另一个能力是「按压缩比拦截 zip 炸弹」，
     * 比按输出长度拦截更早。但压缩炸弹的<b>实际危害是输出爆炸</b>，
     * 而 {@link WriteOutContentHandler} 正好卡在输出这一侧，
     * 所以防护效果是等价的（代价是会多消耗一点解压时的 CPU）。
     */
    static final int MAX_CHARS = 2_000_000;

    /**
     * 长度保险：超过这个长度的行，<b>不再尝试用「有歧义的模式」判断它是不是标题</b>。
     *
     * <p>真实标题都很短，而「1.5 米长的数据线支持七天无理由退货…」这类正文句子
     * 一旦长了就被挡在外面。
     *
     * <p>⚠️ 这个保险<b>不适用于 Markdown 的 {@code #} 模式</b> —— 那个模式没有歧义，
     * 长度检查放在它之后（见 {@link #guessHeading}）。放错顺序会让长标题被漏掉。
     */
    private static final int MAX_HEADING_CHARS = 30;

    /** ★ 策略③：Markdown 的行首 # 号。捕获组 1 = # 的个数 = 层级 */
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(\\S.*)$");

    /** ★ 策略③：中文章节号，如「第一章 总则」「第 2 节 适用范围」 */
    private static final Pattern CN_CHAPTER =
            Pattern.compile("^第\\s*[一二三四五六七八九十百零〇\\d]+\\s*[章节条]([\\s、.．:：].*)?$");

    /** ★ 策略③：中文序号，如「一、适用范围」 */
    private static final Pattern CN_NUMBER = Pattern.compile("^[一二三四五六七八九十]{1,3}、\\S.*$");

    /** ★ 策略③：多级数字编号，如「1.1 不适用的情况」——必须带小数点，挡掉「1. 序言」这种列表 */
    private static final Pattern NUM_DOTTED = Pattern.compile("^\\d+(\\.\\d+)+[\\s、.．:：]+\\S.*$");

    /** 这些标签里的内容不是正文，直接跳过 */
    private static final Set<String> SKIP_TAGS = Set.of("script", "style", "head", "meta", "link", "title");

    /** 块级标签。用来判断一个元素是「容器」还是「叶子文本」 */
    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "div", "li", "td", "th", "tr", "table", "ul", "ol",
            "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote",
            "section", "article", "header", "footer", "body", "html");

    private static final String MIME_PDF = "application/pdf";

    /**
     * Tika 在缺失元数据时填的占位值。这两个值等于「没有」，要当 null 处理，
     * 否则文档标题会变成字符串 "(anonymous)"，并且被写进数据库。
     */
    private static final Set<String> TIKA_PLACEHOLDERS = Set.of("(anonymous)", "(unspecified)", "(none)", "");

    /**
     * Tika 的自动格式识别解析器。
     *
     * <p>只创建一个实例并复用：它的构造过程要走 SPI 扫描类路径上所有解析器
     * （几十个 jar），每次 new 一遍代价不小。
     *
     * <p><b>线程安全吗？</b> 是。Tika 的解析器被设计成无状态的 ——
     * 所有解析过程中的可变状态都放在每次调用新建的 {@link ParseContext} 里，
     * 而不是存在解析器实例上。所以入库线程池里的多个线程可以共用这一个实例。
     */
    private final Parser parser = new AutoDetectParser();

    @Override
    public ParsedDocument parse(InputStream in, String fileName) {
        if (in == null) {
            throw new DocumentParseException(fileName, "输入流为 null");
        }

        // ★★ 包装只做一次，探测和解析必须用【同一个】流对象。
        //
        //   这里踩过一个很隐蔽的坑，值得记下来：
        //   第一版写成了
        //       if (isEmpty(in)) { ... }          // 内部自己包了一层 BufferedInputStream
        //       in = ensureMarkSupported(in);     // 又包了第二层
        //   结果所有小于 8KB 的文件都报 "InputStream must have > 0 bytes"。
        //
        //   原因是 BufferedInputStream 在【构造时】就会从底层流预读一批数据
        //   （默认 8192 字节）填进自己的缓冲区。探测用的那层包装被丢弃后，
        //   底层 FileInputStream 的读取位置已经前进了 8KB ——
        //   而那 8KB 数据随着被丢弃的包装流一起没了。
        //   于是第二层包装从一个已经跳过 8KB 的位置开始读，
        //   对小于 8KB 的文件来说就是「什么都没读到」。
        //
        //   为什么小样本测试没抓到：getResourceAsStream 返回的流本身就支持
        //   mark，ensureMarkSupported 原样返回、不做包装，所以不会重复包。
        //   【只有 Files.newInputStream 这种真实文件流才会触发】——
        //   而生产代码走的恰恰是这条路。这是语料端到端测试抓出来的
        InputStream stream = ensureMarkSupported(in);

        // 空文件要单独拦下来。Tika 对 0 字节输入会抛
        // TikaException: "InputStream must have > 0 bytes"，
        // 这条信息对使用者毫无帮助 —— 看到它的人不会想到「哦我传了个空文件」。
        // 这里提前判断并给一句能直接看懂的话，它会原样写进 kb_document.error_msg
        if (isEmpty(stream)) {
            throw new DocumentParseException(fileName, "文件内容为空（0 字节）");
        }

        // ---------- ① Tika：字节流 → XHTML ----------
        Metadata metadata = new Metadata();
        // 把文件名告诉 Tika。某些格式单看字节流分不出来
        // （如 OLE2 的 .doc 和 .xls 前缀相同），要靠文件名辅助
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

        ParseContext context = new ParseContext();
        // ★ 显式塞进 ParseContext。Tika 3 里如果只传裸 ParseContext 给具体解析器，
        //   嵌入文档（如 docx 里的缩略图）会被静默跳过。
        //   走 AutoDetectParser 本来就会自动设置，这里显式写一遍是为了
        //   让「为什么需要它」在代码里可见
        context.set(Parser.class, parser);

        ToXMLContentHandler xhtml = new ToXMLContentHandler();
        // ★ 包一层输出上限，挡住压缩炸弹
        WriteOutContentHandler limited = new WriteOutContentHandler(xhtml, MAX_CHARS);

        String mimeType;
        try {
            parser.parse(stream, limited, metadata, context);
            mimeType = metadata.get(Metadata.CONTENT_TYPE);
        } catch (WriteLimitReachedException e) {
            // 内容超限。这是「这份文档太大」，不是程序 bug
            throw new DocumentParseException(fileName,
                    "文档正文超过 " + MAX_CHARS + " 字符上限，已拒绝（防压缩炸弹）", e);
        } catch (SAXException e) {
            // WriteOutContentHandler 在超限时可能把异常包在 SAXException 里
            if (e.getCause() instanceof WriteLimitReachedException) {
                throw new DocumentParseException(fileName,
                        "文档正文超过 " + MAX_CHARS + " 字符上限，已拒绝（防压缩炸弹）", e);
            }
            throw new DocumentParseException(fileName, "XHTML 生成失败：" + e.getMessage(), e);
        } catch (TikaException e) {
            // TikaException 是受检异常：格式识别失败、解析器内部出错都会走这里。
            // 它和 SAXException 的区别是：前者是「解析这件事本身失败了」，
            // 后者是「解析过程中往 ContentHandler 写内容失败了」
            if (e.getCause() instanceof WriteLimitReachedException) {
                throw new DocumentParseException(fileName,
                        "文档正文超过 " + MAX_CHARS + " 字符上限，已拒绝（防压缩炸弹）", e);
            }
            throw new DocumentParseException(fileName, "格式解析失败：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new DocumentParseException(fileName, "读取文档失败：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Tika 面对损坏文件时会抛各种 RuntimeException，统一收口。
            // 不这样做的话，一份坏文档会让整个批量入库任务崩掉
            throw new DocumentParseException(fileName, "解析器内部错误：" + e.getMessage(), e);
        }

        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        // ---------- ②③ XHTML → 文本块 ----------
        List<TextBlock> blocks = extractBlocks(xhtml.toString(), fileName, mimeType);

        String title = cleanTitle(metadata.get(TikaCoreProperties.TITLE));
        int charCount = blocks.stream().mapToInt(b -> b.text().length()).sum();
        long headingCount = blocks.stream().filter(TextBlock::isHeading).count();

        // ★ headings=0 值得警惕：说明这份文档的标题层级完全没提取出来，
        //   切分会退化成定长模式（不同章节的内容可能被切进同一个切片）。
        //   它不是错误（扫描件本来就提不出标题），但排查检索质量问题时
        //   这是第一个该看的数字，所以打在日志里
        if (headingCount == 0 && charCount > 200) {
            log.info("解析完成但未识别出任何标题，将退化为定长切分 file={} mime={} chars={}",
                    fileName, mimeType, charCount);
        } else {
            log.debug("解析完成 file={} mime={} blocks={} headings={} chars={}",
                    fileName, mimeType, blocks.size(), headingCount, charCount);
        }

        return new ParsedDocument(blocks, title, mimeType, charCount);
    }

    // ================================================================
    // 策略②：PDF 大纲
    // ================================================================

    /**
     * XHTML 字符串 → 带层级的文本块序列。
     *
     * <p>抽成独立方法（而不是塞在 {@link #parse} 里）是为了<b>可测试</b>：
     * 这是整条解析链路里最绕的一步（PDF 大纲的两种嵌套形状、表格行拼接、
     * 行首模式判定都在这里），而验证它的唯一办法原本是「生成一份 PDF 再解析」——
     * 那样既慢又只能覆盖生成器碰巧产出的形状。
     *
     * <p>抽出来之后，测试可以直接喂手工构造的 XHTML 字符串，
     * 把两种大纲形状、各种畸形结构逐一钉死。包级可见（不用 private）
     * 就是为了让同包的测试能直接调用。
     *
     * @param xhtml     Tika 产出的 XHTML
     * @param fileName  仅用于异常信息
     * @param mimeType  Tika 识别出的 MIME。<b>决定要不要按 PDF 规则找大纲</b>
     */
    List<TextBlock> extractBlocks(String xhtml, String fileName, String mimeType) {
        Element body;
        try {
            // ★ 不能用 XML 解析器（Jsoup.parse(xhtml, "", Parser.xmlParser())）：
            //   Tika 的 PDF 大纲输出不是良构 XML（嵌套 <ul> 没有正确的父子闭合），
            //   XML 解析器会直接抛异常。宽容的 HTML 解析器才能吃下它
            body = Jsoup.parse(xhtml).body();
        } catch (RuntimeException e) {
            throw new DocumentParseException(fileName, "XHTML 结构解析失败：" + e.getMessage(), e);
        }

        // ★ 这两个集合必须一起产出：outline 是「标题文本→层级」的映射，
        //   outlineElements 是「哪些 DOM 元素本身是大纲、不要当正文走」。
        //   只拿前者不拿后者的话，大纲会被当正文再遍历一遍，
        //   每个标题都会在正文里重复出现一次
        Map<String, Integer> outline = new LinkedHashMap<>();
        Set<Element> outlineElements = new LinkedHashSet<>();
        collectPdfOutline(body, mimeType, outline, outlineElements);

        List<TextBlock> blocks = new ArrayList<>();
        walk(body, blocks, outline, outlineElements);
        return blocks;
    }

    /**
     * 从 {@code <body>} 的直接子元素里收集 PDF 大纲，产出「标题文本 → 层级」映射。
     *
     * <p><b>怎么认出这是大纲而不是正文里的列表？</b>
     * 靠 MIME 类型判断：<b>PDF 格式本身没有「列表」这个概念</b>，
     * 它的正文画的全是定位文本，Tika 永远不会为 PDF 正文生成 {@code <ul>}。
     * 所以「MIME 是 PDF」+「{@code <body>} 下有 {@code <ul>}」同时成立时，
     * 那个 {@code <ul>} 必然是大纲。这个判据在 PDF 上是可靠的。
     *
     * <p>（Word 的正文列表也会生成 {@code <ul>}，所以这个判据<b>不能</b>推广到
     * 其他格式 —— 那正是这里要按 MIME 分流的原因。）
     */
    private void collectPdfOutline(Element body, String mimeType,
                                   Map<String, Integer> outline, Set<Element> outlineElements) {
        if (!MIME_PDF.equals(mimeType)) {
            return;
        }
        for (Element child : body.children()) {
            if (isListTag(child)) {
                walkOutline(child, 1, outline);
                // ★ 记下这个元素，遍历正文时跳过它。
                //   不记的话大纲会被当正文再走一遍，每个标题重复出现两次
                outlineElements.add(child);
            }
        }
        if (!outline.isEmpty()) {
            log.debug("PDF 大纲解析出 {} 个标题", outline.size());
        }
    }

    /**
     * 探测输入流是不是空的，<b>且不消耗它的内容</b>。
     *
     * <p>做法是 mark → 读 1 字节 → reset，把位置退回去。
     * 这要求流支持 mark/reset，而 {@code FileInputStream} 恰好<b>不支持</b>
     * （它没法往回退，因为底层是操作系统的文件指针），
     * 所以先套一层 {@link java.io.BufferedInputStream}。
     *
     * <p>注意这里套的这层包装<b>不会被关闭</b> —— 关掉它会连带关掉调用方的流。
     * 这不会造成资源泄漏：{@code BufferedInputStream} 自身不持有任何操作系统句柄，
     * 真正的文件句柄在调用方的流上，由调用方负责关。
     */
    private static boolean isEmpty(InputStream in) {
        try {
            // ⚠️ 这里【不再包装】——传进来的必须已经是支持 mark 的流。
            //    再包一层会造成「缓冲区预读的数据被丢弃」的问题，
            //    详见 parse() 里的说明
            in.mark(1);
            boolean empty = in.read() == -1;
            in.reset();
            return empty;
        } catch (IOException e) {
            // 探测失败不当成「空文件」——那会掩盖真正的 IO 问题。
            // 放行让后面的 Tika 去处理，它会给出更准确的错误
            return false;
        }
    }

    /** {@code FileInputStream} 不支持 mark/reset，套一层缓冲区补上这个能力 */
    private static InputStream ensureMarkSupported(InputStream in) {
        return in.markSupported() ? in : new BufferedInputStream(in);
    }

    /**
     * 递归走大纲树。每层 {@code <ul>} 的嵌套深度就是标题层级。
     *
     * <p><b>★ 嵌套列表有两种形状，都必须处理</b>
     *
     * <p>Tika 生成的 PDF 大纲 HTML <b>不是良构的</b>，同样的层级关系会输出成两种不同的嵌套形态：
     *
     * <pre>
     *   形状 A：嵌套 &lt;ul&gt; 在 &lt;li&gt; 【里面】
     *     &lt;ul&gt;&lt;li&gt;七天无理由退货规则
     *       &lt;ul&gt;&lt;li&gt;一、适用范围&lt;/li&gt;&lt;/ul&gt;
     *     &lt;/ul&gt;
     *
     *   形状 B：嵌套 &lt;ul&gt; 是同级 &lt;li&gt; 的【兄弟】
     *     &lt;ul&gt;&lt;li&gt;星辰 X1 智能手机用户手册&lt;/li&gt;
     *       &lt;ul&gt;&lt;li&gt;产品简介&lt;/li&gt;
     *           &lt;li&gt;外观与按键&lt;/li&gt;&lt;/ul&gt;
     *     &lt;/ul&gt;
     * </pre>
     *
     * <p>两种形状的差别只在 {@code </li>} 闭合的位置 —— 形状 B 里它出现在嵌套
     * {@code <ul>} 之前，于是浏览器/jsoup 把嵌套列表解析成了兄弟节点而不是子节点。
     *
     * <p><b>这个坑是实测踩出来的，而且极具迷惑性</b>：第一版只处理了形状 A，
     * 结果一份 PDF 正常（运气好，正好是形状 A），另一份的标题层级<b>整个丢失</b>——
     * 而且不报错，只是退化成「一个标题 + 定长切分」。
     * 症状是检索质量变差，从日志里完全看不出原因。
     */
    private void walkOutline(Element list, int level, Map<String, Integer> outline) {
        for (Element child : list.children()) {
            if ("li".equals(child.tagName())) {
                // ★ 用 ownText() 而不是 text()：text() 会把嵌套子列表的文字
                //   也拼进来，于是所有标题会变成 "七天无理由退货规则 一、适用范围 …" 这一长串
                String title = normalize(child.ownText());
                if (!title.isEmpty()) {
                    // putIfAbsent：同名标题取先出现的层级。
                    // 覆盖会导致层级在文档中途跳变，切分边界反而更乱
                    outline.putIfAbsent(title, level);
                }
                // 形状 A
                for (Element nested : child.children()) {
                    if (isListTag(nested)) {
                        walkOutline(nested, level + 1, outline);
                    }
                }
            } else if (isListTag(child)) {
                // 形状 B
                walkOutline(child, level + 1, outline);
            }
        }
    }

    // ================================================================
    // ③ DOM 遍历
    // ================================================================

    /**
     * 深度优先遍历 DOM，产出有序的文本块序列。
     *
     * @param outlineElements 需要当作「大纲」跳过、不当正文的元素集合
     */
    private void walk(Element parent, List<TextBlock> out,
                      Map<String, Integer> outline, Set<Element> outlineElements) {
        for (Element el : parent.children()) {
            if (outlineElements.contains(el)) {
                continue;
            }
            String tag = el.tagName();
            if (SKIP_TAGS.contains(tag)) {
                continue;
            }
            // Tika 用 <div class="embedded"> 标记嵌入资源（如 docx 里的缩略图）。
            // 它的文字是资源路径，不是正文，混进去会变成垃圾切片
            if (el.hasClass("embedded")) {
                continue;
            }

            int headingLevel = headingLevelOf(tag);
            if (headingLevel > 0) {
                // 策略①：标签名直接给出层级（Word / HTML 走这条）
                addLines(out, el.wholeText(), outline, headingLevel);
            } else if ("table".equals(tag)) {
                collectTable(el, out);
            } else if (hasBlockChild(el)) {
                // 是容器（如 <div class="page">），继续往下钻
                walk(el, out, outline, outlineElements);
            } else {
                // 叶子文本节点
                addLines(out, el.wholeText(), outline, 0);
            }
        }
    }

    /** 表格：每一行拼成一行文本，单元格之间用 " | " 分隔 */
    private void collectTable(Element table, List<TextBlock> out) {
        for (Element tr : table.select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.children()) {
                String tag = cell.tagName();
                if (!"td".equals(tag) && !"th".equals(tag)) {
                    continue;
                }
                String text = normalize(cell.wholeText());
                if (!text.isEmpty()) {
                    cells.add(text);
                }
            }
            if (!cells.isEmpty()) {
                // ★ 用 standaloneBody 而不是 body：表格的每一行是一个
                //   自包含的记录，不该和相邻行合并进同一个切片。
                //
                //   不这么做的后果是实测看到的：20 行 FAQ 被合并成 2 个切片，
                //   每片混了十几条互不相关的问答，向量变成十几个主题的平均值，
                //   检索精度被稀释 —— 而且不报错，只是答案质量莫名地差。
                //   详见 TextBlock.standalone 的说明
                out.add(TextBlock.standaloneBody(String.join(" | ", cells)));
            }
        }
    }

    // ================================================================
    // 策略③：按行判断标题
    // ================================================================

    /**
     * 把一段文本按换行拆成行，逐行判断是标题还是正文，追加到结果里。
     *
     * @param forcedLevel 由标签名强制指定的层级（策略①）。
     *                    <b>0 表示「标签没给出层级，请按行内容自己判断」</b>
     */
    private void addLines(List<TextBlock> out, String rawText,
                          Map<String, Integer> outline, int forcedLevel) {
        if (rawText == null || rawText.isEmpty()) {
            return;
        }
        for (String rawLine : rawText.split("\n")) {
            String line = normalize(rawLine);
            if (line.isEmpty()) {
                continue;
            }

            if (forcedLevel > 0) {
                out.add(TextBlock.heading(forcedLevel, line));
                continue;
            }

            Integer fromOutline = outline.get(line);
            if (fromOutline != null) {
                // 策略②：这一行恰好等于大纲里的某个标题
                out.add(TextBlock.heading(fromOutline, line));
                continue;
            }

            HeadingGuess guess = guessHeading(line);
            if (guess != null) {
                // 策略③：行首模式。
                // ★ 用 guess.text() 而不是 line —— Markdown 的 "#" 必须剥掉，
                //   否则它会一路写进 kb_chunk.heading_path，变成 "售后政策 > # 退货"
                out.add(TextBlock.heading(guess.level(), guess.text()));
            } else {
                out.add(TextBlock.body(line));
            }
        }
    }

    /**
     * 策略③ 猜出来的标题。
     *
     * <p>之所以要连文本一起返回，而不是只返回层级：<b>猜出层级的同时往往也要改写文本</b>。
     * Markdown 的 {@code "## 适用范围"} 层级来自 {@code #} 的个数，
     * 但真正该入库的标题文本是 {@code "适用范围"}。
     * 如果只返回层级、让调用方沿用原行，那两个 {@code #} 就会跟着进数据库。
     */
    private record HeadingGuess(int level, String text) {
    }

    /**
     * 按行首模式猜标题。猜不出来返回 {@code null}。
     *
     * <p><b>这是启发式，有误判可能</b>，所以加了两道保险：
     * <ol>
     *   <li><b>模式本身写得保守</b>：{@code NUM_DOTTED} 要求必须带小数点
     *       （挡掉「1. 序言」这种有序列表），{@code CN_NUMBER} 要求序号后面
     *       紧跟顿号（挡掉「三年、五年保修」这种以数字开头的正文）。</li>
     *   <li><b>长度限制</b>：超过 {@value #MAX_HEADING_CHARS} 字的行，
     *       不再用上面这些有歧义的模式去判。真实标题都很短，
     *       而「1.5 米长的数据线支持七天无理由退货」这类正文句子一旦长了就被挡住。</li>
     * </ol>
     *
     * <p>⚠️ <b>两道保险的顺序很重要</b>：Markdown 的 {@code #} 模式<b>没有歧义</b>
     * （正文不会以 {@code "# "} 开头），所以它放在长度检查之前，不受限制；
     * 有歧义的数字前缀模式才受长度检查约束。
     *
     * <p>即便如此仍有残余误判风险。代价可控：误判只会多切一刀
     * （把一个正文段落开头当标题），<b>不会丢内容</b>。
     * 阶段 7 可以量化它对检索指标的实际影响，再决定要不要收紧。
     */
    private HeadingGuess guessHeading(String line) {
        // ★ Markdown 的 # 判断放在长度保险【之前】，因为它不需要保险：
        //   正文里不可能有一行是以 "# " 开头的（那不是正文的写法）。
        //   而下面几个数字前缀的模式是有歧义的，才需要长度限制兜底。
        //   如果把长度检查放最前面，一个 40 字的 Markdown 标题就会被漏掉 ——
        //   而标题长度本来就不该有限制。
        Matcher md = MD_HEADING.matcher(line);
        if (md.matches()) {
            // 捕获组 1 = # 的个数 = 层级；组 2 = 剥掉 # 之后的标题文本
            return new HeadingGuess(md.group(1).length(), md.group(2).trim());
        }

        // 以下模式有误判风险，用长度上限兜底
        if (line.length() > MAX_HEADING_CHARS) {
            return null;
        }
        // 中文章节号统一按二级。PDF/Word 里它们通常就是章节级
        if (CN_CHAPTER.matcher(line).matches()) {
            return new HeadingGuess(2, line);
        }
        if (CN_NUMBER.matcher(line).matches()) {
            return new HeadingGuess(2, line);
        }
        // 多级数字编号按三级：它一般是章节下的小节
        if (NUM_DOTTED.matcher(line).matches()) {
            return new HeadingGuess(3, line);
        }
        return null;
    }

    // ================================================================
    // 小工具
    // ================================================================

    private static boolean isListTag(Element el) {
        String tag = el.tagName();
        return "ul".equals(tag) || "ol".equals(tag);
    }

    private static int headingLevelOf(String tag) {
        if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') {
            return tag.charAt(1) - '0';
        }
        return 0;
    }

    /** 元素里有没有块级子元素。有 → 它是容器，要继续往下钻 */
    private static boolean hasBlockChild(Element el) {
        for (Element child : el.children()) {
            if (BLOCK_TAGS.contains(child.tagName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一化空白：把连续空白（含换行、制表）压成一个空格，再去首尾。
     *
     * <p><b>为什么必须做这一步</b>：标题匹配是「正文行 == 大纲标题」的精确比较，
     * 而 PDF 大纲里的文字和正文里的文字可能因为换行位置不同而带不同的空白。
     * 两边都归一化之后比较才可靠。
     */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Tika 拿不到元数据时会填 "(anonymous)" 之类的占位值，这些要当 null 处理 */
    private static String cleanTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return TIKA_PLACEHOLDERS.contains(trimmed) ? null : trimmed;
    }
}
