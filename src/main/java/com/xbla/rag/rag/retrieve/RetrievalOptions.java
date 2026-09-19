package com.xbla.rag.rag.retrieve;

import java.util.List;

/**
 * 一次召回的<b>范围声明</b> —— 「这次要在哪些 {@code doc_type} 里找，最多要几条」。
 *
 * <h2>一、为什么要有这个类型，而不是多加一个 {@code List<Integer>} 参数</h2>
 *
 * <p>因为「<b>没有声明</b>」和「<b>声明了一个空集</b>」必须是两件事，
 * 而裸的 {@code List} 表达不了 —— 传 {@code null} 和传 {@code List.of()}
 * 在方法签名上看起来一样，在语义上却是「不限范围」和「范围里什么都没有」。
 *
 * <p>本类把 {@code null} 在<b>构造期</b>就归一成空列表，
 * 于是一条规则贯穿全链路：<b>空列表 = 不限制</b>。
 *
 * <h2>二、★ 空列表为什么解读成「不限制」而不是「什么都不匹配」</h2>
 *
 * <p>两种解读都能自圆其说，选前者是因为它在两条真实的链路上都是对的：
 *
 * <ul>
 *   <li><b>意图树里 {@code doc_types: []} 的三个叶子</b>
 *       （{@code ORDER_STATUS} / {@code INVENTORY} / {@code MY_COUPON}）。
 *       它们声明空集是因为「答案不在知识库里」——
 *       它们该走 MCP 工具，而工具要到 5.7 才落地。
 *       解读成「什么都不匹配」会让这三个意图在 5.7 之前<b>退化成裸聊</b>，
 *       而用户问「我的订单到哪了」时，裸聊会编一个订单状态出来。</li>
 *   <li><b>分类失败</b>（{@code UNKNOWN_CODE} / {@code CALL_FAILED}）。
 *       不知道范围时，全池检索是唯一合理的默认 ——
 *       这正是阶段 4 的行为，退回去不引入回归。</li>
 * </ul>
 *
 * <h2>三、★ 为什么过滤条件用「PostgreSQL 数组字面量字符串」传递</h2>
 *
 * <p>{@link #docTypesLiteral()} 产出的是 {@code "{2,4}"} 这样的字符串，
 * 由 SQL 里的 {@code CAST(#{docTypes} AS int[])} 解析。
 *
 * <p>换成 {@code <foreach>} 拼 {@code IN (?,?)} 也能work，但那样
 * <b>每一种集合大小都是一条不同的 SQL 文本</b>，PostgreSQL 要分别解析和规划；
 * 而数组字面量只占<b>一个参数位</b>，SQL 文本恒定不变，计划可以复用。
 *
 * <p>⚠️ 这样做<b>必须</b>保证字面量里只有数字和逗号 ——
 * 本类的构造器在结构上保证这一点（见 {@link #normalize}），
 * 而不是在拼 SQL 的地方做转义。同一个思路见 {@code SearchText} 对 tsquery 操作符的处理。
 *
 * @param topK     本路最多返回条数
 * @param docTypes 允许的 {@code doc_type} 集合，去重升序。<b>空列表 = 不限制</b>
 */
public record RetrievalOptions(int topK, List<Integer> docTypes) {

    /**
     * 允许的 {@code doc_type} 取值范围，与 {@code kb_document.doc_type} 的
     * CHECK 约束和意图树文件头的词表一致。
     *
     * <p>在构造期就按它过滤，所以<b>越界的值到不了 SQL</b> ——
     * 一个拼错的 {@code doc_type}（比如把「售后」写成 22）
     * 会变成「过滤掉一切」，而那种失败在检索结果上看不出原因。
     */
    private static final int MIN_DOC_TYPE = 1;
    private static final int MAX_DOC_TYPE = 5;

    public RetrievalOptions {
        topK = Math.max(1, topK);
        docTypes = normalize(docTypes);
    }

    /** 不做任何范围限制，只限定条数 */
    public static RetrievalOptions unfiltered(int topK) {
        return new RetrievalOptions(topK, List.of());
    }

    /** 是否要把 {@code doc_type} 条件下推到 SQL */
    public boolean filtered() {
        return !docTypes.isEmpty();
    }

    /**
     * 去掉范围限制、保留条数。
     *
     * <p>用途只有一个：pipeline 判定「过滤不划算 / 过滤后一条都没召回」时
     * 回落到全池。把它做成方法而不是让调用方 {@code new} 一个，
     * 是为了让「回落」这个动作在代码里只有一个写法、一个含义。
     */
    public RetrievalOptions withoutFilter() {
        return new RetrievalOptions(topK, List.of());
    }

    /**
     * 转成 PostgreSQL 的 {@code int[]} 字面量，如 {@code "{2,4}"}；
     * 不限制时返回 {@code null}（SQL 里靠 {@code CAST(NULL AS int[])} 走不过滤分支）。
     *
     * <p>⚠️ 返回值里<b>只可能出现数字、逗号和花括号</b> ——
     * 这是 {@link #normalize} 的产物，不是调用方的约定。
     */
    public String docTypesLiteral() {
        if (docTypes.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < docTypes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(docTypes.get(i));
        }
        return sb.append('}').toString();
    }

    /**
     * 去空、去重、升序、裁掉越界值。
     *
     * <p>用 {@code stream().sorted()} 而不是保留原顺序：
     * <b>同一个集合必须产出逐字节相同的字面量</b>，否则
     * 「这次和上次的筛选条件一样吗」要靠眼睛比对，
     * 而阶段 7 的 A/B 对比要能直接 diff 两份 {@code retrieval_detail}。
     * （同一个理由见 {@code RetrievalDetailBuilder.round6}。）
     */
    private static List<Integer> normalize(List<Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(java.util.Objects::nonNull)
                .filter(t -> t >= MIN_DOC_TYPE && t <= MAX_DOC_TYPE)
                .distinct()
                .sorted()
                .toList();
    }
}
