package com.xbla.rag.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenAI 兼容协议的 HTTP 底座 —— 三个客户端共用的「发请求」逻辑。
 *
 * <p>它只做四件事：拼 URL、装请求头、发出去、按状态码分流。
 * <b>不解析业务语义</b>（那是各个客户端自己的事）。
 *
 * <p>抽出来的价值在于：错误处理逻辑只写一遍。特别是
 * 「非 2xx 时要把响应体读出来放进异常」这一点 —— 服务端的错误说明
 * （「余额不足」「model not found」）只在响应体里，
 * 丢了就只能看到一句干巴巴的 400，排查时抓瞎。
 *
 * <p><b>★ 这是全项目唯二会出现模型 HTTP 请求的地方</b>
 * （另一个是它的调用方 {@code OpenAiCompatibleLlmClient} 等实现类）。
 * 这条边界由 {@code client/package-info.java} 约定，
 * 目的是让熔断、降级、计费、日志能在一处统一生效。
 */
@Slf4j
@Component
public class OpenAiHttpTransport {

    /**
     * API 版本路径段。
     *
     * <p>★ 刻意放在代码里而不是配置里：这样「配错域名」和「配错协议版本」
     * 这两类问题在日志里能一眼分开 —— 日志里看到的 URL 长什么样，
     * 就知道是哪种错。
     */
    private static final String API_VERSION_SEGMENT = "/v1";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final LlmProperties props;

    /**
     * 显式写构造器而不是用 Lombok 的 {@code @RequiredArgsConstructor}：
     * 因为 {@link ObjectMapper} 有两个 Bean（Spring MVC 用的那个是
     * {@code @Primary}，本项目的线格式专用版叫 {@code modelObjectMapper}），
     * 必须靠 {@code @Qualifier} 指名道姓。
     * 而 {@code @Qualifier} 是<b>不能</b>标在 Lombok 生成的构造器参数上的。
     */
    public OpenAiHttpTransport(HttpClient httpClient,
                               @Qualifier("modelObjectMapper") ObjectMapper mapper,
                               LlmProperties props) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * 一个供应商的连接信息（从配置里解析出来的运行期视图）。
     *
     * @param provider 供应商名（{@code deepseek} / {@code siliconflow}），用于日志和异常
     * @param baseUrl  域名，<b>不含 {@code /v1}</b>
     * @param apiKey   API Key
     */
    public record Endpoint(String provider, String baseUrl, String apiKey) {
    }

    // ============================================================
    // 非流式
    // ============================================================

    /**
     * 发一个 JSON 请求，一次性拿回完整响应体。
     *
     * @param endpoint 目标供应商
     * @param modelKey 链路条目名，仅用于异常里定位是哪个模型出的问题
     * @param path     路径，如 {@code /chat/completions}
     * @param body     请求体对象，会被序列化成 JSON
     * @param timeout  完整响应超时（★ 非流式必须用较长的 completeTimeout）
     * @return 响应体字符串
     * @throws ModelCallException 网络失败、超时、或非 2xx 响应
     */
    public String postForString(Endpoint endpoint, String modelKey, String path,
                                Object body, Duration timeout) {
        HttpRequest request = buildRequest(endpoint, path, body, timeout);

        // ★★★ 为什么是 sendAsync(...).get(timeout) 而不是 send()（阶段 7 补）
        //
        // 因为 send() 内部是【不带超时的】future.get()，它把「什么时候放弃」
        // 整个托付给了 HttpRequest.timeout()。而实测那次托付失败了：
        //
        //   2026-09-21 的一次跑批里，两个出站调用挂着不返回，
        //   HttpRequest.timeout()（180 秒）始终没有触发，
        //   `answer-5` / `answer-7` 两个线程停在 HttpClientImpl.send() 里
        //   超过 60 分钟没动 —— 而 .timeout() 确实设了（见 buildRequest）。
        //
        // 后果不只是「一次调用慢」：那个线程不结束 → 它的 finally 不执行 →
        // 名额不释放 → 而心跳一直替它续期 → 8 个名额掉到 6 个，永不恢复。
        // 链路是「出站调用挂死」→「容量静默变少」，中间隔了四层，
        // 所以这个根因必须在这里修，不能靠下游兜。
        //
        // ★ .get(timeout) 是【我们自己的】闸门：它是 ForkJoinPool.managedBlock
        //   上的标准等待，到点必抛 TimeoutException，不依赖 JDK 内部的
        //   定时器还活着。多一道和 .timeout() 同长的闸门不会有副作用 ——
        //   谁先响都行，两条路最后都归到 ModelErrorKind.TIMEOUT。
        CompletableFuture<HttpResponse<String>> pending =
                httpClient.sendAsync(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        try {
            log.debug("→ 模型请求 provider={} model={} url={}",
                    endpoint.provider(), modelKey, request.uri());

            HttpResponse<String> response =
                    pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            log.debug("← 模型响应 provider={} model={} status={} len={}",
                    endpoint.provider(), modelKey, response.statusCode(),
                    response.body() == null ? 0 : response.body().length());

            if (isNotSuccess(response.statusCode())) {
                throw ModelCallException.fromHttpStatus(
                        response.statusCode(), response.body(),
                        endpoint.provider(), modelKey);
            }
            return response.body();

        } catch (ModelCallException e) {
            throw e;

        } catch (TimeoutException e) {
            // ★ 我们不等了。但那个请求本身还在飞 —— 取消它是为了让 JDK
            //   早点把连接和缓冲还回去，否则每次超时都留一份垃圾。
            //   ⚠️ 取消【不保证】能让对端停下（它只是个本地动作），
            //      这里也不假装它保证了 —— 我们要的只是「本地有界」。
            pending.cancel(true);
            // ★ 用【带原因的那个】异常类：of() 里已经把 TimeoutException 映射成
            //   ModelErrorKind.TIMEOUT，所以这里不需要再造一个 HttpTimeoutException
            //   来「骗」分类器 —— 分类规则只有一处，在 ModelCallException.of 里。
            throw ModelCallException.of(
                    new TimeoutException("出站调用超过 " + timeout.toSeconds()
                            + " 秒未返回，调用方主动放弃"),
                    endpoint.provider(), modelKey);

        } catch (ExecutionException e) {
            // ★ 拆掉 CompletableFuture 的包装，把【真实原因】交给分类器 ——
            //   包着 ExecutionException 送进去，所有 IOException 都会被归成 UNKNOWN，
            //   而 UNKNOWN 在熔断器里的含义和 TIMEOUT/CONNECT 完全不同。
            throw ModelCallException.of(
                    e.getCause() == null ? e : e.getCause(),
                    endpoint.provider(), modelKey);

        } catch (InterruptedException e) {
            // ★ 中断标志必须还原 —— 吞掉它会让上层的关闭流程失去信号。
            Thread.currentThread().interrupt();
            throw ModelCallException.of(e, endpoint.provider(), modelKey);
        }
    }

    // ============================================================
    // 流式
    // ============================================================

    /**
     * 发一个 JSON 请求，拿回<b>按行切好的响应流</b>（SSE 用）。
     *
     * <p>返回的 {@link Stream} <b>必须</b>由调用方在 try-with-resources 里用完关闭 ——
     * 它持有底层 HTTP 连接，不关的话连接池会被耗尽，
     * 表现为「跑一段时间后所有请求都卡住」。
     *
     * <p><b>★ 状态码在这里就已经判完了。</b>
     * 这一点非常关键：{@code ofLines()} 返回的流是懒的，
     * 如果不在拿到响应头时立刻判状态码，会出现「明明返回了 401，
     * 却当成正常的空流一路走下去」的诡异情况 —— 最后表现为
     * 「模型不回答但不报错」，排查方向完全被带偏。
     *
     * <p>这个方法正常返回，就意味着「连接已建立、首字节已到达」——
     * 也就是降级链上那条<b>「还能降级」的分界线</b>。
     * 一旦调用方开始从返回的流里读数据并推给用户，就不能再降级了。
     *
     * @return 响应行的流。<b>调用方负责关闭</b>
     * @throws ModelCallException 建连失败、超时、或非 2xx 响应 ——
     *                            此时<b>还没有任何数据发给用户</b>，可以安全降级
     */
    public Stream<String> postForLines(Endpoint endpoint, String modelKey, String path,
                                       Object body, Duration timeout) {
        HttpRequest request = buildRequest(endpoint, path, body, timeout);

        HttpResponse<Stream<String>> response;
        // ★★ 和 postForString 同一个理由、同一个修法（阶段 7）——
        //    `send()` 内部的 future.get() 没有超时，什么时候放弃完全交给
        //    HttpRequest.timeout()，而实测它会不触发。流式这条路一样会
        //    永久挂住，一样会占着名额不放（挂住的线程不结束，finally 不执行）。
        //
        // ⚠️ 注意这里的语义：ofLines() 的 body 是【懒】的，
        //    所以 get(timeout) 等的是「响应头到达」，不是「回答生成完」。
        //    这正好对应 request-timeout（15 秒）的文档口径。
        //    头之后的边读边推由 SseEmitter 的预算管，不在这里。
        CompletableFuture<HttpResponse<Stream<String>>> pending =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
        try {
            log.debug("→ 模型流式请求 provider={} model={} url={}",
                    endpoint.provider(), modelKey, request.uri());

            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            // 建连/首字节阶段超时 —— 一个字节都没发给用户，可以安全降级
            pending.cancel(true);
            throw ModelCallException.of(
                    new TimeoutException("流式请求超过 " + timeout.toSeconds()
                            + " 秒未收到响应头，调用方主动放弃"),
                    endpoint.provider(), modelKey);

        } catch (ExecutionException e) {
            throw ModelCallException.of(
                    e.getCause() == null ? e : e.getCause(),
                    endpoint.provider(), modelKey);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 建连阶段失败 —— 一个字节都没发出去，可以安全降级
            throw ModelCallException.of(e, endpoint.provider(), modelKey);
        }

        if (isNotSuccess(response.statusCode())) {
            // ★ 非 2xx 时响应体是普通 JSON 错误对象，不是 SSE。
            //   把它读完拼起来放进异常，然后必须关掉流释放连接。
            String errorBody = readAndClose(response.body());
            throw ModelCallException.fromHttpStatus(
                    response.statusCode(), errorBody, endpoint.provider(), modelKey);
        }

        log.debug("← 模型流式响应已建立 provider={} model={} status={}",
                endpoint.provider(), modelKey, response.statusCode());

        return response.body();
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /**
     * 拼 URL：{@code baseUrl + "/v1" + path}。
     *
     * <p>配置里的 base-url 不带 {@code /v1}，也不带尾部斜杠 ——
     * 在启动期校验里会检查这一点（见 {@code ChatChainConfig}），
     * 避免出现 {@code https://api.deepseek.com//v1/chat/completions} 这种双斜杠。
     */
    private URI buildUri(String baseUrl, String path) {
        return URI.create(baseUrl + API_VERSION_SEGMENT + path);
    }

    private HttpRequest buildRequest(Endpoint endpoint, String path, Object body, Duration timeout) {
        String json = serialize(body, endpoint, path);
        return HttpRequest.newBuilder()
                .uri(buildUri(endpoint.baseUrl(), path))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=UTF-8")
                // ★ 显式声明接受 SSE，有些网关会据此决定是否开启缓冲
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + endpoint.apiKey())
                // ★ 显式指定 UTF-8 —— 绝不能依赖平台默认编码。
                //   Windows 上平台默认是 GBK，中文提问会被发成乱码，
                //   而服务端只会回一句「invalid unicode code point」，
                //   完全看不出是客户端编码问题。
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 序列化请求体。
     *
     * <p>失败时归类为 {@link ModelErrorKind#BAD_REQUEST} —— 序列化失败
     * 说明<b>我们自己的对象有问题</b>（比如字段类型不匹配），
     * 换一家供应商会以同样的方式失败，所以既不该降级、也不该污染熔断统计。
     */
    private String serialize(Object body, Endpoint endpoint, String path) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ModelCallException(
                    ModelErrorKind.BAD_REQUEST, -1, endpoint.provider(), path,
                    null,
                    "请求体序列化失败（这是本项目的 bug，不是供应商的问题）: " + e.getMessage(),
                    e);
        }
    }

    /** 把剩余的行读完并关闭流，用于非 2xx 时提取错误说明 */
    private String readAndClose(Stream<String> lines) {
        if (lines == null) {
            return "";
        }
        try (lines) {
            return lines.collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isNotSuccess(int statusCode) {
        return statusCode < 200 || statusCode >= 300;
    }

    /** 暴露给客户端读取默认超时 */
    public LlmProperties.Defaults defaults() {
        return props.getDefaults();
    }

    /**
     * 按供应商名从配置里解析出连接信息。
     *
     * <p>向量化和重排序客户端用它来定位硅基流动的地址和密钥。
     *
     * @param providerName 配置里 {@code xbla.llm.providers} 的 key
     * @throws IllegalStateException 找不到该供应商时。
     *         ★ 这里<b>故意</b>抛 IllegalStateException 而不是 ModelCallException ——
     *         配置里写了个不存在的供应商名是<b>启动期就该发现的问题</b>，
     *         不该混进「调用失败」的类别里被降级链当成网络问题处理掉。
     *         （正常情况下 {@code ChatChainConfig} 的启动校验会先一步拦住它）
     */
    public Endpoint resolveEndpoint(String providerName) {
        LlmProperties.Provider provider = props.getProviders().get(providerName);
        if (provider == null) {
            throw new IllegalStateException(
                    "配置里不存在供应商 '%s'，可选值：%s"
                            .formatted(providerName, props.getProviders().keySet()));
        }
        return new Endpoint(providerName, provider.getBaseUrl(), provider.getApiKey());
    }
}
