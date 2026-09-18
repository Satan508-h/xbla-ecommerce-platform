package com.xbla.rag.rag.chunk;

import com.xbla.rag.rag.parse.TextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HeadingAwareChunker} 的测试。
 *
 * <p><b>为什么这个测试值得写得这么细</b>
 *
 * <p>切分是整个 RAG 链路里<b>最容易悄悄出错</b>的一环。写错了：
 * <ul>
 *   <li>不会抛异常</li>
 *   <li>不会写错数据（切片照样入库、照样有向量）</li>
 *   <li>检索照样能返回结果</li>
 * </ul>
 * 唯一的症状是<b>答案质量莫名其妙地差</b>，而且很难归因 ——
 * 你没法从日志里看出「这个切片跨越了两个章节」。
 *
 * <p>所以边界条件必须在这里钉死。纯计算测试，不起 Spring，毫秒级。
 */
@DisplayName("HeadingAwareChunker —— 标题层级 + 定长 + 重叠窗口")
class HeadingAwareChunkerTest {

    private final HeadingAwareChunker chunker = new HeadingAwareChunker();

    /** 测试用参数：窗口 100、重叠 20、最短 30、整段阈 140 */
    private static TextChunkingOptions options() {
        return new TextChunkingOptions(100, 20, 30, 140, "。！？；\n", true);
    }

    /** 不带标题前缀的参数，方便断言纯正文 */
    private static TextChunkingOptions noHeadingOption() {
        return new TextChunkingOptions(100, 20, 30, 140, "。！？；\n", false);
    }

    private static TextBlock h(int level, String text) {
        return TextBlock.heading(level, text);
    }

    private static TextBlock b(String text) {
        return TextBlock.body(text);
    }

    /** 生成一段指定长度的中文文字，以句号结尾 */
    private static String text(int length) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length) {
            sb.append("这是一句用于测试的中文内容。");
        }
        return sb.substring(0, length);
    }

    /**
     * 生成一段<b>每句都唯一</b>的中文文本，共 {@code sentences} 句。
     *
     * <p><b>为什么重叠测试必须用这种文本</b>：验证重叠的做法是
     * 「取第一片的结尾，断言它出现在第二片里」。如果文本是
     * 「这是一句用于测试的中文内容。」这种反复重复的句子，
     * 那么第一片的结尾本来就满篇都是 —— 断言恒成立，
     * <b>无论重叠有没有生效</b>。
     *
     * <p>这个坑是实测踩出来的：第一版测试就是这么写的，
     * 结果是「关闭重叠」那条用例失败，而失败原因和被测代码毫无关系。
     * 测试数据没有区分力，测试就等于没写。
     */
    private static String distinctText(int sentences) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences; i++) {
            sb.append("第").append(i).append("句的内容在此。");
        }
        return sb.toString();
    }

    // ================================================================
    // ① 按标题切段
    // ================================================================

    @Nested
    @DisplayName("① 按标题切段：不同章节的内容绝不混进同一个切片")
    class SectionSplitting {

        @Test
        @DisplayName("两个章节 → 两个切片，各自带上自己的标题路径")
        void twoSections() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    h(1, "售后政策"),
                    b("本政策适用于自营商品。"),
                    h2("换货"),
                    b("换货需要联系客服。")
            ), noHeadingOption(), null);

            assertThat(chunks).hasSize(2);
            assertThat(chunks.get(0).headingPath()).isEqualTo("售后政策");
            assertThat(chunks.get(0).content()).isEqualTo("本政策适用于自营商品。");
            assertThat(chunks.get(1).headingPath()).isEqualTo("售后政策 > 换货");
            assertThat(chunks.get(1).content()).isEqualTo("换货需要联系客服。");
        }

        private static TextBlock h2(String text) {
            return h(2, text);
        }

        @Test
        @DisplayName("★ 层级回退：h3 之后遇到 h2，路径要正确弹出中间层级")
        void hierarchyPopsBack() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    h(1, "售后政策"),
                    h(2, "退货"),
                    h(3, "七天无理由"),
                    b("自营商品签收后七天内可申请。"),
                    h(2, "换货"),                       // ★ 回到二级，应该弹掉 h3 和 h2
                    b("换货需联系客服。")
            ), noHeadingOption(), null);

            assertThat(chunks).extracting(TextChunk::headingPath).containsExactly(
                    "售后政策 > 退货 > 七天无理由",
                    "售后政策 > 换货");     // ← 没有残留的「退货 > 七天无理由」
        }

        @Test
        @DisplayName("★ 切片不跨越章节边界（本类最重要的不变量）")
        void neverCrossesSectionBoundary() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    h(1, "退货"),
                    b("退货需要保持商品完好。"),
                    h(1, "发货"),
                    b("发货时效为四十八小时。")
            ), options(), null);

            // 每个切片只应该包含自己章节的内容。
            // 跨章节的切片意味着前面识别标题的功夫全白费了
            for (TextChunk chunk : chunks) {
                boolean hasReturn = chunk.content().contains("退货");
                boolean hasShipping = chunk.content().contains("发货时效");
                assertThat(hasReturn && hasShipping)
                        .as("切片同时包含两个章节的内容：%s", chunk.content())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("连续多个正文块合并进同一个 section")
        void consecutiveBodyBlocksMerge() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    h(1, "标题"),
                    b("第一段。"),
                    b("第二段。"),
                    b("第三段。")
            ), noHeadingOption(), null);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).content()).isEqualTo("第一段。\n第二段。\n第三段。");
        }

        @Test
        @DisplayName("文档标题作为路径第一层（正文没有 h1 时）")
        void documentTitleSeedsPath() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    b("没有任何标题的正文。")
            ), noHeadingOption(), "七天无理由退货规则");

            assertThat(chunks.get(0).headingPath()).isEqualTo("七天无理由退货规则");
        }

        @Test
        @DisplayName("★ 文档标题会被真正的 h1 顶掉，不会叠成两层")
        void documentTitleIsReplacedByRealH1() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    h(1, "正文里的标题"),
                    b("正文。")
            ), noHeadingOption(), "元数据里的标题");

            // 如果栈的弹出规则写错（用 > 而不是 >=），这里会变成
            // "元数据里的标题 > 正文里的标题" —— 一个不存在的层级关系
            assertThat(chunks.get(0).headingPath()).isEqualTo("正文里的标题");
        }

        @Test
        @DisplayName("完全没有标题也没有文档标题时，headingPath 是 null 而不是空串")
        void noHeadingYieldsNullPath() {
            List<TextChunk> chunks = chunker.chunk(List.of(b("一段没有标题的正文。")),
                    noHeadingOption(), null);

            // null 和 "" 的区别有业务含义：
            // 运维查询要能写 WHERE heading_path IS NULL 一眼筛出
            // 所有「没识别出结构」的文档
            assertThat(chunks.get(0).headingPath()).isNull();
        }
    }

    // ================================================================
    // ② 定长切分
    // ================================================================

    @Nested
    @DisplayName("② 定长切分")
    class FixedLength {

        @Test
        @DisplayName("整段短于阈值时不切，保持完整")
        void shortSectionIsNotSplit() {
            String body = text(120);   // < mergeThreshold=140

            List<TextChunk> chunks = chunker.chunk(
                    List.of(h(1, "标题"), b(body)), noHeadingOption(), null);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).content()).isEqualTo(body);
        }

        @Test
        @DisplayName("超长段落被切成多片，每片不超过 maxChars")
        void longSectionIsSplit() {
            List<TextChunk> chunks = chunker.chunk(
                    List.of(h(1, "标题"), b(text(1000))), noHeadingOption(), null);

            assertThat(chunks.size()).isGreaterThan(1);
            for (TextChunk chunk : chunks) {
                assertThat(chunk.charCount())
                        .as("切片长度不能超过 maxChars=100：%s", chunk.content())
                        .isLessThanOrEqualTo(100);
            }
        }

        @Test
        @DisplayName("chunk_index 从 0 开始连续递增（阶段 7 取相邻切片靠它）")
        void indexIsSequential() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    h(1, "A"), b(text(300)),
                    h(1, "B"), b(text(300))
            ), noHeadingOption(), null);

            assertThat(chunks).hasSizeGreaterThan(2);
            for (int i = 0; i < chunks.size(); i++) {
                assertThat(chunks.get(i).index()).isEqualTo(i);
            }
        }

        @Test
        @DisplayName("★ 优先断在标点之后，不把句子拦腰截断")
        void breaksAtPunctuation() {
            // 构造一段每 12 字一个句号的文本，窗口 100。
            // 理想情况下每个切片都以「。」结尾
            List<TextChunk> chunks = chunker.chunk(
                    List.of(b(text(500))), noHeadingOption(), null);

            // 最后一片可能不以句号结尾（原文末尾被截断），所以只查前面几片
            for (int i = 0; i < chunks.size() - 1; i++) {
                assertThat(chunks.get(i).content())
                        .as("第 %d 片应该断在标点处", i)
                        .endsWith("。");
            }
        }

        @Test
        @DisplayName("标点离得太远时放弃断句优化，硬切在 maxChars（不能无限回溯）")
        void givesUpWhenPunctuationTooFar() {
            // 一段 300 字没有任何标点的文本。
            // 如果不设回溯上限，会一路退回开头，切出极短的碎片
            String noPunctuation = "无标点文本".repeat(60);   // 300 字，无任何标点

            List<TextChunk> chunks = chunker.chunk(
                    List.of(b(noPunctuation)), noHeadingOption(), null);

            assertThat(chunks).isNotEmpty();
            // 第一片应该接近 maxChars（100），而不是被回溯成很短的一片
            assertThat(chunks.get(0).charCount())
                    .as("没有标点可断时应该硬切在 maxChars 附近")
                    .isGreaterThan(70);
        }
    }

    // ================================================================
    // ③ 重叠窗口
    // ================================================================

    @Nested
    @DisplayName("③ 重叠窗口：答案横跨切分点时靠它救回来")
    class Overlap {

        @Test
        @DisplayName("★ 相邻切片确实有重叠内容")
        void adjacentChunksOverlap() {
            List<TextChunk> chunks = chunker.chunk(
                    List.of(b(distinctText(60))), noHeadingOption(), null);

            assertThat(chunks.size()).isGreaterThan(1);

            String first = chunks.get(0).content();
            String second = chunks.get(1).content();

            // 取出第一片的结尾 20 个字（= overlapChars），它必须出现在第二片里。
            // 这条断言如果不成立，说明重叠窗口没生效 ——
            // 后果是答案横跨切分点时会变成两个半句话，谁都答不出来
            String tail = first.substring(first.length() - 20);
            assertThat(second)
                    .as("第一片的结尾应该出现在第二片里（重叠窗口）")
                    .contains(tail);
        }

        @Test
        @DisplayName("★ 反向验证：关掉重叠后，同样的断言必须失败")
        void withoutOverlapNoDuplication() {
            // ★ 这条用例是上一条的「对照组」，它的价值在于证明上一条断言
            //   【真的有区分力】—— 如果只写「有重叠时包含」，那么当
            //   overlapChars 被误设成 0 时代码照样能过某些宽松的断言。
            //   正反两条一起跑，才能说明重叠参数真的在起作用
            TextChunkingOptions noOverlap =
                    new TextChunkingOptions(100, 0, 30, 140, "。", false);

            List<TextChunk> chunks = chunker.chunk(List.of(b(distinctText(60))), noOverlap, null);

            assertThat(chunks.size()).isGreaterThan(1);
            String first = chunks.get(0).content();
            String second = chunks.get(1).content();
            String tail = first.substring(first.length() - 20);

            assertThat(second).doesNotContain(tail);
        }
    }

    // ================================================================
    // ④ 短切片合并
    // ================================================================

    @Nested
    @DisplayName("④ 过短的尾片并入前一片")
    class ShortTailMerging {

        @Test
        @DisplayName("★ 最后一片过短时并入前一片，不产生噪声切片")
        void shortTailIsMerged() {
            // 窗口 100、重叠 20、步进 80。
            // 520 字的文本会切出 0-100, 80-180, ..., 400-500, 480-520
            // 最后一片只有 40 字 < minChars=30？不，40 > 30。
            // 用 510 字：最后一片是 480-510 = 30 字，正好卡在边界上。
            // 改用 505 字 → 最后一片 480-505 = 25 字 < 30，应该被合并
            List<TextChunk> chunks = chunker.chunk(
                    List.of(b(text(505))), noHeadingOption(), null);

            for (TextChunk chunk : chunks) {
                assertThat(chunk.charCount())
                        .as("不该出现低于 minChars=30 的噪声切片：%s", chunk.content())
                        .isGreaterThanOrEqualTo(30);
            }
        }

        @Test
        @DisplayName("只有一片时即使很短也不合并（没有前一片可并）")
        void singleShortChunkIsKept() {
            List<TextChunk> chunks = chunker.chunk(
                    List.of(h(1, "标题"), b("很短。")), noHeadingOption(), null);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).content()).isEqualTo("很短。");
        }
    }

    // ================================================================
    // ④b standalone 边界（表格行不能和邻居合并）
    // ================================================================

    @Nested
    @DisplayName("★ standalone 块：表格行必须各自成片")
    class StandaloneBlocks {

        @Test
        @DisplayName("★ 每一行表格各自成片，不合并（否则向量变成多主题的平均值）")
        void standaloneRowsBecomeSeparateChunks() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    h(1, "售后FAQ"),
                    TextBlock.standaloneBody("退货要几天 | 签收后七天内 | 退货"),
                    TextBlock.standaloneBody("运费谁承担 | 质量问题平台承担 | 退货"),
                    TextBlock.standaloneBody("怎么开发票 | 下单时选择电子发票 | 发票")
            ), noHeadingOption(), null);

            // ★ 这是实测踩出来的问题：合并成一个切片后，
            //   那个切片的向量是「退货 + 运费 + 发票」三个主题的平均值，
            //   用户问其中任何一个问题，相似度都会被稀释
            assertThat(chunks).hasSize(3);
            assertThat(chunks).extracting(TextChunk::content).containsExactly(
                    "退货要几天 | 签收后七天内 | 退货",
                    "运费谁承担 | 质量问题平台承担 | 退货",
                    "怎么开发票 | 下单时选择电子发票 | 发票");
        }

        @Test
        @DisplayName("普通段落仍然合并（散文需要上下文，不该被拆散）")
        void normalParagraphsStillMerge() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    h(1, "标题"),
                    b("第一段。"),
                    b("第二段。")
            ), noHeadingOption(), null);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).content()).isEqualTo("第一段。\n第二段。");
        }

        @Test
        @DisplayName("★ 混合场景：standalone 块把两侧的普通段落也隔开")
        void standaloneSeparatesNeighborParagraphs() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    TextBlock.body("前言段落。"),
                    TextBlock.standaloneBody("表格行 | 内容"),
                    TextBlock.body("后记段落。")
            ), noHeadingOption(), null);

            // 规则是「standalone 自成一派，两边的普通块也不能和它合并」。
            // 不然表格行会被吸进前言段落那一组，问题又回来了
            assertThat(chunks).hasSize(3);
            assertThat(chunks).extracting(TextChunk::content).containsExactly(
                    "前言段落。", "表格行 | 内容", "后记段落。");
        }

        @Test
        @DisplayName("连续的 standalone 块各自成片，不同组之间不产生重叠")
        void consecutiveStandaloneDoNotOverlap() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    TextBlock.standaloneBody("行一 | 内容甲"),
                    TextBlock.standaloneBody("行二 | 内容乙")
            ), options(), null);

            assertThat(chunks).hasSize(2);
            assertThat(chunks.get(0).content()).doesNotContain("内容乙");
            assertThat(chunks.get(1).content()).doesNotContain("内容甲");
        }

        @Test
        @DisplayName("standalone 块保留标题路径前缀")
        void standaloneKeepsHeadingPath() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    h(1, "售后FAQ"),
                    TextBlock.standaloneBody("退货要几天 | 七天内")
            ), options(), null);

            assertThat(chunks.get(0).headingPath()).isEqualTo("售后FAQ");
            assertThat(chunks.get(0).content()).isEqualTo("售后FAQ\n退货要几天 | 七天内");
        }
    }

    // ================================================================
    // ⑤ 标题前缀装饰
    // ================================================================

    @Nested
    @DisplayName("⑤ 标题路径拼进正文（提升检索命中率）")
    class HeadingDecoration {

        @Test
        @DisplayName("★ 标题路径出现在正文最前面 —— 「退货」二字只在标题里有")
        void headingIsPrependedToContent() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    h(1, "退货政策"),
                    b("签收后七天内可申请。")
            ), options(), null);

            // ★ 这条断言是「为什么要把标题拼进正文」的直接证据：
            //   用户问「退货政策是什么」，正文里根本没有「退货」二字，
            //   标题不参与向量化的话，这个切片跟问题匹配不上
            assertThat(chunks.get(0).content())
                    .isEqualTo("退货政策\n签收后七天内可申请。");
        }

        @Test
        @DisplayName("关掉开关后正文里不含标题（阶段 7 的 A/B 对比维度）")
        void decorationCanBeDisabled() {
            List<TextChunk> chunks = chunker.chunk(List.of(
                    h(1, "退货政策"),
                    b("签收后七天内可申请。")
            ), noHeadingOption(), null);

            assertThat(chunks.get(0).content()).isEqualTo("签收后七天内可申请。");
        }

        @Test
        @DisplayName("没有标题路径时不加前缀，不会拼出一个空行开头")
        void noPathMeansNoPrefix() {
            List<TextChunk> chunks = chunker.chunk(List.of(b("正文。")), options(), null);

            assertThat(chunks.get(0).content()).isEqualTo("正文。");
        }
    }

    // ================================================================
    // ⑥ 参数校验与边界
    // ================================================================

    @Nested
    @DisplayName("⑥ 参数校验：死循环防线")
    class Validation {

        @Test
        @DisplayName("★ overlapChars ≥ maxChars 时必须拒绝 —— 否则滑动窗口步进为 0 会死循环")
        void rejectsOverlapNotSmallerThanMaxChars() {
            // 这个参数组合的后果是：窗口永远停在原地，while 循环永不结束。
            // 现象是「入库任务卡住、CPU 打满、没有任何日志输出」——
            // 在死循环里 debug 的成本极高，所以必须在构造期拦住
            assertThatThrownBy(() -> new TextChunkingOptions(100, 100, 30, 140, "。", true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("死循环");

            assertThatThrownBy(() -> new TextChunkingOptions(100, 150, 30, 140, "。", true))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("step() 恒为正数")
        void stepIsAlwaysPositive() {
            assertThat(new TextChunkingOptions(100, 20, 30, 140, "。", true).step()).isEqualTo(80);
            assertThat(new TextChunkingOptions(100, 0, 30, 140, "。", true).step()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("⑦ 输入边界")
    class InputEdgeCases {

        @Test
        @DisplayName("空列表返回空结果，不抛异常")
        void emptyBlocks() {
            assertThat(chunker.chunk(List.of(), options(), null)).isEmpty();
            assertThat(chunker.chunk(null, options(), null)).isEmpty();
        }

        @Test
        @DisplayName("只有标题没有正文时不产生切片（不硬造空切片）")
        void headingWithoutBody() {
            List<TextChunk> chunks = chunker.chunk(List.of(h(1, "空章节")), options(), null);

            assertThat(chunks).isEmpty();
        }

        @Test
        @DisplayName("★ 超长文本能正常切完，不会死循环（回归测试）")
        void veryLongTextTerminates() {
            // 这条测试的价值在于「能跑完」本身。
            // 如果滑动窗口的步进算错，它会挂住直到测试超时，
            // 而不是断言失败 —— 这也是一种明确的失败信号
            List<TextBlock> blocks = new ArrayList<>();
            blocks.add(h(1, "超大章节"));
            blocks.add(b(text(50_000)));

            List<TextChunk> chunks = chunker.chunk(blocks, options(), null);

            assertThat(chunks).hasSizeGreaterThan(100);
            assertThat(chunks.get(chunks.size() - 1).index()).isEqualTo(chunks.size() - 1);
        }
    }
}
