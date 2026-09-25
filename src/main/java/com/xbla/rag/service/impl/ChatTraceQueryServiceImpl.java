package com.xbla.rag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.dto.TraceDetail;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.service.ChatTraceNotFoundException;
import com.xbla.rag.service.ChatTraceQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChatTraceQueryService} 的实现。
 *
 * <p>★ 这个类里<b>没有任何写操作</b>，也不调模型。
 */
@Slf4j
@Service
public class ChatTraceQueryServiceImpl implements ChatTraceQueryService {

    private final QaLogMapper qaLogMapper;
    private final ObjectMapper objectMapper;

    public ChatTraceQueryServiceImpl(QaLogMapper qaLogMapper, ObjectMapper objectMapper) {
        this.qaLogMapper = qaLogMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public TraceDetail traceDetail(String traceId) {
        // ★★ 用 selectOne 读【整个实体】，不是手写列清单。
        //
        //   QaLogMapper.selectByEvalRun 那种「显式列清单」在本项目坏了两次
        //   （漏 question、漏 prompt_tokens/completion_tokens），
        //   症状是一个看起来很合法的 0 —— 见 docs/10 坑 18。
        //   这里投影目标和实体完全一致，所以用 BaseMapper 生成的全列查询，
        //   让「读漏一列」在构造上不可能发生。
        //
        // ★ trace_id 上【没有】唯一约束，但「一个 traceId 一行」是事实上的
        //   不变量（2026-09-24 实测 4263 行 / 4263 个不同 traceId）。
        //   所以这里不 selectList().get(0) —— 真出现两行时让它抛
        //   TooManyResultsException，那是【不变量坏掉了】，该大声。
        //   静默挑一行会把一个数据问题伪装成一次正常的显示。
        QaLog row = qaLogMapper.selectOne(
                Wrappers.<QaLog>lambdaQuery().eq(QaLog::getTraceId, traceId));

        if (row == null) {
            throw new ChatTraceNotFoundException(traceId);
        }

        return new TraceDetail(
                row.getTraceId(),
                row.getStatus(),
                row.getIntent(),
                row.getIntentConfidence(),
                row.getProvider(),
                row.getModel(),
                row.getCost(),
                row.getPromptTokens(),
                row.getCompletionTokens(),
                row.getTotalTokens(),
                row.getDegradationEvents() != null,
                parseJson(row.getDegradationEvents()),
                buildLatency(row),
                buildRetrieval(parseJson(row.getRetrievalDetail())),
                parseJson(row.getToolCalls()));
    }

    // ============================================================
    // 五段延迟
    // ============================================================

    /**
     * 组装五段延迟，并算出「未归类」。
     *
     * <h3>★★★ 「未归类」是怎么来的：它是【减出来的】</h3>
     *
     * <pre>
     *   unclassified = total − queue − retrieval − llm
     * </pre>
     *
     * <p>它里面<b>主要是意图分类那一次模型往返</b> —— 那是一次 0.5~2.5 秒的
     * 调用（p50 实测 823ms），它进了 {@code total} 却不在任何一列里。
     * 详见 {@code docs/08} ADR-083。
     *
     * <h3>★★ 为什么 null 在这里当 0 用 —— 这是【查过数据】才敢下的判断</h3>
     *
     * <p>2026-09-24 实测（{@code status=1} 的 3641 行）：
     *
     * <pre>
     *   queue_ms 为 null        3637 行  ← 99.9%，是【常态】不是异常
     *   retrieval_latency_ms    272 行  ← 工具/澄清路径本来就不检索
     *   rerank_latency_ms       697 行  ← 没重排（重排关着，或候选为空）
     *   llm_latency_ms            0 行
     *   total_latency_ms          0 行
     * </pre>
     *
     * <p>所以这些 null 的语义是「<b>这一格没有发生</b>」（限流关着就没有排队），
     * 在减法里当 0 是对的。
     *
     * <p>⚠️ <b>但「null 当 0」是一个危险的默认</b> —— 如果 null 其实是
     * 「我们没读到那一列」，那算出来的「未归类」就会差一个数，而且
     * <b>看起来完全正常</b>（这正是坑 18 的形态）。
     * 这里之所以安全，是因为上面用的是<b>全列查询</b>：
     * 投影目标就是实体，没有「忘了加列」这个可能。
     *
     * <p>★ 反过来说，各个分段的 null <b>原样保留在响应里</b> ——
     * 前端能看到「这次没排队」，而不是看到一个 0。算术用 0、显示用 null，
     * 这两件事不矛盾：前者是「它对总和贡献多少」，后者是「它发生过没有」。
     *
     * <p>⚠️ 只有 {@code total} 和 {@code llm} 缺一个就<b>算不出来</b>，
     * 那时给 null（不编一个数出来）。
     */
    private static TraceDetail.Latency buildLatency(QaLog row) {
        Integer queue = row.getQueueMs();
        Integer retrieval = row.getRetrievalLatencyMs();
        Integer llm = row.getLlmLatencyMs();
        Integer total = row.getTotalLatencyMs();

        Integer unclassified = null;
        if (total != null && llm != null) {
            unclassified = total - zeroIfNull(queue) - zeroIfNull(retrieval) - llm;
        }

        return new TraceDetail.Latency(
                queue, retrieval, row.getRerankLatencyMs(), llm, total, unclassified);
    }

    private static int zeroIfNull(Integer v) {
        return v == null ? 0 : v;
    }

    // ============================================================
    // 检索范围与各段条数
    // ============================================================

    /**
     * 从 {@code retrieval_detail} 里取出 {@code filter} 和 {@code sizes} 两段。
     *
     * <h3>★★ ★ 三段状态，不是两态：没有检索 / 有检索但没记 sizes / 都记了</h3>
     *
     * <p>第三种情况是真实存在的：{@code sizes} 是<b>阶段 7.4 才加进去的</b>，
     * 在那之前写进库的行<b>根本没有这个段</b>（{@code docs/10} 记了这件事：
     * 「旧数据只能重跑才能对齐，重算报告没用」）。
     *
     * <pre>
     *   detail 为 null（这次没检索）        → 整个 retrieval 返回 null
     *   有 detail 但没有 sizes 段（老数据）  → 五个条数全是 null（= 不可得）
     *   有 sizes 段                        → 五个条数都是数
     * </pre>
     *
     * <p>★ 用 {@code asInt(0)} 兜底的话，老数据会显示「召回 0 条」——
     * 那是在撒谎，而且它看起来完全正常。这个项目的纪律是
     * <b>「读不到」和「没有做过」永远不许渲染成一个样子</b>。
     */
    private static TraceDetail.Retrieval buildRetrieval(JsonNode detail) {
        if (detail == null || !detail.isObject()) {
            // 工具意图不检索、澄清路径也不检索 —— 那是「没有发生」的诚实表达，
            // 不是「检索了但结果为空」（ADR-041 / ADR-067）
            return null;
        }

        JsonNode filter = objectOrNull(detail.get("filter"));
        JsonNode sizes = objectOrNull(detail.get("sizes"));

        return new TraceDetail.Retrieval(
                filter == null ? null : intListOrNull(filter.get("doc_types")),
                filter == null ? null : boolOrNull(filter.get("applied")),
                filter == null ? null : textOrNull(filter.get("reason")),
                filter == null ? null : intOrNull(filter.get("pool_size")),
                sizes == null ? null : intOrNull(sizes.get("vector_hits")),
                sizes == null ? null : intOrNull(sizes.get("keyword_hits")),
                sizes == null ? null : intOrNull(sizes.get("fused")),
                sizes == null ? null : intOrNull(sizes.get("reranked")),
                sizes == null ? null : intOrNull(sizes.get("final_top_k")));
    }

    // ============================================================
    // JSONB 解析
    // ============================================================

    /**
     * 把 JSONB 列读出来的文本解析回 JSON。
     *
     * <p>★ 空值返回 <b>null</b>（沿用「没有就是 NULL，不是空数组」的既有约定）。
     *
     * <p>★ catch 分支按约束不可达：这几列在库里都是 JSONB，
     * 而 PostgreSQL 在写入时就校验合法性 —— 非法 JSON 存不进去。
     * 真走到那里只说明列类型被改过，所以是 {@code log.error}。
     */
    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.error("JSONB 列反序列化失败 —— 该列应当永远是合法的 JSONB，"
                    + "走到这里说明列类型被改过。长度={} 异常={}", raw.length(), e.getMessage());
            return null;
        }
    }

    private static JsonNode objectOrNull(JsonNode node) {
        return node != null && node.isObject() ? node : null;
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private static Boolean boolOrNull(JsonNode node) {
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /**
     * ★ {@code []} 会原样返回<b>空列表</b>，不是 null —— 因为它有含义：
     * {@code doc_types: []} 表示「<b>不限制</b>」，见 ADR-044。
     * 把它变成 null 会让「明确声明不限制」和「没有声明」混在一起。
     */
    private static List<Integer> intListOrNull(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<Integer> out = new ArrayList<>(node.size());
        for (JsonNode n : node) {
            out.add(n.asInt());
        }
        return out;
    }
}
