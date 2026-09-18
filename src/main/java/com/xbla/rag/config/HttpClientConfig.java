package com.xbla.rag.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

/**
 * 模型接入层的 HTTP 基础设施。
 *
 * <p>这里产出的两个 Bean（{@link HttpClient} 和 {@link #modelObjectMapper()}）
 * 专供 {@code client/} 包使用，<b>不要</b>在业务代码里直接用 ——
 * CLAUDE.md 约定「所有 LLM 调用必须经过 {@code client/} 层封装」，
 * 目的就是让熔断、降级、计费、日志能在一处统一生效。
 *
 * @see com.xbla.rag.client
 */
@Configuration
public class HttpClientConfig {

    /**
     * 模型 API 专用的 HTTP 客户端。
     *
     * <p><b>为什么用 JDK 内置的 {@link HttpClient}？</b>
     * <ul>
     *   <li><b>零新增依赖</b> —— JDK 11 起自带，不需要 OkHttp / Apache HttpClient</li>
     *   <li><b>原生支持流式读取</b> —— {@code BodyHandlers.ofLines()} 直接返回
     *       {@code Stream<String>}，解析 SSE 非常自然</li>
     *   <li><b>可逐行解释</b> —— 项目要求 RAG 链路手写、不用 Spring AI / LangChain4j，
     *       内置客户端没有隐藏的连接管理魔法，每一行都能讲清楚</li>
     * </ul>
     *
     * <p><b>关于 HTTP 版本</b>：这里固定用 <b>HTTP/1.1</b>，不用 HTTP/2。
     * 虽然 HTTP/2 有多路复用优势，但 SSE 是长连接场景，
     * HTTP/1.1 的行为更可预测（一个连接一条流，超时和断开的语义直观）。
     * JDK HttpClient 的 HTTP/2 实现在流式响应上还有过一些边界问题，
     * 对一个以「稳定演示」为目标的项目来说不值得冒这个险。
     *
     * @param props 读取 {@code xbla.llm.defaults.connect-timeout}
     */
    @Bean
    public HttpClient modelHttpClient(LlmProperties props) {
        return HttpClient.newBuilder()
                // ★ 建连超时：只管 TCP 握手阶段（目标挂了 / DNS 解析不出来）。
                //   连上之后的等待由 HttpRequest.timeout() 管，是两层不同的超时。
                .connectTimeout(props.getDefaults().getConnectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                // 不自动跟随重定向。模型 API 不应该有重定向，
                // 万一出现（比如域名迁移），我们希望它直接失败并触发降级，
                // 而不是被无声地转到另一个地址
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new NoProxySelector())
                .build();
    }

    /**
     * 模型协议专用 ObjectMapper —— 与 Spring MVC 用的那个<b>彻底隔离</b>。
     *
     * <p><b>为什么要单独一个？</b>
     * 全局的 ObjectMapper 由 Spring Boot 根据 {@code spring.jackson.*} 配置装配，
     * 它服务于 {@code ApiResponse} 的序列化。如果哪天有人改了
     * {@code spring.jackson.date-format} 或者注册了一个新的 Jackson 模块，
     * 不应该连带影响我们对 OpenAI 协议报文的解析 ——
     * 那种耦合引发的 bug 极难定位（接口返回值变了，但没人动过 client 代码）。
     *
     * <p><b>为什么必须关掉 FAIL_ON_UNKNOWN_PROPERTIES？</b>
     * 这是本项目「丢弃 {@code reasoning_content}」这个决策的落地方式：
     * {@code deepseek-flash} 会返回一个非 OpenAI 标准的 {@code reasoning_content} 字段，
     * 而我们的 DTO 里<b>故意不声明</b>它。关掉这个开关后，
     * Jackson 遇到未知字段会静默跳过，<b>不需要写任何一行过滤代码</b>。
     *
     * <p>不关的话会抛：
     * <pre>
     * Unrecognized field "reasoning_content" (class WireDelta),
     * not marked as ignorable
     * </pre>
     * 而且是在流式解析的中间抛，错误信息完全看不出跟推理模型有关。
     */
    @Bean("modelObjectMapper")
    public ObjectMapper modelObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // 序列化时丢掉 null 字段 —— 这样请求体里不会出现
                // {"temperature":null,"stream_options":null} 这种噪音。
                // 有些模型服务对多余字段很敏感（严格模式下会直接 400），
                // 而「不传」和「传 null」在语义上本来就不同：
                // 前者是「用服务端默认值」，后者可能被理解成「温度设为无」。
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * 强制不走代理的 {@link ProxySelector}。
     *
     * <p><b>为什么需要这个？</b>
     * JDK 的 {@code HttpClient} 默认使用 {@code ProxySelector.getDefault()}，
     * 它会读取 JVM 的系统属性。这台开发机为了装 Docker 配过代理，
     * 如果启动参数里带了 {@code -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890}
     * 之类的配置，所有模型请求都会绕道代理 ——
     * 而 DeepSeek 和硅基流动都是<b>国内服务，直连即可</b>。
     * 走代理的结果是莫名其妙的超时，日志里却看不出跟代理有关。
     *
     * <p>显式声明 NO_PROXY 后，行为只由代码决定，不受运行环境的历史配置影响。
     * 如果将来确实需要走代理，改这一个类即可，不用去翻启动脚本。
     */
    private static final class NoProxySelector extends ProxySelector {

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // 永远返回 NO_PROXY，所以这个方法不会被调用。
            // 但 ProxySelector 是抽象类，必须实现。
        }
    }
}
