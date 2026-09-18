package com.xbla.rag.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code POST /v1/chat/completions} 的请求体 —— 线格式。
 *
 * <p><b>什么叫「线格式」（wire format）？</b>
 * 就是「真正在网络上传输的 JSON 结构」，和 OpenAI 协议一一对应。
 * 它和项目内部的领域对象（{@link ChatRequest}）是<b>两回事</b>：
 * <ul>
 *   <li>线格式随供应商协议走（改协议就要改它）</li>
 *   <li>领域对象随业务走（改业务才改它）</li>
 * </ul>
 * 两者分开，换来的是「换供应商时业务代码一行不用动」。
 *
 * <p><b>为什么放在 {@code client/dto/} 而不是顶层 {@code dto/}？</b>
 * 顶层 {@code dto/} 放的是「对外 HTTP 接口的进出参」，
 * 而线格式是 {@code client/} 的内部实现细节。
 * 这样隔离之后，「只有 client 包知道 HTTP 长什么样」这条约定才守得住 ——
 * service 层永远不会 import 到这个类。
 */
public record WireChatRequest(

        /** 真实的模型 ID，如 {@code deepseek-flash} 或 {@code deepseek-ai/DeepSeek-V4-Flash} */
        String model,

        /** 对话历史（含本轮提问），按时间正序 */
        List<WireMessage> messages,

        /**
         * 最大输出 token 数。
         *
         * <p>★ 字段名带下划线是 OpenAI 协议的硬性要求，不能写成 maxTokens。
         * 这类「协议用 snake_case、Java 用 camelCase」的差异，
         * 全靠 {@code @JsonProperty} 在这里显式桥接 ——
         * <b>不要</b>去改全局 ObjectMapper 的命名策略，那会影响所有对外接口的返回值。
         */
        @JsonProperty("max_tokens") Integer maxTokens,

        /**
         * 采样温度。
         *
         * <p>为 null 时字段不会出现在 JSON 里（靠 modelObjectMapper 的
         * {@code NON_NULL} 设置），等价于「用服务端默认值」。
         */
        Double temperature,

        /** 是否流式。null = 不提这个字段（服务端默认非流式） */
        Boolean stream,

        /** 流式选项。非流式请求不要带这个字段 */
        @JsonProperty("stream_options") WireStreamOptions streamOptions

) {

    /**
     * 一条对话消息。
     *
     * <p>{@code role} 的取值：{@code system}（系统提示）/ {@code user}（用户）/ {@code assistant}（助手）。
     */
    public record WireMessage(String role, String content) {

        public static WireMessage system(String content) {
            return new WireMessage("system", content);
        }

        public static WireMessage user(String content) {
            return new WireMessage("user", content);
        }

        public static WireMessage assistant(String content) {
            return new WireMessage("assistant", content);
        }
    }

    /**
     * 流式选项。
     *
     * <p>{@code include_usage = true} 时，服务端会在 {@code data: [DONE]} 之前
     * <b>额外推一个 chunk</b>：它的 {@code choices} 是<b>空数组</b>，
     * {@code usage} 是整次请求的 token 汇总。
     *
     * <p>★ 不开启的话，流式请求拿不到任何 token 数，成本只能记 NULL。
     *
     * <p>★ 实测（2026-09-18）：DeepSeek 官方和硅基流动都支持这个参数。
     */
    public record WireStreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {

        /**
         * 创建「开启用量汇报」的选项。
         *
         * <p>★ 方法名<b>不能</b>叫 {@code includeUsage()} ——
         * record 会自动生成一个同名的访问器 {@code Boolean includeUsage()}，
         * 两者只差返回类型，编译器会报「读取方法的方法类型必须与记录组件匹配」。
         * 这是一个纯粹因为命名撞车导致的编译错误，记在这里免得再踩。
         */
        public static WireStreamOptions enabled() {
            return new WireStreamOptions(true);
        }
    }
}
