package com.xbla.rag.rag.tokenize;

import java.util.List;

/**
 * 一段文本分词后的结果 —— 词元列表，以及由它派生出的两种字符串形态。
 *
 * <p>之所以把「词元列表」而不是「拼好的字符串」作为唯一的真相来源：
 * 入库要的是空格分隔的词元串（喂给 {@code to_tsvector}），
 * 检索要的是 {@code |} 分隔的查询串（喂给 {@code to_tsquery}）。
 * 两份字符串各存一份迟早会不一致，派生出来就不会。
 *
 * @param tokens 词元，<b>保留重复</b>，顺序为原文出现顺序。
 *
 *               <h3>★ 为什么不能去重 —— 一个被实测纠正的设计错误</h3>
 *
 *               <p>最初这里做过去重，理由是「倒排索引只关心有没有这个词，
 *               词频是噪声」。在全量回填后实测发现<b>这个理由是错的</b>：
 *
 *               <pre>
 *                 查询「退货要几天」命中 43 条切片，统计不同分值的个数：
 *                   去重版   →  1 种分值      ← 完全没有排序能力
 *                   不去重版 →  8 种分值      ← 正解「七天无理由退货政策
 *                                               自签收之日起 7 天内」排第 3
 *               </pre>
 *
 *               <p>根因在 PostgreSQL 的 {@code ts_rank}：
 *               对 <b>OR 查询</b>它取的是「单个词元得分的<b>最大值</b>」，
 *               而单词语元的得分<b>只取决于词频</b>。
 *               去重让每个词元的频率恒为 1，于是所有命中同一个词的切片
 *               拿到完全相同的分数 —— 排序退化成「按 id 排」，语义上毫无意义。
 *
 *               <p>（{@code ts_rank_cd} 也一样，实测同样全部并列 0.1。）
 *
 *               <p>教训：<b>「词频是噪声」这个直觉，和 {@code ts_rank} 的实际算法相反。</b>
 *               在替换掉一个排序信号之前，要先确认它是那个排序算法唯一的输入。
 */
public record SearchText(List<String> tokens) {

    /**
     * ★ 词元的合法字符集。<b>这是一条安全边界，不是格式洁癖。</b>
     *
     * <p>{@link #orQuery()} 的输出会被直接拼进 {@code to_tsquery(...)}，
     * 而 {@code to_tsquery} 把 {@code + & ! : ( ) * < >} 当作<b>操作符</b>解析。
     * 用户输入 {@code A&B} 或型号 {@code C++} 会产生含操作符的词元，
     * 拼出来的查询串会让 PostgreSQL 直接抛语法错误，<b>整条关键词召回挂掉</b>。
     *
     * <p>在构造器里把这条约束变成<b>结构性的不变量</b>，就不需要在拼查询串时
     * 做转义（转义是那种「漏了一个字符就出事」的防御，不可靠）。
     *
     * <p>反过来说：只要有词元能构造出来，它拼进 tsquery 就一定是安全的。
     *
     * <p>允许的字符：ASCII 小写字母、数字、CJK 表意文字（汉字 / 假名 / 谚文）。
     */
    public SearchText {
        if (tokens == null) {
            tokens = List.of();
        } else {
            for (String token : tokens) {
                requireSafeToken(token);
            }
            tokens = List.copyOf(tokens);
        }
    }

    /** 空结果 */
    public static SearchText empty() {
        return new SearchText(List.of());
    }

    /**
     * 空格分隔的词元串 —— 写进 {@code kb_chunk.search_text} 的形态。
     *
     * <p>空格是必须的：{@code to_tsvector('simple', ...)} 正是靠空白和标点
     * 把文本切成词元的。不加空格的话整串又会粘成一个词元。
     */
    public String tokenText() {
        return String.join(" ", tokens);
    }

    /**
     * {@code |} 分隔的查询串 —— 喂给 {@code to_tsquery} 的形态。
     *
     * <p><b>必须是 OR 不是 AND。</b>{@code plainto_tsquery} 生成的是 AND 语义，
     * 中文查询「退货要几天」会被拆成 {@code 退 & 货 & 要 & 几 & 天}，
     * 要求五个字全部出现在同一个切片里 —— 实测命中 <b>0 行</b>。
     * 必须手工拼 {@code |}，再靠 {@code ts_rank} 排序。
     *
     * <p>★ 调用方必须先检查 {@link #isEmpty()}。实测
     * {@code to_tsquery('simple', '')} <b>不抛异常</b>，只发一个 NOTICE，
     * 返回空 tsquery 而 {@code @@} 恒为 false —— <b>静默返回 0 行</b>，
     * 和「真的没有匹配」完全无法区分。这是比抛异常更危险的行为。
     */
    public String orQuery() {
        return String.join(" | ", tokens);
    }

    /** 词元数量 */
    public int size() {
        return tokens.size();
    }

    /** 没有任何词元。★ 调用方据此短路，见 {@link #orQuery()} */
    public boolean isEmpty() {
        return tokens.isEmpty();
    }

    /** 截取前 n 个词元；n 小于等于 0 或超过总数时返回自身 */
    public SearchText limit(int n) {
        if (n <= 0 || tokens.size() <= n) {
            return this;
        }
        return new SearchText(tokens.subList(0, n));
    }

    /**
     * 校验单个词元只含合法字符。
     *
     * <p>用逐字符判断而不是正则：这段代码在入库时会对每个切片的上百个词元
     * 各跑一次，正则的编译和匹配开销在这里是白花的。
     * （同理见 {@code DigestUtil.toHex} 里不用 {@code String.format} 的注释。）
     */
    private static void requireSafeToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("词元不能为 null 或空串");
        }
        for (int i = 0; i < token.length(); ) {
            int cp = token.codePointAt(i);
            boolean ok = (cp >= 'a' && cp <= 'z')
                    || (cp >= '0' && cp <= '9')
                    || isCjk(cp);
            if (!ok) {
                throw new IllegalArgumentException(
                        "词元含非法字符 " + new String(Character.toChars(cp))
                                + "（码点 " + cp + "），词元=" + token
                                + "。只允许 [a-z0-9] 与 CJK —— "
                                + "这是防止 tsquery 操作符注入的安全边界，见 SearchText 类注释");
            }
            i += Character.charCount(cp);
        }
    }

    /**
     * CJK 表意文字：汉字、日文假名、韩文谚文。
     *
     * <p>三者的检索粒度都是「字」而不是「词」，所以走同一套 bigram 切分。
     *
     * <p>⚠️ 反过来说，<b>不在这里的书写系统会被当作分隔符丢弃</b> ——
     * 西里尔字母、希腊字母、带重音的拉丁字母（{@code é}）都不进索引。
     * 对本项目的中文电商语料没有影响；若将来要支持其他语言，
     * 应该给它们各自的「段类型」（按词切分，而不是按字切分），
     * 而不是简单地塞进这个判断里。
     */
    private static boolean isCjk(int cp) {
        return switch (Character.UnicodeScript.of(cp)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> true;
            default -> false;
        };
    }

    @Override
    public String toString() {
        return "SearchText(" + size() + " 个词元) " + String.join(" ", tokens);
    }
}
