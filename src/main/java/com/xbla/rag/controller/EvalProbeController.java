package com.xbla.rag.controller;

import com.xbla.rag.common.ApiResponse;
import com.xbla.rag.common.ConfigRedactor;
import com.xbla.rag.rag.eval.EvalAnswerService;
import com.xbla.rag.rag.eval.EvalQuestionLoader;
import com.xbla.rag.rag.eval.EvalReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 评测集调试探针。
 *
 * <p>⚠️ {@code @Profile("local")} —— 和另外两个探针一样只在本地存在。
 *
 * <h3>接口</h3>
 * <pre>
 *   # 从 data/eval/baseline-questions.yml 重新加载评测集（幂等 upsert）
 *   curl -s -X POST localhost:8080/api/debug/eval/reload
 * </pre>
 *
 * <p>返回体里带 <b>已解析出 ground truth 的完整题目列表</b> ——
 * {@code scripts/eval_baseline.py} 直接用它来算指标，
 * 不需要自己去查数据库，也不需要知道锚点是怎么解析的。
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/eval")
@Profile("local")
public class EvalProbeController {

    private final EvalQuestionLoader loader;

    /**
     * ★ 类型是 {@link ConfigurableEnvironment} 而不是 {@code Environment} ——
     * 因为只有它（以及它的父接口 {@code ConfigurablePropertyResolver}）才有
     * {@code getPropertySources()}。{@code Environment} 只答「某个键是多少」，
     * 答不了「有哪些键」。
     *
     * <p>Spring 容器里本来就只有 {@code ConfigurableEnvironment} 这一个实现，
     * 所以注入没有代价 —— 是<b>接口选窄了</b>，不是多要了什么。
     */
    private final ConfigurableEnvironment environment;

    private final EvalReportService reportService;

    private final EvalAnswerService answerService;

    public EvalProbeController(EvalQuestionLoader loader, ConfigurableEnvironment environment,
                               EvalReportService reportService,
                               EvalAnswerService answerService) {
        this.loader = loader;
        this.environment = environment;
        this.reportService = reportService;
        this.answerService = answerService;
    }

    /**
     * 重新加载题库。
     *
     * <p><b>幂等</b>：按 {@code question_no} upsert，反复调用不会产生重复数据。
     *
     * <p>★ 阶段 7 起扫的是 {@code data/eval/} <b>整个目录</b>（见
     * {@link EvalQuestionLoader#EVAL_DIR}）—— 因为「漏加载一个文件」是静默的，
     * 而「多加载一个文件」会当场报错。
     *
     * @param set 只加载这一套题（{@code baseline} / {@code stage7} / {@code stage7-multi}）。
     *            不传 = 全部。★ 日常应该<b>不传</b>：部分加载会让「某一套少了一半题」
     *            从数据上看不出来（它只表现为题数变少）。
     */
    /**
     * ★ 返回类型是 {@code Object}，因为**这个接口有两种形状**：
     *
     * <pre>
     *   成功  →  扁平的 Map（count / bySet / questions / …）
     *   失败  →  {@code ApiResponse.fail(加载器那句话)}
     * </pre>
     *
     * <p><b>为什么不统一成 {@code ApiResponse}</b>：成功那条形状是阶段 4 就定下的，
     * {@code scripts/eval_baseline.py} 直接读 {@code loaded["count"]}
     * （它已经按「没有 count 就是失败」写好了失败分支，会打印 {@code message}）。
     * 把成功也包进信封会打断那个脚本，而它现在正被用来做回归对比。
     *
     * <p><b>为什么失败要单独接住</b>：{@code IllegalStateException} 落到
     * {@code GlobalExceptionHandler} 的兜底分支时，那边<b>刻意不把异常消息
     * 返回给客户端</b>（生产里那可能泄露内部信息）—— 于是调用方只看到
     * 「服务内部错误，请稍后重试」，而<b>真正的那句话（哪道题、哪个锚点、
     * 命中了哪几条）只躺在日志里</b>。
     *
     * <p>结果就是：加载器辛辛苦苦「吵起来」，而吵闹的内容没人听见。
     * 这个接口是 {@code @Profile("local")} 的调试探针，不受生产那条约束，
     * 所以这里显式地把它透出去 —— <b>吵闹必须到达那个能动手改的人</b>。
     *
     * <p>⚠️ 只捕 {@code IllegalStateException}：它是加载器表达「题库有问题」
     * 的唯一形态。别的异常仍然是 bug，该走 500 走 500。
     */
    @PostMapping("/reload")
    public Object reload(
            @RequestParam(value = "set", required = false) String set) {
        EvalQuestionLoader.Result result;
        try {
            result = loader.reload(set);
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", result.questions().size());
        response.put("inserted", result.inserted());
        response.put("updated", result.updated());
        // ★ bySet 是「这次跑的是哪一套、多少题」的唯一出处 —— 报告里每张表
        //   都要写清统计的是哪一套，否则三套题的数字会被读者当成一回事
        response.put("bySet", result.bySet());
        // ★ 没写标注理由的题数：不是错误，只是**可见**。
        //   150 题里悄悄少了 20 条理由，是没人会发现的事
        response.put("missingNotes", result.missingNotes());
        // ★★ 声明「不检索」的题数（工具题 / 兜底 / 澄清）—— 它们【不进】
        //   检索指标的分母。跑题器与报告端点都靠它知道「有多少道题
        //   本来就测不出召回」。★ 不是错误，是口径。
        response.put("emptyGold", result.emptyGold());
        // ★★ 多轮题数（阶段 7 批次 6）—— 它等于 bySet 里 *-multi 那一项。
        //   两个数摆在一起不是冗余：多轮题必须单独一套是【约定】，
        //   而约定被违反时（多轮题写进了单轮集）加载器会当场报错，
        //   所以这两个数【必须】相等 —— 摆出来是让人一眼看到那个约定还在
        response.put("multiTurn", result.questions().stream()
                .filter(EvalQuestionLoader.LoadedQuestion::isMultiTurn).count());

        // 完整返回解析后的题目 —— 评测脚本靠它拿 ground truth
        // ★ 不返回 source / annotatedBy：它们是**文件级**的，逐题重复 150 份
        //   只是噪声。库里那两列需要时直接查（或看响应里的 bySet）
        response.put("questions", result.questions().stream().map(q -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("questionSet", q.questionSet());
            m.put("questionNo", q.questionNo());
            m.put("question", q.question());
            // ★★ 多轮题的两个字段（V13）—— 跑题器【按 turns 逐轮发问】，
            //   而不是按 question 发一句。question 只是 turns 的最后一轮，
            //   它是给报告和 SQL 用的（单轮题的 turns 是空数组，不是 null）
            m.put("turns", q.turns());
            // ★ standalone 是 gold 不是注释：T3 会拿它做「同一句话
            //   单独问 vs 跟着上下文问」的对照 —— 那个差值就是多轮能力的边界
            m.put("standaloneQuestion", q.standaloneQuestion());
            m.put("category", q.category());
            m.put("difficulty", q.difficulty());
            m.put("intent", q.intent());
            m.put("expectedChunkIds", q.expectedChunkIds());
            // ★★ 跑题器要 userId 才能给工具题发 X-Xbla-User-Id 头 ——
            //   它只能从这里拿（题库是唯一出处）。没有身份的题返回 null，
            //   而 null 与 0 的区别要保住：0 可能是个合法 id。
            m.put("userId", q.userId());
            // ★ 声明「不检索」的题：跑题器据此知道【不要】去比对
            //   retrieval_detail（它的检索段会是 null，那是「没有发生」的
            //   诚实表达，不是「检索失败」）
            m.put("expectNoRetrieval", q.expectNoRetrieval());
            return m;
        }).toList());

        // 按查询形态统计 —— 三类查询在两条召回路上的表现本来就不同，
        // 报告必须能按这个维度切片
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (EvalQuestionLoader.LoadedQuestion q : result.questions()) {
            byCategory.merge(q.category() == null ? "(未分类)" : q.category(), 1L, Long::sum);
        }
        response.put("byCategory", byCategory);

        // 按意图统计 —— 意图树是 5.1 的交付物，这里报出每类各有多少题，
        // 顺便让「某个意图一道题都没有」这件事可见（那意味着 7.2 算不出它的准确率）
        Map<String, Long> byIntent = new LinkedHashMap<>();
        for (EvalQuestionLoader.LoadedQuestion q : result.questions()) {
            byIntent.merge(q.intent(), 1L, Long::sum);
        }
        response.put("byIntent", byIntent);
        response.put("note", "intent 为人工标注，取值见 data/agent/intent-tree.yml。"
                + "一致性（该题 gold doc_types ⊆ 意图声明的 doc_types）由 "
                + "IntentTreeConsistencyTest 持续校验；"
                + "历史哨兵值 " + EvalQuestionLoader.INTENT_PLACEHOLDER
                + " 在重跑本接口后不再产生");

        return response;
    }

    // ================================================================
    // 指标报告（阶段 7 · T4）
    // ================================================================

    /**
     * 一轮评测的指标报告 —— 从 {@code qa_log} 算，<b>零成本、可复算</b>。
     *
     * <pre>
     *   curl -s "localhost:8080/api/debug/eval/report?runId=20260921-stage7" | python -m json.tool
     * </pre>
     *
     * <h3>★ 它是「可复算」的，这一点要用起来</h3>
     *
     * <p>同一份库里的数据，换一天再调一次这个端点，必须得到逐字节一样的报告。
     * 如果不是，说明有东西没被固定住 —— 而那种问题在报告里表现为
     * 「数字悄悄变了」，不会有任何异常。<b>所以「重复调两次 diff 为空」
     * 是这个端点的第一条验收标准。</b>
     *
     * <h3>★ 报告里的每一节都带口径</h3>
     *
     * <p>每个比率都带着分母（{@code n}），每个切片都带 {@code 可信} 标志
     * （{@code n < }{@value EvalReportService#MIN_SLICE_N} 时是 {@code false}），
     * 而且口径本身作为数据返回（{@code 口径} 那一节）—— 因为报告会被抄进
     * {@code docs/11}，注释不会跟着一起去。见 {@link EvalReportService} 的类注释。
     *
     * <h3>⚠️ 它不做的事</h3>
     *
     * <ul>
     *   <li><b>不判断这轮数据能不能用</b> —— 它把「跑了但题库里没有的题」
     *       「在题库里但没跑的题」列出来（{@code 数据完整性} 一节），
     *       但不会因此拒绝出数。<b>该不该采信是读的人的决定</b>，
     *       端点的责任是让那个决定做得了</li>
     *   <li><b>不读 {@code run.raw.json}</b> —— 那是跑题器的调度侧事实，
     *       而这里是服务端的账本。两边对不上是<b>要查的信号</b>，
     *       让端点去读那个文件只会把两个来源糅成一个</li>
     * </ul>
     *
     * @param runId 评测运行 ID。空或者不存在时返回一个空报告（不是 404）——
     *              ★ 因为「这一轮一行都没有」和「这一轮不存在」在数据上
     *              本来就分不开，硬造一个 404 只是多一层要维护的语义
     */
    @GetMapping("/report")
    public Map<String, Object> report(@RequestParam("runId") String runId) {
        return reportService.report(runId);
    }

    /**
     * 一轮评测的<b>答题明细</b>，整理成 RAGAS 能直接吃的形状（阶段 7.5）。
     *
     * <pre>
     *   curl -s "localhost:8080/api/debug/eval/answers?runId=&lt;id&gt;" &gt; eval_results/&lt;id&gt;/answers.json
     * </pre>
     *
     * <p>★ <b>零成本</b>：只读库，不调任何模型。RAGAS 那一步才花钱，
     * 而它需要先拿到这份数据 —— 所以「取数」和「评判」分成了两步，
     * 这样调 RAGAS 的口径（喂哪份 contexts、跑哪几个指标）可以改了再跑，
     * <b>不必重跑评测</b>。
     *
     * <p>★★ <b>返回两个 contexts 数组</b>，理由见
     * {@link com.xbla.rag.rag.eval.EvalAnswerService} 的类注释 ——
     * 简言之：faithfulness 要「模型看到的全部输入」，而
     * context_precision/recall 要「检索回来的东西」。<b>一个数组满足不了两件事。</b>
     *
     * @param runId 评测运行 ID。与 {@code /report} 同口径：空或不存在时返回
     *              一份「题数 0」的空结果，不返回 404
     */
    @GetMapping("/answers")
    public Map<String, Object> answers(@RequestParam("runId") String runId) {
        return answerService.answers(runId);
    }

    // ================================================================
    // 配置快照（阶段 7 · T3）
    // ================================================================

    /** 只快照这个前缀——`xbla.*` 是本项目自己的全部配置。 */
    private static final String PREFIX = "xbla.";

    /**
     * 本次运行**实际生效的** {@code xbla.*} 配置。
     *
     * <h3>★★ 为什么必须问 Spring，不能读 {@code application.yml}</h3>
     *
     * <p>阶段 7 的 A/B 是<b>靠环境变量覆盖</b>跑的（这正是 CLAUDE.md「六」里
     * 演示的用法）：
     *
     * <pre>
     *   SPRING_APPLICATION_JSON='{"xbla":{"chat":{"history":{"enabled":false}}}}' ./mvnw spring-boot:run
     * </pre>
     *
     * <p>于是<b>文件里的值和运行时的值不是一回事</b>。读文件写快照的后果是：
     * 报告上印着「配置 A」，而实际跑的是「配置 B」—— <b>没有任何报错</b>，
     * 只是 A/B 的结论从此不可信。这和 ADR-080 那几条是同一类错误：
     * <b>一个数旁边的口径如果是错的，那个数就不可解释。</b>
     *
     * <p>所以要问 {@link Environment} —— 它是解析完所有来源（yml / 环境变量 /
     * {@code SPRING_APPLICATION_JSON} / 命令行 / 测试的 {@code @TestPropertySource}）
     * 之后的那一个答案。
     *
     * <h3>★ 为什么带一个 {@code origin}</h3>
     *
     * <p>光看值不够。{@code "false"} 这个值在「作者把默认值改成 false」和
     * 「这轮 A/B 用环境变量压成了 false」两种情况下<b>长得一模一样</b>，
     * 而它们的含义完全相反。{@code origin} 是属性来源的名字
     * （{@code application.yml} / {@code spring.application.json} / …），
     * 它让「这轮到底是不是在跑 A/B」变成一个可以直接看出来的事实。
     *
     * <p>分成两张平行的表（{@code properties} / {@code origin}）而不是一张嵌套表：
     * A/B 的配置 diff 只需要比 {@code properties}，让 {@code origin} 去当诊断信息，
     * 免得它把每一行 diff 都变成两行。
     *
     * <h3>⚠️ 两点边界</h3>
     * <ul>
     *   <li>只快照 {@code xbla.*}。数据源 / 线程池 / Tomcat 那些<b>刻意不含在内</b> ——
     *       它们不是 A/B 的自变量，混进来只会让配置 diff 变吵</li>
     *   <li>{@code origin} 记的是「<b>它被找到</b>的那个可枚举来源」。
     *       极端情况下某个更高优先级的来源不可枚举（协议自定义的
     *       {@link PropertySource} 可以是），此时值与 origin 会对不上。
     *       Spring 自带的来源（systemProperties / systemEnvironment /
     *       {@code spring.application.json} / 各种 yml）<b>全部是可枚举的</b>，
     *       所以这条在实践中不会发生 —— 写在这里是因为它一旦发生<b>不会报错</b></li>
     * </ul>
     *
     * <p>⚠️ 它和这个类里另外两个端点一样是 {@code @Profile("local")} 的，
     * 绝不能暴露到生产。即便如此，密钥照样脱敏 —— 快照会落进报告文件。
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        // ★ TreeMap 而不是 LinkedHashMap：快照要能【跨运行 diff】，
        //   而属性来源的枚举顺序不是契约。同一个配置两次跑出两种顺序，
        //   diff 就会满屏噪声 —— 那正是它最该安静的时候。
        Map<String, String> properties = new TreeMap<>();
        Map<String, String> origin = new TreeMap<>();

        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                // ★ 先出现的赢：getPropertySources() 是按优先级排的，
                //   而 environment.getProperty() 也用同一个顺序解析。
                //   不判 containsKey 的话，低优先级的来源会【覆盖】高优先级的，
                //   于是这里报的又是一份「文件里的值」。
                if (!name.startsWith(PREFIX) || properties.containsKey(name)) {
                    continue;
                }
                properties.put(name, ConfigRedactor.redact(name, environment.getProperty(name)));
                origin.put(name, source.getName());
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("prefix", PREFIX);
        response.put("count", properties.size());
        response.put("properties", properties);
        response.put("origin", origin);
        response.put("note", "这是【运行时实际生效】的配置，已解析 yml / 环境变量 / "
                + "SPRING_APPLICATION_JSON 的全部覆盖 —— 不要拿 application.yml 顶替它。"
                + "脱敏规则见 ConfigRedactor：键名【按分隔符切开后某一段整个等于】"
                + "key/secret/password/token/credential 一类词才抹掉（所以 "
                + "keyword-weight 不会被误伤）；"
                + "值为空串表示「配置了但是空的」，字段缺席表示「没有任何来源提供它」。");
        return response;
    }
}
