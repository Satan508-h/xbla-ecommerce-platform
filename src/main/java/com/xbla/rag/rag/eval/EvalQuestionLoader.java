package com.xbla.rag.rag.eval;

import com.xbla.rag.entity.EvalQuestion;
import com.xbla.rag.mapper.EvalQuestionMapper;
import com.xbla.rag.mapper.KbChunkMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@code data/eval/baseline-questions.yml} 里的评测题加载进 {@code eval_question}。
 *
 * <h2>一、★ 锚点必须 fail loudly</h2>
 *
 * <p>评测题不存 {@code chunk_id}（BIGSERIAL 换台机器就失效），
 * 而是存「该切片里独有的一段原文」，加载时反查。
 *
 * <p>反查结果只有三种，<b>每一种都必须有明确行为</b>：
 *
 * <table border="1">
 *   <caption>锚点解析结果</caption>
 *   <tr><th>命中条数</th><th>含义</th><th>行为</th></tr>
 *   <tr><td>1</td><td>正常</td><td>采用</td></tr>
 *   <tr><td>0</td><td>语料变了、切分粒度改了、锚点写错了</td>
 *       <td><b>抛异常</b></td></tr>
 *   <tr><td>&gt;1</td><td>锚点不够独特，无法确定该标哪一个</td>
 *       <td><b>抛异常</b></td></tr>
 * </table>
 *
 * <p>「静默取第一条」是最糟的选择 —— 它让 ground truth 悄悄漂移，
 * 而<b>基线漂移是评测系统里最难发现、后果最严重的一类错误</b>：
 * 你会拿着两份不可比的报告去论证「优化有效」。
 *
 * <h2>二、为什么不用 @Profile("seed") 的启动 runner</h2>
 *
 * <p>锚点要对着<b>已经入库的</b> {@code kb_chunk} 解析，
 * 而入库是异步的、由 API 触发的 —— 应用启动那一刻语料可能还没灌进去。
 * 所以做成一个按需触发的接口（{@code POST /api/debug/eval/reload}），
 * 时机由调用方掌握。
 *
 * <h2>三、为什么用 SnakeYAML 而不是 Jackson</h2>
 *
 * <p>{@code jackson-dataformat-yaml} 不在当前依赖里，而 SnakeYAML
 * 是 Spring Boot 解析 {@code application.yml} 自带的，<b>已经在 classpath 上</b>。
 * 引一个新依赖只为了读一个 20 条的配置文件不划算。
 *
 * <p>代价是要手写一层 Map 到 record 的转换 —— 但这也让「字段缺了怎么办」
 * 变得显式可控（见 {@link #requireString}）。
 */
@Component
public class EvalQuestionLoader {

    private static final Logger log = LoggerFactory.getLogger(EvalQuestionLoader.class);

    /**
     * 默认读取的评测集文件（相对项目根目录）。
     *
     * <p>⚠️ 阶段 7 起<b>不再是唯一来源</b> —— {@link #reload()} 扫的是
     * {@link #EVAL_DIR} 目录下的全部 {@code *.yml}。这个常量留给
     * 「只想加载某一个文件」的场景（迁移脚本、单个文件的测试）。
     */
    public static final String DEFAULT_PATH = "data/eval/baseline-questions.yml";

    /**
     * 评测集目录 —— <b>阶段 7 起，这里的每一个 {@code *.yml} 都是题库的一部分</b>。
     *
     * <h3>★ 为什么是「扫目录」而不是「在代码里列一份文件名清单」</h3>
     *
     * <p>两种做法都能工作，区别在<b>漏掉一个文件时会发生什么</b>：
     *
     * <pre>
     *   清单   新加一个文件、忘了登记 → 它【静默地】不被加载，
     *         报告里的题数从 150 变成 120，而没有人知道少了什么。
     *         （同 docs/03 里那句「文件清单式的配置，漏了不报错」）
     *   扫目录 目录里多了一个文件 → 它一定被加载。
     *         ★ 加错了会【当场报错】（缺 question_set、锚点解析不出来），
     *         而报错是可以修的，静默漏加载不行。
     * </pre>
     *
     * <p>★ 与 {@code data/corpus/manifest.yml} 的选择相反，而这是<b>刻意的</b>：
     * 语料那边要登记是因为 {@code doc_type} 只能由人来判（猜不出来），
     * 而题库这边每个文件都<b>自带</b> {@code question_set} 和来源声明 ——
     * 声明就在文件里，所以「有没有声明」这件事本身是可见的。
     */
    public static final String EVAL_DIR = "data/eval";

    /** 题库文件的后缀 —— 只认这两个，别的东西放在这个目录里会被忽略（并打日志） */
    private static final List<String> EVAL_FILE_SUFFIXES = List.of(".yml", ".yaml");

    /**
     * {@code eval_question.intent} 的<b>历史</b>占位值。
     *
     * <p>那一列是 NOT NULL，而意图树的定义属于阶段 5。阶段 4 时这里写的是哨兵值，
     * 目的是让阶段 5 回填时 {@code WHERE intent = 'UNCLASSIFIED'} 一眼查出剩多少条。
     *
     * <p>★ <b>2026-09-19 阶段 5.1 起，这个值不再被写入。</b>
     * 意图树已经定义好了（{@code data/agent/intent-tree.yml}），
     * 评测集 YAML 里的 {@code intent} 字段现在是<b>必填</b>的，
     * 缺失会直接导致加载失败 —— 就像锚点解析不唯一一样。
     *
     * <p>保留这个常量是因为<b>库里的旧行还带着它</b>：
     * 在跑过一次 {@code POST /api/debug/eval/reload} 之前，
     * {@code eval_question} 里仍有 20 行是 UNCLASSIFIED。
     * 调试探针用它来报告「还有多少条没回填」。
     */
    public static final String INTENT_PLACEHOLDER = "UNCLASSIFIED";

    /**
     * 多轮题集的名字后缀（{@code stage7-multi}）。
     *
     * <p>★ 加载器<b>用这个名字来判一道题该不该有多轮</b>，也就是「约定即校验」：
     *
     * <pre>
     *   turns 非空，但 question_set 不以 -multi 结尾  →  报错
     *   turns 为空，但 question_set 以 -multi 结尾    →  报错
     * </pre>
     *
     * <p><b>为什么值得把一条命名约定写进代码</b>：多轮题混进单轮集
     * <b>不会报错</b> —— 它只让那一套的单轮指标里多出几道「正确答案依赖上一句」的题，
     * 于是两个数都不再描述任何东西（阶段 7 评审的洞 6）。
     * 而「混进去了」这件事从题数上是完全看不出来的。
     */
    public static final String MULTI_TURN_SET_SUFFIX = "-multi";

    /**
     * 单轮题允许出现的字段 —— <b>不在这张表里的字段会让加载失败</b>。
     *
     * <p>动机是一个具体的已知风险：有人把 {@code session_no} 写进题库
     * （以为「多轮题应该由题库指定会话」）→ 跑题器照用 → repeat 之间会话串味
     * （阶段 7 的 R7），而症状是「多轮指标莫名其妙地好」。
     * 加载器原来对未知字段是<b>全静默忽略</b>的，所以那句话写进去不会有人知道。
     *
     * <p>代价：写题时不能随手加给人看的注释字段了。<b>这个代价是刻意选的</b> ——
     * 注释该写在 {@code #} 里（YAML 原生支持），而字段名拼错
     * （{@code difficlty}）在宽松模式下永远不会被发现。
     *
     * <p>⚠️ 注意这是<b>逐层</b>的白名单：本表管题目那一层，锚点那一层见
     * {@link #ANCHOR_KEYS}。两层的字段集合不同。
     */
    private static final Set<String> QUESTION_KEYS = Set.of(
            "question_no", "question", "turns", "standalone_question",
            "category", "difficulty", "intent", "expected_answer",
            "anchors", "allow_multiple", "expect_no_retrieval", "user_id", "notes");

    /**
     * 锚点项允许出现的字段。
     *
     * <p>{@code doc_hint} 是<b>给人看的</b>（这段原文出自哪份文档的哪一节），
     * 加载器不读它 —— 但它在阶段 4 的 20 道题里用了 39 次，
     * 是核对锚点时唯一能帮上忙的东西，所以留着。
     */
    private static final Set<String> ANCHOR_KEYS = Set.of("text", "doc_hint");

    /**
     * 把 {@code turns} 写成 JSON 用的。
     *
     * <p>★ 用<b>独立的静态实例</b>而不是注入 Spring 那个：这个类的构造签名
     * 被测试直接调用（{@code new EvalQuestionLoader(mapper, mapper)}），
     * 为它多注入一个 Bean 只会让所有测试跟着改。而序列化一个
     * {@code List<String>} 不需要任何定制配置。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final KbChunkMapper chunkMapper;
    private final EvalQuestionMapper evalQuestionMapper;

    public EvalQuestionLoader(KbChunkMapper chunkMapper, EvalQuestionMapper evalQuestionMapper) {
        this.chunkMapper = chunkMapper;
        this.evalQuestionMapper = evalQuestionMapper;
    }

    /**
     * 一道已解析出 ground truth 的评测题。
     *
     * <p>这个结构也会被接口返回给评测脚本 —— 脚本拿它去比对检索结果。
     *
     * @param questionSet 属于哪一套题（{@code baseline} / {@code stage7} / …）。
     *                    ★ 脚本必须按它分流：三套的口径不同，混算出的指标
     *                    不描述任何东西（见 V11 迁移）。
     * @param source      题目来源（{@code reverse_constructed} / {@code corpus_driven_manual}），
     *                    <b>来自文件顶层声明</b>。
     * @param annotatedBy 标注者与审核者，同样来自文件顶层。
     *                    ★ 这两个字段和 {@code questionSet} 一样是<b>文件级</b>的，
     *                    放进这个 record 是为了让 {@code upsert} 只依赖它一个参数 ——
     *                    副作用是本 record 有 10 个分量。可接受：它是个数据载体，
     *                    不是给别人实现的接口。
     *                    ⚠️ 调试接口<b>不把它们逐个返回</b>（150 题各带一份重复的字符串
     *                    只是噪声）；它们落在 {@code eval_question} 两列里，需要时查库。
     */
    public record LoadedQuestion(
            String questionSet,
            String questionNo,
            String question,
            List<String> turns,
            String standaloneQuestion,
            String category,
            Integer difficulty,
            String expectedAnswer,
            String intent,
            List<Long> expectedChunkIds,
            String source,
            String annotatedBy,
            Long userId,
            boolean expectNoRetrieval) {

        /**
         * ★ 这道题有没有检索目标。
         *
         * <p>等于 {@code expectedChunkIds.isEmpty()} —— 但<b>用名字说出来</b>：
         * 报告端点在决定「这题进不进检索指标的分母」时，
         * 读的是这个语义，而不是自己去判空数组。
         */
        public boolean hasRetrievalGold() {
            return !expectedChunkIds.isEmpty();
        }

        /**
         * ★ 这是不是一道多轮题。
         *
         * <p>等于 {@code turns.size() >= 2} —— 同样用名字说出来。
         * 单轮题返回空列表（<b>不是 null</b>）：调用方可以无条件 {@code .stream()}，
         * 少一个 NPE 的分支。
         */
        public boolean isMultiTurn() {
            return !turns.isEmpty();
        }
    }

    /**
     * 加载结果。
     *
     * @param questions    解析好的题目（含 ground truth）
     * @param inserted     新插入条数
     * @param updated      更新条数
     * @param bySet        每套题各多少道 —— ★ 报告里「这次跑的是哪一套、多少题」靠它
     * @param missingNotes 有多少道题<b>没写标注理由</b>（{@code notes} 字段）。
     *                     ⚠️ 它不是错误，只是<b>可见</b>：150 题里有一批没写理由时，
     *                     这个数会明明白白地打出来，而不是等人去发现
     */
    public record Result(List<LoadedQuestion> questions, int inserted, int updated,
                         Map<String, Integer> bySet, int missingNotes, int emptyGold) {
    }

    /**
     * 从 {@link #EVAL_DIR} 加载<b>全部</b>题库文件。
     *
     * <p>★ 这是唯一的常规入口 —— 部分加载（只 reload 一个文件）在阶段 7 是危险的：
     * 报告端点是按 {@code question_set} 分流的，而「某一套只加载了一半」
     * 从数据上完全看不出来（它只表现为题数变少）。
     */
    public Result reload() {
        return reload(null);
    }

    /**
     * 加载题库，可只加载其中一套。
     *
     * @param onlySet 只要这一套（{@code null} = 全都要）
     */
    public Result reload(String onlySet) {
        return reload(Path.of(EVAL_DIR), onlySet);
    }

    /**
     * 从指定目录加载 —— <b>包级可见，只给测试用</b>。
     *
     * <p>★ 为什么留这个缝：这个类<b>所有的价值都在「什么时候抛异常」上</b>
     * （缺 question_set、题号重复、锚点不唯一……），而那些分支在真实目录上
     * 一条都跑不到 —— 真实目录永远是合法的。
     * 没有这个缝，那些断言就只能在脑内推演，而<b>「脑内推演过的错误处理」
     * 和「跑过的错误处理」是两件事</b>。
     *
     * <p>⚠️ 它【不】public：外面不该有人拿别的目录来加载题库 ——
     * 那样评测报告统计的就是一个没人知道的题集。
     */
    Result reload(Path dir, String onlySet) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("题库目录不存在：" + dir.toAbsolutePath()
                    + "（它是 git 跟踪的，检查是不是在项目根目录下运行）");
        }

        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> EVAL_FILE_SUFFIXES.stream()
                            .anyMatch(s -> p.getFileName().toString().endsWith(s)))
                    // ★ 排序：加载顺序必须确定，否则「同样的输入产出不同的日志顺序」
                    //   会让两次运行的 diff 里混进噪声（同 ADR-028 的字节确定性）
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描题库目录失败：" + e.getMessage(), e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("题库目录里一个 " + EVAL_FILE_SUFFIXES
                    + " 文件都没有：" + dir.toAbsolutePath());
        }

        List<LoadedQuestion> loaded = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<String, String> seenQuestionNos = new LinkedHashMap<>();

        for (Path file : files) {
            loadFile(file, onlySet, loaded, problems, seenQuestionNos);
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "★ 题库加载失败，共 " + problems.size() + " 处问题（锚点必须唯一命中）：\n  "
                            + String.join("\n  ", problems));
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("没有加载到任何题目（onlySet=" + onlySet
                    + "）。★ 空题库会让评测报告看起来「全 0 分」而不是「没跑」—— "
                    + "所以这里直接失败");
        }

        int inserted = 0;
        int updated = 0;
        for (LoadedQuestion q : loaded) {
            if (upsert(q)) {
                inserted++;
            } else {
                updated++;
            }
        }

        Map<String, Integer> bySet = new LinkedHashMap<>();
        int missingNotes = 0;
        int emptyGold = 0;
        for (LoadedQuestion q : loaded) {
            bySet.merge(q.questionSet(), 1, Integer::sum);
            if (!q.hasRetrievalGold()) {
                emptyGold++;
            }
        }
        for (Path file : files) {
            missingNotes += countMissingNotes(file, onlySet);
        }

        // ★ emptyGold 和 missingNotes 一样，是「不是错误但必须可见」的数：
        //   它说明有多少道题不进检索指标的分母。150 题里悄悄有 30 道
        //   不进分母，是没人会发现的事 —— 所以它至少得有个数。
        log.info("★ 题库加载完成：共 {} 题（新增 {} / 更新 {}），按题集 {}，"
                        + "其中 {} 题没写 notes，{} 题声明不检索（空 gold）",
                loaded.size(), inserted, updated, bySet, missingNotes, emptyGold);
        return new Result(loaded, inserted, updated, bySet, missingNotes, emptyGold);
    }

    /** 单个文件 —— 顶层声明 + 逐题解析，问题收集起来一次性报 */
    @SuppressWarnings("unchecked")
    private void loadFile(Path path, String onlySet, List<LoadedQuestion> loaded,
                          List<String> problems, Map<String, String> seenQuestionNos) {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(path)) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            problems.add("[" + path.getFileName() + "] 读取失败：" + e.getMessage());
            return;
        }
        if (root == null) {
            problems.add("[" + path.getFileName() + "] 文件是空的");
            return;
        }

        // ── 顶层三元组：这三样必须写在文件里，不是写在代码里 ──
        //
        // ★ question_set 决定「和谁一起统计」，source / annotated_by 决定
        //   「这份报告的可信度该怎么描述」。三者缺一个，报告里就会有一块
        //   说不清来历的数字 —— 而那正是「看起来专业、实际没法解释」的形态。
        String set = optionalString(root, "question_set");
        if (set == null) {
            problems.add("[" + path.getFileName() + "] 顶层缺少 question_set。"
                    + "★ 它是「这三套题不能混算」这件事的唯一出处 —— "
                    + "缺了它，这套题会被静默算进别的口径里");
            return;
        }
        if (onlySet != null && !onlySet.equals(set)) {
            return;
        }
        String source = optionalString(root, "source");
        String annotatedBy = optionalString(root, "annotated_by");
        if (source == null) {
            problems.add("[" + path.getFileName() + "] 顶层缺少 source"
                    + "（这道题是怎么造出来的：reverse_constructed / corpus_driven_manual 等）。"
                    + "★ 报告里必须能读出「题是怎么来的」，否则同一个数字可以被解释成完全不同的东西");
        }
        if (annotatedBy == null) {
            problems.add("[" + path.getFileName() + "] 顶层缺少 annotated_by"
                    + "（谁标的、谁核的）。★ 同 source：不写就等于让读者自己猜");
        }

        if (!(root.get("questions") instanceof List<?> rawQuestions)) {
            problems.add("[" + path.getFileName() + "] 顶层缺少 questions 列表");
            return;
        }

        for (Object item : rawQuestions) {
            Map<String, Object> node = (Map<String, Object>) item;
            String no = requireString(node, "question_no", problems);
            if (no == null) {
                continue;
            }
            // ★ 题号在【全部文件之间】唯一 —— 重复了会让 upsert 覆盖掉一道题，
            //   而症状是「题数比预期少一个」，没有任何地方会报错。
            String previous = seenQuestionNos.putIfAbsent(no, path.getFileName().toString());
            if (previous != null) {
                problems.add("[" + no + "] 题号重复：已经在 " + previous + " 里出现过。"
                        + "★ upsert 是按 question_no 做的，重复会让后一道题【覆盖】前一道"
                        + "（症状是题数少一个，不报错）");
                continue;
            }
            try {
                loaded.add(parseOne(node, set, source, annotatedBy, no));
            } catch (RuntimeException e) {
                // ★ 收集所有问题后一次性抛出，而不是遇到第一个就停 ——
                //   改题库时通常一次改好几处，逐条报错要来回跑很多遍
                problems.add("[" + no + "] " + e.getMessage());
            }
        }
    }

    /**
     * 数一下这个文件里有多少道题没写 {@code notes}。
     *
     * <p>★ 它只统计、不报错，是刻意的：{@code notes} 是给人看的（为什么标这个叶子、
     * 哪些候选被否掉了），它的缺失不会让任何数字变错。
     * 但<b>「有多少道题没写理由」这个数本身必须可见</b> ——
     * 否则 150 题里悄悄少了 20 条理由是没人会发现的事。
     */
    @SuppressWarnings("unchecked")
    private int countMissingNotes(Path path, String onlySet) {
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> root = new Yaml().load(in);
            if (root == null || (onlySet != null
                    && !onlySet.equals(optionalString(root, "question_set")))) {
                return 0;
            }
            if (!(root.get("questions") instanceof List<?> rawQuestions)) {
                return 0;
            }
            int missing = 0;
            for (Object item : rawQuestions) {
                if (optionalString((Map<String, Object>) item, "notes") == null) {
                    missing++;
                }
            }
            return missing;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 解析一道题 —— <b>锚点必须唯一命中，否则抛异常</b>。
     *
     * <p>为什么「静默取第一条」是最糟的选择，见类注释第一节。
     */
    @SuppressWarnings("unchecked")
    private LoadedQuestion parseOne(Map<String, Object> node, String set,
                                    String source, String annotatedBy, String no) {
        // ── ① 先看字段名认不认识 —— 拼错的字段名不会自己暴露 ──
        requireKnownKeys(node, QUESTION_KEYS, "本题");

        // ── ② 轮次 ──
        //
        // ★ 多轮题的【最后一轮】就是被测量那一轮，加载器从 turns 里取出来填进
        //   question 列 —— 所以题库里不许再写一次 question（见下面）。
        List<String> turns = parseTurns(node.get("turns"));
        String standaloneQuestion = optionalString(node, "standalone_question");
        boolean multiTurn = !turns.isEmpty();

        // ★★ 多轮 ⇔ 题集名带 -multi。
        //
        //   这条约定其实就是「多轮题必须单独一套」这句话的可执行版本。
        //   两个方向都会出事，而且都不报错：
        //     多轮题混进单轮集 → 那一套的单轮指标里混进几道「答案依赖上一句」的题
        //     单轮题混进多轮集 → 多轮数字被单轮题稀释，看起来「多轮没那么差」
        if (multiTurn != set.endsWith(MULTI_TURN_SET_SUFFIX)) {
            throw new IllegalStateException(multiTurn
                    ? "写了 turns，但 question_set 是 \"" + set + "\" —— "
                      + "多轮题必须放进以 \"" + MULTI_TURN_SET_SUFFIX + "\" 结尾的那一套。"
                      + "★ 混进单轮集不会报错，只会让单轮指标里多出几道「正确答案依赖上一句」"
                      + "的题，于是两个数都不再描述任何东西"
                    : "question_set 是 \"" + set + "\"，但没有 turns —— "
                      + "这一套是多轮题集，单轮题请放进单轮题库。"
                      + "★ 掺进来会让多轮数字被稀释，看起来像「多轮其实没那么差」");
        }

        String question;
        if (multiTurn) {
            // ★ turns 的最后一轮 vs question 字段：同一个事实的两个来源
            //   —— 而两个来源一定会漂。漂了的症状见 V13 迁移第二节。
            if (node.containsKey("question")) {
                throw new IllegalStateException("同时写了 turns 和 question —— "
                        + "turns 的【最后一轮】就是被测量那一轮，加载器自动填进 question 列。"
                        + "两处都写等于同一个事实有两个来源，改了一处忘了另一处时"
                        + "报告会开始描述错的东西，而且不报错。请删掉 question 这一行");
            }
            question = turns.get(turns.size() - 1);
        } else {
            question = optionalString(node, "question");
            if (question == null || question.isBlank()) {
                throw new IllegalStateException("缺少 question 字段");
            }
        }

        // ★ standalone_question 与 turns 同生同死（数据库有一条 CHECK 强制同一件事）。
        //
        //   少了它 → 这题没有 gold：intent / anchors / expected_answer 该按哪句话标？
        //   多了它 → 那其实是【单轮题】写错了字段名（该写 question）。
        //   两种半成品都不留 —— 半成品会被人按自己的理解补齐，而每个人补的不一样。
        if (multiTurn && standaloneQuestion == null) {
            throw new IllegalStateException("多轮题缺少 standalone_question —— "
                    + "即「把最后一轮单独说该怎么说」。"
                    + "★ 本题的 intent / anchors / expected_answer 全部按【那一句】标："
                    + "用户真正想问的是那个意思，追问句只是它在上下文里的省略说法。"
                    + "没有它就不知道该按哪句话标 gold");
        }
        if (!multiTurn && standaloneQuestion != null) {
            throw new IllegalStateException("单轮题不该有 standalone_question —— "
                    + "它的值必然等于 question，那是同一个事实的第二个来源。"
                    + "★ 真想要一句「换种说法」，那是另一道题，写进题库而不是写在备注里");
        }

        Object anchorsNode = node.get("anchors");
        List<?> anchors = anchorsNode instanceof List<?> l ? l : List.of();

        // ★★ 三态校验：有锚点 / 声明了「本就没有检索目标」/ 两者都不是（=写漏了）
        //
        //   为什么要三态而不是「没写锚点就当空 gold」：
        //   「这道题本来没有 gold」（工具题、兜底题）与「忘了写 anchors 段」
        //   在数据上长得**一模一样**（都是空数组）。后者不报错的话，
        //   那道题会**静默退出检索指标** —— 题数少一道看不出来，
        //   指标也不会算错，只是那道题从此不测任何东西了（同 ADR-036 的静默漏跑）。
        boolean expectNoRetrieval = Boolean.TRUE.equals(node.get("expect_no_retrieval"));

        if (anchors.isEmpty() && !expectNoRetrieval) {
            throw new IllegalStateException("缺少 anchors 列表。★ 如果这道题【本来就没有】"
                    + "检索目标（工具调用 / 兜底 / 澄清 —— 正确答案不在知识库里），"
                    + "请显式写一行 expect_no_retrieval: true，而不是留空 —— "
                    + "留空与写漏锚点无法区分，会让这道题静默退出检索指标");
        }
        if (!anchors.isEmpty() && expectNoRetrieval) {
            throw new IllegalStateException("同时写了 anchors 和 expect_no_retrieval: true —— "
                    + "声明「本就没有检索目标」却又给出锚点，是自相矛盾。"
                    + "二者只能有一个（这道题到底要不要进检索指标？）");
        }

        // ★ opt-in：允许锚点命中多条。
        //
        //   默认【不允许】，因为「锚点太泛」必须报错（否则 ground truth 无法确定）。
        //   但确实存在两种【合理】的多命中：
        //     ① 同一份文件被灌了两次（本项目的售后政策汇编就是），切片内容真的一模一样；
        //     ② 那段话在多个切片里都出现，且每一个都能回答问题。
        //   这两种情况下，命中【任意一条】都应该算检索成功 ——
        //   要求必须命中某一条反而是错的。
        //
        //   所以做成显式开关，而不是放宽默认行为：
        //   写评测题的人必须主动确认「这几条真的都算对」。
        boolean allowMultiple = Boolean.TRUE.equals(node.get("allow_multiple"));

        // ★★ 用 LinkedHashSet 而不是 List —— **两条锚点落在同一片切片上是合法的**。
        //
        //   例：一道题想问「退款时限」和「余额到账」，而这两句话在同一个切片里
        //   （售后政策汇编 2.3 就是这么写的），于是两条锚点解析出同一个 chunk_id。
        //   不去重的话 gold 会变成 [55, 55] —— 而
        //
        //     Recall@K = |命中 ∩ gold| / |gold|
        //
        //   的分母被算成 2，于是「正解被完整召回」只能得 0.5 分。
        //   ★ 它不会报错、不会漏题，只是让某些题的召回率**永久偏低**，
        //     而报告上看不出是哪几道题、也看不出是为什么。
        //
        //   ⚠️ 这个缺陷在阶段 4 就存在（原来的 `addAll` 一模一样），
        //   只是那 20 道题恰好每题只有一条锚点、或两条锚点从不相交，所以没暴露。
        //   阶段 7 的题普遍是多锚点（一个事实被多份文档覆盖），第一次写多锚点题就撞上了。
        //
        //   ★ LinkedHashSet 保插入顺序：gold 的顺序会影响报告里的可读性，
        //     而 HashSet 的顺序由 hash 决定 —— 那会让两次加载产出不同的数组。
        LinkedHashSet<Long> chunkIds = new LinkedHashSet<>();
        for (Object anchorItem : anchors) {
            Map<String, Object> anchorNode = (Map<String, Object>) anchorItem;
            requireKnownKeys(anchorNode, ANCHOR_KEYS, "锚点");

            // ★ text 必须显式取出来判空。
            //   原来写的是 String.valueOf(node.get("text")) —— 一个只写了 doc_hint
            //   的锚点会被转成字符串 "null"，然后拿它去全库搜「null」。
            //   那次搜索注定 0 命中，于是报错说「锚点在全库里找不到任何切片: "null"」
            //   —— 吵是吵了，但它在说一件与真正原因无关的事。
            Object textNode = anchorNode.get("text");
            if (!(textNode instanceof String anchor) || anchor.isBlank()) {
                throw new IllegalStateException("锚点缺少 text（或它是空的）。"
                        + "text 是那段原文本身，doc_hint 只是给人看的注释 —— "
                        + "只写 doc_hint 的锚点解析不出任何切片");
            }
            List<Long> hits = chunkMapper.findIdsByContentAnchor(anchor);

            if (hits.isEmpty()) {
                throw new IllegalStateException("锚点在全库里找不到任何切片：\"" + anchor
                        + "\"。可能语料变了或切分粒度改了 —— 需要重新核对这道题");
            }
            if (hits.size() > 1 && !allowMultiple) {
                throw new IllegalStateException("锚点命中了 " + hits.size() + " 条切片，不够独特：\""
                        + anchor + "\" → " + hits
                        + "。请换一段更独有的原文；若这几条【确实都算对】"
                        + "（内容重复、或都能回答该问题），给这道题加一行 allow_multiple: true"
                        + "（★ 静默取第一条会让基线悄悄漂移）");
            }
            chunkIds.addAll(hits);
        }

        // ★ intent 是【必填】的（阶段 5.1 起）。
        //
        //   为什么不做成「缺了就写 UNCLASSIFIED 哨兵」：那个占位值的理由是
        //   「意图树还没定义」—— 现在定义了，理由就不成立了。
        //   留一条可选的退路，等于保留一个已经没有意义的逃生口，
        //   而它绕过的是 7.2 最能说明问题的那一列。
        //
        //   这里【不】校验 code 是否存在于意图树里 —— 那会让 rag/eval 反向依赖
        //   agent/intent，而 agent 是更上层（它编排检索）。校验放在
        //   IntentTreeConsistencyTest 里，那个测试同时还能验证「声明的 doc_types 对不对」，
        //   比在这里查一次 code 存在性强得多。
        String intent = optionalString(node, "intent");
        if (intent == null) {
            throw new IllegalStateException("缺少 intent 字段。它是 7.2「Top-1 意图准确率」的"
                    + " ground truth，必须人工标注（不允许让模型生成 —— 那等于模型给自己打分）。"
                    + " 取值必须是 data/agent/intent-tree.yml 里的某个叶子 code，"
                    + "例如 SPEC_QUERY / PRICE_PROTECTION / RETURN_EXCHANGE");
        }

        // ★ 工具题的用户身份（阶段 7 批次 5）。
        //
        //   它【只】在 retrieval=TOOL 的题上有意义 —— 跑题器把它写进
        //   X-Xbla-User-Id 头，工具从这里之外的任何地方都拿不到身份（ADR-054）。
        //   ⚠️ 没有值就是 null（不是 0）：0 可能是个合法 id，
        //      而「没写」必须能与「写了 0」区分开。
        Long userId = node.get("user_id") instanceof Number un ? un.longValue() : null;

        return new LoadedQuestion(
                set,
                no,
                question,
                turns,
                standaloneQuestion,
                optionalString(node, "category"),
                node.get("difficulty") instanceof Number n ? n.intValue() : null,
                optionalString(node, "expected_answer"),
                intent,
                List.copyOf(chunkIds),
                source,
                annotatedBy,
                userId,
                expectNoRetrieval);
    }

    /**
     * 按 {@code question_no} upsert（表上有唯一约束，反复调用幂等）。
     *
     * <p>★★ {@code source} / {@code annotated_by} 现在<b>从文件里读</b>，
     * 不再硬编码 —— 这是阶段 7 修掉的一个静默错误：
     *
     * <pre>
     *   原来：entity.setSource("reverse_constructed");
     *         entity.setAnnotatedBy("阶段4-反向构题，阶段5.1-标注意图");
     *   后果：阶段 7 那 150 道【不是】反向构造的题，会带着阶段 4 的出身进库。
     *         ★ 而这两列正是报告里用来声明「这批题有已知偏差」的字段 ——
     *         写错了它，报告会用一个过期的理由为一批新数据背书。
     * </pre>
     *
     * @return true = 新增，false = 更新
     */
    private boolean upsert(LoadedQuestion q) {
        EvalQuestion existing = evalQuestionMapper.selectOne(
                Wrappers.<EvalQuestion>lambdaQuery().eq(EvalQuestion::getQuestionNo, q.questionNo()));

        EvalQuestion entity = existing != null ? existing : new EvalQuestion();
        entity.setQuestionSet(q.questionSet());
        entity.setQuestionNo(q.questionNo());
        entity.setQuestion(q.question());
        entity.setIntent(q.intent());
        entity.setExpectedAnswer(q.expectedAnswer());
        entity.setExpectedChunkIds(q.expectedChunkIds().toArray(new Long[0]));
        entity.setCategory(q.category());
        entity.setDifficulty(q.difficulty());
        entity.setSource(q.source());
        entity.setAnnotatedBy(q.annotatedBy());
        // ★ 这两个是阶段 7 批次 5 加的（V12）：工具题的身份 + 空 gold 声明。
        //   ⚠️ 用 setter 显式写，不依赖列 DEFAULT —— 默认值只在
        //   「有人手写 SQL 插入」时才起作用，而那时它应该拦住人（见 V12）。
        entity.setUserId(q.userId());
        entity.setExpectNoRetrieval(q.expectNoRetrieval());
        // ★ 多轮那两列（V13）：单轮题写 null —— 数据库有一条 CHECK 要求这两列
        //   「同生同死」，所以这里必须【一起】写，不能只写其中一个。
        //   ⚠️ 空列表要变成 null 而不是 "[]"：`[]` 是合法的 JSON 数组，
        //   但它 jsonb_array_length = 0，会被那条 CHECK 挡下来。
        entity.setTurns(q.isMultiTurn() ? writeTurnsJson(q.turns()) : null);
        entity.setStandaloneQuestion(q.standaloneQuestion());
        entity.setAnnotatedAt(OffsetDateTime.now());

        if (existing == null) {
            evalQuestionMapper.insert(entity);
            return true;
        }
        evalQuestionMapper.updateById(entity);
        return false;
    }

    /**
     * 解析 {@code turns} —— 单轮题没有这个字段，返回空列表（<b>不是 null</b>）。
     *
     * <p>它把「只有一轮」「空字符串」这两种写法都变成报错，因为两者都会<b>静默</b>：
     * 前者让多轮数字被单轮数据稀释，后者让跑题器发一句空问题，
     * 而那句空问题失败后会被记成「模型不行」。
     */
    private static List<String> parseTurns(Object turnsNode) {
        if (turnsNode == null) {
            return List.of();
        }
        if (!(turnsNode instanceof List<?> raw)) {
            throw new IllegalStateException("turns 必须是一个列表（每一行是一轮用户提问），"
                    + "实际是 " + turnsNode.getClass().getSimpleName());
        }
        if (raw.size() < 2) {
            throw new IllegalStateException("turns 只有 " + raw.size() + " 轮。"
                    + "★ 多轮题至少要两轮 —— 只有一轮的那不是多轮题，"
                    + "是一道写法绕了点儿的【单轮题】，请写进单轮题库"
                    + "（放进这一套会让它的数字被当成「多轮能力」读）");
        }
        List<String> turns = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Object turn = raw.get(i);
            if (!(turn instanceof String s) || s.isBlank()) {
                throw new IllegalStateException("turns 第 " + (i + 1) + " 轮不是一句非空的提问："
                        + turn + "。⚠️ 多写一个空的 \"-\" 会让跑题器发一句空问题，"
                        + "而它必然失败 —— 那个失败在报告里长得和「模型不行」一模一样");
            }
            turns.add(s);
        }
        return List.copyOf(turns);
    }

    /**
     * 白名单校验 —— <b>不认识的字段直接失败</b>。
     *
     * <p>为什么值得为「多写了一个字段」就崩一次，见 {@link #QUESTION_KEYS} 的说明。
     * 一句话：宽松模式下，拼错的字段名（{@code difficlty}）永远不会被发现，
     * 而它的症状是「注释写得很对、数据却是空的」。
     */
    private static void requireKnownKeys(Map<String, Object> node, Set<String> known,
                                         String where) {
        List<String> unknown = node.keySet().stream()
                .filter(k -> !known.contains(k))
                .toList();
        if (unknown.isEmpty()) {
            return;
        }
        // ★ 对最危险的那个未知字段给出【专门的】解释，而不是一句通用的「不认识」——
        //   session_no 是唯一一个「写进去之后功能看起来还在工作」的字段。
        String hint = unknown.contains("session_no")
                ? " ★ session_no 尤其不能写：会话由【跑题器】在每次 repeat 时新建，"
                  + "写进题库会让同一个会话被反复复用 → repeat 之间串味（阶段 7 的 R7），"
                  + "而症状是「多轮指标莫名其妙地好」——它看起来像好消息。"
                : "";
        throw new IllegalStateException(where + "出现了不认识的字段 " + unknown
                + "。允许的字段：" + known.stream().sorted().toList()
                + "。★ 加载器对未知字段报错（而不是忽略）是刻意的："
                + "写错的字段名在宽松模式下永远不会被发现。" + hint);
    }

    /** 把轮次写成 JSON —— 与 {@code qa_log.retrieval_detail} 同一条约定（JSONB 存、String 进出） */
    private static String writeTurnsJson(List<String> turns) {
        try {
            return JSON.writeValueAsString(turns);
        } catch (JsonProcessingException e) {
            // 一个 List<String> 序列化失败只可能是环境坏了，不是数据问题
            throw new IllegalStateException("turns 序列化成 JSON 失败：" + e.getMessage(), e);
        }
    }

    private static String requireString(Map<String, Object> node, String key, List<String> problems) {
        String value = optionalString(node, key);
        if (value == null) {
            problems.add("缺少 " + key + " 字段");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> node, String key) {
        Object value = node.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
