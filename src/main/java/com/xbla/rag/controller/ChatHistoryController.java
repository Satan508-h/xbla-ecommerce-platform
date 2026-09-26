package com.xbla.rag.controller;

import com.xbla.rag.common.ApiResponse;
import com.xbla.rag.dto.ChatMessageView;
import com.xbla.rag.dto.ChatSessionSummary;
import com.xbla.rag.dto.ReferenceDetail;
import com.xbla.rag.dto.TraceDetail;
import com.xbla.rag.service.ChatHistoryQueryService;
import com.xbla.rag.service.ChatReferenceQueryService;
import com.xbla.rag.service.ChatTraceQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话历史接口 —— 阶段 8 前端「历史会话列表 + 切换」的数据源。
 *
 * <p>按 CLAUDE.md 的约定，这个类<b>只做参数校验和响应封装</b>：
 * 夹紧 limit、拼 {@link ApiResponse}，别的都在 {@link ChatHistoryQueryService} 里。
 *
 * <h3>★ 为什么和 {@link ChatController} 分开</h3>
 *
 * <p>两者都挂在 {@code /api/chat} 下，但它们是<b>两个方向</b>：
 * {@code ChatController} 是写路径（问答，一次请求会调模型、会花钱），
 * 这里是读路径（翻历史，纯查库、零成本）。
 *
 * <p>分开的实际收益是<b>边界看得见</b>：
 * 「这个类里不会有任何一次模型调用」是一个光看类名就能成立的判断，
 * 而不是需要读完整个文件才知道的事。
 *
 * <p>★ 类名里的「History」宽了半格：除了会话和消息，这里还有两个
 * <b>回看一次已发生的问答</b>的接口（{@code /trace/{traceId}} 和
 * {@code /refs/{traceId}/{chunkId}}）。它们和会话历史的共同点正是那条边界 ——
 * <b>都只读、都按精确键查、都不花钱</b>。真正的判据是那条边界，不是类名。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatHistoryController {

    private final ChatHistoryQueryService chatHistoryService;
    private final ChatTraceQueryService chatTraceQueryService;
    private final ChatReferenceQueryService chatReferenceQueryService;

    public ChatHistoryController(ChatHistoryQueryService chatHistoryService,
                                 ChatTraceQueryService chatTraceQueryService,
                                 ChatReferenceQueryService chatReferenceQueryService) {
        this.chatHistoryService = chatHistoryService;
        this.chatTraceQueryService = chatTraceQueryService;
        this.chatReferenceQueryService = chatReferenceQueryService;
    }

    /**
     * 会话列表，最近活跃的在前。★ <b>已排除评测流量</b>
     * （阶段 7 跑了 4022 个评测会话，不排的话列表 97.8% 是噪声）——
     * 判据与实测数字见 {@code ChatSessionMapper.selectRealSessions}。
     *
     * @param limit 最多几条。缺省 30；非法值（≤0）当缺省、超过 200 夹到 200，
     *              两种夹法都写在 {@code ChatHistoryQueryServiceImpl.clampLimit} 里
     */
    @GetMapping("/sessions")
    public ApiResponse<List<ChatSessionSummary>> sessions(
            @RequestParam(value = "limit", defaultValue = "30") int limit) {
        return ApiResponse.ok(chatHistoryService.listRealSessions(limit));
    }

    /**
     * 某个会话的全部消息，按写入顺序。
     *
     * <p>★ 这里<b>不</b>排除评测会话，和上面那个方法是故意不一致的 ——
     * 理由是「排除评测流量」属于<b>列表这个读模型</b>，
     * 而这是一个<b>按精确键查找</b>，见 {@code ChatHistoryQueryService.listMessages} 的注释。
     *
     * @throws com.xbla.rag.service.ChatSessionNotFoundException 这个 sessionNo 不存在 →
     *         由 {@code GlobalExceptionHandler} 转成 HTTP 404。
     *         ★ 不回空列表：那会把「没有这个会话」和「这个会话没有消息」渲染成同一个响应
     */
    @GetMapping("/sessions/{sessionNo}/messages")
    public ApiResponse<List<ChatMessageView>> messages(
            @PathVariable("sessionNo") String sessionNo) {
        return ApiResponse.ok(chatHistoryService.listMessages(sessionNo));
    }

    /**
     * 一次问答的技术细节 —— 前端的「技术细节」面板。
     *
     * <pre>
     * curl -s localhost:8080/api/chat/trace/&lt;traceId&gt; | python -m json.tool
     * </pre>
     *
     * <p>★ 返回<b>意图 / 下推的 doc_types / 五段延迟 / 供应商 / 成本 / 工具调用</b>，
     * 全部来自 {@code qa_log}。这套东西是本项目「可量化」那个卖点的界面化 ——
     * 没有它，演示时那些数字只能靠讲。
     *
     * <p>★ <b>零成本</b>：只查一行 {@code qa_log}，不调用任何模型。
     *
     * <p>⚠️ 为什么不用现成的 {@code /api/debug/mcp/qa-log?traceId=}：
     * 那是 {@code @Profile("local")} 的，生产环境下不存在，而且
     * {@code /api/debug/**} 绝不能跟着公网隧道出去。见
     * {@code ChatTraceQueryService} 的类注释。
     *
     * @throws com.xbla.rag.service.ChatTraceNotFoundException 这个 traceId 不存在 →
     *         由 {@code GlobalExceptionHandler} 转成 HTTP 404。
     *         ★ 正常情况下不该发生：{@code qa_log} 的落库在 SSE 的 {@code done}
     *         事件之前（{@code ChatServiceImpl} 里的顺序），所以拿到 traceId
     *         之后立刻来查是安全的。但前端仍要能容忍它 ——
     *         那条顺序是流程的自然顺序，不是显式契约
     */
    @GetMapping("/trace/{traceId}")
    public ApiResponse<TraceDetail> trace(@PathVariable("traceId") String traceId) {
        return ApiResponse.ok(chatTraceQueryService.traceDetail(traceId));
    }

    /**
     * 一条引用的<b>原文</b> —— 前端「点开引用看原文」的数据源（9.6b 前置）。
     *
     * <pre>
     * curl -s localhost:8080/api/chat/refs/&lt;traceId&gt;/15 | python -m json.tool
     * </pre>
     *
     * <h3>★★ 路径里为什么必须有 {@code traceId}</h3>
     *
     * <p>只有 {@code chunkId} 的话，这就是一个<b>知道 id 就能读任意切片</b>的接口
     * —— 而 {@code kb_chunk.id} 是 {@code BIGSERIAL}，连续自增，枚举成本约等于零。
     * 带上 {@code traceId} 之后，服务端会先确认<b>这一片真的被这条回答引用过</b>
     * 才去取正文，而 {@code traceId} 是一次性、不可猜的。
     *
     * <p>⇒ <b>能读到的切片只剩「已经随某次回答给出去过的那些」。</b>
     * 这不是把口子关小，是把它关到只剩一条缝 —— 见
     * {@link com.xbla.rag.service.ChatReferenceNotFoundException} 的类注释。
     *
     * <h3>★ 它为什么在这个类里（而不是新的控制器）</h3>
     *
     * <p>因为它和上面的 {@code /trace/{traceId}} 是同一类东西：
     * <b>读 {@code qa_log}、按精确键查、零成本</b>。这个类的边界判据
     * 「这里不会有任何一次模型调用」在它身上同样成立。
     *
     * <h3>⚠️ 历史消息里的引用【点不开】—— 这是已批准的边界</h3>
     *
     * <p>{@code chat_message} 里没有 {@code trace_id}（阶段 8 拍板「技术面板
     * 只服务当前回答」的直接后果），所以历史消息<b>构造不出这个请求</b>。
     * 前端对此的表达是「历史消息取不到原文」，而不是让用户点了没反应。
     *
     * @throws com.xbla.rag.service.ChatTraceNotFoundException      没有这个 traceId → 404
     * @throws com.xbla.rag.service.ChatReferenceNotFoundException   有这条记录，
     *         但它的引用里没有这个切片 → 404。★ 正常情况下不该发生：
     *         前端手上的 chunkId 就是从同一条 {@code references} 里来的
     * @throws com.xbla.rag.service.KbChunkNotFoundException         引用有效，
     *         但切片正文已经查不到了 → 404
     */
    @GetMapping("/refs/{traceId}/{chunkId}")
    public ApiResponse<ReferenceDetail> reference(
            @PathVariable("traceId") String traceId,
            @PathVariable("chunkId") Long chunkId) {
        return ApiResponse.ok(chatReferenceQueryService.referenceDetail(traceId, chunkId));
    }
}
