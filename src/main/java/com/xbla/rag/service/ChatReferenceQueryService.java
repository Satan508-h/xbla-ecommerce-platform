package com.xbla.rag.service;

import com.xbla.rag.dto.ReferenceDetail;

/**
 * 一条引用的<b>原文</b> —— 前端「点开引用看原文」的数据源（阶段 9.6b 前置）。
 *
 * <h3>★ 只读、零成本、不调模型</h3>
 *
 * <p>查一行 {@code qa_log}（拿 {@code references}）+ 查一行 {@code kb_chunk}
 * （拿正文）。<b>不写库、不改状态、不花钱。</b>
 *
 * <h3>★★ 它为什么需要 {@code traceId} 而不只是 {@code chunkId}</h3>
 *
 * <p>见 {@link ChatReferenceNotFoundException} 的类注释 ——
 * <b>{@code traceId} 是这道口的封条</b>：没有它，「按 chunkId 取正文」
 * 就是「知道 id 就能枚举整个知识库」（id 是连续自增的）。
 * 有了它，能读到的切片必须已经被某条真实回答引用过。
 *
 * <h3>★ 历史消息里的引用【点不开】，这是已批准的边界</h3>
 *
 * <p>{@code chat_message} 里<b>没有</b> {@code trace_id} 那一列
 * （阶段 8 拍板「技术面板只服务当前回答」的直接后果，见
 * {@code ChatMessageView} 的注释）。所以从历史消息里读出来的引用
 * <b>构造不出这个请求</b> —— 前端手里根本没有 traceId。
 *
 * <p>★ 这不是「办不到」，是「那一次回答的链路记录本来就没和消息存在一起」。
 * 界面上的诚实表达是<b>「历史消息取不到原文」</b>，而不是让用户点了没反应
 * —— 同 {@code ReferenceList.vue} 里「没有引用」和「没检索」是两件事那条约定。
 */
public interface ChatReferenceQueryService {

    /**
     * 取一条引用的原文。
     *
     * @param traceId 这次问答的链路 id
     * @param chunkId 切片 id，<b>必须在这条 {@code qa_log} 的 {@code references} 里</b>
     * @throws ChatTraceNotFoundException       {@code qa_log} 里没有这个 traceId
     * @throws ChatReferenceNotFoundException   有这条记录，但它的引用里没有这个切片
     * @throws com.xbla.rag.service.ResourceNotFoundException
     *                                         有这条引用，但 {@code kb_chunk} 里
     *                                         取不到正文（切片被物理删掉了）
     */
    ReferenceDetail referenceDetail(String traceId, Long chunkId);
}
