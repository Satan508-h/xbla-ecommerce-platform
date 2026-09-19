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
 */
public record IntentClassification(
        String code,
        Outcome outcome,
        String rawReply,
        ModelDescriptor descriptor,
        BigDecimal cost,
        long latencyMs,
        String error) {

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
