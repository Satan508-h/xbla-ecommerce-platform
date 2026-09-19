package com.xbla.rag.rag.prompt;

import com.xbla.rag.rag.facts.StructuredFacts;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.springframework.stereotype.Component;

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
 *   [可变上下文]   本次检索到的资料                       ← 从这里起必然未命中
 *   [固定指令]     回答要求                               ← 已经是固定尾块，缓存不到
 * </pre>
 *
 * <p>摘要是<b>半固定</b>的：它由 {@code SessionSummarizer} 在窗口溢出后生成，
 * 之后一直不变，直到游标再次推进。所以它比资料稳定得多，
 * 排在资料<b>之前</b>能让这段缓存前缀在大多数轮次里命中。
 * 反着放（资料之后）就等于它每轮都是新的，一点都缓存不到。
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
    private static final int MAX_CHARS_PER_CHUNK = 1000;

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
     * @return 拼好的 system prompt。<b>永远不会是 null</b>；
     *         没有切片时返回「基础 prompt + 一句未检索到的说明」
     */
    public String build(String baseSystemPrompt, List<RetrievedChunk> chunks,
                        boolean hasHistory, String sessionSummary, StructuredFacts facts) {
        String base = baseSystemPrompt == null ? "" : baseSystemPrompt.stripTrailing();
        boolean hasSummary = sessionSummary != null && !sessionSummary.isBlank();

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

        // ★★ 半固定段之二：结构化事实（阶段 5.9）。
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
     */
    private static String factsSection(StructuredFacts facts) {
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

    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= MAX_CHARS_PER_CHUNK) {
            return content;
        }
        return content.substring(0, MAX_CHARS_PER_CHUNK) + "…（已截断）";
    }
}
