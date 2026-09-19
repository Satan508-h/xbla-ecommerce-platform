package com.xbla.rag.rag.query;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 不加工 —— 「原样检索」。
 *
 * <p>这是<b>默认实现</b>（{@code xbla.rag.rewrite.enabled} 缺省或为 false 时生效），
 * 也是阶段 4 记录基线时用的那一支。
 *
 * <h2>它做的最重要的一件事是「什么都不做」</h2>
 *
 * <p>返回的 {@link QueryPlan#rewrittenQuestion()} 是 <b>null</b>，不是原问题。
 * 这个区别在阶段 7 的 A/B 对比里是关键：
 *
 * <pre>
 *   rewritten_question = null   →  这一条记录来自「没开重写」的运行
 *   rewritten_question = 原文   →  无法区分上面那种，还是「开了重写但模型没改动」
 * </pre>
 *
 * <p>而这是两个完全不同的实验条件，混在一起会让 A/B 的结论直接失效。
 * 同 ADR-010「拿不到用量就记 NULL，绝不估算」的原则。
 */
@Component
@ConditionalOnProperty(name = "xbla.rag.rewrite.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpQueryPlanner implements QueryPlanner {

    @Override
    public QueryPlan plan(String question) {
        return QueryPlan.identity(question);
    }
}
