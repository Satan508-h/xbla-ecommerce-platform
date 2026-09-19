package com.xbla.rag.rag;

import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link RetrievalTrace} 转成 {@code qa_log.retrieval_detail} 的结构。
 *
 * <h2>一、★ 为什么必须是一个独立的纯函数</h2>
 *
 * <p>因为<b>验收标准 1 和落库结构必须是同一份东西</b>。
 *
 * <p>路线图阶段 4 验收标准第 1 条是「给定 20 个测试问题，能看到完整召回链路的
 * 中间输出（每一路的原始结果、融合后、重排后）」；而 {@code qa_log.retrieval_detail}
 * 要存的是同样的四段。如果调试接口自己拼一份、落库时再拼一份，
 * 两份迟早会不一致 —— 到那时「验收证据」就不能证明「线上真的这么跑的」了。
 *
 * <p>所以：{@code GET /api/debug/kb/retrieve} 和 {@code ChatServiceImpl} 落库
 * <b>调的是本类的同一个方法</b>。
 *
 * <h2>二、★ 结构是冻结的</h2>
 *
 * <p>{@code V5__chat_and_observability.sql} 里已经把这个结构写死在注释里了，
 * 本类必须严格照做：
 *
 * <pre>
 *   {
 *     "vector_hits":  [{"chunk_id":12, "score":0.87}],
 *     "keyword_hits": [{"chunk_id":45, "score":3.2}],
 *     "fused":        [{"chunk_id":12, "rrf_score":0.032}],
 *     "reranked":     [{"chunk_id":45, "rerank_score":0.95}],
 *     "final_top_k":  [45, 12, 8]
 *   }
 * </pre>
 *
 * <p>注意三段的分值字段名<b>各不相同</b>（{@code score} / {@code rrf_score} /
 * {@code rerank_score}）—— 这不是笔误，是刻意的：它们的量纲完全不可比，
 * 用同一个名字会诱使后来的人拿它们互相比较。见 {@link RetrievedChunk} 的注释。
 *
 * <h2>三、★ 必须截断</h2>
 *
 * <p>每段最多记 {@value #MAX_ENTRIES_PER_SECTION} 条。{@code qa_log} 是全项目
 * <b>写入最频繁</b>的表，而 {@code retrieval_detail} 是一个 JSONB 大字段；
 * 不截断的话，召回条数一调大（阶段 7 很可能这么干），这张表就会迅速膨胀。
 *
 * <h2>四、★ 附加字段是「加法式」的</h2>
 *
 * <p>上面 5 个 key <b>恒定存在且语义永不改变</b>。额外的诊断信息
 * （{@code events} / {@code sub_questions}）只在非空时才出现，
 * 且不改变那 5 个 key 的任何含义。这样阶段 7 的对比脚本可以直接按
 * 固定路径读取，不用管有没有附加字段。
 */
@Component
public class RetrievalDetailBuilder {

    /** 每段最多记录多少条。见类注释「必须截断」 */
    public static final int MAX_ENTRIES_PER_SECTION = 20;

    /**
     * 构建。{@code trace} 为 null 时返回 <b>null</b>（不是空 Map）。
     *
     * <p>为什么空要写 null 而不是 {@code {}}：和 {@code ChatServiceImpl.serializeEvents}
     * 的既有约定一致 —— 查询时可以写 {@code WHERE retrieval_detail IS NOT NULL}
     * 一眼筛出「这一次真的做了检索」的记录。空 Map 会让那个查询失去意义。
     */
    public Map<String, Object> build(RetrievalTrace trace) {
        if (trace == null) {
            return null;
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("vector_hits", withScore(trace.vectorHits(), "score"));
        detail.put("keyword_hits", withScore(trace.keywordHits(), "score"));
        detail.put("fused", withScore(trace.fused(), "rrf_score"));
        detail.put("reranked", withScore(trace.reranked(), "rerank_score"));
        detail.put("final_top_k", trace.finalChunkIds());

        // ── 以下是附加字段，只在有内容时出现 ──
        List<String> events = trace.events();
        if (!events.isEmpty()) {
            detail.put("events", List.copyOf(events));
        }
        List<String> subQuestions = trace.subQuestions();
        if (!subQuestions.isEmpty()) {
            detail.put("sub_questions", subQuestions);
        }
        if (trace.rewrittenQuestion() != null) {
            // 与 qa_log.rewritten_question 同源。这里冗余一份是为了让
            // 「把这个 JSON 单独拷出来」时它是一份自洽的证据
            detail.put("rewritten_question", trace.rewrittenQuestion());
        }
        return detail;
    }

    /**
     * 把一段结果转成 {@code [{chunk_id, <分值字段名>}]}。
     *
     * <p>只记 id 和分数，<b>不记正文</b> —— 正文在 {@code kb_chunk} 里，
     * 按 id 就能取回。把正文也塞进来会让这个 JSONB 字段的体积再涨一个数量级。
     */
    private static List<Map<String, Object>> withScore(List<RetrievedChunk> chunks, String scoreField) {
        int size = Math.min(chunks.size(), MAX_ENTRIES_PER_SECTION);
        List<Map<String, Object>> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            RetrievedChunk chunk = chunks.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("chunk_id", chunk.id());
            entry.put(scoreField, round6(chunk.score()));
            out.add(entry);
        }
        return out;
    }

    /**
     * 统一保留 6 位小数。
     *
     * <p>不是为了省字节，是为了让<b>两次运行的 JSON 可以直接 diff</b>。
     * double 打印出来会有 {@code 0.6934000000000001} 这种尾巴，
     * 而阶段 7 要对着两份报告做差异比对。
     * （同理见 {@code ModelCostCalculator} 里对齐 {@code NUMERIC(10,6)} 的处理。）
     */
    private static double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
