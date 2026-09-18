package com.xbla.rag.rag.parse;

/**
 * 解析产物的最小单位：一个「文本块」。
 *
 * <p><b>为什么要有这个中间层，而不是直接给一段纯文本？</b>
 *
 * <p>因为「标题层级 + 定长 + 重叠窗口」这个切分策略需要知道<b>哪些行是标题</b>。
 * 如果解析阶段只吐一段纯文本，切分阶段就只能靠正则去猜，
 * 而它已经拿不到「这个词是 Word 里的一级标题样式」这种结构化信息了 ——
 * 信息在解析阶段被丢掉，下游再也找不回来。
 *
 * <p>所以解析层的职责是：<b>把四种格式（PDF/Word/MD/Excel）千差万别的结构
 * 归一化成同一种「带层级的文本块序列」</b>，切分层的逻辑就只需要面对这一种形态。
 *
 * <p>举例 —— 下面四种输入最终都会产出同构的 {@code List<TextBlock>}：
 * <pre>
 *   Word   &lt;h1&gt;七天无理由退货规则&lt;/h1&gt;&lt;p&gt;本规则适用于…&lt;/p&gt;
 *   PDF    [大纲] 七天无理由退货规则(1级)  +  [正文] 七天无理由退货规则\n本规则适用于…
 *   MD     # 七天无理由退货规则\n\n本规则适用于…
 *   Excel  &lt;h1&gt;售后FAQ&lt;/h1&gt;&lt;table&gt;…
 *                       ↓ 解析层归一化
 *   [ (1,"七天无理由退货规则"), (0,"本规则适用于…") ]
 * </pre>
 *
 * @param level 层级。<b>0 表示正文段落</b>，1~6 表示标题层级（对应 HTML 的 h1~h6）。
 *              用 0 而不是 null 表示正文，是为了让下游可以直接比较大小，
 *              不用到处判空。
 * @param text  文本内容，已做首尾去空白。保证非空白字符串 ——
 *              解析层负责过滤掉空块，下游不用再判
 * @param standalone ★ <b>这一块能否和相邻块合并进同一个切片</b>。
 *
 *              <p>{@code false}（默认）表示「可以合并」—— 连续的散文段落
 *              合并成一个切片是好事，上下文更完整。
 *
 *              <p>{@code true} 表示「不要合并」—— 用在<b>表格行</b>上。
 *              以售后 FAQ 表为例，每一行是「问题 | 答案 | 分类」，
 *              它本身就是一个自包含的记录，和「章节」是同一量级的结构单位。
 *
 *              <p><b>不区分这一点的后果是实测看到的</b>：20 行 FAQ 被合并成
 *              2 个切片，每片混了十几条互不相关的问答。那个切片的向量是
 *              十几个主题的「平均值」—— 用户问「退货要几天」时，
 *              相似度被稀释，检索精度明显下降。
 *              而且<b>不会报错</b>，只是答案质量莫名地差。
 *
 *              <p>刻意用布尔标记而不是「把表格行伪装成标题」之类的技巧：
 *              后者会让 {@code heading_path} 里出现「售后FAQ &gt; 第 3 行」
 *              这种毫无意义的内容，还会被展示到引用来源里给用户看。
 *              <b>标记要表达真实语义，不能为了绕过一个实现细节而编造结构。</b>
 */
public record TextBlock(int level, String text, boolean standalone) {

    /** 正文段落的层级常量。写作 {@code TextBlock.BODY} 比写 {@code 0} 好读 */
    public static final int BODY = 0;

    /** 标题最大层级。超过 6 级的（罕见）一律压到 6 */
    public static final int MAX_LEVEL = 6;

    public TextBlock {
        if (level < BODY || level > MAX_LEVEL) {
            throw new IllegalArgumentException("标题层级必须在 0~6 之间，实际是 " + level);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文本块内容不能为空");
        }
    }

    /** 是不是标题 */
    public boolean isHeading() {
        return level > BODY;
    }

    /** 是不是正文段落 */
    public boolean isBody() {
        return level == BODY;
    }

    /** 正文块。<b>可以和相邻块合并</b>（散文段落用这个） */
    public static TextBlock body(String text) {
        return new TextBlock(BODY, text, false);
    }

    /** 标题块 */
    public static TextBlock heading(int level, String text) {
        return new TextBlock(Math.min(Math.max(level, 1), MAX_LEVEL), text, false);
    }

    /**
     * 自包含正文块：<b>不要和相邻块合并进同一个切片</b>。
     *
     * <p>用在表格行上。详见 {@link #standalone} 的说明。
     */
    public static TextBlock standaloneBody(String text) {
        return new TextBlock(BODY, text, true);
    }
}
