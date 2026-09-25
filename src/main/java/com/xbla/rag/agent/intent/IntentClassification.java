package com.xbla.rag.agent.intent;

import com.xbla.rag.client.dto.ModelDescriptor;

import java.math.BigDecimal;

/**
 * 一次意图分类的结果。
 *
 * <h2>★ 为什么是「结果对象」而不是「一个 String 或 null」</h2>
 *
 * <p>因为分类失败有<b>三种不同的原因</b>，而它们要触发完全不同的行动：
 *
 * <table border="1">
 *   <caption>四种 outcome</caption>
 *   <tr><th>outcome</th><th>含义</th><th>该做什么</th></tr>
 *   <tr><td>{@link Outcome#CLASSIFIED}</td><td>正常分类成功</td>
 *       <td>照常走 5.4 的定向过滤</td></tr>
 *   <tr><td>{@link Outcome#UNKNOWN_CODE}</td><td>模型答了，但答的不是合法 code</td>
 *       <td>★ <b>去改 prompt，不是去改兜底</b>。这是分类器本身坏了</td></tr>
 *   <tr><td>{@link Outcome#CALL_FAILED}</td><td>调用就没成功（超时 / 全链路失败）</td>
 *       <td>这是基础设施问题，改不了 prompt，也<b>不该重试</b>（见下）</td></tr>
 * </table>
 *
 * <p>如果只返回 {@code null} 表示失败，这三种情况就混成了一种，
 * 而「模型在乱答」和「供应商挂了」的排查方向完全相反。
 * 和 {@code ModelErrorKind} 刻意区分很多失败类型是同一个思路。
 *
 * <p><b>★ 失败时不重试、不换个模型再试。</b>
 * 分类是每次提问都要跑一次的<b>高频附加调用</b>，而它失败的合理兜底
 * 是「不做定向检索」—— 那退化成阶段 4 的完整行为，用户无感。
 * 换个模型重试只会把延迟叠上去，而正确率并没有保证。
 *
 * @param code       分类目标的 code。失败时为 {@code null}
 * @param outcome    见上表
 * @param rawReply   模型的<b>原始</b>返回（未清洗）。排查「模型到底答了什么」时只有它有用
 * @param descriptor 实际作答的模型。降级链生效时可能不是 P0 ——
 *                   不记下来就看不出「今天准确率掉了是因为一直在用 P2」
 * @param cost       本次调用的成本。拿不到用量时为 {@code null}（不估算）
 * @param latencyMs  分类耗时（含模型往返）。它是加在检索之前的用户可感延迟
 * @param error      失败原因。成功时为 {@code null}
 * @param plan       <b>阶段 9.2</b>：同一次调用顺带产出的计划（要不要检索、缺哪些槽位）。
 *                   <p>★ {@code null} 是<b>有意义的状态</b>，不是「忘了填」：
 *                   它表示<b>这次分类没有产出计划</b> —— 测试直接构造的结果，
 *                   或者分类整个失败了。此时检索门控退回「只看意图树」，
 *                   也就是 9.2 之前的行为。
 *                   <p>★★ 它<b>刻意不携带 intent</b>：意图码只有上面那个 {@code code} 一个出处。
 *                   两个字段能各自构造 = 总有一天会不一致，而那种不一致是静默的。
 */
public record IntentClassification(
        String code,
        Outcome outcome,
        String rawReply,
        ModelDescriptor descriptor,
        BigDecimal cost,
        long latencyMs,
        String error,
        IntentPlan plan) {

    /**
     * <b>兼容构造器</b>（阶段 9.2）—— 不带计划的那种。
     *
     * <p>它存在的唯一理由是<b>改动的爆炸半径</b>：{@code IntentClassification}
     * 在阶段 9.2 之前有 <b>16 个构造点</b>，绝大多数在测试里，
     * 而它们<b>根本不关心计划</b>。
     *
     * <p>★ 有了它，那些点位一个字都不用改，{@code plan} 为 {@code null}
     * （= 没有计划 = 门控只看意图树 = 9.2 之前的行为）。
     *
     * <p>⚠️ 不要因为「看起来更短」而把线上那条路也写成 7 参 ——
     * {@code LlmIntentClassifier} 必须带上真实的计划，
     * 否则门控永远收不到模型的意见，而表现是「什么都对，就是 retrieve 永远是 true」。
     */
    public IntentClassification(String code, Outcome outcome, String rawReply,
                                ModelDescriptor descriptor, BigDecimal cost, long latencyMs,
                                String error) {
        this(code, outcome, rawReply, descriptor, cost, latencyMs, error, null);
    }

    public enum Outcome {
        /** 拿到了合法 code */
        CLASSIFIED,
        /** 模型答了，但不是树里的任何一个分类目标 */
        UNKNOWN_CODE,
        /** 调用本身失败（超时、熔断、全链路失败等） */
        CALL_FAILED
    }

    public boolean isClassified() {
        return outcome == Outcome.CLASSIFIED;
    }

    /** 日志用的一句话摘要 */
    public String describe() {
        return switch (outcome) {
            case CLASSIFIED -> String.format("intent=%s model=%s latency=%dms",
                    code,
                    descriptor == null ? "?" : descriptor.modelKey(),
                    latencyMs);
            case UNKNOWN_CODE -> String.format("★ 分类失败：模型返回了非法 code，raw=%s", quote(rawReply));
            case CALL_FAILED -> String.format("★ 分类失败：调用未成功（%s）", error);
        };
    }

    private static String quote(String text) {
        if (text == null) {
            return "null";
        }
        String flat = text.replace("\n", "\\n");
        return flat.length() > 120 ? "\"" + flat.substring(0, 120) + "…\"" : "\"" + flat + "\"";
    }
}
