package com.xbla.rag.client;

import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.client.dto.StreamResult;

import java.util.function.Consumer;

/**
 * 单个模型供应商的调用契约。
 *
 * <p>一个实例 = 一个「链路条目」= 一个熔断器 = 一个确定的
 * (供应商, 模型) 组合。降级链上有几档，就有几个实例。
 *
 * <p><b>本项目只会有一个实现类</b>（{@code OpenAiCompatibleLlmClient}），
 * 因为 DeepSeek 官方和硅基流动<b>都提供 OpenAI 兼容协议</b> ——
 * 差别只在域名、密钥和模型 ID，而那三样都是注入进来的。
 * 这也正是「双供应商」这个架构在本项目里成本很低的原因。
 *
 * <h3>流式接口为什么设计成「阻塞 + 回调」</h3>
 *
 * <p>{@link #chatStream} 是<b>阻塞</b>的，正文通过 {@code onDelta} 回调逐段送出，
 * 方法返回时代表流已正常结束。
 *
 * <p>被否决的两个方案：
 * <ul>
 *   <li>{@code Flux<Chunk>} —— 要引入 WebFlux 响应式栈，
 *       违反「零新增依赖」的决策，且与 Spring MVC 是两套心智模型</li>
 *   <li>{@code Stream<Chunk>} —— 它是<b>惰性</b>的，try-with-resources 一关就废；
 *       更麻烦的是流中间抛出的异常会被包装成 {@code UncheckedIOException}，
 *       堆栈里完全看不出是哪家供应商出的问题，而降级链恰恰需要这个信息</li>
 * </ul>
 *
 * <p>回调式虽然「老派」，但它能在任意时刻抛出<b>真正的业务异常</b>，
 * 而且 {@code Consumer} 天然表达「每来一个增量就做一次副作用」——
 * 正好对应 SSE 的 {@code emitter.send()}。
 */
public interface LlmClient {

    /**
     * 本客户端的逻辑名，等于配置里 {@code chat-chain} 的条目名。
     *
     * <p>同时用作<b>熔断器名</b>和降级事件里的 {@code from}/{@code to}。
     * 要求全局唯一。
     */
    String modelKey();

    /**
     * 完整的模型身份（供应商 + 真实模型 ID + 单价）。
     *
     * <p>调用成功后，它会被写进 {@code qa_log.provider} / {@code qa_log.model}，
     * 并在计算成本时提供单价。
     */
    ModelDescriptor descriptor();

    /**
     * <b>非流式</b>调用，阻塞直到拿到完整回答。
     *
     * @param request 对话请求
     * @return 完整结果
     * @throws ModelCallException 调用失败。异常里的 {@link ModelCallException#kind()}
     *                            决定「要不要降级」，由 {@code ChatModelRouter} 消费
     */
    ChatResponse chat(ChatRequest request);

    /**
     * <b>流式</b>调用。阻塞直到流结束。
     *
     * <p>{@code onDelta} 会在每收到一段正文增量时被调用（一次请求可能几百次）。
     * 回调里<b>不要做耗时操作</b>——它会直接阻塞上游的读取循环，
     * 导致模型那边的数据积压。
     *
     * @param request 对话请求
     * @param onDelta 正文增量回调。<b>只包含正式回答</b>，
     *                推理模型的 {@code reasoning_content} 在这里已经被丢弃
     * @return 用量与「吐了多少字」
     * @throws ModelCallException 失败。★ 若已经吐过字，
     *                            异常类型会是 {@code PARTIAL_STREAM}，
     *                            它的 {@code isFallbackWorthy()} 为 false ——
     *                            即「不能降级」，原因见该类注释
     */
    StreamResult chatStream(ChatRequest request, Consumer<String> onDelta);
}
