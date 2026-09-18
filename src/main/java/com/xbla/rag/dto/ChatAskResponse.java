package com.xbla.rag.dto;

import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.DegradationEvent;

import java.math.BigDecimal;
import java.util.List;

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
        List<DegradationEvent> degradationEvents

) {
}
