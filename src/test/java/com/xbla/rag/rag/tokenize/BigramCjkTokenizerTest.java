package com.xbla.rag.rag.tokenize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 中文分词器的单元测试。
 *
 * <p>纯计算测试，不起 Spring，毫秒级 —— 直接 {@code new} 被测对象。
 *
 * <p>本类里有<b>两组正-反对照</b>（用 {@code // ★ 对照组} 标出）。
 * 对照组的价值：证明正向断言<b>真的有区分力</b>，
 * 而不是一条恒真的废话。这是本项目测试的硬性风格，
 * 起因是阶段 3 踩过一次「测试数据本身没有区分力导致断言恒真」的坑。
 */
@DisplayName("BigramCjkTokenizer · 中文双字切分")
class BigramCjkTokenizerTest {

    private final BigramCjkTokenizer tokenizer = new BigramCjkTokenizer();

    /** 取词元列表，省得每处都写 .tokens() */
    private List<String> toks(String text) {
        return tokenizer.tokenize(text).tokens();
    }

    // ============================================================
    // 一、基本切分
    // ============================================================

    @Nested
    @DisplayName("一、基本切分")
    class Basic {

        @Test
        @DisplayName("纯中文：2 字窗口滑动，n 个字产出 n-1 个 bigram")
        void pureChinese() {
            assertThat(toks("退货要几天"))
                    .as("「退货要几天」5 个字应产出 4 个 bigram")
                    .containsExactly("退货", "货要", "要几", "几天");
        }

        @Test
        @DisplayName("标点会被切断：逗号两侧不产生跨标点的 bigram")
        void punctuationBreaksSegments() {
            // 「退货，怎么办」如果没有标点，会产出「货怎」这种跨语义边界的噪音
            assertThat(toks("退货，怎么办"))
                    .as("逗号应切断段，「货怎」不应出现")
                    .containsExactly("退货", "怎么", "么办")
                    .doesNotContain("货怎");
        }

        @Test
        @DisplayName("★ 单字词元一律丢弃 —— 实测全是高频无区分度的排版噪音")
        void singleCharSegmentsDropped() {
            assertThat(toks("日")).as("单个汉字不产生任何词元").isEmpty();
            assertThat(toks("7")).as("单个数字（小节号「2.1」里就有）").isEmpty();
            assertThat(toks("A 货 B")).as("被空格孤立的单字全部丢弃").isEmpty();

            // ★ 实测证据（全库 1652 条切片回填后统计）：
            //     '元' 出现在 192 条（12%）—— 多来自行尾的「- 价格：5999 元」
            //     '年' 出现在 159 条（10%）—— 来自行尾的「- 上市年份：2026 年」
            //     '2'  出现在 166 条（10%）—— 来自小节号「2.1 有效期」
            //   一个字符无法把任何两个切片区分开，保留它们只会让 OR 查询的候选集膨胀。
            //
            //   ⚠️ 注意「孤立」的定义：单字段是被分隔符【两侧夹住】的。
            //      "5999 元" 里的「元」在行尾 → 孤立；
            //      "6000 元价位段" 里的「元」后面紧跟着汉字 → 它和「价位段」
            //      属于【同一个 CJK 段】，根本不会单独成为词元。
            assertThat(toks("5999 元")).as("行尾的「元」是独立的单字段，丢弃")
                    .containsExactly("5999");
            assertThat(toks("2026 年")).as("行尾的「年」同理").containsExactly("2026");
            assertThat(toks("6000 元价位段"))
                    .as("这里的「元」没有孤立，它和「价位段」同段，产出 元价/价位/位段")
                    .containsExactly("6000", "元价", "价位", "位段");
        }

        @Test
        @DisplayName("单字被丢弃不影响真正的词元：同一切片的其余内容照常成词元")
        void droppingSingleCharsKeepsRealTokens() {
            assertThat(toks("上市年份：2026 年"))
                    .as("行尾的「年」被丢弃，但「上市」「市年」「年份」「2026」都还在 —— 信息没有实质损失")
                    .containsExactly("上市", "市年", "年份", "2026");
        }

        @Test
        @DisplayName("空输入：null / 空串 / 全空白 / 全标点 都返回空结果，不抛异常")
        void emptyInputs() {
            assertThat(toks(null)).isEmpty();
            assertThat(toks("")).isEmpty();
            assertThat(toks("   ")).isEmpty();
            assertThat(toks("，。！？")).as("纯标点产出 0 个词元").isEmpty();
            assertThat(tokenizer.tokenize(null).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("★ 保留重复词元 —— 重复就是词频，而词频是 ts_rank 唯一的排序信号")
        void duplicatesPreserved() {
            // 「退货退货」是一个 4 字 CJK 段 → bigram: 退货 / 货退 / 退货
            assertThat(toks("退货退货"))
                    .as("「退货」出现两次就要保留两次。★ 最初这里做过去重，"
                            + "理由是「倒排索引只关心有没有这个词」—— "
                            + "实测证明那是错的：去重让词频恒为 1，"
                            + "而 ts_rank 对 OR 查询取的是「单词元得分的最大值」，"
                            + "于是 43 条命中切片拿到【同一个】分数，排序退化成按 id 排")
                    .containsExactly("退货", "货退", "退货");
        }

        @Test
        @DisplayName("★ 对照：去重后词元种类数不变，但总数变了 —— 那正是 ts_rank 唯一的输入")
        void dedupWouldDestroyFrequency() {
            List<String> kept = toks("七天无理由退货政策 退货");
            List<String> deduped = kept.stream().distinct().toList();

            assertThat(kept).as("不去重：退货 出现两次")
                    .hasSize(deduped.size() + 1)
                    .filteredOn("退货"::equals)
                    .as("词频信息就藏在这个重复里")
                    .hasSize(2);
            assertThat(deduped)
                    .as("★ 对照组：去重后「退货」只剩一个，频率信息彻底消失")
                    .filteredOn("退货"::equals)
                    .hasSize(1);
        }

        @Test
        @DisplayName("★ 按码点切分：CJK 扩展区汉字是代理对，不能被劈成两半")
        void supplementaryPlane() {
            // U+2000B / U+2000C 是 CJK 扩展 B 区的汉字，各占 2 个 Java char
            String twoExtChars = new String(Character.toChars(0x2000B))
                    + new String(Character.toChars(0x2000C));

            List<String> result = toks(twoExtChars);

            assertThat(result).as("两个字应产出 1 个 bigram").hasSize(1);
            assertThat(result.get(0))
                    .as("bigram 必须由两个完整码点组成；若按 char 遍历会得到半个汉字组成的乱码")
                    .isEqualTo(twoExtChars);
        }

        @Test
        @DisplayName("假名与谚文也按 bigram 切（同属表意文字，检索粒度都是「字」）")
        void otherCjkScripts() {
            assertThat(toks("カタカナ")).as("日文片假名").containsExactly("カタ", "タカ", "カナ");
            assertThat(toks("한국어")).as("韩文谚文").containsExactly("한국", "국어");
        }
    }

    // ============================================================
    // 二、★ 不跨段 —— 本类最核心的规则
    // ============================================================

    @Nested
    @DisplayName("二、★ 不跨段（36.6% 噪音的根因）")
    class NoCrossSegment {

        @Test
        @DisplayName("型号混排：ASCII 段整体保留，CJK↔ASCII 的交界处不产生 bigram")
        void alnumKeptWhole() {
            // ★ 注意「辰x」不在结果里：辰 是 CJK、X 是 ASCII，
            //   两者的交界是一条段边界，按规则不生成任何词元
            assertThat(toks("星辰X1 电池"))
                    .as("CJK 段切 bigram，ASCII 段整体保留，跨段交界不生成")
                    .containsExactly("星辰", "x1", "电池")
                    .doesNotContain("辰x");
        }

        @Test
        @DisplayName("★ 对照组：朴素的跨段切分确实会产出会被 to_tsvector 退化成裸单字的词元")
        void naiveCrossSegmentProducesNoise() {
            // 对照组 = 本项目最初的实现：对**原始文本**（不剥空白）无脑滑 2 字窗口。
            // 这正是 36.6% 那个实测数据的来源。
            List<String> naive = naiveCrossSegmentBigrams("星辰X1 电池");

            assertThat(naive)
                    .as("对照组证明：跨段切分产出了含空白的词元 \"1 \" 和 \" 电\"")
                    .contains("1 ")
                    .contains(" 电");

            // ★ 而问题真正出在下一步：这些词元写进 search_text 后，
            //   to_tsvector 会把它们退化成【裸的单字词元】。这一条在 Java 侧测不了，
            //   是在真库上实测的（见 BigramCjkTokenizer 类注释与 V6 迁移注释）：
            //       SELECT to_tsvector('simple','星辰 辰X X1 1 电 电池');
            //       →  '1':4 'x1':3 '星辰':1 '电':5 '电池':6 '辰x':2
            //   裸单字在中文里是最高频词元，OR 起来候选集从 3.8% 涨到 36.6%。
            assertThat(naive)
                    .as("对照组的词元含有会被退化的形态——这正是正式实现要避免的")
                    .anyMatch(t -> t.contains(" "));

            assertThat(toks("星辰X1 电池"))
                    .as("★ 正式实现里既没有含空白的词元，也没有裸单字 1 / 电")
                    .doesNotContain("1 ")
                    .doesNotContain(" 电")
                    .doesNotContain("1")
                    .doesNotContain("电")
                    .allMatch(t -> !t.contains(" "));
        }

        @Test
        @DisplayName("单位不被切碎：5000mAh 整体成一个词元")
        void unitNotShredded() {
            assertThat(toks("电池容量5000mAh"))
                    .as("ASCII 段整体保留")
                    .contains("5000mah")
                    .as("逐字符切会产生这些碎片，一个都不该有")
                    .doesNotContain("50")
                    .doesNotContain("0m")
                    .doesNotContain("ma")
                    .doesNotContain("mah");
        }

        @Test
        @DisplayName("★ 对照组：同一个输入，朴素实现会把 5000mAh 切成碎片")
        void naiveShredsUnit() {
            assertThat(naiveCrossSegmentBigrams("电池容量5000mAh"))
                    .as("这条对照证明上一条的 doesNotContain 断言【真的有区分力】—— "
                            + "朴素的「丢弃含空格 bigram」规则拦不住这些碎片，因为它们不含空格")
                    .contains("50")
                    .contains("0m");
        }

        @Test
        @DisplayName("空格不影响型号匹配：查询写 星辰X1、文档写 星辰 X1，x1 词元仍对得上")
        void spaceDoesNotBreakModelMatch() {
            assertThat(toks("星辰X1")).contains("x1", "星辰");
            assertThat(toks("星辰 X1")).contains("x1", "星辰");
        }

        @Test
        @DisplayName("大小写归一：X1 与 x1 是同一个词元")
        void caseNormalized() {
            assertThat(toks("X1")).isEqualTo(toks("x1"));
        }
    }

    // ============================================================
    // 三、★ 安全边界：词元里不能出现 tsquery 操作符
    // ============================================================

    @Nested
    @DisplayName("三、★ 安全边界（tsquery 操作符注入）")
    class Safety {

        @Test
        @DisplayName("型号里的 + & : ( ) 会被当成段边界丢弃，不会进入词元")
        void operatorsNeverBecomeTokens() {
            // 这些字符在 to_tsquery 里全是操作符，混进查询串会让 PostgreSQL 抛语法错误。
            // ★ 注意本组输入里的字母都是【单字符段】，按「单字丢弃」规则也不产出词元 ——
            //   所以这里的「不含操作符」是双重保证的。真正独立的一条是下面的 (测试)。
            assertThat(toks("A&B")).as("& 是分隔符，a 和 b 都是单字被丢弃").isEmpty();
            assertThat(toks("a:b")).isEmpty();
            assertThat(toks("A|B")).isEmpty();
            assertThat(toks("a!b")).isEmpty();
            assertThat(toks("C++")).as("'+' 是分隔符，c 是单字被丢弃").isEmpty();

            assertThat(toks("(测试)")).as("括号丢弃，「测试」是双字保留").containsExactly("测试");
            assertThat(toks("退货&政策"))
                    .as("操作符夹在两个双字段之间，它自己消失、两段各自成词元")
                    .containsExactly("退货", "政策");
        }

        @Test
        @DisplayName("★ orQuery() 里只可能出现「我们自己插入的 |」，用户输入的操作符一个都进不来")
        void orQueryIsSafe() {
            // ★ 这里不能简单断言「orQuery 不含 |」—— 那个 | 正是我们用来连接词元的操作符，
            //   那种断言恒为假，测了等于没测。要断言的是【用户输入的操作符没有变成词元】，
            //   所以比对精确串。
            assertThat(tokenizer.tokenizeQuery("退货&政策", 64).orQuery())
                    .as("输入的 & 消失，两段之间的 | 是我们自己加的")
                    .isEqualTo("退货 | 政策");
            assertThat(tokenizer.tokenizeQuery("退货|换货", 64).orQuery())
                    .as("输入的 | 被当成普通分隔符丢弃 —— 它不会变成词元")
                    .isEqualTo("退货 | 换货");
            assertThat(tokenizer.tokenizeQuery("退货+政策", 64).orQuery()).isEqualTo("退货 | 政策");
            assertThat(tokenizer.tokenizeQuery("退货!政策", 64).orQuery()).isEqualTo("退货 | 政策");
            assertThat(tokenizer.tokenizeQuery("退货:政策", 64).orQuery()).isEqualTo("退货 | 政策");
            assertThat(tokenizer.tokenizeQuery("(测试)", 64).orQuery()).isEqualTo("测试");
        }

        @Test
        @DisplayName("★ 对照：含操作符的输入若不做清洗，拼进 to_tsquery 会直接语法报错")
        void unsanitizedQueryWouldBreakTsquery() {
            // 对照组：直接拿用户输入原样拼 OR 链（本项目【不做】这件事）。
            // 说明为什么必须清洗 —— 这条不写，上面那条 doesNotContain 就没有说服力。
            String raw = "退货&政策";
            String unsanitized = raw.replace(" ", " | ");

            assertThat(unsanitized)
                    .as("原样拼出的查询串含操作符 &，PostgreSQL 会报 syntax error in tsquery")
                    .contains("&");

            assertThat(tokenizer.tokenizeQuery(raw, 64).orQuery())
                    .as("清洗后的查询串里只有 | 这一个操作符")
                    .doesNotContain("&")
                    .doesNotContain("+")
                    .doesNotContain("!")
                    .doesNotContain(":")
                    .doesNotContain("(");
        }

        @Test
        @DisplayName("SearchText 构造器是最后一道闸：非法词元直接抛异常")
        void searchTextRejectsUnsafeToken() {
            assertThatThrownBy(() -> new SearchText(List.of("退货&")))
                    .as("containing '&'")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法字符");
            assertThatThrownBy(() -> new SearchText(List.of("退货 政策")))
                    .as("containing a space")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SearchText(List.of("AAA")))
                    .as("containing uppercase")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ============================================================
    // 四、查询串与入库串
    // ============================================================

    @Nested
    @DisplayName("四、两种输出形态")
    class OutputForms {

        @Test
        @DisplayName("tokenText() 用空格分隔（喂 to_tsvector）")
        void tokenText() {
            assertThat(tokenizer.tokenize("退货要几天").tokenText())
                    .isEqualTo("退货 货要 要几 几天");
        }

        @Test
        @DisplayName("★ orQuery() 用 | 分隔（OR 语义），不能用 & 或 plainto_tsquery")
        void orQuery() {
            assertThat(tokenizer.tokenizeQuery("退货要几天", 64).orQuery())
                    .as("必须是 | —— plainto_tsquery 的 AND 语义实测命中 0 行")
                    .isEqualTo("退货 | 货要 | 要几 | 几天");
        }

        @Test
        @DisplayName("空格是必须的：不加空格的词元串会被 to_tsvector 粘成一个词元")
        void tokenTextMustBeSpaceSeparated() {
            SearchText st = tokenizer.tokenize("退货要几天");
            assertThat(st.tokenText()).contains(" ");
            assertThat(st.tokenText().split(" ")).hasSize(4);
        }
    }

    // ============================================================
    // 五、截断：查询截断，文档不截断
    // ============================================================

    @Nested
    @DisplayName("五、截断策略")
    class Truncation {

        @Test
        @DisplayName("★ 文档不截断：500 字切片的所有 bigram 都要入库")
        void documentNotTruncated() {
            // ★ 必须用【互不相同】的字：bigram 是去重收集的，
            //   重复文本（如 "退货政策".repeat(120)）去重后只剩 4 个词元，
            //   断言会失败在一个和被测逻辑完全无关的地方
            String longText = distinctCjk(480);
            SearchText all = tokenizer.tokenize(longText);

            assertThat(all.size())
                    .as("480 个互不相同的字应产出 479 个互不相同的 bigram；"
                            + "截断到几十个会毁掉索引 —— 文档侧必须全量")
                    .isEqualTo(479)
                    .isGreaterThan(64);
        }

        @Test
        @DisplayName("查询截断到上限：超长问题不会拼出上千项的 OR 链")
        void queryTruncated() {
            String longQuery = distinctCjk(800);
            assertThat(tokenizer.tokenizeQuery(longQuery, 64).size())
                    .as("ChatAskRequest 允许 2000 字的问题，必须能截断")
                    .isEqualTo(64);
            assertThat(tokenizer.tokenizeQuery(longQuery, 0).size())
                    .as("上限 <= 0 表示不限制")
                    .isEqualTo(799);
        }

        @Test
        @DisplayName("短查询不受上限影响")
        void shortQueryUnaffected() {
            assertThat(tokenizer.tokenizeQuery("退货要几天", 64).size()).isEqualTo(4);
        }

        @Test
        @DisplayName("★ 零词元时必须能被识别出来，调用方据此短路")
        void emptyResultIsDetectable() {
            SearchText st = tokenizer.tokenizeQuery("，。！？", 64);
            assertThat(st.isEmpty())
                    .as("★ 实测 to_tsquery('simple','') 不抛异常，只静默返回 0 行 —— "
                            + "所以必须在 Java 侧靠这个判断短路掉关键词检索")
                    .isTrue();
            assertThat(st.orQuery()).isEmpty();
        }
    }

    // ============================================================
    // 对照组用的朴素实现
    // ============================================================

    /**
     * 朴素的跨段 bigram 实现 —— <b>故意保留的错误版本</b>，只用于对照测试。
     *
     * <p>它模拟<b>本项目最初的实现</b>：对原始文本（<b>不剥离空白</b>）
     * 无脑滑 2 字窗口。这正是 36.6% 那个实测噪声数据的来源 ——
     * 它会对 {@code 星辰X1 电池} 产出 {@code "1 "} 和 {@code " 电"} 这种
     * 含空白的词元，而 {@code to_tsvector} 会把它们退化成裸的单字词元。
     *
     * <p>留在这里是为了让正向断言<b>可证伪</b> —— 否则
     * 「不含裸单字」这种断言永远为真，测了等于没测。
     * （这是本项目测试的硬性风格，起因是阶段 3 踩过「断言恒真」的坑。）
     */
    private static List<String> naiveCrossSegmentBigrams(String text) {
        List<String> out = new ArrayList<>();
        int[] cps = text.codePoints().toArray();
        for (int i = 0; i + 1 < cps.length; i++) {
            out.add(new String(Character.toChars(cps[i]))
                    + new String(Character.toChars(cps[i + 1])));
        }
        return out;
    }

    /**
     * 生成 n 个互不相同、且两两相邻组成的 bigram 也互不相同的汉字。
     *
     * <p>为什么不能用 {@code "退货政策".repeat(120)} 这种重复文本 ——
     * bigram 是<b>去重</b>的，重复文本只能产出极少数几个词元，
     * 于是「长度大于 64」这类断言会失败在一个和被测逻辑无关的地方。
     * <b>测试数据本身必须有区分力</b>，这是阶段 3 记录过的坑。
     *
     * <p>从 CJK 统一汉字区（U+4E00）起连续取 n 个码点：
     * 相邻两个字组成的 bigram 天然互不相同。
     */
    private static String distinctCjk(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.appendCodePoint(0x4E00 + i);
        }
        return sb.toString();
    }
}
