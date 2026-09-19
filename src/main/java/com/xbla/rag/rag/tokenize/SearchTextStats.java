package com.xbla.rag.rag.tokenize;

/**
 * {@code kb_chunk.search_text} 的覆盖率与规模统计。
 *
 * <h2>为什么需要这个探针</h2>
 *
 * <p>关键词检索的过滤条件是 {@code search_vector @@ query}。
 * 而 <b>SQL 的 {@code NULL} 参与了布尔运算，结果是 {@code NULL} 而不是 {@code false}</b>，
 * 所以 {@code search_text} 为 NULL 的切片会被 {@code WHERE} <b>静默排除</b> ——
 * <b>不报错、不告警</b>。
 *
 * <p>这意味着：如果哪天有人改了入库代码、忘了写 {@code search_text}，
 * 症状是「新文档检索不到」，而<b>老数据全都正常</b>。
 * 这种 bug 能潜伏很久，且第一反应一定是去查检索逻辑而不是入库逻辑。
 *
 * <p>把「静默」变成「可观测」，就是这个统计存在的唯一理由。
 *
 * @param total      未删除的切片总数
 * @param missing    {@code search_text} 为 NULL <b>或空串</b>的条数
 *                   （★ 两个条件都要查：{@code to_tsvector('simple','')} 得到的是
 *                   <b>空 tsvector 而不是 NULL</b>，只查 NULL 会漏掉空串那一类）
 * @param minTokens  词元数最小值（无数据时为 0）
 * @param avgTokens  词元数平均值
 * @param maxTokens  词元数最大值
 */
public record SearchTextStats(
        long total,
        long missing,
        int minTokens,
        int avgTokens,
        int maxTokens) {

    /** 覆盖率是否健康：一条都没漏 */
    public boolean healthy() {
        return missing == 0;
    }
}
