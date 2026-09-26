package com.xbla.rag.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.agent.intent.IntentTree;
import com.xbla.rag.entity.EvalQuestion;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.EvalQuestionMapper;
import com.xbla.rag.mapper.KbChunkMapper;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.rag.facts.PolicyFactProvider;
import com.xbla.rag.rag.facts.StructuredFacts;
import com.xbla.rag.rag.prompt.RagPromptBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 把一轮评测的 {@code qa_log} 整理成 <b>RAGAS 能直接吃的形状</b>（阶段 7.5）。
 *
 * <h3>它存在的理由</h3>
 *
 * <p>RAGAS 需要四样东西：{@code user_input} / {@code response} /
 * {@code retrieved_contexts} / {@code reference}。前两样和第四样在
 * {@code qa_log} 里（{@code final_answer}、题库的 {@code expected_answer}），
 * 唯独 <b>{@code retrieved_contexts} 需要拼接三次</b>：
 *
 * <ol>
 *   <li>{@code qa_log.references} 里<b>只有 chunk_id，没有正文</b> —— 要回查
 *       {@code kb_chunk}</li>
 *   <li>正文要按 {@code RagPromptBuilder.truncate} 截断 —— 模型看到的是<b>截断后</b>的</li>
 *   <li>★★ 还要加上<b>结构化硬数据段</b>：{@code RETURN_EXCHANGE} 的 prompt 里
 *       有一段不在 {@code references} 里的天数表（ADR-067/070）——
 *       实测 {@code MT-005} 的答案说「7 天」而它引用的 5 条切片里一个天数都没有</li>
 * </ol>
 *
 * <h3>★★ 为什么出口是「两个 contexts」而不是一个</h3>
 *
 * <p>因为这几栏指标量的<b>不是同一件事</b>：
 *
 * <pre>
 *   faithfulness        「答案有没有被【模型看到的全部输入】支持」
 *                       → 用 {@code contexts}（切片 + 硬数据）
 *   context_precision   「检索回来的东西准不准」
 *   context_recall      → 用 {@code contextsRetrievedOnly}（【只】切片）
 * </pre>
 *
 * <p>把硬数据塞进后两栏会让它们<b>虚高</b>：那段天数表是<b>注入</b>的，
 * 不是检索来的，拿它去证明「检索质量好」是循环论证。而拿掉它又会让
 * faithfulness <b>虚低</b>（判它「无依据」的东西模型其实看见了）。
 * <b>一个 contexts 满足不了两件事</b>，所以两个都给，由调用方按指标选。
 *
 * <p>★ 本类<b>不含</b>指标算法（那在 {@code EvalReportService}），也<b>不调</b>
 * RAGAS（那在 {@code scripts/eval_ragas.py}）。它只做一件事：把库里的行
 * 还原成「模型当时看到的那份输入」。
 */
@Component
public class EvalAnswerService {

    private final QaLogMapper qaLogMapper;
    private final EvalQuestionMapper evalQuestionMapper;
    private final KbChunkMapper kbChunkMapper;
    private final IntentTree intentTree;
    private final PolicyFactProvider policyFactProvider;
    private final ObjectMapper mapper;

    public EvalAnswerService(QaLogMapper qaLogMapper,
                             EvalQuestionMapper evalQuestionMapper,
                             KbChunkMapper kbChunkMapper,
                             IntentTree intentTree,
                             PolicyFactProvider policyFactProvider,
                             ObjectMapper mapper) {
        this.qaLogMapper = qaLogMapper;
        this.evalQuestionMapper = evalQuestionMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.intentTree = intentTree;
        this.policyFactProvider = policyFactProvider;
        this.mapper = mapper;
    }

    // ================================================================
    // 取数
    // ================================================================

    /**
     * 一轮评测的答题明细。
     *
     * <p>取四份数据：那一轮的行、整个题库（要 {@code expected_answer}）、
     * 被引用到的切片正文、以及那条结构化硬数据段。
     */
    public Map<String, Object> answers(String runId) {
        List<QaLog> rows = qaLogMapper.selectByEvalRun(runId);

        Map<String, EvalQuestion> bank = new TreeMap<>();
        for (EvalQuestion q : evalQuestionMapper.selectList(null)) {
            bank.put(q.getQuestionNo(), q);
        }

        // 只取这一轮真正被引用到的切片 —— 全库 1640 条正文没必要都过一遍
        Set<Long> ids = new TreeSet<>();
        for (QaLog row : rows) {
            ids.addAll(referenceIds(mapper, row));
        }
        Map<Long, String> contents = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (ChunkContent c : kbChunkMapper.selectContentsByIds(new ArrayList<>(ids))) {
                contents.put(c.chunkId(), c.content());
            }
        }

        return build(intentTree.get(), mapper, renderFactsSection(), runId, rows, bank, contents);
    }

    /**
     * 渲染结构化硬数据那一段。
     *
     * <p>★ 走 {@link RagPromptBuilder#factsSection} —— <b>必须是 prompt 用的那个方法</b>。
     * 评测里重写一遍渲染就是第二个事实来源，它和 prompt 的漂移是<b>静默</b>的：
     * 报告里的 faithfulness 会因此偏低，而看报告的人会去改一个没坏的东西。
     *
     * <p>★ 之所以敢在这里渲染（而不是去拦当时那次请求）：{@code PolicyFactProvider.load()}
     * <b>无参</b>，它只读「生效中 + deleted=0 + 有退货天数」的政策行并按 id 排序 ——
     * 同一份库必然产出逐字相同的一段。⚠️ <b>但它的前提是库没变过</b>：
     * 政策表改过之后再去还原<b>更早</b>的 run，还原出来的就不是当时那段了。
     * 调用方把 {@code 政策表指纹} 一起返回，让这件事可查。
     */
    private String renderFactsSection() {
        StructuredFacts facts = policyFactProvider.load();
        if (facts == null || facts.isEmpty()) {
            return null;
        }
        return RagPromptBuilder.factsSection(facts);
    }

    // ================================================================
    // 计算（纯函数 —— 测试从这里进来）
    // ================================================================

    /**
     * {@code build} 是<b>纯函数</b>，全部输入走参数 —— 测试直接从这里进来，
     * 不需要起 Spring、不需要库。
     *
     * <p>★ {@code mapper} 必须是参数（而不是读实例字段）：静态方法读不到实例字段，
     * 而把它改成非静态又会让这个函数<b>不可重入</b>（T4 踩过：那正是本项目最恨的
     * 「不报错但结果不对」）。同 {@code EvalReportService.build} 的形状。
     *
     * @param factsSection 已经渲染好的硬数据段；{@code null} = 这一轮没有硬数据
     * @param contents     {@code chunkId → 切片正文（未截断）}。
     *                     ⚠️ 查不到的 id 必须<b>单独数出来</b> —— 它不是
     *                     「这次没召回」，是「切片已经不在了」
     */
    static Map<String, Object> build(IntentTree.Tree tree, ObjectMapper mapper, String factsSection,
                                     String runId, List<QaLog> rows, Map<String, EvalQuestion> bank,
                                     Map<Long, String> contents) {

        Map<String, List<QaLog>> byQuestion = new TreeMap<>();
        for (QaLog row : rows) {
            String no = row.getEvalQuestionNo();
            byQuestion.computeIfAbsent(no == null ? "(无题号)" : no, k -> new ArrayList<>()).add(row);
        }

        // ★★ 排除原因<b>预先</b>建好全部桶。T4 踩过一次：桶只在有题落进去时才建键，
        //    于是「某原因是 0」和「这个原因在代码里没了」在报告里长得一模一样。
        Map<String, Integer> excluded = new TreeMap<>();
        for (String reason : List.of(
                "不在题库里",
                "参考答案为空",
                "多轮题",
                "意图不在树里",
                "意图不需要检索",
                "没有 status=1 的行")) {
            excluded.put(reason, 0);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        int factsUsed = 0;
        int affinityUsed = 0;
        int missingChunkRefs = 0;
        int refsDifferFromFinalTopK = 0;

        for (Map.Entry<String, List<QaLog>> e : byQuestion.entrySet()) {
            String no = e.getKey();
            List<QaLog> group = e.getValue();

            QaLog picked = null;
            int attempt = 0;
            for (int i = 0; i < group.size(); i++) {
                if (group.get(i).getStatus() != null
                        && group.get(i).getStatus() == QaLog.STATUS_SUCCESS) {
                    picked = group.get(i);
                    attempt = i + 1;
                    break;
                }
            }
            if (picked == null) {
                excluded.merge("没有 status=1 的行", 1, Integer::sum);
                continue;
            }

            EvalQuestion question = bank.get(no);
            if (question == null) {
                excluded.merge("不在题库里", 1, Integer::sum);
                continue;
            }
            // ★ 多轮题单独一套、单独 run_id（洞 6）。真被喂进来时必须挡掉 ——
            //   分类器按决策不看历史，所以「追问轮的意图」不是一个可测量的量
            if (question.getTurns() != null && !question.getTurns().isBlank()) {
                excluded.merge("多轮题", 1, Integer::sum);
                continue;
            }
            if (question.getExpectedAnswer() == null || question.getExpectedAnswer().isBlank()) {
                excluded.merge("参考答案为空", 1, Integer::sum);
                continue;
            }

            IntentTree.Retrieval retrieval = tree.retrievalOf(picked.getIntent());
            if (retrieval == null) {
                // 分类失败 / 模型输出了一个树里没有的码。
                // ★ 和「这类问题不需要检索」是两件事，不能糊在一起：前者要修分类，后者是设计
                excluded.merge("意图不在树里", 1, Integer::sum);
                continue;
            }
            if (retrieval != IntentTree.Retrieval.KB) {
                excluded.merge("意图不需要检索", 1, Integer::sum);
                continue;
            }

            // ── 还原模型看到的东西 ────────────────────────────────
            List<Long> refIds = referenceIdList(mapper, picked);
            List<String> retrieved = new ArrayList<>(refIds.size());
            for (Long id : refIds) {
                String text = contents.get(id);
                if (text == null) {
                    // 切片被删了 / id 变了。★ 不能静默跳过：少一条上下文会让
                    // faithfulness 偏低，而那看起来像模型的问题
                    missingChunkRefs++;
                    continue;
                }
                retrieved.add(RagPromptBuilder.truncate(text));
            }

            boolean useFacts = factsSection != null
                    && tree.structuredFactOf(picked.getIntent()) == IntentTree.StructuredFact.POLICY;

            // ★★ 偏好块（阶段 9.5）—— 读的是【快照】，不是重建。
            //   和上面那条 useFacts 的关键差别在这里：硬数据无参可重建
            //   （PolicyFactProvider.load()），偏好块 per-user 且随订单变，
            //   事后重建出来的不是当时那一段。
            //   ⇒ 所以判据只能是 qa_log.affinity 有没有值，而它【就是】
            //     当时拼进 prompt 的那段字符串（同一个字符串，不是两次渲染）。
            String affinitySection = picked.getAffinity();
            boolean useAffinity = affinitySection != null && !affinitySection.isBlank();

            // ★ 顺序按【prompt 里的顺序】：偏好 → 硬数据 → 切片。
            //   顺序不影响 faithfulness 的计算，但报告是给人看的，
            //   和 prompt 一致能让「这一行到底还原了什么」一眼对上
            List<String> contexts = new ArrayList<>(retrieved.size() + 2);
            if (useAffinity) {
                contexts.add(affinitySection);
                affinityUsed++;
            }
            if (useFacts) {
                contexts.add(factsSection);
                factsUsed++;
            }
            contexts.addAll(retrieved);

            // ★ 自检：references 的顺序/成员和 final_top_k 一致吗？
            //   报告要用 references 当「模型看到的那几条」，这个前提值得被测一次
            if (!refIds.equals(finalTopK(mapper, picked))) {
                refsDifferFromFinalTopK++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("questionNo", no);
            row.put("attempt", attempt);            // 第几次重复（1-based），同 T4 的口径
            row.put("question", picked.getQuestion());
            row.put("expectedAnswer", question.getExpectedAnswer());
            row.put("answer", picked.getFinalAnswer() == null ? "" : picked.getFinalAnswer());
            row.put("intent", picked.getIntent());
            row.put("goldIntent", question.getIntent());
            row.put("provider", picked.getProvider());
            row.put("model", picked.getModel());
            row.put("latencyMs", picked.getTotalLatencyMs());
            row.put("cost", picked.getCost());
            row.put("chunkIds", refIds);
            row.put("factsInjected", useFacts);
            row.put("affinityInjected", useAffinity);
            row.put("contexts", contexts);
            row.put("contextsRetrievedOnly", retrieved);
            out.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("题库题数", byQuestion.size());
        summary.put("进 RAGAS 的题数", out.size());
        summary.put("被排除", excluded);
        summary.put("带硬数据的题数", factsUsed);
        summary.put("带偏好块的题数", affinityUsed);
        summary.put("引用里取不到正文的切片数", missingChunkRefs);
        summary.put("references 与 final_top_k 不一致的行数", refsDifferFromFinalTopK);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("口径", definitions());
        result.put("统计", summary);
        result.put("rows", out);
        return result;
    }

    /**
     * 口径声明 —— <b>每个发出去的数字旁边都要挂着它是怎么来的</b>。
     *
     * <p>这是 {@code EvalReportService} 立下的规矩，这里照做：报告里出现的
     * 每一个数，读的人都要能自己复算出来。
     */
    private static Map<String, Object> definitions() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("选题", "每题取【第一次 status=1】的那次（与 EvalReportService 同口径）");
        d.put("排除", "意图树的 retrievalOf(intent) != KB 的题一律不进 —— "
                + "工具题/兜底题/澄清题的答案没有【检索上下文】可言，"
                + "拿 faithfulness 去量它们是在量一个不存在的分母");
        d.put("★ contexts", "切片正文按 references 的顺序，单条走 RagPromptBuilder.truncate 截断"
                + "（模型看到的就是截断后的），再在【最前面】拼上结构化硬数据段");
        d.put("★ contextsRetrievedOnly", "只有切片，没有硬数据段");
        d.put("★★ 哪个给哪个指标", "faithfulness 用 contexts（要判「模型看到的全部输入」）；"
                + "context_precision / context_recall 用 contextsRetrievedOnly"
                + "（它们量的是【检索】质量，把注入的硬数据算进去是循环论证）");
        d.put("硬数据来源", "PolicyFactProvider.load() 无参重建 + RagPromptBuilder.factsSection 渲染"
                + "（与 prompt 同一个方法）。⚠️ 政策表改动后还原更早的 run 会失真");
        d.put("偏好块来源", "★★ 读 qa_log.affinity 那份【快照】，不是重建 —— "
                + "偏好是逐用户、且随订单变的，事后重建出来的不是当时那一段。"
                + "快照就是当时拼进 prompt 的那个字符串本身（同一个字符串，不是两次渲染）");
        d.put("偏好的作用范围", "只有带快照的行进 contexts（affinityInjected=true）。"
                + "⚠️ 匿名提问、订单不足 min-orders、以及 9.5 之前的 run 全都是 NULL —— "
                + "对那些行来说「没有偏好块」是【当时的事实】，不是这里漏读了");
        return d;
    }

    // ================================================================
    // JSON 小工具
    // ================================================================

    /** 解析一行 {@code references}。空 / 坏行返回空列表 —— 与「没有引用」同义 */
    private static List<Long> referenceIdList(ObjectMapper mapper, QaLog row) {
        List<Long> out = new ArrayList<>();
        JsonNode node = parse(mapper, row.getReferences());
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                out.add(item.path("chunk_id").asLong());
            }
        }
        return out;
    }

    private static Set<Long> referenceIds(ObjectMapper mapper, QaLog row) {
        return new TreeSet<>(referenceIdList(mapper, row));
    }

    /**
     * {@code retrieval_detail.final_top_k}。
     *
     * <p>★ 它是<b>数字数组</b>不是对象数组 —— 和那五段的形状不同。
     * 用 {@code x->>'chunk_id'} 去读会全是 NULL，且<b>不报错</b>
     * （阶段 7.4 的 Python 对拍脚本踩过一次）。
     */
    private static List<Long> finalTopK(ObjectMapper mapper, QaLog row) {
        List<Long> out = new ArrayList<>();
        JsonNode d = parse(mapper, row.getRetrievalDetail());
        if (d == null) {
            return out;
        }
        JsonNode arr = d.get("final_top_k");
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(n.asLong());
            }
        }
        return out;
    }

    private static JsonNode parse(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;    // 坏 JSON 当成「没有」—— 与项目里其它 JSONB 列同一条约定
        }
    }
}
