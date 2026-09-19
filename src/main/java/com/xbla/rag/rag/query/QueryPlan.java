package com.xbla.rag.rag.query;

import java.util.List;

/**
 * 一次查询计划 —— 「拿用户的原话去检索」之前要不要先做点加工的结论。
 *
 * <p>这是 4.3（查询重写）和 4.4（子问题拆分）的产物。
 * 两个功能都默认关闭，此时本对象退化成
 * {@code new QueryPlan(原问题, null, List.of(), null)} ——
 * 也就是「原样检索」。
 *
 * @param originalQuestion  用户原话。<b>永远保留</b>，它是
 *                          {@code qa_log.question} 的权威来源
 * @param rewrittenQuestion 重写后的问题。<b>没重写时必须是 null，不能是原问题</b>。
 *
 *                          <p>★ 这条约定是为了阶段 7 的 A/B 对比：
 *                          如果关闭重写时这里填原问题，那
 *                          {@code qa_log.rewritten_question} 就无法区分
 *                          「没开重写」和「开了重写但结果和原文一样」——
 *                          而这是两个完全不同的实验条件。
 *                          （同 ADR-010「拿不到用量就记 NULL，绝不估算」的原则。）
 * @param subQuestions      拆分出的子问题。未拆分时为空列表，不是 null
 * @param note              过程说明，仅在「发生了值得记录的事」时非空
 *                          （例如重写失败回落）。由 pipeline 读出来记进 trace。
 *                          <b>正常路径下是 null</b>
 */
public record QueryPlan(
        String originalQuestion,
        String rewrittenQuestion,
        List<String> subQuestions,
        String note) {

    public QueryPlan {
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
    }

    /** 不加工的计划 —— 默认路径，也是所有失败路径的回落目标 */
    public static QueryPlan identity(String question) {
        return new QueryPlan(question, null, List.of(), null);
    }

    /** 不加工，但带一条说明 */
    public static QueryPlan identityWithNote(String question, String note) {
        return new QueryPlan(question, null, List.of(), note);
    }

    /**
     * 实际拿去检索的问题。
     *
     * <p>有重写用重写，没有用原话。检索链路上所有下游
     * （向量路、关键词路、重排）都应该用这个值，
     * 而 {@code qa_log.question} 永远用 {@link #originalQuestion()}。
     */
    public String effectiveQuery() {
        return (rewrittenQuestion == null || rewrittenQuestion.isBlank())
                ? originalQuestion
                : rewrittenQuestion;
    }

    /** 是否真的做了重写 */
    public boolean rewritten() {
        return rewrittenQuestion != null && !rewrittenQuestion.isBlank();
    }

    /** 是否拆出了子问题 */
    public boolean split() {
        return !subQuestions.isEmpty();
    }
}
