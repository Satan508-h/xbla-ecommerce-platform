package com.xbla.rag.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.mcp.McpSessionStore;
import com.xbla.rag.mcp.McpTool;
import com.xbla.rag.mcp.McpToolRegistry;
import com.xbla.rag.mcp.ToolField;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xbla.rag.client.dto.ToolSpec;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.mcp.client.McpGatewayException;
import com.xbla.rag.mcp.client.McpToolGateway;
import com.xbla.rag.mcp.client.SdkMcpToolGateway;
import com.xbla.rag.mcp.client.ToolOutcome;
import com.xbla.rag.mcp.protocol.McpProtocol;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 的调试探针（阶段 5.7）。
 *
 * <p>⚠️ {@code @Profile("local")} —— 和其它探针一样只在本地存在。
 * <b>只读，不调模型，不花钱。</b>
 *
 * <h2>为什么需要它</h2>
 *
 * <p>「工具列表」这件事有<b>三个</b>可能不一致的版本，而它们各自都不报错：
 *
 * <ol>
 *   <li>代码里注册了哪些工具（Bean 扫描的结果）</li>
 *   <li>发给模型的 {@code inputSchema} 长什么样（模型只认这个）</li>
 *   <li>工具实现真正去读哪些 key</li>
 * </ol>
 *
 * <p>本接口把 ① 和 ② 原样打出来 —— 而 ② 和 ③ 的一致性由
 * {@link ToolField} 从结构上保证（见那个类的注释）：
 * schema 和取值是同一个常量产出的，物理上不可能漂移。
 *
 * <p>所以这个探针要回答的是另一个问题：
 * <b>「模型现在看到的工具清单，是不是我以为的那一份」</b>。
 * 同 {@code /api/debug/agent/intent-tree} 之于 5.1、{@code /api/debug/agent/memory} 之于 5.6。
 */
@RestController
@RequestMapping("/api/debug/mcp")
@Profile("local")
public class McpProbeController {

    private final McpToolRegistry registry;
    private final McpSessionStore sessions;
    private final McpToolGateway gateway;
    private final QaLogMapper qaLogMapper;
    private final ObjectMapper objectMapper;

    public McpProbeController(McpToolRegistry registry, McpSessionStore sessions,
                              McpToolGateway gateway, QaLogMapper qaLogMapper,
                              ObjectMapper objectMapper) {
        this.registry = registry;
        this.sessions = sessions;
        this.gateway = gateway;
        this.qaLogMapper = qaLogMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 看模型实际会拿到的工具清单。
     *
     * <p>{@code tools} 里就是 {@code tools/list} 的返回原文 ——
     * 直接抄给别的 MCP 客户端也能用。
     */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpoint", "POST /mcp");
        response.put("transport", "Streamable HTTP");
        response.put("protocolVersion", McpProtocol.PROTOCOL_VERSION);
        response.put("supportedVersions", McpProtocol.SUPPORTED_VERSIONS_ORDERED);
        response.put("toolCount", registry.size());
        response.put("activeSessions", sessions.size());

        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpTool tool : registry.all()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.name());
            entry.put("title", tool.title());
            // ★ 定义原文照打 —— 它就是模型看到的东西，任何二次加工都会让
            //   「探针看到的」和「线上跑的」产生偏差
            entry.put("definition", tool.toToolDefinition());
            entry.put("inputFields", describe(tool.inputFields()));
            entry.put("outputFields", describe(tool.outputFields()));
            tools.add(entry);
        }
        response.put("tools", tools);
        return response;
    }

    /**
     * <b>直接调一个工具</b>，把它的返回原文打出来（阶段 5.9）。
     *
     * <h3>★ 为什么需要它：工具说的和模型说的是两件事</h3>
     *
     * <p>5.8 的验收里踩过一次：越权查询时工具回的是
     * 「没有找到订单号 X 对应的订单」，而模型把它改写成了
     * <b>「没查到」</b>。判据只能下在<b>性质</b>上，因为措辞是模型的。
     *
     * <p>但反过来也需要一个东西能回答：<b>「工具到底说了什么」</b>。
     * 有了它，「模型答得不对」和「工具给的就是错的」就能分开 ——
     * 而这两件事的修法完全相反。
     *
     * <p>★★ <b>它走的是完整的 MCP 客户端链路</b>（{@link McpToolGateway} →
     * HTTP → {@code McpServer}），不是直接调 Bean。所以参数 schema 校验、
     * 身份注入、会话握手全都在路径上 —— <b>它拿到的东西和模型拿到的一模一样</b>。
     * 直接 {@code tool.call(...)} 会跳过这一整层，那就成了一个只能验证
     * 「我调了我自己」的接口。
     *
     * <p>⚠️ {@code args} 是 JSON 字符串。<b>中文必须由调用方做百分号编码</b> ——
     * Git Bash 直接把中文塞进 query string 会变成 {@code U+FFFD}
     * （见 CLAUDE.md 第八节）。{@code scripts/probe_tool.py} 已经处理了。
     *
     * <p>★ <b>不花钱</b>：这条路径上没有模型。
     *
     * @param tool   工具名，如 {@code query_my_coupons}
     * @param userId 身份。★ 它进的是请求头，不是工具参数 —— 见 {@code McpToolContext}
     * @param args   工具参数，JSON 对象。省略 = 空对象（无参工具就是这种）
     */
    @GetMapping("/call")
    public Map<String, Object> call(@RequestParam String tool,
                                    @RequestParam(defaultValue = "8") long userId,
                                    @RequestParam(required = false) String args) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tool", tool);
        response.put("userId", userId);

        Map<String, Object> arguments = Map.of();
        if (args != null && !args.isBlank()) {
            try {
                arguments = objectMapper.readValue(args, new TypeReference<>() {
                });
            } catch (Exception e) {
                response.put("ok", false);
                response.put("error", "args 不是合法的 JSON 对象：" + e.getMessage());
                return response;
            }
        }
        response.put("arguments", arguments);

        long start = System.currentTimeMillis();
        try {
            ToolOutcome outcome = gateway.callTool(userId, tool, arguments);
            response.put("ok", true);
            // ★ isError 原样透出 —— 「工具有没有给出答案」和「答案是不是空的」
            //   是两回事，见 McpToolResult 的类注释
            response.put("isError", outcome.isError());
            response.put("text", outcome.text());
            response.put("data", outcome.data());
        } catch (McpGatewayException e) {
            // ★ 把 stage 也报出来：CALL_TOOL 的失败（模型能改）
            //   和 CONNECT 的失败（模型改不了）是两回事
            response.put("ok", false);
            response.put("stage", e.stage().name());
            response.put("error", e.getMessage());
        }
        response.put("elapsedMs", System.currentTimeMillis() - start);
        return response;
    }

    /** 会话表的状态。⚠️ 只报数量，不报 ID —— ID 就是身份凭据，不该出现在探针输出里 */
    @GetMapping("/sessions")
    public Map<String, Object> sessions() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("activeSessions", sessions.size());
        response.put("note", "会话存在进程内存里 —— ⚠️ 只能单实例部署，重启即失效");
        response.put("cleanedNow", sessions.sweep());
        return response;
    }

    // ============================================================
    // ★ 客户端视角（阶段 5.8）
    // ============================================================

    /**
     * <b>走一遍真正的 MCP 客户端</b>去拉工具，然后打出<b>模型实际会收到的那份报文</b>。
     *
     * <h3>★★ 它和上面 {@code /tools} 的区别，正是这个接口存在的理由</h3>
     *
     * <p>{@code /tools} 看的是<b>服务端注册了什么</b>；这里看的是
     * <b>客户端转了一圈之后，模型到底收到什么</b>。中间隔着<b>四跳</b>，
     * 而每一跳都可能悄悄丢东西：
     *
     * <pre>
     *   ① 服务端注册表          McpTool.toToolDefinition()
     *   ② 序列化成 JSON         走 HTTP
     *   ③ 官方 SDK 反序列化      McpSchema$Tool
     *   ④ 映射成 ToolSpec        SdkMcpToolGateway.toSpec()
     *   ⑤ 再拼回 OpenAI 形状     ToolSpec.toWireTool()      ← 模型看到的是这个
     * </pre>
     *
     * <p>任何一跳丢一个 {@code description} 或者多一层包装，
     * <b>都不会报错</b> —— 症状只是「模型选错工具」，
     * 而那是所有 AI 问题里最难查的一类。
     *
     * <p>★ 另外 {@code wireTools} 是<b>逐字</b>打出来的（含键序）。
     * 它是 Prompt 前缀的一部分，而前缀缓存命中价差 50 倍 ——
     * 所以这个接口也是「改了工具定义之后，前缀有没有变」的对比工具：
     * 两次调用的输出直接 diff 即可。
     *
     * @param userId 以谁的身份去拉。默认 8（库里有 8 笔订单的真实用户）
     */
    @GetMapping("/client")
    public Map<String, Object> client(@RequestParam(defaultValue = "8") long userId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("note", "★ 走的是真正的 MCP 客户端（官方 SDK），不是读注册表。"
                + "wireTools 就是模型会收到的那份报文原文");

        if (gateway instanceof SdkMcpToolGateway sdk) {
            response.put("connection", sdk.describeTarget());
        }

        long start = System.nanoTime();
        List<ToolSpec> specs;
        try {
            specs = gateway.listTools(userId);
        } catch (McpGatewayException e) {
            // ★ 不抛 —— 探针的价值就在于把失败也看清楚。
            //   抛出去的话 GlobalExceptionHandler 会把堆栈吃掉，
            //   而这里恰恰需要知道「是哪一步失败的」
            response.put("ok", false);
            response.put("stage", e.stage().name());
            response.put("error", e.getMessage());
            response.put("elapsedMs", (System.nanoTime() - start) / 1_000_000L);
            return response;
        }
        response.put("elapsedMs", (System.nanoTime() - start) / 1_000_000L);

        response.put("ok", true);
        response.put("toolCount", specs.size());

        List<Map<String, Object>> wireTools = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();
        for (ToolSpec spec : specs) {
            wireTools.add(spec.toWireTool());

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("name", spec.name());
            detail.put("descriptionLength", spec.description() == null ? 0 : spec.description().length());
            // ★ description 为空是个【要命但不报错】的状态：
            //   模型选不选这个工具全看它。null 在这里必须看得见
            detail.put("description", spec.description());
            detail.put("inputSchema", spec.inputSchema());
            details.add(detail);
        }
        response.put("wireTools", wireTools);
        response.put("tools", details);
        return response;
    }

    /**
     * 按 {@code traceId} 查这次问答在 {@code qa_log} 里落下的东西。
     *
     * <h3>★ 为什么探针要看 qa_log</h3>
     *
     * <p>因为「工具到底调没调」这件事<b>从接口响应里看不出来</b>：
     * 调成了和没调成（模型自己编的）可以给出<b>一模一样的回答文本</b>。
     * 唯一的证据是 {@code qa_log.tool_calls} 和
     * {@code retrieval_detail}（必须是 NULL，证明检索没跑）。
     *
     * <p>★ 走这个接口而不是让脚本连 psql，是为了让
     * {@code scripts/probe_tool.py} 只要有一个 Python 就能跑 ——
     * 和 {@code data/eval/} 进 git 是同一条理由：别人克隆下来就能验证。
     */
    @GetMapping("/qa-log")
    public Map<String, Object> qaLog(@RequestParam String traceId) {
        Map<String, Object> response = new LinkedHashMap<>();
        List<QaLog> rows = qaLogMapper.selectList(
                new LambdaQueryWrapper<QaLog>().eq(QaLog::getTraceId, traceId));
        if (rows.isEmpty()) {
            response.put("code", 1);
            response.put("message", "没有这个 traceId 的 qa_log：" + traceId);
            return response;
        }
        QaLog log = rows.get(0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("traceId", log.getTraceId());
        data.put("status", log.getStatus());
        data.put("intent", log.getIntent());
        data.put("toolCalls", log.getToolCalls());
        data.put("provider", log.getProvider());
        data.put("model", log.getModel());
        data.put("promptTokens", log.getPromptTokens());
        data.put("completionTokens", log.getCompletionTokens());
        data.put("totalTokens", log.getTotalTokens());
        data.put("cost", log.getCost() == null ? null : log.getCost().toPlainString());
        data.put("llmLatencyMs", log.getLlmLatencyMs());
        data.put("retrievalDetail", log.getRetrievalDetail());
        // ★ 阶段 9.2/9.4：结构化计划（shape / gate / tools / missing / resumed）。
        //   补它的理由和这个接口存在的理由是同一条 ——
        //   「门控到底有没有生效」和「恢复路径触发过几次」都【只能】从这一列看出来，
        //   而在此之前要回答它们只能去连 psql（那条路对克隆仓库的人不成立）。
        data.put("intentPlan", log.getIntentPlan());
        // ★ 阶段 9.5：偏好块的原义那一份快照。★ 它和系统提示里那一段【逐字相同】，
        //   所以这里看到的既是「注入了没有」，也是「注入的是什么」。
        //   ⚠️ null = 这一次没有偏好块（匿名 / 身份不存在 / 订单不足 / 开关关掉）——
        //      想知道是哪一种，用 /api/debug/profile/affinity?userId=…
        data.put("affinity", log.getAffinity());
        data.put("references", log.getReferences());
        data.put("finalAnswer", log.getFinalAnswer());
        response.put("code", 0);
        response.put("data", data);
        return response;
    }

    private static List<String> describe(List<ToolField> fields) {
        return fields.stream().map(ToolField::display).toList();
    }
}
