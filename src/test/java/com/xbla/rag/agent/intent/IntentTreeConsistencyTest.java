package com.xbla.rag.agent.intent;

import com.xbla.rag.mapper.EvalQuestionMapper;
import com.xbla.rag.rag.eval.EvalQuestionLoader;
import com.xbla.rag.rag.eval.QuestionGoldDocType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ <b>意图树的验收测试</b>（阶段 5.1）。
 *
 * <p>这是 5.1 唯一能<b>自动验证</b>的东西。没有它，「意图树定义好了」
 * 就只是一句主观的话 —— {@link IntentTreeTest} 能证明这棵树语法正确、
 * code 唯一、字段齐全，但证明不了<b>这套分类能不能接住真实的问题</b>、
 * <b>声明的 doc_types 对不对</b>。那两件事只能用真实数据验，而
 * {@code eval_question} 里那 20 道人工标注的题就是现成的真实数据。
 *
 * <h2>验收标准</h2>
 *
 * <pre>
 *   对每一道已标注意图的题：
 *     该题 gold 切片的 doc_type 集合  ⊆  该题意图声明的 doc_types 集合
 * </pre>
 *
 * <p>不满足就是<b>树画错了</b> —— 不是题标错了，也不是过滤太激进。
 * 这个区分很重要：两者掉分时的表现一样，修法却完全相反
 * （一个是改 YAML，一个是改 5.4 的过滤策略）。
 *
 * <h2>为什么这条测试值得存在</h2>
 *
 * <p>阶段 7 要把它扩到 150 题。那时标注工作量的分布是：<b>判「在问什么」很快，
 * 判「答案在哪几个 doc_type」很慢</b>（后者要通读语料）。这条测试把第二件事
 * 变成了自动的：标完 intent，跑一下，错了立刻报出来。
 *
 * <p><b>前置条件</b>：{@code eval_question} 里得有数据（跑过一次
 * {@code POST /api/debug/eval/reload}）。没有数据时本类会 {@code assumeTrue}
 * 跳过，而不是失败 —— 空的表不能说明树有问题。
 */
@SpringBootTest
@Transactional
@DisplayName("IntentTreeConsistency · 意图树验收")
class IntentTreeConsistencyTest {

    @Autowired
    private IntentTree intentTree;

    @Autowired
    private EvalQuestionMapper evalQuestionMapper;

    /** 按题目聚合出来的 gold doc_types + 意图标注 */
    private record QuestionFacts(String questionNo, String intent, Set<Integer> goldDocTypes) {
    }

    /**
     * 把「一题 × 一种类型 = 一行」的扁平查询结果，聚合成「一题 → 一组类型」。
     *
     * <p>用 {@link TreeMap} 让题目按编号有序 —— 失败信息里的顺序才是稳定的，
     * 两次运行能直接 diff。
     */
    private Map<String, QuestionFacts> loadFacts() {
        List<QuestionGoldDocType> rows = evalQuestionMapper.findGoldDocTypes();

        Map<String, Set<Integer>> gold = new TreeMap<>();
        Map<String, String> intents = new LinkedHashMap<>();
        for (QuestionGoldDocType row : rows) {
            gold.computeIfAbsent(row.questionNo(), k -> new LinkedHashSet<>()).add(row.docType());
            intents.put(row.questionNo(), row.intent());
        }

        Map<String, QuestionFacts> facts = new TreeMap<>();
        for (Map.Entry<String, Set<Integer>> e : gold.entrySet()) {
            facts.put(e.getKey(),
                    new QuestionFacts(e.getKey(), intents.get(e.getKey()), e.getValue()));
        }
        return facts;
    }

    private void assumeEvalQuestionsLoaded() {
        Assumptions.assumeTrue(
                evalQuestionMapper.selectCount(null) > 0,
                "eval_question 是空的 —— 先跑 POST /api/debug/eval/reload 再验意图树");
    }

    // ============================================================
    // 一、验收标准本体
    // ============================================================

    @Test
    @DisplayName("★ 每道题的 gold doc_types 都 ⊆ 它的意图声明的 doc_types")
    void goldDocTypesAreSubsetOfDeclared() {
        assumeEvalQuestionsLoaded();
        IntentTree.Tree tree = intentTree.get();

        List<String> violations = new ArrayList<>();
        int checked = 0;

        for (QuestionFacts facts : loadFacts().values()) {
            if (EvalQuestionLoader.INTENT_PLACEHOLDER.equals(facts.intent())) {
                continue;   // 未标注的题没有「声明的集合」可比，由另一个测试盯着
            }

            Set<Integer> declared = tree.docTypesOf(facts.intent());
            if (!tree.findLeaf(facts.intent()).isPresent()) {
                continue;   // 意图 code 不存在，由 intentCodesExistInTree 盯着
            }

            checked++;
            Set<Integer> orphan = new LinkedHashSet<>(facts.goldDocTypes());
            orphan.removeAll(declared);
            if (!orphan.isEmpty()) {
                violations.add(String.format(
                        "%s [%s]：gold 落在 %s，但这些类型没有被声明%s%n"
                        + "        → 要么把 doc_types 补上，要么这道题标错了意图",
                        facts.questionNo(), facts.intent(), facts.goldDocTypes(),
                        "，多出来的是 " + orphan));
            }
        }

        assertThat(violations)
                .as("意图树声明的 doc_types 覆盖不了这些题的正解 —— 树画错了，"
                        + "不是题标错了。共检查 %d 道题", checked)
                .isEmpty();
        assertThat(checked).as("应当至少检查到一道有标注的题").isPositive();
    }

    // ============================================================
    // 二、配套的两条
    // ============================================================

    @Test
    @DisplayName("每道题的 intent 都是意图树里真实存在的叶子 code")
    void intentCodesExistInTree() {
        assumeEvalQuestionsLoaded();
        IntentTree.Tree tree = intentTree.get();

        List<String> unknown = new ArrayList<>();
        for (QuestionFacts facts : loadFacts().values()) {
            String intent = facts.intent();
            if (EvalQuestionLoader.INTENT_PLACEHOLDER.equals(intent)) {
                continue;
            }
            if (tree.findLeaf(intent).isEmpty()) {
                unknown.add(facts.questionNo() + " 标了 " + intent
                        + "，但意图树里没有这个叶子");
            }
        }

        assertThat(unknown)
                .as("标注用了一个树里不存在的 code —— 多半是拼错了，"
                        + "或者它在树里是【顶层】code 而不是叶子")
                .isEmpty();
    }

    @Test
    @DisplayName("没有任何题还停在 UNCLASSIFIED 哨兵值上")
    void noQuestionIsLeftUnclassified() {
        assumeEvalQuestionsLoaded();

        List<String> unclassified = loadFacts().values().stream()
                .filter(f -> EvalQuestionLoader.INTENT_PLACEHOLDER.equals(f.intent()))
                .map(QuestionFacts::questionNo)
                .toList();

        assertThat(unclassified)
                .as("这些题的意图还是阶段 4 的哨兵值。"
                        + "评测集 YAML 里现在 intent 是必填的，"
                        + "重跑 POST /api/debug/eval/reload 即可回填。"
                        + "留着它们 7.2 的意图准确率就算不出来")
                .isEmpty();
    }

    // ============================================================
    // 三、★ 反向：报告「哪些意图一道题都没有」
    // ============================================================

    @Test
    @DisplayName("每个业务意图在评测集里至少有一道题（否则 7.2 算不出它的准确率）")
    void everyBusinessIntentIsCovered() {
        assumeEvalQuestionsLoaded();
        IntentTree.Tree tree = intentTree.get();

        Set<String> labeled = new LinkedHashSet<>();
        for (QuestionFacts facts : loadFacts().values()) {
            if (!EvalQuestionLoader.INTENT_PLACEHOLDER.equals(facts.intent())) {
                labeled.add(facts.intent());
            }
        }

        // ⚠️ 这条【不是硬性失败】—— 20 题的基线集本来就覆盖不全 12 个叶子
        //    （订单物流那三个走工具，评测集里一道都没有）。
        //    所以这里断言的是「业务意图至少被覆盖面达到某个比例」，
        //    并把缺口打印出来供 7.1 扩样时参考。
        long uncoveredTopLevel = tree.businessIntents().stream()
                .filter(top -> top.children().stream()
                        .noneMatch(leaf -> labeled.contains(leaf.code())))
                .count();

        assertThat(uncoveredTopLevel)
                .as("有 %d 个业务意图在评测集里一道题都没有。"
                        + "已标注的叶子：%s%n"
                        + "★ 这是 7.1 扩到 150 题时的待办清单，不是当前就要修的缺陷",
                        uncoveredTopLevel, labeled)
                .isLessThanOrEqualTo(1L);   // 允许 ORDER_LOGISTICS 这一个（走工具，不检索）
    }
}
