package com.xbla.rag.agent.intent;

import com.xbla.rag.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「这一次要不要检索」的<b>唯一判断点</b>（阶段 9.2）。
 *
 * <h2>★★ 为什么必须是唯一一处</h2>
 *
 * <p>本项目有一条反复出现的失败形态：<b>两条平行路径只改了一条</b>（ADR-047）——
 * 9.1 修的正是它的一个实例（工具链只长在非流式那条路上）。
 *
 * <p>检索判断比那还危险一点，因为它有<b>三条路</b>要用
 * （{@code ask} / {@code askStream} / 调试探针），而漏改一条的症状不是报错，
 * 是「那条路上的问题答得更差」—— 没有任何指标会单独红。
 *
 * <p>所以判断收在这里：纯函数，输入是「分类结果 + 树 + 配置」，
 * 输出是一个 {@link Decision}。<b>调用方只读它的结论，不复现它的逻辑。</b>
 *
 * <h2>门控表</h2>
 *
 * <pre>
 *   意图树的 retrieval   模型的计划       生效结果                     reason
 *   ──────────────────────────────────────────────────────────────────────────
 *   KB                  retrieve=true   检索 [+ 叶子声明的工具]        KB
 *   KB                  retrieve=false  不检索 [+ 叶子声明的工具]      PLAN_OFF
 *   TOOL                —               不检索 + 顶层声明的工具        TOOL
 *   NONE                —               不检索、不调工具              NONE_INTENT
 *   分类失败 / 没开       —               检索、不带工具（阶段 4 行为）  NO_CLASSIFY
 * </pre>
 *
 * <h2>★★ 两条边界规则（都是单向的，反向在类型上不可表达）</h2>
 *
 * <p><b>① 模型只能把检索【关掉】，不能把意图树声明不检索的意图【打开】。</b>
 *
 * <p>反向的那条路（模型说「NONE 类的意图要检索」）会复活 ADR-044 那个坑：
 * 工具意图一旦退化成裸聊，模型会<b>拿通用规则编一个具体的订单状态出来</b>。
 * 所以在本类里，{@code TOOL} / {@code NONE} 两类的分支<b>根本不读</b> {@code plan.retrieve()} ——
 * 那个值在这两条路上是「不可表达」的，而不是「读了但不采纳」。
 *
 * <p><b>② 分类失败时【不】引入回归。</b>
 *
 * <p>返回 {@code RETRIEVE} 而不是「不检索」：分类失败 / 意图识别关闭时，
 * 系统应该退化成阶段 4 的行为（全池检索 → 生成），那是<b>可用</b>的。
 * 反过来判成「不检索」会让一次分类故障直接变成一次裸聊 ——
 * 那是一个比「检索范围太大」严重得多的后果。
 * ★ 同时工具清单也必须是<b>空的</b>：分类都没成，我们不知道该给哪一份白名单，
 * 给一份「全都行」等于把按意图裁剪整个作废。
 *
 * <h2>★★★ 阶段 9.3：工具清单是【树】给的，模型说的话里根本没有这一项</h2>
 *
 * <p>9.2 定下「计划的 JSON 里只有 {@code retrieve}，没有 {@code tools}」这条时，
 * 理由写在 {@code IntentTree.classificationTargets()} 上：工具 schema 本来就是
 * 给模型在<b>工具循环里</b>选工具用的，让意图识别先选一遍是重复劳动
 * （阶段 5.9 已否决过一次）。
 *
 * <p>那条决定在 9.3 结出了一个很好的果子：<b>{@code retrieve} 和 {@code tools}
 * 是两个正交的轴</b>，而它们各自的来源不同 ——
 *
 * <pre>
 *   retrieve  模型可以关掉（KB 类意图上）
 *   tools     模型碰不到，永远来自意图树的声明
 * </pre>
 *
 * <p>所以「模型说不用检索」**不等于**「这次不调工具」。一个挂着工具的叶子
 * 被关掉检索之后，仍然应该走工具轮 —— 那正是「不用查资料，去查实时数据」这个合法意图。
 * 反过来（把工具也一起关掉）会让这类叶子退化成裸聊，就是 ADR-044 那个坑。
 */
@Component
public class RetrievalGate {

    /**
     * 这次走哪条路。
     *
     * <p>★ 它有三个值而不是一个布尔量，因为「不检索」有<b>两种含义完全不同</b>的成因：
     *
     * <pre>
     *   TOOLS   不检索，但要去调实时工具（订单/库存/券）
     *   NONE    不检索，也不调工具（兜底闲聊、澄清反问）
     * </pre>
     *
     * <p>合成一个布尔量的话，调用方要靠 {@code retrievalOf(code)} 再判一次 ——
     * 那就等于把判断又搬回了调用点，本类也就白存在了。
     *
     * <p>★★ 阶段 9.3 的补充：<b>{@code Path} 只决定「要不要检索」，
     * 工具清单是另一个轴</b>（见 {@link Decision#tools()}）。所以
     * {@code RETRIEVE} <b>可以</b>带着非空的工具清单 —— 那是混合轮。
     * 三个值的含义因此重述为：
     *
     * <pre>
     *   RETRIEVE  检索（工具清单可能为空 = 纯知识库，也可能非空 = 混合轮）
     *   TOOLS     不检索，有工具可调
     *   NONE      不检索，也没有工具可调
     * </pre>
     *
     * <p>恒等式 <b>{@code TOOLS ⟺ !shouldRetrieve() && hasTools()}</b>。
     * 它由 {@link #decide} 一处保证，单测直接断言它。
     */
    public enum Path {
        /** 检索知识库 */
        RETRIEVE,
        /** 不检索知识库，走 MCP 工具 */
        TOOLS,
        /** 不检索，也不调工具 */
        NONE
    }

    /**
     * @param path   见 {@link Path}
     * @param reason 为什么是这个结论。<b>它会落进 {@code qa_log.intent_plan.gate}</b>，
     *               所以取值是稳定的小写串，能被统计
     * @param tools  ★ 阶段 9.3：这次允许模型调用的工具白名单，
     *               <b>永远来自意图树的声明，模型说的不算</b>（见类注释）。
     *               空列表 = 这次不调工具。它<b>不是</b>「工具不可用」——
     *               那是 {@code ToolLoop} 才看得见的事（部署层）。
     */
    public record Decision(Path path, String reason, List<String> tools) {

        public Decision {
            tools = List.copyOf(tools);
        }

        public boolean shouldRetrieve() {
            return path == Path.RETRIEVE;
        }

        /**
         * 这次有没有工具可调。
         *
         * <p>★ 调用方应该判这个，<b>不要</b>判 {@code path == TOOLS} ——
         * 混合轮的 path 是 {@code RETRIEVE}，但它同样要进工具循环。
         * 判 path 会在混合轮上静默走成纯知识库问答，而回答看起来完全正常。
         */
        public boolean hasTools() {
            return !tools.isEmpty();
        }
    }

    /** reason 取值 —— 写成常量是因为它们要进 qa_log 并被统计，不能被手打错 */
    public static final String REASON_KB = "KB";
    public static final String REASON_PLAN_OFF = "PLAN_OFF";
    public static final String REASON_TOOL = "TOOL";
    public static final String REASON_NONE_INTENT = "NONE_INTENT";
    public static final String REASON_NONE_DISABLED = "NONE_DISABLED";
    public static final String REASON_NO_CLASSIFY = "NO_CLASSIFY";

    private final IntentTree intentTree;
    private final AgentProperties properties;

    public RetrievalGate(IntentTree intentTree, AgentProperties properties) {
        this.intentTree = intentTree;
        this.properties = properties;
    }

    /**
     * @param intent 分类结果。<b>可以为 null</b>（意图识别关掉了）
     */
    public Decision decide(IntentClassification intent) {
        // ★ 分类失败 / 没开意图识别 → 退化成阶段 4 的行为。见类注释「边界规则 ②」
        //
        //   ★ 工具清单【必须】是空的：分类都没成，我们不知道该给哪一份白名单。
        //     给一份「全都行」等于把按意图裁剪整个作废，而症状是
        //     「分类一失败，模型就能看到全部工具」—— 没有任何指标会红。
        if (intent == null || !intent.isClassified()) {
            return new Decision(Path.RETRIEVE, REASON_NO_CLASSIFY, List.of());
        }

        IntentTree.Tree tree = intentTree.get();
        IntentTree.Retrieval declared = tree.retrievalOf(intent.code());

        // ★ retrievalOf 对「模型编出来的 code」返回 null。理论上到不了这里
        //   （分类成功就意味着 findTarget 匹配过），但真到了也绝不能变成「不检索」——
        //   那会把一次分类 bug 放大成一次裸聊
        if (declared == null) {
            return new Decision(Path.RETRIEVE, REASON_NO_CLASSIFY, List.of());
        }

        // ★★ 白名单只算一次，四个分支各自决定要不要带上它。
        //    toolsOf 同时接受顶层码和叶子码 —— 这是必须的，理由见那个方法
        List<String> tools = tree.toolsOf(intent.code());

        return switch (declared) {
            // ★★ 这一支不读 plan.retrieve() —— 见类注释「边界规则 ①」
            //    ★ tools 在这条路上【必然非空】：加载期校验写着
            //      「retrieval=TOOL 却没有可用的 tools → 启动即崩」
            case TOOL -> new Decision(Path.TOOLS, REASON_TOOL, tools);

            // ★★ 9.2 修掉的那个已知不一致：树里写着 NONE，代码从阶段 5 起
            //    从来没实现过它（那 7 道兜底题 100% 过度检索）
            //
            //    ★ tools 在这条路上【必然为空】（加载期不许 NONE 声明 tools），
            //      这里传 List.of() 是把它写成事实而不是「碰巧」
            case NONE -> properties.getPlan().isNoneIntentSkipRetrieval()
                    ? new Decision(Path.NONE, REASON_NONE_INTENT, List.of())
                    : new Decision(Path.RETRIEVE, REASON_NONE_DISABLED, List.of());

            // ★ 只有这一类才轮到模型说话 —— 而且它只能说 retrieve 那一项
            case KB -> planOff(intent, tools);
        };
    }

    /**
     * KB 类意图：模型说了算的那一半。
     *
     * <p>★★ 9.3 的要害在这一行 —— <b>模型关掉的是【检索】，不是【工具】。</b>
     *
     * <p>一个挂着工具的 KB 叶子（今天只有 {@code SCENARIO_PICK}）被关掉检索之后，
     * 仍然应该走工具轮：那正是「不用查资料，去查实时数据」这个合法意图。
     * 把工具也一起关掉会让它退化成裸聊 —— 就是 ADR-044 那个坑，
     * 而症状是模型拿通用规则编一份具体的商品推荐出来，读起来完全正常。
     *
     * <p>★ 反过来，工具清单为空时路径就是 {@code NONE}（纯裸聊）——
     * 那没有错：这个叶子本来就没有工具可用，关掉检索之后确实什么都查不了。
     */
    private Decision planOff(IntentClassification intent, List<String> tools) {
        if (!modelTurnedItOff(intent)) {
            return new Decision(Path.RETRIEVE, REASON_KB, tools);
        }
        return tools.isEmpty()
                ? new Decision(Path.NONE, REASON_PLAN_OFF, List.of())
                : new Decision(Path.TOOLS, REASON_PLAN_OFF, tools);
    }

    /**
     * 模型有没有把这次检索关掉。
     *
     * <p>★ 三个条件缺一不可，而且每一条都有具体的失败形态在里面：
     *
     * <pre>
     *   plan != null            测试构造的分类结果没有计划 → 不该因此改变行为
     *   shape == JSON           裸码回退时 retrieve 是【默认值】true，不是模型说的
     *   !retrieve               模型确实说了「不用检索」
     * </pre>
     *
     * <p>中间那条最容易被漏：如果把回退路径的默认值也当成「模型的意见」，
     * 那么「模型没按契约作答」这个信息就丢了 —— 而它正是
     * {@link IntentPlan.Shape} 存在的全部理由。
     */
    private boolean modelTurnedItOff(IntentClassification intent) {
        IntentPlan plan = intent.plan();
        if (plan == null || plan.shape() != IntentPlan.Shape.JSON) {
            return false;
        }
        return !plan.retrieve() && properties.getPlan().isAllowModelNoRetrieve();
    }
}
