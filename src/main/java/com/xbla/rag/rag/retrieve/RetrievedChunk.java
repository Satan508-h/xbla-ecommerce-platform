package com.xbla.rag.rag.retrieve;

/**
 * 检索链路上流动的一个切片 —— <b>从召回到重排到送进 prompt，全程只有这一种类型</b>。
 *
 * <h2>★ {@code score} 的含义随阶段变化</h2>
 *
 * <table border="1">
 *   <caption>各阶段的分值含义</caption>
 *   <tr><th>所处阶段</th><th>{@code score} 是什么</th><th>取值范围</th></tr>
 *   <tr><td>向量召回</td><td>余弦相似度（{@code 1 - 距离}）</td><td>-1 ~ 1</td></tr>
 *   <tr><td>关键词召回</td><td>{@code ts_rank}</td><td>0 ~ 0.1 左右，量纲完全不同</td></tr>
 *   <tr><td>RRF 融合</td><td>RRF 得分 {@code Σ 1/(k+rank)}</td><td>0 ~ 0.05 左右</td></tr>
 *   <tr><td>重排序</td><td>{@code bge-reranker} 的相关度</td><td>0 ~ 1，但与向量相似度不可比</td></tr>
 * </table>
 *
 * <p>⚠️ <b>这四个分值之间没有任何可比性</b>，绝对不能跨阶段比较大小，
 * 也不能拿一个阶段的阈值去过滤另一个阶段的结果。
 * 它们是「同一批切片在不同尺子下的读数」，仅此而已。
 *
 * <p>之所以还用同一个类型：<b>阶段是靠它所在的列表表达的</b>，
 * 而不是靠类型区分。给每个阶段单独定义一个 record 会得到四个字段完全相同的类，
 * 而且每次串联都要写一遍逐字段搬运的映射代码 —— 那是纯粹的噪音。
 * （同理见 {@code VectorHit} 的注释：那个类警告过「距离和相似度混用会把排序条件写反」，
 * 这里的风险是同一类，靠注释和单一数据流来防。）
 *
 * <h2>为什么不带「来源」字段</h2>
 *
 * <p>融合之后一个切片可能<b>同时</b>来自向量路和关键词路，单个枚举表达不了。
 * 而每一路的原始结果在 {@code RetrievalTrace} 里本来就是分开的列表，
 * 需要归因时查那两个列表即可 —— 再在命中上带一个「来源」字段，
 * 就成了会与列表不一致的冗余状态。
 *
 * @param id          切片 ID（{@code kb_chunk.id}）
 * @param documentId  所属文档 ID
 * @param chunkIndex  在文档内的序号
 * @param content     切片正文
 * @param headingPath 标题层级路径，可为 null
 * @param score       当前阶段的分数，含义见上表
 */
public record RetrievedChunk(
        Long id,
        Long documentId,
        Integer chunkIndex,
        String content,
        String headingPath,
        double score) {

    /** 从 Mapper 返回的行结构转换。两路召回都用它。 */
    public static RetrievedChunk from(VectorHit hit) {
        return new RetrievedChunk(hit.id(), hit.documentId(), hit.chunkIndex(),
                hit.content(), hit.headingPath(), hit.score());
    }

    /** 换一个分数，其余字段不变。融合与重排阶段用得上 */
    public RetrievedChunk withScore(double newScore) {
        return new RetrievedChunk(id, documentId, chunkIndex, content, headingPath, newScore);
    }
}
