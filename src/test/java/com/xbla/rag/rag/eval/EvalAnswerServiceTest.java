package com.xbla.rag.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.agent.intent.IntentTree;
import com.xbla.rag.entity.EvalQuestion;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.rag.prompt.RagPromptBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EvalAnswerService} 的纯函数部分。
 *
 * <p>不起 Spring、不连库 —— {@code build} 的全部输入走参数。
 *
 * <p><b>这个测试守的是什么</b>：把 {@code qa_log} 的几行还原成
 * 「模型当时看到的那份输入」。还原错一步的后果<b>不是报错</b>，
 * 而是 RAGAS 给出一个偏低的分 —— 然后有人去调一个没坏的东西。
 */
@DisplayName("EvalAnswerService · 把 qa_log 还原成 RAGAS 的输入")
class EvalAnswerServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * <pre>
     *   KB_TOP             (KB, BUSINESS)
     *     ├─ LEAF_A        doc_types [2,4]   structured_facts NONE
     *     └─ FACTS_LEAF    doc_types [2]     structured_facts POLICY
     *   TOOL_TOP           (TOOL, BUSINESS)
     *     └─ TOOL_LEAF     doc_types []
     *   FALLBACK           (NONE, OUT_OF_SCOPE)
     *   CLARIFY_TOP        (NONE, CLARIFY)
     * </pre>
     *
     * <p>★ <b>刻意让硬数据只挂在 FACTS_LEAF 上</b>（和真实树一样：
     * 只有 {@code RETURN_EXCHANGE} 有）。这样「硬数据是不是按叶子给的」
     * 才有东西可测 —— 全部叶子都带的话，一个「无脑拼在最前面」的实现
     * 也能让所有断言通过。
     */
    private static IntentTree.Tree tree() {
        return new IntentTree.Tree(1, List.of(
                new IntentTree.TopIntent("KB_TOP", "知识库类", "d", "s",
                        IntentTree.Retrieval.KB, IntentTree.Role.BUSINESS, List.of(
                        new IntentTree.Leaf("LEAF_A", "叶子A", "d", List.of(2, 4),
                                List.of(), null, IntentTree.StructuredFact.NONE, List.of()),
                        new IntentTree.Leaf("FACTS_LEAF", "带硬数据的叶子", "d", List.of(2),
                                List.of(), null, IntentTree.StructuredFact.POLICY, List.of())),
                        // ★ 9.3 起 tools 是 TopIntent / Leaf 的最后一个分量。
                        //   这里一律传空 —— 本类测的是 prompt 组装，不涉及工具绑定；
                        //   带工具的那些用例在 ToolLoopToolboxFilterTest 和
                        //   RetrievalGateTest 里（那边才需要有非空的 whitelist）
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

    private static final String FACTS = "【售后政策硬数据】\n手机：退货 7 天\n条件、流程和例外情况见知识库资料。";

    // ── 造行 ──────────────────────────────────────────────────────

    /** 一行 {@code qa_log}。{@code refs} = references 里的 chunk_id 顺序（可为 null = 没有引用） */
    private static QaLog row(String no, int status, String intent, String answer,
                             String detail, Long... refs) {
        QaLog r = new QaLog();
        r.setEvalQuestionNo(no);
        r.setEvalRunId("T");
        r.setStatus(status);
        // ★ user_input 取【qa_log.question】（实际问出去的那句话），
        //   不是题库里的 question —— 见 «问句取自哪一侧» 那条测试
        r.setQuestion("实际问的 " + no);
        r.setIntent(intent);
        r.setFinalAnswer(answer);
        r.setRetrievalDetail(detail);
        r.setProvider("deepseek");
        r.setModel("deepseek-flash");
        if (refs.length > 0) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < refs.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"no\":").append(i + 1).append(",\"chunk_id\":").append(refs[i])
                        .append(",\"score\":0.9}");
            }
            r.setReferences(sb.append(']').toString());
        }
        return r;
    }

    private static EvalQuestion q(String no, String intent, String expected) {
        EvalQuestion e = new EvalQuestion();
        e.setQuestionNo(no);
        e.setQuestion("题 " + no);
        e.setIntent(intent);
        e.setQuestionSet("stage7");
        e.setExpectedAnswer(expected);
        return e;
    }

    private static String detail(Long... finalTopK) {
        StringBuilder sb = new StringBuilder("{\"final_top_k\":[");
        for (int i = 0; i < finalTopK.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(finalTopK[i]);
        }
        return sb.append("]}").toString();
    }

    // ── 跑一遍 ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> run(String factsSection,
                                           List<QaLog> rows,
                                           Map<String, EvalQuestion> bank,
                                           Map<Long, String> contents) {
        return EvalAnswerService.build(tree(), MAPPER, factsSection, "T", rows, bank, contents);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> excluded(Map<String, Object> result) {
        return (Map<String, Integer>) ((Map<String, Object>) result.get("统计")).get("被排除");
    }

    @SuppressWarnings("unchecked")
    private static List<String> contexts(Map<String, Object> row) {
        return (List<String>) row.get("contexts");
    }

    @SuppressWarnings("unchecked")
    private static List<String> contextsOnly(Map<String, Object> row) {
        return (List<String>) row.get("contextsRetrievedOnly");
    }

    private static Map<Long, String> contents(long... ids) {
        Map<Long, String> m = new LinkedHashMap<>();
        for (long id : ids) {
            m.put(id, "正文" + id);
        }
        return m;
    }

    private static Map<String, EvalQuestion> bank(EvalQuestion... qs) {
        Map<String, EvalQuestion> m = new LinkedHashMap<>();
        for (EvalQuestion e : qs) {
            m.put(e.getQuestionNo(), e);
        }
        return m;
    }

    // ============================================================
    // 一、选题：第一次 status=1
    // ============================================================

    @Nested
    @DisplayName("一、★ 每题取【第一次 status=1】的那次（与 EvalReportService 同口径）")
    class PickFirstSuccess {

        @Test
        @DisplayName("第 1 次是澄清、第 2 次成功 → 取第 2 次，attempt=2")
        void skipsClarification() {
            List<QaLog> rows = List.of(
                    row("A-1", QaLog.STATUS_CLARIFY, "CLARIFY_TOP", "请问您指的是哪一款？", null),
                    row("A-1", QaLog.STATUS_SUCCESS, "LEAF_A", "答案甲", detail(1L, 2L), 1L, 2L));

            List<Map<String, Object>> out = rows(run(null, rows, bank(q("A-1", "LEAF_A", "参考甲")),
                    contents(1, 2)));

            assertThat(out).hasSize(1);
            assertThat(out.get(0).get("answer")).isEqualTo("答案甲");
            assertThat(out.get(0).get("attempt"))
                    .as("★ attempt 是【第几次重复】的载体。口径是「第一次 status=1」，"
                            + "不是「第 1 次」也不是「任取一次」—— 写错不会报错，"
                            + "只会让检索指标随机丢样本")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("三次全不是 status=1 → 整题不进，且被计数")
        void allClarify() {
            List<QaLog> rows = List.of(
                    row("A-1", QaLog.STATUS_CLARIFY, "CLARIFY_TOP", "反问1", null),
                    row("A-1", QaLog.STATUS_FAILED, "LEAF_A", null, null),
                    row("A-1", QaLog.STATUS_CLARIFY, "CLARIFY_TOP", "反问2", null));

            Map<String, Object> r = run(null, rows, bank(q("A-1", "LEAF_A", "参考")), Map.of());

            assertThat(rows(r)).isEmpty();
            assertThat(excluded(r).get("没有 status=1 的行"))
                    .as("★ 三次全被闸门挡掉的题【不进检索分母】（检索确实没发生），"
                            + "但它必须出现在计数里 —— 否则报告的分母会悄悄变小")
                    .isEqualTo(1);
        }
    }

    // ============================================================
    // 二、排除判据
    // ============================================================

    @Nested
    @DisplayName("二、★ 排除判据（按意图语义，不是按 references 有没有）")
    class Exclusion {

        @Test
        @DisplayName("工具题不进 RAGAS")
        void toolQuestionExcluded() {
            List<QaLog> rows = List.of(
                    row("T-1", QaLog.STATUS_SUCCESS, "TOOL_LEAF", "您的订单已发货", null));

            Map<String, Object> r = run(null, rows, bank(q("T-1", "TOOL_LEAF", "已发货")), Map.of());

            assertThat(rows(r)).isEmpty();
            assertThat(excluded(r).get("意图不需要检索")).isEqualTo(1);
        }

        @Test
        @DisplayName("★★ 反对照：「意图不在树里」和「意图不需要检索」是两件事，分开计数")
        void unknownIntentCountedSeparately() {
            List<QaLog> rows = List.of(
                    row("T-1", QaLog.STATUS_SUCCESS, "TOOL_LEAF", "答案", null),
                    row("T-2", QaLog.STATUS_SUCCESS, "MODEL_MADE_THIS_UP", "答案", null,
                            1L));

            Map<String, Object> r = run(null, rows,
                    bank(q("T-1", "TOOL_LEAF", "x"), q("T-2", "LEAF_A", "y")), contents(1));

            assertThat(excluded(r).get("意图不需要检索"))
                    .as("前者是【设计】：这类问题本来就不该检索")
                    .isEqualTo(1);
            assertThat(excluded(r).get("意图不在树里"))
                    .as("★★ 后者是【故障】：分类输出了一个树里没有的码，要修分类器。"
                            + "把两者合成一个桶 → 「分类器坏了」会被读成「这是设计」")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("多轮题不进（分类器按决策不看历史，追问轮的意图不是一个可测量的量）")
        void multiTurnExcluded() {
            EvalQuestion multi = q("M-1", "LEAF_A", "参考");
            multi.setTurns("t1|t2");

            Map<String, Object> r = run(null,
                    List.of(row("M-1", QaLog.STATUS_SUCCESS, "LEAF_A", "答案", detail(1L), 1L)),
                    bank(multi), contents(1));

            assertThat(rows(r)).isEmpty();
            assertThat(excluded(r).get("多轮题")).isEqualTo(1);
        }

        @Test
        @DisplayName("参考答案为空的题不进（RAGAS 拿不到 reference）")
        void blankReferenceExcluded() {
            Map<String, Object> r = run(null,
                    List.of(row("E-1", QaLog.STATUS_SUCCESS, "LEAF_A", "答案", detail(1L), 1L)),
                    bank(q("E-1", "LEAF_A", "   ")), contents(1));

            assertThat(rows(r)).isEmpty();
            assertThat(excluded(r).get("参考答案为空")).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 六种排除原因【预先】建桶 —— 「是 0」和「代码里没了」必须长得不一样")
        void allBucketsAlwaysPresent() {
            Map<String, Integer> ex = excluded(run(null, List.of(), Map.of(), Map.of()));

            assertThat(ex).containsOnlyKeys(
                    "不在题库里", "参考答案为空", "多轮题",
                    "意图不在树里", "意图不需要检索", "没有 status=1 的行");
            assertThat(ex.values())
                    .as("★ 一道题都没排除时，六个桶都在且都是 0 —— "
                            + "T4 踩过这个坑：桶只在有题落进去时才建键，"
                            + "于是「某原因是 0」和「这个原因在代码里被删了」在报告里逐字相同")
                    .containsOnly(0);
        }
    }

    // ============================================================
    // 三、★★ 两个 contexts
    // ============================================================

    @Nested
    @DisplayName("三、★★ 两个 contexts —— 一个给 faithfulness，一个给 context_precision")
    class TwoContexts {

        @Test
        @DisplayName("★★ 带硬数据的叶子：contexts 第 0 条是硬数据，contextsRetrievedOnly 里没有它")
        void factsGoesFirstInContextsOnly() {
            Map<String, Object> r = run(FACTS,
                    List.of(row("F-1", QaLog.STATUS_SUCCESS, "FACTS_LEAF", "7 天",
                            detail(1L, 2L), 1L, 2L)),
                    bank(q("F-1", "FACTS_LEAF", "退货 7 天")), contents(1, 2));

            Map<String, Object> row = rows(r).get(0);

            assertThat(contexts(row))
                    .as("★ faithfulness 判的是「答案有没有被模型看到的【全部输入】支持」。"
                            + "硬数据段确实进了 prompt，所以它必须在 contexts 里 —— "
                            + "不在的话，答案里那个「7 天」会被判成无依据")
                    .containsExactly(FACTS, "正文1", "正文2");
            assertThat(contextsOnly(row))
                    .as("★★ context_precision / context_recall 量的是【检索】质量。"
                            + "硬数据是【注入】的，不是检索来的 —— 拿它去证明「检索好」"
                            + "是循环论证。所以这一份必须只有切片")
                    .containsExactly("正文1", "正文2");
            assertThat(row.get("factsInjected")).isEqualTo(true);
        }

        @Test
        @DisplayName("★★ 反对照：同一批数据、同一个 factsSection，不带硬数据的叶子【一条都不拼】")
        void factsArePerLeafNotGlobal() {
            Map<String, Object> r = run(FACTS,
                    List.of(row("N-1", QaLog.STATUS_SUCCESS, "LEAF_A", "答案",
                            detail(3L), 3L)),
                    bank(q("N-1", "LEAF_A", "参考")), contents(3));

            Map<String, Object> row = rows(r).get(0);

            assertThat(contexts(row))
                    .as("★★ 这一条是上面那条的反对照。少了它，「无脑把硬数据拼在最前面」"
                            + "这个实现也能让上面那条通过 —— 而它会让【每一道】题的 "
                            + "faithfulness 都虚高，且没有任何症状")
                    .containsExactly("正文3");
            assertThat(row.get("factsInjected")).isEqualTo(false);
        }

        @Test
        @DisplayName("拿不到硬数据段时（factsSection=null），两个 contexts 逐字相同")
        void nullFactsMeansIdentity() {
            Map<String, Object> r = run(null,
                    List.of(row("F-1", QaLog.STATUS_SUCCESS, "FACTS_LEAF", "答案",
                            detail(1L), 1L)),
                    bank(q("F-1", "FACTS_LEAF", "参考")), contents(1));

            Map<String, Object> row = rows(r).get(0);
            assertThat(contexts(row)).isEqualTo(contextsOnly(row));
            assertThat(row.get("factsInjected")).isEqualTo(false);
        }
    }

    // ============================================================
    // 三之二、★★ 偏好块（阶段 9.5）
    // ============================================================

    /**
     * <b>偏好块进不进 {@code contexts}</b>（阶段 9.5）。
     *
     * <h3>★★★ 它和硬数据那一节最大的区别：来源是<b>快照</b>，不是重建</h3>
     *
     * <p>{@code PolicyFactProvider.load()} 是<b>无参</b>的，所以评测敢现场重建
     * 「模型当时看到的那一段」；偏好块 per-user 且随订单变，
     * <b>事后重建出来的不是当时那一段</b>。
     *
     * <p>所以这里的判据只能是 {@code qa_log.affinity} 有没有值 ——
     * 而它<b>就是</b>当时拼进 prompt 的那个字符串（同一个字符串，不是两次渲染）。
     */
    @Nested
    @DisplayName("三之二、★★ 偏好块读的是快照")
    class AffinitySnapshot {

        private static final String AFFINITY =
                "【这位用户的购买记录】\n近 180 天内 3 笔已付款订单（共 3 件商品），"
                        + "涉及 1 个类目：手机×3\n会员等级：银卡";

        /** 给一行打上偏好快照 —— 真实链路上它由 ChatServiceImpl 填（V16 那一列） */
        private static QaLog withAffinity(QaLog r, String affinity) {
            r.setAffinity(affinity);
            return r;
        }

        @Test
        @DisplayName("★★★ 有快照 → 它在 contexts 里，排在硬数据与切片之前")
        void snapshotGoesIntoContextsFirst() {
            Map<String, Object> r = run(FACTS,
                    List.of(withAffinity(row("A-1", QaLog.STATUS_SUCCESS, "FACTS_LEAF",
                            "选这款", detail(1L), 1L), AFFINITY)),
                    bank(q("A-1", "FACTS_LEAF", "参考")), contents(1));

            Map<String, Object> row = rows(r).get(0);

            assertThat(contexts(row))
                    .as("★ 顺序按 prompt 里的顺序：偏好 → 硬数据 → 切片。"
                            + "顺序不影响 faithfulness 的计算，但报告是给人看的，"
                            + "和 prompt 一致能让「这一行还原了什么」一眼对上")
                    .containsExactly(AFFINITY, FACTS, "正文1");
            assertThat(contextsOnly(row))
                    .as("★★ 和硬数据同一条纪律：context_precision / context_recall 量的是"
                            + "【检索】质量，偏好是注入的，算进去是循环论证")
                    .containsExactly("正文1");
            assertThat(row.get("affinityInjected")).isEqualTo(true);
        }

        @Test
        @DisplayName("★★ 反对照：同一批数据，只把快照去掉 → contexts 里就没有它")
        void withoutSnapshotItIsNotThere() {
            Map<String, Object> r = run(FACTS,
                    List.of(row("A-2", QaLog.STATUS_SUCCESS, "FACTS_LEAF", "选这款",
                            detail(1L), 1L)),
                    bank(q("A-2", "FACTS_LEAF", "参考")), contents(1));

            Map<String, Object> row = rows(r).get(0);

            assertThat(contexts(row))
                    .as("★★ 少了这一条，「把偏好无脑拼进去」这个实现也能让上面那条通过 ——"
                            + "而它会给【每一道】题凭空加一段模型从没见过的上下文，"
                            + "faithfulness 于是虚高，且没有任何症状")
                    .containsExactly(FACTS, "正文1");
            assertThat(row.get("affinityInjected")).isEqualTo(false);
        }

        @Test
        @DisplayName("★ 空白快照按「没有」处理 —— 守 V16「这一列不能是空串」那条")
        void blankSnapshotCountsAsAbsent() {
            Map<String, Object> r = run(null,
                    List.of(withAffinity(row("A-3", QaLog.STATUS_SUCCESS, "LEAF_A", "答案",
                            detail(1L), 1L), "   ")),
                    bank(q("A-3", "LEAF_A", "参考")), contents(1));

            Map<String, Object> row = rows(r).get(0);

            assertThat(contexts(row)).containsExactly("正文1");
            assertThat(row.get("affinityInjected")).isEqualTo(false);
        }

        @Test
        @DisplayName("★★ 快照是【逐行】的，不是全局参数 —— 同一批里两行各带各的")
        void snapshotIsPerRowNotPerRun() {
            String other = "【这位用户的购买记录】\n近 180 天内 5 笔已付款订单（共 6 件商品）";
            Map<String, Object> r = run(null,
                    List.of(withAffinity(row("A-4", QaLog.STATUS_SUCCESS, "LEAF_A", "答案1",
                                    detail(1L), 1L), AFFINITY),
                            withAffinity(row("A-5", QaLog.STATUS_SUCCESS, "LEAF_A", "答案2",
                                    detail(2L), 2L), other)),
                    bank(q("A-4", "LEAF_A", "参考1"), q("A-5", "LEAF_A", "参考2")),
                    contents(1, 2));

            assertThat(contexts(rows(r).get(0))).startsWith(AFFINITY);
            assertThat(contexts(rows(r).get(1)))
                    .as("★★ 偏好是 per-user 的 —— 做成「整轮一个参数」的接口"
                            + "（像 factsSection 那样）在这里就会把两个人的偏好混起来")
                    .startsWith(other);
        }
    }

    // ============================================================
    // 四、正文还原
    // ============================================================

    @Nested
    @DisplayName("四、★ 正文还原：顺序、截断、缺失")
    class RestoreContexts {

        @Test
        @DisplayName("★ 顺序跟 references，不跟 final_top_k")
        void orderFollowsReferences() {
            // references 是 [2,1]，final_top_k 是 [1,2] —— 两者故意不一致
            Map<String, Object> r = run(null,
                    List.of(row("O-1", QaLog.STATUS_SUCCESS, "LEAF_A", "答案",
                            detail(1L, 2L), 2L, 1L)),
                    bank(q("O-1", "LEAF_A", "参考")), contents(1, 2));

            Map<String, Object> row = rows(r).get(0);

            assertThat(contextsOnly(row))
                    .as("★★ references 的顺序【就是 prompt 里 [编号] 的顺序】"
                            + "（buildReferences 里 no = i+1）。RAGAS 的判词模型看到"
                            + "乱序的上下文会给出不同的分 —— 而这种偏差是静默的")
                    .containsExactly("正文2", "正文1");
            assertThat(row.get("chunkIds")).isEqualTo(List.of(2L, 1L));
        }

        @Test
        @DisplayName("★ 不一致这件事被数出来了 —— 不能只靠我口头保证它们相同")
        void mismatchIsCounted() {
            Map<String, Object> r = run(null,
                    List.of(row("O-1", QaLog.STATUS_SUCCESS, "LEAF_A", "答案",
                            detail(1L, 2L), 2L, 1L)),
                    bank(q("O-1", "LEAF_A", "参考")), contents(1, 2));

            assertThat((Integer) ((Map<String, Object>) r.get("统计"))
                    .get("references 与 final_top_k 不一致的行数"))
                    .as("★ 「references 就是模型看到的那几条」是个【前提】，"
                            + "而这个前提值得被测一次 —— 它是静默的")
                    .isEqualTo(1);
        }

        /**
         * ★ 造一段<b>每一小段都唯一</b>的长正文。
         *
         * <p>⚠️ 不能用 {@code "甲".repeat(n)} —— 全等的字符里，
         * 「第 1200 字之后的内容」和「第 1 字之后的内容」<b>是同一个字符串</b>，
         * 于是「整条上下文里没有后半段」这个断言<b>必然失败</b>，
         * 而它失败的原因是测试造的数据本身没有位置信息。
         * （这条测试第一版就是这么写的，红了一次。）
         */
        private static String longText(int segments) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segments; i++) {
                sb.append("第").append(i).append("段。");
            }
            return sb.toString();
        }

        @Test
        @DisplayName("★ 正文按 prompt 的同一个上限截断 —— 让评判标准不比被评对象更宽")
        void contentIsTruncated() {
            String long_ = longText(400);          // 约 2000 字，远超 1000
            assertThat(long_.length()).isGreaterThan(RagPromptBuilder.MAX_CHARS_PER_CHUNK + 400);

            Map<String, Object> r = run(null,
                    List.of(row("L-1", QaLog.STATUS_SUCCESS, "LEAF_A", "答案",
                            detail(1L), 1L)),
                    bank(q("L-1", "LEAF_A", "参考")), Map.of(1L, long_));

            String ctx = contextsOnly(rows(r).get(0)).get(0);

            // ★ 截断不是「砍到 1000 就完」—— RagPromptBuilder 还会追加一个
            //   「…（已截断）」标记，而那 6 个字【也进了 prompt】。
            //   所以这里断言的是结构（前缀 + 标记），不是长度等于 1000 ——
            //   写死 1000 会让这条测试在改动标记文案时红，而它其实没坏
            assertThat(ctx)
                    .startsWith(long_.substring(0, RagPromptBuilder.MAX_CHARS_PER_CHUNK))
                    .endsWith("（已截断）")
                    .hasSizeLessThan(long_.length());

            // ★★ 反对照：上面那条只证明「它变短了、尾巴有个标记」。
            //    这一条证明【尾巴的内容真的不在里面】—— 少了它，
            //    「砍掉中间再拼回末尾」这种实现也能让上面那条通过
            assertThat(ctx)
                    .as("★★ 模型看到的是截断后的版本。喂全文就是让判词模型拿着"
                            + "【比模型当时更多的信息】去判决 —— 答案依据了第 1001 字之后"
                            + "的内容时，faithfulness 会判它「无依据」，"
                            + "而那是测量口径的问题，不是模型的问题")
                    .doesNotContain("第399段")
                    .doesNotContain("第350段");
            assertThat(long_)
                    .as("★ 对照物本身要成立：这两个标记在原文里【是存在的】。"
                            + "少了这一句，上面那条断言在「标记压根不存在」时也会绿 —— "
                            + "而那正是恒真断言的样子")
                    .contains("第399段")
                    .contains("第350段");
        }

        @Test
        @DisplayName("★★ 切片取不到正文时【不静默跳过】—— 单独计数")
        void missingContentIsCounted() {
            Map<String, Object> r = run(null,
                    List.of(row("X-1", QaLog.STATUS_SUCCESS, "LEAF_A", "答案",
                            detail(1L, 2L), 1L, 2L)),
                    bank(q("X-1", "LEAF_A", "参考")), contents(1));   // 2 号没正文

            assertThat(contextsOnly(rows(r).get(0)))
                    .as("★ 少一条就少一条（不能拿空串顶替 —— 那会变成一条「空的上下文」，"
                            + "被 RAGAS 当成有内容是另一回事）")
                    .containsExactly("正文1");
            assertThat((Integer) ((Map<String, Object>) r.get("统计"))
                    .get("引用里取不到正文的切片数"))
                    .as("★★ 这个数【必须】单独暴露。它意味着「切片被删了 / id 变了」，"
                            + "而不是「这次没召回」—— 混起来会让人去查召回，"
                            + "而真正该做的是重跑 reload（洞 7）")
                    .isEqualTo(1);
        }
    }

    // ============================================================
    // 五、出口形状
    // ============================================================

    @Nested
    @DisplayName("五、出口形状")
    class Shape {

        @Test
        @DisplayName("每一行都带齐 RAGAS 要的四个字段")
        void rowCarriesRagasFields() {
            Map<String, Object> r = run(FACTS,
                    List.of(row("S-1", QaLog.STATUS_SUCCESS, "FACTS_LEAF", "答案甲",
                            detail(1L), 1L)),
                    bank(q("S-1", "FACTS_LEAF", "参考答案甲")), contents(1));

            assertThat(rows(r).get(0)).containsKeys(
                    "questionNo", "question", "expectedAnswer", "answer",
                    "contexts", "contextsRetrievedOnly", "intent", "provider", "model");
            Map<String, Object> row = rows(r).get(0);
            assertThat(row.get("expectedAnswer")).isEqualTo("参考答案甲");
            assertThat(row.get("answer")).isEqualTo("答案甲");
        }

        @Test
        @DisplayName("★★ user_input 取【实际问出去的那句话】，不取题库里的题面")
        void questionComesFromTheRow() {
            Map<String, Object> r = run(null,
                    List.of(row("S-1", QaLog.STATUS_SUCCESS, "LEAF_A", "答案甲",
                            detail(1L), 1L)),
                    bank(q("S-1", "LEAF_A", "参考答案甲")), contents(1));

            assertThat(rows(r).get(0).get("question"))
                    .as("★★ 题库里的 question 和 qa_log.question 在【多轮题】和"
                            + "【改写过的问句】上会不一样。RAGAS 的 user_input 必须"
                            + "是【模型实际看到的那句】—— 拿题库的题面去判，"
                            + "等于问「这句话切不切题」而不是「模型当时在答什么」，"
                            + "而两者的差在单轮题上是 0（所以不会报错），"
                            + "在多轮题上才显形 —— 那时已经没人会回来查这里了")
                    .isEqualTo("实际问的 S-1");
        }

        @Test
        @DisplayName("答案缺席时给空串，不给 null —— RAGAS 收到 null 会给出一个说不清的分")
        void nullAnswerBecomesEmptyString() {
            Map<String, Object> r = run(null,
                    List.of(row("S-2", QaLog.STATUS_SUCCESS, "LEAF_A", null,
                            detail(1L), 1L)),
                    bank(q("S-2", "LEAF_A", "参考")), contents(1));

            assertThat(rows(r).get(0).get("answer")).isEqualTo("");
        }

        @Test
        @DisplayName("★ 口径声明随结果一起发出去")
        void definitionsTravelWithResult() {
            Map<String, Object> r = run(null, List.of(), Map.of(), Map.of());

            assertThat(r).containsKeys("runId", "口径", "统计", "rows");
            @SuppressWarnings("unchecked")
            Map<String, Object> defs = (Map<String, Object>) r.get("口径");
            assertThat(defs.keySet())
                    .as("★ 每个发出去的数字旁边都要挂着它是怎么来的 —— "
                            + "EvalReportService 立下的规矩，这里照做")
                    .contains("★★ 哪个给哪个指标", "硬数据来源");
        }
    }
}
