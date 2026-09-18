package com.xbla.rag.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.client.dto.EmbeddingResult;
import com.xbla.rag.client.dto.WireEmbedding;
import com.xbla.rag.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 向量化客户端 —— OpenAI 兼容协议的 {@code POST /v1/embeddings}。
 *
 * <p>本项目里它固定指向<b>硅基流动</b>的 {@code BAAI/bge-m3}
 * （DeepSeek 官方不提供向量化能力）。
 *
 * <h3>★ 维度校验为什么是硬失败</h3>
 *
 * <p>每次调用后都会检查返回的向量维度是否等于配置里的
 * {@code xbla.llm.embedding.dimension}（1024）。不匹配就抛异常。
 *
 * <p>理由是<b>维度不一致会静默损坏数据</b>：如果哪天换了个 768 维的模型，
 * 而配置没改，写进 {@code kb_chunk.embedding}（建表时是 {@code vector(1024)}）
 * 时 PostgreSQL 会直接报错 —— 那还算好的。更糟的是如果表也跟着改了，
 * 新老向量维度不一致，余弦相似度计算会得到毫无意义的结果，
 * 而<b>检索仍然「能跑」</b>，只是结果全错。这种问题几乎不可能靠人工发现。
 *
 * <p>所以宁可在这里响亮地失败。
 */
@Slf4j
@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final String EMBEDDINGS_PATH = "/embeddings";

    /**
     * 单次请求的最大文本条数。
     *
     * <p>硅基流动对批量大小有上限（不同模型不同），超过会返回 400。
     * 这里取一个保守值并<b>自动分批</b> ——
     * 让调用方（阶段 3 灌知识库）不用关心供应商的限制。
     */
    private static final int MAX_BATCH_SIZE = 32;

    private final OpenAiHttpTransport transport;
    private final ObjectMapper mapper;
    private final LlmProperties props;
    private final OpenAiHttpTransport.Endpoint endpoint;

    public OpenAiCompatibleEmbeddingClient(OpenAiHttpTransport transport,
                                           @Qualifier("modelObjectMapper") ObjectMapper mapper,
                                           LlmProperties props) {
        this.transport = transport;
        this.mapper = mapper;
        this.props = props;
        this.endpoint = transport.resolveEndpoint(props.getEmbedding().getProvider());
    }

    @Override
    public EmbeddingResult embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new EmbeddingResult(List.of(), 0);
        }

        List<float[]> allVectors = new ArrayList<>(texts.size());
        int totalTokens = 0;

        // ★ 自动分批：调用方传多少条都行，这里按供应商限制切开
        for (int from = 0; from < texts.size(); from += MAX_BATCH_SIZE) {
            int to = Math.min(from + MAX_BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(from, to);

            WireEmbedding.Response response = callOnce(batch);
            allVectors.addAll(toFloatVectors(response, batch.size()));

            if (response.usage() != null && response.usage().promptTokens() != null) {
                totalTokens += response.usage().promptTokens();
            }
        }

        return new EmbeddingResult(allVectors, totalTokens);
    }

    private WireEmbedding.Response callOnce(List<String> batch) {
        WireEmbedding.Request request = new WireEmbedding.Request(
                props.getEmbedding().getModelId(), batch);

        String body = transport.postForString(endpoint, modelId(), EMBEDDINGS_PATH,
                request, transport.defaults().getCompleteTimeout());

        try {
            return mapper.readValue(body, WireEmbedding.Response.class);
        } catch (JsonProcessingException e) {
            throw new ModelCallException(
                    ModelErrorKind.SERVER_ERROR, 200, endpoint.provider(), modelId(),
                    body, "向量化响应解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把线格式的 {@code List<Double>} 转成 {@code float[]}，并校验维度。
     *
     * <p>转换用 {@code index} 字段对齐而不是直接按数组顺序 ——
     * 批量接口在服务端并发处理时，返回顺序不保证与输入一致。
     */
    private List<float[]> toFloatVectors(WireEmbedding.Response response, int expectedCount) {
        if (response.data() == null || response.data().isEmpty()) {
            throw ModelCallException.emptyContent(
                    endpoint.provider(), modelId(), "向量化响应里没有任何 data");
        }

        int expectedDim = props.getEmbedding().getDimension();

        // 按 index 排序后取向量，保证与输入顺序一一对应
        List<WireEmbedding.Response.EmbeddingData> sorted = new ArrayList<>(response.data());
        sorted.sort(Comparator.comparing(
                d -> d.index() == null ? Integer.MAX_VALUE : d.index()));

        List<float[]> vectors = new ArrayList<>(sorted.size());
        for (WireEmbedding.Response.EmbeddingData item : sorted) {
            List<Double> raw = item.embedding();
            if (raw == null) {
                throw ModelCallException.emptyContent(
                        endpoint.provider(), modelId(), "某个向量为 null");
            }
            if (raw.size() != expectedDim) {
                throw new ModelCallException(
                        ModelErrorKind.SERVER_ERROR, 200, endpoint.provider(), modelId(),
                        null,
                        ("★ 向量维度不匹配：模型 %s 返回 %d 维，但配置里写的是 %d 维。" +
                                "维度是全项目基准（kb_chunk.embedding 建表时是 vector(%d)），" +
                                "不一致会静默损坏检索质量 —— 请核对 " +
                                "xbla.llm.embedding.dimension 与数据库表结构")
                                .formatted(props.getEmbedding().getModelId(),
                                        raw.size(), expectedDim, expectedDim),
                        null);
            }
            vectors.add(toFloatArray(raw));
        }

        if (vectors.size() != expectedCount) {
            log.warn("向量化返回条数({})与请求条数({})不一致 provider={} model={}",
                    vectors.size(), expectedCount, endpoint.provider(), modelId());
        }
        return vectors;
    }

    private static float[] toFloatArray(List<Double> raw) {
        float[] arr = new float[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            Double v = raw.get(i);
            arr[i] = v == null ? 0f : v.floatValue();
        }
        return arr;
    }

    private String modelId() {
        return props.getEmbedding().getModelId();
    }
}
