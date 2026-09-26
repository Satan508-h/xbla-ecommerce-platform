package com.xbla.rag.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.agent.intent.IntentTree;
import com.xbla.rag.entity.EvalQuestion;
import com.xbla.rag.entity.QaLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EvalReportService#build} —— <b>指标算法本身</b>，纯单测（不需要 Spring、不需要库）。
 *
 * <h2>★ 为什么这个测试比它的被测代码还重要</h2>
 *
 * <p>指标算错的症状是<b>「数字看起来很正常」</b>。一个分母用错的准确率、
 * 一个把第 3 次当第 1 次取的检索命中率，都会印出一个漂亮的百分比 ——
 * 而报告是<b>拿来下结论的</b>，所以它错了比崩了严重得多。
 *
 * <p>这个类不做「跑一遍真实数据看数字合不合理」（那正是错的数字看起来的样子）。
 * 它<b>自己造数据</b>：一棵 4 个分支的合成意图树、10 条已知类型的切片、
 * 几条手工编排的 {@code qa_log} 行 —— 然后断言算出来的就是<b>手算的那个数</b>。
 *
 * <h2>★★ 每个断言都配一条反对照</h2>
 *
 * <p>本项目纪律（{@code CLAUDE.md}）：纯单测必须写正-反对照，否则断言可能恒真。
 * 这里最典型的两个是：
 * <ul>
 *   <li>{@code filtered_out} 排在 {@code not_recalled} 之前 —— 反对照是
 *       <b>同一份数据把 {@code filter.applied} 改成 false</b>，那时必须变成
 *       {@code not_recalled}。没有这一半，「它被判成 filtered_out」可能只是因为
 *       {@code not_recalled} 那支压根坏了</li>
 *   <li>{@code beyond_record_cutoff} vs {@code fusion_dropped} —— 反对照是
 *       <b>同一份数据只改 {@code sizes.fused} 这一个数</b>，判决必须翻转。
 *       两条一起才说明判据真的在读那个字段，而不是恒返回其中一个</li>
 * </ul>
 *
 * <h2>★ 树是合成的，不是加载 intent-tree.yml</h2>
 *
 * <p>加载真实树会让这个测试在<b>每次有人改意图树时红一次</b>，而它测的应该是
 * 指标算法。用合成树还有一个好处：{@code docTypesOf} 在这种树上的返回值
 * 是我一眼能背下来的，所以断言里的数字可以直接手算。
 * 真实树的正确性由 {@code IntentTreeConsistencyTest} 守。
 */
@DisplayName("EvalReportService · 指标算法")
class EvalReportServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ================================================================
    // 合成环境
    // ================================================================

    /**
     * <pre>
     *   KB_TOP            (KB,   BUSINESS)
     *     ├─ LEAF_A       doc_types [2, 4]
     *     └─ LEAF_B       doc_types [3]
     *   TOOL_TOP          (TOOL, BUSINESS)
     *     └─ TOOL_LEAF    doc_types []      ← 和真实树一样：TOOL 叶子不声明类型
     *   FALLBACK          (NONE, OUT_OF_SCOPE)
     *   CLARIFY_TOP       (NONE, CLARIFY)
     * </pre>
     *
     * <p>★ 刻意让 LEAF_A 和 LEAF_B 的 {@code doc_types} <b>不相交</b>：
     * 这样「分类到了兄弟叶子」在检索范围准确率上是<b>不一致</b>的，
     * 于是那个测试才有东西可测。真实树里 {@code COUPON} 和
     * {@code FULL_REDUCTION} 都是 {@code [3,4]}，那种「无害的相邻分歧」
     * 在这里恰好是被排除掉的情况。
     */
    private static IntentTree.Tree tree() {
        return new IntentTree.Tree(1, List.of(
                new IntentTree.TopIntent("KB_TOP", "知识库类", "d", "s",
                        IntentTree.Retrieval.KB, IntentTree.Role.BUSINESS, List.of(
                        new IntentTree.Leaf("LEAF_A", "叶子A", "d", List.of(2, 4),
                                List.of(), null, IntentTree.StructuredFact.NONE, List.of()),
                        new IntentTree.Leaf("LEAF_B", "叶子B", "d", List.of(3),
                                List.of(), null, IntentTree.StructuredFact.NONE, List.of())),
                        // ★ 9.3 起 tools 是 TopIntent / Leaf 的最后一个分量。
                        //   这里一律传空 —— 本类测的是报告指标的口径，不涉及工具绑定
                        List.of()),
                new IntentTree.TopIntent("TOOL_TOP", "工具类", "d", "s",
                        IntentTree.Retrieval.TOOL, IntentTree.Role.BUSINESS, List.of(
                        new IntentTree.Leaf("TOOL_LEAF", "工具叶子", "d", List.of(),
                                List.of(), null, IntentTree.StructuredFact.NONE, List.of())),
                        List.of()),
                new IntentTree.TopIntent("FALLBACK", "兜底", "d", "s",
                        IntentTree.Retrieval.NONE, IntentTree.Role.OUT_OF_SCOPE, List.of(),
                        List.of()),
                new IntentTree.TopIntent("CLARIFY_TOP", "澄清", "d", "s",
                        IntentTree.Retrieval.NONE, IntentTree.Role.CLARIFY, List.of(),
                        List.of())));
    }

    /** 切片 1~10 的类型：1-4 是 2，5-8 是 3，9 是 4，10 是 1。★ 手算得出的对照表 */
    private static Map<Long, Integer> chunks() {
        Map<Long, Integer> m = new LinkedHashMap<>();
        m.put(1L, 2);
        m.put(2L, 2);
        m.put(3L, 2);
        m.put(4L, 2);
        m.put(5L, 3);
        m.put(6L, 3);
        m.put(7L, 3);
        m.put(8L, 3);
        m.put(9L, 4);
        m.put(10L, 1);
        return m;
    }

    // ── 造行 ──────────────────────────────────────────────────────

    private static QaLog row(String no, int status, String intent, String detail) {
        QaLog r = new QaLog();
        r.setEvalQuestionNo(no);
        r.setEvalRunId("T");
        r.setStatus(status);
        r.setIntent(intent);
        r.setRetrievalDetail(detail);
        // ★ total 刻意留 null：延迟那一节要能处理「某几段没记」的行
        return r;
    }

    // ── 造题 ──────────────────────────────────────────────────────

    private static EvalQuestion q(String no, String intent, Long... gold) {
        EvalQuestion e = new EvalQuestion();
        e.setQuestionNo(no);
        e.setQuestion("题 " + no);
        e.setIntent(intent);
        e.setQuestionSet("stage7");
        e.setExpectedChunkIds(gold.length == 0 ? null : gold);
        if (gold.length == 0) {
            e.setExpectNoRetrieval(true);
        }
        return e;
    }

    /** 一段 {@code [{chunk_id, score}]} 的 JSON。只写 id 就够 —— 分值不参与任何指标 */
    private static String seg(long... ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"chunk_id\":").append(ids[i]).append(",\"score\":0.5}");
        }
        return sb.append(']').toString();
    }

    /** {@code final_top_k} 是<b>数字数组</b>，和别的段形状不同 —— 这个 helper 就为强调这一点 */
    private static String flat(long... ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * 拼一份 {@code retrieval_detail}。
     *
     * @param sizes 三态：{@code null} = <b>不写 sizes 段</b>（模拟 2026-09-21 之前的旧行）；
     *              非 null = 写，长度必须是 5（vector/keyword/fused/reranked/final）
     */
    private static String detail(String legs, String fused, String finalTopK,
                                 List<Integer> filterDocTypes, boolean applied,
                                 int[] sizes) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"vector_hits\":").append(legs);
        sb.append(",\"keyword_hits\":[]");
        sb.append(",\"fused\":").append(fused);
        sb.append(",\"reranked\":").append(finalTopK);
        sb.append(",\"final_top_k\":").append(finalTopK);
        sb.append(",\"filter\":{\"doc_types\":").append(filterDocTypes)
                .append(",\"applied\":").append(applied)
                .append(",\"pool_size\":50,\"reason\":\"")
                .append(applied ? "ok" : "no_declaration").append("\"}");
        if (sizes != null) {
            sb.append(",\"sizes\":{\"vector_hits\":").append(sizes[0])
                    .append(",\"keyword_hits\":").append(sizes[1])
                    .append(",\"fused\":").append(sizes[2])
                    .append(",\"reranked\":").append(sizes[3])
                    .append(",\"final_top_k\":").append(sizes[4]).append('}');
        }
        return sb.append('}').toString();
    }

    // ── 跑一遍 ────────────────────────────────────────────────────

    private static Map<String, Object> run(List<QaLog> rows, List<EvalQuestion> bank) {
        return run(rows, bank, tree());
    }

    /** ★ 允许换一棵树 —— 给「合成树本身就是被测变量」的测试用（如渲染顺序） */
    private static Map<String, Object> run(List<QaLog> rows, List<EvalQuestion> bank,
                                           IntentTree.Tree tree) {
        Map<String, EvalQuestion> byNo = new TreeMap<>();
        for (EvalQuestion e : bank) {
            byNo.put(e.getQuestionNo(), e);
        }
        Map<String, Set<Integer>> gold = new TreeMap<>();
        for (EvalQuestion e : bank) {
            if (e.getExpectedChunkIds() == null) {
                continue;
            }
            Set<Integer> types = new java.util.TreeSet<>();
            for (Long id : e.getExpectedChunkIds()) {
                Integer t = chunks().get(id);
                if (t != null) {
                    types.add(t);
                }
            }
            gold.put(e.getQuestionNo(), types);
        }
        return EvalReportService.build(tree, MAPPER,
                new EvalReportService.Inputs("T", rows, byNo, gold, chunks()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> report, String name) {
        return (Map<String, Object>) report.get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> parent, String name) {
        return (Map<String, Object>) parent.get(name);
    }

    private static long num(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }

    // ================================================================

    @Nested
    @DisplayName("一、取「第一次 status=1 的那次」")
    class FirstSuccess {

        /**
         * ★★ 这道题被闸门挡了两次才放行。
         *
         * <pre>
         *   第 1 行  status=3（反问）
         *   第 2 行  status=3（反问）
         *   第 3 行  status=1（成功）  ← 只有这一行的 final_top_k 是有效的
         * </pre>
         */
        @Test
        @DisplayName("★ 前两次被挡，第三次成功 → 取第三次，且被记成「第二或第三次才成功」")
        void thirdTimeIsTheCharm() {
            List<QaLog> rows = List.of(
                    row("T-1", 3, "CLARIFY_TOP", null),
                    row("T-1", 3, "CLARIFY_TOP", null),
                    row("T-1", 1, "LEAF_A",
                            detail(seg(1, 2), seg(1, 2), flat(1, 2), List.of(2, 4), true,
                                    new int[]{2, 0, 2, 2, 2})));

            Map<String, Object> r = run(rows, List.of(q("T-1", "LEAF_A", 1L, 2L)));
            Map<String, Object> ret = section(r, "检索");
            Map<String, Object> nth = sub(ret, "★取到了第几次成功");

            assertEquals(0L, num(nth, "第一次就成功"));
            assertEquals(1L, num(nth, "第二或第三次才成功"),
                    "★ 这一行是「闸门抖动」在检索分母上的投影 —— 它必须被看见，"
                            + "否则读者会以为这道题一次就跑通了");
            assertEquals(0L, num(nth, "★三次全被挡（不进任何检索分母）"));

            // ★ 它进了检索分母，而且命中了（gold={1,2} 都在 final_top_k 里）
            assertEquals(1L, num(sub(ret, "HitRate@5"), "n"));
            assertEquals(1.0, (double) sub(ret, "HitRate@5").get("值"));
        }

        @Test
        @DisplayName("★ 反对照：三次全被挡 → 不进检索分母，但必须被单独数出来")
        void allClarifiedNeverEntersTheDenominator() {
            List<QaLog> rows = List.of(
                    row("T-2", 3, "CLARIFY_TOP", null),
                    row("T-2", 3, "CLARIFY_TOP", null),
                    row("T-2", 3, "CLARIFY_TOP", null));

            Map<String, Object> r = run(rows, List.of(q("T-2", "LEAF_A", 1L)));
            Map<String, Object> ret = section(r, "检索");

            assertEquals(0L, num(sub(ret, "HitRate@5"), "n"),
                    "★ 检索【确实没发生】，它不该进分母 —— 算成「没召回到」"
                            + "会把闸门的行为记成检索的失败");
            assertEquals(1L, num(sub(ret, "★取到了第几次成功"), "★三次全被挡（不进任何检索分母）"));
            assertEquals(List.of("T-2"),
                    sub(ret, "★取到了第几次成功").get("三次全被挡的题号"));
        }
    }

    // ================================================================

    @Nested
    @DisplayName("二、意图准确率的分母（幸存者偏差）")
    class IntentDenominator {

        /**
         * ★★ 这一对守的是 {@code docs/06} §1.5 那条纪律的另一面：
         * 分类<b>先于</b>闸门跑，所以被挡的行也带着 intent。
         *
         * <p>构造得让两个口径给出<b>不同</b>的数，否则这个测试是恒真的：
         * <pre>
         *   逐行分母 = intent 非空的行 = 3   →  2/3 = 0.667
         *   若按 status=1 算 = 1 行         →  1/1 = 1.000   ← 假的 100%
         * </pre>
         */
        @Test
        @DisplayName("★ 被闸门挡掉的行也进分母 —— 只统计 status=1 会造出一个假的 100%")
        void clarifiedRowsStillCount() {
            List<QaLog> rows = List.of(
                    row("T-3", 3, "CLARIFY_TOP", null),   // 错：该题标的是 LEAF_A
                    row("T-3", 3, "CLARIFY_TOP", null),   // 错
                    row("T-3", 1, "LEAF_A", detail(seg(1), seg(1), flat(1),
                            List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})));

            Map<String, Object> r = run(rows, List.of(q("T-3", "LEAF_A", 1L)));
            Map<String, Object> rowLevel = sub(section(r, "意图"), "逐行");

            assertEquals(3L, num(rowLevel, "n"),
                    "★★ 分母是 intent 非空的行（=3），不是 status=1 的行（=1）。"
                            + "按后者算这里会得到 1/1 = 100% —— 而模型两次都分错了。"
                            + "『多挡一些』会机械地抬高准确率，这条断言就是那道闸门。");
            assertEquals(1L, num(rowLevel, "命中"));
            assertEquals(1.0 / 3, (double) rowLevel.get("值"), 1e-9);
        }

        @Test
        @DisplayName("★ 反对照：三次都分对 → 逐行与逐题两个口径都是 100%，且都不在摇摆桶里")
        void unanimousCorrectIsNotSwinging() {
            List<QaLog> rows = List.of(
                    row("T-4", 1, "LEAF_A", detail(seg(1), seg(1), flat(1),
                            List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})),
                    row("T-4", 1, "LEAF_A", detail(seg(1), seg(1), flat(1),
                            List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})),
                    row("T-4", 1, "LEAF_A", detail(seg(1), seg(1), flat(1),
                            List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})));

            Map<String, Object> intent = section(run(rows, List.of(q("T-4", "LEAF_A", 1L))), "意图");

            assertEquals(1.0, (double) sub(intent, "逐行").get("值"));
            assertEquals(1.0, (double) sub(intent, "逐题取众数").get("值"));
            assertEquals(0L, num(sub(intent, "★非全票一致的题（主指标）"), "n"),
                    "★ 全票一致的题不进摇摆桶");
            assertEquals(0L, num(sub(intent, "无唯一众数的题（诊断）"), "n"));
        }

        /**
         * ★★★ 这一条对应 §八.3 那个口径改写：摇摆桶的定义从
         * <b>「无唯一众数」</b>改成了<b>「非全票一致」</b>。
         *
         * <pre>
         *   2:1  →  有明确众数（所以不在「无众数」里）
         *         ★ 但它【必须】在摇摆桶里 —— 换个时间跑就可能翻
         *   3:0  →  两边都不在
         * </pre>
         *
         * <p>用 3 次重复时，一个真实 70/30 的题有约 34% 的概率给出 3:0 的假象 ——
         * 所以摇摆桶<b>永远是分母而不是分子</b>：它说明「这个数不稳」，
         * 不说明「这道题错了」。
         */
        @Test
        @DisplayName("★★ 2:1 有明确众数，但仍必须进摇摆桶 —— 旧定义会漏掉它")
        void splitVoteIsSwingingEvenWithAClearMode() {
            List<QaLog> rows = List.of(
                    // 3 次里分对 2 次。众数 = LEAF_A（对），但票型是 2:1
                    row("T-5", 1, "LEAF_A", detail(seg(1), seg(1), flat(1),
                            List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})),
                    row("T-5", 1, "LEAF_A", detail(seg(1), seg(1), flat(1),
                            List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})),
                    row("T-5", 1, "LEAF_B", detail(seg(5), seg(5), flat(5),
                            List.of(3), true, new int[]{1, 0, 1, 1, 1})));

            Map<String, Object> intent = section(run(rows, List.of(q("T-5", "LEAF_A", 1L))), "意图");

            assertEquals(1.0, (double) sub(intent, "逐题取众数").get("值"),
                    "前提：众数是 LEAF_A，这道题【算对】");
            assertEquals(1L, num(sub(intent, "★非全票一致的题（主指标）"), "n"),
                    "★★ 它算对了，但仍然是不稳的 —— 摇摆桶的口径是「非全票一致」，"
                            + "不是「错了」。旧口径（无唯一众数）在这里返回 0，"
                            + "而那个 0 会被读成「这道题很稳」。");
            assertEquals(0L, num(sub(intent, "无唯一众数的题（诊断）"), "n"),
                    "★ 反对照的另一半：2:1 是【有】众数的，所以它不该出现在诊断那一栏。"
                            + "两条一起才说明这两个桶在区分，而不是都恒为 0");
        }
    }

    // ================================================================

    @Nested
    @DisplayName("二·五、★★ gold 不是合法分类目标（2026-09-21 跑真实数据时发现）")
    class GoldNotAClassificationTarget {

        /**
         * ★★★ 这条断言守的是 T4 最重要的那个发现。
         *
         * <p>{@code IntentTree.classificationTargets()} 的规则是
         * <b>「行为相同的不区分」</b>：
         * <pre>
         *   retrieval = KB   →  展开到【叶子】
         *   retrieval ≠ KB   →  只算【一个】目标，用【顶层 code】
         * </pre>
         *
         * <p>所以 {@code TOOL_TOP} 是合法输出，而它的叶子 {@code TOOL_LEAF}
         * <b>模型永远不可能输出</b>。实测 stage7 那 15 道工具题的 gold
         * 恰恰是三个工具<b>叶子</b>码 —— 它们在意图准确率的分子里恒为 0。
         *
         * <p>实测后果：{@code 84.3%} 里有 <b>9.4 个百分点</b>是这条接缝造成的，
         * 不是模型造成的。
         */
        @Test
        @DisplayName("★★ 工具题的叶子 gold 恒错 → 可比口径剔除它，并单列顶层命中")
        void toolLeafGoldIsExcludedFromTheComparableMetric() {
            List<QaLog> rows = List.of(
                    // K-9：KB 题，分对
                    row("K-9", 1, "LEAF_A", detail(seg(1), seg(1), flat(1),
                            List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})),
                    // T-9：工具题，gold 是【叶子】码，而模型输出的是【顶层】码
                    row("T-9", 1, "TOOL_TOP", detail(seg(1), seg(1), flat(1),
                            List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})));

            Map<String, Object> intent = section(
                    run(rows, List.of(q("K-9", "LEAF_A", 1L), q("T-9", "TOOL_LEAF"))),
                    "意图");

            // ── 原始口径：把 T-9 算成错的 ──
            assertEquals(2L, num(sub(intent, "逐题取众数"), "n"));
            assertEquals(1L, num(sub(intent, "逐题取众数"), "命中"),
                    "★ 原始口径下 T-9 恒错 —— 模型判【对】了类别，"
                            + "但它永远输出不出 gold 那个叶子码");

            // ── 可比口径：把它剔除 ──
            assertEquals(1L, num(sub(intent, "★★gold不是合法分类目标的题"), "n"));
            assertEquals(1L, num(sub(intent, "★★可比口径的准确率"), "n"),
                    "★★ 分母剔掉了 T-9 —— 它测不出分类质量");
            assertEquals(1L, num(sub(intent, "★★可比口径的准确率"), "命中"));
            assertEquals(1.0, (double) sub(intent, "★★可比口径的准确率").get("值"),
                    "★ 剩下那道真的判对了，所以可比口径是 100%");

            // ── 工具题的正确量法：比到顶层 ──
            Map<String, Object> tool = sub(intent, "★工具题判到顶层这一级");
            assertEquals(1L, num(sub(tool, "可比口径_判成工具类"), "n"));
            assertEquals(1L, num(sub(tool, "可比口径_判成工具类"), "命中"),
                    "★★ 「code 完全一致」对工具题【永远不成立】——"
                            + "正确的量法是「有没有被路由到工具这一类」，"
                            + "那才是这些题真正在测的东西");
            assertEquals(List.of(), tool.get("误路由的题"));
        }

        @Test
        @DisplayName("★ 反对照：工具题被判成 KB 叶子 → 必须进「误路由的题」")
        void misroutedToolQuestionIsReported() {
            List<QaLog> rows = List.of(
                    row("T-10", 1, "LEAF_A", detail(seg(1), seg(1), flat(1),
                            List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})));

            Map<String, Object> intent = section(
                    run(rows, List.of(q("T-10", "TOOL_LEAF"))), "意图");

            Map<String, Object> tool = sub(intent, "★工具题判到顶层这一级");
            assertEquals(1L, num(sub(tool, "可比口径_判成工具类"), "n"));
            assertEquals(0L, num(sub(tool, "可比口径_判成工具类"), "命中"),
                    "★★ 反对照：上一条的 100% 不是恒真的 —— "
                            + "工具题被判成知识库题时必须为 0");
            assertEquals(1, ((List<?>) tool.get("误路由的题")).size());
            assertTrue(String.valueOf(((List<?>) tool.get("误路由的题")).get(0))
                            .contains("应为工具"),
                    "★ 明细要说清「本该去哪儿、实际去了哪儿」—— "
                            + "只报一个题号的话，读的人还得自己回去查");
        }
    }

    // ================================================================

    @Nested
    @DisplayName("三、归因：filtered_out 必须排在 not_recalled 之前")
    class FilteredOut {

        /**
         * 数据是同一份，<b>只改 {@code filter.applied} 一个布尔</b>：
         * <pre>
         *   applied=true  + 范围 [3]  →  正解（类型 2）整体被挡在池子外
         *                                →  filtered_out（因）
         *   applied=false              →  没有任何过滤，两路真的都没找到
         *                                →  not_recalled（果）
         * </pre>
         *
         * <p>★★ 这一对是整份测试里最重要的：它证明「filtered_out 排在前面」
         * 这件事<b>真的在起作用</b>，而不是「反正都返回 filtered_out」。
         */
        @Test
        @DisplayName("★★ 同一份数据，只改 applied 一个布尔 → 归因必须翻转")
        void theSameDataFlipsOnOneBoolean() {
            long gold = 1L;        // 切片 1 的类型是 2
            // 两路、融合、最终 —— 全都没有 1。也就是「检索真的没找到它」
            String legs = seg(5, 6);
            String fused = seg(5, 6);
            String top = flat(5, 6);

            Map<String, Object> filtered = run(
                    List.of(row("T-6", 1, "LEAF_A",
                            detail(legs, fused, top, List.of(3), true, new int[]{2, 0, 2, 2, 2}))),
                    List.of(q("T-6", "LEAF_A", gold)));

            Map<String, Object> unfiltered = run(
                    List.of(row("T-6", 1, "LEAF_A",
                            detail(legs, fused, top, List.of(3), false, new int[]{2, 0, 2, 2, 2}))),
                    List.of(q("T-6", "LEAF_A", gold)));

            assertEquals(1L, num(sub(section(filtered, "归因"), "桶"), "filtered_out"),
                    "★ 范围 [3] 挡不住切片 1（类型 2）—— 但没有过滤时它同样没出现，"
                            + "所以「检索没找到」是果，而「范围选错了」才是因");
            assertEquals(0L, num(sub(section(filtered, "归因"), "桶"), "not_recalled"),
                    "★ 判定必须排在 not_recalled 之前，否则这道题会被读成"
                            + "「召回/切分的问题」—— 而真正该改的是分类");

            assertEquals(1L, num(sub(section(unfiltered, "归因"), "桶"), "not_recalled"),
                    "★★ 反对照：把 applied 改成 false 之后，同样的行【必须】变成 not_recalled。"
                            + "没有这一半，上面那条断言可能只是因为 not_recalled 那支坏了");
            assertEquals(0L, num(sub(section(unfiltered, "归因"), "桶"), "filtered_out"));
        }

        @Test
        @DisplayName("★ 反对照：部分正解被挡住时不判 filtered_out —— 剩下的仍可能被找到")
        void partialFilterIsNotFilteredOut() {
            // 正解 = 切片 1（类型 2）+ 切片 5（类型 3）；过滤范围 [3] 只放行后者
            Map<String, Object> r = run(
                    List.of(row("T-7", 1, "LEAF_A",
                            detail(seg(5), seg(5), flat(5), List.of(3), true,
                                    new int[]{1, 0, 1, 1, 1}))),
                    List.of(q("T-7", "LEAF_A", 1L, 5L)));

            Map<String, Object> attr = section(r, "归因");
            assertEquals(0L, num(sub(attr, "桶"), "filtered_out"),
                    "★ 过滤【没有】把这道题的正解整体挡掉（切片 5 还在），"
                            + "所以不能判它 —— 判了就等于说「检索压根没机会」，而那是假的");
            assertEquals(1L, num(attr, "部分被过滤的题"),
                    "★ 但它必须被数出来：读者要知道有多少道题处在「正解缺了一半」的状态");
        }
    }

    // ================================================================

    @Nested
    @DisplayName("四、归因：截断让 fusion_dropped 变成「不知道」")
    class Truncation {

        /**
         * <pre>
         *   两路有正解（切片 1）· fused 的记录里【没有】它
         *
         *   sizes.fused = 2，记录也是 2 条  →  记录完整  →  融合真的丢了它  →  fusion_dropped
         *   sizes.fused = 37，记录只有 2 条 →  被截断过  →  可能只是排在第 25 名  →  不知道
         *   没有 sizes 段                  →  旧数据，无从判断  →  不知道
         * </pre>
         *
         * <p>★★ 注意第一行：{@code fusion_dropped} 能成立，靠的是
         * <b>「记录完整」这个正面证据</b>，而不是「没写 sizes 就当没截断」。
         * 这个区别就是整个 {@code sizes} 扩段存在的意义。
         */
        @Test
        @DisplayName("★★ 只改 sizes.fused 一个数 → fusion_dropped 与 beyond_record_cutoff 必须翻转")
        void truncationEvidenceFlipsTheVerdict() {
            String legs = seg(1, 2);     // 正解切片 1 在向量路里
            String fusedRec = seg(2, 3); // ★ 融合的记录里没有它
            String top = flat(2, 3);

            Map<String, Object> complete = run(
                    List.of(row("T-8", 1, "LEAF_A",
                            detail(legs, fusedRec, top, List.of(2, 4), true,
                                    new int[]{2, 0, 2, 1, 1}))),   // sizes.fused=2 == 记录 2 条
                    List.of(q("T-8", "LEAF_A", 1L)));

            Map<String, Object> truncated = run(
                    List.of(row("T-8", 1, "LEAF_A",
                            detail(legs, fusedRec, top, List.of(2, 4), true,
                                    new int[]{2, 0, 37, 1, 1}))),  // sizes.fused=37 > 记录 2 条
                    List.of(q("T-8", "LEAF_A", 1L)));

            Map<String, Object> legacy = run(
                    List.of(row("T-8", 1, "LEAF_A",
                            detail(legs, fusedRec, top, List.of(2, 4), true, null))),  // 无 sizes
                    List.of(q("T-8", "LEAF_A", 1L)));

            assertEquals(1L, num(sub(section(complete, "归因"), "桶"), "fusion_dropped"),
                    "★ 记录完整（sizes 说只有 2 条，记录也写了 2 条）→ "
                            + "「融合把它丢了」是一个【有证据的指控】");
            assertEquals(0L, num(sub(section(complete, "归因"), "桶"), "beyond_record_cutoff"));

            assertEquals(1L, num(sub(section(truncated, "归因"), "桶"), "beyond_record_cutoff"),
                    "★★ 记录被截断过（真实 37 条、只写了 2 条）→ 「融合丢了它」和"
                            + "「它排在第 25 名」分不开，判决只能是【不知道】");
            assertEquals(0L, num(sub(section(truncated, "归因"), "桶"), "fusion_dropped"),
                    "★ 没有这一半，上面那条可能只是因为判据恒返回 beyond_record_cutoff");

            assertEquals(1L, num(sub(section(legacy, "归因"), "桶"), "beyond_record_cutoff"),
                    "★★ 旧数据（没有 sizes 段）也必须落到【不知道】。"
                            + "把缺席当成「没截断」就是拿一个没测过的假设去支撑归因 —— "
                            + "而那正是 sizes 扩段要消灭的东西。");

            // ★★ 「有没有 sizes 段」本身必须被数出来 —— 它是读者的第一个判断依据：
            //    无sizes段的行 > 0 时，fusion_dropped 的那个 0 不构成任何证据
            Map<String, Object> detComplete = sub(section(complete, "归因"), "★截断可判定性");
            Map<String, Object> detTruncated = sub(section(truncated, "归因"), "★截断可判定性");
            Map<String, Object> detLegacy = sub(section(legacy, "归因"), "★截断可判定性");

            assertEquals(1L, num(detComplete, "有sizes段的行"));
            assertEquals(0L, num(detComplete, "无sizes段的行"));

            assertEquals(1L, num(detTruncated, "有sizes段的行"),
                    "★ truncated 那一轮 sizes.fused 写的是 37，段本身是在的 —— "
                            + "「截断过」和「没这个字段」是两件事，别混");
            assertEquals(0L, num(detTruncated, "无sizes段的行"));

            assertEquals(0L, num(detLegacy, "有sizes段的行"));
            assertEquals(1L, num(detLegacy, "无sizes段的行"),
                    "★★ 这一格是给读报告的人看的：它 > 0 就说明这批数据里"
                            + "有一部分行的截断情况【无从判断】—— "
                            + "于是 fusion_dropped 那个 0 不能当成「RRF 没丢东西」");
        }
    }

    // ================================================================

    @Nested
    @DisplayName("五、澄清边界混淆矩阵")
    class ClarifyMatrix {

        /**
         * 四格各来一道题，并且让其中一道题<b>自己跟自己不一致</b>（抖动）。
         *
         * <pre>
         *   C-1  标注=CLARIFY_TOP  行为=反问          →  该反问_且反问了
         *   C-2  标注=CLARIFY_TOP  行为=放行（分类失败）→  该反问_没反问
         *   C-3  标注=LEAF_A       行为=反问          →  不该反问_却反问了
         *   C-4  标注=LEAF_A       行为=3,1,1（抖）   →  多数票=放行 → 不该反问_没反问 + 抖动
         * </pre>
         */
        @Test
        @DisplayName("★ 四格 + 抖动：同题 3 次里 status 不一致 = 用户会时而被反问时而被回答")
        void fourCellsAndJitter() {
            List<QaLog> rows = List.of(
                    row("C-1", 3, "CLARIFY_TOP", null),
                    row("C-1", 3, "CLARIFY_TOP", null),
                    row("C-1", 3, "CLARIFY_TOP", null),

                    // ★ 分类失败：intent 是 null，而 status 照常是 1
                    //   （ClarificationDecider 明确「分类失败不澄清」）
                    row("C-2", 1, null, detail(seg(1), seg(1), flat(1),
                            List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})),

                    row("C-3", 3, "CLARIFY_TOP", null),
                    row("C-3", 3, "CLARIFY_TOP", null),
                    row("C-3", 3, "CLARIFY_TOP", null),

                    row("C-4", 3, "CLARIFY_TOP", null),
                    row("C-4", 1, "LEAF_A", detail(seg(1), seg(1), flat(1),
                            List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})),
                    row("C-4", 1, "LEAF_A", detail(seg(1), seg(1), flat(1),
                            List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})));

            List<EvalQuestion> bank = new ArrayList<>();
            bank.add(q("C-1", "CLARIFY_TOP"));
            bank.add(q("C-2", "CLARIFY_TOP"));
            bank.add(q("C-3", "LEAF_A", 1L));
            bank.add(q("C-4", "LEAF_A", 1L));

            Map<String, Object> m = section(run(rows, bank), "澄清边界");
            Map<String, Object> rowCells = sub(m, "逐行");
            Map<String, Object> qCells = sub(m, "逐题（多数票）");

            // ── 逐行 ──
            assertEquals(4L, num(rowCells, "分母_该反问的行"),
                    "★ 标了 CLARIFY_TOP 的只有 C-1(3 行) 和 C-2(1 行) —— "
                            + "C-3 标的是 LEAF_A，它属于「不该反问」那一侧，不进这个分母");
            assertEquals(6L, num(rowCells, "分母_不该反问的行"), "C-3 三行 + C-4 三行");
            assertEquals(3L, num(rowCells, "★该反问_且反问了"));
            assertEquals(1L, num(rowCells, "★该反问_没反问（漏）"),
                    "★ C-2：标注要澄清，但分类失败（intent=null），"
                            + "而分类失败【不澄清】—— 于是它照常检索了。"
                            + "这一格是「系统的故障被伪装成用户的问题」的反面教材");
            assertEquals(4L, num(rowCells, "★不该反问_却反问了（假阳）"),
                    "★ C-3 三行【加上 C-4 的第一行】= 4。"
                            + "C-4 的三次是 3,1,1 —— 它第一次也反问了（那是它抖动的那一次）。"
                            + "★ 我第一版把这格写成 3，漏掉了 C-4 那一行："
                            + "逐行矩阵数的是【行】，不是【题】");
            assertEquals(2L, num(rowCells, "不该反问_没反问"),
                    "★ 不该反问的共 6 行（C-3 三行 + C-4 三行），其中 4 行反问了 —— "
                            + "剩下 2 行（C-4 的第二次和第三次）没反问");

            // ── 逐题 ──
            assertEquals(4L, num(qCells, "分母"));
            assertEquals(1L, num(qCells, "★该反问_且反问了"));
            assertEquals(1L, num(qCells, "★该反问_没反问（漏）"));
            assertEquals(1L, num(qCells, "★不该反问_却反问了（假阳）"));
            assertEquals(1L, num(qCells, "不该反问_没反问"));

            // ── 抖动 ──
            assertEquals(1L, num(sub(m, "★闸门抖动的题"), "n"),
                    "★ C-4 三次里一次反问、两次放行 —— 用户会【时而被反问、时而被回答】。"
                            + "★ 这个量在意图探针里测不出来（探针不过闸门），"
                            + "只有端到端重复跑才看得见");
            assertEquals(List.of("C-4"), sub(m, "★闸门抖动的题").get("题号"));
        }
    }

    // ================================================================

    @Nested
    @DisplayName("六、检索范围准确率的分母")
    class ScopeDenominator {

        /**
         * ★★ 这道题是「两个分母的差」的显微镜。
         *
         * <pre>
         *   K-1  标注 LEAF_A（KB）· 分成 LEAF_B  →  范围 {2,4} vs {3}  →  不一致
         *   K-2  标注 TOOL_LEAF   · 分成 TOOL_LEAF →  ∅ vs ∅          →  恒真「一致」
         * </pre>
         *
         * <ul>
         *   <li>KB 口径：1/1 = 0% —— <b>它说的是实话</b>（分类真的错了）</li>
         *   <li>全体口径：(1+1)/2 = 50% —— 被 K-2 那格必然正确的答案抬高了</li>
         * </ul>
         *
         * <p>把 K-2 放进分母，指标只会在「工具题越多」时越好看，
         * 而它衡量分类质量的能力一点没变。
         */
        @Test
        @DisplayName("★★ 非 KB 题两侧都是空集 → 恒真「一致」→ 会把指标掺水")
        void nonKbQuestionsAreVacuouslyCorrect() {
            List<QaLog> rows = List.of(
                    row("K-1", 1, "LEAF_B", detail(seg(5), seg(5), flat(5),
                            List.of(3), true, new int[]{1, 0, 1, 1, 1})),
                    row("K-2", 1, "TOOL_LEAF", detail(seg(5), seg(5), flat(5),
                            List.of(3), true, new int[]{1, 0, 1, 1, 1})));

            Map<String, Object> scope = section(
                    run(rows, List.of(q("K-1", "LEAF_A", 1L), q("K-2", "TOOL_LEAF"))),
                    "检索范围");

            assertEquals(1L, num(sub(scope, "★主数字_KB题"), "n"),
                    "★ 主数字的分母只在 KB 题上");
            assertEquals(0L, num(sub(scope, "★主数字_KB题"), "命中"),
                    "★ LEAF_A 声明 {2,4}，分成了 LEAF_B 的 {3} —— 真的不一致");
            assertEquals(0.0, (double) sub(scope, "★主数字_KB题").get("值"));

            assertEquals(2L, num(sub(scope, "全体"), "n"));
            assertEquals(1L, num(sub(scope, "全体"), "命中"));
            assertEquals(0.5, (double) sub(scope, "全体").get("值"),
                    "★★ 全体口径是 50% —— K-2 那格是【恒真的正确】，"
                            + "它把 0% 抬到了 50%。这就是为什么主数字不能用这个分母。");

            assertEquals(1L, num(sub(scope, "非KB题_单列"), "n"));
            assertEquals(1L, num(sub(scope, "非KB题_单列"), "命中"));
        }
    }

    // ================================================================

    @Nested
    @DisplayName("七、过度检索率（两个口径）")
    class OverRetrieval {

        /**
         * <pre>
         *   标注 = LEAF_A（doc_types {2,4}）
         *   分类 = LEAF_B（doc_types {3}）
         *   送进 prompt 的 5 条 = 切片 [5,6,7,8,9] → 类型 [3,3,3,3,4]
         *
         *   对标注叶子：只有 9（类型 4）在里面  →  越界 4 / 5
         *   对分类叶子：5,6,7,8（类型 3）在里面 →  越界 1 / 5   （9 的类型 4 越界）
         * </pre>
         *
         * <p>★ 两个数的差（4 与 1）就是「分类选错了范围造成的额外越界」——
         * 合成一个数就把它丢了。这正是 {@code docs/06} §1.4 那条
         * 「两个数字的差值本身携带信息」。
         */
        @Test
        @DisplayName("★ 两个口径给出不同的数 —— 差值就是「分类选错范围」的代价")
        void twoScopesDiffer() {
            Map<String, Object> r = run(
                    List.of(row("O-1", 1, "LEAF_B",
                            detail(seg(5, 6, 7, 8, 9), seg(5, 6, 7, 8, 9),
                                    flat(5, 6, 7, 8, 9), List.of(3), true,
                                    new int[]{5, 0, 5, 5, 5}))),
                    List.of(q("O-1", "LEAF_A", 1L)));

            Map<String, Object> over = section(r, "过度检索");

            assertEquals(4L, num(sub(over, "★对标注叶子"), "越界条数"),
                    "★ 标注要的是 {2,4}：5~8 是类型 3、9 是类型 4 —— 只有 9 在范围内");
            assertEquals(5L, num(sub(over, "★对标注叶子"), "上下文条数"));
            assertEquals(0.8, (double) sub(over, "★对标注叶子").get("值"), 1e-9);

            assertEquals(1L, num(sub(over, "★对分类叶子"), "越界条数"),
                    "★ 分类要的是 {3}：5~8 都在，只有 9（类型 4）越界");
            assertEquals(0.2, (double) sub(over, "★对分类叶子").get("值"), 1e-9);
        }
    }

    // ================================================================

    @Nested
    @DisplayName("八、分位数与 n<5 纪律")
    class PercentileAndSliceDiscipline {

        /**
         * 最近秩：{@code sorted[ceil(p*n)-1]}，不插值。
         *
         * <p>★ 断言的是「它返回的值<b>确实出现过</b>」——
         * 这正是选这个算法的全部理由。
         */
        @Test
        @DisplayName("★ 最近秩永远返回一个真的观测值（不插值）")
        void nearestRankReturnsAnObservedValue() {
            List<Integer> values = List.of(10, 20, 30, 40, 50, 60, 70, 80, 90, 100);

            assertEquals(50, EvalReportService.percentile(values, 0.50),
                    "ceil(0.5*10)=5 → 第 5 个 → 50");
            assertEquals(100, EvalReportService.percentile(values, 0.95),
                    "ceil(0.95*10)=10 → 第 10 个 → 100");
            assertEquals(10, EvalReportService.percentile(values, 0.01));

            // ★ 反对照：插值出来的 55 或 95.5 都【不是】用户真实体验过的值
            assertTrue(values.contains(EvalReportService.percentile(values, 0.50)),
                    "★ P50 必须是列表里真实存在的元素。线性插值会给 55 —— "
                            + "而没有任何一次请求花过 55ms");
            assertTrue(values.contains(EvalReportService.percentile(values, 0.95)));

            // ★ 单个值的边界：P50 == P95 == 它自己，不能越界
            assertEquals(7, EvalReportService.percentile(List.of(7), 0.95));
            assertNull(EvalReportService.percentile(List.of(), 0.95));
        }

        /**
         * ★ §七.2 的硬要求：每个切片印 n，n &lt; 5 标「不作为结论」。
         *
         * <p>本项目最小的意图只有 3 道题，而一道题就是 33 个百分点。
         * 一个 n=3 的「100%」和一个 n=15 的「87%」放在同一张表里，
         * 前者的噪声比两者之差还大。
         */
        @Test
        @DisplayName("★ n=3 的切片必须带 可信=false，n=5 的才是 可信=true")
        void smallSlicesAreFlaggedNotDropped() {
            List<QaLog> rows = new ArrayList<>();
            List<EvalQuestion> bank = new ArrayList<>();
            // 5 道 LEAF_A —— 正好到线
            for (int i = 1; i <= 5; i++) {
                rows.add(row("N-" + i, 1, "LEAF_A", detail(seg(1), seg(1), flat(1),
                        List.of(2, 4), true, new int[]{1, 0, 1, 1, 1})));
                bank.add(q("N-" + i, "LEAF_A", 1L));
            }
            // 3 道 LEAF_B —— 在线下
            for (int i = 6; i <= 8; i++) {
                rows.add(row("N-" + i, 1, "LEAF_B", detail(seg(5), seg(5), flat(5),
                        List.of(3), true, new int[]{1, 0, 1, 1, 1})));
                bank.add(q("N-" + i, "LEAF_B", 5L));
            }

            Map<String, Object> slices = sub(section(run(rows, bank), "意图"), "按标注意图切片");

            assertEquals(5L, num(sub(slices, "LEAF_A"), "n"));
            assertTrue((boolean) sub(slices, "LEAF_A").get("可信"),
                    "n=5 正好到线，可信");

            assertEquals(3L, num(sub(slices, "LEAF_B"), "n"));
            assertFalse((boolean) sub(slices, "LEAF_B").get("可信"),
                    "★ n=3 < 5 → 可信=false");
            assertNotNull(sub(slices, "LEAF_B").get("★"),
                    "★★ 关键：它【照报】，只是旁边写了「不作为结论」。"
                            + "把小样本直接过滤掉会丢信息 —— 读者需要知道"
                            + "「这一类我只有 3 道题」，那本身就是一个结论");
            assertEquals(1.0, (double) sub(slices, "LEAF_B").get("值"),
                    "★ 数字本身是 100%，很漂亮 —— 而它由一个 3 道题的样本支撑。"
                            + "这就是为什么标记比过滤重要");
        }
    }

    // ================================================================
    // 九、逐题明细（阶段 7 · T6）
    // ================================================================

    /**
     * <h2>★★ 这个类守的是「汇总 == 逐题行重新算一遍」</h2>
     *
     * <p>阶段 7 的 A/B 要靠逐题行做翻转矩阵。但逐题行<b>不是</b>第二套算法 ——
     * 它是各段汇总的<b>原始材料</b>。两者对不上的话，报告里会出现
     * 「检索段说 HitRate 2/3、逐题表数出来 1/3」，两个数各自都自洽，
     * 合起来是一句谎话，而<b>没有任何东西会报错</b>。
     *
     * <p>所以这里把每一段都重算一遍，断言<b>逐字相等</b>（double 也不给容差 ——
     * 逐题行刻意不做四舍五入，就是为了让这个断言能成立）。
     *
     * <h2>★★ 第二个断言是「把 null 当成 false 会得出不一样的数」</h2>
     *
     * <p>三态字段（{@code 意图正确} / {@code 命中@5}）是这套设计的全部要害。
     * 只断言「null 存在」是不够的 —— 那证明不了它<b>承重</b>。
     * 所以再加一条：用「把 null 当 false」的朴素算法算一遍，得到的数
     * <b>必须和正确算法不同</b>。相同的话，三态就是装饰品，
     * 删掉它这个测试照样绿。
     */
    @Nested
    @DisplayName("九、★★ 逐题明细：重新汇总必须精确等于各段")
    class PerQuestionDetail {

        /**
         * 六种情况各一道，每一道都对应逐题表里某一条路径：
         *
         * <pre>
         *   K1  KB · 命中 · 三次都成功
         *   K2  KB · 没命中（两路原始输出里都没有）
         *   K3  KB · 命中，但【意图分错】了（gold=LEAF_B，判成 LEAF_A）
         *   K4  KB · 三次全被闸门挡（status 全是 3）→ 不进检索分母
         *   T1  工具题 · gold 是 TOOL_LEAF，【不是合法分类目标】→ 意图正确=null
         *   N1  兜底题 · 声明不检索，但 FALLBACK 是合法目标 → 意图正确=true
         * </pre>
         */
        private Map<String, Object> fixture() {
            List<EvalQuestion> bank = List.of(
                    q("K1", "LEAF_A", 1L),
                    q("K2", "LEAF_A", 2L),
                    q("K3", "LEAF_B", 5L),
                    q("K4", "LEAF_A", 3L),
                    q("T1", "TOOL_LEAF"),
                    q("N1", "FALLBACK"));

            List<QaLog> rows = new ArrayList<>();
            // K1：gold=1（类型 2），上下文 [1,5,6,7,8] → 命中，越界 4 条（5/6/7/8 不在 {2,4}）
            for (int i = 0; i < 3; i++) {
                rows.add(row("K1", 1, "LEAF_A", detail(seg(1, 5, 6, 7, 8), seg(1, 5, 6, 7, 8),
                        flat(1, 5, 6, 7, 8), List.of(2, 4), true, new int[]{5, 0, 5, 5, 5})));
            }
            // K2：gold=2（类型 2），上下文 [3,4,9] 里没有它，两路原始输出里也没有 → not_recalled
            for (int i = 0; i < 3; i++) {
                rows.add(row("K2", 1, "LEAF_A", detail(seg(3, 4, 9), seg(3, 4, 9),
                        flat(3, 4, 9), List.of(2, 4), true, new int[]{3, 0, 3, 3, 3})));
            }
            // K3：gold=5（类型 3），命中。但意图判成 LEAF_A（gold 是 LEAF_B）→ 意图正确=false
            for (int i = 0; i < 3; i++) {
                rows.add(row("K3", 1, "LEAF_A", detail(seg(5, 6), seg(5, 6),
                        flat(5, 6), List.of(3), true, new int[]{2, 0, 2, 2, 2})));
            }
            // K4：三次全被闸门挡掉 —— 检索确实没发生，不进任何检索分母
            for (int i = 0; i < 3; i++) {
                rows.add(row("K4", 3, "LEAF_A", null));
            }
            // T1 / N1：工具题与兜底题，声明不检索，压根没有 retrieval_detail
            for (int i = 0; i < 3; i++) {
                rows.add(row("T1", 1, "TOOL_TOP", null));
                rows.add(row("N1", 1, "FALLBACK", null));
            }
            return run(rows, bank);
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> detailRows(Map<String, Object> report) {
            return (List<Map<String, Object>>) section(report, "逐题").get("行");
        }

        @Test
        @DisplayName("★★ 检索段：分母 / HitRate / Recall / MRR 全部由逐题行重算得出，逐字相等")
        void retrievalSectionIsReproducibleFromRows() {
            Map<String, Object> report = fixture();
            List<Map<String, Object>> rows = detailRows(report);
            Map<String, Object> ret = section(report, "检索");

            List<Map<String, Object>> inDen = rows.stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("进检索分母")))
                    .toList();
            assertEquals(3, inDen.size(),
                    "★ 只有 K1/K2/K3 进分母。K4 三次全被挡（检索没发生）、"
                            + "T1/N1 声明不检索 —— 它们既不在分子也不在分母里");
            assertEquals(num(sub(ret, "分母"), "★分母_测到的题数"), (long) inDen.size());

            long hit = inDen.stream().filter(r -> Boolean.TRUE.equals(r.get("命中@5"))).count();
            assertEquals(2, hit);
            assertEquals(num(sub(ret, "HitRate@5"), "命中"), hit);

            double recall = inDen.stream()
                    .mapToDouble(r -> (double) r.get("recall@5")).sum() / inDen.size();
            assertEquals((double) sub(ret, "Recall@5").get("值"), recall,
                    "★★ 逐题行的 recall@5 【刻意不做四舍五入】就是为了让这里能逐字相等。"
                            + "舍入过的话「逐题表重算不出汇总」，而读者会以为是自己数错了");

            double mrr = inDen.stream()
                    .mapToDouble(r -> (double) r.get("mrr@5")).sum() / inDen.size();
            assertEquals((double) sub(ret, "MRR@5").get("值"), mrr);
        }

        @Test
        @DisplayName("★★ 归因段与过度检索段：同样由逐题行重算得出")
        void attributionAndOverRetrievalAreReproducible() {
            Map<String, Object> report = fixture();
            List<Map<String, Object>> rows = detailRows(report);

            Map<String, Object> buckets = sub(section(report, "归因"), "桶");
            List<Map<String, Object>> judged = rows.stream()
                    .filter(r -> r.get("归因") != null)
                    .toList();
            assertEquals(3, judged.size(), "★ 和检索分母同宽 —— 这是刻意的");
            assertEquals(num(buckets, "★分母"), (long) judged.size());
            for (String bucket : List.of("ok", "not_recalled", "filtered_out",
                    "fusion_dropped", "rerank_dropped", "beyond_record_cutoff", "gold_missing")) {
                long fromRows = judged.stream()
                        .filter(r -> bucket.equals(r.get("归因"))).count();
                assertEquals(num(buckets, bucket), fromRows,
                        "桶 " + bucket + " 两边必须相等。★ 汇总与逐题各写一份判定的话，"
                                + "症状是报告里两个数都对、合起来是谎话");
            }
            assertEquals(2L, num(buckets, "ok"));
            assertEquals(1L, num(buckets, "not_recalled"));

            Map<String, Object> over = sub(section(report, "过度检索"), "★对标注叶子");
            long oob = rows.stream()
                    .filter(r -> r.get("越界切片数") != null)
                    .mapToLong(r -> (long) r.get("越界切片数")).sum();
            // ★ 分母是「类型已知的上下文条数」—— 切片已不在库里时它既不算越界
            //   也不算在内，所以必须把「未知类型切片数」减掉，两边才对得上
            long ctxKnown = rows.stream()
                    .filter(r -> r.get("上下文条数") != null)
                    .mapToLong(r -> (long) r.get("上下文条数") - (long) r.get("未知类型切片数"))
                    .sum();
            assertEquals(num(over, "越界条数"), oob);
            assertEquals(num(over, "上下文条数"), ctxKnown);
            assertEquals(10L, ctxKnown, "K1 的 5 条 + K2 的 3 条 + K3 的 2 条");
        }

        @Test
        @DisplayName("★★ 意图可比口径：分母是「gold 是合法分类目标」的题，不是题库总题数")
        void intentComparableDenominatorMatchesRows() {
            Map<String, Object> report = fixture();
            List<Map<String, Object>> rows = detailRows(report);

            Map<String, Object> comparable = sub(section(report, "意图"), "★★可比口径的准确率");
            long judgeable = rows.stream().filter(r -> r.get("意图正确") != null).count();
            long correct = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("意图正确"))).count();

            assertEquals(5, judgeable, "★ T1 的 gold 是 TOOL_LEAF —— 工具叶子码，"
                    + "分类器被要求输出的是顶层 TOOL_TOP，所以这一格【不可判】");
            assertEquals(4, correct, "K1/K2/K4/N1 对，K3 分错（gold=LEAF_B 判成 LEAF_A）");
            assertEquals(num(comparable, "n"), judgeable);
            assertEquals(num(comparable, "命中"), correct);
        }

        /**
         * ★★ 阶段 9.6 加的两个决策段，也必须能由逐题行重算出来。
         *
         * <p>★ 为什么这条测试<b>非有不可</b>：它守的是那两段和 {@code verdictRow}
         * 用的是<b>同一套判据</b>。两处各写一份的话，症状是
         * 「检索决策段说 3/4、逐题表数出来 2/4」—— 两个数各自自洽，
         * 合起来是谎话，而<b>没有任何东西会报错</b>。
         *
         * <p>★★ 它还兜住另一件更远的事：A/B 的翻转矩阵<b>只看逐题行</b>。
         * 汇总段加了、逐题行忘了加，新指标就<b>静默地不进任何对比</b> ——
         * 报告上一切正常，只是那两栏永远不参与「变了没有」的判定。
         */
        @Test
        @DisplayName("★★ 决策段（检索 / 工具）同样由逐题行重算得出")
        void decisionSectionsAreReproducibleFromRows() {
            Map<String, Object> report = fixture();
            List<Map<String, Object>> rows = detailRows(report);

            Map<String, Object> retrieval = sub(section(report, "检索决策"), "四格（逐题·多数票）");
            long shouldRetrieve = rows.stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("该检索"))).count();
            long shouldNot = rows.stream()
                    .filter(r -> Boolean.FALSE.equals(r.get("该检索"))).count();
            assertEquals(4, shouldRetrieve, "★ K1/K2/K3/K4 都声明了要检索");
            assertEquals(2, shouldNot, "★ T1/N1 声明不检索");

            // ── 逐题：四格 + 分母，全部由逐题行的两列重算 ──
            assertEquals(shouldRetrieve, num(retrieval, "分母_声明要检索的题"));
            assertEquals(shouldNot, num(retrieval, "分母_声明不检索的题"));

            long wantHit = rows.stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("该检索"))
                            && "检索".equals(r.get("检索决策"))).count();
            assertEquals(3, wantHit, "★ K1/K2/K3 检索了；K4 三次全被挡 ⇒ 检索没发生");
            assertEquals(wantHit, num(retrieval, "★声明要检索_实际检索了"));
            long noHit = rows.stream()
                    .filter(r -> Boolean.FALSE.equals(r.get("该检索"))
                            && "不检索".equals(r.get("检索决策"))).count();
            assertEquals(2, noHit);
            assertEquals(noHit, num(retrieval, "★声明不检索_实际也没检索"));

            // ── 逐行：分母 = 题数 × 重复次数（这里每一题都是 3 次）──
            Map<String, Object> rowCells = sub(section(report, "检索决策"), "四格（逐行）");
            assertEquals(12L, num(rowCells, "分母_声明要检索的行"), "★ 4 道题 × 3 次重复");
            assertEquals(6L, num(rowCells, "分母_声明不检索的行"), "★ 2 道题 × 3 次重复");
            assertEquals(9L, num(rowCells, "★声明要检索_实际检索了"), "★ K1/K2/K3 各 3 次");
            assertEquals(3L, num(rowCells, "★声明要检索_实际没检索（过度关闭）"),
                    "★ K4 三次全被闸门挡 ⇒ 三次都「声明要检索而没检索」");

            // ── 工具：gold 是 TOOL_LEAF 才能推出「该有工具」──
            Map<String, Object> tools = sub(section(report, "工具调用"), "四格（逐题·多数票）");
            long shouldHaveTools = rows.stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("该有工具"))).count();
            assertEquals(1, shouldHaveTools,
                    "★★ 只有 T1。判据是 retrievalOf(gold)==TOOL —— "
                            + "gold 是【叶子码】TOOL_LEAF，它自己没声明 tools，"
                            + "要抬到父顶层 TOOL_TOP 才看得出这一类走工具");
            assertEquals(shouldHaveTools, num(tools, "分母_该有工具的题"));
            assertEquals(rows.size() - shouldHaveTools, num(tools, "分母_不该有工具的题"));

            long toolMiss = rows.stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("该有工具"))
                            && "没调".equals(r.get("工具决策"))).count();
            assertEquals(1L, toolMiss,
                    "★★ 这个夹具里没有任何 tool_calls ⇒ T1 落在「该调却没调」那一格。"
                            + "★ 那正是 9.1 修掉的那个的模式（静默降级）—— "
                            + "所以真实数据上它非 0 时要先分清「真降级了」和「工具链没跑」，"
                            + "判据是 工具调用.★给了工具却一次没调的行（那一栏看的是"
                            + "生效白名单，不是 gold）");
            assertEquals(toolMiss, num(tools, "★该有工具_一次没调"));
            assertEquals(0L, num(tools, "★该有工具_确实调了"));
        }

        @Test
        @DisplayName("★★★ 反对照：把 null 当成 false 会得出【不一样的数】—— 三态确实在承重")
        void treatingNullAsFalseWouldChangeTheNumber() {
            Map<String, Object> report = fixture();
            List<Map<String, Object>> rows = detailRows(report);

            assertTrue(rows.stream().anyMatch(r -> r.get("意图正确") == null),
                    "★ 没有 null 的话，下面那条断言就恒真了 —— "
                            + "三态会退化成一个装饰品，删掉它测试照样绿");
            assertTrue(rows.stream().anyMatch(r -> r.get("命中@5") == null));

            long correct = rows.stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("意图正确"))).count();
            double right = (double) correct / rows.stream()
                    .filter(r -> r.get("意图正确") != null).count();
            double naive = (double) correct / rows.size();   // ← 把 null 当 false 的算法

            assertEquals(0.8, right, 1e-12);
            assertEquals(4.0 / 6.0, naive, 1e-12);
            assertNotEquals(right, naive,
                    "★★★ 这就是为什么 null 不能当 false：那 15 道工具题在【每一轮】里"
                            + "都会是 false、一格都不翻，翻转矩阵看起来完全正常 —— "
                            + "而「可比口径 93% vs 全体 84%」这 9.4 个百分点"
                            + "就这样被摊平进了噪声里。报告里那个 0.667 是错的数，"
                            + "但它长得和真数一样");
        }

        @Test
        @DisplayName("★ 逐题行的键在每一行上都必须存在 —— 缺键的后果是 A/B 静默跳过该格")
        void everyRowHasEveryKey() {
            List<Map<String, Object>> rows = detailRows(fixture());
            assertEquals(6, rows.size(), "六道题各一行，包括不进任何分母的那三道");

            for (Map<String, Object> r : rows) {
                for (String key : List.of("题号", "进检索分母", "三次status", "闸门",
                        "意图正确", "gold是合法分类目标", "命中@5", "recall@5", "归因",
                        "越界切片数", "跨次序列变了")) {
                    assertTrue(r.containsKey(key),
                            "题 " + r.get("题号") + " 缺键 " + key
                                    + " —— ★ 缺键的后果不是报错，是 A/B 在那一格上"
                                    + "静默地什么都不比，于是那次翻转永远不会被数出来");
                }
            }
            // ★ K4 三次全是 3 → 闸门「都反问」；K1 三次全是 1 → 「都放行」
            assertEquals("都反问", rows.stream()
                    .filter(r -> "K4".equals(r.get("题号"))).findFirst().orElseThrow().get("闸门"));
            assertEquals("都放行", rows.stream()
                    .filter(r -> "K1".equals(r.get("题号"))).findFirst().orElseThrow().get("闸门"));
        }
    }

    // ================================================================
    // 十、渲染顺序
    // ================================================================

    /**
     * <h2>★ 这个类守的是「报告里的文字顺序不能由 JDK 决定」</h2>
     *
     * <p>2026-09-22 实测到一件事：同一份数据、同一份 {@code intent-tree.yml}
     * （{@code doc_types: [1, 5]}），旧一轮的 {@code report.json} 印
     * {@code [1, 5]}、新一轮印 {@code [5, 1]}。
     * 用同样的 JDK 单独跑
     * {@code Set.copyOf(List.copyOf(new ArrayList<>(List.of(1, 5))))}
     * 得到的是 {@code [1, 5]} —— <b>也就是说，那个差异没有被解释清楚</b>。
     *
     * <p>★ 正确的处理不是「继续查 JDK」，是<b>不再依赖它</b>：
     * {@code docTypesOf} 返回的是 {@code Set}，而 {@code Set} 的迭代顺序
     * 根本不是它承诺的东西 —— 它是给 {@code equals} 用的，不是给人读的。
     * 渲染前排序之后，这两个字符串由我们决定。
     *
     * <p>★★ 为什么值得为「一串文字」写测试：{@code report.json} 是要被
     * <b>跨轮 diff</b>（{@code eval_ab.py}）并被<b>抄进 {@code docs/11}</b> 的。
     * 那里的随机差异是纯噪声，而噪声会让真正的变化淹没 ——
     * 一个 A/B 报告里如果每次都多出几行「变了」，读的人很快就会不再看那一栏。
     */
    @Nested
    @DisplayName("十、★ 渲染顺序：声明写成 [4, 2]，报告里必须印 [2, 4]")
    class RenderOrder {

        /** 唯一的叶子把 {@code doc_types} 写成<b>倒序</b> —— 专为暴露「直接拼 Set」的写法 */
        private IntentTree.Tree treeWithUnsortedDeclaration() {
            return new IntentTree.Tree(1, List.of(
                    new IntentTree.TopIntent("KB_TOP", "知识库类", "d", "s",
                            IntentTree.Retrieval.KB, IntentTree.Role.BUSINESS, List.of(
                            new IntentTree.Leaf("LEAF_UNSORTED", "倒序声明", "d", List.of(4, 2),
                                    List.of(), null, IntentTree.StructuredFact.NONE, List.of())),
                            List.of())));
        }

        @SuppressWarnings("unchecked")
        private List<String> mismatchedOf(Map<String, Object> report) {
            return (List<String>) section(report, "检索范围").get("不一致的题");
        }

        @Test
        @DisplayName("★★ 声明 [4, 2] → 报告印 [2, 4]；直接拼 Set 会印 [4, 2]")
        void declaredOrderDoesNotLeakIntoTheReport() {
            // gold = LEAF_UNSORTED（doc_types [4,2]），分类结果 = FALLBACK（空集）
            // → 两侧不同 → 进「不一致的题」
            Map<String, Object> report = run(
                    List.of(row("X1", 1, "FALLBACK",
                            detail(seg(1), seg(1), flat(1), List.of(4, 2), true,
                                    new int[]{1, 0, 1, 1, 1}))),
                    List.of(q("X1", "LEAF_UNSORTED", 1L)),
                    treeWithUnsortedDeclaration());

            List<String> mismatched = mismatchedOf(report);
            assertEquals(1, mismatched.size());
            assertEquals("X1: LEAF_UNSORTED[2, 4] → FALLBACK[]", mismatched.get(0));

            assertFalse(mismatched.get(0).contains("[4, 2]"),
                    "★★ 反对照：直接拼 Set 的写法会印出【一个我们没承诺过的顺序】"
                            + "（JDK 的桶序，随 JVM 启动时的随机 hash SALT 变）。"
                            + "★ 注意措辞：它【不保证】印出 [4, 2] —— 实测 20 个独立 JVM，"
                            + "2 个元素时 30% 恰好也印 [2, 4]。"
                            + "所以这一条只能保证「报告里的顺序不是从 Set 来的」，"
                            + "不能保证「不排序就一定看得见」。"
                            + "报告要被跨轮 diff、要被抄进 docs/11，"
                            + "那里的随机差异会让真正的变化淹没");
        }

        @Test
        @DisplayName("★★ 反对照：不排序会印出 [4, 2] —— 用一个【顺序可控】的集合证明它")
        void unsortedRenderingReallyDiffers() {
            // ★★★ 这里原来写的是：
            //       assertEquals("[4, 2]", Set.copyOf(List.of(4, 2)).toString())
            //     它把 JDK 的内部桶序当成了我们的承诺。实测 30 个独立 JVM，
            //     2 个元素时约一半会印成 [2, 4] —— 那条断言【自己】有一半概率红。
            //     ★ 它守的东西是对的（不能把 Set 的顺序当承诺），
            //       错的是拿一个不受我们控制的量去下断言。
            //
            // ★ 正确的对照物是【顺序可控】的集合：LinkedHashSet 保留插入顺序，
            //   所以它就是「不排序的样子」的一个确定性样本。
            Set<Integer> naive = new LinkedHashSet<>(List.of(4, 2));
            assertEquals("[4, 2]", naive.toString(),
                    "前提：这个集合确实是倒序的（LinkedHashSet 保留插入顺序）");
            assertNotEquals("[2, 4]", naive.toString(),
                    "★ 不排序会印出 [4, 2]，而报告印的是 [2, 4] —— "
                            + "两者不同，所以上面那条断言测的是真实的差异，不是一句恒真的话");

            // ⚠️ 残余盲区，记下来别假装它不存在：
            //    上面那条报告级断言（印 [2, 4]）**不是** 100% 的检测器 ——
            //    如果哪天 sorted() 被删掉，「直接拼 Set」的写法在
            //    约 1/4 的 JVM 上会【恰好也印出升序】，那时这条测试仍然绿。
            //    ★ 这不是测试写坏了，是「直接拼 Set」在那个 JVM 上真的没出错。
            //    实测 30 个独立 JVM：n=2 约一半、n≥3 稳定在约 1/4 巧合。
            //    （另有 §检索范围 的一致性判据从另一侧兜底：排序与否不影响
            //      集合相等，所以那条永不受影响。）
        }
    }

    // ================================================================
    // 十一、★★ 检索决策：该不该检索（阶段 9.6）
    // ================================================================

    @Nested
    @DisplayName("十一、★★ 检索决策：该不该检索")
    class RetrievalDecision {

        /**
         * ★★ 四个格子必须【各自成数】。
         *
         * <p>并成一个「准确率」会把两个方向完全不同的错压成一个数：
         * 「过度检索」要收窄，而「过度关闭」要放宽 —— 一个数字同时代表这两件事时，
         * 它掉下来你<b>不知道该往哪边调</b>。
         */
        @Test
        @DisplayName("★★ 四个格子各自成数")
        void fourCellsAreSeparate() {
            List<EvalQuestion> bank = List.of(
                    q("D1", "LEAF_A"), q("D2", "LEAF_A"), q("D3", "LEAF_A"),
                    q("S1", "LEAF_A", 1L), q("S2", "LEAF_A", 1L), q("S3", "LEAF_A", 1L));
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);
            List<QaLog> rows = List.of(
                    row("D1", 1, "FALLBACK", null),   // 声明不检索，也没检索 ✓
                    row("D2", 1, "FALLBACK", null),   // 同上 ✓
                    row("D3", 1, "FALLBACK", d),      // 声明不检索，检索了 ✗ 过度检索
                    row("S1", 1, "LEAF_A", d),        // 要检索，检索了 ✓
                    row("S2", 1, "LEAF_A", null),     // 要检索，没检索 ✗ 过度关闭
                    row("S3", 1, "LEAF_A", null));    // 同上 ✗

            Map<String, Object> cells = sub(section(run(rows, bank), "检索决策"), "四格（逐行）");

            assertEquals(3L, num(cells, "分母_声明不检索的行"),
                    "★ 分母是【声明不检索的那 3 道题】，不是全部 6 道");
            assertEquals(2L, num(cells, "★声明不检索_实际也没检索"));
            assertEquals(1L, num(cells, "★声明不检索_实际检索了（过度检索）"),
                    "★ 这一格是过度检索 —— 9.2 之前那 7 道兜底题的形状");
            assertEquals(3L, num(cells, "分母_声明要检索的行"));
            assertEquals(1L, num(cells, "★声明要检索_实际检索了"));
            assertEquals(2L, num(cells, "★声明要检索_实际没检索（过度关闭）"),
                    "★ 这一格是过度关闭，修法和上一格【相反】，所以绝不能并成一个数");
        }

        @Test
        @DisplayName("★★★ 反对照：被限流拒掉的行【不算一次决策】")
        void rateLimitedRowsAreNotDecisions() {
            List<EvalQuestion> bank = List.of(q("D1", "LEAF_A"), q("D2", "LEAF_A"),
                    q("D3", "LEAF_A"),
                    q("S1", "LEAF_A", 1L), q("S2", "LEAF_A", 1L), q("S3", "LEAF_A", 1L));
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);

            // ★ 只改一个数：D3 / S2 / S3 从 status=1 变成 4（被限流拒掉）
            List<QaLog> healthy = List.of(
                    row("D1", 1, "FALLBACK", null), row("D2", 1, "FALLBACK", null),
                    row("D3", 1, "FALLBACK", d),
                    row("S1", 1, "LEAF_A", d), row("S2", 1, "LEAF_A", null),
                    row("S3", 1, "LEAF_A", null));
            List<QaLog> refused = List.of(
                    row("D1", 1, "FALLBACK", null), row("D2", 1, "FALLBACK", null),
                    row("D3", 4, "FALLBACK", d),
                    row("S1", 1, "LEAF_A", d), row("S2", 4, "LEAF_A", null),
                    row("S3", 4, "LEAF_A", null));

            Map<String, Object> a = sub(section(run(healthy, bank), "检索决策"), "四格（逐行）");
            Map<String, Object> b = sub(section(run(refused, bank), "检索决策"), "四格（逐行）");
            Map<String, Object> bAll = section(run(refused, bank), "检索决策");

            assertEquals(3L, num(a, "分母_声明不检索的行"), "健康那一份：3 道");
            assertEquals(2L, num(b, "分母_声明不检索的行"),
                    "★★ 拒掉的那一份必须只剩 2 行 —— 它压根没轮到做决策，"
                            + "拿它当「决定不检索」会让报告说「要检索的题有 N% 没检索」，"
                            + "而真相是那一刻名额用完了，结论指向【相反的调优方向】");
            assertEquals(1L, num(a, "★声明不检索_实际检索了（过度检索）"),
                    "★ 健康那一份：D3 声明不检索却检索了 ⇒ 过度检索 1/3");
            assertEquals(0L, num(b, "★声明不检索_实际检索了（过度检索）"),
                    "★★★ 连分子也一起出局，于是这个格子【归零】—— "
                            + "一个看起来像好消息的 0（「零过度检索！」），"
                            + "而它百分之百是容量造成的，跟门控一点关系都没有。"
                            + "比率上没有任何痕迹，所以必须另有一格把出局的行数出来");
            assertEquals(3L, num(bAll, "★没算成决策的行"),
                    "★ 三行被单独数出来了 —— 它们不是被丢掉，是被【记成另一件事】");
        }

        @Test
        @DisplayName("★★★ 门控说了「不检索」而实际检索了 —— 应恒 0，且看得见")
        void gateSaidNoButRetrievalHappened() {
            List<EvalQuestion> bank = List.of(q("G1", "LEAF_A", 1L));
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);

            QaLog consistent = row("G1", 1, "LEAF_A", d);
            consistent.setIntentPlan(plan("KB", true, "JSON", "[]", null));
            QaLog ignored = row("G1", 1, "LEAF_A", d);
            ignored.setIntentPlan(plan("PLAN_OFF", false, "JSON", "[]", null));

            List<String> a = strList(section(run(List.of(consistent), bank), "检索决策"),
                    "★算对了但没用的行");
            List<String> b = strList(section(run(List.of(ignored), bank), "检索决策"),
                    "★算对了但没用的行");

            assertTrue(a.isEmpty(), "★ 门控说检索、也确实检索了 —— 一致");
            assertEquals(1, b.size(),
                    "★★★ 门控说【不】检索，而 retrieval_detail 非空 —— "
                            + "这是「算对了但调用点忘了用」，9.2 点名的只改一半的实现。"
                            + "★ 拿 intent_plan.retrieve 当判据会把它整个漏掉");
            assertTrue(b.get(0).contains("G1"), "★ 要能指出是哪一道：" + b);
        }

        @Test
        @DisplayName("★★ 澄清短路不算「过度关闭」—— 它被单独数出来")
        void clarifyShortCircuitIsNotOverClosing() {
            List<EvalQuestion> bank = List.of(q("C1", "LEAF_A", 1L), q("C2", "LEAF_A", 1L));
            // C1：澄清短路（status=3、没有 retrieval_detail）—— 这是【对的】，用户那句话本来就缺信息
            // C2：门控关太狠（status=1 却没检索）—— 这是【真的错】
            List<QaLog> rows = List.of(
                    row("C1", 3, "CLARIFY_TOP", null),
                    row("C2", 1, "LEAF_A", null));

            Map<String, Object> s = section(run(rows, bank), "检索决策");

            assertEquals(2L, num(sub(s, "四格（逐行）"), "★声明要检索_实际没检索（过度关闭）"),
                    "★ 两行都落进这一格 —— 光看这一格分不出谁对谁错");
            assertEquals(1L, num(s, "★其中·澄清短路（不是错）"),
                    "★★ 减掉它，剩下的 1 才是真的「关得太狠」。"
                            + "没有这一栏的话，多轮集上每一道首轮被反问的题都会"
                            + "变成一个假阳性 —— 而那个数看起来像个实实在在的缺陷");
        }

        /**
         * ★★★ {@code gate} 分布必须【六支全在】，哪怕某几支一次都没走到。
         *
         * <p>缺席和「没有这一支」在报告里长得一样 —— 而三支零的读法完全不同：
         * {@code NO_CLASSIFY} 为 0 = 分类一次都没失败过（**好消息**），
         * {@code PLAN_OFF} 为 0 = 这一支从没被数据验证过（**待办**）。
         * 少了这一格，这两件事都印不出来。</p>
         */
        @Test
        @DisplayName("★★★ gate 分布六支全在 —— 零也要印出来，缺席和「没有这一支」不一样")
        void gateDistributionKeepsZeroBranches() {
            List<EvalQuestion> bank = List.of(q("A", "LEAF_A", 1L));
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);
            QaLog one = row("A", 1, "LEAF_A", d);
            one.setIntentPlan(plan("KB", true, "JSON", "[]", null));

            Map<String, Object> gate = sub(
                    section(run(List.of(one), bank), "检索决策"), "gate分布");

            assertEquals(1L, num(gate, "KB"));
            for (String zero : List.of("PLAN_OFF", "TOOL", "NONE_INTENT",
                    "NONE_DISABLED", "NO_CLASSIFY")) {
                assertTrue(gate.containsKey(zero),
                        "★★ 六支里 `" + zero + "` 缺席了 —— 实测 954 行只走到过 3 支，"
                                + "另外 3 支的 0 各自是一条证据（分类从没失败过 / 开关是开的 /"
                                + " 模型从没主动关过检索），缺席的话它们全印不出来");
                assertEquals(0L, num(gate, zero));
            }
            assertEquals(6, gate.size(), "★ 多一支少一支都要在这里红");
        }

        @Test
        @DisplayName("★★ shape 分布是「门控到底有没有生效」的唯一判据")
        void shapeDistribution() {
            List<EvalQuestion> bank = List.of(
                    q("A", "LEAF_A", 1L), q("B", "LEAF_A", 1L), q("C", "LEAF_A", 1L));
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);

            QaLog json = row("A", 1, "LEAF_A", d);
            json.setIntentPlan(plan("KB", true, "JSON", "[]", null));
            QaLog code = row("B", 1, "LEAF_A", d);
            code.setIntentPlan(plan("KB", true, "CODE", "[]", null));
            QaLog none = row("C", 1, "LEAF_A", d);   // 没有计划

            Map<String, Object> shape = sub(section(run(List.of(json, code, none), bank), "检索决策"),
                    "shape分布");

            assertEquals(1L, num(shape, "JSON"));
            assertEquals(1L, num(shape, "CODE"),
                    "★ CODE = 模型没跟上契约，走了裸码回退。"
                            + "那时 plan.retrieve 是【默认值】不是模型说的 —— "
                            + "C 这一格涨起来，上面四个格子就都不算数了");
            assertEquals(1L, num(shape, "(缺这一格)"), "★ 没有计划的行单独数");
        }
    }

    // ================================================================
    // 十二、★★ 工具调用：该不该有工具（阶段 9.6）
    // ================================================================

    @Nested
    @DisplayName("十二、★★ 工具调用：该不该有工具")
    class ToolUsage {

        @Test
        @DisplayName("★★ 四个格子 + 「给了工具却一次没调」—— 9.1 那个 bug 的形状")
        void silentDropIsCaught() {
            List<EvalQuestion> bank = List.of(
                    q("T1", "TOOL_LEAF"), q("T2", "TOOL_LEAF"), q("K1", "LEAF_A", 1L));
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);

            QaLog t1 = row("T1", 1, "TOOL_TOP", null);
            t1.setIntentPlan(plan("TOOL", false, "JSON", "[\"query_x\"]", null));
            t1.setToolCalls(calls("query_x"));
            QaLog t2 = row("T2", 1, "TOOL_TOP", null);
            t2.setIntentPlan(plan("TOOL", false, "JSON", "[\"query_x\"]", null));
            t2.setToolCalls(null);                       // ★ 一次都没调
            QaLog k1 = row("K1", 1, "LEAF_A", d);
            k1.setIntentPlan(plan("KB", true, "JSON", "[]", null));

            Map<String, Object> s = section(run(List.of(t1, t2, k1), bank), "工具调用");
            Map<String, Object> cells = sub(s, "四格（逐行）");

            assertEquals(2L, num(cells, "分母_该有工具的行"),
                    "★ 分母由 gold 推出：TOOL_LEAF 抬到父顶层 TOOL_TOP 才是 TOOL 类");
            assertEquals(1L, num(cells, "★该有工具_确实调了"));
            assertEquals(1L, num(cells, "★该有工具_一次没调"),
                    "★★ 这一格是 9.1 修掉的那个的【形状】：工具意图答得跟裸聊一样。"
                            + "★ 标 「静默降级」要读 final_answer —— "
                            + "「如实说查不到」和「编一个具体值」在这一格里长得一样");
            assertEquals(1L, num(cells, "分母_不该有工具的行"));
            assertEquals(1L, num(cells, "★不该有工具_没调"));

            List<String> drop = strList(s, "★给了工具却一次没调的行");
            assertEquals(1, drop.size(), "★ 只有 T2：" + drop);
            assertTrue(drop.get(0).contains("T2"));

            // ★★ 反对照：把 tool_calls 补上，同一个夹具里它就不该再被点名
            t2.setToolCalls(calls("query_x"));
            List<String> drop2 = strList(section(run(List.of(t1, t2, k1), bank), "工具调用"),
                    "★给了工具却一次没调的行");
            assertTrue(drop2.isEmpty(),
                    "★★ 补上调用记录之后必须清空 —— 否则上面那条断言可能只是恒真"
                            + "（比如它压根不看 tool_calls）：" + drop2);
        }

        /**
         * ★★★ 这一段是<b>整套工具指标里最要紧的一条</b>。
         *
         * <p>白名单拒绝<b>也会留下 {@code CallRecord}</b>（{@code isError=true}）——
         * 所以「越权的调用」非 0 <b>不代表数据泄露</b>（服务端拒了，ADR-093），
         * 它代表「模型叫了一个没给它的名字」。
         *
         * <p>而「<b>拒了没有</b>」是可观测的：被拒那条必然是 {@code isError=true}。
         * 所以 {@code ★★越权且成功的调用} 应恒 0 ——
         * <b>那是 ADR-093 那条不变式在数据上的形状，不是一句「代码里写着所以不会发生」。</b>
         */
        @Test
        @DisplayName("★★★ 越权且成功的调用应恒 0 —— 而它【不是】恒空式")
        void overreachSucceededIsObservable() {
            List<EvalQuestion> bank = List.of(q("T1", "TOOL_LEAF"));
            QaLog refused = row("T1", 1, "TOOL_TOP", null);
            refused.setIntentPlan(plan("TOOL", false, "JSON", "[\"query_x\"]", null));
            // ★ query_y 不在本次白名单里 —— 服务端拒了，所以它是 isError=true
            refused.setToolCalls(calls("query_x", "query_y!"));

            QaLog leaked = row("T1", 1, "TOOL_TOP", null);
            leaked.setIntentPlan(plan("TOOL", false, "JSON", "[\"query_x\"]", null));
            // ★★ 同一个越权调用，但这次 isError=false —— 那意味着它【真的跑了】
            leaked.setToolCalls(calls("query_x", "query_y"));

            Map<String, Object> a = section(run(List.of(refused), bank), "工具调用");
            Map<String, Object> b = section(run(List.of(leaked), bank), "工具调用");

            assertEquals(1, strList(a, "★越权的调用").size(),
                    "★ 越权的【尝试】是要报出来的 —— 它是 prompt / 模型行为的信号");
            assertTrue(strList(a, "★★越权且成功的调用").isEmpty(),
                    "★★ 被拒的那条必然 isError=true ⇒ 这一格为空");
            assertEquals(1, strList(b, "★★越权且成功的调用").size(),
                    "★★★ 反对照：同一个越权调用，只把 isError 改成 false，"
                            + "这一格就必须有东西 —— 证明它不是一句恒真的话。"
                            + "真出现这一条 = 白名单在服务端【没拦住】，"
                            + "模型拿到了不该拿的数据");
        }

        @Test
        @DisplayName("★ 「调了」不等于「用上了」：isError 单独数")
        void erroredCallsAreCounted() {
            List<EvalQuestion> bank = List.of(q("T1", "TOOL_LEAF"));
            QaLog row1 = row("T1", 1, "TOOL_TOP", null);
            row1.setIntentPlan(plan("TOOL", false, "JSON", "[\"query_x\"]", null));
            // ★ 一条题、两次调用、两次都报错 —— 用来钉住「条数 ≠ 题数」
            row1.setToolCalls(calls("query_x!", "query_y!"));

            Map<String, Object> s = section(run(List.of(row1), bank), "工具调用");
            assertEquals(2L, num(s, "isError 的调用条数"),
                    "★ 条数不是【题数】—— 一轮可以调很多次（这里是 1 题 2 条）");
            assertEquals(1L, num(sub(s, "调用的工具分布"), "query_x"));
            assertEquals(1L, num(sub(s, "调用的工具分布"), "query_y"));
        }
    }

    // ================================================================
    // 十三、★★ 多轮澄清：三层（阶段 9.6）
    // ================================================================

    @Nested
    @DisplayName("十三、★★ 多轮澄清：三层")
    class MultiTurn {

        @Test
        @DisplayName("★★ 三层分开数，且「三层全过」是它们合取")
        void threeLayers() {
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);
            List<EvalQuestion> bank = List.of(multi("M1", "LEAF_A", 2, true, 1L));
            List<QaLog> rows = new ArrayList<>();

            // 尝试一（session 100）：该反问 → 反问了 → 末轮恢复且落点对
            rows.add(srow(1, "M1", 3, "CLARIFY_TOP", null, 100, resume(false)));
            rows.add(srow(2, "M1", 1, "LEAF_A", d, 100, resume(true)));
            // 尝试二（session 200）：该反问，但【没】反问 —— ① 错，而 ②③ 仍然成立
            rows.add(srow(3, "M1", 1, "LEAF_A", d, 200, resume(false)));
            rows.add(srow(4, "M1", 1, "LEAF_A", d, 200, resume(true)));

            Map<String, Object> s = section(run(rows, bank), "多轮澄清");

            assertEquals(2L, num(s, "分母_完整的尝试"));
            assertEquals(2L, num(s, "分母_①可判的尝试"));
            assertEquals(1L, num(s, "①该反问_反问了"), "★ session 100");
            assertEquals(1L, num(s, "①该反问_没反问（漏）"), "★ session 200 —— 该反问而没反问");
            assertEquals(0L, num(s, "①不该反问_没反问"));
            assertEquals(2L, num(sub(s, "②末轮带上了状态"), "命中"), "★ 两次的末轮都带上了");
            assertEquals(2L, num(sub(s, "③末轮落点对了"), "命中"));
            assertEquals(1L, num(sub(s, "★★三层全过"), "命中"),
                    "★ 「三层全过」不是三个率的平均 —— 它要求【同一次尝试】三格都成立");
            assertEquals(2L, num(sub(s, "末轮命中@5"), "n"), "★ 末轮两次都成功、都有正解");
        }

        /**
         * ★★★ 反对照：把 ① 的**标注**翻过来，同一个「没反问」就从错变成对。
         *
         * <p>没有这一条的话，「三层全过」的 ① 那一半写成 {@code asked && ...}
         * 照样能通过上面那个测试 —— 因为那个夹具里 ① 恰好是 true。</p>
         */
        @Test
        @DisplayName("★★★ 反对照：expect_clarify=false 时「没反问」是【对的】")
        void silenceCanBeCorrect() {
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);
            // ★ 一份【完全一样】的行：两个 session 都没反问
            List<QaLog> rows = List.of(
                    srow(1, "M1", 1, "LEAF_A", d, 100, resume(false)),
                    srow(2, "M1", 1, "LEAF_A", d, 100, resume(true)));

            Map<String, Object> wantsIt = section(
                    run(rows, List.of(multi("M1", "LEAF_A", 2, true, 1L))), "多轮澄清");
            Map<String, Object> wantsSilence = section(
                    run(rows, List.of(multi("M1", "LEAF_A", 2, false, 1L))), "多轮澄清");

            assertEquals(1L, num(wantsIt, "①该反问_没反问（漏）"));
            assertEquals(0L, num(sub(wantsIt, "★★三层全过"), "命中"),
                    "★ 该反问却没反问 ⇒ 三层不全过");

            assertEquals(1L, num(wantsSilence, "①不该反问_没反问"));
            assertEquals(1L, num(sub(wantsSilence, "★★三层全过"), "命中"),
                    "★★★ 同一份行，只把标注从 true 改成 false，「三层全过」就从 0 变成 1 —— "
                            + "证明它的 ① 那一半读的是【判对了】而不是【反问了】。"
                            + "写成 `asked && didResume && landed` 的话，这两个数会一模一样，"
                            + "而那个指标会被优化成「反问得越多越好」");
        }

        /**
         * ★★★ 反对照：多轮题没标 {@code expect_clarify} ⇒ ① 判【不可判】。
         *
         * <p>这是本段最要紧的一条：多轮题的 gold intent 指的是<b>末轮</b>，
         * 拿它给首轮下结论会得出「每一道都不该反问」——一个恒为 0 的「反问率」。
         * <b>猜出来的错值会和真值长得一样地印进报告。</b></p>
         */
        @Test
        @DisplayName("★★★ 反对照：没标 expect_clarify ⇒ ① 不可判，而 ②③ 照样算得出")
        void missingGoldIsNotJudgedButOthersAre() {
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);
            List<QaLog> rows = List.of(
                    srow(1, "M1", 3, "CLARIFY_TOP", null, 100, resume(false)),
                    srow(2, "M1", 1, "LEAF_A", d, 100, resume(true)));

            Map<String, Object> s = section(
                    run(rows, List.of(multi("M1", "LEAF_A", 2, null, 1L))), "多轮澄清");

            assertEquals(1L, num(s, "★★①没有 gold 的尝试"));
            assertEquals(0L, num(s, "分母_①可判的尝试"), "★ ① 的分母是 0 ⇒ 那四格都是不可判");
            assertEquals(0L, num(s, "①该反问_没反问（漏）"),
                    "★★ 猜的话这里会变成 1，读起来像「系统漏了一次反问」—— 而那是编出来的");
            assertEquals(1L, num(sub(s, "②末轮带上了状态"), "命中"),
                    "★ ② 不依赖 ① 的 gold ⇒ 分母是【完整的尝试】，必须照样算得出来");
            assertEquals(1L, num(sub(s, "③末轮落点对了"), "命中"));
            assertEquals(0L, num(sub(s, "★★三层全过"), "n"),
                    "★ 「三层全过」需要 ① ⇒ n=0（不可判，不是「全没过」）");
        }

        @Test
        @DisplayName("★★★ 反对照：会话断在中途的尝试【不进任何分子分母】")
        void incompleteAttemptIsExcluded() {
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);
            List<EvalQuestion> bank = List.of(multi("M1", "LEAF_A", 2, true, 1L));

            List<QaLog> complete = List.of(
                    srow(1, "M1", 3, "CLARIFY_TOP", null, 100, resume(false)),
                    srow(2, "M1", 1, "LEAF_A", d, 100, resume(true)));
            // ★ 只改一个数：第二次尝试只落了一行（第 2 轮失败，客户端 break 了）
            List<QaLog> broken = List.of(
                    srow(1, "M1", 3, "CLARIFY_TOP", null, 100, resume(false)),
                    srow(2, "M1", 1, "LEAF_A", d, 100, resume(true)),
                    srow(3, "M1", 3, "CLARIFY_TOP", null, 200, resume(false)));

            Map<String, Object> a = section(run(complete, bank), "多轮澄清");
            Map<String, Object> b = section(run(broken, bank), "多轮澄清");

            assertEquals(1L, num(a, "分母_完整的尝试"));
            assertEquals(0L, num(a, "★不完整的尝试"));
            assertEquals(1L, num(b, "分母_完整的尝试"), "★★ 分母【不动】：那半次尝试不算数");
            assertEquals(1L, num(b, "★不完整的尝试"), "★ 但必须被数出来");
            assertEquals(1L, num(a, "分母_①可判的尝试"), "★ ① 的分母也不含它");
            assertEquals(1L, num(b, "分母_①可判的尝试"));
            assertEquals(1.0, dbl(sub(b, "②末轮带上了状态"), "值"), 1e-9,
                    "★★★ 反对照：混进去的话 ② 会变成 1/2（看起来像「恢复机制掉了一半」），"
                            + "而真相是有一轮根本没跑完 —— 失败会被读成模型答不对");
            assertFalse(((List<?>) b.get("不完整的尝试明细")).isEmpty(),
                    "★ 要能指出是哪一次：" + b.get("不完整的尝试明细"));
        }

        @Test
        @DisplayName("★★ 计划里没有 resumed 这一格的尝试，单独数出来")
        void missingResumedKeyIsCountedSeparately() {
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);
            List<EvalQuestion> bank = List.of(multi("M1", "LEAF_A", 2, true, 1L));

            // ★ v<3 的计划：根本没有 resumed 那一格（9.4 之前的行）
            String oldPlan = "{\"v\":1,\"retrieve\":true,\"gate\":\"KB\","
                    + "\"tools\":[],\"missing\":[],\"shape\":\"JSON\"}";
            List<QaLog> rows = List.of(
                    srow(1, "M1", 3, "CLARIFY_TOP", null, 100, oldPlan),
                    srow(2, "M1", 1, "LEAF_A", d, 100, oldPlan));

            Map<String, Object> s = section(run(rows, bank), "多轮澄清");

            assertEquals(1L, num(s, "★计划里没有 resumed 这一格的尝试"),
                    "★ 「0% 带上了状态」有两种成因：功能没生效 / 数据是旧版本。"
                            + "不把后者数出来，前者就被淹没了");
            assertEquals(0L, num(sub(s, "②末轮带上了状态"), "命中"),
                    "★ 缺这一格 ⇒ 不算过（不能拿「没有」当「有」）");
        }
    }

    @Nested
    @DisplayName("十四、★★ 槽位声明（missing 的去向）")
    class SlotDeclaration {

        /** 一句「缺 product 这个槽位」的模型输出 —— 这一节的夹具里反复用 */
        private static final String MISSING_PRODUCT = "[\"product\"]";

        @Test
        @DisplayName("★★ 同一批「声明缺槽位」的行，按【落点】分裂成两格")
        void splitByWhereItLanded() {
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);
            List<EvalQuestion> bank = List.of(q("M1", "LEAF_A", 1L));

            List<QaLog> rows = List.of(
                    // 声明缺槽位 + 落在澄清分支 ⇒ 反问
                    srow(1, "M1", 3, "CLARIFY_TOP", null, 100,
                            plan("NONE_INTENT", false, "JSON", "[]", MISSING_PRODUCT, null)),
                    // 声明缺槽位 + 落在业务码 ⇒ 直接作答
                    srow(2, "M1", 1, "LEAF_A", d, 100,
                            plan("KB", true, "JSON", "[]", MISSING_PRODUCT, null)));

            Map<String, Object> s = section(run(rows, bank), "槽位声明");

            assertEquals(2L, num(s, "分母_行"));
            assertEquals(0L, num(s, "★其中·没有计划的行"));
            assertEquals(2L, num(s, "分母_声明缺槽位的行"), "★ 两行的 missing 都非空");
            assertEquals(1L, num(s, "声明缺槽位_落在澄清分支（反问了）"));
            assertEquals(1L, num(s, "★★声明缺槽位_落在业务码（直接作答）"));
            assertEquals(1L, num(s, "分母_反问的行"));
            assertEquals(0L, num(s, "★其中·没声明缺槽位却反问了"));
        }

        /**
         * ★★★ 反对照：同一个 {@code missing}，只翻【落点】那一格，去向就变了。
         *
         * <p>没有这一条的话，把这一节写成「反过来读 {@code status}」照样能通过
         * 上面那个测试 —— 因为那个夹具里两件事恰好同步。这里让 <b>{@code missing}
         * 逐字相同、只有 {@code status} 不同</b>：两个格子的值必须互换。</p>
         */
        @Test
        @DisplayName("★★★ 反对照：missing 逐字相同，只翻落点，去向就互换")
        void theSameMissingFlipsWithTheLanding() {
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);
            List<EvalQuestion> bank = List.of(q("M1", "LEAF_A", 1L));

            Map<String, Object> answered = section(run(List.of(
                    srow(1, "M1", 1, "LEAF_A", d, 100,
                            plan("KB", true, "JSON", "[]", MISSING_PRODUCT, null))), bank),
                    "槽位声明");
            Map<String, Object> asked = section(run(List.of(
                    srow(1, "M1", 3, "CLARIFY_TOP", null, 100,
                            plan("NONE_INTENT", false, "JSON", "[]", MISSING_PRODUCT, null))), bank),
                    "槽位声明");

            assertEquals(1L, num(answered, "★★声明缺槽位_落在业务码（直接作答）"));
            assertEquals(0L, num(answered, "声明缺槽位_落在澄清分支（反问了）"));
            assertEquals(1L, num(asked, "声明缺槽位_落在澄清分支（反问了）"));
            assertEquals(0L, num(asked, "★★声明缺槽位_落在业务码（直接作答）"));
            assertEquals(1L, num(answered, "分母_声明缺槽位的行"),
                    "★★ 分母【不动】—— 变的是去向，不是「缺不缺槽位」");
            assertEquals(1L, num(asked, "分母_声明缺槽位的行"));
        }

        /**
         * ★★★ 反对照：分子是 {@code missing}，<b>不是</b> {@code status}。
         *
         * <p>反问但没声明缺槽位 —— 那一行必须落进「走了回落文案」那一格，
         * 而<b>不</b>进「声明缺槽位」的分母。拿 {@code status} 当分子的实现
         * 会让这条用例的分母变成 1，而两个数在正文里长得一样。</p>
         */
        @Test
        @DisplayName("★★★ 反对照：反问了但没声明缺槽位 ⇒ 不进「声明缺槽位」的分母")
        void clarifyWithoutMissingIsNotADeclaration() {
            List<EvalQuestion> bank = List.of(q("M1", "LEAF_A", 1L));
            List<QaLog> rows = List.of(
                    srow(1, "M1", 3, "CLARIFY_TOP", null, 100,
                            plan("NONE_INTENT", false, "JSON", "[]", "[]", null)));

            Map<String, Object> s = section(run(rows, bank), "槽位声明");

            assertEquals(0L, num(s, "分母_声明缺槽位的行"));
            assertEquals(1L, num(s, "★其中·没声明缺槽位却反问了"),
                    "★ 这一格 = 走了回落文案（缺 missing 或缺模板）");
            assertEquals(1L, num(s, "分母_反问的行"), "★ 它仍然是「反问的行」");
        }

        @Test
        @DisplayName("★ 没有计划的行单独数，且不进任何分母")
        void rowsWithoutAPlanAreCountedSeparately() {
            List<EvalQuestion> bank = List.of(q("M1", "LEAF_A", 1L));
            List<QaLog> rows = List.of(srow(1, "M1", 1, "LEAF_A", null, 100, null));

            Map<String, Object> s = section(run(rows, bank), "槽位声明");

            assertEquals(1L, num(s, "分母_行"));
            assertEquals(1L, num(s, "★其中·没有计划的行"));
            assertEquals(0L, num(s, "分母_声明缺槽位的行"));
            assertEquals(0L, num(s, "分母_反问的行"));
        }

        /**
         * ★★ 分母是【行】不是题 —— 而「题」那一格是去重后的。
         *
         * <p>这两个数不同，混起来会让「重复跑 3 次的同一道题」被读成「3 道题」。
         * 报告里两个都印，且这一条把它们的差钉住。</p>
         */
        @Test
        @DisplayName("★★ 分母是行、题号那一格去重 —— 两个数不是同一个")
        void theQuestionListIsDeduped() {
            String d = detail(seg(1), seg(1), flat(1), List.of(2, 4), true, null);
            List<EvalQuestion> bank = List.of(q("M1", "LEAF_A", 1L));
            List<QaLog> rows = List.of(
                    srow(1, "M1", 1, "LEAF_A", d, 100, plan("KB", true, "JSON", "[]", MISSING_PRODUCT, null)),
                    srow(2, "M1", 1, "LEAF_A", d, 100, plan("KB", true, "JSON", "[]", MISSING_PRODUCT, null)),
                    srow(3, "M1", 1, "LEAF_A", d, 100, plan("KB", true, "JSON", "[]", MISSING_PRODUCT, null)));

            Map<String, Object> s = section(run(rows, bank), "槽位声明");

            assertEquals(3L, num(s, "★★声明缺槽位_落在业务码（直接作答）"), "★ 行");
            assertEquals(List.of("M1"), strList(s, "★声明了却没反问的题"), "★ 题");
        }
    }

    // ── 9.6 的夹具 ────────────────────────────────────────────────
    //   ★ num(...) 上面已经有了，直接复用（它会因为键缺失而 NPE —— 那是吵的失败，
    //     比一个安静的空指针好；这一节不再写第二份）

    private static double dbl(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        assertNotNull(v, "报告里缺少这一格：" + key);
        return ((Number) v).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        assertNotNull(v, "报告里缺少这一格：" + key);
        return (List<String>) v;
    }

    /**
     * 一份 {@code intent_plan} 的 JSON。
     *
     * <p>★ 键序无关（报告只按键取值），但内容必须合法 —— 这一列的
     * {@code v} 固定写 3，因为 {@code resumed} 是 9.4 起的第八格。
     *
     * @param resumed {@code null} = <b>不写这一格</b>（模拟 9.4 之前的行）
     */
    private static String plan(String gate, boolean retrieve, String shape,
                              String tools, Boolean resumed) {
        return plan(gate, retrieve, shape, tools, "[]", resumed);
    }

    /**
     * ★ 带 {@code missing} 的计划 —— 9.6a「槽位声明」那一节要它非空。
     *
     * <p>{@code missing} 是一段**已经拼好的 JSON 数组**（如 {@code ["product"]}）。
     * 不拆成 varargs 是因为「空数组」和「不写这一格」在这里都要能表达。</p>
     */
    private static String plan(String gate, boolean retrieve, String shape,
                              String tools, String missing, Boolean resumed) {
        StringBuilder sb = new StringBuilder("{\"v\":3,\"retrieve\":").append(retrieve)
                .append(",\"gate\":\"").append(gate)
                .append("\",\"tools\":").append(tools)
                .append(",\"missing\":").append(missing)
                .append(",\"shape\":\"").append(shape).append('"');
        if (resumed != null) {
            sb.append(",\"resumed\":").append(resumed);
        }
        return sb.append('}').toString();
    }

    /**
     * 造一份 {@code tool_calls}。
     *
     * <p>★ 名字后面带 {@code !} 表示这条 {@code isError=true} ——
     * 而它正是「被白名单拒掉」在数据上的样子。
     */
    private static String calls(String... specs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < specs.length; i++) {
            String spec = specs[i];
            boolean err = spec.endsWith("!");
            String name = err ? spec.substring(0, spec.length() - 1) : spec;
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"round\":1,\"tool\":\"").append(name).append("\",\"isError\":")
                    .append(err).append(",\"detail\":\"")
                    .append(err ? "工具报告失败" : "chars=87").append("\"}");
        }
        return sb.append(']').toString();
    }

    /** 只改 {@code resumed} 那一格的计划 —— 多轮测试里用得最多 */
    private static String resume(boolean resumed) {
        return plan("KB", true, "JSON", "[]", resumed);
    }

    /**
     * 一道多轮题。★ {@code turns} 的长度必须和测试里给的行数对上。
     *
     * @param expectClarify 第 1 轮该不该被反问（V17）。★ {@code null} = 没标注
     *                      ⇒ 报告判 ① 不可判，而<b>不是</b>当成 false
     */
    private static EvalQuestion multi(String no, String intent, int turns,
                                      Boolean expectClarify, Long... gold) {
        EvalQuestion e = q(no, intent, gold);
        e.setQuestionSet("stage7-multi");
        e.setExpectClarify(expectClarify);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < turns; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"第 ").append(i + 1).append(" 轮\"");
        }
        e.setTurns(sb.append(']').toString());
        e.setStandaloneQuestion("把这几轮合成一句");
        return e;
    }

    /** 带会话号与计划的一行。★ id 必须显式给 —— 轮次顺序就是 id 升序 */
    private static QaLog srow(long id, String no, int status, String intent,
                              String detail, long sessionId, String intentPlan) {
        QaLog r = row(no, status, intent, detail);
        r.setId(id);
        r.setSessionId(sessionId);
        r.setIntentPlan(intentPlan);
        return r;
    }
}
