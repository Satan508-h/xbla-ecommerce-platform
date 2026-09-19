package com.xbla.rag.rag.prompt;

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
     * 组装。
     *
     * @param baseSystemPrompt 基础 system prompt（固定部分）
     * @param chunks           检索到的切片，按相关度降序
     * @return 拼好的 system prompt。<b>永远不会是 null</b>；
     *         没有切片时返回「基础 prompt + 一句未检索到的说明」
     */
    public String build(String baseSystemPrompt, List<RetrievedChunk> chunks) {
        String base = baseSystemPrompt == null ? "" : baseSystemPrompt.stripTrailing();

        if (chunks == null || chunks.isEmpty()) {
            return base + "\n\n" + EMPTY_NOTE;
        }

        StringBuilder sb = new StringBuilder(base.length() + chunks.size() * 200);
        sb.append(base).append("\n\n");

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
