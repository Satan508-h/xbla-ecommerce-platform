package com.xbla.rag.mcp.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MCP Client 的连接参数，对应配置前缀 {@code xbla.mcp.client}。
 *
 * <p>注册方式同 {@code RetrievalProperties}：启动类上的
 * {@code @ConfigurationPropertiesScan} 自动扫描，不用写 {@code @Component}。
 *
 * <h2>为什么这里只有「连接」相关的配置</h2>
 *
 * <p>协议版本、JSON-RPC 错误码、方法名这些东西<b>不放配置</b> ——
 * 它们是协议的一部分，做成配置只会让人配出一份不合规的客户端，
 * 然后拿「我明明是这么配的」来解释一个协议错误。
 * （同 {@code application.yml} 里服务端那段注释的立场。）
 */
@Data
@ConfigurationProperties(prefix = "xbla.mcp.client")
public class McpClientProperties {

    /**
     * 要不要接 MCP 工具。
     *
     * <p>★ 关掉时，{@code retrieval = TOOL} 的意图会拿到一句
     * 「工具尚未接入」的诚实回复，而不是静默退化成知识库检索 ——
     * 后者会让模型<b>拿通用规则编一个订单状态出来</b>（ADR-044）。
     * 所以要有一个明确的开关，而不是「连不上就算了」。
     */
    private boolean enabled = true;

    /**
     * MCP Server 的根地址。<b>不带路径</b>，路径由 {@link #endpoint} 给。
     *
     * <p>★ 默认指向自己（本机 8080）—— 这个项目里 Server 和 Client
     * 在同一个 JVM 里，见 {@code McpController} 类注释第一节的说明。
     * 换成别的地址就是一个真正的外部 MCP Server，
     * <b>工具代码一行都不用改</b>，这是走协议而不是直接调用的收益。
     */
    private String baseUrl = "http://localhost:8080";

    /** MCP 端点路径 */
    private String endpoint = "/mcp";

    /**
     * 建立 TCP 连接的等待上限。
     *
     * <p>★ 它和 {@link #requestTimeout} 是<b>两件事</b>：
     * 一个管「连不上」，一个管「连上了但不回话」。
     * 只配后者的话，地址写错时会卡到请求超时才报错。
     */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * 单次 JSON-RPC 请求的等待上限（握手和工具调用都用它）。
     *
     * <p>本机回环，正常是毫秒级。<b>3 秒已经是很宽的余量</b> ——
     * 超过它基本就是对方真出问题了，早点失败比让用户等更好。
     * （同 {@code retrieveExecutor} 的 5 秒关闭等待是同一个取向。）
     */
    private Duration requestTimeout = Duration.ofSeconds(3);

    /**
     * 握手（{@code initialize}）的等待上限。
     *
     * <p>单独一项是因为它<b>比普通请求重</b>：服务端要装配工具注册表、
     * 加载配置。将来接一个远程 Server 时，这一项的合理值会明显大于
     * {@link #requestTimeout}。
     */
    private Duration initializationTimeout = Duration.ofSeconds(5);

    /**
     * 客户端自报的名字，会出现在服务端的日志和 {@code initialize} 响应里。
     *
     * <p>★ 它是「哪个客户端连了我」唯一的线索。MCP Server 可能同时被
     * Claude Desktop、调试脚本、本项目的 Client 连着，
     * 一个能区分的名字能让服务端日志立刻说清是谁。
     */
    private String clientName = "xbla-rag";

    /** 客户端版本，和 {@code pom.xml} 的版本无关 —— 它是这个客户端实现的版本 */
    private String clientVersion = "0.1.0";
}
