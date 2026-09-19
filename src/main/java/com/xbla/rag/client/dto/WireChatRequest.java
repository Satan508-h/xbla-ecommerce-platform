package com.xbla.rag.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

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
        @JsonProperty("stream_options") WireStreamOptions streamOptions,

        /**
         * ★ 可用的工具（阶段 5.8 新增）。null 时字段不出现 ——
         * 这是「不用工具」的请求与 5.7 之前<b>逐字一致</b>的保证：
         * 多一个空数组都是对前缀缓存的一次无谓改动。
         *
         * <p>每一项的形状由 {@link ToolSpec#toWireTool()} 生成。
         */
        List<Map<String, Object>> tools

) {

    /**
     * 一条对话消息。
     *
     * <p>★ 这里和 {@code WireChatResponse.Message} 不一样：
     * <b>那边故意不声明 {@code reasoning_content}，这边必须声明。</b>
     * 因为请求方向是我们要发出去的东西，少一个字段就是 400。
     * 见 {@link #reasoningContent}。
     *
     * @param role             取值：{@code system} / {@code user} / {@code assistant} / {@code tool}
     * @param content          正文。★ <b>可以省略</b>（真实响应的 tool_calls 回合
     *                         {@code content} 就是空串）—— 实测省略不报错
     * @param toolCalls        见 {@link ToolCall}
     * @param toolCallId       role={@code tool} 时必填
     * @param reasoningContent 见下
     */
    public record WireMessage(

            String role,

            String content,

            /**
             * ★★ 是 {@link WireToolCall}（<b>嵌套</b>的），不是 {@link ToolCall}（平的）。
             *
             * <p>发出去时若用扁平形状，服务端会回
             * {@code HTTP 422 messages[N]: missing field \`type\`}。
             * 见 {@link WireToolCall#fromDomain} 的说明 —— 那是真踩过的。
             */
            @JsonProperty("tool_calls") List<WireToolCall> toolCalls,

            @JsonProperty("tool_call_id") String toolCallId,

            /**
             * ★★ <b>推理模型的思考过程，工具调用时必须原样发回来。</b>
             *
             * <p>{@code deepseek-flash} 是推理模型。实测（2026-09-19）：
             * 丢掉它再发回去，服务端会报
             * <pre>
             *   HTTP 400  The `reasoning_content` in the thinking mode
             *             must be passed back to the API.
             * </pre>
             * 而 400 <b>不降级</b> —— 整个工具调用链路当场死掉。
             *
             * <p>⚠️ <b>这看起来很矛盾，因为 {@code WireChatResponse} 那边
             * 故意声明「不接这个字段」。</b>两处都对，理由不同：
             * <ul>
             *   <li><b>响应方向</b>：不接 = 不落库、不返回给前端。
             *       那是阶段 2「不存 reasoning_content」的决策，
             *       在纯聊天路径上一直有效</li>
             *   <li><b>请求方向</b>：必须发 = 它要在<b>一次工具往返的两条
             *       HTTP 请求之间</b>活着。只活在内存里，不碰 {@code qa_log}、
             *       不碰 {@code chat_message}</li>
             * </ul>
             * 一句话：<b>「不存」不等于「不传」。</b>
             *
             * <p>★ 还有一个更细的实测结论，见 {@link ToolCall} 的类注释：
             * 单独丢掉这个字段<b>不一定</b>报错（服务端可能按 tool_call id
             * 去缓存里找回来），但<b>丢掉它同时改动了 id</b> 一定报错。
             * 所以两条一起守：字段原样带、id 原样搬。
             */
            @JsonProperty("reasoning_content") String reasoningContent

    ) {

        /** 一条纯文本消息 —— 最常用的形态，其余字段全为 null（序列化时被 NON_NULL 略过） */
        public static WireMessage of(String role, String content) {
            return new WireMessage(role, content, null, null, null);
        }

        public static WireMessage system(String content) {
            return of("system", content);
        }

        public static WireMessage user(String content) {
            return of("user", content);
        }

        public static WireMessage assistant(String content) {
            return of("assistant", content);
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
