package com.xbla.rag.rag.tokenize;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 双字（bigram）切分 —— 本项目的中文分词实现。
 *
 * <h2>一、为什么是「两个字」而不是「三个字」</h2>
 *
 * <p>中文词的平均长度是 2 个字（「退货」「电池」「优惠券」），
 * 所以 <b>2 字窗口正好对齐词的粒度</b>。
 *
 * <p>3 字窗口（也就是 PostgreSQL 自带的 {@code pg_trgm}）看起来只差一个字，
 * 实际差得很远 —— 实测查询「退货要几天」产生的 6 个三元组里：
 *
 * <pre>
 *   退货要   货要几   要几天     ← 文本中间可能出现
 *   "  退"   " 退货"   "几天 "   ← 首尾补空格产生的，在长文本里【永远不可能出现】
 * </pre>
 *
 * <p>一半的三元组结构性地不可能命中，所以 {@code pg_trgm} 的
 * {@code word_similarity} 在真实语料上给 4 条正确切片打出<b>完全相同的 0.3333</b>，
 * 没有任何排序能力。只能当二元过滤器用。
 *
 * <p>而 RRF 融合只取<b>排名</b>不取<b>分数</b>，并列时的顺序等于「按 id 排」——
 * 语义上毫无意义，等于把关键词这一路废掉。这就是必须换 2 字窗口的原因。
 *
 * <h2>二、★ 核心规则：词元只在「连续同类字符段」内部生成，永不跨段</h2>
 *
 * <p>「段」= 被分隔符（空白、标点、以及 <b>CJK↔ASCII 的切换</b>）切开的、
 * 最长的同类字符序列。段有三类处理方式：
 *
 * <pre>
 *   段类型              处理                          例子
 *   ─────────────────────────────────────────────────────────────────
 *   CJK（长度 ≥ 2）     切 2-gram                     退货要几天 → 退货 货要 要几 几天
 *   CJK（长度 = 1）     ★ 丢弃                         日 → （无）
 *   ASCII / 数字        整体保留为一个词元（转小写）      X1 → x1、5000mAh → 5000mah
 *   ASCII（长度 = 1）   ★ 丢弃                         7 → （无）
 *   跨段                不生成任何词元
 * </pre>
 *
 * <h3>★ 为什么单字词元一律丢弃</h3>
 *
 * <p>这是一条<b>被实测数据推着改</b>的规则。最初的设计是「保留单字，丢字会丢信息」，
 * 在全量语料上回填之后统计才发现单字词元几乎全是<b>结构性噪音</b>：
 *
 * <pre>
 *   裸单字词元      出现条数（全库 1652 条）   来源
 *   ─────────────────────────────────────────────────────────
 *   '元'               192                  「4000 到 6000 元价位段」—— 被空格隔开的量词
 *   '年'               159                  「2026 年」—— 同上
 *   '2'                166                  「2.1 有效期」—— 小节号
 *   '1'                 96                  同上
 *   '一' / '二' / '三'   19                  章节序号
 *   '战'                27                  「联想 战 66 Pro」—— 商品名里的单字
 * </pre>
 *
 * <p>它们有两个共同特征，决定了保留它们得不偿失：
 *
 * <ol>
 *   <li><b>频率极高</b> —— 「元」出现在 12% 的切片里。
 *       而 OR 查询里每多一个高频词元，候选集就膨胀一圈；</li>
 *   <li><b>区分度为零</b> —— 一个字符无法把任何两个切片区分开。
 *       检索的价值在于「筛掉大部分」，单字做不到这一点。</li>
 * </ol>
 *
 * <p>丢弃它们的代价是：查询里若含被空格孤立的单字，那个字不参与关键词召回
 * （向量那一路仍然覆盖）。而如果查询<b>只</b>由单字构成，会得到零个词元 ——
 * 这种情况由调用方短路处理，见 {@link SearchText#orQuery()}。
 *
 * <p>这条规则也让分词器的契约变得非常干脆：<b>词元至少两个字符</b>。
 *
 * <h3>为什么「不跨段」这一条这么重要</h3>
 *
 * <p>起初的规则是「丢弃含空格的 bigram」，但实测证明那只是<b>症状不是根因</b>，
 * 而且规则本身有漏洞 —— 它拦不住 {@code 5000mAh}：
 * 逐字符切 2-gram 会得到 {@code 50 00 0m ma ah}，<b>每一个都不含空格</b>，
 * 那条规则完全看不见它们。
 *
 * <p>真正的机制是：跨段切出来的词元
 * （比如 {@code 星辰X1 电池} 里的 {@code "1 "}）
 * 写进 {@code search_text} 之后，{@code to_tsvector} 再解析时会把它们
 * <b>退化成裸的单字词元</b>：
 *
 * <pre>
 *   SELECT to_tsvector('simple', '星辰 辰X X1 1 电 电池');
 *   →  '1':4 'x1':3 '星辰':1 '电':5 '电池':6 '辰x':2      ← 裸单字 1 和 电 出现了
 *
 *   SELECT to_tsvector('simple', '星辰 辰X X1 电池');
 *   →  'x1':3 '星辰':1 '电池':4 '辰x':2                    ← 干净
 * </pre>
 *
 * <p>裸单字在中文里是<b>最高频词元</b>，OR 起来候选集直接爆炸：
 * 实测查询「星辰X1 电池」的候选集从 3.8% 涨到 <b>36.6%</b>。
 *
 * <p>按「段内切」的规则，这个查询产出的词元是
 * {@code 星辰 辰x x1 电池} —— 没有任何裸单字。
 *
 * <h3>为什么 ASCII 段整体保留、不切 2-gram</h3>
 *
 * <p>型号和单位是「词」不是「字」：{@code X1} 切成 {@code x1} 是有意义的词元，
 * 而 {@code 5000mAh} 切成 {@code 50 00 0m ma ah} 则是一堆碎片，
 * 既污染索引又让「星辰X1」这类型号查询的候选集无谓地变大。
 * 整体保留还带来一个额外好处：查询写 {@code 星辰X1}、
 * 文档写 {@code 星辰 X1}（中间有空格）时，两边的 {@code x1} 词元照样对得上。
 *
 * <h2>三、★ 保留重复词元（不做去重）</h2>
 *
 * <p>输出里<b>会保留重复的词元</b>，因为重复就是词频，而词频是
 * {@code ts_rank} <b>唯一的排序信号</b>。这一点最初被我判断错了 ——
 * 当时的理由是「倒排索引只关心有没有这个词，词频是噪声」，
 * 于是加了去重。全量回填后实测才发现：
 *
 * <pre>
 *   查询「退货要几天」命中 43 条切片，统计不同分值的个数：
 *     去重版   →  1 种分值      ← 排序退化成「按 id 排」，语义上毫无意义
 *     不去重版 →  8 种分值      ← 正解「自签收之日起 7 天内」排第 3
 * </pre>
 *
 * <p>根因：{@code ts_rank} 对 <b>OR 查询</b>取的是「单个词元得分的<b>最大值</b>」，
 * 而单词语元的得分只取决于词频。去重让频率恒为 1，所有命中同一个词元的切片
 * 就拿到完全相同的分数。（{@code ts_rank_cd} 也一样，实测同样全部并列 0.1。）
 *
 * <p>而 RRF 融合只取<b>排名</b>不取分数 —— 顺序一旦退化成「按 id 排」，
 * 关键词这一路就等于废掉了。
 *
 * <p>教训：「词频是噪声」这个直觉和 {@code ts_rank} 的实际算法是相反的。
 * <b>在替换掉一个排序信号之前，先确认它是不是那个算法唯一的输入。</b>
 *
 * <h2>四、安全边界</h2>
 *
 * <p>产出的词元只含 ASCII 小写字母、数字、CJK 表意文字。
 * 这条约束由 {@link SearchText} 的构造器强制，是<b>防止 tsquery 操作符注入</b>的
 * 结构性保证 —— 详见那个类的注释。
 *
 * <h2>五、纯计算，不依赖 Spring 容器</h2>
 *
 * <p>虽然标了 {@code @Component} 以便注入，但类里<b>没有任何注入点</b>，
 * 所有输入都是方法参数。单测可以直接 {@code new BigramCjkTokenizer()}。
 * （和 {@code HeadingAwareChunker} 同一个路数。）
 *
 * <h2>六、★ 输出不是 token 数</h2>
 *
 * <p>这里切出的「词元」是<b>倒排索引项</b>，不是模型计费的 token。
 * {@code kb_chunk.token_count} 仍然刻意保持 NULL（ADR-010）。
 * <b>任何情况下都不要把 {@link SearchText#size()} 写进 {@code token_count}</b>。
 */
@Component
public class BigramCjkTokenizer implements CjkTokenizer {

    /** 段的字符类别 */
    private enum SegKind {
        /** CJK 表意文字：按 2-gram 切 */
        CJK,
        /** ASCII 字母与数字：整体保留 */
        ALNUM,
        /** 其他一切：段边界，直接丢弃 */
        SEP
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>不截断</b>：一个 500 字的中文切片会产生约 499 个 bigram，
     * 截断到几十个会直接毁掉索引。
     */
    @Override
    public SearchText tokenize(String text) {
        return doTokenize(text, 0);
    }

    /** {@inheritDoc} */
    @Override
    public SearchText tokenizeQuery(String text, int maxTokens) {
        return doTokenize(text, maxTokens);
    }

    /**
     * 切分主流程。
     *
     * <p>按<b>码点</b>而不是 {@code char} 遍历：CJK 扩展区的汉字
     * （如「𠀋」U+2000B）在 Java 里占两个 {@code char}（代理对），
     * 按 {@code char} 切会把一个汉字劈成两半，产出无法阅读的乱码词元。
     */
    private SearchText doTokenize(String text, int maxTokens) {
        if (text == null || text.isBlank()) {
            return SearchText.empty();
        }

        List<String> tokens = new ArrayList<>();

        int i = 0;
        int n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            SegKind kind = classify(cp);
            if (kind == SegKind.SEP) {
                i += Character.charCount(cp);
                continue;
            }

            // 收集一个「连续同类字符段」
            StringBuilder seg = new StringBuilder();
            while (i < n) {
                int c = text.codePointAt(i);
                if (classify(c) != kind) {
                    break;
                }
                seg.appendCodePoint(c);
                i += Character.charCount(c);
            }

            emitSegment(seg.toString(), kind, tokens);
        }

        SearchText result = new SearchText(tokens);
        return maxTokens > 0 ? result.limit(maxTokens) : result;
    }

    /**
     * 把一个段变成词元。
     *
     * @param segment 同类字符组成的连续段，<b>不会为空</b>
     */
    private void emitSegment(String segment, SegKind kind, List<String> tokens) {
        if (kind == SegKind.ALNUM) {
            // ★ 单字符 ASCII 段丢弃（如小节号「2.1」里的 2 和 1，实测出现在 10% 的切片里）
            if (segment.length() < 2) {
                return;
            }
            // ASCII/数字段整体保留。转小写是为了和 to_tsvector 的归一化对齐，
            // 让「X1」和「x1」在 tsvector 里合并成同一个词元、频率叠加。
            // ★ 必须用 Locale.ROOT：土耳其语环境下 'I'.toLowerCase() 会得到 'ı'
            //   （无点的 i），同一个词在不同机器上会变成不同的词元
            tokens.add(segment.toLowerCase(Locale.ROOT));
            return;
        }

        // CJK 段：按码点切 2-gram。
        // ★ 单字符段直接丢弃 —— 「元」「年」「一」这类被排版空格孤立的字，
        //   实测出现在 12% / 10% 的切片里，是高频无区分度的噪音。
        //   完整理由见类注释里的实测数据表。
        int[] cps = segment.codePoints().toArray();
        for (int j = 0; j + 1 < cps.length; j++) {
            tokens.add(new String(Character.toChars(cps[j]))
                    + new String(Character.toChars(cps[j + 1])));
        }
    }

    private static SegKind classify(int cp) {
        if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9')) {
            return SegKind.ALNUM;
        }
        return switch (Character.UnicodeScript.of(cp)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> SegKind.CJK;
            default -> SegKind.SEP;
        };
    }
}
