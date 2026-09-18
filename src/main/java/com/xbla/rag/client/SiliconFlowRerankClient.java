package com.xbla.rag.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.client.dto.RerankResult;
import com.xbla.rag.client.dto.WireRerank;
import com.xbla.rag.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 重排序客户端 —— {@code POST /v1/rerank}，固定指向硅基流动的
 * {@code BAAI/bge-reranker-v2-m3}。
 *
 * <p><b>为什么这个类不叫 {@code OpenAiCompatibleRerankClient}？</b>
 * 因为 rerank <b>不是</b> OpenAI 协议的一部分 —— 它是各家自己扩展的接口。
 * 硅基流动的 {@code /v1/rerank} 字段名（{@code top_n} / {@code return_documents} /
 * {@code relevance_score}）是它自己的约定。
 * 命名上区分开，是为了让「将来要换一家重排序服务，这个类得重写」
 * 这件事在类名上就看得出来 —— 而对话客户端换供应商时只要改配置。
 *
 * <h3>实测的调用效果</h3>
 *
 * <p>查询「怎么退货」，候选文档三条，实测得分：
 * <pre>
 *   七天无理由退货政策说明  → 0.2458
 *   商品发货时效说明        → 0.0000167
 * </pre>
 * 相差四个数量级。★ 注意这个分数<b>不是 0~1 的相似度</b>，
 * 只能用来排序，不要当百分比展示给用户。
 */
@Slf4j
@Component
public class SiliconFlowRerankClient implements RerankClient {

    private static final String RERANK_PATH = "/rerank";

    private final OpenAiHttpTransport transport;
    private final ObjectMapper mapper;
    private final LlmProperties props;
    private final OpenAiHttpTransport.Endpoint endpoint;

    public SiliconFlowRerankClient(OpenAiHttpTransport transport,
                                   @Qualifier("modelObjectMapper") ObjectMapper mapper,
                                   LlmProperties props) {
        this.transport = transport;
        this.mapper = mapper;
        this.props = props;
        this.endpoint = transport.resolveEndpoint(props.getRerank().getProvider());
    }

    @Override
    public RerankResult rerank(String query, List<String> documents, Integer topN) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return new RerankResult(List.of());
        }

        // ★ 显式传 false：不要求服务端回带原文。
        //   我们手里本来就有原文，靠 index 回查即可 ——
        //   回带一份白白增加响应体积（重排的文档往往很长），没有任何收益。
        WireRerank.Request request = new WireRerank.Request(
                props.getRerank().getModelId(), query, documents, topN, false);

        String body = transport.postForString(endpoint, modelId(), RERANK_PATH,
                request, transport.defaults().getCompleteTimeout());

        WireRerank.Response response = parse(body);

        if (response.results() == null || response.results().isEmpty()) {
            throw ModelCallException.emptyContent(
                    endpoint.provider(), modelId(), "重排序响应里没有任何 results");
        }

        List<RerankResult.Item> items = new ArrayList<>(response.results().size());
        for (WireRerank.Response.Result r : response.results()) {
            if (r.index() == null) {
                continue;
            }
            // ★ 服务端返回的 index 必须落在原始文档列表范围内。
            //   越界的话调用方拿它去 documents.get(index) 会直接
            //   IndexOutOfBoundsException —— 与其让异常在业务代码深处爆，
            //   不如在这里就拦住，错误消息也能说清楚是哪来的数据有问题。
            if (r.index() < 0 || r.index() >= documents.size()) {
                throw new ModelCallException(
                        ModelErrorKind.SERVER_ERROR, 200, endpoint.provider(), modelId(),
                        body,
                        "重排序返回的下标 %d 越界（候选文档共 %d 条）"
                                .formatted(r.index(), documents.size()),
                        null);
            }
            items.add(new RerankResult.Item(
                    r.index(),
                    r.relevanceScore() == null ? 0d : r.relevanceScore(),
                    r.document()));
        }

        // 服务端声称已降序，但排序是要喂给下游做截断的 ——
        // 依赖对方保证不如自己排一次，代价可以忽略
        items.sort((a, b) -> Double.compare(b.score(), a.score()));

        return new RerankResult(items);
    }

    private WireRerank.Response parse(String body) {
        try {
            return mapper.readValue(body, WireRerank.Response.class);
        } catch (JsonProcessingException e) {
            throw new ModelCallException(
                    ModelErrorKind.SERVER_ERROR, 200, endpoint.provider(), modelId(),
                    body, "重排序响应解析失败: " + e.getMessage(), e);
        }
    }

    private String modelId() {
        return props.getRerank().getModelId();
    }
}
