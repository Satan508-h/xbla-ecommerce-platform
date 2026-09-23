package com.xbla.rag.rag;

import com.xbla.rag.rag.retrieve.RetrievedChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次检索的<b>轨迹收集器</b> —— 记录「这次检索到底经历了什么」。
 *
 * <h2>一、为什么需要它</h2>
 *
 * <p>{@code qa_log.retrieval_detail} 要存的是<b>链路上每一段的中间结果</b>：
 * 两路各自的原始召回、融合后、重排后、最终送进 prompt 的。
 * 没有这些，评测就只能回答「答对了/答错了」，回答不了
 * <b>「召回失败是因为向量检索没找到，还是重排排错了，还是切分粒度不对」</b>——
 * 而这三种情况的优化方向完全不同。
 *
 * <p>这是 {@code docs/04} 里 {@code retrieval_detail} 那列注释的原话，
 * 也是阶段 4 验收标准第 1 条（「能看到完整召回链路的中间输出」）的落点。
 *
 * <h2>二、为什么是「显式传参」而不是 ThreadLocal</h2>
 *
 * <p>和 {@code ModelCallTrace} <b>完全同构</b>，理由也一样：
 * <ul>
 *   <li>ThreadLocal 让数据流不可见 —— 看方法签名完全不知道有这回事；</li>
 *   <li>检索会在线程池里并行执行（两路并行），
 *       ThreadLocal 的值不会自动传到工作线程上；</li>
 *   <li>异常只能表达「失败了」，表达不了「成功了但降过级 / 有一条路失败了」。</li>
 * </ul>
 *
 * <p><b>生命周期</b>：
 * <pre>
 * RetrievalPipeline 创建 → 并行执行各路时往里填 → 读出来给
 *   ├─ RetrievalDetailBuilder（转成 retrieval_detail 的 JSON）
 *   └─ RagPromptBuilder（取最终切片拼 prompt）
 * </pre>
 *
 * <h2>三、线程安全</h2>
 *
 * <p>两路召回是<b>并行</b>跑的，所以「写」来自不同线程。
 * 字段一律用 {@link AtomicReference} / {@link CopyOnWriteArrayList}。
 * 这类容器读多写少，开销可忽略，而省下的是一类「看起来偶发、实际是可见性问题」
 * 的 bug。
 *
 * <h2>四、★ 关于 4.4 子问题拆分的扩展（尚未实现）</h2>
 *
 * <p>本类目前每路只存<b>一个</b>列表。开启 4.4 子问题拆分后，
 * 每个子问题都会产生自己的一路结果，而 <b>RRF 的名次是在单个列表内部计算的</b>——
 * 把 N 个子问题的结果倒进同一个列表会让名次失去意义。
 *
 * <p>正确的做法是每个子问题<b>各自融合一次</b>再合并。届时需要给
 * {@code vectorHits} / {@code keywordHits} 加一层「子问题下标」，
 * 并在 {@code retrieval_detail} 里做<b>加法式</b>扩展
 * （5 个 key 恒定不变，另加 {@code sub_questions} 数组）。
 *
 * <p>现在不实现，因为 {@code xbla.rag.rewrite.enabled} 默认关闭，
 * 而没有真实数据支撑的扩展设计只是猜测。
 */
public class RetrievalTrace {

    private final String traceId;

    /** 向量路的原始召回结果 */
    private final AtomicReference<List<RetrievedChunk>> vectorHits =
            new AtomicReference<>(List.of());

    /** 关键词路的原始召回结果 */
    private final AtomicReference<List<RetrievedChunk>> keywordHits =
            new AtomicReference<>(List.of());

    /** RRF 融合后的结果 */
    private final AtomicReference<List<RetrievedChunk>> fused =
            new AtomicReference<>(List.of());

    /** 重排后的结果。未启用或重排失败时等于 {@link #fused} */
    private final AtomicReference<List<RetrievedChunk>> reranked =
            new AtomicReference<>(List.of());

    /** 最终送进 prompt 的切片 */
    private final AtomicReference<List<RetrievedChunk>> finalChunks =
            new AtomicReference<>(List.of());

    /** 查询重写后的问题。★ 开关关闭时保持 null，<b>不写原文</b>，理由见 RetrievalProperties */
    private final AtomicReference<String> rewrittenQuestion = new AtomicReference<>();

    /** 子问题列表（4.4）。未启用时为空 */
    private final List<String> subQuestions = new CopyOnWriteArrayList<>();

    /**
     * 过程中的异常事件。
     *
     * <p>存的是给<b>人看的短句</b>，不是异常对象 —— 它最终会进
     * {@code retrieval_detail}（JSONB），塞异常进去会让序列化变成一场赌博。
     */
    private final List<String> events = new CopyOnWriteArrayList<>();

    /**
     * 失败的路名。
     *
     * <p>为什么要单独记而不是靠 {@link #events} 里有没有内容来判断：
     * 「这一路<b>失败了</b>」和「这一路<b>跑了但没匹配到</b>」是两件事 ——
     * 前者要降级，后者是正常结果。靠自然语言的事件文本去反推语义，
     * 迟早在改文案的时候出错。
     */
    private final List<String> failedLegs = new CopyOnWriteArrayList<>();

    private volatile int retrievalLatencyMs;
    private volatile int rerankLatencyMs;

    /**
     * 本次检索的范围过滤情况（阶段 5.4）。
     *
     * <p>初始值不是 {@code null} 而是一个「没声明范围」的实例 ——
     * 这样 {@code retrieval_detail} 里那一格<b>永远存在</b>，
     * 阶段 7 的脚本不用先判断它在不在。
     */
    private final AtomicReference<FilterScope> filter =
            new AtomicReference<>(FilterScope.none());

    public RetrievalTrace(String traceId) {
        this.traceId = traceId;
    }

    /**
     * 一次检索实际使用的 {@code doc_type} 范围过滤。
     *
     * <h3>★ {@code applied} 的定义：这次检索<b>最终</b>用了过滤吗</h3>
     *
     * <p>不是「打算用吗」、也不是「曾经用过吗」。所以<b>回落后它是 {@code false}</b> ——
     * 因为回落意味着最终跑的是全池查询，{@code vector_hits} 那些段里装的
     * 也是全池的结果。让 {@code applied} 和那几段说的是同一件事，
     * 这个 JSON 才是自洽的：任何一格单独拿出来都能正确解释其他格。
     *
     * <p>{@code reason} 负责解释「为什么」，把五种情况分开：
     *
     * <table border="1">
     *   <caption>reason 取值</caption>
     *   <tr><th>取值</th><th>applied</th><th>含义</th><th>该去查什么</th></tr>
     *   <tr><td>{@code disabled_by_config}</td><td>false</td>
     *       <td><b>声明了，但配置把「按意图下推」整个关掉了</b></td>
     *       <td>{@code xbla.rag.retrieve.by-intent.enabled} ——
     *           它关着时这里前面几行都够不着</td></tr>
     *   <tr><td>{@code no_declaration}</td><td>false</td>
     *       <td>没给范围（没开意图识别 / 分类失败 / 该意图声明空集）</td>
     *       <td>{@code qa_log.intent} 是 null 还是某个码</td></tr>
     *   <tr><td>{@code small_pool}</td><td>false</td>
     *       <td>范围太窄，池子比 top-k 还小，过滤不划算</td>
     *       <td>{@code pool_size}，以及意图树里那个叶子的声明</td></tr>
     *   <tr><td>{@code ok}</td><td>true</td>
     *       <td>过滤真的下推了</td>
     *       <td>——</td></tr>
     *   <tr><td>{@code empty_result}</td><td>false</td>
     *       <td>下推了但一条都没召回，改用全池重查</td>
     *       <td>{@code events}，以及 {@code search_text} 覆盖率探针</td></tr>
     * </table>
     *
     * <p>★ {@code disabled_by_config} 与 {@code no_declaration} 的
     * {@code applied} 都是 {@code false}、{@code docTypes} 都可能看着「很正常」，
     * 唯一的分野就是这一格。<b>这就是它必须单独存在、不能被合并的理由</b> ——
     * 合并之后「调用方没声明」和「配置关掉了」在 trace 上逐字相同，
     * 而这两件事的修法一个在调用点、一个在 {@code application.yml}。
     *
     * @param docTypes 调用方声明的集合，<b>如实记录</b>（即使最终没用上）。
     *                 ★ 空列表 = 没声明，但这只对 {@code no_declaration} 成立 ——
     *                 {@code disabled_by_config} 下它<b>通常非空</b>（声明照给，
     *                 只是没被用），所以「docTypes 空 = 没声明」这个推论
     *                 必须先看 {@code reason} 才能下
     * @param applied  最终是否真的下推了 {@code WHERE doc_type IN (...)}
     * @param poolSize 该集合在 {@code kb_chunk} 里的切片数；没测量时为 {@code null}
     * @param reason   为什么是现在这个状态
     */
    public record FilterScope(List<Integer> docTypes, boolean applied,
                              Integer poolSize, Reason reason) {

        /** 见 {@link FilterScope} 的表 */
        public enum Reason {
            /**
             * ★ 排在最前是因为它<b>最先被判断</b> —— 关掉时后面几种结局根本够不着。
             * 顺序在这里是文档，不是实现细节。
             */
            DISABLED_BY_CONFIG,
            NO_DECLARATION,
            SMALL_POOL,
            OK,
            EMPTY_RESULT;

            /** 进 JSON 的写法：小写下划线，和 {@code retrieval_detail} 里别的字段一致 */
            public String wireName() {
                return name().toLowerCase(java.util.Locale.ROOT);
            }
        }

        public FilterScope {
            docTypes = docTypes == null ? List.of() : List.copyOf(docTypes);
        }

        /** 没做任何范围声明的初始状态 */
        public static FilterScope none() {
            return new FilterScope(List.of(), false, null, Reason.NO_DECLARATION);
        }
    }

    // ================================================================
    // 写入（由 pipeline 调用）
    // ================================================================

    public void vectorHits(List<RetrievedChunk> hits) {
        vectorHits.set(hits == null ? List.of() : List.copyOf(hits));
    }

    public void keywordHits(List<RetrievedChunk> hits) {
        keywordHits.set(hits == null ? List.of() : List.copyOf(hits));
    }

    public void fused(List<RetrievedChunk> hits) {
        fused.set(hits == null ? List.of() : List.copyOf(hits));
    }

    public void reranked(List<RetrievedChunk> hits) {
        reranked.set(hits == null ? List.of() : List.copyOf(hits));
    }

    public void finalChunks(List<RetrievedChunk> hits) {
        finalChunks.set(hits == null ? List.of() : List.copyOf(hits));
    }

    public void rewrittenQuestion(String question) {
        rewrittenQuestion.set(question);
    }

    public void subQuestions(List<String> questions) {
        subQuestions.clear();
        if (questions != null) {
            subQuestions.addAll(questions);
        }
    }

    /**
     * 记一条过程事件。
     *
     * <p>典型用法：某一路召回的失败（{@code vector_failed: ...}）、
     * 重排失败回落（{@code rerank_failed: ...}）、
     * 查询切不出词元而跳过关键词路（{@code keyword_skipped: ...}）。
     *
     * <p>★ 这些事件<b>不进 {@code qa_log.status}</b>。
     * {@code status=2} 的语义是「模型链路失败」，把检索的问题混进去，
     * 阶段 7 统计时会把「检索挂了」算成「模型挂了」——
     * <b>归因彻底错乱，而且从数据上看不出来</b>。
     */
    public void event(String message) {
        events.add(message);
    }

    /**
     * 记一次「某一路召回失败」。
     *
     * <p>同时写进 {@link #events}（给人看）和 {@link #failedLegs}（给代码判断）。
     *
     * @param legName 路名，{@code "vector"} 或 {@code "keyword"}
     * @param reason  简短原因，会进 {@code retrieval_detail}
     */
    public void legFailed(String legName, String reason) {
        failedLegs.add(legName);
        event(legName + "_failed: " + reason);
    }

    public void retrievalLatencyMs(int ms) {
        this.retrievalLatencyMs = ms;
    }

    /** 记录本次的范围过滤情况。见 {@link FilterScope} 关于 {@code applied} 的定义 */
    public void filter(FilterScope scope) {
        filter.set(scope == null ? FilterScope.none() : scope);
    }

    public void rerankLatencyMs(int ms) {
        this.rerankLatencyMs = ms;
    }

    // ================================================================
    // 读取
    // ================================================================

    public String traceId() {
        return traceId;
    }

    public List<RetrievedChunk> vectorHits() {
        return vectorHits.get();
    }

    public List<RetrievedChunk> keywordHits() {
        return keywordHits.get();
    }

    public List<RetrievedChunk> fused() {
        return fused.get();
    }

    public List<RetrievedChunk> reranked() {
        return reranked.get();
    }

    public List<RetrievedChunk> finalChunks() {
        return finalChunks.get();
    }

    public String rewrittenQuestion() {
        return rewrittenQuestion.get();
    }

    public List<String> subQuestions() {
        return List.copyOf(subQuestions);
    }

    public List<String> events() {
        return List.copyOf(events);
    }

    public int retrievalLatencyMs() {
        return retrievalLatencyMs;
    }

    public int rerankLatencyMs() {
        return rerankLatencyMs;
    }

    /** 本次的范围过滤情况。<b>永不为 null</b>（没做过就是 {@link FilterScope#none()}） */
    public FilterScope filter() {
        return filter.get();
    }

    /** 失败的路名列表 */
    public List<String> failedLegs() {
        return List.copyOf(failedLegs);
    }

    /**
     * 是否某一路失败了（另一路可能仍然有结果）。
     *
     * <p>此时检索<b>不应该整体失败</b> —— 用另一路的结果照常往下走，
     * 只是质量打了折扣。检索是「尽力而为」的服务。
     */
    public boolean anyLegFailed() {
        return !failedLegs.isEmpty();
    }

    /**
     * 两路<b>都</b>失败了。
     *
     * <p>即便如此也<b>不抛异常</b>：返回空的检索结果，让问答退化成
     * 「没有知识库上下文的裸聊」。理由 —— 用户问一个问题，
     * 我们至少还能给出一个基于模型自身知识的回答；
     * 而抛异常会让整次问答 500，那是更差的体验。
     */
    public boolean bothLegsFailed() {
        return failedLegs.contains("vector") && failedLegs.contains("keyword");
    }

    /** 最终送进 prompt 的切片 ID，按顺序 */
    public List<Long> finalChunkIds() {
        List<Long> ids = new ArrayList<>(finalChunks.get().size());
        for (RetrievedChunk chunk : finalChunks.get()) {
            ids.add(chunk.id());
        }
        return ids;
    }

    /** 一行摘要，打日志用 */
    public String summary() {
        FilterScope scope = filter.get();
        return "trace=" + traceId
                + " 向量=" + vectorHits.get().size()
                + " 关键词=" + keywordHits.get().size()
                + " 融合=" + fused.get().size()
                + " 重排=" + reranked.get().size()
                + " 最终=" + finalChunks.get().size()
                // 过滤那一段只在真的有声明时才打 —— 绝大多数请求是 no_declaration，
                // 每次都打一个 "范围=[]" 只会稀释日志里真正有信息的那部分
                + (scope.docTypes().isEmpty() ? ""
                        : " 范围=" + scope.docTypes()
                          + "(" + scope.reason().wireName()
                          + "/池" + scope.poolSize() + ")")
                + " 检索耗时=" + retrievalLatencyMs + "ms"
                + " 重排耗时=" + rerankLatencyMs + "ms";
    }
}
