package com.xbla.rag.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.agent.intent.IntentTree;
import com.xbla.rag.entity.EvalQuestion;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.EvalQuestionMapper;
import com.xbla.rag.mapper.KbChunkMapper;
import com.xbla.rag.mapper.QaLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 阶段 7 的<b>指标计算</b>：把一轮评测的 {@code qa_log} 行算成一张报告。
 *
 * <h2>一、★ 为什么指标算在 Java 里，而不是脚本里</h2>
 *
 * <p>{@code scripts/eval_run.py} 已经拿到了一大堆东西，但它<b>算不了</b>这些指标，
 * 因为它缺两个只有 Java 侧才有的东西：
 *
 * <pre>
 *   ① IntentTree.docTypesOf(code) —— 「这个叶子声明了哪些 doc_type」
 *      Python 要算它就得把意图树解析一遍。那是【第二个事实来源】，
 *      而它迟早会和 Java 那个说不同的话（改了 yml、忘了改脚本）
 *
 *   ② 澄清闸门的判据 —— 「该不该反问」等价于「分类结果落在 role:CLARIFY 分支上」，
 *      那是树里的一个字段，不是脚本能猜的
 * </pre>
 *
 * <p>所以这里的分工是：<b>脚本负责「跑」和「对账」，Java 负责「算」</b>。
 * 这也让指标变成<b>零成本、可复算</b>的 —— 同一份库里的数据，
 * 换一天再调一次这个端点，必须得到逐字节一样的报告。
 *
 * <h2>二、★★ 数字旁边的口径和数字本身一样重要</h2>
 *
 * <p>本项目已经被这一类错误咬过好几次（见 {@code CLAUDE.md} 的「代码风格」一节）：
 * <b>一个数旁边如果挂着错的口径，那个数是不可解释的。</b>
 * 所以这个类做的每一件事都遵循同一条规矩：
 *
 * <ul>
 *   <li>每个比率都带着它的<b>分母</b>（{@code n}）一起进报告，不允许只报一个百分比</li>
 *   <li>每个<b>切片</b>（按意图 / 按类别）都印 n，且 {@code n < }{@value #MIN_SLICE_N}
 *       时明确写上「不作为结论」</li>
 *   <li>口径本身作为数据的一部分返回（{@code 口径} 那一节），而不是只写在注释里 ——
 *       注释不会跟着数据一起被抄进报告文件</li>
 * </ul>
 *
 * <h2>三、★ 这个类是纯函数</h2>
 *
 * <p>{@link #build} 是 {@code static} 的，输入是「一堆行 + 题库 + 两张对照表」，
 * 输出是一个 Map。<b>不碰数据库、不碰 Spring</b>（除了注入进来的那棵树）。
 *
 * <p>这么拆的理由和 {@code RetrievalDetailBuilder} 一样：<b>能测</b>。
 * 指标算错的症状是「数字看起来很正常」，而它不会报错 ——
 * 唯一能挡住它的是「喂一组人为构造的行，断言算出来的数就是手算的那个数」。
 */
@Slf4j
@Component
public class EvalReportService {

    /**
     * 切片样本数低于这个值时，报告里那个数字<b>不作为结论</b>。
     *
     * <p>★ 5 不是统计学的魔法数字，是这道题的算术：本项目最小的那一类意图只有
     * 3~5 道题，而一道题就是 20~33 个百分点。一个 n=3 的「100%」和一个
     * n=15 的「87%」放在同一张表里，前者的噪声比后者和后者的差距还大。
     *
     * <p>所以本类不做「过滤掉小样本」这种事（那会丢信息），只做<b>标记</b> ——
     * 数字照报，旁边写清它当不当得了证据。
     */
    public static final int MIN_SLICE_N = 5;

    private final QaLogMapper qaLogMapper;
    private final EvalQuestionMapper evalQuestionMapper;
    private final KbChunkMapper kbChunkMapper;
    private final IntentTree intentTree;
    private final ObjectMapper mapper;

    /**
     * ★ 参数里的 {@code ObjectMapper} 是<b>注入到 {@code @Primary} 的那个</b>
     * （{@code JacksonConfig.webObjectMapper}），不是模型协议那个。
     * 这里只用它做一件事：把 {@code retrieval_detail} 这个我们<b>自己写进去的</b>
     * String 解析回 {@code JsonNode}。两条路都读得动，但用对外的那个更贴近
     * 「它是本应用的持久化格式」这个事实。
     */
    public EvalReportService(QaLogMapper qaLogMapper,
                             EvalQuestionMapper evalQuestionMapper,
                             KbChunkMapper kbChunkMapper,
                             IntentTree intentTree,
                             ObjectMapper mapper) {
        this.qaLogMapper = qaLogMapper;
        this.evalQuestionMapper = evalQuestionMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.intentTree = intentTree;
        this.mapper = mapper;
    }

    // ================================================================
    // 取数
    // ================================================================

    /**
     * 算一轮评测的报告。
     *
     * <p>取三份数据：那一轮的 {@code qa_log} 行、<b>整个题库</b>、以及
     * 两张 doc_type 对照表。题库特意取全量而不是只取跑过的那些 ——
     * 因为「这道题在库里但这一轮没跑」是必须被看见的（它会让报告的分母
     * 悄悄小于题库，而那<b>不会报错</b>）。
     */
    public Map<String, Object> report(String runId) {
        List<QaLog> rows = qaLogMapper.selectByEvalRun(runId);

        Map<String, EvalQuestion> bank = new TreeMap<>();
        for (EvalQuestion q : evalQuestionMapper.selectList(null)) {
            bank.put(q.getQuestionNo(), q);
        }

        // 正解切片的 doc_type：从「题库 × 正解切片」这条链上取
        Map<String, Set<Integer>> goldDocTypes = new TreeMap<>();
        for (QuestionGoldDocType row : evalQuestionMapper.findGoldDocTypes()) {
            goldDocTypes.computeIfAbsent(row.questionNo(), k -> new TreeSet<>())
                    .add(row.docType());
        }

        // 全库切片 → doc_type：判「送进 prompt 的上下文有没有越界」用
        Map<Long, Integer> chunkDocTypes = new LinkedHashMap<>();
        for (ChunkDocType row : kbChunkMapper.findAllChunkDocTypes()) {
            chunkDocTypes.put(row.chunkId(), row.docType());
        }

        return build(intentTree.get(), mapper,
                new Inputs(runId, rows, bank, goldDocTypes, chunkDocTypes));
    }

    /**
     * {@link #build} 的全部输入 —— 一个记录而不是五个参数。
     *
     * <p>理由很实际：这五样东西是<b>一起</b>被造出来、一起被传下去的，
     * 中间任何一处少传一个都会得到一份「看起来正常但数字不对」的报告。
     * 打包成一个对象之后，「传少了」这件事连编译都过不去。
     *
     * @param rows         该轮的全部 {@code qa_log} 行，已按 (题号, id) 排序
     * @param bank         {@code questionNo → 题目}。★ <b>全量题库</b>，不只是跑过的
     * @param goldDocTypes {@code questionNo → 该题正解切片落在哪些 doc_type}。
     *                     ⚠️ 正解切片<b>一条都没匹配上</b>（被删了）的题<b>不在这里面</b> ——
     *                     调用方必须把「键不存在」和「值空集」当成两件事
     * @param chunkDocTypes {@code chunkId → doc_type}，全库。查不到 = 这条切片不在库里了
     */
    public record Inputs(String runId,
                         List<QaLog> rows,
                         Map<String, EvalQuestion> bank,
                         Map<String, Set<Integer>> goldDocTypes,
                         Map<Long, Integer> chunkDocTypes) {
    }

    // ================================================================
    // 计算（纯函数 —— 测试从这里进来）
    // ================================================================

    static Map<String, Object> build(IntentTree.Tree tree, ObjectMapper mapper, Inputs in) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", in.runId());
        out.put("口径", definitions());

        // ── 分组 ────────────────────────────────────────────────
        Map<String, List<QaLog>> byQuestion = new TreeMap<>();
        for (QaLog row : in.rows()) {
            String no = row.getEvalQuestionNo();
            byQuestion.computeIfAbsent(no == null ? "(无题号)" : no, k -> new ArrayList<>())
                    .add(row);
        }

        // 树里的三个「特殊分支码」—— 一律从树读，不硬编码字符串。
        // ★ 硬编码的后果不是报错，是「改了 yml 之后报告里的分类悄悄错位」
        String clarifyCode = tree.clarify().map(IntentTree.TopIntent::code).orElse(null);
        String outOfScopeCode = tree.outOfScope().map(IntentTree.TopIntent::code).orElse(null);

        List<QuestionView> views = new ArrayList<>();
        for (Map.Entry<String, List<QaLog>> e : byQuestion.entrySet()) {
            if ("(无题号)".equals(e.getKey())) {
                continue;   // 单独数出来，不进任何指标
            }
            views.add(new QuestionView(e.getKey(), in.bank().get(e.getKey()), e.getValue(),
                    in.chunkDocTypes(), mapper));
        }

        out.put("数据完整性", integrity(in, byQuestion, views));
        out.put("意图", intentSection(tree, views));
        out.put("澄清边界", clarifyBoundary(clarifyCode, views));
        out.put("检索范围", scopeSection(tree, views));
        out.put("兜底", outOfScopeSection(outOfScopeCode, views, tree));
        out.put("检索", retrievalSection(mapper, views));
        out.put("归因", attributionSection(mapper, views));
        out.put("过度检索", overRetrievalSection(tree, mapper, in, views));
        out.put("延迟", latencySection(in.rows()));
        out.put("成本", costSection(in.rows()));
        // ★ 放最后：它是【原始材料】，上面每一段都是它的汇总。
        //   读报告的人先看结论，需要追一个数怎么来的再往下翻
        out.put("逐题", perQuestionSection(views, tree));
        out.put("★切片可信度纪律", Map.of(
                "MIN_SLICE_N", MIN_SLICE_N,
                "说明", "每个切片都带 n。n < " + MIN_SLICE_N + " 的切片旁边写了 可信=false —— "
                        + "数字照报，但它【不作为结论】，不要拿它去支撑「X 比 Y 好」。"
                        + "本项目最小的意图只有 3 道题，而一道题就是 33 个百分点。"));
        return out;
    }

    // ================================================================
    // 口径表
    // ================================================================

    /**
     * 每个数字的定义，<b>作为数据返回</b>。
     *
     * <p>★ 不写成类注释的原因很直白：报告是<b>被抄进 {@code docs/11} 的</b>，
     * 而注释不会跟着一起去。口径和数字分开存放的最终结局是
     * 「文档里写着口径 A，而 JSON 里的数字是按口径 B 算的」——
     * 两边各自都是对的，合起来是一句谎话。
     */
    private static Map<String, Object> definitions() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("意图准确率",
                "分母 = 【intent 非空的行】。★ 不是 status=1 的行 —— 分类发生在澄清闸门【之前】，"
                        + "所以被闸门挡掉的行也带着 intent。只统计 status=1 会让「多挡一些」"
                        + "机械地抬高准确率（幸存者偏差）。"
                        + "★ 另报「逐题取众数」的版本，以及「非全票一致」的题 —— 后者才是"
                        + "「这个数换个时间跑可能翻」的真实信号。");
        d.put("意图准确率的主口径不在这里",
                "★★ 分类器只看那一句话，不需要跑完整管线。所以主口径在 "
                        + "scripts/eval_intent_probe.py（12~20 次重复，样本多 4 倍、更便宜，"
                        + "而且天然没有闸门的幸存者偏差）。本端点的这一份是【线上实际发生】的口径 —— "
                        + "两者都要报，报告里必须写清哪个是哪个。");
        d.put("检索范围准确率",
                "分类结果的 doc_types 集合 == 标注的 doc_types 集合。"
                        + "★ 主数字的分母【只在 retrieval:KB 的题】上 —— 见 检索范围.note。");
        d.put("兜底误判率",
                "分母 = 标注为【兜底分支】的题；分子 = 其中被分类成业务意图的。"
                        + "★ 它和「过度检索率」的中文名很像，但修法完全相反，别混。");
        d.put("HitRate@5 / Recall@5 / MRR@5",
                "只统计 status=1 的行，且【每个题取第一次 status=1 的那次】——"
                        + "不是第 1 次（那道题可能恰好被闸门挡了），也不是任取一次。"
                        + "★ 分母【已排除「声明不检索」的题】（工具/兜底/澄清），"
                        + "因为把它们算成「没召回到」会把平均机械地拉低十几个点。");
        d.put("过度检索率",
                "最终送进 prompt 的上下文里，doc_type 不在期望集合内的切片占比（微平均：总越界条数 / 总条数）。"
                        + "★ 两个口径【分开命名】：对标注叶子 / 对分类叶子。");
        d.put("延迟",
                "五列：queue / retrieval / rerank / llm / 未归类。"
                        + "★★ 【rerank ⊂ retrieval，是包含关系不是并列关系】——"
                        + "所以这几列【不相加等于 total】。未归类 = total - queue - retrieval - llm，"
                        + "它含【意图分类那一次模型往返】和会话记忆，是本项目最大的一块未拆分耗时。");
        d.put("分位数",
                "最近秩（nearest-rank）：升序排序后取第 ceil(p×n) 个，不插值。"
                        + "★ 选它的理由是它【永远返回一个真的观测到的值】—— 插值会算出一个"
                        + "任何一次请求都没达到过的数，而报告里那个数会被当成「真实体验」。"
                        + "Python 侧对拍用同一套算法（sorted_vals[ceil(p*n)-1]）。");
        d.put("检索范围准确率的分母",
                "见 检索范围.note。一句话：TOOL/NONE 的顶层码两侧都是空集，"
                        + "判「一致」是恒真的，所以把它们放进分母等于往里面掺水。");
        return d;
    }

    // ================================================================
    // 数据完整性
    // ================================================================

    /**
     * 先证明这批数据<b>能被当成一轮评测</b>，再谈指标。
     *
     * <p>★ 这一节的存在理由是：上面每一个指标的分母都是从这批行里数出来的，
     * 而「行本身有问题」时它们<b>全都会算出一个看起来很正常的数</b>。
     * 比如少了 30 道题，所有比率的分子分母会一起缩小，比率纹丝不动。
     */
    private static Map<String, Object> integrity(Inputs in,
                                                 Map<String, List<QaLog>> byQuestion,
                                                 List<QuestionView> views) {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Long> byStatus = new TreeMap<>();
        for (QaLog row : in.rows()) {
            byStatus.merge(String.valueOf(row.getStatus()), 1L, Long::sum);
        }
        out.put("行数", in.rows().size());
        out.put("按status", byStatus);

        // ★ 「有 runId 但没有题号」= 既进不了评测统计、又混在真实数据里。
        //   它不会报错，只会让某一轮的分母少几行。必须数出来。
        int noQuestionNo = in.rows().stream()
                .filter(r -> r.getEvalQuestionNo() == null || r.getEvalQuestionNo().isBlank())
                .toList().size();
        out.put("★有runId却没有题号的行", noQuestionNo);

        // 跑过的题号 vs 题库
        List<String> notInBank = new ArrayList<>();
        List<String> inBankNotRun = new ArrayList<>();
        int ranWithGold = 0;
        int ranWithoutGold = 0;
        for (QuestionView v : views) {
            if (v.bank() == null) {
                notInBank.add(v.questionNo());
                continue;
            }
            if (Boolean.TRUE.equals(v.bank().getExpectNoRetrieval())) {
                ranWithoutGold++;
            } else {
                ranWithGold++;
            }
        }
        // 题库里「这一轮没跑」的题 —— 只在跑过的那几套题集里找，
        // 否则会把另外两套题全列出来，那不是异常，是设计
        Set<String> setsRan = new TreeSet<>();
        for (QuestionView v : views) {
            if (v.bank() != null && v.bank().getQuestionSet() != null) {
                setsRan.add(v.bank().getQuestionSet());
            }
        }
        for (EvalQuestion q : in.bank().values()) {
            if (setsRan.contains(q.getQuestionSet()) && !byQuestion.containsKey(q.getQuestionNo())) {
                inBankNotRun.add(q.getQuestionNo());
            }
        }

        out.put("questionSet", setsRan);
        out.put("★跑了但题库里没有的题号", notInBank);
        out.put("★在题库里但这一轮没跑的题号", inBankNotRun);
        out.put("跑过的题数", views.size());
        out.put("  其中 声明要检索的", ranWithGold);
        out.put("  其中 声明不检索的", ranWithoutGold);
        out.put("note", "★ 上面两个带★的列表非空 = 这一轮的数据不能用 —— "
                + "前者说明有人删了题，后者说明跑题器漏发了题。"
                + "两种都会让报告的分母悄悄小于题库，而【比率看不出任何异常】。"
                + "对账（scripts/eval_run.py）只保证「发了的都落库了」，"
                + "保证不了「该发的都发了」—— 这一节补的就是后者。");
        return out;
    }

    // ================================================================
    // 意图
    // ================================================================

    private static Map<String, Object> intentSection(IntentTree.Tree tree, List<QuestionView> views) {
        Map<String, Object> out = new LinkedHashMap<>();

        // ── 逐行 ──
        long rowTotal = 0;
        long rowCorrect = 0;
        for (QuestionView v : views) {
            for (QaLog row : v.rows()) {
                if (row.getIntent() == null) {
                    continue;
                }
                rowTotal++;
                if (row.getIntent().equals(v.goldIntent())) {
                    rowCorrect++;
                }
            }
        }
        out.put("逐行", ratio(rowCorrect, rowTotal));

        // ── 逐题取众数 ──
        List<QuestionView> withGold = views.stream().filter(v -> v.goldIntent() != null).toList();
        long qCorrect = 0;
        List<String> swing = new ArrayList<>();
        List<String> tied = new ArrayList<>();
        for (QuestionView v : withGold) {
            if (v.intentMode() != null && v.intentMode().equals(v.goldIntent())) {
                qCorrect++;
            }
            if (!v.unanimous()) {
                swing.add(v.questionNo());
            }
            if (v.intentMode() == null) {
                tied.add(v.questionNo());
            }
        }
        out.put("逐题取众数", ratio(qCorrect, withGold.size()));
        out.put("★非全票一致的题（主指标）", Map.of(
                "n", (long) swing.size(),
                "分母", (long) withGold.size(),
                "题号", swing,
                "说明", "★ 这些题的众数是【有】的，但那意味着它的分数换个时间跑就可能翻。"
                        + "本项目实测：同 20 题同 prompt 两轮各 8 次，准确率一样、错题一样，"
                        + "但逐题票型变了（一道从 6:2 变成 8:0）。"
                        + "3 次重复只会把 6:2 渲染成 2:1 或 3:0 —— 后者看起来最稳。"));
        out.put("无唯一众数的题（诊断）", Map.of(
                "n", (long) tied.size(),
                "题号", tied,
                "说明", "⚠️ 这个数【几乎永远是 0】，别盯着它。两轮各 8 次实测都是 0 道 —— "
                        + "真正的不稳定长成 6:2 / 7:1，而不是平票。主指标是上面那个。"));

        // ── 切片：按标注意图 / 按类别 ──
        out.put("按标注意图切片", byKey(withGold, QuestionView::goldIntent,
                v -> v.intentMode() != null && v.intentMode().equals(v.goldIntent())));
        out.put("按类别切片", byKey(withGold,
                v -> v.bank() == null ? "(题库里没有)" : String.valueOf(v.bank().getCategory()),
                v -> v.intentMode() != null && v.intentMode().equals(v.goldIntent())));
        out.put("按难度切片", byKey(withGold,
                v -> v.bank() == null || v.bank().getDifficulty() == null
                        ? "(未标)" : String.valueOf(v.bank().getDifficulty()),
                v -> v.intentMode() != null && v.intentMode().equals(v.goldIntent())));

        // ★ 分类失败（intent 为 null）：单列出来。它和「分错」是两件事 ——
        //   前者是基础设施/prompt 挂了，后者是树不好理解
        List<String> unclassified = withGold.stream()
                .filter(v -> v.rows().stream().allMatch(r -> r.getIntent() == null))
                .map(QuestionView::questionNo).toList();
        out.put("★一次都没分类成功的题", unclassified);

        // ══════════════════════════════════════════════════════════
        // ★★★ 下面两栏是 2026-09-21 第一次跑真实数据时发现的
        //
        // `IntentTree.classificationTargets()` 的规则是「行为相同的不区分」：
        //   retrieval = KB    → 展开到【叶子】（叶子之间 doc_types 不同）
        //   retrieval ≠ KB    → 只算【一个】目标，用【顶层 code】
        //
        // 于是 ORDER_LOGISTICS 是合法输出，而 ORDER_STATUS / INVENTORY /
        // MY_COUPON 这三个【工具叶子码】模型【永远不可能输出】。
        // 而题库里那 15 道工具题的 gold 恰恰是那三个叶子码。
        //
        // ★★ 后果：它们在「意图准确率」的分子里恒为 0，与分类质量【无关】。
        //    实测 159 题错 25 道，其中 15 道就是它们 —— 也就是说
        //    「84.3%」里有 9.4 个百分点是【口径造成的】，不是模型造成的。
        //
        // ★ 这不是 bug，是「两套粒度」必然的接缝。但一个数字旁边挂着错的口径
        //   就不可解释 —— 所以这里把两个数【都】报出来，并写清哪个是哪个。
        // ══════════════════════════════════════════════════════════
        List<String> notATarget = new ArrayList<>();
        long comparableHit = 0;
        long comparableTotal = 0;
        for (QuestionView v : withGold) {
            if (tree.findTarget(v.goldIntent()).isEmpty()) {
                notATarget.add(v.questionNo() + "（gold=" + v.goldIntent() + "）");
                continue;
            }
            comparableTotal++;
            if (v.intentMode() != null && v.intentMode().equals(v.goldIntent())) {
                comparableHit++;
            }
        }
        out.put("★★gold不是合法分类目标的题", Map.of(
                "n", (long) notATarget.size(),
                "题号", notATarget,
                "说明", "★★ 这些题的标注意图【不在 classificationTargets() 里】——"
                        + "分类器只被要求输出「KB 叶子 + TOOL/NONE 顶层」，"
                        + "所以 TOOL 的三个叶子码（ORDER_STATUS / INVENTORY / MY_COUPON）"
                        + "模型永远不可能输出。★ 它们在「意图准确率」的分子里【恒为 0】，"
                        + "与分类质量无关。见下面那一栏的修正口径。"));
        out.put("★★可比口径的准确率", ratio(comparableHit, comparableTotal));
        out.put("★口径的关系", "逐题取众数 = 全部题都算（含 gold 不是合法目标的那些，"
                + "它们恒错）；可比口径 = 只算 gold 确实是合法分类目标的题。"
                + "★ 两个都要报，而且要写清哪个是哪个 —— "
                + "只报前者会把「树的粒度设计」记成「模型不行」，"
                + "只报后者会掩盖「题库有一批题测不出东西」这个事实。");

        // ★ 工具题的正确量法：模型输出的是顶层，所以【比到顶层】这一级
        List<String> toolMisrouted = new ArrayList<>();
        long toolTotal = 0;
        long toolTopOk = 0;
        for (QuestionView v : withGold) {
            if (tree.retrievalOf(v.goldIntent()) != IntentTree.Retrieval.TOOL) {
                continue;
            }
            toolTotal++;
            String pred = v.intentMode();
            if (pred != null && tree.retrievalOf(pred) == IntentTree.Retrieval.TOOL) {
                toolTopOk++;
            } else {
                toolMisrouted.add(v.questionNo() + "（gold=" + v.goldIntent()
                        + " 应为工具，实判 " + pred + "）");
            }
        }
        out.put("★工具题判到顶层这一级", Map.of(
                "可比口径_判成工具类", ratio(toolTopOk, toolTotal),
                "误路由的题", toolMisrouted,
                "说明", "★ 工具题的 gold 是叶子码（ORDER_STATUS），而模型只会输出顶层"
                        + "（ORDER_LOGISTICS）—— 所以「code 完全一致」对它们【永远不成立】。"
                        + "正确的量法是「有没有被路由到工具这一类」，"
                        + "那才是这 15 道题真正在测的东西。"));
        out.put("note", "逐行与逐题两个口径【都要报】：逐行衡量「一次问答准不准」，"
                + "逐题众数衡量「这道题稳不稳」。只报前者会让一道 2:1 的题看起来"
                + "和一道 3:0 的题一样。");
        return out;
    }

    // ================================================================
    // 澄清边界混淆矩阵（阶段 7 · T4 的硬要求之一）
    // ================================================================

    /**
     * ★ 「该反问的有没有反问 / 不该反问的有没有反问」。
     *
     * <p>这条边界在阶段 5.3 落地时<b>从来没有被单独量过</b>：单轮题库里
     * {@code NEEDS_CLARIFICATION} 那 6 道只覆盖了「该反问」那一侧，
     * 而另一侧（指代词 ≠ 信息不足）散在各业务叶子里，看不出总量。
     *
     * <h3>★★ 这张矩阵和意图混淆矩阵是同源的，这一点必须写出来</h3>
     *
     * <p>{@code ClarificationDecider} 的判据就是
     * <b>「分类结果落在 {@code role:CLARIFY} 那个分支上」</b>——
     * 它不是第二个模型、也不是置信度阈值。所以逐行矩阵里的每一格
     * 都和 {@code qa_log.intent} 一一对应，差别只在于：
     *
     * <pre>
     *   intent 那一列   ← 分类器【说了什么】（分类失败时为 null）
     *   status=3        ← 系统【做了什么】（分类失败时照常检索，所以是 1）
     * </pre>
     *
     * <p>两列都报，是为了让「分类失败导致没反问」这件事可见 ——
     * 它在这张矩阵里表现为「intent 是 null 但 status 是 1」。
     * 只报一个的话，那几行会静默地被算进「不该反问且没反问」，也就是看起来完全正常。
     */
    private static Map<String, Object> clarifyBoundary(String clarifyCode, List<QuestionView> views) {
        Map<String, Object> out = new LinkedHashMap<>();
        String clarify = clarifyCode == null ? "" : clarifyCode;

        // ── 逐行 ──
        long shouldClarify = 0, clarified = 0, falsePositive = 0, notClarifiedShould = 0;
        long goldUnknown = 0;
        for (QuestionView v : views) {
            String gold = v.goldIntent();
            if (gold == null) {
                goldUnknown += v.rows().size();
                continue;
            }
            boolean goldWants = clarify.equals(gold);
            for (QaLog row : v.rows()) {
                boolean didClarify = row.getStatus() != null
                        && row.getStatus() == QaLog.STATUS_CLARIFY;
                if (goldWants) {
                    shouldClarify++;
                    if (didClarify) {
                        clarified++;
                    } else {
                        notClarifiedShould++;
                    }
                } else if (didClarify) {
                    falsePositive++;
                }
            }
        }
        long notClarifiedTotal = views.stream()
                .filter(v -> v.goldIntent() != null && !clarify.equals(v.goldIntent()))
                .mapToLong(v -> v.rows().size()).sum();

        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("★该反问_且反问了", clarified);
        rows.put("★该反问_没反问（漏）", notClarifiedShould);
        rows.put("★不该反问_却反问了（假阳）", falsePositive);
        rows.put("不该反问_没反问", notClarifiedTotal - falsePositive);
        rows.put("分母_该反问的行", shouldClarify);
        rows.put("分母_不该反问的行", notClarifiedTotal);
        rows.put("标注为澄清但未计的行", goldUnknown);
        out.put("逐行", rows);

        // ── 逐题（按题内多数票）──
        List<QuestionView> withGold = views.stream().filter(v -> v.goldIntent() != null).toList();
        long tp = 0, fn = 0, fp = 0, tn = 0;
        List<String> jitter = new ArrayList<>();
        List<String> allClarified = new ArrayList<>();
        for (QuestionView v : withGold) {
            boolean goldWants = clarify.equals(v.goldIntent());
            Boolean mode = v.statusMode();
            if (goldWants) {
                if (Boolean.TRUE.equals(mode)) {
                    tp++;
                } else {
                    fn++;
                }
            } else if (Boolean.TRUE.equals(mode)) {
                fp++;
            } else {
                tn++;
            }
            if (!v.statusUnanimous()) {
                jitter.add(v.questionNo());
            }
            if (Boolean.TRUE.equals(mode)) {
                allClarified.add(v.questionNo());
            }
        }
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("★该反问_且反问了", tp);
        q.put("★该反问_没反问（漏）", fn);
        q.put("★不该反问_却反问了（假阳）", fp);
        q.put("不该反问_没反问", tn);
        q.put("分母", (long) withGold.size());
        out.put("逐题（多数票）", q);

        out.put("★闸门抖动的题", Map.of(
                "n", (long) jitter.size(),
                "题号", jitter,
                "说明", "★ 同一道题重复跑几次，status 不一致 = 用户会【时而被反问、时而被回答】。"
                        + "★ 这个量在意图探针里测不出来 —— 探针不过闸门。"
                        + "它是 T3 那套 --repeat 存在的三个理由之一（另两个是检索抖动和答案抖动）。"));
        out.put("反问了哪些题", allClarified);
        out.put("放行率", ratio(withGold.size() - allClarified.size(), withGold.size()));
        out.put("判据", "反问 ⟺ 分类结果 == 树里 role:CLARIFY 的那个分支码（当前："
                + (clarifyCode == null ? "树里没有这个分支" : clarifyCode) + "）。"
                + "★★ 所以这张矩阵与意图混淆矩阵【同源】—— ClarificationDecider 不是一个"
                + "独立的模型，它读的就是分类结果。区别只在分类失败的行：那里 intent 是 null、"
                + "而 status 照常是 1（分类失败不澄清，见 ClarificationDecider 类注释第二节）。");
        return out;
    }

    // ================================================================
    // 检索范围准确率
    // ================================================================

    /**
     * 分类结果的 {@code doc_types} 集合 == 标注的集合。
     *
     * <h3>★★ 为什么主数字的分母只在 KB 题上（2026-09-21 拍板）</h3>
     *
     * <p>{@code IntentTree.docTypesOf} 对 <b>TOOL/NONE 的顶层码</b>返回<b>空集</b>
     * （它只查叶子）。而工具题和兜底题的标注恰恰也是顶层码 ——
     * 于是「分类结果」和「标注」<b>两边都是空集</b>，比较结果是「一致」。
     *
     * <p>这不是错的，是<b>恒真的</b>：那 28 道题对这个指标的贡献与分类质量无关。
     * 把它们放进分母等于往里面掺 28 份必然正确的答案 ——
     * 指标会变好看，而它衡量分类质量的能力一点没变。这和
     * {@code qa_log.queue_position} 恒为 0 是同一类问题：
     * <b>一个看起来像数据的数，不携带信息。</b>
     *
     * <p>所以：<b>主数字用 KB 题做分母</b>，同时把全体口径和两个分母都印出来。
     * 两个数都在，读者知道该信哪个。
     *
     * <p>★ 但非 KB 题<b>不是完全没用</b>：一道工具题如果被分成了 KB 叶子，
     * 它的 {@code docTypesOf} 就非空了，那时「不一致」会正确触发。
     * 所以「非 KB 题里有多少道不一致」也是一个有信息量的数，一起报。
     */
    private static Map<String, Object> scopeSection(IntentTree.Tree tree, List<QuestionView> views) {
        Map<String, Object> out = new LinkedHashMap<>();

        long kbTotal = 0, kbMatch = 0;
        long allTotal = 0, allMatch = 0;
        long nonKbTotal = 0, nonKbMatch = 0;
        List<String> mismatched = new ArrayList<>();
        List<String> unknownCode = new ArrayList<>();

        for (QuestionView v : views) {
            String gold = v.goldIntent();
            String pred = v.intentMode();
            if (gold == null || pred == null) {
                continue;
            }
            allTotal++;
            boolean equal = tree.docTypesOf(gold).equals(tree.docTypesOf(pred));
            if (equal) {
                allMatch++;
            } else {
                // ★★ 渲染前【排序】。`docTypesOf` 返回的是 Set，而 Set 的迭代顺序
                //    不是它承诺的东西 —— 这两个集合是【相等判断】用的，不是给人读的。
                //    直接拼接它，等于让 JDK 的内部实现决定报告里的文字顺序。
                //
                //    ⚠️ 这不是假想的风险：2026-09-22 实测到同一份数据、同一份
                //    intent-tree.yml（`doc_types: [1, 5]`），旧一轮报告印 `[1, 5]`、
                //    新一轮印 `[5, 1]`。用同样的 JDK 单独跑
                //    `Set.copyOf(List.copyOf(new ArrayList<>(List.of(1,5))))`
                //    得到的是 `[1, 5]` —— ★ 也就是说，**我没能解释它**。
                //    我没有继续追，因为正确的做法不是解释它，是【不再依赖它】：
                //    排序之后这两个字符串由我们决定，与 JDK、与 YAML 的书写顺序、
                //    与哈希桶位都无关。
                //
                //    ★ 数字不受影响（Set.equals 本来就与顺序无关，实测两轮
                //    检索范围准确率的分子分母逐字相同），变的是这一串人类可读文本。
                //    但报告是要被 diff、被抄进 docs/11 的 —— 那里的随机差异
                //    是纯粹的噪声，而噪声会让真正的变化淹没。
                mismatched.add(v.questionNo() + ": " + gold + sorted(tree.docTypesOf(gold))
                        + " → " + pred + sorted(tree.docTypesOf(pred)));
            }
            if (tree.retrievalOf(pred) == null) {
                unknownCode.add(v.questionNo() + " → " + pred);
            }
            if (tree.retrievalOf(gold) == IntentTree.Retrieval.KB) {
                kbTotal++;
                if (equal) {
                    kbMatch++;
                }
            } else {
                nonKbTotal++;
                if (equal) {
                    nonKbMatch++;
                }
            }
        }

        out.put("★主数字_KB题", ratio(kbMatch, kbTotal));
        out.put("全体", ratio(allMatch, allTotal));
        out.put("非KB题_单列", ratio(nonKbMatch, nonKbTotal));
        out.put("不一致的题", mismatched);
        // ★★ 判据是 retrievalOf(pred) == null（既不是顶层也不是叶子）。
        //    第一版写的是 findLeaf(pred).isEmpty() —— 那会把 ORDER_LOGISTICS、
        //    OUT_OF_SCOPE、NEEDS_CLARIFICATION 这三个【合法的顶层分类目标】
        //    全部标成「编造的码」。它们在分类目标的定义里本来就是顶层，
        //    findLeaf 当然找不到 —— 而那个误报【不报错】，只是让报告里
        //    多出一栏看着很吓人的「25 道题模型在编码」
        out.put("★模型编了不存在的码的题", unknownCode);
        out.put("note", "★ 主数字的分母 = gold 的 retrieval 是 KB 的题。"
                + "非 KB 题（3 类工具 + 兜底 + 澄清）两侧 doc_types 都是空集，"
                + "比较结果恒真 —— 放进分母只会让指标变好看。"
                + "★ 但「非 KB 题里不一致的有几道」是有信息量的："
                + "它抓的是「工具题被判成知识库题」这种真错。"
                + "★ 分母用 gold 的 retrieval 而不是 pred 的："
                + "这道题该不该检索是【标注】决定的，不是分类结果决定的。");
        return out;
    }

    // ================================================================
    // 兜底误判率
    // ================================================================

    /**
     * 「该兜底的被当成了业务问题」——{@code docs/06} §1.4 的第二个指标。
     *
     * <p>★ 它和「过度检索率」的中文名很像（都在说「检索跑偏了」），
     * 但它们是两回事、修法也相反：
     * <pre>
     *   兜底误判率  ← 分类的问题（ADR-031）：不该检索的题去检索了
     *   过度检索率  ← 过滤的问题（ADR-032）：该检索的范围没锁住
     * </pre>
     *
     * <p>判「是不是业务意图」用的是<b>树里的 {@code role} 字段</b>，
     * 而不是「{@code retrievalOf} 是不是 KB」—— 后者会把
     * {@code ORDER_LOGISTICS}（{@code retrieval:TOOL}，但 {@code role:BUSINESS}）
     * 漏掉，而工具题被答成知识库题，代价和兜底题被答成知识库题一样大。
     */
    private static Map<String, Object> outOfScopeSection(String outOfScopeCode,
                                                         List<QuestionView> views,
                                                         IntentTree.Tree tree) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (outOfScopeCode == null) {
            out.put("note", "意图树里没有 role:OUT_OF_SCOPE 的分支，算不了");
            return out;
        }

        Set<String> businessCodes = new TreeSet<>();
        for (IntentTree.TopIntent top : tree.businessIntents()) {
            businessCodes.add(top.code());
            for (IntentTree.Leaf leaf : top.children()) {
                businessCodes.add(leaf.code());
            }
        }

        long total = 0, misjudged = 0;
        List<String> detail = new ArrayList<>();
        for (QuestionView v : views) {
            if (!outOfScopeCode.equals(v.goldIntent())) {
                continue;
            }
            total++;
            String pred = v.intentMode();
            if (pred != null && businessCodes.contains(pred)) {
                misjudged++;
                detail.add(v.questionNo() + " → " + pred);
            }
        }

        out.put("分母_标注为兜底的题", total);
        out.put("★被判成业务意图的题", misjudged);
        out.put("★兜底误判率", total == 0 ? null : (double) misjudged / total);
        out.put("明细", detail);
        out.put("可信", total >= MIN_SLICE_N);
        out.put("note", "★ 分母用【题的众数分类结果】，不用逐行 —— "
                + "这个指标问的是「这类题系统整体判得对不对」，"
                + "而逐行版本会被重复次数稀释（同一道题投 3 次票，权重变成 3 倍）。"
                + (total < MIN_SLICE_N
                        ? " ⚠️ n=" + total + " < " + MIN_SLICE_N + "，【不作为结论】。" : ""));
        return out;
    }

    // ================================================================
    // 检索指标
    // ================================================================

    private static Map<String, Object> retrievalSection(ObjectMapper mapper,
                                                        List<QuestionView> views) {
        Map<String, Object> out = new LinkedHashMap<>();

        int firstTry = 0, laterTry = 0, never = 0;
        List<String> neverQuestions = new ArrayList<>();
        List<String> changedAcrossRepeats = new ArrayList<>();
        List<String> goldChunksGone = new ArrayList<>();

        long hit5 = 0, hit3 = 0, hit1 = 0;
        double recall5Sum = 0, mrr5Sum = 0;
        long measured = 0;

        for (QuestionView v : views) {
            if (v.bank() == null) {
                continue;
            }
            // ★ 排除「声明不检索」的题 —— 它们的 gold 是空集，算进去就是把
            //   平均机械地拉低十几个点（这是阶段 4 脚本的一个已知缺陷）
            if (Boolean.TRUE.equals(v.bank().getExpectNoRetrieval())) {
                continue;
            }

            // ★ 取「第一次 status=1 的那次」
            QaLog row = v.firstSuccess();
            if (row == null) {
                never++;
                neverQuestions.add(v.questionNo());
                continue;
            }
            // 「第几次才成功」—— 用来让「闸门随机挡掉题目」这件事可见
            int ordinal = v.successOrdinal();
            if (ordinal == 1) {
                firstTry++;
            } else {
                laterTry++;
            }

            // ★ 抖动：同一题多次成功的行之间，送进 prompt 的序列有没有变。
            //   检索本身是确定性的（召回的 ID 序列稳定），所以这里变了
            //   通常意味着 docTypes 或分类结果在重复之间变了
            if (v.distinctFinalTopKAcrossSuccesses() > 1) {
                changedAcrossRepeats.add(v.questionNo());
            }

            Set<Long> gold = v.goldChunkIds();
            if (gold.isEmpty()) {
                goldChunksGone.add(v.questionNo());
                continue;
            }

            List<Long> ctx = finalTopK(mapper, row);
            measured++;

            if (contains(ctx, gold, 1)) {
                hit1++;
            }
            if (contains(ctx, gold, 3)) {
                hit3++;
            }
            if (contains(ctx, gold, 5)) {
                hit5++;
            }
            long covered = ctx.stream().limit(5).filter(gold::contains).count();
            recall5Sum += (double) covered / gold.size();
            mrr5Sum += reciprocalRank(ctx, gold, 5);
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("★分母_测到的题数", measured);
        counts.put("空gold题（被排除）", goldChunksGone.size());
        counts.put("  其中 正解切片已不在库里的题号", goldChunksGone);

        out.put("分母", counts);
        out.put("HitRate@1", ratio(hit1, measured));
        out.put("HitRate@3", ratio(hit3, measured));
        out.put("HitRate@5", ratio(hit5, measured));
        out.put("Recall@5", mean(recall5Sum, measured));
        out.put("MRR@5", mean(mrr5Sum, measured));

        out.put("★取到了第几次成功", Map.of(
                "第一次就成功", (long) firstTry,
                "第二或第三次才成功", (long) laterTry,
                "★三次全被挡（不进任何检索分母）", (long) never,
                "三次全被挡的题号", neverQuestions,
                "说明", "★★ 取「第一次 status=1 的那次」，不是「第 1 次」——"
                        + "一道本该放行的题第一次恰好被闸门挡掉时，直接丢弃会让检索指标"
                        + "【随机丢样本】；而「被挡了几次」由左边这一栏单独交代。"));

        out.put("★跨重复送进prompt的序列变了的题", Map.of(
                "n", (long) changedAcrossRepeats.size(),
                "题号", changedAcrossRepeats,
                "说明", "★ 同一道题、同一份配置，重复跑几次，final_top_k 的 ID 序列不一样。"
                        + "检索本身是确定的（实测只有 3e-4 的分值漂移、ID 序列稳定），"
                        + "所以这里变了通常意味着【分类结果的 docTypes 在重复之间变了】，"
                        + "于是过滤范围跟着变。★ 它是端到端链路上才看得到的抖动，"
                        + "意图探针测不出来。"));

        out.put("★命中集合（洞 1）", Map.of(
                "说明", "★★ 上面那几个标量【不足以宣称变好】。HitRate 打在 gold 的并集上："
                        + "换一个同样合法的 gold 命中、或者丢了带数字的那片而留下同主题的泛泛切片，"
                        + "都是 ok/ok。所以跨配置对比时必须看【命中的是哪些 gold】——"
                        + "本端点把逐题命中的集合放在 归因 一节里，"
                        + "A/B 的翻转矩阵（scripts/eval_ab.py）用那个。"));
        return out;
    }

    // ================================================================
    // 未命中归因
    // ================================================================

    /**
     * 每一道没答对的题<b>死在哪一步</b>。{@code docs/06} §1.3 那一列。
     *
     * <h3>★★ 三个桶的口径（2026-09-21 拍板：保名字，把口径写死在报告里）</h3>
     *
     * <p>名字沿用 {@code docs/06} §1.3 和阶段 4 的 {@code eval_baseline.py}
     * （改名会让两个阶段的数据不可比），但<b>每个桶实际是什么必须写在旁边</b>：
     *
     * <table border="1">
     *   <caption>三个桶的真实口径（2026-09-21 实测）</caption>
     *   <tr><th>桶</th><th>实际含义</th><th>⚠️ 不说会被误读成</th></tr>
     *   <tr><td>{@code not_recalled}</td><td>两路原始输出里都没有（记录完整时可信）</td>
     *       <td>——</td></tr>
     *   <tr><td>{@code fusion_dropped}</td>
     *       <td><b>结构性为空</b>：{@code RrfFuser.fuse()} 返回的是两路的<b>完整并集</b>，
     *           一条都不丢</td>
     *       <td>「RRF 参数没问题」—— 而它其实<b>不可能是别的值</b></td></tr>
     *   <tr><td>{@code rerank_dropped}</td>
     *       <td><b>含截断</b>：{@code rerank.top-n} 留空 → 回落成 {@code final-top-k=5}，
     *           实测 {@code reranked} 与 {@code final_top_k} 的 ID 序列<b>逐行相同（582/582）</b>。
     *           所以「重排」和「截断」在当前配置下是<b>同一个动作</b></td>
     *       <td>「重排模型把好结果排下去了」—— 而可能只是它排在第 6 名</td></tr>
     * </table>
     *
     * <p>★ 新增两个桶：
     * <ul>
     *   <li>{@code filtered_out} —— 范围过滤把该题的正解<b>整体</b>挡在池子外。
     *       它是<b>因</b>，不是果：分类错了才会选错范围，所以判定要排在
     *       {@code not_recalled} 之前，否则它会被算成「检索没找到」</li>
     *   <li>{@code beyond_record_cutoff} —— {@code fused} 的记录被截断过，
     *       且正解在记录里找不到。<b>这时「融合把它丢了」和「它排在第 25 名」
     *       分不开</b>，所以既不判 {@code not_recalled} 也不判 {@code fusion_dropped}，
     *       交给它自己一个「不知道」。见 {@code RetrievalDetailBuilder} 的 {@code sizes}</li>
     * </ul>
     */
    private static Map<String, Object> attributionSection(ObjectMapper mapper,
                                                          List<QuestionView> views) {
        Map<String, Object> out = new LinkedHashMap<>();

        // ★★ 先把所有桶建成空列表 —— 和 retrieval_detail「恒定存在的 key 永远出现，
        //    哪怕值是空数组」是同一条规矩。
        //
        //    不这么做的话，「某个桶是 0」和「这个桶在代码里没了」在报告里
        //    【长得一模一样】：都是这个键查不到。而前者是一个结论
        //    （比如 fusion_dropped 结构性为 0），后者是一次静默的回归。
        //    读报告的人没有第三个信息源可以区分它们。
        Map<String, List<String>> buckets = new TreeMap<>();
        for (String name : List.of("ok", "filtered_out", "not_recalled",
                "beyond_record_cutoff", "fusion_dropped", "rerank_dropped", "gold_missing")) {
            buckets.put(name, new ArrayList<>());
        }
        Map<String, String> goldHits = new TreeMap<>();
        long partiallyFiltered = 0;
        long goldMissing = 0;
        long withSizes = 0;
        long withoutSizes = 0;
        int measured = 0;

        for (QuestionView v : views) {
            if (v.bank() == null || Boolean.TRUE.equals(v.bank().getExpectNoRetrieval())) {
                continue;
            }
            QaLog row = v.firstSuccess();
            if (row == null) {
                continue;
            }
            Set<Long> gold = v.goldChunkIds();
            if (gold.isEmpty()) {
                continue;
            }
            JsonNode d = detail(mapper, row);
            if (d == null) {
                continue;
            }
            measured++;

            List<Long> ctx = finalTopK(mapper, row);

            if (d.has("sizes")) {
                withSizes++;
            } else {
                withoutSizes++;
            }

            // ★★ 这两个统【不挂在成败上】，它们是独立的信号：
            //
            //   gold_missing     — 正解切片已经被软删/重灌掉了。这时任何归因
            //                      都是把「数据没了」读成「检索偏了」
            //   部分被过滤        — 过滤挡住了这道题的一部分正解。
            //                      ★ 它量的是「过滤有多激进」，而一道题
            //                      【即使正解被挡了一半仍然可能答对】。
            //                      第一版把它写在「失败」分支里，于是它变成了
            //                      「失败的题里有多少被挡了一半」—— 那是另一个问题，
            //                      而且读者没法从报告里知道这一点。
            Set<Integer> goldTypes = v.goldDocTypes();
            if (goldTypes.isEmpty()) {
                goldMissing++;
            } else if (filterVerdict(d, goldTypes) == FilterVerdict.PARTIALLY_FILTERED) {
                partiallyFiltered++;
            }

            // ★ 桶的判定本身抽在 QuestionView.attributionBucket() 里 ——
            //   逐题明细段用的是【同一个方法】，所以「汇总说 ok 有 126 道」
            //   和「逐题表里数出来 126 道」不可能对不上。
            //   本段上面那四个 continue 正好是它的四个前提。
            String bucket = v.attributionBucket();
            if (bucket == null) {
                // 到不了这里。留着是因为「两处前提不一致」正是本类最怕的
                // 那类漂移：真发生时宁可不计这一道，也不要 NPE 掉整个报告
                continue;
            }

            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(v.questionNo());
            // ★ 洞 1：记【命中的是哪些 gold】，不只记标量
            Set<Long> hit = new TreeSet<>(gold);
            hit.retainAll(ctx);
            goldHits.put(v.questionNo(), hit.toString() + "/" + gold);
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("★分母", (long) measured);
        for (Map.Entry<String, List<String>> e : buckets.entrySet()) {
            counts.put(e.getKey(), (long) e.getValue().size());
        }
        out.put("桶", counts);
        out.put("逐题", buckets);
        out.put("逐题命中集合", goldHits);
        out.put("部分被过滤的题", partiallyFiltered);
        out.put("正解切片已不在库里的题", goldMissing);
        out.put("★截断可判定性", Map.of(
                "有sizes段的行", withSizes,
                "无sizes段的行", withoutSizes,
                "说明", "★★ sizes 段是 2026-09-21 才加进 retrieval_detail 的。"
                        + "【没有它 = 无法判断那一段有没有被记录截断】—— 于是那一行里"
                        + "「融合丢了正解」是证不了的，判决会落到 beyond_record_cutoff 而不是 "
                        + "fusion_dropped。★ 无sizes段的行数 > 0 时，"
                        + "fusion_dropped 这个桶的 0 【不构成「RRF 没丢东西」的证据】，"
                        + "它只是「没有证据说它丢了」。"));
        out.put("说明_桶的命名", "桶名沿用 docs/06 §1.3 与阶段 4 的 eval_baseline.py，"
                + "所以两个阶段的数据可比。每个桶【实际是什么】写在 ★口径 里 —— "
                + "特别是 fusion_dropped 结构性为空、rerank_dropped 含截断这两条，"
                + "不写就会被读成「RRF 没问题」和「重排模型不行」。");
        out.put("★口径", Map.of(
                "not_recalled", "两路原始输出里都没有（记录完好时可信）",
                "fusion_dropped", "★★ 结构性为空。RrfFuser.fuse() 返回两路的【完整并集】，"
                        + "一条候选都不丢 —— 所以这个桶不可能是 0 以外的值，"
                        + "而它显示 0 【不构成「RRF 参数没问题」的证据】。"
                        + "阶段 4 的 docs/06 §1.3 把它写成「融合后掉出前 20」，"
                        + "那句里的「前 20」其实是【记录的截断】，不是融合的行为。",
                "rerank_dropped", "★★ 含截断。【重排】和【截断到 final-top-k】在当前配置下是"
                        + "同一个动作（rerank.top-n 留空 → 回落成 final-top-k）。"
                        + "实测 reranked 与 final_top_k 的 ID 序列逐行相同（582/582），"
                        + "所以这个桶说的是「融合之后、送进 prompt 之前丢的」，"
                        + "不能单独归因给重排模型。",
                "filtered_out", "★ 新增。范围过滤把该题的正解【整体】挡在池子外 —— "
                        + "检索压根没机会找到它。判定排在 not_recalled 之前，因为它是【因】："
                        + "分类错了才会选错范围。部分被过滤的题不判它（见「部分被过滤的题」那一栏），"
                        + "因为剩下的正解仍可能被找到。",
                "beyond_record_cutoff", "★ 新增。fused 的记录被截断过，且正解不在记录里 —— "
                        + "「融合丢了它」和「它排在第 25 名」分不开。"
                        + "★ 旧数据没有 sizes 段时，也无法排除这种可能。",
                "gold_missing", "★ 新增。正解切片已经不在 kb_chunk 里了（被软删/重灌）。"
                        + "这时任何归因都是把【数据没了】读成【检索偏了】。"));
        return out;
    }

    private static String attributeRest(Set<Long> gold, Set<Long> legs, Set<Long> fused,
                                        List<Long> ctx, JsonNode d) {
        if (!intersects(gold, legs)) {
            return "not_recalled";
        }
        if (!intersects(gold, fused)) {
            // ★★ 判据是【反过来的】：只有能【证明】记录是完整的（FALSE），
            //    才敢说「融合把它丢了」。截断过（TRUE）或无从判断（null，
            //    即 2026-09-21 之前落的行没有 sizes 段）都只能说「不知道」。
            //
            //    为什么反过来：`fusion_dropped` 是一个【指控】——
            //    它说「RRF 把一条已经召回的切片丢了」，读完的结论是「去调 RRF 参数」。
            //    而证据不足时做出指控，代价是让人去改一个没坏的东西。
            //    反过来（把真的丢的说成「不知道」）最坏只是漏掉一个线索。
            //    两个方向的代价不对称，所以往保守那边靠。
            Boolean truncated = truncated(d, "fused");
            return Boolean.FALSE.equals(truncated) ? "fusion_dropped" : "beyond_record_cutoff";
        }
        if (!intersects(gold, new TreeSet<>(ctx))) {
            return "rerank_dropped";
        }
        return "ok";
    }

    /** 范围过滤对「这道题的正解」做了什么 */
    private enum FilterVerdict {
        /** 过滤没下推，或者正解的类型全在声明范围内 —— 没挡住它 */
        NOT_FILTERED,
        /** 该题正解的 doc_type【全部】落在过滤范围之外 —— 检索压根没机会 */
        FULLY_FILTERED,
        /** 部分正解被挡住，部分没有 —— 不判它，但必须数出来 */
        PARTIALLY_FILTERED
    }

    /**
     * 只看一件事：<b>实际下推的那个过滤条件，有没有挡住这道题的正解</b>。
     *
     * <p>用的是正解切片<b>自己的</b> {@code doc_type}，不是意图树里声明的那个集合 ——
     * 声明是<b>标注</b>，而这里要判的是<b>事实</b>：那次 SQL 的
     * {@code WHERE doc_type IN (...)} 有没有把它排除掉。
     *
     * @param goldTypes 正解切片实际落在哪些 doc_type。<b>调用方必须先确认它非空</b>
     *                  —— 空集意味着正解切片已经不在库里了，那是另一件事
     *                  （见调用处那个 {@code goldGone} 判定）
     */
    private static FilterVerdict filterVerdict(JsonNode d, Set<Integer> goldTypes) {
        JsonNode filter = d.get("filter");
        if (filter == null || !filter.path("applied").asBoolean(false)) {
            return FilterVerdict.NOT_FILTERED;
        }
        Set<Integer> allowed = new TreeSet<>();
        for (JsonNode t : filter.path("doc_types")) {
            allowed.add(t.asInt());
        }
        if (allowed.isEmpty()) {
            // 空集在 RetrievalOptions 里表示「不限制」，不是「什么都不匹配」
            // （ADR-044）—— 所以它不是一次过滤
            return FilterVerdict.NOT_FILTERED;
        }
        long inside = goldTypes.stream().filter(allowed::contains).count();
        if (inside == 0) {
            return FilterVerdict.FULLY_FILTERED;
        }
        return inside == goldTypes.size() ? FilterVerdict.NOT_FILTERED
                : FilterVerdict.PARTIALLY_FILTERED;
    }

    // ================================================================
    // 过度检索率
    // ================================================================

    /**
     * 「最终送进 prompt 的上下文里，有多少是不该在那儿的」。
     *
     * <p>★ 口径沿用 {@code docs/05} §9.4 已有的实测写法：<b>越界条数 / 上下文总条数</b>
     * （那次是「20 题 × 5 条 = 100 条里 4 条越界」）。分成两个口径是因为
     * 「期望集合」有两个来源，而它们的差值本身携带信息：
     *
     * <pre>
     *   对标注叶子 → 这道题【本该】检索的范围有多干净   （系统的检索偏不偏）
     *   对分类叶子 → 实际下推的那个范围有没有守住      （过滤生效了没有）
     * </pre>
     *
     * <p>两者的差值近似就是「分类错了导致的越界」。合成一个数就把它丢了。
     *
     * <p>⚠️ 期望集合为<b>空集</b>时（分类到了 TOOL/NONE 的顶层码，或者意图树里
     * 根本没有那个码），按定义「所有上下文都越界」→ 100%。
     * 这个数是对的但含义特殊（那次检索本身就不该发生），所以单独数出来。
     */
    private static Map<String, Object> overRetrievalSection(IntentTree.Tree tree, ObjectMapper mapper,
                                                            Inputs in, List<QuestionView> views) {
        Map<String, Object> out = new LinkedHashMap<>();

        long oobA = 0, totA = 0;
        long oobB = 0, totB = 0;
        long emptyExpectedA = 0, emptyExpectedB = 0;
        long unknownChunks = 0;
        Map<String, String> perQuestion = new TreeMap<>();

        for (QuestionView v : views) {
            // ★ 分母规则和 检索 / 归因 / 逐题明细 共用一处（见 inRetrievalDenominator）。
            //   ★★ 本段原先【漏了「正解切片非空」那个条件】—— 当前数据里
            //   这类题是 0 道，所以数字没变、也没人发现；但它是第四份手写的
            //   分母规则，而四份里有一份不同就是「两个分母不一样」的开始。
            if (!v.inRetrievalDenominator()) {
                continue;
            }
            QaLog row = v.firstSuccess();
            JsonNode d = detail(mapper, row);
            if (d == null) {
                continue;
            }
            Set<Integer> expA = tree.docTypesOf(v.goldIntent());
            Set<Integer> expB = v.intentMode() == null
                    ? Set.of() : tree.docTypesOf(v.intentMode());
            if (expA.isEmpty()) {
                emptyExpectedA++;
            }
            if (expB.isEmpty()) {
                emptyExpectedB++;
            }

            long qOobA = 0, qTot = 0;
            for (Long id : finalTopK(mapper, row)) {
                Integer dt = in.chunkDocTypes().get(id);
                if (dt == null) {
                    unknownChunks++;
                    continue;
                }
                qTot++;
                if (!expA.contains(dt)) {
                    qOobA++;
                }
                if (!expB.contains(dt)) {
                    oobB++;
                }
            }
            oobA += qOobA;
            totA += qTot;
            totB += qTot;
            perQuestion.put(v.questionNo(), qOobA + "/" + qTot + " 越界（对标注叶子）");
        }

        out.put("★对标注叶子", sliceMap(oobA, totA));
        out.put("★对分类叶子", sliceMap(oobB, totB));
        out.put("逐题（对标注叶子）", perQuestion);
        out.put("期望集合为空的题_标注口径", emptyExpectedA);
        out.put("期望集合为空的题_分类口径", emptyExpectedB);
        out.put("★切片已不在库里的条目数", unknownChunks);
        out.put("note", "微平均（总越界条数 / 总条数），口径沿用 docs/05 §9.4 的实测写法。"
                + "★ 期望集合为空 = 那次检索按定义全部越界（100%）—— "
                + "分类口径下它通常意味着【分类到了 TOOL/NONE】，也就是这次检索本就不该发生。"
                + "★ 分类口径在过滤真正下推时几乎恒为 0（下推的判据和期望是同一个集合），"
                + "所以它真正度量的是【过滤没能下推的那些行】—— 见 归因 一节里的 filtered_out。");
        return out;
    }

    // ================================================================
    // 延迟
    // ================================================================

    /**
     * 五列延迟 + 分位数。
     *
     * <h3>★★ 这五列<b>不相加等于 total</b>，而且这一点必须写在数据里</h3>
     *
     * <p>两个原因，第二个是本项目特有的：
     *
     * <ol>
     *   <li>{@code rerank ⊂ retrieval} —— 重排跑在检索链路内部，
     *       {@code retrieval_latency_ms} 已经把重排那一段算进去了。
     *       拆开是为了回答「P95 变慢了，慢在召回还是慢在重排」</li>
     *   <li>★ <b>意图分类那一次模型往返不在任何一列里</b>。
     *       它是一次 0.5~2.5 秒的完整模型调用（{@code ChatServiceImpl} 有实测），
     *       进了 {@code total} 却既不是 retrieval 也不是 llm。
     *       <b>它是本项目最大的一块未拆分耗时</b></li>
     * </ol>
     *
     * <p>所以本类报第五列 {@code 未归类 = total − queue − retrieval − llm}，
     * 并把它命名为「含意图分类/会话记忆」。★ 报负值时说明数据有问题
     * （某一段记重了），所以同时报最小值。
     *
     * <p>样本是<b>全部 {@code status=1} 的行</b>，不是每题一次 ——
     * 分位数要的是样本量。被排除的 {@code status≠1} 行单独数出来，
     * 因为它们<b>恰恰是最慢的那些</b>（被闸门挡掉前已经跑完了分类），
     * 排除它们会让 P95 偏低。
     */
    private static Map<String, Object> latencySection(List<QaLog> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<QaLog> ok = rows.stream()
                .filter(r -> r.getStatus() != null && r.getStatus() == QaLog.STATUS_SUCCESS)
                .toList();

        out.put("样本_全部行", (long) rows.size());
        out.put("样本_status=1的行", (long) ok.size());
        out.put("★被status≠1截掉的条数", (long) (rows.size() - ok.size()));

        out.put("queue_ms", stats(values(ok, QaLog::getQueueMs), "★ NULL = 没排队（常态），不是 0"));
        out.put("retrieval_latency_ms", stats(values(ok, QaLog::getRetrievalLatencyMs),
                "★ 这里面【含】重排"));
        out.put("rerank_latency_ms", stats(values(ok, QaLog::getRerankLatencyMs),
                "★★ ⊂ retrieval_latency_ms，是子项不是并列项"));
        out.put("llm_latency_ms", stats(values(ok, QaLog::getLlmLatencyMs), "生成那一跳"));
        out.put("total_latency_ms", stats(values(ok, QaLog::getTotalLatencyMs), ""));

        // 未归类 = total - queue - retrieval - llm（四段都齐的行才算）
        List<Integer> unaccounted = new ArrayList<>();
        for (QaLog r : ok) {
            if (r.getTotalLatencyMs() == null || r.getRetrievalLatencyMs() == null
                    || r.getLlmLatencyMs() == null) {
                continue;
            }
            int q = r.getQueueMs() == null ? 0 : r.getQueueMs();
            unaccounted.add(r.getTotalLatencyMs() - q
                    - r.getRetrievalLatencyMs() - r.getLlmLatencyMs());
        }
        out.put("★未归类_ms", stats(unaccounted,
                "★★ = total − queue − retrieval − llm，含【意图分类那一次模型往返】和会话记忆"));
        out.put("note", "★★ 五列【不相加等于 total】：rerank ⊂ retrieval，"
                + "而且意图分类的耗时在【任何一列里都没有】。"
                + "「四段相加 = total」这句话在这个项目里是错的，"
                + "写成那样会让「少掉的那 1~2 秒」永远没人去查。");
        return out;
    }

    private static List<Integer> values(List<QaLog> rows,
                                        java.util.function.Function<QaLog, Integer> getter) {
        List<Integer> out = new ArrayList<>();
        for (QaLog r : rows) {
            Integer v = getter.apply(r);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private static Map<String, Object> stats(List<Integer> values, String note) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("n", (long) values.size());
        out.put("p50", percentile(values, 0.50));
        out.put("p95", percentile(values, 0.95));
        out.put("min", values.isEmpty() ? null : Collections.min(values));
        out.put("max", values.isEmpty() ? null : Collections.max(values));
        if (!note.isEmpty()) {
            out.put("口径", note);
        }
        return out;
    }

    /**
     * 最近秩分位数：升序排序后取第 {@code ceil(p × n)} 个，<b>不插值</b>。
     *
     * <p>★ 选它是因为它永远返回一个<b>真的被观测到</b>的值。插值（比如线性）
     * 会算出一个任何一次请求都没达到过的数，而报告里那个数会被人当成
     * 「用户的实际体验」。对 P95 这种「最慢的那一小撮有多慢」的问题，
     * 一个从未发生过的值是没有意义的。
     *
     * <p>★ Python 侧对拍用同一套：{@code sorted_vals[min(n-1, ceil(p*n)-1)]}。
     * 两边算得不一样的话，「对拍通过」就成了假绿。
     */
    static Integer percentile(List<Integer> values, double p) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p * sorted.size());
        int index = Math.min(sorted.size() - 1, Math.max(0, rank - 1));
        return sorted.get(index);
    }

    // ================================================================
    // 成本
    // ================================================================

    private static Map<String, Object> costSection(List<QaLog> rows) {
        BigDecimal cost = BigDecimal.ZERO;
        long tokens = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        int rowsWithCost = 0;
        for (QaLog r : rows) {
            if (r.getCost() != null) {
                cost = cost.add(r.getCost());
                rowsWithCost++;
            }
            if (r.getTotalTokens() != null) {
                tokens += r.getTotalTokens();
            }
            if (r.getPromptTokens() != null) {
                promptTokens += r.getPromptTokens();
            }
            if (r.getCompletionTokens() != null) {
                completionTokens += r.getCompletionTokens();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("数据库里的成本合计", cost);
        out.put("token合计", tokens);
        // ★★ 拆分（T8 新增）。**只给一个 total 是没法审计的** ——
        //    T8 撞到的具体情形：关掉重排之后 token 只涨 1.6%，成本却涨 9.5%，
        //    而解释它的那个量（缓存命中的输入 token）**压根不落库**。
        //    有了这两行，命中数可以从「成本 + 三档定价」反推出来（三档定价在配置快照里）。
        //    ⚠️ 反推【假定整轮只用一个模型】；有降级行时它不成立，
        //      所以要连同 provider/model 切片一起读。
        out.put("prompt_token合计", promptTokens);
        out.put("输出token合计", completionTokens);
        out.put("有成本的行", rowsWithCost);
        out.put("note", "★ 这是【服务端账本】。跑题器自己clientCost 是另一份，两个数应当接近 —— "
                + "差得远说明有行没落库（那正是对账要抓的）。"
                + "⚠️ 它不含【意图分类】和【摘要压缩】的花费："
                + "前者不进 qa_log 的任何一列，后者是显式例外（见 CLAUDE.md）。"
                + "所以真实花费【高于】这个数。"
                + "★★ 缓存命中的输入 token（`prompt_cache_hit_tokens`）**参与计费但不落库**："
                + "ModelCostCalculator 用它算钱，算完就丢掉。"
                + "所以「成本为什么变了」只能从【成本 + token 拆分 + 三档定价】反推 ——"
                + "上面那两行就是为这件事加的。");
        return out;
    }

    // ================================================================
    // 逐题明细（阶段 7 · T6 新增）
    // ================================================================

    /**
     * <b>一道题一行</b>的全部逐题判据 —— 排在报告最后，因为它是原始材料，
     * 上面那些段都是从它汇总出来的。
     *
     * <h3>★ 它服务的是 A/B 的翻转矩阵，所以「可比性」必须写在数据里</h3>
     *
     * <p>两份 run 的逐题表按 {@code 题号} 对齐，逐格比较，翻了的记一笔。
     * 但有很多格<b>本来就不可比</b> —— 那 28 道声明不检索的题没有「命中@5」，
     * 15 道工具题的「意图正确」恒不可判。所以每一行自己带着
     * {@code 进检索分母} / {@code gold是合法分类目标} 两个旗子，
     * 两格判据该是 {@code null} 时就是 {@code null}。
     *
     * <p>★★ {@code null} 和 {@code false} 在这里的区别是<b>整个 A/B 的成败</b>：
     * 把不可判读成 false，那 15 道工具题在两轮里都是 false、一格都不翻，
     * 矩阵看起来完全正常 —— 而「可比口径 93% vs 全体 84%」这 9.4 个百分点
     * 就这样被摊平进了噪声里。
     *
     * <h3>★ 为什么不让 Python 脚本自己算</h3>
     *
     * <p>因为指标算法只能有一个出处。脚本自己从 {@code qa_log} 算的话，
     * 「取第一次 status=1」「众数投票」「归因桶」「分母规则」这四件事
     * 会在 Java 和 Python 里各存一份 —— 而它们的漂移是静默的：
     * 两边各自都自洽，只是得出的数不一样。
     * 阶段 7 已经为这件事写过一次对拍脚本（{@code eval_report_check.py}），
     * 那是给「独立复算」用的，不是给「日常取数」用的。
     */
    private static Map<String, Object> perQuestionSection(List<QuestionView> views,
                                                          IntentTree.Tree tree) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>(views.size());
        for (QuestionView v : views) {
            rows.add(v.verdictRow(tree));
        }
        out.put("行", rows);
        out.put("★三态字段", Map.of(
                "意图正确", "null = 这道题的 gold 不是合法分类目标（工具叶子码），"
                        + "这一格【不可判】，不是「答错了」。计入可比口径时分母也要一起摘掉。",
                "命中@5", "null = 这道题不进检索分母（声明不检索 / 三次全被挡 / "
                        + "正解切片已不在库里 / retrieval_detail 缺失）。"
                        + "★ 同理，它不是「没命中」—— 后者是 false。"));
        out.put("★怎么用", "两份 run 的「行」按题号对齐，逐格比。"
                + "两格判据有第三态，比较前必须先把两侧的 null 都摘掉，"
                + "再用【剩下的共同可比集】当分母 —— "
                + "而不是用题库总题数。后者会让「这一轮多了几道不可判的题」"
                + "看起来像「准确率掉了」。");
        out.put("note", "★ 这份表是【汇总各段的原始材料】，不是另一套算法："
                + "「归因」「命中@5」「进检索分母」都由 QuestionView 上"
                + "那几个和汇总段共用的方法产出。单元测试断言"
                + "「把本表重新汇总起来 == 上面各段的数字」。");
        return out;
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 比率 + 分母，一起进报告。★ 只报百分比是这份报告不允许的 */
    private static Map<String, Object> ratio(long numerator, long denominator) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("n", denominator);
        out.put("命中", numerator);
        out.put("值", denominator == 0 ? null : (double) numerator / denominator);
        out.put("可信", denominator >= MIN_SLICE_N);
        if (denominator < MIN_SLICE_N) {
            out.put("★", "n=" + denominator + " < " + MIN_SLICE_N + " —— 【不作为结论】");
        }
        return out;
    }

    private static Map<String, Object> mean(double sum, long n) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("n", n);
        out.put("值", n == 0 ? null : sum / n);
        out.put("可信", n >= MIN_SLICE_N);
        return out;
    }

    private static Map<String, Object> sliceMap(long numerator, long denominator) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("越界条数", numerator);
        out.put("上下文条数", denominator);
        out.put("值", denominator == 0 ? null : (double) numerator / denominator);
        out.put("可信", denominator >= MIN_SLICE_N);
        return out;
    }

    /** 按某个键切片，每个切片都印 n 和可信度 */
    private static Map<String, Object> byKey(List<QuestionView> views,
                                             java.util.function.Function<QuestionView, String> key,
                                             java.util.function.Predicate<QuestionView> correct) {
        Map<String, long[]> acc = new TreeMap<>();
        for (QuestionView v : views) {
            String k = key.apply(v);
            long[] cell = acc.computeIfAbsent(k == null ? "(未知)" : k, x -> new long[2]);
            cell[1]++;
            if (correct.test(v)) {
                cell[0]++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : acc.entrySet()) {
            out.put(e.getKey(), ratio(e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    /** 解析一行 {@code retrieval_detail}。空行返回 null（「没有发生」的诚实表达） */
    private static JsonNode detail(ObjectMapper mapper, QaLog row) {
        String raw = row.getRetrievalDetail();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<Long> ids(JsonNode array) {
        Set<Long> out = new TreeSet<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                out.add(node.path("chunk_id").asLong());
            }
        }
        return out;
    }

    /**
     * 把 {@code doc_type} 集合渲染成<b>升序</b>字符串，给人读、给 diff 用。
     *
     * <p>★ 排序不是美化。见 {@link #scopeSection} 里那段注释：这个字符串会进
     * {@code docs/11} 并被跨轮 diff，而它原先的顺序由 {@code Set} 的迭代顺序决定
     * —— 那是 JDK 的内部实现，不是任何我们承诺过的东西。
     */
    private static String sorted(Set<Integer> values) {
        return new TreeSet<>(values).toString();
    }

    private static Set<Long> union(Set<Long> a, Set<Long> b) {
        Set<Long> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }

    private static List<Long> finalTopK(ObjectMapper mapper, QaLog row) {
        JsonNode d = detail(mapper, row);
        return finalTopK(d);
    }

    /** ★ {@code final_top_k} 是【数字数组】不是对象数组 —— 和那五段的形状不同 */
    private static List<Long> finalTopK(JsonNode d) {
        List<Long> out = new ArrayList<>();
        if (d == null) {
            return out;
        }
        JsonNode arr = d.get("final_top_k");
        if (arr != null && arr.isArray()) {
            for (JsonNode node : arr) {
                out.add(node.asLong());
            }
        }
        return out;
    }

    private static boolean contains(List<Long> ctx, Set<Long> gold, int k) {
        int limit = Math.min(k, ctx.size());
        for (int i = 0; i < limit; i++) {
            if (gold.contains(ctx.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static double reciprocalRank(List<Long> ctx, Set<Long> gold, int k) {
        int limit = Math.min(k, ctx.size());
        for (int i = 0; i < limit; i++) {
            if (gold.contains(ctx.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private static boolean intersects(Set<Long> a, Set<Long> b) {
        for (Long v : a) {
            if (b.contains(v)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某一段的记录是不是被截断过。
     *
     * <p>★★ <b>三态</b>：{@code TRUE} / {@code FALSE} / {@code null}。
     * 第三个不是「不知道就算了」，是<b>「这段数据没有资格支撑结论」</b>：
     * 阶段 7 之前落库的行里没有 {@code sizes} 段，把那种行当成
     * {@code FALSE}（没截断），就是拿一个没测过的假设去支撑归因。
     *
     * <p>判据：这一段记录的条数 == {@code sizes} 里记的<b>真实</b>条数。
     * 后者更大就说明有内容没写进去。
     */
    private static Boolean truncated(JsonNode d, String section) {
        JsonNode sizes = d.get("sizes");
        if (sizes == null || sizes.isNull()) {
            return null;   // 旧数据：无从判断
        }
        JsonNode real = sizes.get(section);
        if (real == null || real.isNull()) {
            return null;
        }
        return real.asInt() > d.path(section).size();
    }

    // ================================================================
    // 每题一行的视图
    // ================================================================

    /**
     * 一道题的<b>全部重复</b>聚在一起，派生出的那几个量只算一次。
     *
     * <p>★ 把它们写在这里而不是散在各节里，是为了保证「众数」只有一处实现 ——
     * 意图准确率、检索范围、兜底误判、过度检索的分类口径<b>全都要用它</b>。
     * 四处各算一遍的话，改了其中一处就会让报告里的数互相矛盾，
     * 而每一个单独看都是对的。
     *
     * <p>★★ <b>它是 {@code static} 的，两张对照表和 mapper 走构造器传进来。</b>
     * 这一点是刻意的：写成非静态内部类 + {@code static} 字段来「共享上下文」，
     * 代码会短几行，但 {@link #build} 就<b>不可重入</b>了 ——
     * 而不可重入的症状是「同时算两轮时数字串了」，<b>不报错、不复现、
     * 只在并发时出现</b>。这是本项目最不想留下的那一类东西，宁可多传两个参数。
     */
    private static final class QuestionView {

        private final String questionNo;
        private final EvalQuestion bank;
        private final List<QaLog> rows;
        private final Map<Long, Integer> chunkDocTypes;
        private final ObjectMapper mapper;

        QuestionView(String questionNo, EvalQuestion bank, List<QaLog> rows,
                     Map<Long, Integer> chunkDocTypes, ObjectMapper mapper) {
            this.questionNo = questionNo;
            this.bank = bank;
            this.rows = rows;
            this.chunkDocTypes = chunkDocTypes;
            this.mapper = mapper;
        }

        String questionNo() {
            return questionNo;
        }

        EvalQuestion bank() {
            return bank;
        }

        List<QaLog> rows() {
            return rows;
        }

        /** 人工标注的意图叶子码。题库里没有这道题时为 null */
        String goldIntent() {
            return bank == null ? null : bank.getIntent();
        }

        /** 正解切片 ID。没有题库 / 没标锚点时为<b>空集</b>（不是 null） */
        Set<Long> goldChunkIds() {
            Set<Long> out = new TreeSet<>();
            if (bank != null && bank.getExpectedChunkIds() != null) {
                out.addAll(List.of(bank.getExpectedChunkIds()));
            }
            return out;
        }

        /**
         * 正解切片<b>实际</b>落在哪些 {@code doc_type}。
         *
         * <p>★ 查的是 {@code kb_chunk} 的当前状态，<b>不是</b>意图树里声明的那个集合。
         * 两者本该一致（{@code IntentTreeConsistencyTest} 在守），
         * 但这里是「过滤有没有挡住它」的判据，必须看<b>实际</b>的那个 ——
         * 声明是意图，实际是事实。
         *
         * <p>⚠️ 切片已不在库里时它<b>缺席</b>，于是这里可能是空集 ——
         * 那和「这道题本来就没有正解类型」长得一样，
         * 所以调用方必须用 {@link #goldChunkIds()} 是否为空来区分，
         * 不能拿这个方法的返回值去判。
         */
        Set<Integer> goldDocTypes() {
            Set<Integer> out = new TreeSet<>();
            for (Long id : goldChunkIds()) {
                Integer t = chunkDocTypes.get(id);
                if (t != null) {
                    out.add(t);
                }
            }
            return out;
        }

        /** 按写入顺序第一次 {@code status=1} 的那一行。三次全被挡时为 null */
        QaLog firstSuccess() {
            for (QaLog row : rows) {
                if (row.getStatus() != null && row.getStatus() == QaLog.STATUS_SUCCESS) {
                    return row;
                }
            }
            return null;
        }

        /** 第一次成功是这道题的第几次（1-based）。一次都没成功过时为 0 */
        int successOrdinal() {
            int ordinal = 0;
            for (QaLog row : rows) {
                if (row.getStatus() != null && row.getStatus() == QaLog.STATUS_SUCCESS) {
                    return ordinal + 1;
                }
                ordinal++;
            }
            return 0;
        }

        /** 分类结果的众数。无唯一众数（平票）或一次都没分类成功时为 null */
        String intentMode() {
            Map<String, Integer> votes = new LinkedHashMap<>();
            for (QaLog row : rows) {
                if (row.getIntent() != null) {
                    votes.merge(row.getIntent(), 1, Integer::sum);
                }
            }
            return mode(votes);
        }

        /** 全部行都投了同一个意图。★ 「全票一致」不等于「正确」 */
        boolean unanimous() {
            Set<String> seen = new TreeSet<>();
            for (QaLog row : rows) {
                if (row.getIntent() != null) {
                    seen.add(row.getIntent());
                }
            }
            return seen.size() <= 1;
        }

        /** 反问与否的多数票。平票或没有行时为 null */
        Boolean statusMode() {
            int clarify = 0;
            int total = 0;
            for (QaLog row : rows) {
                if (row.getStatus() == null) {
                    continue;
                }
                total++;
                if (row.getStatus() == QaLog.STATUS_CLARIFY) {
                    clarify++;
                }
            }
            if (total == 0 || clarify * 2 == total) {
                return null;   // 没有行 / 平票 —— 都不是共识
            }
            return clarify * 2 > total;
        }

        boolean statusUnanimous() {
            Set<Integer> seen = new TreeSet<>();
            for (QaLog row : rows) {
                if (row.getStatus() != null) {
                    seen.add(row.getStatus());
                }
            }
            return seen.size() <= 1;
        }

        /** 所有成功行里出现过几种不同的 {@code final_top_k} 序列 */
        int distinctFinalTopKAcrossSuccesses() {
            Set<String> seen = new TreeSet<>();
            for (QaLog row : rows) {
                if (row.getStatus() != null && row.getStatus() == QaLog.STATUS_SUCCESS) {
                    seen.add(EvalReportService.finalTopK(mapper, row).toString());
                }
            }
            return seen.size();
        }

        // ============================================================
        // 逐题明细（阶段 7 · T6 新增）
        // ============================================================

        /**
         * 这道题进不进<b>检索类指标</b>的分母。
         *
         * <p>三个条件，缺一不可：
         * <ol>
         *   <li><b>题库里有这道题</b> —— 没有标注就没法判对错</li>
         *   <li><b>它声明了要检索</b> —— 工具/兜底/澄清那 28 道题的检索
         *       <b>本来就不该发生</b>，把它们算成「没召回到」是把平均
         *       机械地拉低十几个点（阶段 4 的那个已知缺陷）</li>
         *   <li><b>至少有一次真的成功了</b> —— 三次全被闸门挡掉的题，
         *       检索确实没发生。★ 它<b>不是</b>「检索失败了」，
         *       所以既不进分子也不进分母，而是出现在澄清边界的混淆矩阵里</li>
         * </ol>
         *
         * <p>★ 还有第四个条件：<b>正解切片 ID 非空</b>。
         * 锚点全空时 {@code Recall = 0/0} 不是一个数，而
         * 「正解切片已不在库里」比「检索没找到」严重得多 ——
         * 前者是数据问题、后者是检索问题，修法完全相反。
         *
         * <h3>★ 为什么抽成一个方法</h3>
         *
         * <p>在此之前这条规则<b>在四个地方各写了一遍</b>（检索、归因、过度检索、
         * 以及即将新增的逐题明细）。四份手写的同一个规则，加一个条件时
         * 只有三份会被改到 —— 而症状是「报告里两个分母不一样」，
         * 谁也不报错，读者只能自己数。
         */
        boolean inRetrievalDenominator() {
            if (bank == null) {
                return false;
            }
            if (Boolean.TRUE.equals(bank.getExpectNoRetrieval())) {
                return false;
            }
            if (firstSuccess() == null) {
                return false;
            }
            return !goldChunkIds().isEmpty();
        }

        /**
         * 这道题【死在哪一步】—— {@code docs/06} §1.3 的那一列，逐题版。
         *
         * <p>{@code null} 表示这道题压根不参与归因（见
         * {@link #inRetrievalDenominator()}，外加 {@code retrieval_detail} 缺失）。
         * ★ 和「桶是 {@code ok}」是两件事，别把 null 当 ok。
         *
         * <p>★ 抽成本方法是为了让「归因汇总」和「逐题明细」<b>用同一份判断</b>。
         * 两处各写一遍的后果不是编译错误，是报告里出现
         * 「汇总说 ok 有 126 道、逐题表里数出来 125 道」——
         * 两个数各自都对，合起来是一句谎话。
         */
        String attributionBucket() {
            QaLog row = firstSuccess();
            if (row == null) {
                return null;
            }
            Set<Long> gold = goldChunkIds();
            if (gold.isEmpty()) {
                return null;
            }
            JsonNode d = detail(mapper, row);
            if (d == null) {
                return null;
            }
            Set<Integer> goldTypes = goldDocTypes();
            if (goldTypes.isEmpty()) {
                // 正解切片已经不在 kb_chunk 里了 —— 这时任何归因都是把
                // 【数据没了】读成【检索偏了】
                return "gold_missing";
            }
            List<Long> ctx = finalTopK(mapper, row);
            if (contains(ctx, gold, Integer.MAX_VALUE)) {
                return "ok";
            }
            if (filterVerdict(d, goldTypes) == FilterVerdict.FULLY_FILTERED) {
                // ★ filtered_out 排在 not_recalled 之前 —— 它是因不是果：
                //   分类错了才会选错范围
                return "filtered_out";
            }
            return attributeRest(gold,
                    union(ids(d.get("vector_hits")), ids(d.get("keyword_hits"))),
                    ids(d.get("fused")), ctx, d);
        }

        /**
         * 这道题的<b>全部逐题判据</b>，一行 —— 给 A/B 的翻转矩阵用。
         *
         * <h3>★ 为什么要有这一行，而不是让脚本去拼</h3>
         *
         * <p>在这一行存在之前，逐题数据已经散在报告里的<b>四处、三种形状</b>：
         * 归因桶（桶 → 题号列表）、逐题命中集合（题号 → {@code "hit/gold"} 字符串）、
         * 闸门抖动的题号、跨重复序列变了的题号。每一处都是「异常清单」，
         * 而不是「一道题的全貌」。
         *
         * <p>拿异常清单反推一张表有两个后果：<b>正常的题只能靠「不在任何清单里」
         * 推断</b>（漏看一个清单，那道题就静默消失了），以及
         * <b>每加一个判据就要改一次脚本</b>。
         *
         * <h3>★★ 三态：{@code null} 【不是】{@code false}</h3>
         *
         * <p>有两格判据是<b>三态</b>的，必须能被区分 —— 否则 A/B 会读出一堆假翻转：
         *
         * <pre>
         *   意图正确 = null   ← gold 不是合法分类目标（三个工具叶子码），这一格【不可判】
         *   命中@5   = null   ← 这道题不进检索分母
         * </pre>
         *
         * <p>把 {@code null} 当成 {@code false} 的后果是具体的：
         * 那 15 道工具题的「意图正确」在<b>每一轮</b>里都是 false，
         * 于是「可比口径 93%」和「全体口径 84%」的 9.4 个百分点
         * 被摊平进翻转矩阵 —— 而两轮之间它们<b>一格都不会翻</b>，
         * 矩阵上看起来完全正常。
         *
         * <h3>★ 数字不四舍五入</h3>
         *
         * <p>{@code recall@5} / {@code mrr@5} 发的是<b>原始 double</b>。
         * 舍入会让「把逐题行重新汇总起来」得不到段里的那个数 ——
         * 而「逐题行能精确重算出汇总」正是本段存在的一半理由。
         */
        Map<String, Object> verdictRow(IntentTree.Tree tree) {
            Map<String, Object> r = new LinkedHashMap<>();

            String goldIntent = goldIntent();
            Set<Long> gold = goldChunkIds();
            boolean declared = bank != null && Boolean.TRUE.equals(bank.getExpectNoRetrieval());

            // ── 身份与切片键 ──
            r.put("题号", questionNo);
            r.put("题集", bank == null ? null : bank.getQuestionSet());
            r.put("类别", bank == null ? null : bank.getCategory());
            r.put("难度", bank == null ? null : bank.getDifficulty());
            r.put("标注叶子", goldIntent);

            // ── 分母归属（★ 让脚本不必重写分母规则）──
            r.put("声明不检索", declared);
            r.put("正解切片数", (long) gold.size());
            r.put("正解切片已不在库里", !gold.isEmpty() && goldDocTypes().isEmpty());
            r.put("进检索分母", inRetrievalDenominator());

            // ── 闸门 ──
            List<Integer> statuses = new ArrayList<>(rows.size());
            for (QaLog row : rows) {
                statuses.add(row.getStatus());
            }
            r.put("三次status", statuses);
            r.put("取的是第几次成功", (long) successOrdinal());   // 0 = 三次全被挡
            r.put("闸门", gateVerdict(statuses));

            // ── 意图（三态）──
            String mode = intentMode();
            boolean goldIsTarget = goldIntent != null && tree.findTarget(goldIntent).isPresent();
            r.put("分类众数", mode);
            r.put("意图全票一致", unanimous());
            r.put("gold是合法分类目标", goldIsTarget);
            r.put("意图正确", goldIsTarget ? Boolean.valueOf(goldIntent.equals(mode)) : null);

            // ── 检索（三态）──
            //
            // ★★ judgeable 的条件必须和 检索段 / 归因段 的 continue 条件【逐字相同】。
            //    这里刻意【不】排除「正解切片已不在库里」的题 ——
            //    那类题在检索段里是【算作没命中】的（分母里有它），
            //    在这里也必须如此。把它判成 null 会让两个分母差一道题，
            //    而差一道题的后果是「逐题表重算不出汇总」，
            //    读报告的人会以为是自己数错了。
            //    ★ 那道题本人在 正解切片已不在库里 一格里有记号，
            //    要单独处理它的人有地方下手。
            QaLog ok = firstSuccess();
            List<Long> ctx = ok == null ? null : finalTopK(mapper, ok);
            boolean judgeable = inRetrievalDenominator() && detail(mapper, ok) != null;
            if (!judgeable) {
                r.put("命中@5", null);
                r.put("recall@5", null);
                r.put("mrr@5", null);
                r.put("命中的正解切片", null);
                r.put("归因", null);
                r.put("上下文条数", null);
                r.put("越界切片数", null);
            } else {
                long covered = ctx.stream().limit(5).filter(gold::contains).count();
                Set<Long> hit = new TreeSet<>(gold);
                hit.retainAll(ctx);
                r.put("命中@5", contains(ctx, gold, 5));
                r.put("recall@5", (double) covered / gold.size());
                r.put("mrr@5", reciprocalRank(ctx, gold, 5));
                r.put("命中的正解切片", hit + "/" + gold);
                r.put("归因", attributionBucket());
                r.put("上下文条数", (long) ctx.size());
                r.put("越界切片数", outOfBoundsCount(ctx, tree.docTypesOf(goldIntent)));
                // ★ 越界切片数【只数类型已知的】—— 切片已不在 kb_chunk 里时
                //   它的 doc_type 无从谈起，既不算越界也不算在内。
                //   ★ 于是「上下文条数 − 未知类型切片数」才是过度检索率的分母。
                //   不发这一格的话，两个数对不上时读报告的人会以为是算错了，
                //   而它其实是【正解切片已经不在库里】这个更严重的事实。
                r.put("未知类型切片数", (long) (ctx.size() - ctx.stream()
                        .filter(chunkDocTypes::containsKey).count()));
            }

            // ── 抖动与耗时 ──
            r.put("跨次序列变了", distinctFinalTopKAcrossSuccesses() > 1);
            r.put("总耗时ms", ok == null ? null : ok.getTotalLatencyMs());
            return r;
        }

        /**
         * 闸门的多数票 —— <b>只看「有没有反问」，不看「反问之外发生了什么」</b>。
         *
         * <p>★ {@code status=2}（模型链路失败）和 {@code status=4}（被限流拒掉）
         * 都算「放行」：闸门确实把请求放过去了，后面失败是另一条轴上的事。
         * 把它们叫成「抖动」会让一次模型超时看起来像闸门不稳 ——
         * 而两者的修法毫无关系。要分这两件事看 {@code 三次status} 那一格。
         */
        private static String gateVerdict(List<Integer> statuses) {
            if (statuses.isEmpty()) {
                return "无行";
            }
            if (statuses.contains(null)) {
                return "有行没写status";
            }
            long clarify = statuses.stream()
                    .filter(s -> s == QaLog.STATUS_CLARIFY).count();
            if (clarify == 0) {
                return "都放行";
            }
            return clarify == statuses.size() ? "都反问" : "抖动";
        }

        /** 送进 prompt 的切片里，{@code doc_type} 不在期望集合内的条数 */
        private long outOfBoundsCount(List<Long> ctx, Set<Integer> expected) {
            long n = 0;
            for (Long id : ctx) {
                Integer dt = chunkDocTypes.get(id);
                if (dt != null && !expected.contains(dt)) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * 无唯一众数（平票）时返回 {@code null}。
     *
     * <p>★ 平票<b>不是</b>「取第一个」：两种结果各投 1 票时取谁都是错的，
     * 而返回 null 让「这道题没有共识」变成一个<b>可以被数出来</b>的事实 ——
     * 「无众数的题数」这一栏才有意义。
     */
    private static String mode(Map<String, Integer> votes) {
        if (votes.isEmpty()) {
            return null;
        }
        int best = 0;
        int count = 0;
        String winner = null;
        for (Map.Entry<String, Integer> e : votes.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                winner = e.getKey();
                count = 1;
            } else if (e.getValue() == best) {
                count++;
            }
        }
        return count == 1 ? winner : null;
    }
}
