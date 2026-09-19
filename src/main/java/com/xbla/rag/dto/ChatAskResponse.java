package com.xbla.rag.dto;

import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.DegradationEvent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/chat} 的响应体。
 *
 * <p><b>为什么直接复用了 {@link ChatUsage} 和 {@link DegradationEvent}？</b>
 *
 * <p>它们虽然定义在 {@code client.dto} 包里，但<b>已经是稳定的对外契约</b>：
 * {@code DegradationEvent} 的字段名（{@code from}/{@code to}/{@code reason}/{@code at}）
 * 被写进 {@code qa_log.degradation_events}，阶段 7 的评测脚本要按它解析。
 * 换句话说，它们的生命周期比「一次接口调用」长得多 ——
 * 再包一层 DTO 只会多一处需要同步修改的地方。
 *
 * <p>{@link ChatUsage} 同理：它是纯粹的数据载体，没有任何 HTTP 细节。
 *
 * <p>但 {@code client.dto.ChatRequest} 那样的<b>调用参数</b>就不该暴露 ——
 * 它含有 maxTokens、temperature 这类模型层概念，
 * 暴露出去等于让前端能直接控制模型的采样参数。所以入参那边分了两个类。
 *
 * @param traceId           本次请求的链路 ID，可用来查 {@code qa_log}
 * @param sessionNo         会话编号（新建的或传入的）
 * @param answer            模型回答
 * @param provider          实际生效的供应商。★ 降级后会不是 P0，这是验收标准 2 的直接证据
 * @param model             实际使用的模型 ID
 * @param usage             token 用量。拿不到时为 null
 * @param cost              本次成本（元）。拿不到用量时为 null —— 不估算
 * @param llmLatencyMs      LLM 环节耗时
 * @param totalLatencyMs    端到端耗时
 * @param degraded          本次是否发生过降级
 * @param degradationEvents 降级轨迹。没降级时是空列表
 * @param references        ★ 本次回答引用了哪些知识库切片（阶段 4 新增）。
 *                          没检索到内容时是 <b>null</b> 而不是空列表 ——
 *                          和 {@code degradationEvents} 的「空就不返回」约定一致。
 *
 *                          <p>每项形如
 *                          {@code {"no":1,"chunk_id":15,"document_id":2,"score":0.9276,"heading_path":"售后FAQ"}}。
 *                          {@code no} 对应 prompt 里的 {@code [1]} 引用编号。
 *
 *                          <p>加这个字段是<b>追加式</b>变更（向后兼容）——
 *                          现有调用方不读它不受影响。不加的话，
 *                          「检索有没有真的接进问答」在前端完全不可见
 */
public record ChatAskResponse(

        String traceId,
        String sessionNo,
        String answer,
        String provider,
        String model,
        ChatUsage usage,
        BigDecimal cost,
        int llmLatencyMs,
        int totalLatencyMs,
        boolean degraded,
        List<DegradationEvent> degradationEvents,
        List<Map<String, Object>> references

) {
}
