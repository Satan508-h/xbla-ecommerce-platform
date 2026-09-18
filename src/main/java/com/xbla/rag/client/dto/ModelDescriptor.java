package com.xbla.rag.client.dto;

import com.xbla.rag.config.LlmProperties;

/**
 * 一个模型在运行期的完整身份 —— 「这个客户端背后到底是什么」。
 *
 * <p>它是配置（{@code xbla.llm.models.*}）解析后的产物，
 * 由 {@code ChatChainConfig} 装配、注入给每个 {@code LlmClient} 实例。
 *
 * <p><b>三个标识为什么要分开？</b>
 * <ul>
 *   <li>{@link #modelKey()} —— 项目内的<b>逻辑名</b>（{@code deepseek-flash}）。
 *       出现在降级事件、熔断器名、日志里。<b>必须长期稳定</b>，
 *       因为它会写进 {@code qa_log.degradation_events} 并持久化。</li>
 *   <li>{@link #provider()} —— <b>供应商名</b>（{@code deepseek} / {@code siliconflow}）。
 *       写进 {@code qa_log.provider}，用来回答「实际是哪家服务的」。</li>
 *   <li>{@link #modelId()} —— 发给 API 的<b>真实模型 ID</b>
 *       （{@code deepseek-flash} / {@code deepseek-ai/DeepSeek-V4-Flash}）。
 *       这个值会随供应商改版而变（阶段 2.1 就纠正过一次），
 *       所以要跟逻辑名分开，改它不影响历史数据。</li>
 * </ul>
 *
 * @param modelKey 链路条目名，同时用作熔断器名
 * @param provider 供应商名，对应 {@code xbla.llm.providers} 的 key
 * @param modelId  发给 API 的真实模型 ID
 * @param pricing  单价，用于计算成本
 */
public record ModelDescriptor(

        String modelKey,

        String provider,

        String modelId,

        LlmProperties.Pricing pricing

) {
}
