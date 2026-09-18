package com.xbla.rag.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 流式响应里的<b>单个 chunk</b> —— 线格式。
 *
 * <p>SSE 流里每一行 {@code data: {...}} 反序列化后就是这个对象。
 *
 * <p><b>实测的两种 chunk 形态</b>（2026-09-18）：
 *
 * <p>① 普通增量 chunk —— 每次带几个字：
 * <pre>{@code
 * {"id":"...","object":"chat.completion.chunk","model":"deepseek-flash",
 *  "choices":[{"index":0,
 *              "delta":{"content":"","reasoning_content":null},
 *              "finish_reason":"length"}],
 *  "usage":{"prompt_tokens":31,"completion_tokens":8,...}}
 * }</pre>
 *
 * <p>② usage 汇总 chunk —— 在 {@code data: [DONE]} 之前<b>额外多推一个</b>，
 * ★ 注意 {@code choices} 是<b>空数组</b>：
 * <pre>{@code
 * {"id":"...","choices":[],
 *  "usage":{"prompt_tokens":9,"completion_tokens":156,"total_tokens":165,
 *           "completion_tokens_details":{"reasoning_tokens":148},
 *           "prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":9}}
 * }</pre>
 *
 * <p>形态 ② 就是为什么 {@link #choices} 必须用 {@link List} 而不是数组 ——
 * 用数组配 {@code choices[0]} 会在每个流式请求的最后一刻抛
 * {@code ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0}。
 */
public record WireStreamChunk(

        String id,

        String model,

        /**
         * 增量候选列表。
         *
         * <p>★ <b>可能是空列表</b>（usage 汇总 chunk），解析前必须先判空。
         * 也可能为 null（极少数实现会省略这个字段），所以判空要连 null 一起判。
         */
        List<Choice> choices,

        /** token 用量。只有最后一个 chunk 才非 null，其余 chunk 里是 null 或缺省 */
        WireUsage usage

) {

    /** 取第一个增量候选，没有则返回 null */
    public Choice firstChoice() {
        return (choices == null || choices.isEmpty()) ? null : choices.get(0);
    }

    /**
     * 一个增量候选。
     */
    public record Choice(

            Integer index,

            /** 本 chunk 的增量内容 */
            Delta delta,

            /** 结束原因。最后一个内容 chunk 上才有值（{@code stop} / {@code length}），其余为 null */
            @JsonProperty("finish_reason") String finishReason

    ) {
    }

    /**
     * 增量内容。
     *
     * <p>★ <b>故意只声明 {@code content}，不声明 {@code reasoning_content}。</b>
     *
     * <p>这是「丢弃推理内容」决策的核心落点。推理模型每个 chunk 里都带
     * {@code reasoning_content}，我们靠 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}
     * 让 Jackson 静默跳过 —— <b>零过滤代码</b>。
     *
     * <p>如果把这个字段声明进来，就要在每个 chunk 的处理里判断
     * 「这个增量是推理还是正式回答」，一旦漏判就会把模型的内心独白
     * 当成答案推给用户，而且这种 bug 在日志里完全看不出来。
     *
     * <p>另外 {@code role} 字段只在第一个 chunk 里出现（值恒为 {@code assistant}），
     * 这里也不声明 —— 它对业务没有价值。
     */
    public record Delta(String content) {
    }
}
