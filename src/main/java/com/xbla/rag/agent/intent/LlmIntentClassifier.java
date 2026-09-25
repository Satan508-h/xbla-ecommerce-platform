package com.xbla.rag.agent.intent;

import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallException;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 用 LLM 做意图分类（阶段 5.2）。
 *
 * <h2>一、走哪条模型链路</h2>
 *
 * <p>走<b>完整降级链</b>（{@code ChatModelRouter.chat}），不是指定某一档。
 * 代价是默认落在 P0 = {@code deepseek-flash} 这个<b>推理模型</b>上 ——
 * 它要先生成一大段推理才会吐那个叶子码（实测推理 token 占总输出 87%）。
 * 换来的是一整条链路的熔断与换源：分类的可用性和问答同档。
 *
 * <p>这是 2026-09-19 拍板的选择。它的后果在第 二 节，那是本类最需要注意的地方。
 *
 * <h2>二、★★ {@code max_tokens} 绝不能给小</h2>
 *
 * <p>分类的输出只有几个 token（一个叶子码），直觉上给 16 甚至 8 就够。
 * <b>但给小的后果是分类静默地全部失败</b>：
 *
 * <pre>
 *   P0 是推理模型 → 它先花几百个 token 推理 → 额度被推理吃光
 *   → content 返回【空字符串】→ 我们把它当成一次失败的分类
 *   → 而 HTTP 状态码是 200，日志里没有任何异常
 * </pre>
 *
 * <p>这正是 CLAUDE.md 里记着的那个坑（阶段 2 踩过，现象是「AI 不说话」）。
 * 所以本类：
 * <ul>
 *   <li>默认<b>不覆盖</b> {@code max-tokens}，用全局默认的 2048
 *       （见 {@code AgentProperties.Intent.maxTokens} 的注释）</li>
 *   <li>并且显式调用 {@link ChatResponse#isEmptyContent()} ——
 *       那个方法就是为探测这个坑写的，不用它等于白踩一次</li>
 * </ul>
 *
 * <h2>三、★ 解析策略：先严后宽，但先测再放宽</h2>
 *
 * <p>模型返回的东西可能带一堆包装：{@code RETURN_EXCHANGE}、
 * {@code `RETURN_EXCHANGE`}、{@code "RETURN_EXCHANGE"}、
 * {@code RETURN_EXCHANGE。}、甚至前面加一行解释。
 *
 * <p>本类的做法是<b>只在无害的层面放宽</b>：去首尾引号/反引号/代码围栏、
 * 取第一行非空、去掉尾部标点、统一大写。做完这些之后<b>要求精确匹配</b>，
 * 匹配不上就报 {@link IntentClassification.Outcome#UNKNOWN_CODE}，
 * 并把模型的原话一起带出来。
 *
 * <p><b>刻意不做的那件事</b>：在回复里「搜索第一个长得像 code 的单词」。
 * 那能救回一些啰嗦的回复，但它也会把「模型其实在胡说，只是话里碰巧出现了
 * 一个 code」当成成功 —— 而这两件事的修法完全不同。
 *
 * <p>⚠️ 如果 20 题实测下来 {@code UNKNOWN_CODE} 的比例明显偏高，
 * 说明模型确实爱加包装，那时再加宽松解析 —— <b>由数据驱动，不靠预判</b>。
 *
 * <p>★ <b>阶段 9.2 起，上面这些清洗逻辑搬到了 {@link IntentReplyParser}</b>，
 * 本类只负责「调模型 → 交给解析器 → 组装结果」。搬家的理由是：
 * 解析现在要处理<b>两种</b>契约（JSON 计划 / 裸 code），
 * 而「模型回复怎么解读」这件事只该有一个归属地。
 */
@Component
public class LlmIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentClassifier.class);

    private final ChatModelRouter router;
    private final IntentTree intentTree;
    private final IntentPromptBuilder promptBuilder;
    private final AgentProperties properties;
    private final IntentReplyParser replyParser;

    public LlmIntentClassifier(ChatModelRouter router,
                               IntentTree intentTree,
                               IntentPromptBuilder promptBuilder,
                               AgentProperties properties,
                               IntentReplyParser replyParser) {
        this.router = router;
        this.intentTree = intentTree;
        this.promptBuilder = promptBuilder;
        this.properties = properties;
        this.replyParser = replyParser;
    }

    /**
     * 对一个问题做意图分类。
     *
     * <p><b>本方法不抛异常</b>（除非入参为空白）。所有失败都通过
     * {@link IntentClassification#outcome()} 表达 ——
     * 理由和 {@code RetrievalPipeline} 的「任何情况下都不向上抛」一样：
     * 分类失败应该让系统退化成「不做定向检索」，
     * 而不是让用户的这次提问整个失败。
     *
     * @param question 用户原话（不做任何改写 —— 改写是阶段 4 的
     *                 {@code QueryPlanner} 的事，且默认关闭）
     */
    public IntentClassification classify(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("待分类的问题不能为空");
        }

        String prompt = promptBuilder.build();
        AgentProperties.Intent config = properties.getIntent();
        ChatRequest request = new ChatRequest(
                prompt,
                List.of(),
                question,
                config.getMaxTokens(),
                config.getTemperature(),
                null);  // 分类不打工具 —— 参数留 null，见 ChatRequest 的类注释

        ModelCallTrace trace = new ModelCallTrace("intent-" + shortId());
        long start = System.nanoTime();

        try {
            ChatResponse response = router.chat(request, trace);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            // ★ 推理模型把 max-tokens 吃光的探测器（见类注释第二节）
            if (response.isEmptyContent()) {
                log.warn("★ 意图分类拿到了空正文 —— 多半是 max-tokens 被推理吃光。"
                        + "{}（把 xbla.agent.intent.max-tokens 设为 null 用全局默认值）",
                        response.describe());
                return new IntentClassification(null,
                        IntentClassification.Outcome.CALL_FAILED,
                        response.content(), response.descriptor(), trace.cost(), latencyMs,
                        "模型返回空正文（finish_reason=" + response.finishReason() + "）");
            }

            IntentReplyParser.ParsedReply parsed =
                    replyParser.parse(response.content(), intentTree.get());
            if (!parsed.ok()) {
                log.warn("★ 意图分类失败：{}。raw={}", parsed.note(), abridge(response.content()));
                return new IntentClassification(null,
                        IntentClassification.Outcome.UNKNOWN_CODE,
                        response.content(), response.descriptor(), trace.cost(), latencyMs,
                        parsed.note());
            }

            // ★★ 回退路径（模型没按新契约答、但答对了）—— 这一行是「新契约到底有没有生效」
            //    的唯一线索。打在 WARN 而不是 DEBUG，是因为它一旦长期出现，
            //    整个阶段 9.2 的门控就【一次都没生效】过，而那从任何指标上都看不出来。
            if (parsed.note() != null) {
                log.warn("★ 意图分类走了回退路径：{}（raw={}）",
                        parsed.note(), abridge(response.content()));
            }

            IntentClassification result = new IntentClassification(parsed.code(),
                    IntentClassification.Outcome.CLASSIFIED,
                    response.content(), response.descriptor(), trace.cost(), latencyMs, null,
                    parsed.plan());
            log.debug("意图分类：{} | {}", result.describe(), parsed.plan().describe());
            return result;

        } catch (ModelCallException e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            // ★ 不重试、不换模型。分类失败的合理兜底是「不做定向检索」——
            //   那退化成阶段 4 的完整行为，用户无感；重试只会叠加延迟
            log.warn("★ 意图分类调用失败（不重试）：kind={} {}", e.kind(), e.getMessage());
            return new IntentClassification(null,
                    IntentClassification.Outcome.CALL_FAILED,
                    null, trace.route(), trace.cost(), latencyMs,
                    e.kind() + ": " + e.getMessage());
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String abridge(String text) {
        if (text == null) {
            return "null";
        }
        String flat = text.replace("\n", "\\n");
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }
}
