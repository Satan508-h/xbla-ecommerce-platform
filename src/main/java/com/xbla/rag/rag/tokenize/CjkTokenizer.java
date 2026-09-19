package com.xbla.rag.rag.tokenize;

/**
 * 中文分词器：把一段文本切成「检索用的词元」。
 *
 * <h2>一、为什么需要它 —— PostgreSQL 自己切不开中文</h2>
 *
 * <p>PostgreSQL 内置的全文检索分词器是为英文设计的。实测：
 *
 * <pre>
 *   SELECT to_tsvector('simple', '电池容量是5000mAh支持快充');
 *   →  '电池容量是5000mah支持快充':1        ← 整句粘成【一个】词元
 * </pre>
 *
 * <p>后果是关键词召回几乎全废。全库 1652 条切片上的实测漏检率：
 *
 * <pre>
 *   词      裸 FTS 命中    content LIKE 命中    漏检率
 *   退货        3               43              93%
 *   电池       94              135              30%
 *   星辰        5                5               0%
 * </pre>
 *
 * <p>「退货」这种高频短词，43 条里只能找到 3 条 —— 所以必须自己做中文切分。
 *
 * <h2>二、为什么定义成接口</h2>
 *
 * <p>和 {@code TextChunker} 同理：给阶段 7 的 A/B 对比留位置。
 * 「bigram 切分 vs 真正的词级分词（HanLP / jieba）」是这个项目评测报告里
 * 最有说服力的一段对比之一，换实现类就能对比。
 *
 * <p>换实现类后需要重建索引 —— 走 {@code POST /api/debug/kb/reindex?force=true}。
 *
 * <h2>三、★ 本方法的输出不是 token 数</h2>
 *
 * <p>{@code kb_chunk.token_count} <b>刻意保持 NULL</b>（ADR-010：拿不到用量就记 NULL，
 * 绝不估算）。这里的「词元」是<b>检索索引项</b>，不是模型计费的 token，
 * 两者没有任何换算关系。<b>任何情况下都不要把 {@link SearchText#size()}
 * 写进 {@code token_count}</b>。
 *
 * @see BigramCjkTokenizer 本项目的默认实现
 */
public interface CjkTokenizer {

    /**
     * 分词。<b>不截断</b>，用于文档入库 —— 切片有多少内容就要索引多少。
     *
     * @param text 原始文本，可为 null（返回空结果，不抛异常）
     * @return 词元集合，顺序为首次出现顺序
     */
    SearchText tokenize(String text);

    /**
     * 分词，<b>并截断到上限</b>，用于用户查询。
     *
     * <p>为什么要和 {@link #tokenize} 分开：两者对「长度上限」的需求是相反的。
     * <ul>
     *   <li><b>文档</b>：一个 500 字的中文切片会产生约 499 个 bigram，
     *       截断到几十个会直接毁掉索引 —— 必须全量</li>
     *   <li><b>查询</b>：{@code ChatAskRequest} 允许 2000 字的问题，
     *       那会拼出 2000 项的 OR 链，候选集爆炸、{@code ts_rank} 糊成一团 ——
     *       必须截断</li>
     * </ul>
     *
     * @param text      用户问题，可为 null
     * @param maxTokens 词元数上限；小于等于 0 表示不限制
     */
    SearchText tokenizeQuery(String text, int maxTokens);
}
