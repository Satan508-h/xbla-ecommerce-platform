package com.xbla.rag.agent.intent;

import java.util.ArrayList;
import java.util.List;

/**
 * 澄清要问的<b>槽位词表</b>（阶段 9.4）。
 *
 * <h2>一、槽位是谁定的</h2>
 *
 * <p>不是这个类定的 —— 词的<b>唯一出处是分类 prompt</b>
 * （{@code IntentPromptBuilder.appendPlanContract} 里那四行
 * {@code · purpose（用途或场景…）}）。模型按那段契约产出
 * {@link IntentPlan#missingSlots()}，本类只做两件收尾的事：<b>认得的留下、
 * 按优先级挑一个来问</b>。
 *
 * <p>★★ <b>为什么词表要在这里再写一份</b>（看起来像「同一件事两个出处」）：
 * 因为 {@code IntentReplyParser.readMissing} 是<b>刻意</b>不做白名单的 ——
 * 模型自创的槽位名会原样落进 {@code qa_log.intent_plan.missing}，
 * 那是<b>我们想看见的信号</b>（「模型开始编槽位了」），过滤掉就看不见了。
 * 所以：<b>解析/落库保留原话，消费端过滤</b>。
 * ★ 两份词表的一致性由 {@code IntentPromptBuilderTest} 结构性保证
 * （断言 prompt 里出现的每个槽位名都在 {@link #KNOWN} 里，反之亦然）。
 *
 * <h2>二、★ 缺多个槽位时只问一个，先问哪个</h2>
 *
 * <p>澄清的全部价值是「一次反问换一次有效回答」。罗列「请提供型号、场景、预算」
 * 会让用户面对一张表单（{@code AgentProperties.Intent#clarifyText} 的注释
 * 已经为这件事打过一次架）。所以要有个固定优先级：
 *
 * <pre>
 *   product   最具体，而且「那个怎么样」这类最常见的澄清缺的就是它
 *   purpose   导购场景第二大缺失（送人还是自用，直接改变推荐）
 *   category  比 product 宽 —— 问到它通常还得再问一次 product
 *   budget    答不上也不妨碍先给一版建议，排在最后
 * </pre>
 *
 * <p>★ 写成常量而不是配置：它是<b>产品判断</b>，不是环境差异。
 * 要改的人改代码、跑测试，而不是在某个环境里悄悄拨一下 ——
 * 后者会让「两次实验的差别是什么」变成一道猜谜。
 */
public final class ClarifySlots {

    public static final String PRODUCT = "product";
    public static final String PURPOSE = "purpose";
    public static final String CATEGORY = "category";
    public static final String BUDGET = "budget";

    /** 认得的槽位，<b>顺序 = 缺多个时的追问优先级</b>（见类注释第二节） */
    public static final List<String> KNOWN =
            List.of(PRODUCT, PURPOSE, CATEGORY, BUDGET);

    private ClarifySlots() {
    }

    /**
     * 只留下认得的槽位，顺序与去重都按模型的原始顺序。
     *
     * <p>★ 认不出的<b>静默丢弃</b>而不是报错：模型编一个 {@code "颜色"}
     * 只是「它多说了句话」，不该让整次反问失败。
     * ★ 落库的那一份（{@code intent_plan.missing}）保持原样，两者刻意不同。
     */
    public static List<String> knownOnly(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> known = new ArrayList<>(raw.size());
        for (String slot : raw) {
            if (KNOWN.contains(slot) && !known.contains(slot)) {
                known.add(slot);
            }
        }
        return known;
    }

    /**
     * 这一次该问哪一个槽位。
     *
     * @param missing 模型报的缺失槽位（可以是原话，本方法自己过滤）
     * @return 认得的、优先级最高的那个；<b>一个都没有时返回 {@code null}</b>
     *         （调用方回落到固定的澄清文案）
     */
    public static String pickToAsk(List<String> missing) {
        List<String> known = knownOnly(missing);
        for (String slot : KNOWN) {
            if (known.contains(slot)) {
                return slot;
            }
        }
        return null;
    }
}
