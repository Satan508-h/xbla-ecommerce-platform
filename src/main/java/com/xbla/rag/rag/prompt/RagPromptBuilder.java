package com.xbla.rag.rag.prompt;

import com.xbla.rag.rag.facts.StructuredFacts;
import com.xbla.rag.rag.profile.UserAffinity;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 把检索到的切片拼进 system prompt。
 *
 * <h2>一、为什么拼进 system prompt，而不是给 ChatRequest 加字段</h2>
 *
 * <p>{@code ChatRequest.of(systemPrompt, userQuestion)} 的第一个参数
 * <b>本来就是注入点</b>，所以这个改动<b>一行 {@code client/} 的代码都不用动</b>。
 *
 * <p>反过来，给 {@code ChatRequest} 加一个 {@code context} 字段会让
 * {@code client/} 层知道「检索上下文」这个概念 ——
 * 而 {@code client/package-info.java} 把它钉死成「全项目唯一允许出现模型
 * HTTP 请求的地方」，它的职责是协议，不是 prompt 策略。
 * 以后每次调整引用格式、分块模板都要动 client 包，是错误的耦合方向。
 *
 * <h2>二、★ 顺序：固定指令在前，可变上下文在后</h2>
 *
 * <p>这不是排版偏好，是<b>省钱</b>。
 *
 * <p>DeepSeek 的上下文缓存（context caching）是<b>前缀匹配</b>的：
 * 只有当请求的开头部分与上一次完全相同，那一段才能命中缓存。
 * 而缓存命中的价格是 <b>0.02 元/百万 token</b>，未命中是 <b>1.0 元</b> ——
 * <b>差 50 倍</b>。
 *
 * <p>所以组装顺序必须是：
 *
 * <pre>
 *   [固定的角色设定与回答要求]   ← 每次都一样，可命中缓存
 *   [本次检索到的资料]           ← 每次都不同，从第一个字起就未命中
 * </pre>
 *
 * <p>反过来的话，检索结果插在开头，整个 system prompt 从第 1 个 token 起
 * 就没有任何一次能命中缓存。
 *
 * <p>这条约束可以在 {@code qa_log.cost} 上直接验证：开检索前后各问几个问题，
 * 对比 {@code prompt_tokens} 里缓存命中的比例。
 *
 * <p><b>★ 实际布局是「固定 / 半固定 / 可变 / 固定」四段</b>，而不是两段：
 *
 * <pre>
 *   [固定指令]     base + 会话历史约束（有历史上下文时）  ← 每轮都一样，命中
 *   [半固定]       更早对话的摘要（阶段 5.6）             ← 每 N 轮变一次，大多能命中
 *   [半固定]       这位用户的购买记录（阶段 9.5）         ← 随订单变，但多数轮次里相同
 *   [半固定]       售后政策硬数据（阶段 5.9）             ← 只有那一类问题才有
 *   [可变上下文]   本次检索到的资料                       ← 从这里起必然未命中
 *   [固定指令]     回答要求                               ← 已经是固定尾块，缓存不到
 * </pre>
 *
 * <p>摘要是<b>半固定</b>的：它由 {@code SessionSummarizer} 在窗口溢出后生成，
 * 之后一直不变，直到游标再次推进。所以它比资料稳定得多，
 * 排在资料<b>之前</b>能让这段缓存前缀在大多数轮次里命中。
 * 反着放（资料之后）就等于它每轮都是新的，一点都缓存不到。
 *
 * <p>★★ <b>中间那三段（摘要 / 偏好 / 硬数据）之间的先后，判据只有一条：
 * 谁【更常在】谁就往前排。</b>不是「谁更稳定」、也不是「谁更重要」——
 * 把不常在的放前面，它出现的那一次会把后面所有段整体往后推，
 * 而那一段的缓存就全丢了。三段的常在程度是：
 *
 * <pre>
 *   摘要   有这个用户的任何历史                → 最常
 *   偏好   有身份 ∧ 有效订单 ≥ min-orders      → 其次（实测 20/30 人）
 *   硬数据 意图是售后退换货那一类              → 最少
 * </pre>
 *
 * <p>★ 偏好段排在硬数据之前还有一个附带好处：偏好是<b>每一轮都可能用到</b>的
 * （推荐、比价、找同类），而硬数据只在那一类问题上用得到。
 * 两条理由指向同一个位置。
 *
 * <p>会话历史的约束放在<b>第一段</b>而不是和「回答要求」并在一起，有两个理由：
 * 它是关于<b>整场对话</b>的常设规则，不是关于「上面的资料」的使用说明；
 * 而「回答要求」里的三条逐条指代上面那段资料，挪走会读不通。
 *
 * <h2>三、★ 空上下文时整块省略，不留空标题</h2>
 *
 * <p>写「以下是相关资料：（空）」比不写更糟 —— 它会把模型的注意力引向
 * 「资料在这里」这个结构，而那里什么都没有。
 *
 * <p>正确做法是换成一句明确的说明：本次没有检索到资料，请如实告知。
 */
@Component
public class RagPromptBuilder {

    /**
     * 单个切片拼进 prompt 的字符上限。
     *
     * <p>防御性上限：切分器的 {@code max-chars} 配置是 500，
     * 正常情况下永远轮不到它。但如果那个配置被改错、或者有人绕过切分器
     * 直接写了一个巨大的切片，这里能保证 prompt 不会被一段话撑爆。
     */
    /**
     * 单条切片进 prompt 的字符上限。
     *
     * <p>★★ <b>阶段 7.5 起是 public</b>：评测要把切片交给 RAGAS 当
     * {@code retrieved_contexts}，必须按<b>同一个</b>上限截断。
     * 测试也拿它造边界数据 —— 写成字面量 1000 的话，
     * 改了这里而没改测试，那条测试会<b>继续绿</b>，只是不再守任何东西。
     */
    public static final int MAX_CHARS_PER_CHUNK = 1000;

    /** 引用编号的格式：[1] [2] …… 模型被要求用它标注来源 */
    private static final String MATERIALS_HEADER = "【知识库资料】";
    private static final String EMPTY_NOTE =
            "本次未从平台知识库中检索到相关内容。如果这个问题需要平台政策、"
                    + "商品参数或订单信息才能回答，请如实说明你没有这些信息，不要凭猜测作答。";

    /**
     * 带历史时追加的约束（阶段 5.5）。
     *
     * <h4>为什么需要它</h4>
     *
     * <p>回放给模型的历史<b>只有问和答两段文本</b> —— 当时检索到的资料
     * <b>不在里面</b>（存进去会让窗口每轮翻几倍，而最新的资料这一轮会重新检索）。
     *
     * <p>于是模型看到的是「我问了 X，我答了 Y」，却看不到当时的依据。
     * 而它天生倾向于<b>把上文当成既定事实继续引用</b>：
     * 上一轮如果答错了（没检索到、或者当时编了），这一轮会被当成真的复述下去，
     * 而且这种错<b>读起来非常连贯</b>，人看不出来。
     *
     * <h4>为什么是这三句</h4>
     *
     * <p>「以本次检索到的资料为准」是主句；
     * 后面点名的四类内容（政策条款 / 参数 / 时效 / 金额）是<b>实测最容易出错、
     * 也最容易被用户当真</b>的四类 —— 泛泛说一句「注意准确性」模型会当耳旁风。
     *
     * <p>⚠️ 它是<b>固定文本</b>，不随历史内容变化 —— 所以是合格的缓存前缀。
     * 只在<b>真的有历史</b>时出现：给一轮问答加一句「历史仅供参考」
     * 是纯粹的噪音，还会稀释其他指令的权重。
     */
    private static final String HISTORY_CAVEAT = """
            【关于对话历史】
            你可能会收到之前几轮的问与答。那些回答是当时生成的，
            其中的政策条款、商品参数、时效和金额，一律以【本次】检索到的资料为准；
            不要把历史回答里的具体数值直接当作依据复述。""";

    /**
     * 更早对话的摘要（阶段 5.6）的小节标题。
     *
     * <p>它<b>不是</b>固定文本 —— 内容每 N 轮变一次。所以它排在
     * {@link #HISTORY_CAVEAT}（真正的固定文本）<b>之后</b>、资料之前，
     * 这样缓存前缀能一路吃到摘要之前。见类注释第二节。
     */
    private static final String SUMMARY_HEADER = "【更早的对话摘要】";

    /**
     * 摘要后面跟的一句说明。
     *
     * <p>为什么必须有：摘要是<b>有损</b>的，而模型读到一段「用户说过 X」的
     * 陈述时会当成精确事实。加上这句，它在需要引用具体数值时
     * 才会倾向于以本轮资料为准 —— 和 {@link #HISTORY_CAVEAT} 是同一个目的，
     * 但那条管的是「历史回答」，这条管的是「历史事实」。
     */
    private static final String SUMMARY_NOTE =
            "（以上是对更早对话的压缩，只保留要点，细节可能不全。）";

    /**
     * 结构化事实那一节的标题（阶段 5.9）。
     *
     * <p>★ 它<b>不是</b>「知识库资料」的一部分 —— 两者来源不同、性质不同
     * （一个是表里的字段，一个是文档里的散文），所以标题也必须分开。
     * 合在一起会让模型以为「资料」只有这几行天数。
     */
    private static final String FACTS_HEADER = "【售后政策硬数据】";

    /**
     * 用户偏好那一节的标题（阶段 9.5）。
     *
     * <p>★ 措辞是「购买记录」而不是「偏好」—— 因为这一段里装的是
     * <b>事实</b>（他买过什么），不是结论（他喜欢什么）。
     * 标题写成「偏好」，模型就会把它当成一份用户画像去引用，
     * 而 {@link UserAffinity} 的注释里写着这份数据的样本有多小。
     */
    private static final String AFFINITY_HEADER = "【这位用户的购买记录】";

    /**
     * 偏好块里类目/品牌最多各列几个。
     *
     * <p>★ 它是<b>契约不是旋钮</b>（同 {@link #MAX_CHARS_PER_CHUNK}）：
     * 这一段进的是<b>每一轮</b>的 prompt，长度直接乘上全部请求数。
     *
     * <p>⚠️ <b>截断只在渲染层发生</b>：{@link UserAffinity} 里的列表是完整的，
     * 所以「一共几个类目」这个数仍然是确切的 ——
     * 渲染出来是「涉及 4 个类目，最多的是：A、B、C」，
     * 而不是一句悄悄少了一个的枚举（{@code ADR-094} 那条
     * 「每个数字要么确切、要么标明它是样本」）。
     */
    public static final int MAX_CATEGORY_ITEMS = 3;
    public static final int MAX_BRAND_ITEMS = 5;

    /**
     * 组装（不带对话历史）。等价于 {@code build(base, chunks, false, null, EMPTY)}。
     *
     * <p>保留这个重载是为了让「不关心历史」的调用方（测试、调试探针）
     * 不用多写三个参数。
     */
    public String build(String baseSystemPrompt, List<RetrievedChunk> chunks) {
        return build(baseSystemPrompt, chunks, false, null, StructuredFacts.EMPTY);
    }

    /**
     * 组装（不带摘要）。等价于 {@code build(base, chunks, hasHistory, null, EMPTY)}。
     */
    public String build(String baseSystemPrompt, List<RetrievedChunk> chunks, boolean hasHistory) {
        return build(baseSystemPrompt, chunks, hasHistory, null, StructuredFacts.EMPTY);
    }

    /**
     * 组装（不带结构化事实）。等价于
     * {@code build(base, chunks, hasHistory, sessionSummary, EMPTY)}。
     */
    public String build(String baseSystemPrompt, List<RetrievedChunk> chunks,
                        boolean hasHistory, String sessionSummary) {
        return build(baseSystemPrompt, chunks, hasHistory, sessionSummary, StructuredFacts.EMPTY);
    }

    /**
     * 组装（不带用户偏好）。等价于
     * {@code build(base, chunks, hasHistory, sessionSummary, facts, null)}。
     *
     * <p>保留这个重载是为了让「不关心偏好」的调用方（测试、调试探针）
     * 不用多写一个参数 —— <b>它刻意不给默认值</b>：偏好块是拼进
     * {@code fixed} 的一段，默认成「有」会让测试悄悄依赖上一个它没造的数据。
     */
    public String build(String baseSystemPrompt, List<RetrievedChunk> chunks,
                        boolean hasHistory, String sessionSummary, StructuredFacts facts) {
        return build(baseSystemPrompt, chunks, hasHistory, sessionSummary, facts, null);
    }

    /**
     * 组装。
     *
     * @param baseSystemPrompt 基础 system prompt（固定部分）
     * @param chunks           检索到的切片，按相关度降序
     * @param hasHistory       本次请求是否带了对话历史的<b>原文</b>
     * @param sessionSummary   更早对话的摘要（阶段 5.6），可为 null 或空。
     *                         <b>它也算历史</b> —— 只给摘要不给原文时
     *                         {@link #HISTORY_CAVEAT} 同样会出现
     * @param facts            结构化事实（阶段 5.9）。null 或空 = 这一节不出现。
     *                         ★ 它<b>不受「检索为空」的影响</b> ——
     *                         见下面的实现注释
     * @param affinitySection  <b>用户偏好那一节的【原文】</b>（阶段 9.5），
     *                         null 或空白 = 这一节不出现。
     *                         <p>★★ 收的是<b>渲染好的字符串</b>而不是
     *                         {@link com.xbla.rag.rag.profile.UserAffinity} 对象，这是刻意的：
     *                         同一段文字要进三个地方 ——
     *                         prompt、{@code qa_log.affinity}、以及评测交给 RAGAS 的
     *                         {@code retrieved_contexts} ——
     *                         <b>只有渲染一次才能保证三处逐字相同</b>。
     *                         收对象就等于在这里再渲染一遍，而那是一个
     *                         「评测口径与 prompt 漂移」的机会（同
     *                         {@link #factsSection} 那条警告的反面：
     *                         硬数据敢在那里重渲染，是因为它无参可重建，
     *                         偏好块不是）。
     *                         <p>⚠️ 调用方负责判「该不该给」——
     *                         见 {@code UserAffinityProvider}：那四种「不给」
     *                         全在那一个方法里判完
     * @return 拼好的 system prompt。<b>永远不会是 null</b>；
     *         没有切片时返回「基础 prompt + 一句未检索到的说明」
     */
    public String build(String baseSystemPrompt, List<RetrievedChunk> chunks,
                        boolean hasHistory, String sessionSummary, StructuredFacts facts,
                        String affinitySection) {
        String base = baseSystemPrompt == null ? "" : baseSystemPrompt.stripTrailing();
        boolean hasSummary = sessionSummary != null && !sessionSummary.isBlank();
        boolean hasAffinity = affinitySection != null && !affinitySection.isBlank();

        // ★ 固定段：基础指令 + （有历史上下文时）历史约束。
        //   两者都在可变内容之前 —— 见类注释第二节
        StringBuilder fixed = new StringBuilder(base);
        if (hasHistory || hasSummary) {
            fixed.append("\n\n").append(HISTORY_CAVEAT);
        }

        // ★ 半固定段：摘要。它每 N 轮才变一次，所以排在固定段之后、
        //   资料之前 —— 缓存前缀能一路吃到它
        if (hasSummary) {
            fixed.append("\n\n").append(SUMMARY_HEADER).append('\n')
                    .append(sessionSummary.strip())
                    .append('\n').append(SUMMARY_NOTE);
        }

        // ★★ 半固定段之二：用户偏好（阶段 9.5）。
        //
        //   ① 【为什么排在摘要之后】——判据永远是「谁【更常在】」，
        //      而不是「谁更重要」：
        //        摘要   有这个用户的【任何历史】就在
        //        偏好   有身份 ∧ 有效订单 ≥ min-orders 才在（实测 20/30 人）
        //      更常在的排在前面，前缀缓存才能一路吃到更远的地方。
        //      见类注释第二节那条贯穿四段的规则。
        //
        //   ② 【为什么排在硬数据之前】——偏好几乎每轮都在（只要这个人在），
        //      硬数据只有售后退换货那一类问题才有。同一条判据。
        //
        //   ③ 【为什么拼进 fixed 而不是 chunks 那一段】——同硬数据：
        //      下面那个 early return 会把拼在 chunks 分支里的东西一起吞掉，
        //      而「检索为空 + 恰好有偏好」正是最需要它的时刻之一。
        if (hasAffinity) {
            fixed.append("\n\n").append(affinitySection.strip());
        }

        // ★★ 半固定段之三：结构化事实（阶段 5.9）。
        //
        //   ① 【为什么排在摘要之后，而不是之前】——尽管它比摘要更稳定。
        //      判据不是「谁更稳定」，是「谁【更常在】」：
        //        摘要         每轮都在（只要有历史）
        //        结构化事实   只有售后退换货那一类问题才有
        //      放在摘要【之后】，两种情况下前缀都能一路吃到摘要结束；
        //      放在【之前】，有事实的那次会把摘要往后推，摘要那段缓存全丢。
        //      把不常在的放在后面，是让常在的那部分前缀尽可能长久地命中。
        //
        //   ② 【为什么拼进 fixed 而不是 chunks 那一段】——fixed 是「无论
        //      检索到没检索到都要有的东西」，而事实正是如此：
        //      知识库一条都没召回时，这几行天数照样是准确且有用的。
        //      拼在 chunks 分支里的话，下面那个 early return 会把它一起吞掉 ——
        //      而那个 bug 只在「检索失败 + 恰好是售后退换货问题」时才显形
        if (facts != null && !facts.isEmpty()) {
            fixed.append("\n\n").append(factsSection(facts));
        }

        if (chunks == null || chunks.isEmpty()) {
            return fixed + "\n\n" + EMPTY_NOTE;
        }

        StringBuilder sb = new StringBuilder(fixed.length() + chunks.size() * 200);
        sb.append(fixed).append("\n\n");

        // ── 可变部分从这里开始 ──
        sb.append(MATERIALS_HEADER).append('\n');
        sb.append("以下是从平台知识库检索到的资料，请优先依据它们回答：\n");

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append('\n').append('[').append(i + 1).append("] ");
            if (chunk.headingPath() != null && !chunk.headingPath().isBlank()) {
                // 标题路径给模型提供上下文 —— 「2.3 退款时限与方式」比正文本身
                // 更能说明这段话说的是什么范畴的事
                sb.append(chunk.headingPath()).append('\n');
            }
            sb.append(truncate(chunk.content())).append('\n');
        }

        sb.append("""

                回答要求：
                1. 优先依据上面的资料回答，并在引用处用 [编号] 标注来源
                2. 资料没有覆盖的内容不要编造，如实说明资料中没有提到
                3. 如果上面的资料与问题无关，直接说明没有找到相关信息
                """);

        return sb.toString();
    }

    /**
     * 结构化事实那一节。
     *
     * <p>★ <b>不补齐空白</b>。写成 {@code String.format("%-10s", category)}
     * 让冒号对齐看起来更整齐，但那会往 prompt 里塞一堆无意义的空格 token，
     * 而且中文在等宽字体里占两格、{@code %-10s} 按字符数补 —— 补了也对不齐。
     *
     * <p>★★ <b>阶段 7.5 起是 public</b>：RAGAS 的 faithfulness 判的是
     * 「答案有没有被<b>模型当时看到的全部输入</b>支持」，而这个固定段
     * <b>不在 {@code qa_log.references} 里</b>（实测：{@code MT-005} 的答案说「7 天」，
     * 它引用的 5 条切片里一个天数都没有）。
     *
     * <p>⚠️ 所以评测<b>不能</b>自己再写一遍这个渲染 —— 那就是第二个事实来源，
     * 它和这里的漂移是<b>静默</b>的：报告里的 faithfulness 会因此偏低，
     * 而看报告的人会去调一个根本没坏的东西。同 ADR-058 那条「只写一次」。
     */
    public static String factsSection(StructuredFacts facts) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(FACTS_HEADER).append('\n');
        sb.append("以下是平台政策库里的结构化字段，是准确值，回答时限时以此为准：\n");

        for (StructuredFacts.PolicyTerm term : facts.policies()) {
            sb.append(term.category() == null || term.category().isBlank()
                            ? "通用" : term.category())
                    .append("：退货 ").append(term.returnDays()).append(" 天");
            // ★ 换货天数为 null 时【如实说未规定】，不写 0 —— 见 PolicyTerm 的注释
            if (term.exchangeDays() == null) {
                sb.append("；换货天数未规定");
            } else {
                sb.append(" / 换货 ").append(term.exchangeDays()).append(" 天");
            }
            sb.append('\n');
        }

        sb.append("条件、流程和例外情况见知识库资料。");
        return sb.toString();
    }

    /**
     * 用户偏好那一节（阶段 9.5）。
     *
     * <h3>★ 一、它写的是<b>事实</b>，不是偏好</h3>
     *
     * <p>这一段里没有一个字是「你喜欢 X」。它写的是「你买过什么」，
     * 然后把判断留给模型 —— 因为数据支撑不了那个判断：
     * 实测最活跃的用户也只有 7 笔订单，而且买的 5 个品牌<b>一个都不重</b>。
     *
     * <p>⚠️ 措辞上有一处必须守住：<b>不要写「你偏好」「你常买」</b>。
     * 模型对 prompt 里的话是照单全收的，写成偏好它就会当成既定事实去回答，
     * 而那是一句<b>关于这位用户的假话</b> —— 同 {@code PolicyTerm.exchangeDays}
     * 那条「不要在这一层把 null 兜成默认值」。
     *
     * <h3>★ 二、样本量必须写出来，而且和计数自洽</h3>
     *
     * <p>「7 笔订单、共 7 件商品」里的件数不是装饰：下面那几个「A×3、B×2」
     * 是<b>件级</b>的计数，两个数都写出来，读的人才能核对它们加起来对不对。
     * 只写订单数的话那几行看起来对不上，而「数字对不上」正是读的人
     * 开始怀疑整段数据的起点（{@code ADR-094}：每个数字要么确切、
     * 要么标明它是样本）。
     *
     * <h3>★★ 三、截断只在<b>这一层</b>发生，而且要说破</h3>
     *
     * <p>{@link com.xbla.rag.rag.profile.UserAffinity} 里的列表是完整的，
     * 所以「涉及 4 个类目」这个数<b>永远确切</b>。截断的是后面那个枚举，
     * 而它有一句「最多的是」把它标记成<b>取的前几个</b> ——
     * 只说「：A、B、C」的话，读的人会以为一共就这三个。
     *
     * <p>★ 另一个选择是干脆不截断（类目一共 6 个、品牌 20 个）。
     * 不做，因为这一段进的是<b>每一轮</b>的 prompt：长度直接乘上全部请求数，
     * 而一个买了 20 个品牌的用户的品牌清单，对模型的边际价值接近于零。
     *
     * <p>★★ <b>这个方法是 public static，和 {@link #factsSection} 一样</b>：
     * 评测要拿它当「模型当时看到的上下文」，而<b>不是自己再写一遍渲染</b>。
     * ⚠️ 但和硬数据有一处关键不同 —— 偏好块<b>不能</b>在评测里重建
     * （它 per-user 且随订单变），所以评测读的是 {@code qa_log.affinity}
     * 里那份<b>快照</b>，而快照就是这里渲染出来的同一次输出。
     */
    public static String affinitySection(UserAffinity affinity) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(AFFINITY_HEADER).append('\n');
        sb.append("以下是这位用户自己的历史订单，是【事实】不是对他喜好的判断：\n");

        sb.append("近 ").append(affinity.windowDays()).append(" 天内 ")
                .append(affinity.orderCount()).append(" 笔已付款订单（共 ")
                .append(affinity.itemCount()).append(" 件商品）");
        if (!affinity.categories().isEmpty()) {
            sb.append("，涉及 ").append(affinity.categories().size()).append(" 个类目：")
                    .append(joinCounts(affinity.categories(), MAX_CATEGORY_ITEMS));
        }
        sb.append('\n');

        if (!affinity.brands().isEmpty()) {
            sb.append("买过的品牌：")
                    .append(joinCounts(affinity.brands(), MAX_BRAND_ITEMS)).append('\n');
        }

        // ★ 三个价格要么一起有、要么一起没有（同一个价格列表算出来的）——
        //   判一个就够，但判 null 而不是判 0：0 是一个合法的价格
        if (affinity.priceMin() != null) {
            sb.append("成交单价 ").append(money(affinity.priceMin()))
                    .append(" ~ ").append(money(affinity.priceMax()))
                    .append(" 元，平均 ").append(money(affinity.priceAvg())).append(" 元\n");
        }

        sb.append("会员等级：").append(memberLevelText(affinity.memberLevel())).append('\n');

        sb.append("""
                ★ 样本很小，买过的不等于下次还想买 —— 这是参考，不是结论。
                  不要据此断言「你只喜欢 X」，也不要在用户没问的时候主动罗列他的购买记录。""");
        return sb.toString();
    }

    /**
     * {@code A×3、B×2、C×1} 这样的一行；<b>截断时说破它被截断了</b>。
     *
     * <p>★★ <b>「（其余略）」这四个字不能省。</b>没有它，一个买了 6 个品牌的用户
     * 会看到「买过的品牌：A、B、C、D、E」—— 而那句话<b>读起来就是完整的</b>，
     * 模型会把它当成「他一共买过这五个品牌」。
     *
     * <p>★ 这不是假想：<b>实测就漏过一次</b>（2026-09-25，活体验收时用
     * {@code /api/debug/profile/affinity?userId=8} 打出来的那一段少了
     * 第 6 个品牌，而当时类目那一行是有标记的、品牌那一行没有）。
     * ⚠️ 单测当然抓不到 —— 那条用例的夹具恰好只有 2 个品牌，没到上限。
     * 这正是 {@code ADR-094} 那条「每个数字要么确切、要么标明它是样本」的又一次应用。
     *
     * <p>★ 用 {@code ×}（U+00D7）而不是 {@code x}：中文正文里两者都能读，
     * 但 ASCII 的 x 在等宽字体里会和商品名里的「X1」混起来。
     */
    private static String joinCounts(List<UserAffinity.Count> counts, int limit) {
        StringBuilder sb = new StringBuilder(counts.size() * 8);
        int n = Math.min(limit, counts.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append('、');
            }
            UserAffinity.Count item = counts.get(i);
            sb.append(item.name()).append('×').append(item.count());
        }
        if (counts.size() > n) {
            sb.append("（其余略）");
        }
        return sb.toString();
    }

    /**
     * 金额。
     *
     * <p>★ 不用 {@code BigDecimal.stripTrailingZeros()} —— 它在整数值上会返回
     * {@code 5.431E+3} 这种科学计数法（{@code toPlainString()} 能救回来，
     * 但那是「知道有这个坑才写得对」）。Provider 已经把它们定标到 0 位小数了，
     * 这里直接 {@code toPlainString()} 就是对的。
     */
    private static String money(BigDecimal value) {
        return value.toPlainString();
    }

    /**
     * 会员等级的白话。
     *
     * <p>★ <b>null 和越界值都渲染成「未记录」，绝不兜成「普通会员」</b>：
     * 兜底会把「我们不知道」写成一个具体的等级，而模型分不出它和真值的区别。
     * ⚠️ 这<b>不是</b>死代码 —— {@code X-Xbla-User-Id: 999999} 这类请求
     * 会让这条路上拿到一个 {@code app_user} 里不存在的 id。
     */
    private static String memberLevelText(Integer level) {
        if (level == null) {
            return "未记录";
        }
        return switch (level) {
            case 1 -> "普通";
            case 2 -> "银卡";
            case 3 -> "金卡";
            case 4 -> "钻石";
            // ★ 数据库上有 CHECK 1..4，所以这一支"不该"走到 ——
            //   而它仍然要有一句实话，不能抛异常（这一段只是锦上添花）
            default -> "未记录";
        };
    }

    /**
     * 单条切片进 prompt 前的截断。
     *
     * <p>★★ <b>阶段 7.5 起是 public</b>：评测要把切片正文交给 RAGAS 当
     * {@code retrieved_contexts}，而模型看到的是<b>截断后</b>的版本。
     * 不截断就是让评判标准比被评对象<b>更宽</b> —— 答案里依据了
     * 第 1001 字之后的内容时，faithfulness 会判它「无依据」。
     */
    public static String truncate(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= MAX_CHARS_PER_CHUNK) {
            return content;
        }
        return content.substring(0, MAX_CHARS_PER_CHUNK) + "…（已截断）";
    }
}
