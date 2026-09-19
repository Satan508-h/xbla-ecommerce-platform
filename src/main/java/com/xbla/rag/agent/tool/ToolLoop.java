package com.xbla.rag.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ToolCall;
import com.xbla.rag.client.dto.ToolSpec;
import com.xbla.rag.config.AgentProperties;
import com.xbla.rag.mcp.client.McpGatewayException;
import com.xbla.rag.mcp.client.McpToolGateway;
import com.xbla.rag.mcp.client.ToolOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>模型 ⇄ 工具 的多轮往返</b> —— 阶段 5.8 的核心，也是「智能体」这三个字
 * 在这个项目里唯一名副其实的地方。
 *
 * <h2>一、它到底在干什么</h2>
 *
 * <pre>
 *   第 1 轮  带上工具问模型
 *            ├─ 模型没要工具  → 【结束】它就是最终回答
 *            └─ 模型要调工具  → 执行 → 把结果挂进消息 → 下一轮
 *
 *   第 2 轮  接着问（★ 末尾【没有】用户提问，见 ChatRequest.userQuestion）
 *            └─ …
 *
 *   第 N 轮  超过 maxToolRounds 之后，★ 撤掉工具，强制模型作答
 * </pre>
 *
 * <p>关键在第 1 轮的「没要工具 → 结束」：<b>不用工具的问答和用工具的问答
 * 走的是同一条代码路径</b>，区别只是带上工具之后模型怎么选。
 * 所以这条路径天然兼容「用户问了一个本来不需要工具的问题，但意图分类
 * 把它分到了工具类」—— 模型会直接回答，而不是强行调一个工具。
 *
 * <h2>★★ 二、五条「看起来该报错、其实必须继续」的路径</h2>
 *
 * <p>这个类的绝大部分代码在处理失败。而它们的共同点是：
 * <b>没有任何一条能让整次问答失败</b>。
 *
 * <table border="1">
 *   <caption>失败路径与处理方式</caption>
 *   <tr><th>发生了什么</th><th>怎么处理</th><th>为什么</th></tr>
 *   <tr><td>工具列表拉不到（Server 没起来）</td>
 *       <td>不带工具继续问，但往 system 里加一段
 *           {@code unavailableNote}</td>
 *       <td>★ 见下面第三节 —— 这是本项目最危险的一条路径</td></tr>
 *   <tr><td>模型编了一个不存在的工具名</td>
 *       <td>把服务端的原话（「未知的工具：query_weather」）当作
 *           <b>工具结果</b>喂回去</td>
 *       <td>模型手上有完整的工具清单，<b>它能自己改</b>。
 *           报错终止等于剥夺了它改正的机会</td></tr>
 *   <tr><td>模型给的参数不是合法 JSON</td><td>同上，喂一句说明</td>
 *       <td>同上 —— 这是模型的输出错误，不是系统的故障</td></tr>
 *   <tr><td>工具跑了但说办不到（查无此单）</td><td>原样喂回去</td>
 *       <td>★ 它本来就是 {@code isError:false}，是个<b>答案</b>。
 *           见 {@link ToolOutcome} 的类注释</td></tr>
 *   <tr><td>工具连不上（超时 / 握手失败）</td><td>喂一句「实时查询暂时不可用」</td>
 *       <td>模型改不了，但<b>用户需要一句人话</b>。
 *           直接抛异常会让一个可选功能的故障变成整次问答失败</td></tr>
 * </table>
 *
 * <h2>★★ 三、为什么「工具不可用」时必须动 system prompt</h2>
 *
 * <p>这是 {@code ADR-044} 那条坑的正面用法。
 *
 * <p>意图树把「我的订单到哪了」分到 {@code ORDER_LOGISTICS}（{@code retrieval: TOOL}）。
 * 如果工具这条路断了而我们什么都不说，模型面对的是一个
 * <b>手里没有任何数据、但被要求回答一个具体订单</b>的局面 ——
 * 它最自然的反应是拿通用的售后规则去填：
 * <blockquote>「根据一般情况，您的订单预计 3-5 天送达。」</blockquote>
 * <b>一句听起来完全合理、但完全是编的话。</b>
 *
 * <p>所以 {@code unavailableNote} 的作用不是「告诉模型工具坏了」，
 * 而是<b>给它「不知道」这个选项</b>。措辞里那句
 * 「绝对不要根据通用的售后规则去推测某笔具体订单的状态」是必需的 ——
 * 只说「不要编造」时，模型会退而说「系统繁忙，请稍后再试」，
 * 那是另一种不准确。
 *
 * <h2>四、成本：多轮要【累加】，不能只看最后一轮</h2>
 *
 * <p>每一轮各用一个 {@link ModelCallTrace}，然后
 * {@link ModelCallTrace#mergeRound 合并}到调用方给的 master trace 上。
 * 合并规则（尤其是「用量累加、路由覆盖」）见那个方法 ——
 * 一句话：<b>不累加会让第 1 轮的花费凭空消失</b>，
 * 而 {@code deepseek-flash} 那一轮的推理 token 并不便宜。
 */
@Slf4j
@Component
public class ToolLoop {

    /** 单条工具结果进 Prompt 的字符上限 —— 见 {@link #toolResultText} */
    private static final int MAX_TOOL_RESULT_CHARS = 4000;

    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {
    };

    private final McpToolGateway gateway;
    private final ChatModelRouter router;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    public ToolLoop(McpToolGateway gateway,
                    ChatModelRouter router,
                    AgentProperties properties,
                    ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.router = router;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ============================================================
    // 输入 / 输出
    // ============================================================

    /**
     * 一次工具往返的输入。
     *
     * <p>它和 {@link ChatRequest} 几乎一样，少的是 {@code tools}
     * （由本类自己填）和 {@code maxTokens}/{@code temperature}（由配置给）。
     * 没有直接收一个 {@code ChatRequest} 是因为<b>那个类型允许带 tools</b> ——
     * 让调用方有机会传一份 tools 进来，就会有「传进来的和我拉到的不是同一份」
     * 这种问题。这里干脆不给那个口子。
     *
     * @param systemPrompt 已经完全组装好的系统提示词（含检索上下文、会话摘要等）
     * @param history      会话记忆的窗口，★ 不含本轮提问
     * @param question     本轮用户提问
     * @param userId       身份。★ <b>为 null 表示没有身份</b> ——
     *                     这时不调模型，直接回一句诚实的话。
     *                     见 {@link Result#toolsAvailable()}
     */
    public record Input(String systemPrompt, List<ChatRequest.Turn> history,
                        String question, Long userId) {
    }

    /**
     * 一次工具调用的记录 —— 给日志、调试探针、以及将来的 {@code qa_log} 用。
     *
     * <p>★ 只记「调了什么、成没成、哪一轮」，<b>不记结果正文</b> ——
     * 结果里是订单内容，没有理由让它进日志（同 {@code ToolLoop} 的日志纪律）。
     *
     * @param toolName 工具名
     * @param isError  工具是否报告了失败。<b>false 不代表查到了东西</b>，
     *                 只代表「工具给出了一个答案」
     * @param round    第几轮工具往返（从 1 开始）
     * @param detail   一行摘要，如 {@code chars=87} 或失败原因
     */
    public record CallRecord(String toolName, boolean isError, int round, String detail) {
    }

    /**
     * 往返的最终结果。
     *
     * @param answer        给用户的最终回答。<b>永远不会是 null</b>
     * @param calls         每次工具调用的记录。空列表表示<b>一次都没调</b> ——
     *                     包括「模型看了一眼工具说不必」和「工具根本不可用」两种情况，
     *                      用 {@link #toolsAvailable()} 区分
     * @param rounds        实际发生了几次模型调用
     * @param toolsAvailable ★ 这次问答<b>工具这条路是通的吗</b>。
     *                      false 时 {@code answer} 里那句「查不到」是
     *                      <b>我们的系统状态</b>导致的，不是工具查过之后说的。
     *                      这个区分对阶段 7 的归因很重要
     */
    public record Result(String answer, List<CallRecord> calls, int rounds,
                         boolean toolsAvailable) {

        public boolean usedTools() {
            return !calls.isEmpty();
        }
    }

    // ============================================================
    // 主流程
    // ============================================================

    /**
     * 跑一轮完整的工具往返。
     *
     * @param input 输入
     * @param trace 调用方的轨迹收集器。★ 本方法会往里 {@link ModelCallTrace#mergeRound 合并}
     *              每一轮的用量与降级事件 —— 所以调用方拿到的 trace
     *              是<b>整次问答</b>的合计，而不是最后一轮的
     * @return 最终回答与过程记录
     * @throws com.xbla.rag.client.ModelCallException 模型链路全失败（工具失败不会走到这里）
     */
    public Result run(Input input, ModelCallTrace trace) {

        // ── ① 拿到工具清单（或确认拿不到）──
        Toolbox toolbox = resolveToolbox(input.userId());

        // ★ 没有身份：不调模型，直接回一句实话。
        //   理由见 AgentProperties.Tool.noIdentityText —— 用户没带身份头时，
        //   我们【确实】查不出「他的」订单，让模型发挥的唯一素材就是通用规则，
        //   也就是编。这和 5.3 澄清路径「短路不调模型」是同一个设计。
        if (input.userId() == null) {
            log.info("工具意图但没有身份，短路不调模型");
            return new Result(properties.getTool().getNoIdentityText(), List.of(), 0, false);
        }

        List<ChatRequest.Turn> working = new ArrayList<>();
        if (input.history() != null) {
            working.addAll(input.history());
        }

        // ★ system prompt 在整个往返里【一个字都不变】——
        //   它进了模型请求的最前面，而 DeepSeek 的上下文缓存是前缀匹配。
        //   中间改一次，之后每一轮的输入都整段未命中（差 50 倍）。
        //   所以「工具不可用」这件事必须在【循环之前】就决定要不要加进 prompt
        String systemPrompt = toolbox.available()
                ? input.systemPrompt()
                : input.systemPrompt() + "\n\n" + properties.getTool().getUnavailableNote();

        List<CallRecord> calls = new ArrayList<>();
        String question = input.question();
        int maxToolRounds = Math.max(0, properties.getTool().getMaxToolRounds());

        int rounds = 0;
        ChatResponse response;

        // ★ 循环的退出条件是「模型给了不带工具调用的回答」，不是轮次。
        //   轮次上限只限制【能带工具的轮数】—— 超过之后还有最后一轮（不带工具），
        //   保证一定有答案。见 AgentProperties.Tool.maxToolRounds
        for (int round = 1; ; round++) {
            boolean mayUseTools = round <= maxToolRounds && toolbox.available();
            rounds = round;

            response = callModel(systemPrompt, working, question,
                    mayUseTools ? toolbox.specs() : null, trace, round);

            if (!response.hasToolCalls()) {
                break;      // ★ 正常出口：模型给了正文
            }

            // 把模型这条 tool_calls 消息【原样】挂上 ——
            // content / toolCalls / reasoningContent 三个字段一个都不能改写，
            // 见 ToolCall 的类注释（改 id 会 400，而且不降级）
            working.add(ChatRequest.Turn.assistantToolCalls(
                    response.content(), response.toolCalls(), response.reasoningContent()));

            for (ToolCall call : response.toolCalls()) {
                ToolOutcome outcome = invoke(call, input.userId(), toolbox);
                calls.add(new CallRecord(call.name(), outcome.isError(), round,
                        outcome.isError() ? "工具报告失败" : "chars=" + textLength(outcome)));
                // ★ 一条 tool_calls 对应【恰好一条】tool 消息，id 逐字相同。
                //   少一条、多一条、id 对不上都是 400 且不降级
                working.add(ChatRequest.Turn.tool(call.id(), toolResultText(outcome)));
            }

            // ★★ 第二跳起【没有】新的用户提问 —— 见 ChatRequest.userQuestion 的说明。
            //   忘了这一句的话，消息序列会变成
            //     …[tool:结果][user:我的订单到哪了]
            //   模型会以为用户又问了一遍，于是再调一次工具 ——
            //   看日志像「模型陷入循环」，其实是我们的消息拼错了
            question = null;
        }

        log.info("工具往返结束 rounds={} 工具可用={} 调用 {} 次 {} answer={} 字",
                rounds, toolbox.available(), calls.size(),
                calls.isEmpty() ? "" : calls, textLength(response.content()));

        return new Result(response.content(), List.copyOf(calls), rounds, toolbox.available());
    }

    // ============================================================
    // 一轮模型调用
    // ============================================================

    /**
     * 调一次模型，并把这一轮的轨迹并进 master trace。
     *
     * <p>★ <b>无论成功还是失败都要并。</b>失败的那一轮虽然没产出用量，
     * 但它产生了<b>降级事件</b> —— 那是「P0 挂了，这轮是 P1 答的」唯一证据，
     * 丢掉它等于把一次真实的降级藏起来。
     */
    private ChatResponse callModel(String systemPrompt, List<ChatRequest.Turn> working,
                                   String question, List<ToolSpec> tools,
                                   ModelCallTrace master, int round) {

        // 每轮一个独立的 trace。★ 不复用同一个 —— 那个类里的 route/usage
        // 语义是「一次调用」，复用会让上一轮的值被这一轮覆盖掉，
        // 而 mergeRound 正是为「多轮合一份」设计的
        ModelCallTrace roundTrace = new ModelCallTrace(master.traceId() + "-r" + round);

        try {
            ChatRequest request = new ChatRequest(
                    systemPrompt,
                    List.copyOf(working),
                    question,
                    null,                                   // maxTokens 用配置默认值
                    properties.getTool().getTemperature(),
                    tools);

            return router.chat(request, roundTrace);

        } finally {
            master.mergeRound(roundTrace);
        }
    }

    // ============================================================
    // 一次工具调用
    // ============================================================

    /**
     * 执行一次工具调用 —— ★ <b>这个方法永远不抛异常</b>。
     *
     * <p>它把三种失败都翻译成 {@link ToolOutcome}，因为「让模型知道发生了什么」
     * 比「让调用方处理异常」在这里更合适。见类注释第二节的表。
     */
    private ToolOutcome invoke(ToolCall call, long userId, Toolbox toolbox) {

        // ── ① 参数：模型给的是一段 JSON 字符串，先解析 ──
        Map<String, Object> arguments;
        try {
            arguments = parseArguments(call);
        } catch (Exception e) {
            // ★ 这是【模型的输出错误】，不是系统故障。模型能看到自己刚发的东西，
            //   把原因告诉它，它下一轮就能改对
            log.warn("工具参数不是合法 JSON tool={} —— 已作为工具结果喂回模型", call.name());
            return ToolOutcome.failed(
                    "你给的参数不是合法的 JSON 对象：" + e.getMessage()
                            + "。请重新给出一个合法的 JSON 对象。");
        }

        // ── ② 调 ──
        try {
            return gateway.callTool(userId, call.name(), arguments);

        } catch (McpGatewayException e) {
            // ── ③ 失败了，但【分两种】，因为它们给模型的信息不一样 ──
            //
            //  ★ 判据是「模型能不能改」：
            //    - 服务端明确拒绝了这个调用（工具名错、参数不合 schema）
            //      → 原话喂回去，模型有机会改对
            //    - 连不上 / 超时 / 握手失败
            //      → 模型改不了。告诉它「查不到」，让它如实说
            //
            //  ★★ 而这正是 5.7 那条 isError 判据【下沉一层】的同一个道理：
            //     「该由谁修」决定这条消息长什么样。
            String reason = e.stage() == McpGatewayException.Stage.CALL_TOOL
                    ? serverRejection(e)
                    : "实时查询服务暂时不可用，这次没能查到数据。";

            log.warn("工具调用没跑成 tool={} stage={} —— 已作为工具结果喂回模型：{}",
                    call.name(), e.stage(), e.getMessage());
            return ToolOutcome.failed(reason);

        } catch (RuntimeException e) {
            // ★★ 兜底的兜底 —— 一个【不属于 McpGatewayException】的异常。
            //
            //   这条分支是被一次真实的故障逼出来的（2026-09-19）：
            //   当时 gateway 在某个环节让一个 IllegalArgumentException
            //   穿了出去，于是整次问答 status=2，
            //   而 qa_log 里的错误消息和订单毫无关系。
            //
            //   ★ 结论：既然已经决定了「工具的任何失败都不能让问答失败」，
            //     那这个兜底就必须罩住【所有】RuntimeException，
            //     而不是只罩住我们自己定义的那一种。
            //     只要 gateway 里还有一行代码不在那层包装网里，
            //     这个 catch 就是必要的。
            //
            //   ⚠️ 但日志级别是 ERROR 不是 WARN —— 走到这里说明
            //      gateway 违反了它自己的契约（它承诺只抛 McpGatewayException），
            //      **那是个 bug，不是一次失败**。这条日志就是它的线索。
            log.error("★ 工具调用抛出了非 McpGatewayException 的异常"
                            + "（gateway 违反了自己的契约，请查 SdkMcpToolGateway）tool={} {}",
                    call.name(), e.toString(), e);
            return ToolOutcome.failed("实时查询服务出现内部错误，这次没能查到数据。");
        }
    }

    /**
     * 取服务端拒绝的理由。
     *
     * <p>★ 这里<b>刻意保留服务端的原话</b>（比如「未知的工具：query_weather」），
     * 而不是换成一句我们自己写的「工具调用失败」。理由是那句话本来就是
     * <b>写给模型看的</b>（5.7 的 {@code McpToolException} 设计如此，消息里
     * 带着可用参数列表）—— 中间转一手，我们就成了那个把有用信息磨掉的人。
     *
     * <p>实测（2026-09-19）确认这条链路是通的：服务端回 {@code -32602}，
     * SDK 抛 {@code McpError}，消息是我们写的原文。
     */
    private static String serverRejection(McpGatewayException e) {
        Throwable cause = e.getCause();
        String message = cause == null ? null : cause.getMessage();
        if (message == null || message.isBlank()) {
            return "工具调用被服务端拒绝：" + e.getMessage();
        }
        return "工具调用被服务端拒绝：" + message;
    }

    /**
     * 解析模型给的参数。
     *
     * <p>★ 用 {@code readValue} 到一个 {@code Map} 而不是先判空 ——
     * 无参工具的 {@code arguments} 是 {@code "{}"} 或空串，
     * 这两种都要能变成空 Map（见 {@link ToolCall#hasNoArguments()}）。
     */
    private Map<String, Object> parseArguments(ToolCall call) throws Exception {
        if (call.hasNoArguments()) {
            return Map.of();
        }
        Map<String, Object> parsed = objectMapper.readValue(call.arguments(), ARGUMENTS_TYPE);
        if (parsed == null) {
            throw new IllegalArgumentException("解析结果为空");
        }
        return parsed;
    }

    // ============================================================
    // 工具清单
    // ============================================================

    /**
     * 这次问答能用的工具。
     *
     * @param available true = 拉到清单了；false = 连不上或功能被关掉
     * @param specs     工具清单。{@code available} 为 false 时是空列表
     */
    private record Toolbox(boolean available, List<ToolSpec> specs) {

        static Toolbox of(List<ToolSpec> specs) {
            return new Toolbox(true, List.copyOf(specs));
        }

        static Toolbox unavailable() {
            return new Toolbox(false, List.of());
        }

        /** 拉到了清单，但里面一个工具都没有 —— ★ 和「连不上」不是一回事 */
        boolean isEmpty() {
            return available && specs.isEmpty();
        }
    }

    /**
     * 拉工具清单。
     *
     * <p>★★ <b>连不上时返回 {@link Toolbox#unavailable()} 而不是抛异常。</b>
     * 这一步是整个类里最容易写错的地方：把它写成
     * 「拉不到就抛」的话，工具服务的一次抖动会让
     * <b>所有走工具意图的问题</b>都变成「服务内部错误」——
     * 而用户问的可能只是「我的券还有几天过期」。
     *
     * <p>★ 另外注意：<b>它不区分「工具被配置关掉了」和「连不上」</b>。
     * 两者对模型的意义完全一样（「你现在没有工具可用」），
     * 所以合并成一种状态；区别只在日志里。
     */
    private Toolbox resolveToolbox(Long userId) {
        if (userId == null) {
            return Toolbox.unavailable();
        }
        try {
            List<ToolSpec> specs = gateway.listTools(userId);
            if (specs.isEmpty()) {
                // ★ 服务端连上了但一个工具都没注册 —— 这是个【部署问题】
                //   （工具 Bean 没被扫描到？），值得一条 WARN。
                //   而它就表现为「模型什么也查不到」，不主动报出来很难发现
                log.warn("MCP 服务端连上了但没有任何工具 —— 检查工具 Bean 是否被扫描到");
                return Toolbox.of(specs);
            }
            log.debug("拉到 MCP 工具 {} 个：{}",
                    specs.size(), specs.stream().map(ToolSpec::name).toList());
            return Toolbox.of(specs);

        } catch (McpGatewayException e) {
            // ★ 不抛。见方法注释
            log.error("拉取 MCP 工具清单失败（{}），本次问答将不带工具继续 —— "
                            + "用户会收到「实时查询不可用」而不是「服务内部错误」",
                    e.getMessage());
            return Toolbox.unavailable();
        }
    }

    // ============================================================
    // 工具结果 → 进 Prompt 的那段字
    // ============================================================

    /**
     * 把工具结果转成一条 {@code role=tool} 消息的正文。
     *
     * <p>★ <b>这里必须带上 {@code isError} 的显式标记。</b>
     * 消息里只有一段文本，模型没法区分「工具查到了，内容是『没有找到订单』」
     * 和「工具坏了」。所以失败时前面加一句
     * {@code 【工具调用失败】}，成功时什么都不加 ——
     * <b>成功是默认情况，不占字数</b>。
     *
     * <p>⚠️ 截断上限的存在理由：工具结果的长度是<b>我们控制不了的</b>
     * （将来某个工具可能返回一整个商品列表）。不设上限的话，一次失控的
     * 工具返回会把整个上下文顶爆，而症状是「模型开始答非所问」。
     * 截断时<b>显式写出来</b>，不静默 —— 否则模型不知道自己少看了东西。
     */
    private static String toolResultText(ToolOutcome outcome) {
        String body = outcome.text() == null ? "" : outcome.text();

        if (body.length() > MAX_TOOL_RESULT_CHARS) {
            body = body.substring(0, MAX_TOOL_RESULT_CHARS)
                    + "\n（结果过长已截断，只显示了前 " + MAX_TOOL_RESULT_CHARS + " 个字符）";
        }
        if (body.isBlank()) {
            // ★ 空结果也要说清楚。传一个空串给模型，它会当成
            //   「工具说没有」—— 而实际上是工具什么都没说
            body = outcome.isError()
                    ? "工具调用失败，没有拿到任何数据。"
                    : "工具执行成功，但没有返回任何内容。";
        }
        return outcome.isError() ? "【工具调用失败】" + body : body;
    }

    private static int textLength(ToolOutcome outcome) {
        return outcome.text() == null ? 0 : outcome.text().length();
    }

    private static int textLength(String text) {
        return text == null ? 0 : text.length();
    }
}
