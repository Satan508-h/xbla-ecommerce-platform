package com.xbla.rag.rag.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用 LLM 做查询重写（4.3）和子问题拆分（4.4）。
 *
 * <p>只在 {@code xbla.rag.rewrite.enabled=true} 时装配。
 *
 * <h2>一、它解决什么问题</h2>
 *
 * <p>用户在电商客服场景里的提问是口语化的、含指代的、可能一次问好几件事：
 *
 * <pre>
 *   原话：这个送老人合适吗？还有它电池能用多久
 *   ↓
 *   rewritten：适合送长辈的商品 电池续航时间
 *   sub_questions：["适合送长辈的商品", "电池续航时间"]
 * </pre>
 *
 * <p>「这个」「它」在检索时是完全无效的词，而拆开后的两个子问题
 * 分别在「导购指南」和「说明书」里有精确答案。
 *
 * <h2>二、★ 它必须永远不抛异常</h2>
 *
 * <p>任何失败 —— 模型调用挂了、返回的不是 JSON、JSON 字段缺了、超时 ——
 * 一律回落到 {@link QueryPlan#identityWithNote}，也就是「原样检索」。
 *
 * <p>理由：查询加工是<b>锦上添花</b>的一环。用户问一个问题，
 * 不该因为「改写这一步失败」就拿不到回答 —— 而检索本身完全有能力
 * 处理原始查询（那正是基线在做的事）。
 *
 * <h2>三、★ 用自己的 ModelCallTrace，不污染问答的降级记录</h2>
 *
 * <p>改写是一次<b>独立的</b>模型调用。它的降级事件如果混进
 * 问答主体的 {@code qa_log.degradation_events}，
 * 会让「这次问答在哪个模型上降级了」这个判断变得不可信 ——
 * 阶段 2 验收标准第 2 条正是靠那个字段。
 *
 * <p>所以这里 new 一个独立的 {@code ModelCallTrace}，
 * 它的事件只写进日志。代价是改写的 token 消耗不进 {@code qa_log} ——
 * 这是一个<b>已知的、可接受的</b>取舍，因为改写默认关闭，
 * 且它的成本量级远小于生成回答。
 */
@Component
@ConditionalOnProperty(name = "xbla.rag.rewrite.enabled", havingValue = "true")
public class LlmQueryPlanner implements QueryPlanner {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryPlanner.class);

    /**
     * 改写请求的输出上限。
     *
     * <p>★ 给得比较宽松（1024）而不是「改写结果很短，给 256 就够」：
     * P0 的 {@code deepseek-flash} 是<b>推理模型</b>，实测推理 token
     * 占总输出的 87%。给少了会出现「推理吃光额度、content 返回空串」
     * 这个著名故障 —— 现象是「改写静默失效」，日志里看不出原因。
     */
    private static final int MAX_TOKENS = 1024;

    /** 子问题数量上限。拆得太细会让并行召回的成本线性上涨 */
    private static final int MAX_SUB_QUESTIONS = 4;

    private static final String SYSTEM_PROMPT = """
            你是电商客服系统的检索查询改写助手。你的输出会直接用于关键词检索，\
            不面向最终用户，所以不要回答问题、不要寒暄。

            规则：
            1. rewritten：把口语化、含指代（「这个」「它」「那个」）的问题，
               改写成信息完整、适合检索的查询。保留商品名、型号、数字等专有信息。
               如果原问题已经很清晰，原样返回。
            2. sub_questions：如果问题包含多个相互独立的意图，拆成若干子问题；
               只有一个意图时返回空数组。最多 %d 个。
            3. 严格只输出 JSON，不要 Markdown 代码块，不要任何解释文字。

            输出格式：
            {"rewritten": "改写后的查询", "sub_questions": ["子问题1", "子问题2"]}
            """.formatted(MAX_SUB_QUESTIONS);

    private final ChatModelRouter router;
    private final ObjectMapper objectMapper;

    public LlmQueryPlanner(ChatModelRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    @Override
    public QueryPlan plan(String question) {
        if (question == null || question.isBlank()) {
            return QueryPlan.identity(question);
        }

        try {
            // 独立的 trace：改写的降级事件不混进问答主体的 degradation_events
            ModelCallTrace rewriteTrace = new ModelCallTrace("rewrite-" + System.nanoTime());

            ChatRequest request = new ChatRequest(
                    SYSTEM_PROMPT, List.of(), question, MAX_TOKENS, null, null);  // 改写不打工具
            ChatResponse response = router.chat(request, rewriteTrace);

            if (response.isEmptyContent()) {
                return QueryPlan.identityWithNote(question,
                        "rewrite_empty: 模型返回空正文（推理模型可能吃光了 max-tokens）");
            }

            return parse(question, response.content());

        } catch (Exception e) {
            // ★ 捕获所有异常。改写失败不该让用户拿不到回答
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("★ 查询改写失败，回落到原样检索：{}", reason);
            return QueryPlan.identityWithNote(question, "rewrite_failed: " + reason);
        }
    }

    /**
     * 解析模型返回的 JSON。
     *
     * <p>做得比较宽容，因为「模型只输出 JSON」这个要求在实践中经常被违反：
     * 会包 Markdown 代码块、会在前后加一句解释。与其靠 prompt 反复强调，
     * 不如在解析侧容错 —— 反正最终目的是拿到字段，不是校验模型是否守规矩。
     */
    private QueryPlan parse(String question, String rawContent) {
        String json = extractJsonObject(rawContent);
        if (json == null) {
            return QueryPlan.identityWithNote(question,
                    "rewrite_not_json: 模型输出里找不到 JSON 对象");
        }

        JsonNode root = readTree(json);
        if (root == null) {
            return QueryPlan.identityWithNote(question,
                    "rewrite_bad_json: JSON 解析失败");
        }

        String rewritten = textOrNull(root, "rewritten");
        List<String> subQuestions = readSubQuestions(root);

        // 模型原样返回了原问题 → 不记为「重写过」。
        // 否则 qa_log.rewritten_question 会出现「和 question 一模一样」的行，
        // 阶段 7 无法区分「模型没改动」和「压根没开重写」
        if (rewritten != null && rewritten.equals(question)) {
            rewritten = null;
        }

        return new QueryPlan(question, rewritten, subQuestions, null);
    }

    /** 从可能含 Markdown 围栏或前后缀说明的文本里，抠出最外层的 JSON 对象 */
    private static String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("改写结果 JSON 解析失败：{}", e.getMessage());
            return null;
        }
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value.trim();
    }

    private static List<String> readSubQuestions(JsonNode root) {
        JsonNode node = root.get("sub_questions");
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> questions = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                questions.add(item.asText().trim());
            }
            if (questions.size() >= MAX_SUB_QUESTIONS) {
                break;
            }
        }
        // 只有一个子问题时没有拆分价值，反而多花一次召回
        return questions.size() < 2 ? List.of() : questions;
    }
}
