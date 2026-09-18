package com.xbla.rag.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * token 用量 —— 线格式。两家供应商共用这一个结构。
 *
 * <p><b>实测的真实报文</b>（2026-09-18，{@code deepseek-flash}）：
 * <pre>{@code
 * {
 *   "prompt_tokens": 39,
 *   "completion_tokens": 96,
 *   "total_tokens": 135,
 *   "prompt_tokens_details":     { "cached_tokens": 0 },
 *   "completion_tokens_details": { "reasoning_tokens": 76 },
 *   "prompt_cache_hit_tokens": 0,
 *   "prompt_cache_miss_tokens": 39
 * }
 * }</pre>
 *
 * <p><b>★ 缓存字段为什么有两套？</b>
 * OpenAI 标准把缓存命中信息放在嵌套的 {@code prompt_tokens_details.cached_tokens} 里，
 * 而 DeepSeek 额外在<b>顶层</b>给了 {@code prompt_cache_hit_tokens} /
 * {@code prompt_cache_miss_tokens} 两个平铺字段。
 * 实测发现硅基流动<b>两套都返回</b>。
 *
 * <p>计费时优先用 DeepSeek 的平铺字段，因为它是<b>精确的</b>：
 * 命中价和未命中价差两个数量级（0.02 元 vs 1 元），
 * 用 {@code prompt_tokens - cached_tokens} 反推虽然结果相同，
 * 但多一步减法就多一处出错的可能。
 */
public record WireUsage(

        /** 输入 token 总数（= 缓存命中 + 未命中） */
        @JsonProperty("prompt_tokens") Integer promptTokens,

        /**
         * 输出 token 总数。
         *
         * <p>★ <b>已经包含推理 token</b>，计费直接用它，不要再加上
         * {@code reasoning_tokens}（那是它的子集，不是并列项）。
         */
        @JsonProperty("completion_tokens") Integer completionTokens,

        /** 总 token 数（= 输入 + 输出），仅用于展示 */
        @JsonProperty("total_tokens") Integer totalTokens,

        /**
         * 缓存<b>命中</b>的输入 token 数（DeepSeek 平铺字段）。
         *
         * <p>只影响输入部分的计价档位，不影响输出。
         */
        @JsonProperty("prompt_cache_hit_tokens") Integer promptCacheHitTokens,

        /** 缓存<b>未命中</b>的输入 token 数（DeepSeek 平铺字段） */
        @JsonProperty("prompt_cache_miss_tokens") Integer promptCacheMissTokens,

        /** 嵌套的明细。实测两家都返回，但只用来取 reasoning_tokens 做诊断 */
        @JsonProperty("completion_tokens_details") CompletionDetails completionDetails

) {

    /**
     * 输出侧的明细。
     */
    public record CompletionDetails(

            /**
             * 推理消耗的 token 数。
             *
             * <p>★ <b>只用于诊断，不参与计价。</b>
             * 它是 {@code completion_tokens} 的<b>子集</b>——
             * 实测「124 个输出里 108 个是推理」，而计费看的是 124 这个总数。
             *
             * <p>它的价值在于：这个数字异常大（比如占比 90%+）时，
             * 说明该调大 {@code max-tokens}、或者该换一个不推理的模型。
             */
            @JsonProperty("reasoning_tokens") Integer reasoningTokens

    ) {
    }
}
