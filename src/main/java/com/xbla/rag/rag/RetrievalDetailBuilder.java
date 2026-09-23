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
 *     "final_top_k":  [45, 12, 8],
 *     "filter":       {"doc_types":[2,4], "applied":true, "pool_size":67, "reason":"ok"},
 *     "sizes":        {"vector_hits":20, "keyword_hits":20, "fused":37,
 *                      "reranked":5, "final_top_k":5}
 *   }
 * </pre>
 *
 * <p>注意三段的分值字段名<b>各不相同</b>（{@code score} / {@code rrf_score} /
 * {@code rerank_score}）—— 这不是笔误，是刻意的：它们的量纲完全不可比，
 * 用同一个名字会诱使后来的人拿它们互相比较。见 {@link RetrievedChunk} 的注释。
 *
 * <p><b>★ {@code filter} 是阶段 5.4 加的第六个 key，{@code sizes} 是阶段 7 加的第七个。</b>
 * 两次都是「扩了冻结结构」，但原来那些 key 一个都没动、含义一个都没变，所以阶段 4 的
 * 基线脚本（{@code scripts/eval_baseline.py} 按固定路径取 {@code reranked} 等段）
 * <b>不受影响</b>。这是「加法式扩展」而不是「改结构」——
 * 两者的区别是前者不需要重跑历史基线。
 *
 * <h2>三、★ 必须截断</h2>
 *
 * <p>每段最多记 {@value #MAX_ENTRIES_PER_SECTION} 条。{@code qa_log} 是全项目
 * <b>写入最频繁</b>的表，而 {@code retrieval_detail} 是一个 JSONB 大字段；
 * 不截断的话，召回条数一调大（阶段 7 很可能这么干），这张表就会迅速膨胀。
 *
 * <h3>★★ 所以截断这件事本身必须被记下来 —— 那就是 {@code sizes}</h3>
 *
 * <p>截断有一个不会报错的后果：<b>「这一段里没有它」和「它被截掉了」在数据上
 * 长得一模一样</b>。阶段 7 的归因表要回答「正解死在链路哪一步」，而
 * <pre>
 *   正解不在 fused 里  →  可能是「融合把它丢了」（该调 RRF 参数）
 *                        也可能是「融合留下了它，只是排在第 25 名、被记录截掉」
 *                        （什么都不用调）—— 两个结论相反
 * </pre>
 *
 * <p>没有 {@code sizes} 时，评测脚本只能<b>断言</b>底部那几段没被截到；
 * 有了它，这件事变成<b>可读出来的事实</b>：某一段的 {@code size} 大于它的
 * 实际条数，就说明这一段被截过，在它里面的「找不到」必须降级成「不知道」。
 *
 * <p>★ 2026-09-21 实测：当时 {@code fused} 每行都是<b>恰好 20 条</b>，而 RRF
 * 返回的是两路的<b>完整并集</b>（20~40 条）—— 也就是说这个坑当时就在脚下，
 * 只是恰好没有一个正解落在第 21 名之后（389 道题里 0 道）。
 * 这种「这次没事」不是安全，是运气。<b>写下来它才从运气变成证据。</b>
 *
 * <h2>四、★ 字段分两类，判断标准是「它属不属于那五段」</h2>
 *
 * <p><b>恒定存在的 7 个 key</b>（{@code vector_hits} 到 {@code sizes}）——
 * 语义永不改变，<b>永远出现</b>，哪怕值是空数组。
 * 阶段 7 的对比脚本可以按固定路径直接读，不用先判断 key 在不在。
 *
 * <p><b>附加字段</b>（{@code events} / {@code sub_questions} /
 * {@code rewritten_question}）—— 只在有内容时才出现。
 *
 * <p>{@code filter} 属于前者而不是后者，是因为「<b>这次到底过滤了没有</b>」
 * 是一次检索的固有属性：即使答案是「没有」，那也是一个必须能被机器读到的事实。
 * 把它做成「非空才出现」，脚本就得写「key 不在 == 没过滤」这种隐式约定 ——
 * 而隐式约定在改代码时会静默失效。
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
        detail.put("filter", filterSection(trace.filter()));
        detail.put("sizes", sizesSection(trace));

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
     * 把范围过滤的情况转成 {@code filter} 那一格（阶段 5.4）。
     *
     * <p>四个字段各有各的用途，缺一个就会让另一个变得难解释：
     * <ul>
     *   <li>{@code doc_types} —— <b>声明了什么</b>。即使最终没用上也如实记，
     *       因为「声明了但被跳过」和「压根没声明」要靠它区分</li>
     *   <li>{@code applied} —— <b>最终用了没有</b>。它和上面那 5 段说的是同一件事：
     *       {@code applied=false} 时那些结果是全池查出来的</li>
     *   <li>{@code pool_size} —— 该集合在全库有多少条切片。
     *       ★ 这是排查时第一个该看的数：池子小说明意图声明得窄，
     *       池子大却召回不到说明是检索或切分的问题 —— <b>两种原因的修法完全相反</b></li>
     *   <li>{@code reason} —— 为什么是现在这个状态。取值见
     *       {@link RetrievalTrace.FilterScope.Reason}</li>
     * </ul>
     *
     * <p>{@code pool_size} 可能是 {@code null}（没测量过），
     * 而 {@code null} 在 JSONB 里是存得下的 —— 但<b>不能省略这个 key</b>，
     * 省略了就分不清「没测」和「忘了写」。
     */
    private static Map<String, Object> filterSection(RetrievalTrace.FilterScope scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doc_types", scope.docTypes());
        out.put("applied", scope.applied());
        out.put("pool_size", scope.poolSize());
        out.put("reason", scope.reason().wireName());
        return out;
    }

    /**
     * 每一段<b>在截断之前</b>有多少条（阶段 7）。
     *
     * <p>和 {@link #withScore} 读的是<b>同一个 {@code trace}</b> ——
     * 所以「记录了几条」和「本来有几条」永远不会各自漂移。
     * ★ 这一点是刻意的：如果 {@code sizes} 由 pipeline 另行汇报，
     * 那它就是第二个事实来源，而两个来源迟早会说不同的话。
     *
     * <p>⚠️ <b>{@code sizes}</b> 这个 key 在 V5 的结构里没有，是阶段 7 新增的。
     * 已经落库的历史行里<b>没有它</b> —— 读的一方必须把「缺席」解释成
     * <b>「不知道有没有被截断」</b>，而不是「没被截断」。
     * 把缺席当成后者，就是拿一个没测过的假设去支撑归因结论。
     * 报告端点里那段判空就是为此而写。
     */
    private static Map<String, Object> sizesSection(RetrievalTrace trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vector_hits", trace.vectorHits().size());
        out.put("keyword_hits", trace.keywordHits().size());
        out.put("fused", trace.fused().size());
        out.put("reranked", trace.reranked().size());
        out.put("final_top_k", trace.finalChunks().size());
        return out;
    }

    /**
     * 把一段结果转成 {@code [{chunk_id, <分值字段名>}]}。
     *
     * <p>只记 id 和分数，<b>不记正文</b> —— 正文在 {@code kb_chunk} 里，
     * 按 id 就能取回。把正文也塞进来会让这个 JSONB 字段的体积再涨一个数量级。
     *
     * <p>★ 这里就是截断实际发生的地方。{@code sizes} 记的是<b>传进来的 {@code chunks}
     * 的长度</b>（截断之前），所以「记录条数 &lt; size」当且仅当这一段被截过。
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
