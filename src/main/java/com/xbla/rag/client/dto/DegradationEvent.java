package com.xbla.rag.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一次降级事件。会被序列化成 JSON 写进 {@code qa_log.degradation_events}。
 *
 * <p><b>★ 字段名是持久化契约，不要改。</b>
 * {@code schema} 里约定的结构是：
 * <pre>{@code
 * [{"from":"deepseek-flash","to":"deepseek-v4-flash","reason":"auth_error","at":"2026-09-18T20:30:00+08:00"}]
 * }</pre>
 * 阶段 7 的评测脚本会按 {@code reason} 做聚合（比如「P0 的可用率是多少」），
 * 改名等于让所有历史数据失去意义。
 *
 * <p><b>为什么 {@code from}/{@code to} 存的是「链路条目名」而不是真实模型 ID？</b>
 * 因为降级事件描述的是<b>策略动作</b>（「从第 0 档退到第 1 档」），
 * 而不是「调用了哪个模型」。两者要分开存：
 * <ul>
 *   <li>{@code degradation_events} 里的 from/to → 链路条目名（{@code deepseek-flash}）</li>
 *   <li>{@code qa_log.provider} / {@code qa_log.model} → 真实供应商与模型 ID
 *       （{@code siliconflow} / {@code deepseek-ai/DeepSeek-V4-Flash}）</li>
 * </ul>
 * 这样「哪一档经常出问题」和「实际用了哪家的哪个模型」两个问题都能回答，
 * 混在一起就两个都答不好。
 *
 * @param from   降级前的链路条目名
 * @param to     降级后的链路条目名；后面没有可用条目时为 {@code null}
 * @param reason 降级原因，取自 {@link com.xbla.rag.client.ModelErrorKind#reason()}
 *               或熔断器自身的 {@code circuit_open}
 * @param at     发生时刻，ISO-8601 字符串（带时区偏移）
 */
public record DegradationEvent(

        @JsonProperty("from") String from,

        @JsonProperty("to") String to,

        @JsonProperty("reason") String reason,

        @JsonProperty("at") String at

) {
}
