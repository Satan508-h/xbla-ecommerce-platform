package com.xbla.rag.controller;

import com.xbla.rag.agent.intent.IntentClassification;
import com.xbla.rag.agent.intent.IntentFewShot;
import com.xbla.rag.agent.intent.IntentPlan;
import com.xbla.rag.agent.intent.IntentPromptBuilder;
import com.xbla.rag.agent.intent.IntentTree;
import com.xbla.rag.agent.intent.LlmIntentClassifier;
import com.xbla.rag.agent.intent.RetrievalGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图调试探针（阶段 5.1 的意图树 + 5.2 的意图识别）。
 *
 * <p>⚠️ {@code @Profile("local")} —— 和另外几个探针一样只在本地存在。
 * ⚠️ {@code /classify} 会<b>真实调用模型并花钱</b>，别拿它做压测。
 *
 * <h3>接口</h3>
 * <pre>
 *   # 看加载后的意图树（含每个顶层意图 roll-up 出来的 doc_types 并集）
 *   curl -s localhost:8080/api/debug/agent/intent-tree | python -m json.tool
 *
 *   # 分类一个问题（★ 花钱）
 *   curl -s -G localhost:8080/api/debug/agent/classify --data-urlencode "q=退货要几天"
 *
 *   # 看分类 prompt 的原文（不花钱）—— 「分类不准」时第一个该看的东西
 *   curl -s localhost:8080/api/debug/agent/intent-prompt
 *
 *   # 少样本的加载状态与每个目标的样本数
 *   curl -s localhost:8080/api/debug/agent/intent-fewshot | python -m json.tool
 * </pre>
 *
 * <h3>★ 意图树探针比「直接打开 YAML 看」多提供什么</h3>
 *
 * <p>不多 —— 如果一切正常的话。这个探针真正的价值在<b>出问题的时候</b>：
 *
 * <ul>
 *   <li>{@code fileModifiedAt} 告诉你<b>当前生效的是不是你以为的那个版本</b>。
 *       意图树支持热加载（按修改时间判断），所以「我明明改了怎么没生效」
 *       和「文件根本没保存」在界面上长得一模一样。</li>
 *   <li>{@code lastRefreshError} 非空时说明<b>当前跑的是一棵旧树</b> ——
 *       热加载失败会沿用上一次的成功版本并打 ERROR 日志，
 *       但日志滚过去之后就只剩这个字段还记着。</li>
 *   <li>{@code docTypesUnion} 是<b>算出来的</b>，不在 YAML 里。
 *       一眼能看出「某一类意图的并集覆盖了全部 5 种类型」这种等于没过滤的情况。</li>
 * </ul>
 *
 * <p>阶段 5.2 调试「模型为什么把这个问题分错了」时，
 * 第一步就是确认「模型看到的分类体系到底是什么」—— 这个接口给的就是那个答案。
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/agent")
@Profile("local")
public class IntentProbeController {

    private final IntentTree intentTree;
    private final IntentFewShot intentFewShot;
    private final IntentPromptBuilder promptBuilder;
    private final LlmIntentClassifier classifier;

    /**
     * ★ 阶段 9.2：把门控的结论也报出来。
     *
     * <p>★ 用的是<b>线上那同一个 Bean</b>，不是探针里再实现一遍 ——
     * 同 {@code RetrievalDetailBuilder} 那条纪律：调试看到的和线上跑的
     * 必须是同一个函数，否则「探针说会检索」这件事不构成证据。
     */
    private final RetrievalGate retrievalGate;

    public IntentProbeController(IntentTree intentTree,
                                 IntentFewShot intentFewShot,
                                 IntentPromptBuilder promptBuilder,
                                 LlmIntentClassifier classifier,
                                 RetrievalGate retrievalGate) {
        this.intentTree = intentTree;
        this.intentFewShot = intentFewShot;
        this.retrievalGate = retrievalGate;
        this.promptBuilder = promptBuilder;
        this.classifier = classifier;
    }

    /**
     * ★ <b>分类一个问题的意图</b>（5.2 的验收入口）。
     *
     * <pre>
     *   curl -s -G localhost:8080/api/debug/agent/classify \
     *     --data-urlencode "q=退货要几天" | python -m json.tool
     * </pre>
     *
     * <p>⚠️ 中文参数请用 {@code scripts/probe_kb.py} 或百分号编码 ——
     * Windows + Git Bash 下直接把中文给 curl 会变成 {@code U+FFFD}，
     * 而「分类结果莫名其妙」看起来会像模型的问题。见 CLAUDE.md。
     *
     * <p>返回体里带上 {@code rawReply} 和 {@code modelKey}：
     * 分类错了的时候，第一个要问的是「模型到底答了什么」和
     * 「是不是降级到 P2 在答」—— 这两件事决定了往哪个方向查。
     */
    @GetMapping("/classify")
    public Map<String, Object> classify(@RequestParam("q") String question) {
        IntentClassification result = classifier.classify(question);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", question);
        response.put("intent", result.code());
        response.put("outcome", result.outcome().name());
        response.put("classified", result.isClassified());
        // 模型的原话 —— 排查「模型到底答了什么」时只有它有用
        response.put("rawReply", result.rawReply());
        response.put("provider", result.descriptor() == null ? null : result.descriptor().provider());
        response.put("modelId", result.descriptor() == null ? null : result.descriptor().modelId());
        response.put("modelKey", result.descriptor() == null ? null : result.descriptor().modelKey());
        response.put("cost", result.cost());
        response.put("latencyMs", result.latencyMs());
        response.put("error", result.error());

        // 分类成功时顺带把检索范围也报出来 —— 那是这个意图【真正的用处】
        // （5.4 要拿它当 WHERE 条件），也是验证树有没有画对的地方
        if (result.isClassified()) {
            response.put("docTypes", intentTree.get().docTypesOf(result.code()));

            // ★★ 阶段 9.2：把【计划】和【门控的结论】一起报出来。
            //
            //    没有这一段的话，排查「这次为什么没检索」只有两条路：
            //    去读 qa_log（要真跑一次问答），或者去猜。
            //    而 shape 这一格尤其需要在这里看得见 —— 它是
            //    「模型有没有按新契约作答」的唯一判据，而契约没生效时
            //    【没有任何别的东西会异常】。
            IntentPlan plan = result.plan();
            Map<String, Object> planView = new LinkedHashMap<>();
            planView.put("shape", plan == null ? null : plan.shape().name());
            planView.put("modelRetrieve", plan == null ? null : plan.retrieve());
            planView.put("missing", plan == null ? List.of() : plan.missingSlots());
            response.put("plan", planView);

            RetrievalGate.Decision gate = retrievalGate.decide(result);
            Map<String, Object> gateView = new LinkedHashMap<>();
            gateView.put("path", gate.path().name());
            gateView.put("reason", gate.reason());
            gateView.put("willRetrieve", gate.shouldRetrieve());
            // ★ 9.3：报【生效的清单】，不只是个布尔量。
            //   混合轮上 path 是 RETRIEVE 而 hasTools() 是 true ——
            //   只看 path 会让人以为这次没有工具可用
            gateView.put("tools", gate.tools());
            gateView.put("willUseTools", gate.hasTools());
            response.put("gate", gateView);
        }
        return response;
    }

    /**
     * ★ <b>看分类 prompt 的原文</b>（不调用模型，不花钱）。
     *
     * <p>它有存在的必要，因为「分类不准」最常见的两个原因是
     * <b>判据写糊了</b>和<b>样本放错了类别</b>，而这两件事在
     * JSON 返回里看不出来，必须读 prompt。
     *
     * <p>用 {@code text/plain} 返回，方便直接重定向到文件里 diff。
     */
    @GetMapping(value = "/intent-prompt", produces = "text/plain;charset=UTF-8")
    public String intentPrompt() {
        return promptBuilder.build();
    }

    /**
     * 少样本的加载状态。★ 顺带把「哪些分类目标有多少句样本」报出来 ——
     * 某个目标样本偏少时，模型在它和相邻目标之间的摇摆会变多，
     * 而那正是准确率下滑最常见的成因。
     */
    @GetMapping("/intent-fewshot")
    public Map<String, Object> intentFewShot() {
        IntentFewShot.Samples samples = intentFewShot.get();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("loadedFrom", intentFewShot.path().toAbsolutePath().toString());
        response.put("totalQuestions", samples.totalQuestions());

        List<Map<String, Object>> byTarget = new ArrayList<>();
        for (IntentTree.ClassificationTarget target
                : intentTree.get().classificationTargets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", target.code());
            m.put("name", target.displayName());
            m.put("sampleCount", samples.forIntent(target.code()).size());
            byTarget.add(m);
        }
        response.put("byTarget", byTarget);
        return response;
    }

    @GetMapping("/intent-tree")
    public Map<String, Object> intentTree() {
        IntentTree.Tree tree = intentTree.get();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("loadedFrom", intentTree.path().toAbsolutePath().toString());
        response.put("fileModifiedAt", fileModifiedAt());
        // ★ 非 null 意味着当前用的是【上一次加载成功的旧树】
        response.put("lastRefreshError", intentTree.lastRefreshError());

        response.put("version", tree.version());
        response.put("businessIntents", tree.businessIntents().size());
        response.put("hasOutOfScope", tree.outOfScope().isPresent());
        response.put("hasClarify", tree.clarify().isPresent());
        response.put("leafCount", tree.leafCount());
        // ★ 分类目标的个数和叶子的个数【不是一回事】——
        //   KB 类展开到叶子，TOOL/NONE 类只算一个。模型看到的是这个数
        response.put("classificationTargets", tree.classificationTargets().size());

        List<Map<String, Object>> topLevel = new ArrayList<>();
        for (IntentTree.TopIntent top : tree.roots()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", top.code());
            m.put("name", top.name());
            m.put("retrieval", top.retrieval().name());
            // 三种角色：BUSINESS / OUT_OF_SCOPE / CLARIFY。只有 BUSINESS 计入「5 类」
            m.put("role", top.role().name());
            // ★ 算出来的，不在 YAML 里 —— 5.4 用的是叶子级的 docTypes，
            //   这个并集是给人「看全局」用的
            m.put("docTypesUnion", top.docTypesUnion());
            // ★ 阶段 9.3：工具的合法落点是「分类落点」—— TOOL 类在【顶层】、
            //   KB 类在【叶子】（见 IntentTree 的加载期校验）。
            //   所以这一格要显示出来，而不是只留在 YAML 里让人去数缩进 ——
            //   「这个叶子挂了哪些工具」是排查「模型手上为什么没有工具」的第一站
            if (!top.tools().isEmpty()) {
                m.put("tools", top.tools());
            }

            List<Map<String, Object>> leaves = new ArrayList<>();
            for (IntentTree.Leaf leaf : top.children()) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("code", leaf.code());
                lm.put("name", leaf.name());
                lm.put("docTypes", leaf.docTypes());
                lm.put("exampleCount", leaf.examples().size());
                // ★ 同上。★ 一个 KB 叶子带了工具 = 混合轮（先检索、再给工具）
                if (!leaf.tools().isEmpty()) {
                    lm.put("tools", leaf.tools());
                }
                // ★ 阶段 5.9：只有非 NONE 时才出现这一格。
                //   不写成「永远出现、值为 NONE」是因为 5 个顶层里绝大多数叶子
                //   都是 NONE，全列出来会把真正的声明淹掉 ——
                //   而这个字段的全部价值就是「一眼看出哪些叶子声明了它」
                if (leaf.structuredFact() != IntentTree.StructuredFact.NONE) {
                    lm.put("structuredFacts", leaf.structuredFact().name());
                }
                leaves.add(lm);
            }
            m.put("leaves", leaves);
            topLevel.add(m);
        }
        response.put("topLevel", topLevel);

        // ★ 单列一栏「声明了结构化事实的叶子」—— 见 Tree.leavesWith 的注释。
        //   ⚠️ 和 retrieval 不同，这个字段【不参与分类】：
        //   上面那个 classificationTargets 的数目不受它影响（加它对 5.2 零影响）
        response.put("structuredFactLeaves",
                tree.leavesWith(IntentTree.StructuredFact.POLICY));

        // 顺便把「哪些叶子没被任何评测题标注过」这件事留给评测探针去报
        // （那需要查库，本探针刻意不碰数据库 —— controller 层不写业务逻辑）
        response.put("note", "叶子数是分类的选项数；5.2 的 prompt 按 topLevel 递归拼装。"
                + "评测题的意图标注覆盖率见 POST /api/debug/eval/reload 返回的 byIntent");
        return response;
    }

    private String fileModifiedAt() {
        try {
            FileTime mtime = Files.getLastModifiedTime(intentTree.path());
            return OffsetDateTime.ofInstant(mtime.toInstant(), ZoneId.systemDefault()).toString();
        } catch (IOException e) {
            return null;
        }
    }
}
