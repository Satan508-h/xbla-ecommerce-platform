package com.xbla.rag.rag.rerank;

import com.xbla.rag.client.RerankClient;
import com.xbla.rag.client.dto.RerankResult;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用 {@code bge-reranker-v2-m3} 对候选切片做精排。
 *
 * <h2>一、重排在链路里的位置</h2>
 *
 * <pre>
 *   两路召回各 20 条 → RRF 融合去重，约 30 条 → ★ 重排精排 → 取前 5 条送进 prompt
 *   （快，但不够准）                            （准，但慢）
 * </pre>
 *
 * <p>为什么不能只留重排、跳过召回？因为重排要对<b>每一个 (问题, 候选) 对</b>
 * 做一次完整的模型前向计算。十万条切片逐条算，一次问答要几分钟。
 * 它的定位就是「在<b>小候选集</b>上做精排」，粗筛是向量和关键词的事。
 *
 * <h2>二、★ 失败必须回落，不能把整次检索带下去</h2>
 *
 * <p>{@link RerankClient} 的实现（{@code SiliconFlowRerankClient}）<b>没有降级链</b>——
 * 重排模型只有硅基流动提供，挂了就是挂了。一次 429 或一次超时，
 * 如果直接往上抛，整个问答就失败了。
 *
 * <p>而重排在本项目里的定位是<b>锦上添花</b>：没有它，RRF 融合的顺序
 * 仍然是一个合理的排序（实测能召回正解，只是位置不一定最优）。
 * 所以正确行为是<b>回落到融合顺序</b>，并在 trace 里记一条事件 ——
 * 质量打折，但用户无感。
 *
 * <h2>三、候选数上限的意义</h2>
 *
 * <p>重排的延迟和成本<b>与候选数成正比</b>（每个候选一次前向计算）。
 * {@code maxCandidates} 是这条链路上的成本闸门。
 * 融合后可能有几十条，超过上限的部分直接丢弃 —— 它们在融合里排名靠后，
 * 本来也不太可能进最终 Top-5。
 */
@Component
public class ChunkReranker {

    private static final Logger log = LoggerFactory.getLogger(ChunkReranker.class);

    private final RerankClient rerankClient;

    public ChunkReranker(RerankClient rerankClient) {
        this.rerankClient = rerankClient;
    }

    /**
     * 重排结果。
     *
     * @param chunks        重排后的切片。<b>无论成功失败都返回可用结果</b> ——
     *                      失败时就是原顺序截断后的列表，绝不返回 null
     * @param applied       重排是否真的生效了。false 表示走了回落路径
     * @param failureReason 回落的原因；成功时为 null
     */
    public record Outcome(List<RetrievedChunk> chunks, boolean applied, String failureReason) {

        static Outcome fellBack(List<RetrievedChunk> chunks, String reason) {
            return new Outcome(chunks, false, reason);
        }
    }

    /**
     * 精排。
     *
     * @param query         用户问题（阶段 4 之后可能是查询重写后的问题）
     * @param candidates    融合后的候选，<b>必须已按融合分数降序</b> ——
     *                      它决定了两件事：送进模型的是哪些候选、
     *                      以及失败时回落的顺序
     * @param maxCandidates 送进模型的最大候选数
     * @param topN          保留条数
     */
    public Outcome rerank(String query, List<RetrievedChunk> candidates, int maxCandidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return new Outcome(List.of(), false, null);
        }
        int limit = Math.min(candidates.size(), Math.max(1, maxCandidates));
        List<RetrievedChunk> window = candidates.subList(0, limit);

        try {
            List<String> documents = window.stream().map(RetrievedChunk::content).toList();

            // 把 topN 直接传给接口（而不是全取回来再本地截断）：
            // 少传一半候选就少一半计算量，这是对方服务器的成本，也是我们的延迟
            RerankResult result = rerankClient.rerank(query, documents, topN);

            List<RetrievedChunk> reranked = new ArrayList<>(result.size());
            for (RerankResult.Item item : result.items()) {
                int index = item.index();
                if (index < 0 || index >= window.size()) {
                    // 下标越界说明对方返回了不属于本次请求的下标。
                    // 静默跳过而不是抛异常 —— 少一条候选不影响整体，
                    // 但如果拿它去 get() 会抛 IndexOutOfBounds 把整次检索毁掉
                    log.warn("重排返回了越界下标 {}（候选数 {}），已跳过", index, window.size());
                    continue;
                }
                // ★ 用下标回原始候选列表取值，保留 content / headingPath / documentId。
                //   接口返回的 document 字段是 null（请求时没开 return_documents），
                //   而且即使返回了也不该用它 —— 那是对方服务器上的副本，
                //   我们的元数据（chunkId、标题路径）以本地为准
                reranked.add(window.get(index).withScore(item.score()));
            }

            if (reranked.isEmpty()) {
                // 接口成功但一条都没返回：语义上等于「什么都没排出来」，
                // 这不是「重排生效了只是结果为空」，而是异常情况
                return Outcome.fellBack(head(window, topN),
                        "重排返回了空结果（候选 " + window.size() + " 条）");
            }

            log.debug("重排完成 候选={} 返回={}", window.size(), reranked.size());
            return new Outcome(reranked, true, null);

        } catch (Exception e) {
            // ★ 捕获所有异常而不是只捕 ModelCallException：
            //   重排是锦上添花的一环，任何失败都不该让整次检索挂掉 ——
            //   包括我们没预料到的运行时异常（空指针、解析错误、越界）。
            //   宁可质量打折，不可整体失败
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("★ 重排失败，回落到 RRF 融合顺序：{}", reason);
            return Outcome.fellBack(head(window, topN), reason);
        }
    }

    /** 截取前 n 条；n 超过总数时返回全部 */
    private static List<RetrievedChunk> head(List<RetrievedChunk> chunks, int n) {
        int size = Math.min(chunks.size(), Math.max(1, n));
        return List.copyOf(chunks.subList(0, size));
    }
}
