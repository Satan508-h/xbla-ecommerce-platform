package com.xbla.rag.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次请求的<b>评测运行标记</b> —— 「这一行 qa_log 属于哪一轮评测的哪一题」（阶段 7）。
 *
 * <h2>★ 为什么它和 {@link TraceId} 放在一起</h2>
 *
 * <p>两者是同一类东西：<b>由调用方带进来、一路透传到 {@code qa_log}、</b>
 * <b>不参与任何业务判断</b>的元信息。
 *
 * <p>它从 HTTP 头进来（只有评测脚本会带），穿过排队层，
 * 最后由 {@code ChatServiceImpl.baseLog} 写进 {@code qa_log} 的两列。
 * 中间没有任何一处需要读它的值 —— 唯一的用途是<b>事后分得开</b>。
 *
 * <h2>★★ 为什么 {@code null} 是常态，不是异常</h2>
 *
 * <p>绝大多数请求是真实用户发的，它们<b>两个头都不带</b> → 字段就是 {@code null}。
 * 这和 {@code queue_ms} / {@code queue_position} / {@code references}
 * 是同一条约定：<b>没有发生的事就留 NULL，不填空值、不填 0</b>。
 *
 * <p>反过来写（缺失时填一个 {@code ""} 或 {@code "none"}）的后果是：
 * {@code WHERE eval_run_id IS NULL} 这个「只统计真实使用」的筛子
 * <b>会静默漏掉一部分真实行</b> —— 而那正是最需要它是准的那个查询。
 *
 * <h2>⚠️ 两列必须一起出现</h2>
 *
 * <p>只带 run 不带来题号的行，报告端点<b>归不了题</b>（算不出这行的 gold 是什么）；
 * 只带题号的行则无从知道是哪一轮。{@code V10} 里说了：
 * 不建 CHECK 强制它们同生共死（调试期这种行有用），
 * 但<b>报告端点必须把它们数出来并单独报</b> —— 半标记的行既进不了评测统计
 * 又混在真实数据里，两头都不属于。
 *
 * @param runId      评测运行 ID。{@code null} = 不是评测流量
 * @param questionNo 该轮里的题号（对应 {@code eval_question.question_no}）。
 *                   长度上限 {@value #MAX_QUESTION_NO_LENGTH}，同列宽
 */
public record EvalMark(String runId, String questionNo) {

    private static final Logger log = LoggerFactory.getLogger(EvalMark.class);

    /**
     * 评测脚本用来标记运行的头。
     *
     * <p><b>全项目单一出处</b> —— 和 {@code McpProtocol.HEADER_USER_ID} 同一个做法。
     * 名字写两遍（常量一份、{@code @RequestHeader} 注解里一份）的漂移是<b>静默</b>的：
     * 打了错头的请求会被当成真实用户流量，而<b>没有任何地方会报错</b>。
     *
     * <p>⚠️ Python 侧（{@code scripts/eval_run.py}）必须自己再写一份字面量 ——
     * 跨语言共享不了常量。那个脚本的注释里指回了这里。
     */
    public static final String HEADER_RUN_ID = "X-Xbla-Eval-Run";

    /** 题号头，取值对应 {@code eval_question.question_no}。和上面成对使用。 */
    public static final String HEADER_QUESTION_NO = "X-Xbla-Eval-No";

    /** 同 {@code qa_log.eval_question_no VARCHAR(32)} 的列宽 */
    private static final int MAX_QUESTION_NO_LENGTH = 32;

    /** 同 {@code qa_log.eval_run_id VARCHAR(64)} 的列宽 */
    private static final int MAX_RUN_ID_LENGTH = 64;

    /**
     * 从两个 HTTP 头构造。<b>两个都为空时返回 {@code null}</b>，不返回
     * {@code new EvalMark(null, null)}。
     *
     * <p>★ 为什么「没有标记」要用 {@code null} 表达，而不是一个字段全空的对象：
     * 调用方要判断的只有一件事 ——「这次是不是评测」。
     * 一个非空但字段全空的对象会让 {@code if (mark != null)} 这种判断
     * <b>看起来成立、实际什么也没带</b>，而那是最难查的一类：
     * 代码读了它、日志打了它、值却什么都不表示。
     *
     * <p>⚠️ 超长值这里<b>截断并打 WARN</b>，不抛异常。理由：
     * <ul>
     *   <li>抛异常会让一个<b>真实用户</b>的请求 500（头是攻击面，不能让它决定请求成败）</li>
     *   <li>不在库里静默失败：{@code qa_log} 的那两列是有长度的，
     *       超长会让整行 <b>INSERT 失败</b>，而 {@code writeQaLog} 把异常吞成一条 ERROR
     *       日志 —— 于是那一行<b>整行消失</b>，包括本该记下的真实问答</li>
     * </ul>
     * 截断的后果是：跑题器按 run id 对账时会发现行数不符 → <b>整轮作废</b>。
     * 也就是说错误仍然会以「这一轮白跑了」的形式浮出来，代价不算在用户身上。
     */
    public static EvalMark of(String runId, String questionNo) {
        String run = blankToNull(runId);
        String no = blankToNull(questionNo);
        if (run == null && no == null) {
            return null;
        }
        return new EvalMark(clamp(run, MAX_RUN_ID_LENGTH, HEADER_RUN_ID),
                clamp(no, MAX_QUESTION_NO_LENGTH, HEADER_QUESTION_NO));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String clamp(String value, int max, String header) {
        if (value == null || value.length() <= max) {
            return value;
        }
        log.warn("{} 的值超长（{} > {}），已截断 —— 评测对账会因此失败，"
                        + "请检查跑题器生成的 id 长度。值={}",
                header, value.length(), max, value);
        return value.substring(0, max);
    }
}
