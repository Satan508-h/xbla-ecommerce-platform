package com.xbla.rag.service;

import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.EmbeddingClient;
import com.xbla.rag.client.LlmClient;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.ModelCostCalculator;
import com.xbla.rag.client.RerankClient;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.EmbeddingResult;
import com.xbla.rag.client.dto.RerankResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 模型接入层的调试探针 —— <b>只服务于阶段 2 的验收，不是业务逻辑</b>。
 *
 * <p>它让三条链路（对话 / 向量 / 重排）都能被单独调起来验证，
 * 而不用等到 {@code /api/chat} 全部写完。这是阶段 2 实现顺序
 * 「一步一验证」的基础设施。
 *
 * <p><b>为什么返回 {@code Map} 而不是定义响应 record？</b>
 * 因为探针的输出是<b>探索性</b>的 —— 验证过程中会不断往里加诊断字段
 * （维度、耗时、用量、降级轨迹……）。定义成 record 的话，
 * 每加一个字段都要新建/修改类，而它最终会随阶段 3 的到来被删掉。
 * 这里用 Map 是有意的取舍，<b>业务接口不这么写</b>。
 *
 * <p><b>★ 安全提示</b>：对应 Controller 标了 {@code @Profile("local")}，
 * 只在本地开发时存在。生产环境绝不能暴露 ——
 * 它可以让任何人消耗你的 API 额度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelProbeService {

    private final ChatModelRouter router;
    private final EmbeddingClient embeddingClient;
    private final RerankClient rerankClient;
    private final ModelCostCalculator costCalculator;

    // ============================================================
    // ① 对话
    // ============================================================

    /**
     * 对话探针。
     *
     * @param modelKey 指定链路条目名则<b>直连</b>那一档（绕过熔断和降级），
     *                 传 {@code auto} 或 null 则走完整降级链
     * @param question 提问
     */
    public Map<String, Object> chat(String modelKey, String question) {
        ChatRequest request = ChatRequest.of(null, question);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", isAuto(modelKey) ? "chain" : "direct");
        result.put("question", question);

        if (isAuto(modelKey)) {
            ModelCallTrace trace = new ModelCallTrace(newTraceId());
            ChatResponse response = router.chat(request, trace);
            result.put("answer", response.content());
            fillRoute(result, response, trace);
        } else {
            LlmClient client = requireClient(modelKey);
            ChatResponse response = client.chat(request);
            result.put("answer", response.content());
            result.put("provider", response.descriptor().provider());
            result.put("model", response.descriptor().modelId());
            result.put("modelKey", response.descriptor().modelKey());
            result.put("finishReason", response.finishReason());
            result.put("latencyMs", response.latencyMs());
            fillUsage(result, response.usage());
            result.put("cost",
                    costCalculator.calculate(response.descriptor(), response.usage()));
        }
        return result;
    }

    /**
     * 流式对话探针。
     *
     * <p>把每个增量<b>实时</b>通过 {@code onDelta} 回调送出去 ——
     * 直接观察它就能确认「打字机效果」是真的在流，
     * 而不是攒完一次性返回。
     *
     * @param onDelta 增量回调，由 Controller 接到 SSE 上
     */
    public Map<String, Object> chatStream(String modelKey, String question,
                                          java.util.function.Consumer<String> onDelta) {
        ChatRequest request = ChatRequest.of(null, question);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", "chatStream");
        result.put("modelKey", isAuto(modelKey) ? "auto" : modelKey);

        ModelCallTrace trace = new ModelCallTrace(newTraceId());

        // 记录「第一个字什么时候到」——这是验证打字机效果的关键指标。
        // 如果首字耗时接近总耗时，说明响应被缓冲了，根本没在流式推送。
        long start = System.nanoTime();
        List<Long> firstDeltaAt = new ArrayList<>(1);

        java.util.function.Consumer<String> instrumented = delta -> {
            if (firstDeltaAt.isEmpty()) {
                firstDeltaAt.add(System.nanoTime() - start);
            }
            onDelta.accept(delta);
        };

        Map<String, Object> streamResult = new LinkedHashMap<>();
        var sr = isAuto(modelKey)
                ? router.chatStream(request, trace, instrumented)
                : requireClient(modelKey).chatStream(request, instrumented);

        streamResult.put("contentChars", sr.contentChars());
        streamResult.put("finishReason", sr.finishReason());
        streamResult.put("ttfbMs", sr.ttfbMs());
        streamResult.put("totalMs", sr.totalMs());
        streamResult.put("firstDeltaMs", firstDeltaAt.isEmpty()
                ? null : firstDeltaAt.get(0) / 1_000_000L);
        result.put("stream", streamResult);

        if (isAuto(modelKey)) {
            // 走降级链：线路和成本由 router 填进 trace
            var route = trace.route();
            if (route != null) {
                result.put("provider", route.provider());
                result.put("model", route.modelId());
                result.put("modelKey", route.modelKey());
            }
            result.put("degraded", trace.degraded());
            result.put("degradationEvents", trace.events());
            result.put("cost", trace.cost());
        } else {
            // 直连某一档：绕过了 router，线路和成本要自己填 ——
            // 直连意味着没有降级链，degraded 恒为 false
            var descriptor = requireClient(modelKey).descriptor();
            result.put("provider", descriptor.provider());
            result.put("model", descriptor.modelId());
            result.put("modelKey", descriptor.modelKey());
            result.put("degraded", false);
            result.put("degradationEvents", List.of());
            result.put("cost", costCalculator.calculate(descriptor, sr.usage()));
        }
        fillUsage(result, sr.usage());
        return result;
    }

    // ============================================================
    // ② 向量化
    // ============================================================

    /**
     * 向量化探针。
     *
     * <p>重点验证一件事：<b>维度是不是 1024</b>。
     * 这是全项目的向量基准，和 {@code kb_chunk.embedding vector(1024)} 的建表一致。
     */
    public Map<String, Object> embedding(List<String> texts) {
        long start = System.nanoTime();
        EmbeddingResult result = embeddingClient.embed(texts);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inputCount", texts.size());
        out.put("vectorCount", result.size());
        out.put("dimension", result.dimension());
        // 前 5 个分量，用于肉眼确认「不是全 0、也不是 NaN」
        out.put("head", head(result.first(), 5));
        out.put("promptTokens", result.promptTokens());
        out.put("latencyMs", elapsedMs);
        return out;
    }

    /**
     * 计算两个文本的余弦相似度 —— 验证向量「有没有语义」。
     *
     * <p>语法相同的句子得分应该明显高于无关句子。
     * 这是比「维度对不对」更进一步的验证：
     * 维度对了但向量全是 0 的话，检索会静默失效。
     */
    public Map<String, Object> similarity(String textA, String textB) {
        EmbeddingResult result = embeddingClient.embed(List.of(textA, textB));
        float[] a = result.vectors().get(0);
        float[] b = result.vectors().get(1);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("textA", textA);
        out.put("textB", textB);
        out.put("dimension", a.length);
        out.put("cosineSimilarity", round(cosine(a, b), 4));
        return out;
    }

    // ============================================================
    // ③ 重排序
    // ============================================================

    /**
     * 重排序探针。
     *
     * <p>重点验证：<b>相关文档的得分是否显著高于不相关的</b>。
     * 阶段 2.1 实测时，相关 0.2458、不相关 0.0000167 —— 差四个数量级。
     */
    public Map<String, Object> rerank(String query, List<String> documents, Integer topN) {
        long start = System.nanoTime();
        RerankResult result = rerankClient.rerank(query, documents, topN);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        List<Map<String, Object>> items = new ArrayList<>();
        for (RerankResult.Item item : result.topN(topN == null ? result.size() : topN)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", item.index());
            row.put("score", round(item.score(), 6));
            // 回查原文 —— 服务端没回带 document（我们没开 return_documents）
            row.put("text", item.index() < documents.size() ? documents.get(item.index()) : null);
            items.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("candidateCount", documents.size());
        out.put("ranked", items);
        out.put("latencyMs", elapsedMs);
        return out;
    }

    // ============================================================
    // ④ 链路状态
    // ============================================================

    /**
     * 当前降级链与熔断器状态。
     *
     * <p>验收标准 2（改错 P0 的 Key 后自动降级）靠它观察 ——
     * 连续打几次之后能看到 P0 的熔断器从 CLOSED 变成 OPEN。
     */
    public Map<String, Object> chainStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chain", router.describeChain());
        return out;
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private boolean isAuto(String modelKey) {
        return modelKey == null || modelKey.isBlank() || "auto".equalsIgnoreCase(modelKey);
    }

    private LlmClient requireClient(String modelKey) {
        LlmClient client = router.byModelKey(modelKey);
        if (client == null) {
            throw new IllegalArgumentException(
                    "链路里没有模型 '%s'，可选：%s".formatted(modelKey, router.describeChain()));
        }
        return client;
    }

    private void fillRoute(Map<String, Object> result, ChatResponse response, ModelCallTrace trace) {
        result.put("provider", response.descriptor().provider());
        result.put("model", response.descriptor().modelId());
        result.put("modelKey", response.descriptor().modelKey());
        result.put("finishReason", response.finishReason());
        result.put("latencyMs", response.latencyMs());
        fillUsage(result, response.usage());
        result.put("cost", trace.cost());
        result.put("degraded", trace.degraded());
        result.put("degradationEvents", trace.events());
    }

    private void fillUsage(Map<String, Object> result, com.xbla.rag.client.dto.ChatUsage usage) {
        if (usage == null) {
            result.put("usage", null);
            return;
        }
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("promptTokens", usage.promptTokens());
        u.put("completionTokens", usage.completionTokens());
        u.put("totalTokens", usage.totalTokens());
        u.put("reasoningTokens", usage.reasoningTokens());
        u.put("cacheHitTokens", usage.promptCacheHitTokens());
        u.put("cacheMissTokens", usage.promptCacheMissTokens());
        result.put("usage", u);
    }

    private static List<Float> head(float[] arr, int n) {
        if (arr == null) {
            return List.of();
        }
        List<Float> out = new ArrayList<>(Math.min(n, arr.length));
        for (int i = 0; i < Math.min(n, arr.length); i++) {
            out.add(round(arr[i], 6));
        }
        return out;
    }

    /** 余弦相似度。两个向量都必须非空且等长 */
    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0d;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0d : dot / denom;
    }

    private static Double round(double v, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(v * factor) / factor;
    }

    private static Float round(float v, int digits) {
        double factor = Math.pow(10, digits);
        return (float) (Math.round(v * factor) / factor);
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
