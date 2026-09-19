package com.xbla.rag.agent.intent;

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
 */
@Component
public class IntentPromptBuilder {

    private final IntentTree intentTree;
    private final IntentFewShot fewShot;

    public IntentPromptBuilder(IntentTree intentTree, IntentFewShot fewShot) {
        this.intentTree = intentTree;
        this.fewShot = fewShot;
    }

    /**
     * 构建分类 prompt。
     *
     * <p>结构固定为三段：角色与任务 → 候选清单（含每个目标的判据和示例）→
     * 输出要求。段与段的顺序<b>不要调整</b>：
     * 通用指令在前、具体素材在后，和阶段 4 的
     * {@code RagPromptBuilder} 是同一条理由（DeepSeek 的上下文缓存是前缀匹配）。
     */
    public String build() {
        IntentTree.Tree tree = intentTree.get();
        IntentFewShot.Samples samples = fewShot.get();

        StringBuilder sb = new StringBuilder(4096);

        sb.append("你是电商问答平台的意图分类器。\n")
          .append("读用户的一句话，判断它属于下面哪一类，只输出一个 code。\n\n");

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

        sb.append("## 输出要求\n")
          .append("- 只输出一个 code，不要有任何其他文字、标点、引号或解释\n")
          .append("- 必须是上面列出的 code 之一，不要自创\n")

          // ★ 下面这三条是本 prompt 最容易写错的地方，每一条都对应一次实测或一次事故：
          //
          //   ① NEEDS_CLARIFICATION 和 OUT_OF_SCOPE 的边界
          //      —— 不写清的话，模型会把「那个怎么样」丢进 OUT_OF_SCOPE。
          //         实测（2026-09-19）在没有 NEEDS_CLARIFICATION 这个选项时，
          //         它对「那个怎么样」给出的正是一个【自信的】OUT_OF_SCOPE，
          //         于是用户收到「我只处理商品导购与售后问题」——
          //         而那句话明明就是在问商品。
          //
          //   ② 「拿不准 ≠ 信息不足」
          //      —— 加了澄清选项之后最大的风险是模型【滥用】它：
          //         把「退货要几天」这种正常问题也判成信息不足。
          //         必须显式说明这两件事不是一回事。
          //
          //   ③ 「拿不准时选更接近的」
          //      —— 不能因为分不清就反问用户。分类边界模糊是【我们的】问题，
          //         不该拿去骚扰用户。这是 5.3 设计里被实测修正过的一条：
          //         曾经以为「分类置信度低 → 澄清」，实测证明两者正交。
          .append("- 三个非业务选项的边界：\n")
          .append("  · NEEDS_CLARIFICATION：是本平台的业务，但这句话【单独拿出来】回答不了\n")
          .append("  · OUT_OF_SCOPE：与商品、促销、售后、订单【都无关】\n")
          .append("- ★ 在两个【业务类别】之间拿不准时，选更接近的那一个。\n")
          .append("  「拿不准」不是「信息不足」—— 后者说的是【这句话本身】缺东西，\n")
          .append("  不是「你分不清它属于哪一类」。分类边界模糊是我们该解决的问题，\n")
          .append("  不该拿去反问用户\n");

        return sb.toString();
    }
}
