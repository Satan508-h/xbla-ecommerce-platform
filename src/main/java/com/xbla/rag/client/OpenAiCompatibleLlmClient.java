package com.xbla.rag.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.client.dto.StreamResult;
import com.xbla.rag.client.dto.ToolSpec;
import com.xbla.rag.client.dto.WireChatRequest;
import com.xbla.rag.client.dto.WireChatResponse;
import com.xbla.rag.client.dto.WireStreamChunk;
import com.xbla.rag.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenAI 兼容协议的对话客户端 —— <b>一个类同时服务 DeepSeek 官方和硅基流动</b>。
 *
 * <p>能一份实现服务两家，是因为两家的接口<b>都是 OpenAI 兼容协议</b>：
 * 同样的 {@code POST /v1/chat/completions}、同样的请求体结构、同样的响应字段。
 * 差别只有三样 —— 域名、密钥、模型 ID —— 而这三样都是构造时注入的。
 * 这正是本项目「双供应商」架构能用很低成本实现的原因。
 *
 * <p><b>它不是 Spring Bean</b>，由 {@code ChatChainConfig} 按降级链的条目
 * 逐个 {@code new} 出来。链上有几档就有几个实例，每个实例绑定一个
 * {@link ModelDescriptor} 和一个熔断器。
 *
 * @see LlmClient 接口约定
 * @see OpenAiHttpTransport HTTP 底座（错误分流在那里）
 */
@Slf4j
public class OpenAiCompatibleLlmClient implements LlmClient {

    /** 对话补全的路径。域名和 {@code /v1} 由 transport 拼接 */
    private static final String CHAT_PATH = "/chat/completions";

    private final OpenAiHttpTransport transport;
    private final ObjectMapper mapper;
    private final ModelDescriptor descriptor;
    private final OpenAiHttpTransport.Endpoint endpoint;
    private final LlmProperties.Defaults defaults;

    /**
     * 流式请求是否带 {@code stream_options.include_usage}。
     *
     * <p>实测两家都支持，所以默认 true；留成参数是因为换供应商时可能不支持 ——
     * 那时改配置即可，不用改代码。
     */
    private final boolean streamIncludeUsage;

    public OpenAiCompatibleLlmClient(OpenAiHttpTransport transport,
                                     ObjectMapper mapper,
                                     ModelDescriptor descriptor,
                                     OpenAiHttpTransport.Endpoint endpoint,
                                     LlmProperties.Defaults defaults,
                                     boolean streamIncludeUsage) {
        this.transport = transport;
        this.mapper = mapper;
        this.descriptor = descriptor;
        this.endpoint = endpoint;
        this.defaults = defaults;
        this.streamIncludeUsage = streamIncludeUsage;
    }

    @Override
    public String modelKey() {
        return descriptor.modelKey();
    }

    @Override
    public ModelDescriptor descriptor() {
        return descriptor;
    }

    // ============================================================
    // 非流式
    // ============================================================

    @Override
    public ChatResponse chat(ChatRequest request) {
        WireChatRequest wire = buildWireRequest(request, false);

        long start = System.nanoTime();

        // ★ 非流式用 completeTimeout（覆盖整个响应体），不是 requestTimeout。
        //   两者的差异见 LlmProperties.Defaults 的注释。
        String body = transport.postForString(endpoint, modelKey(), CHAT_PATH,
                wire, defaults.getCompleteTimeout());

        int latencyMs = elapsedMs(start);

        WireChatResponse response = parseChatResponse(body);
        WireChatResponse.Choice choice = response.firstChoice();

        if (choice == null) {
            throw ModelCallException.emptyContent(
                    descriptor.provider(), modelKey(), "响应里没有任何 choices");
        }

        WireChatResponse.Message message = choice.message();
        String content = message == null ? null : message.content();
        ChatUsage usage = ChatUsage.from(response.usage());

        // ★★★ 空正文探测 —— 判据必须是 hasAnyContent()，不是 isEmptyContent()。
        //
        //   这道判断的本来目的是「推理模型把 max-tokens 吃光」：
        //   HTTP 200、finish_reason=length、内容却是空的 ——
        //   对外表现为「AI 不说话」，所以当成失败让降级链接手。
        //
        //   ★ 但工具决策轮的 content 【本来就是空串】。
        //     实测（2026-09-19）：连续 5 次让 deepseek-flash「需要订单信息就
        //     直接调用工具、不要说话」，5 次的 content 全是 ''。
        //
        //   只判 content 的话，每一次工具调用都会掉进这里：
        //     误判成失败 → ModelErrorKind.EMPTY_CONTENT 是可降级的
        //     → 降级链走一圈 → 三家全失败 → qa_log.status=2
        //     → 错误消息写着「输出 0 token，推理占 0%」
        //   而真正发生的事是「模型很有礼貌，一个字都没说，只给了工具调用」。
        //   排查方向会被这条消息带到「max-tokens 是不是给小了」上。
        if (message == null || !message.hasAnyContent()) {
            throw ModelCallException.emptyContent(
                    descriptor.provider(), modelKey(), describeEmptyChoice(choice, usage));
        }

        return new ChatResponse(content, choice.finishReason(), usage, descriptor, latencyMs,
                message.domainToolCalls(), message.reasoningContent());
    }

    // ============================================================
    // 流式
    // ============================================================

    @Override
    public StreamResult chatStream(ChatRequest request, Consumer<String> onDelta) {
        WireChatRequest wire = buildWireRequest(request, true);

        long start = System.nanoTime();

        // ★★★ 降级分界线 ★★★
        //
        //   postForLines 正常返回 = 连接已建立、响应头已到达、状态码是 2xx。
        //   在此之前抛出的任何异常，都发生在「一个字节都没发给用户」的阶段，
        //   所以可以安全地降级到下一家。
        //
        //   一旦下面开始读流并调用 onDelta 把字推给用户，
        //   就【不能再降级】了 —— 换一家从头重来会让用户看到
        //   两段拼接起来的、前后矛盾的话。
        //
        //   流式用 requestTimeout（TTFB 语义），不是 completeTimeout。
        Stream<String> lines = transport.postForLines(endpoint, modelKey(), CHAT_PATH,
                wire, defaults.getRequestTimeout());

        int ttfbMs = elapsedMs(start);

        ChatUsage usage = null;
        String finishReason = null;
        int contentChars = 0;

        try (lines) {
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                SseLineParser.Parsed parsed = SseLineParser.parse(it.next());

                if (parsed.isDone()) {
                    break;      // data: [DONE] —— 正常收尾
                }
                if (!parsed.isData()) {
                    continue;   // 空行 / 心跳注释 / 其他协议字段
                }

                WireStreamChunk chunk = parseChunk(parsed.payload());

                // ★ 先捞 usage：汇总 chunk 的 choices 是空数组，
                //   先判 choices 的话就永远读不到它了。
                //   最后一个 chunk 的 usage 覆盖前面的 null。
                if (chunk.usage() != null) {
                    usage = ChatUsage.from(chunk.usage());
                }

                WireStreamChunk.Choice choice = chunk.firstChoice();
                if (choice == null) {
                    continue;   // usage 汇总 chunk（choices 为空数组），不是错误
                }

                if (choice.finishReason() != null) {
                    finishReason = choice.finishReason();
                }

                // ★ 只取 content。推理模型的 reasoning_content 在 DTO 层
                //   就没被声明，Jackson 直接丢掉了，这里根本看不见它 ——
                //   这就是「丢弃推理内容」决策的全部实现，零过滤代码。
                String delta = choice.delta() == null ? null : choice.delta().content();
                if (delta != null && !delta.isEmpty()) {
                    try {
                        onDelta.accept(delta);
                    } catch (Exception e) {
                        // 回调抛出 = 下游出问题（多半是浏览器断开了），
                        // 不是供应商的错。见 ModelErrorKind#CLIENT_ABORTED。
                        throw ModelCallException.clientAborted(
                                descriptor.provider(), modelKey(), contentChars, e);
                    }
                    contentChars += delta.length();
                }
            }
        } catch (ModelCallException e) {
            throw e;
        } catch (UncheckedIOException e) {
            // Stream 的迭代器在底层 IO 出错时会把它包成 UncheckedIOException，
            // 解开一层才能看到真正的 IOException（超时 / 连接被掐）
            throw classifyStreamFailure(e.getCause(), contentChars, ttfbMs);
        } catch (IOException e) {
            throw classifyStreamFailure(e, contentChars, ttfbMs);
        } catch (RuntimeException e) {
            throw classifyStreamFailure(e, contentChars, ttfbMs);
        }

        int totalMs = elapsedMs(start);

        // ★ 一个字都没吐出去 —— 和同步路径同一个判据。
        //   此时降级是安全的（用户什么都没看到），所以异常类型是
        //   EMPTY_CONTENT，isFallbackWorthy() 为 true。
        if (contentChars == 0) {
            throw ModelCallException.emptyContent(
                    descriptor.provider(), modelKey(),
                    "流式响应结束但正文为空，finish_reason=%s，%s"
                            .formatted(finishReason, describeUsage(usage)));
        }

        return new StreamResult(usage, finishReason, contentChars, ttfbMs, totalMs);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /**
     * 组装协议请求体。
     *
     * @param stream true 时带上 {@code stream:true} 和 {@code stream_options}
     */
    private WireChatRequest buildWireRequest(ChatRequest request, boolean stream) {
        Integer maxTokens = request.maxTokens() != null
                ? request.maxTokens()
                : defaults.getMaxTokens();
        Double temperature = request.temperature() != null
                ? request.temperature()
                : defaults.getTemperature();

        WireChatRequest.WireStreamOptions streamOptions = null;
        if (stream && streamIncludeUsage) {
            streamOptions = WireChatRequest.WireStreamOptions.enabled();
        }

        // ★ tools 为 null 时字段不出现 —— 不用工具的请求与 5.7 之前逐字一致。
        //   顺序用 stream 的 map 直出，保证「同一份工具集 → 同一份字节」，
        //   这是前缀缓存命中的前提（见 ToolSpec.toWireTool 的说明）。
        List<Map<String, Object>> tools = null;
        if (request.hasTools()) {
            tools = request.tools().stream().map(ToolSpec::toWireTool).toList();
        }

        return new WireChatRequest(
                descriptor.modelId(),
                request.toWireMessages(),
                maxTokens,
                temperature,
                stream,
                streamOptions,
                tools);
    }

    /**
     * 把「流中途失败」归类。
     *
     * <p>★ 分类依据只有一条：<b>有没有已经吐字给用户</b>。
     * <ul>
     *   <li>没吐过 → 按异常本身的性质分类，可降级</li>
     *   <li>吐过了 → 一律 {@link ModelErrorKind#PARTIAL_STREAM}，不可降级</li>
     * </ul>
     *
     * <p>这就是「流式降级边界」这条架构约束的落地点。
     */
    private ModelCallException classifyStreamFailure(Throwable cause, int contentChars, int ttfbMs) {
        if (contentChars > 0) {
            return ModelCallException.partialStream(
                    descriptor.provider(), modelKey(), contentChars, cause);
        }
        if (cause instanceof JsonProcessingException) {
            // 响应不是合法 JSON —— 服务端的问题，换一家可能就好
            return new ModelCallException(
                    ModelErrorKind.SERVER_ERROR, 200, descriptor.provider(), modelKey(),
                    null,
                    "流式响应解析失败（首字之前，尚未吐字）: " + cause.getMessage(),
                    cause);
        }
        return ModelCallException.of(cause, descriptor.provider(), modelKey());
    }

    private WireChatResponse parseChatResponse(String body) {
        try {
            return mapper.readValue(body, WireChatResponse.class);
        } catch (JsonProcessingException e) {
            throw new ModelCallException(
                    ModelErrorKind.SERVER_ERROR, 200, descriptor.provider(), modelKey(),
                    body,
                    "对话响应解析失败: " + e.getMessage(), e);
        }
    }

    private WireStreamChunk parseChunk(String json) throws IOException {
        return mapper.readValue(json, WireStreamChunk.class);
    }

    /**
     * 空回答的原因描述 —— 直接写进异常消息，省去排查时翻日志。
     */
    private String describeEmptyChoice(WireChatResponse.Choice choice, ChatUsage usage) {
        return "finish_reason=%s，%s".formatted(choice.finishReason(), describeUsage(usage));
    }

    /**
     * 描述用量，特别是推理 token 的占比。
     *
     * <p>推理占比是「该不该调大 max-tokens」最直接的判据，所以放进错误消息里 ——
     * 看到「推理占比 87%」就能立刻明白发生了什么。
     */
    private String describeUsage(ChatUsage usage) {
        if (usage == null || !usage.isPresent()) {
            return "未拿到用量";
        }
        Integer reasoning = usage.reasoningTokens();
        Integer completion = usage.completionTokens();
        if (reasoning != null && completion != null && completion > 0) {
            return "输出 %d token（其中推理 %d，占 %d%%）"
                    .formatted(completion, reasoning, reasoning * 100 / completion);
        }
        return "输出 %s token".formatted(completion);
    }

    private static int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }
}
