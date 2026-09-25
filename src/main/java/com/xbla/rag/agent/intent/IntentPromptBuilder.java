package com.xbla.rag.agent.intent;

import com.xbla.rag.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把意图树 + 少样本拼成分类用的 system prompt。
 *
 * <p><b>纯函数</b>：同样的树 + 同样的样本，一定产出同样的字符串。
 * 这一点很重要 —— 阶段 7 做 A/B 时，两次实验的 prompt 必须可以直接 diff，
 * 否则「准确率变了」这件事无法归因到具体改了哪一句。
 *
 * <h2>★ 三条写入 prompt 的纪律</h2>
 *
 * <p><b>① 不放 {@code doc_types}。</b>
 *
 * <p>叶子声明的 {@code doc_types} 是<b>检索策略</b>，不是用户意图
 * （这正是 ADR-030 / ADR-031 花了一整节讲清楚的事）。
 * 把它放进 prompt 会诱导模型按「答案在哪几个文档里」去分类，
 * 而不是按「用户在问什么」—— 于是分类体系从用户视角悄悄漂移成实现视角，
 * 而这件事<b>从准确率上看不出来</b>（大部分时候两条路给出同一个答案）。
 *
 * <p><b>② 不用意图树里的 {@code examples}，只用 {@code intent-fewshot.yml}。</b>
 *
 * <p>树里的示例与评测题有 18/20 逐字重合。用它们当 few-shot，
 * 准确率测的就是「模型能不能照抄刚看过的句子」。
 * {@link IntentTree.ClassificationTarget} <b>刻意不提供 examples 字段</b>，
 * 所以这里想写错也写不出来。
 *
 * <p><b>③ 不诱导模型用兜底来表示「不确定」。</b>
 *
 * <p>一个很自然但错误的写法是加一句「拿不准时输出 OUT_OF_SCOPE」。
 * 兜底的含义是<b>「这句话与平台业务无关」</b>，
 * 不是「我分不清 AFTER_SALE 下面哪一类」——
 * 混起来之后，「模型没把握」和「问题确实无关」在数据上就分不开了，
 * 而 5.3 的澄清反问恰恰要靠「没把握」这个信号。
 *
 * <p><b>④ 上一轮的澄清只在【有】的时候出现（阶段 9.4）。</b>
 *
 * <p>它由 {@link #build(PendingClarify)} 的参数决定，<b>没有待澄清时产出的
 * 字符串与 9.3 逐字节相同</b> —— 这正是「改了分类 prompt，但 5.2 的准确率基线
 * 不用重测」的依据（那 20 道题都是单轮的，永远没有 pending）。
 * ★ 所以那段插入<b>不能</b>写成「永远出现、内容为空」，那会让对照组凭空多一个变量。
 */
@Component
public class IntentPromptBuilder {

    private final IntentTree intentTree;
    private final IntentFewShot fewShot;
    private final AgentProperties properties;

    public IntentPromptBuilder(IntentTree intentTree, IntentFewShot fewShot,
                               AgentProperties properties) {
        this.intentTree = intentTree;
        this.fewShot = fewShot;
        this.properties = properties;
    }

    /**
     * 构建分类 prompt。
     *
     * <p>结构固定为三段：角色与任务 → 候选清单（含每个目标的判据和示例）→
     * 输出要求。段与段的顺序<b>不要调整</b>：
     * 通用指令在前、具体素材在后，和阶段 4 的
     * {@code RagPromptBuilder} 是同一条理由（DeepSeek 的上下文缓存是前缀匹配）。
     *
     * <p>★★ <b>阶段 9.4 起多了一个可选的第四段</b>（{@link #appendResumeSection}）：
     * 上一轮的反问还没被回答时，插入一小段结构化状态。
     * 它插在<b>候选清单之后、输出要求之前</b> ——
     * 素材挨着素材，而「只输出一行 JSON」那条契约仍然是最后一句
     * （prompt 里最后的指令是模型最不容易忽略的）。
     *
     * @param pending ★ 上一轮悬着的反问；<b>{@code null} = 没有</b>（常态）。
     *                ⚠️ <b>本方法刻意只有一个版本，没有 {@code build()} 重载</b> ——
     *                加重载会让「忘了传」编译通过，而症状正是这次要修的那个
     *                （恢复路径一次都不生效，而回答看起来完全正常）。同 ADR-091。
     */
    public String build(PendingClarify pending) {
        IntentTree.Tree tree = intentTree.get();
        IntentFewShot.Samples samples = fewShot.get();
        // ★ 这个开关必须在【本方法内】读，而不是构造时读一次：
        //   阶段 7 的 A/B 是靠改环境变量跑的，构造时缓存会让开关在某些路径上失效。
        boolean planEnabled = properties.getPlan().isEnabled();

        StringBuilder sb = new StringBuilder(4096);

        sb.append("你是电商问答平台的意图分类器。\n")
          .append(planEnabled
                  ? "读用户的一句话，判断它属于下面哪一类，并按指定的 JSON 格式输出。\n\n"
                  : "读用户的一句话，判断它属于下面哪一类，只输出一个 code。\n\n");

        sb.append("## 候选意图\n\n");

        String currentParent = null;
        for (IntentTree.ClassificationTarget target : tree.classificationTargets()) {
            // 聚类标题只在切换时打一次 —— 让 13 个叶子按 5 个顶层分组显示，
            // 这比平铺 15 个选项更容易让模型看出层级关系
            String parent = target.parentName();
            if (parent != null && !parent.equals(currentParent)) {
                sb.append("【").append(parent).append("】\n");
                currentParent = parent;
            }

            sb.append("### ").append(target.name()).append('\n');
            sb.append("code: ").append(target.code()).append('\n');
            sb.append(target.description()).append('\n');

            List<String> examples = samples.forIntent(target.code());
            if (!examples.isEmpty()) {
                sb.append("问法举例：").append(String.join(" / ", examples)).append('\n');
            }
            sb.append('\n');
        }

        // ★★ 开关在这里再判一次（而不是只信调用方）：
        //   「关掉时 prompt 与 9.3 逐字节相同」这句话要由【产出 prompt 的这个地方】
        //   保证，否则某天有人绕过调用方的判断传了 pending 进来，
        //   对照组就悄悄多了一个变量，而两组的数据看起来都正常。
        if (pending != null && properties.getSlots().isEnabled()) {
            appendResumeSection(sb, pending);
        }

        sb.append("## 输出要求\n");
        if (planEnabled) {
            appendPlanContract(sb);
        } else {
            appendBareCodeContract(sb);
        }
        appendBoundaryRules(sb);

        return sb.toString();
    }

    /**
     * <b>上一轮的反问</b>（阶段 9.4）—— 只在「刚问过、还没答」的那一轮出现。
     *
     * <h3>★★ 措辞是刻意写弱的</h3>
     *
     * <p>我们<b>不可能</b>在分类之前知道用户是不是在回答上一个问题 ——
     * 他完全可能换了话题（「送长辈」之后突然问「退货要几天」）。
     * 所以这一段不写「用户正在回答」，而写「<b>如果</b>是在回答…；
     * 如果是在问别的，忽略这一段」——把判断权交给模型，它手上信息比我们多
     * （它看得见这一句话本身）。
     *
     * <p>★ 两层缓解缺一不可：这一段的措辞 ＋ 状态的<b>一次性</b>生命周期
     * （{@code pending_clarify} 读后即清，窗口只有一轮）。只有一层的话，
     * 一个悬了很久的旧反问会在后续每一轮里误导分类。
     *
     * <h3>★ 为什么把「缺的是哪几项」和「实际问的是哪一项」都写上</h3>
     *
     * <p>两者会不同：缺三项时我们只问一项（{@link ClarifySlots} 的优先级）。
     * 只写「缺三项」模型会以为用户在一次性回答三个问题；
     * 只写「问的是 product」又丢了「本来还缺什么」这个背景。
     */
    private static void appendResumeSection(StringBuilder sb, PendingClarify pending) {
        sb.append("## 上一轮的澄清（★ 只在这一轮出现）\n")
          .append("- 用户上一轮问的是：「").append(oneLine(pending.question())).append("」\n")
          .append("- 你当时判定这句话信息不足，缺的是：")
          .append(pending.slots().isEmpty() ? "（未记录）" : String.join("、", pending.slots()))
          .append('\n');
        if (pending.asked() != null) {
            sb.append("- 你实际反问他的是：").append(pending.asked()).append('\n');
        }
        sb.append("- ★ 如果现在这句话是在回答上面那个问题，就把它和上面那个问题"
                        + "【合起来】判断 intent —— 例如回答「送长辈」时应当归到对应的"
                        + "业务类别，而不是再判一次信息不足；已经补上的那一项不要再写进 missing\n")
          .append("- ★ 如果这句话明显是在问别的事情、和上面无关，就忽略这一段，照常判断\n\n");
    }

    /** 注入的是用户原话，换行会把这一段的排版打乱 —— 压成一行再放进去 */
    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 100 ? flat.substring(0, 100) + "…" : flat;
    }

    /**
     * <b>老契约</b>：只输出一个 code。
     *
     * <p>★★ 这个分支在 {@code xbla.agent.plan.enabled=false} 时生效，而它的输出
     * <b>必须与阶段 9.2 之前逐字节相同</b> —— 否则「关掉新契约」这个对照组的
     * 意义就没了：你分不清准确率的变化是来自新契约，还是来自这一段的措辞改动。
     * 所以下面两行是<b>原样搬过来</b>的，一个字都不要"顺手改改"。
     */
    private static void appendBareCodeContract(StringBuilder sb) {
        sb.append("- 只输出一个 code，不要有任何其他文字、标点、引号或解释\n")
          .append("- 必须是上面列出的 code 之一，不要自创\n");
    }

    /**
     * <b>新契约</b>（阶段 9.2）：一行 JSON，四个键。
     *
     * <h3>★ 为什么是扁平的四键 JSON，而不是自定义的行协议</h3>
     *
     * <p>备选方案是 {@code CODE|retrieve=1|missing=a,b} 这种省 token 的私有格式。
     * 选 JSON 是因为<b>失败可枚举</b>：缺键、多键、值类型不对，都能给出一句
     * 可读的原因；而私有格式的失败长得都差不多。而且 JSON 走 Jackson，
     * 和本项目「JSONB 一律 Jackson、绝不手拼」是同一条纪律。
     *
     * <p><b>为什么是一层扁平对象、不是嵌套的 {@code {"plan":{...}}}</b>：
     * 多一层就多一个「多打了个花括号」的失败形态，而这一层的全部价值就是低失败率。
     *
     * <h3>★★ 示例里为什么写 {@code <某个 code>} 而不是一个真的 code</h3>
     *
     * <p>写一个真的 code 会<b>诱导模型偏向那一类</b> —— 示例在 prompt 里是最强的信号。
     * 而 {@link IntentFewShot} 的整个存在理由就是「不能给模型看它待会儿要答的题」。
     */
    private static void appendPlanContract(StringBuilder sb) {
        sb.append("- 只输出一行 JSON，不要代码块、不要解释、不要任何其他文字\n")
          .append("- 格式（键的顺序固定，缺一不可）：\n")
          .append("  {\"v\":1,\"intent\":\"<某个 code>\",\"retrieve\":true,\"missing\":[]}\n")
          .append("- intent：必须是上面列出的 code 之一，不要自创\n")
          .append("- retrieve：这句话【需不需要查平台资料】。\n")
          .append("  只有当它完全不需要任何平台资料就能回答时（纯问候、纯感谢、\n")
          .append("  对你上一轮反问的确认）才写 false。拿不准时一律写 true\n")
          .append("- missing：要回答这句话还缺哪些信息，从下面几个里选，可以多选也可以是空：\n")
          .append("  · purpose（用途或场景，比如送人还是自用）\n")
          .append("  · budget（预算）\n")
          .append("  · product（具体是哪一款）\n")
          .append("  · category（品类）\n")
          .append("  ★ 它只是一条记录，【不影响】intent 的选择，也不要因此去选\n")
          .append("    NEEDS_CLARIFICATION —— 那个选项的判据见下面\n");
    }

    /**
     * 两个契约<b>共用</b>的边界规则。
     *
     * <p>★ 下面这三条是本 prompt 最容易写错的地方，每一条都对应一次实测或一次事故：
     *
     * <pre>
     *   ① NEEDS_CLARIFICATION 和 OUT_OF_SCOPE 的边界
     *      —— 不写清的话，模型会把「那个怎么样」丢进 OUT_OF_SCOPE。
     *         实测（2026-09-19）在没有 NEEDS_CLARIFICATION 这个选项时，
     *         它对「那个怎么样」给出的正是一个【自信的】OUT_OF_SCOPE，
     *         于是用户收到「我只处理商品导购与售后问题」——
     *         而那句话明明就是在问商品。
     *
     *   ② 「拿不准 ≠ 信息不足」
     *      —— 加了澄清选项之后最大的风险是模型【滥用】它：
     *         把「退货要几天」这种正常问题也判成信息不足。
     *         必须显式说明这两件事不是一回事。
     *
     *   ③ 「拿不准时选更接近的」
     *      —— 不能因为分不清就反问用户。分类边界模糊是【我们的】问题，
     *         不该拿去骚扰用户。这是 5.3 设计里被实测修正过的一条：
     *         曾经以为「分类置信度低 → 澄清」，实测证明两者正交。
     * </pre>
     *
     * <p>★ 放在共用方法里而不是各写一份：两份措辞一旦漂移，
     * 两个实验组就不止差一个变量了，而那正好是本 builder 最忌讳的事。
     */
    private static void appendBoundaryRules(StringBuilder sb) {
        sb.append("- 三个非业务选项的边界：\n")
          .append("  · NEEDS_CLARIFICATION：是本平台的业务，但这句话【单独拿出来】回答不了\n")
          .append("  · OUT_OF_SCOPE：与商品、促销、售后、订单【都无关】\n")
          .append("- ★ 在两个【业务类别】之间拿不准时，选更接近的那一个。\n")
          .append("  「拿不准」不是「信息不足」—— 后者说的是【这句话本身】缺东西，\n")
          .append("  不是「你分不清它属于哪一类」。分类边界模糊是我们该解决的问题，\n")
          .append("  不该拿去反问用户\n");
    }
}
