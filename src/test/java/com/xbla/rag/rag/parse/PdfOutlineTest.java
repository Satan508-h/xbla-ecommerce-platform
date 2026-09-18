package com.xbla.rag.rag.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 大纲提取的专项测试 —— 直接喂手工构造的 XHTML，不经过 Tika。
 *
 * <h2>★ 为什么必须把它单独拆出来测</h2>
 *
 * <p>因为 <b>Tika 生成的 PDF 大纲 HTML 不是良构的</b>，同样的层级关系会输出成
 * 两种不同的嵌套形态，而它们只差一个 {@code </li>} 的位置：
 *
 * <pre>
 *   形状 A：嵌套 &lt;ul&gt; 在 &lt;li&gt;【里面】
 *     &lt;ul&gt;&lt;li&gt;七天无理由退货规则
 *       &lt;ul&gt;&lt;li&gt;一、适用范围&lt;/li&gt;&lt;/ul&gt;
 *     &lt;/ul&gt;
 *
 *   形状 B：嵌套 &lt;ul&gt; 是同级 &lt;li&gt; 的【兄弟】
 *     &lt;ul&gt;&lt;li&gt;星辰 X1 智能手机用户手册&lt;/li&gt;
 *       &lt;ul&gt;&lt;li&gt;产品简介&lt;/li&gt;&lt;/ul&gt;
 *     &lt;/ul&gt;
 * </pre>
 *
 * <p><b>这个坑真实地发生过</b>：第一版实现只处理了形状 A。
 * 结果两份 PDF 里有一份完全正常、另一份的标题层级<b>整个丢失</b> ——
 * 而且不报错，只是悄悄退化成「一个标题 + 定长切分」，
 * 表现为检索质量变差，从日志里完全看不出原因。
 *
 * <p>如果只靠「生成 PDF 再解析」来测，那么覆盖到哪种形状<b>取决于生成器碰巧
 * 产出哪一种</b> —— 生成器换个库、换个版本就可能变。
 * 直接喂字符串才能把两种形状都钉死。
 *
 * <p>顺带一提：{@code shouldSkipOutline} 那条断言（大纲不能重复进正文）
 * 也是同一类问题 —— 它不会报错，只会让每个标题在切片里出现两次。
 */
@DisplayName("PDF 大纲提取 —— 两种嵌套形状")
class PdfOutlineTest {

    private final TikaDocumentParser parser = new TikaDocumentParser();

    private static final String MIME_PDF = "application/pdf";

    private List<TextBlock> parse(String bodyHtml) {
        return parser.extractBlocks("<html><body>" + bodyHtml + "</body></html>", "t.pdf", MIME_PDF);
    }

    private static List<String> headings(List<TextBlock> blocks) {
        return blocks.stream()
                .filter(TextBlock::isHeading)
                .map(b -> b.level() + ":" + b.text())
                .toList();
    }

    // ================================================================
    // 形状 A：嵌套 <ul> 在 <li> 里面
    // ================================================================

    @Test
    @DisplayName("★ 形状 A：嵌套 <ul> 闭合在 <li> 内部")
    void shapeA_nestedInsideLi() {
        List<TextBlock> blocks = parse("""
                <div class="page">
                  <p>七天无理由退货规则</p>
                  <p>本规则适用于自营商品。</p>
                  <p>一、适用范围</p>
                  <p>手机类商品适用。</p>
                </div>
                <ul><li>七天无理由退货规则
                  <ul><li>一、适用范围</li></ul>
                </li></ul>
                """);

        assertThat(headings(blocks)).containsExactly(
                "1:七天无理由退货规则",
                "2:一、适用范围");
    }

    // ================================================================
    // 形状 B：嵌套 <ul> 是 <li> 的兄弟 —— ★ 这一条是回归测试
    // ================================================================

    @Test
    @DisplayName("★★ 形状 B：嵌套 <ul> 闭合在 <li> 之后（兄弟节点）—— 回归测试")
    void shapeB_nestedAsSibling() {
        // ★ 这就是让标题层级【整个丢失】的那种形状。
        //   注意 </li> 出现在嵌套 <ul> 之前 —— 只差这一个字符，
        //   但 jsoup/浏览器会把它解析成兄弟节点而不是子节点
        List<TextBlock> blocks = parse("""
                <div class="page">
                  <p>星辰 X1 智能手机用户手册</p>
                  <p>产品简介</p>
                  <p>星辰 X1 是旗舰智能手机。</p>
                  <p>外观与按键</p>
                  <p>机身右侧是音量键。</p>
                </div>
                <ul><li>星辰 X1 智能手机用户手册</li>
                <ul><li>产品简介</li>
                    <li>外观与按键</li>
                </ul>
                </ul>
                """);

        assertThat(headings(blocks))
                .as("形状 B 下嵌套层级必须被递归进去，否则 5 个标题只剩 1 个")
                .containsExactly(
                        "1:星辰 X1 智能手机用户手册",
                        "2:产品简介",
                        "2:外观与按键");
    }

    @Test
    @DisplayName("形状 B 的多层嵌套（三级）")
    void shapeB_threeLevels() {
        List<TextBlock> blocks = parse("""
                <div class="page">
                  <p>手册</p>
                  <p>第一章</p>
                  <p>1.1 节</p>
                </div>
                <ul><li>手册</li>
                <ul><li>第一章</li>
                <ul><li>1.1 节</li></ul>
                </ul>
                </ul>
                """);

        assertThat(headings(blocks)).containsExactly(
                "1:手册",
                "2:第一章",
                "3:1.1 节");
    }

    // ================================================================
    // 大纲与正文的关系
    // ================================================================

    @Test
    @DisplayName("★ 大纲本身不能进正文（否则每个标题在切片里出现两次）")
    void outlineIsNotPartOfBody() {
        List<TextBlock> blocks = parse("""
                <div class="page"><p>产品简介</p><p>正文内容。</p></div>
                <ul><li>产品简介</li></ul>
                """);

        long count = blocks.stream().filter(b -> "产品简介".equals(b.text())).count();
        assertThat(count)
                .as("大纲被当正文重复遍历了 —— 每个标题会入库两次")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ 非 PDF 格式的 <ul> 是正文列表，不能当大纲吃掉")
    void nonPdfListsAreContent() {
        // Word 的正文列表也会生成 <ul>，但它是内容不是大纲。
        // 判据是 MIME —— PDF 格式本身没有「列表」概念，
        // 它的 <ul> 必然是大纲；别的格式的 <ul> 是内容
        List<TextBlock> blocks = parser.extractBlocks("""
                <html><body>
                <div><p>购物流程</p>
                <ul><li>加入购物车</li><li>去结算</li><li>支付</li></ul>
                </div>
                </body></html>
                """, "t.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        assertThat(blocks.stream().map(TextBlock::text))
                .as("Word 的正文列表必须被保留")
                .contains("加入购物车", "去结算", "支付");
    }

    @Test
    @DisplayName("大纲标题文本和正文不一致时，正文那行不会被误判成标题")
    void outlineOnlyMatchesExactText() {
        List<TextBlock> blocks = parse("""
                <div class="page">
                  <p>产品简介</p>
                  <p>本节介绍产品简介中提到的内容。</p>
                </div>
                <ul><li>产品简介</li></ul>
                """);

        // 第二行包含「产品简介」四个字但不是精确相等，不该被判成标题
        assertThat(blocks.stream().filter(TextBlock::isHeading).map(TextBlock::text))
                .containsExactly("产品简介");
    }

    @Test
    @DisplayName("PDF 正文里没有标点断行的长段，不会因为大纲存在而被切碎")
    void bodyWithoutHeadingsStaysBody() {
        List<TextBlock> blocks = parse("""
                <div class="page"><p>一段没有任何标题的正文。</p></div>
                <ul><li>某个标题</li></ul>
                """);

        assertThat(blocks.stream().map(TextBlock::text)).contains("一段没有任何标题的正文。");
    }
}
