package com.xbla.rag.agent.intent;

import java.util.List;

/**
 * 模型对这次提问的<b>计划</b>（阶段 9.2）—— 除了「是哪一类」之外的几个决定。
 *
 * <h2>★★ 为什么它【不】携带 intent</h2>
 *
 * <p>{@link IntentClassification} 已经有 {@code code} 了。如果本对象再存一份
 * {@code intent}，就变成<b>两个字段能各自构造</b> —— 而它们总有一天会不一致，
 * 且那种不一致是<b>静默的</b>（"这条 qa_log 的 intent 和计划里的 intent 不一样"
 * 不会抛异常，只会让下游统计悄悄分成两堆）。
 *
 * <p>所以这里是<b>单一出处</b>：意图码只有一个地方存，计划只存它额外决定的东西。
 * 想把两者放一起看，就在组装 {@code qa_log.intent_plan} 的时候拼 —— 那是展示，不是状态。
 *
 * <h2>★★ 为什么它不改 {@code IntentClassification} 的构造签名</h2>
 *
 * <p>{@link IntentClassification} 有 16 个构造点（大多在测试里）。
 * 把它改成一个「持有 plan、{@code code()} 变成派生访问器」的形状固然更纯粹，
 * 但那会让 16 处机械改动涌进这次提交，而其中大多数测试<b>根本不关心计划</b>。
 *
 * <p>做法是给 {@code IntentClassification} 加一个分量 + 一个<b>兼容构造器</b>：
 * 老的 7 参写法继续编译，{@code plan} 为 {@code null}。
 * ★ 而 {@code null} 在这里是<b>有意义的状态</b>而不是"忘了填"：
 * 它表示<b>这次分类没有产出计划</b>（测试构造的、或者分类整个失败了），
 * 此时门控退回「只看意图树」，也就是 9.2 之前的行为。
 *
 * <h2>三个字段</h2>
 *
 * @param retrieve     这句话需不需要<b>检索知识库</b>。
 *                     <p>★ 它只能把检索<b>关掉</b>，不能打开：
 *                     意图树声明 {@code retrieval=NONE} 的意图（工具类、兜底类），
 *                     模型说"要检索"也不检索 —— 那是 ADR-044 的坑
 *                     （工具意图一旦退化成裸聊，模型会编一个订单状态出来）。
 *                     <p>⚠️ 反过来「模型关掉检索」是<b>允许</b>的，那正是本阶段的目的。
 *                     它只对 {@code retrieval=KB} 的意图有意义。
 * @param missingSlots 要回答这句话还缺哪些信息（如 {@code purpose} / {@code budget}）。
 *                     <p>★★ <b>阶段 9.2 只解析、只落库、【不消费】。</b>
 *                     现在就把它定下来，是因为改一次分类 prompt 就要重测一次
 *                     5.2 的准确率基线（0.0278 的噪声底）—— 9.4 做槽位填充时
 *                     就不用再改一次契约。
 * @param shape        模型的回复<b>长什么样</b>。见 {@link Shape}
 */
public record IntentPlan(boolean retrieve, List<String> missingSlots, Shape shape) {

    public IntentPlan {
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }

    /**
     * 模型回复的形状 —— ★★ <b>这是「prompt 改动有没有生效」的唯一判据</b>。
     *
     * <p>没有它，本阶段最可能发生的失败是<b>静默</b>的：
     *
     * <pre>
     *   prompt 改成了「输出 JSON」，但模型照旧只吐一个裸 code
     *   → 解析走回退分支（② CODE）
     *   → 一切看起来正常，门控【一次都没生效】
     *   → 报告上所有旧指标一格不动，没人会发现
     * </pre>
     *
     * <p>★ 所以 {@code shape} 的分布要印进报告：{@code JSON} 占比一旦明显低于 100%，
     * 说明模型没跟上契约，那时候「门控的有效性」根本无从谈起。
     *
     * <pre>
     *   JSON      模型按新契约答的（能拿到 retrieve / missing）
     *   CODE      模型只回了一个裸码 —— 老契约。retrieve 取保守值 true
     *   UNPARSED  两种都不是 → 分类整体失败
     * </pre>
     */
    public enum Shape {
        /** 模型按新契约答的 */
        JSON,
        /** 模型只回了一个裸码（老契约）—— 能跑，但拿不到计划 */
        CODE,
        /** 两种都解析不出来 */
        UNPARSED
    }

    /** 只回了一个裸码时的计划：检索照旧（= 9.2 之前的行为），没有任何槽位信息 */
    public static IntentPlan bareCode() {
        return new IntentPlan(true, List.of(), Shape.CODE);
    }

    /** 给日志用的一句话 */
    public String describe() {
        return String.format("retrieve=%s missing=%s shape=%s", retrieve, missingSlots, shape);
    }
}
