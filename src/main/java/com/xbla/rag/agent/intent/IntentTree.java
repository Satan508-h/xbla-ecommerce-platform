package com.xbla.rag.agent.intent;

import com.xbla.rag.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 分层意图树：{@code data/agent/intent-tree.yml} 的读取器。
 *
 * <h2>一、它是什么</h2>
 *
 * <p>把「用户到底在问什么」定义成一棵树（5 类业务意图 + 1 个兜底分支），
 * 叶子层承载<b>检索策略</b>。它是阶段 5 的地基：
 * 5.2 拿它拼 prompt、5.3 拿它算置信度、5.4 拿它收窄候选池、
 * 7.1 标注评测题时从它里面取 {@code intent} 的值。
 *
 * <h2>二、★ {@code doc_types} 是「答案全集」，不是「过滤条件」</h2>
 *
 * <p>叶子上声明的 {@code doc_types} 是一个关于语料的<b>事实断言</b>：
 * 「这类问题的答案，在语料里可能出现在这几种文档中」。它允许重叠、
 * 允许多个 —— 同一个 {@code doc_type} 会被多个叶子引用。
 *
 * <p>做成「全集」而不是「主类型」换来的是<b>一个可自动验证的验收标准</b>：
 *
 * <pre>
 *   对评测集的每一题：该题的 gold doc_types ⊆ 该题意图声明的 doc_types
 * </pre>
 *
 * <p>不满足 = 树画错了。这个信号是自动的，不靠人看。
 * 如果只写「5.4 要过滤成哪个类型」，B-011（价格保护，gold 跨 {@code {2,3,4}}）
 * 掉分时就没人能说清是<b>树错了</b>还是<b>过滤太激进</b> —— 两者的修法完全相反。
 *
 * <p>至于 5.4 要不要拿它当 {@code WHERE} 条件、当软权重、还是不用，
 * <b>是另一个决策，由 5.4 自己拍板</b>，不该由这份文件替你回答。
 *
 * <h2>三、★ 三种失败语义（刻意不同）</h2>
 *
 * <table border="1">
 *   <caption>失败语义</caption>
 *   <tr><th>时机</th><th>情况</th><th>行为</th><th>理由</th></tr>
 *   <tr><td rowspan="2">启动时</td><td>文件不存在</td>
 *       <td><b>抛异常，应用起不来</b></td>
 *       <td>意图树没有合理的兜底值。「回落」只能等于把所有问题判成同一类，
 *           那是静默地把系统变成坏的系统。这和语料清单刻意相反 ——
 *           清单的老路径 {@code ?docType=} 还在，有真兜底</td></tr>
 *   <tr><td>内容畸形</td>
 *       <td><b>抛异常，应用起不来</b></td>
 *       <td>写错了就该停下来，而不是带着半棵树跑起来</td></tr>
 *   <tr><td>运行中</td><td>文件被改坏</td>
 *       <td><b>打 ERROR，沿用上一次的好树</b></td>
 *       <td>配置里打错一个字母就让整个问答接口 500，是实打实的可用性故障。
 *           和 {@code RetrievalPipeline} 的「检索失败不影响问答」同一个原则：
 *           能降级就不要把错误冒到用户面前，但必须留痕</td></tr>
 * </table>
 *
 * <h2>四、热加载：缓存 + 修改时间校验</h2>
 *
 * <p>与 {@code CorpusManifest} <b>不缓存</b>的做法不同。清单只在人工触发扫目录时
 * 读一次，几毫秒无所谓；而意图树在<b>每次意图识别时都要读</b>，
 * 一个请求读一次文件说不过去。
 *
 * <p>所以：缓存整棵树，但每次访问先 {@code stat} 一下文件的修改时间，
 * 变了就重新解析。这样阶段 7 做 A/B 时「改一句话术 → 重跑分类」不需要重启应用，
 * 而 {@code stat} 的开销在微秒级，可以忽略。
 *
 * <p>与 {@code EvalQuestionLoader} / {@code CorpusManifest} 同一条原则：
 * <b>能安静漂移的东西必须喊出来。</b>
 */
@Component
public class IntentTree {

    private static final Logger log = LoggerFactory.getLogger(IntentTree.class);

    /** 本加载器认识的 schema 版本。文件里的 {@code version} 对不上就拒绝加载 */
    private static final int SUPPORTED_VERSION = 1;

    /**
     * 期望的业务意图数量。
     *
     * <p>和 {@code docs/10-开发路线图.md} 里「5.1 分层意图树定义（<b>5 类意图</b>）」
     * 以及 7.1「约 150 题，<b>覆盖 5 类意图</b>」是同一份契约。
     *
     * <p>在代码里也钉一遍，是为了让「分类体系悄悄变成 6 类」这件事
     * <b>在启动时就暴露</b>，而不是等到 7.2 算指标时才发现分母对不上。
     * 和 {@code CorpusManifest} 把 {@code doc_type} 的 1~5 抄一遍是同一个手法。
     *
     * <p>⚠️ 真要改成 6 类，改这个常量的同时必须一起改 {@code docs/10} 和
     * {@code docs/06} 里的指标定义 —— 这是刻意的摩擦。
     */
    private static final int EXPECTED_BUSINESS_INTENTS = 5;

    /** 意图码的格式：大写下划线。7.1 标注时抄的就是它，格式必须严格 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    private static final int MIN_DOC_TYPE = 1;
    private static final int MAX_DOC_TYPE = 5;

    private final Path treePath;

    /** 当前生效的树。{@code volatile} —— 会被热加载线程整体替换，读方无锁 */
    private volatile Tree cached;

    /** 上面那棵树对应的文件修改时间，用来判断要不要重读 */
    private volatile FileTime cachedMtime;

    /** 最近一次热加载失败的原因。为 null 表示一切正常。给调试探针用 */
    private volatile String lastRefreshError;

    /**
     * ★ 这个 {@code @Autowired} 不能删。
     *
     * <p>本类有<b>两个构造函数</b>（下面还有一个给测试用的）。
     * Spring 的构造器注入规则是：<b>只有一个构造函数时可以省略注解；
     * 有两个及以上时，必须显式标出用哪一个</b> ——
     * 否则它会退回去找无参构造函数，然后报
     * {@code No default constructor found}。
     *
     * <p>这个坑单测发现不了：单测直接 {@code new IntentTree(path)}，
     * 压根不走 Spring 的实例化路径，所以 26 个单测全绿也照样会踩。
     * 它说明了一件事 —— <b>单元测试通过不等于应用能起来</b>，
     * 涉及 Spring 装配的改动必须真启动一次。
     */
    @Autowired
    public IntentTree(AgentProperties properties) {
        this.treePath = Path.of(properties.getIntentTreePath());
    }

    /**
     * 供测试直接指定路径用，绕过 Spring。
     *
     * <p>单测要覆盖「文件不存在」「version 对不上」「doc_types 越界」这些
     * 失败分支，起一个 Spring 上下文来测一个纯解析函数不划算。
     */
    IntentTree(Path treePath) {
        this.treePath = treePath;
    }

    /**
     * 启动时加载一次，让畸形配置<b>在应用启动阶段就炸掉</b>，而不是等第一个用户提问。
     *
     * <p>这里不吞异常 —— 抛出去会让这个 bean 创建失败，Spring 随之终止启动。
     */
    @PostConstruct
    void loadOnStartup() {
        Tree tree = reload();
        java.util.List<String> extras = new ArrayList<>();
        if (tree.outOfScope().isPresent()) {
            extras.add("兜底");
        }
        if (tree.clarify().isPresent()) {
            extras.add("澄清");
        }
        log.info("意图树已加载：{} 类业务意图 + {}，{} 个叶子，{} 个分类目标（{}）",
                tree.businessIntents().size(),
                extras.isEmpty() ? "无非业务分支" : String.join(" / ", extras) + "分支",
                tree.leafCount(),
                tree.classificationTargets().size(),
                treePath.toAbsolutePath());
    }

    /**
     * 取当前生效的意图树。这是所有调用方的唯一入口。
     *
     * <p>内部会先比对文件修改时间：没变就直接返回缓存，变了就重新解析。
     */
    public Tree get() {
        Tree current = cached;
        FileTime mtime = readMtimeQuietly();
        if (current != null && mtime != null && mtime.equals(cachedMtime)) {
            return current;
        }
        return reload();
    }

    /** 最近一次热加载失败的原因，正常时为 {@code null}。调试探针会把它报出来 */
    public String lastRefreshError() {
        return lastRefreshError;
    }

    public Path path() {
        return treePath;
    }

    /**
     * 重新解析并替换缓存。
     *
     * <p><b>加锁</b>：并发的第一个请求都会走到这里，不加锁会让同一个文件被解析 N 次。
     * 进锁后<b>再查一次</b>修改时间 —— 可能已经有别的线程刷完了。
     */
    private synchronized Tree reload() {
        FileTime mtime = readMtimeQuietly();
        if (cached != null && mtime != null && mtime.equals(cachedMtime)) {
            return cached;
        }

        try {
            Tree fresh = parse(treePath);
            cached = fresh;
            cachedMtime = mtime;
            lastRefreshError = null;
            return fresh;
        } catch (RuntimeException e) {
            if (cached == null) {
                // 启动阶段：没有可用的树，只能抛
                throw e;
            }
            // 运行阶段：沿用旧树，但必须留痕，且把 mtime 记下来避免每次请求都重试+刷屏
            cachedMtime = mtime;
            lastRefreshError = e.getMessage();
            log.error("★ 意图树热加载失败，继续沿用上一次加载成功的版本（{}）。"
                    + "修好文件后会自动重试，无需重启。原因：{}",
                    treePath.toAbsolutePath(), e.getMessage(), e);
            return cached;
        }
    }

    private FileTime readMtimeQuietly() {
        try {
            return Files.isRegularFile(treePath) ? Files.getLastModifiedTime(treePath) : null;
        } catch (IOException e) {
            // 读不到修改时间（权限、文件被占用）不该致命：当作「没变」，
            // 继续用缓存。真的读不了文件时，下面的 parse 会给出真正的错误
            return null;
        }
    }

    // ================================================================
    // 解析与校验
    // ================================================================

    /**
     * 纯函数：把一个 YAML 文件解析成意图树，不合法就抛异常。
     *
     * <p>包级可见 + static，是为了让单测能直接喂各种畸形输入，
     * 不需要起 Spring，也不需要碰缓存状态。
     *
     * <p><b>一次报出所有问题</b>，而不是遇到第一个就停 ——
     * 改 YAML 的人希望一轮就把该修的修完，而不是修一个报一个。
     */
    static Tree parse(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                    "★ 意图树文件不存在：" + path.toAbsolutePath()
                            + "\n  它是阶段 5 的必需件 —— 没有它就无法做意图识别，"
                            + "也没有合理的兜底值（回落只能等于把所有问题判成同一类）。"
                            + "\n  请检查 xbla.agent.intent-tree-path 配置，"
                            + "并确认应用的工作目录是项目根目录。");
        }

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(path)) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取意图树失败：" + path.toAbsolutePath(), e);
        } catch (YAMLException e) {
            throw new IllegalStateException(
                    "意图树的 YAML 语法错误：" + path.toAbsolutePath() + "\n  " + e.getMessage(), e);
        }

        if (root == null) {
            throw new IllegalStateException("意图树是空文件：" + path.toAbsolutePath());
        }

        List<String> problems = new ArrayList<>();

        // ---------- version ----------
        int version = 0;
        if (root.get("version") instanceof Number number) {
            version = number.intValue();
        }
        if (version != SUPPORTED_VERSION) {
            problems.add("version 必须是 " + SUPPORTED_VERSION + "，实际是 "
                    + root.get("version")
                    + "（本加载器只认识 version " + SUPPORTED_VERSION
                    + " 的 schema。加了新字段就该升版本号，而不是让旧代码静默忽略它）");
        }

        // ---------- intents ----------
        if (!(root.get("intents") instanceof List<?> rawIntents) || rawIntents.isEmpty()) {
            throw new IllegalStateException(
                    "意图树格式错误，顶层缺少非空的 intents 列表：" + path.toAbsolutePath());
        }

        List<TopIntent> roots = new ArrayList<>();
        Set<String> allCodes = new LinkedHashSet<>();
        Map<Role, Integer> roleCounts = new EnumMap<>(Role.class);

        for (Object item : rawIntents) {
            if (!(item instanceof Map<?, ?> node)) {
                problems.add("intents 里有一项不是键值对：" + item);
                continue;
            }
            TopIntent parsed = parseIntent(node, problems, allCodes);
            if (parsed != null) {
                roots.add(parsed);
                roleCounts.merge(parsed.role(), 1, Integer::sum);
            }
        }

        // 非业务角色最多各一个 —— 多了会互相抢，谁生效取决于读的顺序
        for (Role role : List.of(Role.OUT_OF_SCOPE, Role.CLARIFY)) {
            int count = roleCounts.getOrDefault(role, 0);
            if (count > 1) {
                problems.add("role=" + role + " 的分支有 " + count
                        + " 个，最多只能有一个（多个同类分支会互相抢，谁生效取决于读的顺序）");
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("★ 意图树有 " + problems.size() + " 处问题：\n  "
                    + String.join("\n  ", problems) + "\n文件：" + path.toAbsolutePath());
        }

        Tree tree = new Tree(version, roots);

        // 数量校验放在最后：上面已经把单个节点的格式问题报全了，
        // 这时再谈「有几类」才是准确的
        int business = tree.businessIntents().size();
        if (business != EXPECTED_BUSINESS_INTENTS) {
            throw new IllegalStateException("★ 意图树有 " + business + " 类业务意图，但代码期望 "
                    + EXPECTED_BUSINESS_INTENTS + " 类。\n"
                    + "  这条契约来自 docs/10「5.1 分层意图树定义（5 类意图）」"
                    + "和 7.1「约 150 题，覆盖 5 类意图」——\n"
                    + "  7.2 的意图准确率指标的分母直接由它决定，所以不能悄悄变。\n"
                    + "  真要改，请同时修改 IntentTree.EXPECTED_BUSINESS_INTENTS、"
                    + "docs/10 和 docs/06 的指标定义。\n文件：" + path.toAbsolutePath());
        }

        return tree;
    }

    /** 解析一个顶层意图。有问题就记进 {@code problems} 并返回 {@code null} */
    private static TopIntent parseIntent(Map<?, ?> node, List<String> problems, Set<String> allCodes) {
        String code = requireString(node, "code", null, problems);
        if (code != null) {
            if (!CODE_PATTERN.matcher(code).matches()) {
                problems.add("[" + code + "] code 格式不合法 —— "
                        + "必须是「大写字母开头 + 大写字母/数字/下划线」，例如 PRICE_PROTECTION");
                code = null;
            } else if (!allCodes.add(code)) {
                problems.add("[" + code + "] 这个 code 在树里出现了两次（全树的 code 必须唯一）");
                code = null;
            }
        }

        String name = requireString(node, "name", code, problems);
        String description = requireString(node, "description", code, problems);
        String answerStyle = requireString(node, "answer_style", code, problems);

        // ---------- role ----------
        Role role = Role.BUSINESS;
        Object rawRole = node.get("role");
        if (rawRole != null) {
            try {
                role = Role.valueOf(String.valueOf(rawRole).trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                problems.add(prefix(code) + "role=" + rawRole
                        + " 不是合法值，只能是 BUSINESS / OUT_OF_SCOPE / CLARIFY");
            }
        }

        Retrieval retrieval = null;
        Object rawRetrieval = node.get("retrieval");
        if (rawRetrieval == null) {
            problems.add(prefix(code) + "缺少 retrieval 字段（可选值："
                    + "KB 知识库 / TOOL 实时工具 / NONE 不检索）");
        } else {
            try {
                retrieval = Retrieval.valueOf(String.valueOf(rawRetrieval).trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                problems.add(prefix(code) + "retrieval=" + rawRetrieval
                        + " 不是合法值，只能是 KB / TOOL / NONE");
            }
        }

        // ---------- children ----------
        List<Leaf> children = new ArrayList<>();
        Object rawChildren = node.get("children");
        if (rawChildren != null && !(rawChildren instanceof List<?>)) {
            problems.add(prefix(code) + "children 必须是列表");
            rawChildren = null;
        }
        if (rawChildren instanceof List<?> childNodes) {
            for (Object childItem : childNodes) {
                if (!(childItem instanceof Map<?, ?> childNode)) {
                    problems.add(prefix(code) + "children 里有一项不是键值对：" + childItem);
                    continue;
                }
                Leaf leaf = parseLeaf(childNode, code, retrieval, problems, allCodes);
                if (leaf != null) {
                    children.add(leaf);
                }
            }
        }

        // ---------- 组合层面的约束 ----------
        if (retrieval == Retrieval.KB && children.isEmpty()) {
            problems.add(prefix(code)
                    + "retrieval=KB 但没有任何子意图 —— 分类只能落到叶子，"
                    + "顶层没有 doc_types 可查，等于这类问题永远检索不到东西");
        }
        if (role != null && role != Role.BUSINESS) {
            if (retrieval != null && retrieval != Retrieval.NONE) {
                problems.add(prefix(code)
                        + "role=" + role + " 却又声明了 retrieval=" + retrieval
                        + " —— 非业务分支不该去检索任何东西");
            }
            if (!children.isEmpty()) {
                problems.add(prefix(code)
                        + "role=" + role + " 却带了 " + children.size()
                        + " 个子意图 —— 它是一个整体，拆开就不再是它了");
            }
        }

        // ---------- tools（阶段 9.3，可选）----------
        //
        // ★★ 它合法的位置只有一处：【分类的落点】。而落点由 retrieval 决定 ——
        //    KB 类意图分类到叶子（classificationTargets 会展开 children），
        //    TOOL / NONE 类意图分类到顶层（不展开，见那条「行为相同的不区分」）。
        //
        //    所以顶层这一格：【只有 TOOL 类该写】。写在 KB / NONE 上不是
        //    「暂时没生效」，是【永远读不到】—— 没有日志、没有异常，
        //    症状是「我明明配了，模型怎么不用」。
        //    同 structured_facts 那条「声明了一个永远不生效的东西就启动即崩」。
        List<String> tools = parseTools(node, code, problems);
        if (!tools.isEmpty()) {
            if (retrieval != null && retrieval != Retrieval.TOOL) {
                // ⚠️ 这里【刻意】不单独判 role != BUSINESS。理由：
                //   上面已经强制「非业务分支必须是 retrieval=NONE」，
                //   而 NONE 这一支本来就拒绝 tools —— 再写一条 role 分支，
                //   它永远不会命中（前一条先报），是一段看起来有意义的死代码。
                //   实测就是这么发现的：写了 role 分支之后，
                //   「NONE 顶层不许带工具」这条断言永远走不到。
                problems.add(prefix(code) + "retrieval=" + retrieval
                        + " 却在【顶层】声明了 tools=" + tools + " —— "
                        + (retrieval == Retrieval.KB
                        ? "KB 类意图的分类落点是【叶子】，工具清单要写在需要它的那个叶子下"
                        : "NONE 类意图不调任何工具（RetrievalGate 的 NONE 分支"
                                + "【根本不读】工具清单，那是刻意的「不可表达」；"
                                + "role != BUSINESS 的两个分支都属于这一类）"));
            }
        } else if (retrieval == Retrieval.TOOL) {
            problems.add(prefix(code) + "retrieval=TOOL 却没有可用的 tools —— "
                    + "这一类问题的答案【全部】来自实时工具，白名单为空等于这条路必然答不出，"
                    + "而模型会退回去拿知识库里的通用规则编一个出来（ADR-044）");
        }

        if (code == null || name == null || description == null
                || answerStyle == null || retrieval == null) {
            return null;
        }
        return new TopIntent(code, name, description, answerStyle, retrieval, role, children,
                List.copyOf(tools));
    }

    /** 解析一个叶子。有问题就记进 {@code problems} 并返回 {@code null} */
    private static Leaf parseLeaf(Map<?, ?> node, String parentCode, Retrieval parentRetrieval,
                                 List<String> problems, Set<String> allCodes) {
        String code = requireString(node, "code", parentCode, problems);
        if (code != null) {
            if (!CODE_PATTERN.matcher(code).matches()) {
                problems.add("[" + parentCode + " > " + code + "] code 格式不合法，"
                        + "必须是「大写字母开头 + 大写字母/数字/下划线」");
                code = null;
            } else if (!allCodes.add(code)) {
                problems.add("[" + parentCode + " > " + code + "] 这个 code 在树里出现了两次"
                        + "（全树的 code 必须唯一 —— 7.1 标注时只写 code，重名就分不清是哪一类）");
                code = null;
            }
        }

        String name = requireString(node, "name", code, problems);
        String description = requireString(node, "description", code, problems);
        String note = stringOrNull(node.get("note"));

        // doc_types：KB 的叶子必须有且非空；TOOL / NONE 的叶子必须是空（或省略）
        boolean docTypesForbidden = parentRetrieval != null && parentRetrieval != Retrieval.KB;
        List<Integer> docTypes = new ArrayList<>();
        Object rawDocTypes = node.get("doc_types");
        if (rawDocTypes == null) {
            if (!docTypesForbidden) {
                problems.add(prefix(code) + "缺少 doc_types —— "
                        + "它是这类问题「答案可能落在哪些类型」的断言，KB 类意图的叶子必须写");
            }
        } else if (!(rawDocTypes instanceof List<?> rawList)) {
            problems.add(prefix(code) + "doc_types 必须是列表，例如 [2, 4]");
        } else {
            for (Object dt : rawList) {
                if (!(dt instanceof Number number)) {
                    problems.add(prefix(code) + "doc_types 里有非数字项：" + dt);
                    continue;
                }
                int value = number.intValue();
                if (value < MIN_DOC_TYPE || value > MAX_DOC_TYPE) {
                    problems.add(prefix(code) + "doc_types 里的 " + value + " 越界，"
                            + "合法范围 " + MIN_DOC_TYPE + "~" + MAX_DOC_TYPE
                            + "（1商品详情 2售后政策 3促销规则 4FAQ 5说明书）");
                    continue;
                }
                docTypes.add(value);
            }
            if (docTypesForbidden && !docTypes.isEmpty()) {
                problems.add(prefix(code) + "doc_types=" + docTypes + " 但它所属的 "
                        + parentCode + " 是 retrieval=" + parentRetrieval
                        + " —— 走工具兜底的意图不检索知识库，声明 doc_types 是自相矛盾");
            }
        }

        List<String> examples = new ArrayList<>();
        Object rawExamples = node.get("examples");
        if (rawExamples instanceof List<?> list) {
            for (Object ex : list) {
                String text = stringOrNull(ex);
                if (text != null) {
                    examples.add(text);
                }
            }
        }
        if (examples.isEmpty()) {
            problems.add(prefix(code) + "缺少 examples —— "
                    + "5.2 靠它做少样本示例，没有示例的分类准确率会明显下降。"
                    + "至少写一句真实问法");
        }

        // ---------- structured_facts（阶段 5.9，可选）----------
        StructuredFact structuredFact = StructuredFact.NONE;
        Object rawStructuredFact = node.get("structured_facts");
        if (rawStructuredFact != null) {
            try {
                structuredFact = StructuredFact.valueOf(
                        String.valueOf(rawStructuredFact).trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                problems.add(prefix(code) + "structured_facts=" + rawStructuredFact
                        + " 不是合法值，只能是 " + namesOfStructuredFacts());
            }
            // ★ 非 KB 的叶子声明它 = 声明了一个永远不会生效的东西。
            //   走工具那条路根本不进 RagPromptBuilder，而症状是
            //   「我明明在树里配了，怎么没生效」—— 没有日志、没有异常
            if (docTypesForbidden) {
                problems.add(prefix(code) + "声明了 structured_facts，但它所属的 "
                        + parentCode + " 是 retrieval=" + parentRetrieval
                        + " —— 不检索的意图不会组装 prompt，这个声明永远不会生效");
            }
        }

        // ---------- tools（阶段 9.3，可选）----------
        //
        // ★ 叶子上写 tools 只有一个合法场景：**混合轮** —— 这个 KB 叶子
        //   检索完知识库之后，还允许模型调这几个实时工具（今天只有 SCENARIO_PICK）。
        //
        // ⚠️ 父顶层是 TOOL / NONE 的叶子写它【启动即崩】：那两类的分类落在顶层，
        //   叶子上这一格永远读不到。同上面 structured_facts 那条一模一样的理由 ——
        //   「声明了一个不会生效的东西」是配置类错误，没有合理的回落值。
        List<String> tools = parseTools(node, code, problems);
        if (!tools.isEmpty() && docTypesForbidden) {
            problems.add(prefix(code) + "声明了 tools=" + tools + "，但它所属的 "
                    + parentCode + " 是 retrieval=" + parentRetrieval
                    + " —— 那类意图的分类落点是【顶层】，写在叶子上永远读不到");
        }

        if (code == null || name == null || description == null) {
            return null;
        }
        return new Leaf(code, name, description, List.copyOf(docTypes),
                List.copyOf(examples), note, structuredFact, List.copyOf(tools));
    }

    /**
     * 解析一个节点的 {@code tools} 字段（阶段 9.3）。
     *
     * <p>★ 它只校验<b>形状</b>（是不是字符串列表、有没有重复），
     * <b>不校验工具名是否存在</b> —— 那要拿注册表比，而注册表在 {@code mcp} 包。
     * 让意图树依赖注册表会糊掉「意图树只描述用户想干什么」这条边界，
     * 所以那一半在 {@code IntentToolBindingValidator}（它同时看得见两边）。
     *
     * <p>⚠️ 这个分工的代价必须说清楚：<b>只跑 IntentTree 的单测发现不了拼错的工具名</b>。
     * 所以那条校验必须是<b>启动期</b>的，而不是「跑起来之后第一次调到才发现」——
     * 后者会让一个拼写错误安静地活到线上。
     */
    private static List<String> parseTools(Map<?, ?> node, String context, List<String> problems) {
        Object raw = node.get("tools");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            problems.add(prefix(context) + "tools 必须是列表，例如 "
                    + "[search_products, compare_prices]");
            return List.of();
        }
        List<String> tools = new ArrayList<>();
        for (Object item : list) {
            String name = stringOrNull(item);
            if (name == null) {
                problems.add(prefix(context) + "tools 里有空白项：" + item);
                continue;
            }
            if (tools.contains(name)) {
                // ★ 重复不改变行为，但它是复制粘贴留下的痕迹 ——
                //   而复制粘贴正是「两份清单一改一漏」的源头，值得一条报错
                problems.add(prefix(context) + "tools 里 " + name + " 出现了两次");
                continue;
            }
            tools.add(name);
        }
        return List.copyOf(tools);
    }

    /** 报错时列全可选值 —— 写错的枚举值最容易的修法就是照着这句话改 */
    private static String namesOfStructuredFacts() {
        StringBuilder sb = new StringBuilder();
        for (StructuredFact fact : StructuredFact.values()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(fact.name());
        }
        return sb.toString();
    }

    // ================================================================
    // 小工具
    // ================================================================

    private static String requireString(Map<?, ?> node, String field, String context, List<String> problems) {
        String value = stringOrNull(node.get(field));
        if (value == null) {
            problems.add(prefix(context) + "缺少 " + field + " 字段（或它是空白）");
        }
        return value;
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String prefix(String code) {
        return code == null ? "" : "[" + code + "] ";
    }

    // ================================================================
    // 数据模型
    // ================================================================

    /** 检索策略 */
    public enum Retrieval {
        /** 查知识库（阶段 4 的双路召回） */
        KB,
        /** 调 MCP 工具拿实时数据（5.7~5.9） */
        TOOL,
        /** 不检索 —— 非业务分支 */
        NONE
    }

    /**
     * 一类问题要不要在检索之外，<b>额外</b>带一份结构化事实进 prompt（阶段 5.9）。
     *
     * <h2>一、它和 {@link Retrieval} 的区别，以及为什么必须分开</h2>
     *
     * <p>看起来可以把 {@code POLICY} 塞进 {@link Retrieval} 变成第四个值，
     * 但那是把两个正交的概念混在一起：
     * <pre>
     *   Retrieval        「这次去【哪里】取上下文」    —— 互斥的三种方式
     *   StructuredFact   「在取到的上下文之外【再加】什么」—— 可叠加的附加来源
     * </pre>
     * 一次问答完全可以是「<b>既检索，又带一份硬数据</b>」—— 那正是售后政策这一类。
     * 塞进 {@code Retrieval} 就只能二选一了。
     *
     * <h2>★★ 二、为什么是【叶子】粒度，而不是像 {@code retrieval} 那样是顶层粒度</h2>
     *
     * <p>{@code retrieval} 是整类的性质，理由见 {@link Tree#retrievalOf}。
     * 但这件事<b>不是</b>：{@code PROMOTION} 这一整类里，
     * 「价格保护是几天」需要政策硬数据，而「满减怎么算」完全不需要。
     * 按顶层声明只能要么全给、要么全不给。
     *
     * <h2>★★ 三、它【不参与】分类，所以加它不影响 5.2 的准确率</h2>
     *
     * <p>{@link Tree#classificationTargets()} 只看 {@code retrieval}，
     * 所以这个字段<b>不会让分类 prompt 多出一个选项</b> ——
     * 加它对 5.2 的意图准确率和 5.4 的评测基线<b>零影响</b>。
     *
     * <p>⚠️ 但要守住一条：<b>非 {@code KB} 的叶子不能声明它</b>。
     * 走工具的那条路根本不进 {@code RagPromptBuilder}，
     * 声明了也永远不会生效 —— 而那是<b>静默</b>的。加载期直接报错。
     */
    public enum StructuredFact {
        /** 不带（默认） */
        NONE,
        /**
         * 带一份平台政策的<b>结构化字段</b>（{@code after_sale_policy} 表的
         * {@code return_days} / {@code exchange_days}）。
         *
         * <p>★ 只带<b>天数</b>，不带 {@code conditions} —— 后者已经在知识库里
         * （实测：12 条「附加条件」切片），带进 prompt 就是重复。
         * 而天数是 {@code content} 里以<b>散文</b>形式出现的
         * （「自签收之日起 7 天内支持无理由退货，15 天内支持换货」），
         * 模型从散文里读数字可能读错或张冠李戴 —— 那才是这份硬数据的价值。
         */
        POLICY
    }

    /**
     * 顶层意图的<b>角色</b>。三者互斥。
     *
     * <p>用枚举而不是「一个 {@code out_of_scope} 布尔量」，是因为 5.3 引入了第三种角色 ——
     * 两个互斥的布尔量可以同时为真，而那个状态是无意义的、只能靠校验拦住；
     * 枚举让「只能三选一」在类型层面就成立。
     *
     * <p>★ 更要紧的是，这三者的<b>语义差别的确很大</b>，混在一起会让用户拿到错误的回答：
     * <pre>
     *   BUSINESS          用户在问本平台的业务     → 正常检索并回答
     *   OUT_OF_SCOPE      与业务完全无关           → 「我只处理导购和售后问题」（不是我的业务）
     *   CLARIFY           是业务，但这句话没说清   → 「你指的是哪款商品？」（是我的业务，你得再说点）
     * </pre>
     * 把后两者混成一个「兜底」，用户问「那个怎么样」时会收到
     * 「我只处理商品导购与售后问题」—— 而那句话明明就是在问商品。
     */
    public enum Role {
        /** 业务意图。只有这一类计入「5 类意图」，也是 7.2 准确率的分母 */
        BUSINESS,
        /** 与平台业务完全无关（问候、闲聊、帮我写诗） */
        OUT_OF_SCOPE,
        /** 与业务相关，但这句话本身不足以判断他想问什么（指代不明、缺主语、信息太少） */
        CLARIFY
    }

    /**
     * 树的一个叶子。
     *
     * <p>{@code eval_question.intent} 这一列存的就是 {@code code}
     * ——存叶子码而不是顶层码，因为顶层码可以由树 roll-up 出来，
     * 反过来推不出来。只存顶层就永远算不出「价格保护这类题答得怎么样」。
     *
     * @param description 给<b>模型</b>看的判据（分类 prompt 里用它）。
     *                    ⚠️ 和 {@code note} 分工不同：{@code description} 说的是
     *                    「什么算这一类」，{@code note} 说的是「为什么这么设计」
     *                    （比如「doc_type=4 在这里不是笔误」）——
     *                    后者放进 prompt 只会干扰模型
     * @param docTypes    答案可能落在的 doc_type 全集（{@code retrieval != KB} 时为空）
     * @param examples    <b>给人看的</b>典型问法。★ 它<b>不</b>参与分类 prompt ——
     *                    里面有多条与评测题逐字相同，拿它做 few-shot 会让准确率虚高。
     *                    给模型看的样本在 {@code data/agent/intent-fewshot.yml}
     * @param structuredFact ★ 阶段 5.9：这类问题除了检索，<b>还要不要额外带一份
     *                    结构化事实</b>。默认 {@link StructuredFact#NONE}。
     *                    详见那个枚举的注释 —— 关键是它<b>不参与</b>
     *                    {@link Tree#classificationTargets()}，
     *                    所以给叶子加它不会动分类 prompt、不会影响 5.2 的准确率基线
     * @param tools       ★ 阶段 9.3：这个叶子在<b>检索之外</b>还允许模型调用的工具白名单。
     *                    空列表 = 纯知识库问答，这是绝大多数叶子的情形。
     *                    ⚠️ 它只能写在<b>分类落点</b>上 —— KB 意图的分类落点是叶子
     *                    （所以写在 {@code Leaf} 上是对的），TOOL / NONE 意图落点是顶层
     *                    （写在叶子上<b>启动即崩</b>，因为永远读不到）。
     *                    见 {@link Tree#toolsOf}。
     *                    ★ 它是<b>白名单</b>不是「建议」：{@code ToolLoop} 只按它
     *                    <b>过滤</b>，不重排、更不能放大（放大会让工具意图退化成裸聊）。
     */
    public record Leaf(String code, String name, String description, List<Integer> docTypes,
                       List<String> examples, String note,
                       StructuredFact structuredFact, List<String> tools) {

        public Leaf {
            tools = List.copyOf(tools);
        }
    }

    /**
     * 树的一个顶层意图。
     *
     * @param role 见 {@link Role}。<b>只有 {@code BUSINESS} 计入「5 类意图」</b> ——
     *             另外两种在评测集里要么无法定义 ground truth（{@code OUT_OF_SCOPE}
     *             的 {@code expected_chunk_ids} 只能是空集），要么根本不是「意图」
     *             （{@code CLARIFY} 是问题质量的问题，不是用户想要什么）。
     *             混在一起会让 7.2 的指标承载性质相反的错
     * @param tools ★ 阶段 9.3：这一类的工具白名单。<b>只有 {@code retrieval = TOOL}
     *              的顶层该有它，而且必须有</b> —— 见 {@code parseIntent} 里那两条校验。
     *              ⚠️ {@code retrieval = KB} 的顶层写它<b>启动即崩</b>：KB 类的分类落点是
     *              叶子（{@link Tree#classificationTargets()}），顶层这一格永远读不到。
     */
    public record TopIntent(String code, String name, String description, String answerStyle,
                            Retrieval retrieval, Role role, List<Leaf> children,
                            List<String> tools) {

        public TopIntent {
            children = List.copyOf(children);
            tools = List.copyOf(tools);
        }

        /**
         * 这个顶层意图下所有叶子的 {@code doc_types} 并集。
         *
         * <p><b>不是</b> 5.4 的过滤条件 —— 5.4 用的是叶子的 {@code docTypes}
         * （叶子才是分类的落点）。这个并集是给「看全局」用的：
         * 调试探针报它，方便一眼看出「售后这一整类会碰到哪些 doc_type」，
         * 以及发现「某类意图的并集覆盖了全部 5 种类型」这种等于没过滤的情况。
         */
        public Set<Integer> docTypesUnion() {
            Set<Integer> union = new LinkedHashSet<>();
            for (Leaf leaf : children) {
                union.addAll(leaf.docTypes());
            }
            return union;
        }
    }

    /**
     * 加载好的一棵完整的树。
     *
     * <p>查找方法是<b>线性扫描</b>：树的规模是 6 个顶层 × 十来个叶子，
     * 一次请求最多查一次。为它建索引的复杂度换不来可测量的收益，
     * 反而多一处要先构建、要维护、可能失效的状态。
     */
    public record Tree(int version, List<TopIntent> roots) {

        public Tree {
            roots = List.copyOf(roots);
        }

        /** 5 类业务意图。7.2 的意图准确率分母由它决定 */
        public List<TopIntent> businessIntents() {
            return roots.stream().filter(r -> r.role() == Role.BUSINESS).toList();
        }

        /** 兜底分支（与业务完全无关）。按约定最多一个 —— 加载时已经校验过 */
        public Optional<TopIntent> outOfScope() {
            return withRole(Role.OUT_OF_SCOPE);
        }

        /** 澄清分支（业务相关但信息不足）。调用方靠它决定要不要走澄清反问 */
        public Optional<TopIntent> clarify() {
            return withRole(Role.CLARIFY);
        }

        private Optional<TopIntent> withRole(Role role) {
            return roots.stream().filter(r -> r.role() == role).findFirst();
        }

        public Optional<TopIntent> findTop(String topCode) {
            return roots.stream().filter(r -> r.code().equals(topCode)).findFirst();
        }

        public List<Leaf> allLeaves() {
            return roots.stream().flatMap(r -> r.children().stream()).toList();
        }

        public Optional<Leaf> findLeaf(String leafCode) {
            return allLeaves().stream().filter(l -> l.code().equals(leafCode)).findFirst();
        }

        /**
         * 某个叶子声明的 doc_type 全集。查不到这个叶子时返回<b>空集</b>。
         *
         * <p>返回空集而不是抛异常，是给 5.4 用的：分类结果可能是兜底分支
         * （{@code OUT_OF_SCOPE}，本来就不检索），也可能是模型编出来的
         * 不存在的 code。两者都表现为「没有可过滤的类型」，处理方式相同。
         * ⚠️ 但 5.2 <b>必须</b>把「模型编了个不存在的 code」单独记下来 ——
         * 那是分类失败，不是「这类问题不需要检索」。
         */
        public Set<Integer> docTypesOf(String leafCode) {
            return findLeaf(leafCode).map(l -> Set.copyOf(l.docTypes())).orElseGet(Set::of);
        }

        /**
         * 一个分类目标要走哪条路 —— {@code KB} / {@code TOOL} / {@code NONE}。
         *
         * <p>★★ 阶段 5.8 加这个方法的理由：{@code ask()} 需要知道
         * 「这次该检索还是该调工具」，而那是<b>树里的声明</b>（{@code retrieval} 字段），
         * 不是业务代码该判断的事情。
         *
         * <p>★ <b>它必须同时接受两种粒度</b>，因为分类结果是两种：
         * <ul>
         *   <li>{@code retrieval = KB} 的意图分类到<b>叶子</b>（如 {@code RETURN_EXCHANGE}）</li>
         *   <li>{@code retrieval = TOOL / NONE} 的意图分类到<b>顶层</b>
         *       （如 {@code ORDER_LOGISTICS}）—— 见 {@link #classificationTargets()}
         *       那条「行为相同的不区分」</li>
         * </ul>
         * 只查叶子的话，{@code ORDER_LOGISTICS} 会查不到，
         * 拿到 {@link Retrieval#NONE}，于是<b>工具意图被当成非业务分支</b>。
         *
         * <p>⚠️ 模型编了一个不存在的 code 时返回 {@code null}：
         * 那是分类失败，和「这类问题不需要检索」不是一回事，
         * <b>不能拿 NONE 糊弄过去</b>。调用方必须显式处理 null。
         */
        public Retrieval retrievalOf(String code) {
            if (code == null) {
                return null;
            }
            // ① 先看是不是顶层 —— TOOL / NONE 类意图是【以顶层 code 参与分类】的
            //    （见 classificationTargets 那条「行为相同的不区分」），
            //    所以 ORDER_LOGISTICS 这个 code 会从这条分支返回
            Optional<TopIntent> top = findTop(code);
            if (top.isPresent()) {
                return top.get().retrieval();
            }

            // ② 再看是不是叶子 —— ★ 叶子的 retrieval 是【继承父顶层】的。
            //
            //    retrieval 是【整类】的性质，不是单个叶子的性质：
            //    「订单物流这一类都去查实时数据」对所有子意图都成立。
            //    这正是为什么 IntentTree 的校验规则里写着
            //    「TOOL / NONE 的叶子必须【没有】doc_types」——
            //    一个叶子不可能「自己决定要检索」而它的兄弟不用。
            for (TopIntent intent : roots) {
                for (Leaf leaf : intent.children()) {
                    if (leaf.code().equals(code)) {
                        return intent.retrieval();
                    }
                }
            }
            return null;
        }

        /**
         * 一个分类目标要不要额外带一份结构化事实（阶段 5.9）。
         *
         * <p>★ <b>它只看叶子，不看顶层</b> —— 和 {@link #retrievalOf} 刻意不同。
         * 理由是这件事<b>不是整类的性质</b>：{@code PROMOTION} 下只有
         * {@code PRICE_PROTECTION} 需要政策硬数据，{@code FULL_REDUCTION} 不需要。
         * 见 {@link StructuredFact} 的注释第二节。
         *
         * <p>查不到这个 code（模型编的、或者是顶层码）时返回
         * {@link StructuredFact#NONE}，<b>不返回 null</b> ——
         * 调用方要的是「这次带不带事实」这个二值问题，
         * 而「分类失败」已经由 {@code IntentClassification.isClassified()}
         * 在更上游表达过了。返回 null 会让每个调用方都要多写一次判空，
         * 而漏写的那一处会 NPE。
         */
        public StructuredFact structuredFactOf(String code) {
            if (code == null) {
                return StructuredFact.NONE;
            }
            for (TopIntent intent : roots) {
                for (Leaf leaf : intent.children()) {
                    if (leaf.code().equals(code)) {
                        return leaf.structuredFact();
                    }
                }
            }
            return StructuredFact.NONE;
        }

        /**
         * 一个分类目标这次允许模型调用的工具白名单（阶段 9.3）。
         *
         * <p>★★ 它和 {@link #retrievalOf} 一样<b>必须同时接受两种粒度</b>，
         * 理由也完全相同：分类结果是两种。KB 类意图分类到叶子，
         * TOOL / NONE 类意图分类到顶层。只查叶子会让 {@code ORDER_LOGISTICS}
         * 查不到 → 拿到空列表 → <b>模型手上一件工具都没有</b>，
         * 而症状是「工具题答得跟裸聊一样」，不是报错。
         *
         * <p>查不到这个 code 时返回<b>空列表</b>而不是 null —— 同
         * {@link #structuredFactOf}：调用方要的是「这次能用哪些工具」，
         * 而「分类失败」已经由上游的 {@code isClassified()} 表达过了。
         *
         * <p>★ 空列表的含义是「这次不调工具」，<b>不是</b>「工具不可用」。
         * 「拉不到工具清单」是另一件事（部署问题），由 {@code ToolLoop} 表达 ——
         * 两者在日志和给模型的提示上完全不同，不能合并。
         */
        public List<String> toolsOf(String code) {
            if (code == null) {
                return List.of();
            }
            Optional<TopIntent> top = findTop(code);
            if (top.isPresent()) {
                return top.get().tools();
            }
            return findLeaf(code).map(Leaf::tools).orElseGet(List::of);
        }

        /**
         * 全树声明了某类结构化事实的叶子码，<b>按书写顺序</b>。
         * 给调试探针用 —— 「我明明配了怎么没生效」第一个该看的就是它。
         */
        public List<String> leavesWith(StructuredFact fact) {
            return allLeaves().stream()
                    .filter(l -> l.structuredFact() == fact)
                    .map(Leaf::code)
                    .toList();
        }

        /**
         * 每个顶层意图的 doc_type 并集，<b>保持文件里的书写顺序</b>。
         * 给调试探针用（稳定顺序让人能直接 diff 两次输出）。
         */
        public Map<String, Set<Integer>> docTypesByTop() {
            Map<String, Set<Integer>> byTop = new LinkedHashMap<>();
            for (TopIntent intent : roots) {
                byTop.put(intent.code(), intent.docTypesUnion());
            }
            return byTop;
        }

        public int leafCount() {
            return allLeaves().size();
        }

        // ============================================================
        // 分类目标（阶段 5.2）
        // ============================================================

        /**
         * ★ <b>模型要从中选一个的最小集合</b> —— 分类粒度和树的粒度在这里解耦。
         *
         * <p>规则只有一条：<b>行为相同的不区分。</b>
         *
         * <ul>
         *   <li>{@code retrieval = KB} 的顶层意图：展开到<b>叶子</b>。
         *       因为叶子之间 {@code doc_types} 不同，5.4 对它们的行为不同，
         *       不区分就没法定向过滤。</li>
         *   <li>{@code retrieval = TOOL / NONE} 的顶层意图：<b>只算一个目标</b>，
         *       用顶层 code。因为它们的叶子 {@code doc_types} 都是空集，
         *       5.4 对它们的行为完全一样（都不检索）——
         *       区分它们只对 5.9 有意义，而 5.9 有更好的机制
         *       （MCP 工具 schema 本来就是给模型选工具用的，
         *       让意图识别先选一遍是重复劳动）。</li>
         * </ul>
         *
         * <p>这套规则的一个副作用是好的：<b>将来加一个 TOOL 类意图，
         * 不需要改 prompt 的结构</b>，它自动变成一个目标。
         *
         * <p>⚠️ {@code ClassificationTarget} <b>刻意不携带 leaf.examples()</b>。
         * 树里的示例与评测题有 18/20 逐字重合（见 {@code IntentFewShot} 的说明），
         * 把它们放进 prompt 会让 5.2 的准确率虚高。不提供这个字段，
         * 就是让「写错」在类型层面不可表达。
         *
         * @return 顺序稳定：按树的书写顺序。这样 prompt 是可复现的，
         *         两次调用能直接 diff（对缓存命中率也友好）
         */
        public List<ClassificationTarget> classificationTargets() {
            List<ClassificationTarget> targets = new ArrayList<>();
            for (TopIntent top : roots) {
                if (top.retrieval() == Retrieval.KB) {
                    for (Leaf leaf : top.children()) {
                        targets.add(new ClassificationTarget(
                                leaf.code(), leaf.name(), top.name(), leaf.description()));
                    }
                } else {
                    targets.add(new ClassificationTarget(
                            top.code(), top.name(), null, top.description()));
                }
            }
            return List.copyOf(targets);
        }

        /** 按 code 找分类目标。模型返回的 code 是否合法，就靠它判定 */
        public Optional<ClassificationTarget> findTarget(String code) {
            return classificationTargets().stream()
                    .filter(t -> t.code().equals(code))
                    .findFirst();
        }
    }

    /**
     * 一个<b>分类目标</b> —— 模型可以选的一项。
     *
     * <p>它可能是树的一个叶子（KB 类意图），也可能是树的一个顶层
     * （TOOL / NONE 类意图）。模型不需要知道这个区别，
     * 它只需要从一份扁平的候选列表里挑一个。
     *
     * @param code       模型要输出的那个字符串
     * @param name       中文名，prompt 里给模型看的
     * @param parentName 所属顶层意图的中文名；顶层自身作为目标时为 {@code null}
     * @param description 判据。给模型看
     */
    public record ClassificationTarget(String code, String name, String parentName,
                                       String description) {

        /** prompt 里显示用：「售后服务 / 退换货」或「订单物流」 */
        public String displayName() {
            return parentName == null ? name : parentName + " / " + name;
        }
    }
}
