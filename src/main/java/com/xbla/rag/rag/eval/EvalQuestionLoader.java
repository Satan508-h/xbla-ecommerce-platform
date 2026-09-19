package com.xbla.rag.rag.eval;

import com.xbla.rag.entity.EvalQuestion;
import com.xbla.rag.mapper.EvalQuestionMapper;
import com.xbla.rag.mapper.KbChunkMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import java.util.List;
import java.util.Map;

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

    /** 默认读取的评测集文件（相对项目根目录） */
    public static final String DEFAULT_PATH = "data/eval/baseline-questions.yml";

    /**
     * {@code eval_question.intent} 的占位值。
     *
     * <p>那一列是 NOT NULL，而意图树的定义属于阶段 5。
     * 这里<b>不复用 doc_type 的词表</b> —— 那会把「用户想问什么」
     * 和「文档是什么类型」两个正交概念混在一起。
     * 写哨兵值，阶段 5 回填时 {@code WHERE intent = 'UNCLASSIFIED'} 一眼能查出剩多少条。
     */
    public static final String INTENT_PLACEHOLDER = "UNCLASSIFIED";

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
     */
    public record LoadedQuestion(
            String questionNo,
            String question,
            String category,
            Integer difficulty,
            String expectedAnswer,
            List<Long> expectedChunkIds) {
    }

    /**
     * 加载结果。
     *
     * @param questions 解析好的题目（含 ground truth）
     * @param inserted  新插入条数
     * @param updated   更新条数
     */
    public record Result(List<LoadedQuestion> questions, int inserted, int updated) {
    }

    /** 从默认路径加载 */
    public Result reload() {
        return reload(Path.of(DEFAULT_PATH));
    }

    /**
     * 从指定文件加载并 upsert 进 {@code eval_question}。
     *
     * <p>按 {@code question_no}（表上已有唯一约束）做 upsert：
     * 已存在就更新，不存在就插入。这样反复调用是幂等的，
     * 而且改了 YAML 里的问题描述后重跑能生效。
     *
     * @throws IllegalStateException 文件不存在、格式错误、或某个锚点解析不出唯一结果
     */
    @SuppressWarnings("unchecked")
    public Result reload(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("评测集文件不存在：" + path.toAbsolutePath()
                    + "（它是 git 跟踪的，检查是不是在项目根目录下运行）");
        }

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(path)) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取评测集失败：" + e.getMessage(), e);
        }
        if (root == null || !(root.get("questions") instanceof List<?> rawQuestions)) {
            throw new IllegalStateException("评测集格式错误：顶层缺少 questions 列表");
        }

        List<LoadedQuestion> loaded = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        for (Object item : rawQuestions) {
            Map<String, Object> node = (Map<String, Object>) item;
            String no = requireString(node, "question_no", problems);
            if (no == null) {
                continue;
            }
            try {
                loaded.add(parseOne(node, no));
            } catch (RuntimeException e) {
                // ★ 收集所有问题后一次性抛出，而不是遇到第一个就停 ——
                //   改评测集时通常一次改好几处，逐条报错要来回跑很多遍
                problems.add("[" + no + "] " + e.getMessage());
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "★ 评测集加载失败，共 " + problems.size() + " 处问题（锚点必须唯一命中）：\n  "
                            + String.join("\n  ", problems));
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

        log.info("★ 评测集加载完成：共 {} 题（新增 {} / 更新 {}）", loaded.size(), inserted, updated);
        return new Result(loaded, inserted, updated);
    }

    @SuppressWarnings("unchecked")
    private LoadedQuestion parseOne(Map<String, Object> node, String no) {
        String question = requireString(node, "question", new ArrayList<>());
        if (question == null) {
            throw new IllegalStateException("缺少 question 字段");
        }

        Object anchorsNode = node.get("anchors");
        if (!(anchorsNode instanceof List<?> anchors) || anchors.isEmpty()) {
            throw new IllegalStateException("缺少 anchors 列表");
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

        List<Long> chunkIds = new ArrayList<>();
        for (Object anchorItem : anchors) {
            String anchor = String.valueOf(((Map<String, Object>) anchorItem).get("text"));
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

        return new LoadedQuestion(
                no,
                question,
                optionalString(node, "category"),
                node.get("difficulty") instanceof Number n ? n.intValue() : null,
                optionalString(node, "expected_answer"),
                List.copyOf(chunkIds));
    }

    /** @return true = 新增，false = 更新 */
    private boolean upsert(LoadedQuestion q) {
        EvalQuestion existing = evalQuestionMapper.selectOne(
                Wrappers.<EvalQuestion>lambdaQuery().eq(EvalQuestion::getQuestionNo, q.questionNo()));

        EvalQuestion entity = existing != null ? existing : new EvalQuestion();
        entity.setQuestionNo(q.questionNo());
        entity.setQuestion(q.question());
        entity.setIntent(INTENT_PLACEHOLDER);
        entity.setExpectedAnswer(q.expectedAnswer());
        entity.setExpectedChunkIds(q.expectedChunkIds().toArray(new Long[0]));
        entity.setCategory(q.category());
        entity.setDifficulty(q.difficulty());
        entity.setIsBaseline(true);
        entity.setSource("reverse_constructed");
        entity.setAnnotatedBy("阶段4-从语料反向构造");
        entity.setAnnotatedAt(OffsetDateTime.now());

        if (existing == null) {
            evalQuestionMapper.insert(entity);
            return true;
        }
        evalQuestionMapper.updateById(entity);
        return false;
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
