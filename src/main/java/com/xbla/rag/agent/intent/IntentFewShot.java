package com.xbla.rag.agent.intent;

import com.xbla.rag.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 意图分类的<b>少样本示例</b>：{@code data/agent/intent-fewshot.yml} 的读取器。
 *
 * <h2>一、★ 它为什么不能和意图树里的 {@code examples} 合并</h2>
 *
 * <p>动工 5.2 前对了一遍：{@code intent-tree.yml} 里那 55 条 {@code examples}，
 * 和 20 道评测题<b>有 18 条逐字相同</b>。
 *
 * <pre>
 *   B-001 退货要几天         ← RETURN_EXCHANGE 的示例就是这一句
 *   B-009 优惠券怎么用       ← COUPON 的示例
 *   B-011 价格保护怎么申请   ← PRICE_PROTECTION 的示例
 *   B-017 这个适合送长辈吗   ← SCENARIO_PICK 的示例
 *   ……（干净的只剩 B-014 / B-015）
 * </pre>
 *
 * <p>成因是写树的时候为了「真实、有代表性」，直接从语料和已有的题里挑句子。
 * 后果是：如果拿那 55 条当 few-shot，再拿 20 题测准确率，
 * 测的就是<b>「模型能不能把 prompt 里刚看过的句子照抄回来」</b>，
 * 而不是「能不能分类」—— 整个 5.2 的验收会失去意义。
 *
 * <p>根因是<b>一个字段背了两个职责</b>：「给人看的文档」要用典型问法，
 * 「给模型的样本」不能用考题。而「典型」和「不在考题里」在只有 20 道题的
 * 现状下几乎冲突。所以拆成两份文件，各司其职。
 *
 * <h2>二、★ 三种失败语义</h2>
 *
 * <table border="1">
 *   <caption>失败语义</caption>
 *   <tr><th>情况</th><th>行为</th><th>理由</th></tr>
 *   <tr><td>文件不存在</td><td><b>抛异常，应用起不来</b></td>
 *       <td>和意图树一致。没有样本的分类 prompt 会静默地变差，<b>而且看不出来</b></td></tr>
 *   <tr><td>格式错误、code 不存在、样本重复</td><td><b>抛异常</b></td>
 *       <td>写错了就该停下来。一次报出全部问题</td></tr>
 *   <tr><td>某个分类目标<b>没有样本</b></td><td><b>抛异常</b></td>
 *       <td>★ 这是本类最严的一条：见下</td></tr>
 * </table>
 *
 * <h3>★ 为什么「某个目标没样本」是致命的，而不是 WARN</h3>
 *
 * <p>它符合这个项目的通用判据：<b>失败的结果是不是比停下来更差。</b>
 * 没有样本的目标不会被跳过 —— 它会以「只有一句干巴巴的 description」
 * 的形式留在候选列表里。于是模型在它和相邻目标之间的判断力下降，
 * 准确率掉一点，而<b>没有任何信号告诉你为什么</b>。
 * 这和语料清单「文件没声明就静默用兜底 doc_type」是同一类错误。
 *
 * <p>而修它的成本只有几行 YAML —— 停下来的代价明显更小。
 *
 * <h2>三、它和评测集的不相交性怎么保证</h2>
 *
 * <p>本类<b>不</b>读评测集（那会让 agent 层依赖 rag/eval 层的数据路径）。
 * 这条约束由 {@code IntentFewShotTest} 强制：它同时读两个文件，
 * 断言这里的每一句都不等于 {@code baseline-questions.yml} 里的任何一道题。
 *
 * <p>7.1 扩到 150 题时新题可能撞上这里的某一句 —— 撞了测试会变红，
 * 并告诉你撞的是哪一句。这正是把纪律做成断言的价值：
 * 树里那份 {@code examples} 每扩一次题就要人工重查一遍，这份不用。
 */
@Component
public class IntentFewShot {

    private static final Logger log = LoggerFactory.getLogger(IntentFewShot.class);

    private final Path path;
    private final IntentTree intentTree;

    /** 当前生效的样本。{@code volatile} —— 会被热加载线程整体替换，读方无锁 */
    private volatile Samples cached;

    /** 上面那份样本对应的文件修改时间，用来判断要不要重读 */
    private volatile FileTime cachedMtime;

    /** 最近一次热加载失败的原因。为 null 表示一切正常。给调试探针用 */
    private volatile String lastRefreshError;

    public IntentFewShot(AgentProperties properties, IntentTree intentTree) {
        this.path = Path.of(properties.getIntent().getFewShotPath());
        this.intentTree = intentTree;
    }

    /**
     * 加载结果。
     *
     * @param byIntent 分类目标 code → 样本问题列表。<b>保持文件里的书写顺序</b>，
     *                 这样 prompt 是稳定可复现的
     */
    public record Samples(Map<String, List<String>> byIntent) {

        public Samples {
            byIntent = Map.copyOf(byIntent);
        }

        /** 某个目标的样本。没有时返回空列表（调用方不该依赖它 —— 加载时已经校验过每个目标都有） */
        public List<String> forIntent(String code) {
            return byIntent.getOrDefault(code, List.of());
        }

        public int totalQuestions() {
            return byIntent.values().stream().mapToInt(List::size).sum();
        }
    }

    /**
     * 启动时加载一次 —— 和意图树一样，畸形配置要在<b>启动阶段</b>就炸掉。
     *
     * <p>这里不吞异常：抛出去会让 bean 创建失败，Spring 随之终止启动。
     */
    @PostConstruct
    void loadOnStartup() {
        Samples samples = reload();
        log.info("意图少样本已加载：{} 个分类目标、共 {} 句（{}）",
                samples.byIntent().size(), samples.totalQuestions(),
                path.toAbsolutePath());
    }

    /**
     * 取当前生效的样本。加载失败时<b>沿用上一次的成功版本</b>并打 ERROR。
     *
     * <p><b>★ 与意图树一样支持热加载</b>（按文件修改时间判断），
     * 这一点是刻意的 —— 调样本是 5.3 最频繁的动作：
     * 「改一句样本 → 重跑模糊题批次 → 看命中率」。不给热加载的话，
     * 每改一次都要等应用重启（约 50 秒），这个循环根本跑不起来。
     *
     * <p>失败语义与 {@code IntentTree} 完全一致：<b>启动时畸形直接崩，
     * 运行中畸形沿用旧版本并留痕</b>。理由见那边的类注释。
     */
    public Samples get() {
        Samples current = cached;
        FileTime mtime = readMtimeQuietly();
        if (current != null && mtime != null && mtime.equals(cachedMtime)) {
            return current;
        }
        return reload();
    }

    private synchronized Samples reload() {
        FileTime mtime = readMtimeQuietly();
        if (cached != null && mtime != null && mtime.equals(cachedMtime)) {
            return cached;
        }
        try {
            Samples fresh = load();
            cachedMtime = mtime;
            lastRefreshError = null;
            return fresh;
        } catch (RuntimeException e) {
            if (cached == null) {
                throw e;   // 启动阶段：没有可用的样本，只能抛
            }
            // 运行阶段：沿用旧样本，留痕，并记下 mtime 免得每次请求都重试刷屏
            cachedMtime = mtime;
            lastRefreshError = e.getMessage();
            log.error("★ 意图少样本热加载失败，继续沿用上一次加载成功的版本（{}）。"
                    + "修好文件后会自动重试，无需重启。原因：{}",
                    path.toAbsolutePath(), e.getMessage(), e);
            return cached;
        }
    }

    private FileTime readMtimeQuietly() {
        try {
            return Files.isRegularFile(path) ? Files.getLastModifiedTime(path) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 最近一次热加载失败的原因，正常时为 {@code null}。调试探针会把它报出来 */
    public String lastRefreshError() {
        return lastRefreshError;
    }

    /**
     * 解析并校验。
     *
     * <p><b>不缓存</b>：只在启动时和调试探针里调用，不在请求路径上
     * （{@code IntentPromptBuilder} 拿的是 {@link #get()} 的缓存结果）。
     *
     * @throws IllegalStateException 文件不存在、YAML 语法错误、或校验不通过
     */
    public Samples load() {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                    "★ 意图少样本文件不存在：" + path.toAbsolutePath()
                            + "\n  它是 5.2 分类 prompt 的必需件 —— 没有它，prompt 只剩"
                            + "每个目标一句干巴巴的判据，"
                            + "而准确率会静默下降（没有任何信号）。"
                            + "\n  请检查 xbla.agent.intent.few-shot-path 配置。");
        }

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(path)) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取意图少样本失败：" + path.toAbsolutePath(), e);
        } catch (YAMLException e) {
            throw new IllegalStateException(
                    "意图少样本的 YAML 语法错误：" + path.toAbsolutePath() + "\n  " + e.getMessage(), e);
        }

        if (root == null || !(root.get("samples") instanceof List<?> rawSamples)) {
            throw new IllegalStateException(
                    "意图少样本格式错误，顶层缺少 samples 列表：" + path.toAbsolutePath());
        }

        // 分类目标集合 —— 校验 code 合法性、以及「每个目标都有样本」都靠它
        Set<String> validCodes = new LinkedHashSet<>();
        for (IntentTree.ClassificationTarget target
                : intentTree.get().classificationTargets()) {
            validCodes.add(target.code());
        }

        Map<String, List<String>> byIntent = new LinkedHashMap<>();
        Map<String, String> seenQuestions = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        for (Object item : rawSamples) {
            if (!(item instanceof Map<?, ?> node)) {
                problems.add("samples 里有一项不是键值对：" + item);
                continue;
            }

            String code = trimToNull(node.get("intent"));
            if (code == null) {
                problems.add("有一条记录缺少 intent 字段");
                continue;
            }
            if (!validCodes.contains(code)) {
                problems.add("[" + code + "] 不是合法的分类目标 code。"
                        + "它必须是意图树里的一个叶子，或者是 retrieval 为 TOOL/NONE 的顶层。"
                        + "合法值：" + validCodes);
                continue;
            }
            if (byIntent.containsKey(code)) {
                problems.add("[" + code + "] 在文件里出现了两次（同一目标的样本要合并到一处）");
                continue;
            }

            if (!(node.get("questions") instanceof List<?> rawQuestions) || rawQuestions.isEmpty()) {
                problems.add("[" + code + "] 缺少 questions 列表（或它是空的）");
                continue;
            }

            List<String> questions = new ArrayList<>();
            for (Object q : rawQuestions) {
                String text = trimToNull(q);
                if (text == null) {
                    problems.add("[" + code + "] questions 里有一项是空白");
                    continue;
                }
                // ★ 跨目标重复也要报：同一句话教给两个类别，
                //   等于给模型一个自相矛盾的样本，它只会学糊
                String previous = seenQuestions.putIfAbsent(text, code);
                if (previous != null) {
                    problems.add("样本「" + text + "」重复出现（在 [" + previous + "] 和 ["
                            + code + "]）。同一句话不能同时是两个类别的示例");
                    continue;
                }
                questions.add(text);
            }
            if (questions.isEmpty()) {
                continue;
            }
            byIntent.put(code, List.copyOf(questions));
        }

        // ★ 每个分类目标都必须有样本 —— 见类注释「为什么是致命的」
        List<String> uncovered = validCodes.stream()
                .filter(code -> !byIntent.containsKey(code))
                .toList();
        if (!uncovered.isEmpty()) {
            problems.add("以下分类目标没有任何少样本：「" + String.join("」「", uncovered)
                    + "」。它们会以「只有一句 description」的形式留在候选列表里，"
                    + "模型在它们和相邻目标之间的判断力下降，而准确率掉一点时"
                    + "没有任何信号。请为每个目标至少写 2 句真实问法");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("★ 意图少样本文件有 " + problems.size() + " 处问题：\n  "
                    + String.join("\n  ", problems) + "\n文件：" + path.toAbsolutePath());
        }

        Samples samples = new Samples(byIntent);
        cached = samples;
        return samples;
    }

    public Path path() {
        return path;
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
