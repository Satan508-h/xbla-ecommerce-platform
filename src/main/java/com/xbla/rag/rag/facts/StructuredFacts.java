package com.xbla.rag.rag.facts;

import java.util.List;

/**
 * 一次问答要额外带进 prompt 的<b>结构化事实</b>（阶段 5.9）。
 *
 * <h2>一、它和「检索到的切片」是什么关系</h2>
 *
 * <p>两者的区别是<b>来源的性质</b>，不是内容的多少：
 *
 * <pre>
 *   RetrievedChunk   从【文档】里召回的一段文字 —— 散文，可能有歧义
 *   StructuredFacts  从【表】里查出的字段值   —— 准确值，无歧义
 * </pre>
 *
 * <p>一次问答可以两者都有（售后政策的退换货问题就是），也可以只有其中一个。
 *
 * <h2>★★ 二、为什么不能做成「检索一种特殊切片」</h2>
 *
 * <p>看起来可以把它伪装成 {@link com.xbla.rag.rag.retrieve.RetrievedChunk}
 * 混进 {@code final_top_k}，那样就不用动 prompt 组装了。但那是<b>伪造检索结果</b>：
 * {@code qa_log.retrieval_detail} 的 {@code final_top_k} 会包含一个
 * <b>永远不会出现在 {@code kb_chunk} 表里的 chunk_id</b>，
 * 而阶段 7 拿它对账时对不上 —— 那是个会被查很久的问题。
 *
 * <p>所以它是<b>独立的一节</b>，在 prompt 里和「知识库资料」并列，
 * 各自有标题。
 *
 * <h2>三、★ 只放「表里有、文档里没有」的东西</h2>
 *
 * <p>{@code after_sale_policy} 表和知识库有重叠：{@code conditions}
 * 已经被切分进语料了（实测 12 条「附加条件」切片）。把重叠的部分也带进来，
 * 只会让 prompt 变长、让模型在两份一样的文字之间做无谓的对照。
 *
 * <p>所以 {@link PolicyTerm} 里<b>只有天数</b> —— 那是文档里以散文形式
 * （「自签收之日起 7 天内支持无理由退货，15 天内支持换货」）出现、
 * 模型读起来可能张冠李戴的东西。见 {@code IntentTree.StructuredFact} 的注释。
 *
 * @param policies 政策条款。<b>空列表表示「这次没有要带的事实」</b>，
 *                 用 {@link #EMPTY} 而不是 {@code null}
 */
public record StructuredFacts(List<PolicyTerm> policies) {

    /**
     * 「这次不带任何结构化事实」。
     *
     * <p>★ 用一个常量而不是让调用方到处 {@code new StructuredFacts(List.of())} ——
     * 后者会在每个调用点都分配一个新对象，而它们全都相等。
     * 更实际的理由是：<b>「没有事实」是一个要能一眼认出来的状态</b>，
     * 有个名字比读到一个空列表字面量清楚。
     */
    public static final StructuredFacts EMPTY = new StructuredFacts(List.of());

    public StructuredFacts {
        // ★ 这里用 List.copyOf 是安全的：它拒绝 null【元素】，
        //   而 PolicyTerm 内部的 category 允许为 null（通用政策）——
        //   那是字段级 null，不是元素级。两者的区别见 McpToolResult 那次教训
        policies = List.copyOf(policies);
    }

    public boolean isEmpty() {
        return policies.isEmpty();
    }

    public int size() {
        return policies.size();
    }

    /**
     * 一条政策的天数。
     *
     * <p>★ <b>没有 {@code conditions}，也没有 {@code content}</b> ——
     * 理由见类注释第三节。
     *
     * @param category     适用类目。<b>为 null 表示通用政策</b>（各类目都适用）。
     *                     ⚠️ 不要在这里把 null 换成「全部」或「通用」字符串 ——
     *                     那是<b>渲染</b>的事，由 {@code RagPromptBuilder} 决定怎么写。
     *                     数据层保留 null 才能让「到底是不是通用」这件事可查
     * @param policyNo     政策编号（如 {@code AS-001}）。给日志和探针用
     * @param returnDays   可退货天数
     * @param exchangeDays 可换货天数。
     *                     ★ <b>可空</b> —— 数据库上没有「换货天数非空」的约束。
     *                     ⚠️ 不要在这里把 null 兜成 {@code 0}：那会渲染成
     *                     「换货 0 天」，<b>一句听起来像事实的胡说</b>，
     *                     而模型会原样转述给用户。null 一路传到渲染层，
     *                     由那里写成「未规定」—— 同 {@code McpToolResult}
     *                     那条「可选字段的值合法地就是 null」的纪律
     */
    public record PolicyTerm(String category, String policyNo,
                             int returnDays, Integer exchangeDays) {
    }
}
