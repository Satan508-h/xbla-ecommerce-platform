package com.xbla.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.agent.intent.ClarificationDecider;
import com.xbla.rag.agent.intent.IntentClassification;
import com.xbla.rag.agent.intent.IntentTree;
import com.xbla.rag.agent.intent.LlmIntentClassifier;
import com.xbla.rag.agent.memory.ConversationMemory;
import com.xbla.rag.agent.memory.MemoryContext;
import com.xbla.rag.agent.memory.SessionSummarizer;
import com.xbla.rag.agent.tool.ToolLoop;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.common.TraceId;
import com.xbla.rag.config.AgentProperties;
import com.xbla.rag.config.RetrievalProperties;
import com.xbla.rag.rag.RetrievalDetailBuilder;
import com.xbla.rag.rag.RetrievalPipeline;
import com.xbla.rag.rag.RetrievalTrace;
import com.xbla.rag.rag.facts.PolicyFactProvider;
import com.xbla.rag.rag.facts.StructuredFacts;
import com.xbla.rag.rag.prompt.RagPromptBuilder;
import com.xbla.rag.rag.retrieve.RetrievalOptions;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.StreamResult;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.entity.ChatMessage;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.service.ChatMessageService;
import com.xbla.rag.service.ChatService;
import com.xbla.rag.service.CallContext;
import com.xbla.rag.service.ChatSessionService;
import com.xbla.rag.service.QaLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答业务编排的实现。
 *
 * <h3>★ 写库时序（CLAUDE.md 第 4 条：所有写操作要能追溯到 qa_log）</h3>
 *
 * <pre>
 * 1. 生成 traceId
 * 2. 会话 upsert（sessionNo 为空则新建）
 * 3. 写 chat_message(role=1) —— 用户提问
 * 4. try   { 调模型 → 写 chat_message(role=2) + qa_log(status=1) }
 *    catch { 写 qa_log(status=2)，【不写】助手消息 }
 *    finally { 更新会话的 last_active_at 和 message_count }
 * </pre>
 *
 * <h3>三个必须注意的点</h3>
 *
 * <p><b>① 失败时不写助手消息。</b>
 * {@code chat_message.content} 是 {@code NOT NULL}，
 * 硬塞一句「服务异常」进去能绕过约束，但会<b>污染阶段 7 的评测语料</b>——
 * 那条记录看起来像模型真的说了这句话。失败的事实由
 * {@code qa_log.status=2} 表达，那才是正确的落点。
 *
 * <p><b>② 流式路径【绝对不能】加 {@code @Transactional}。</b>
 * 事务是绑定线程的。Controller 返回 {@code SseEmitter} 时 HTTP 请求线程
 * 已经结束、事务早就提交了，而真正写库发生在推送线程上、流结束之后。
 * 加事务的话，那段代码跑在一个已经结束的事务上下文里，行为未定义 ——
 * 可能静默不生效，也可能抛莫名其妙的状态异常。
 * 因此这里让每次 {@code save()} 各自自动提交。
 *
 * <p><b>③ {@code chat_session.last_active_at} 要手工设置。</b>
 * 自动填充处理器（{@code MybatisPlusMetaObjectHandler}）只管
 * {@code created_at} / {@code updated_at}，{@code last_active_at} 是业务字段，
 * 不在它的职责范围内。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    /**
     * 默认系统提示词。
     *
     * <p>阶段 2 还没有接知识库，所以这里明确告诉模型「不要编造」——
     * 否则它会拿通用知识硬答电商政策问题，而那种错误答案
     * 在评测阶段会很难和「检索没召回到」区分开。
     */
    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是「休伯利安」电商平台的智能客服助手，负责回答商品咨询、规格对比、促销政策和售后服务类问题。
            回答要求：
            1. 用简洁、友好的中文回答，直接给结论，不要复述问题
            2. 如果问题需要具体的订单号、商品信息或平台政策才能回答，而你手头没有这些信息，
               请如实说明你需要什么，不要编造政策条款或数字
            3. 不要输出与问题无关的寒暄
            """;

    /** 会话标题取提问的前 N 个字符 */
    private static final int TITLE_MAX_LENGTH = 50;

    private final ChatModelRouter router;
    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final QaLogService qaLogService;
    private final ObjectMapper objectMapper;
    private final RetrievalPipeline retrievalPipeline;
    private final RetrievalDetailBuilder retrievalDetailBuilder;
    private final RagPromptBuilder ragPromptBuilder;

    // ── 意图识别与澄清反问（阶段 5.3 新增）──
    private final LlmIntentClassifier intentClassifier;
    private final ClarificationDecider clarificationDecider;
    private final AgentProperties agentProperties;

    // ── 意图定向检索（阶段 5.4 新增）──
    private final IntentTree intentTree;
    private final RetrievalProperties retrievalProperties;

    // ── 结构化事实注入（阶段 5.9 新增）──
    private final PolicyFactProvider policyFactProvider;

    // ── 滑动窗口会话记忆（阶段 5.5 新增）──
    private final ConversationMemory conversationMemory;

    // ── 摘要压缩（阶段 5.6 新增）──
    private final SessionSummarizer sessionSummarizer;

    /**
     * 模型 ⇄ MCP 工具的多轮往返（阶段 5.8）。
     *
     * <p>★ 它<b>只被 {@code ask()} 用到</b>，{@code askStream()} 还没接 ——
     * 见 {@link ChatService#askStream} 的说明。这个不对称是刻意的边界，
     * 不是漏改。
     */
    private final ToolLoop toolLoop;

    // ============================================================
    // ★ 意图识别（阶段 5.3 新增）
    //
    // ⚠️ 和检索一样，这两个方法被 ask() 和 askStream() 【两条路径各调用一次】。
    //    改其中一个的调用点时记得同步改另一个 —— 这两条是平行代码。
    // ============================================================

    /**
     * 做意图分类，<b>并保证永不抛异常</b>。开关关闭时返回 {@code null}。
     *
     * <h3>★ 为什么必须自己吞掉异常（和 {@link #retrieveSafely} 完全同一条理由）</h3>
     *
     * <p>调用点所在的那个 {@code try} 块，{@code catch} 的语义是
     * 「<b>模型链路</b>失败 → {@code qa_log.status = 2}」。
     * 分类的异常冒到那里，阶段 7 会把「分类挂了」统计成「模型挂了」——
     * 归因彻底错乱，而且从数据上完全看不出来。
     *
     * <p>（{@code LlmIntentClassifier.classify} 本身已经把所有失败都转成了
     * {@link IntentClassification.Outcome}，这里再包一层是防御性的 ——
     * 万一将来它在构造 prompt、查树的过程中抛了别的异常。）
     *
     * <h3>★ 返回 null 的两种含义，调用方不需要区分</h3>
     *
     * <p>「开关关着」和「分类失败」在这里都返回 {@code null}，
     * 两者的后续行为完全一样：<b>不澄清，照常检索</b>。
     * {@code qa_log.intent} 也都写 {@code null}。
     *
     * <p>⚠️ 但阶段 7 做 A/B 时要区分它们 —— 看配置快照里的
     * {@code xbla.agent.intent.enabled} 即可，不需要在数据里再记一份。
     */
    private IntentClassification classifySafely(String question) {
        if (!agentProperties.getIntent().isEnabled()) {
            return null;
        }
        try {
            return intentClassifier.classify(question);
        } catch (Exception e) {
            log.warn("意图分类出现未预期的异常，按「不澄清、照常检索」处理：{}", e.getMessage(), e);
            return null;
        }
    }

    // ============================================================
    // ★ 检索（阶段 4 新增）
    //
    // ⚠️ 下面这两个方法被 ask() 和 askStream() 【两条路径各调用一次】。
    //    改动其中一个的调用点时，记得同步改另一个 ——
    //    这两条路径是平行代码，不是共用实现。
    // ============================================================

    /**
     * 执行检索，<b>并保证永不抛异常</b>。
     *
     * <h3>★ 为什么必须自己吞掉异常</h3>
     *
     * <p>调用点所在的那个 {@code try} 块，它的 {@code catch} 语义是
     * 「<b>模型链路</b>失败 → {@code qa_log.status = 2}」。
     *
     * <p>如果检索的异常冒到那里，阶段 7 统计时会把
     * 「<b>检索挂了</b>」算成「<b>模型挂了</b>」——
     * <b>归因彻底错乱，而且从数据上完全看不出来</b>：
     * {@code status=2} 的行看起来就是模型调用失败。
     *
     * <p>所以检索自己处理自己的失败：记进 {@code RetrievalTrace}，
     * 返回空列表，让问答退化成没有知识库上下文的裸聊。
     * 用户至少还能拿到一个基于模型自身知识的回答，
     * 比整次请求 500 好得多。
     */
    private List<RetrievedChunk> retrieveSafely(String question, RetrievalOptions options,
                                                RetrievalTrace trace) {
        try {
            return retrievalPipeline.retrieve(question, options, trace);
        } catch (Exception e) {
            // 理论上 RetrievalPipeline 内部已经处理了所有失败路径，
            // 这里是最后一道闸 —— 防御的是「我们没预料到的运行时异常」
            log.error("★ 检索发生未预期的异常，退化为无知识库上下文 traceId={}: {}",
                    trace.traceId(), e.getMessage(), e);
            trace.event("retrieval_crashed: " + e.getClass().getSimpleName());
            return List.of();
        }
    }

    // ============================================================
    // ★ 意图定向检索（阶段 5.4 新增）
    //
    // ⚠️ 和上面两个方法一样，被 ask() 和 askStream() 【两条路径各调用一次】。
    // ============================================================

    /**
     * 把意图分类结果翻译成「允许检索的 {@code doc_type} 范围」，构造本次检索的选项。
     *
     * <h3>★ 只有两行，因为有一半的情况被 {@code docTypesOf} 天然吃掉了</h3>
     *
     * <p>空列表在 {@link RetrievalOptions} 里的含义是<b>不限制</b>（不是「什么都不匹配」），
     * 而 {@code IntentTree.Tree.docTypesOf(code)} 在三种情况下都返回空集 ——
     * 每一种的「不限制」都是正确的：
     *
     * <ol>
     *   <li><b>非叶子 code</b>（{@code OUT_OF_SCOPE} / {@code NEEDS_CLARIFICATION}，
     *       以及 {@code ORDER_STATUS} / {@code INVENTORY} / {@code MY_COUPON}
     *       这三个顶层的工具意图）。{@code findLeaf} 查不到它们 → 空集。
     *       ★ 这里<b>不需要</b>单独判断一次 {@code role}：澄清路径在更前面就短路了；
     *       而 {@code OUT_OF_SCOPE} 走到这里时给它全池是诚实的 ——
     *       它虽然标着 {@code retrieval: NONE}，但那条「不检索」的短路还没实现
     *       （见 {@code docs/05} §9.3 ⑩），当前它确实会检索</li>
     *   <li><b>叶子声明了空集</b> —— 「答案不在知识库里」不等于「什么都不匹配」，
     *       理由见 {@link RetrievalOptions}</li>
     *   <li><b>模型编了个树里不存在的叶子码</b> —— 但那属于分类失败，
     *       在上面第一行就被拦下了，走不到这里</li>
     * </ol>
     *
     * <h3>★ 这里只「声明」，不决定「要不要真的过滤」</h3>
     *
     * <p>因为那个判断需要池子大小和 {@code vector-top-k}，两者都在检索层。
     * 见 {@code RetrievalPipeline} 类注释第四节。本方法只负责
     * 「意图 → 哪几种文档」这一个纯查表动作。
     *
     * <p>{@code intentTree.get()} 每次调用都会做一次 mtime 检查（支持热加载），
     * 所以改了 {@code intent-tree.yml} <b>不用重启</b>就生效 ——
     * 这也是 5.1 选择 YAML 而不是硬编码的理由之一。
     */
    private RetrievalOptions retrievalOptions(IntentClassification intent) {
        int topK = retrievalProperties.getRetrieve().getVectorTopK();

        if (intent == null || intent.outcome() != IntentClassification.Outcome.CLASSIFIED) {
            // 意图识别关闭，或分类失败。不知道范围时就别乱缩 ——
            // 全池检索是唯一合理的默认，而且它等于阶段 4 的行为，不引入回归
            return RetrievalOptions.unfiltered(topK);
        }

        // docTypesOf 返回的是 Set，这里排序转 List：
        // 顺序稳定的字面量（"{2,4}" 而不是 "{4,2}"）让两次运行的 retrieval_detail
        // 可以直接 diff —— 排序本身在 RetrievalOptions 里也会做一次，这里显式写出来
        // 是为了让「集合 → 字面量」这条路上没有一步是依赖哈希顺序的
        List<Integer> docTypes = intentTree.get().docTypesOf(intent.code())
                .stream().sorted().toList();
        return new RetrievalOptions(topK, docTypes);
    }

    /**
     * 把检索结果、会话历史与调用方给的 system prompt 拼成最终的 system prompt。
     *
     * <p>两条路径共用。空检索结果不会产生空标题，而是换成一句
     * 「本次未检索到」的说明 —— 见 {@link RagPromptBuilder}。
     *
     * @param memory 本次读到的会话记忆（阶段 5.6）。★ 摘要和原文<b>一起</b>传进去，
     *               因为「有没有历史上下文」= 两者任一非空，
     *               这个判断不该散在调用点上
     * @param facts  结构化事实（阶段 5.9）。传 {@link StructuredFacts#EMPTY} 表示没有 ——
     *               ★ 不要传 null，那两个含义不同的东西在这里是同一个，
     *               而 null 会让「忘了传」和「确实没有」长得一样
     */
    private String ragSystemPrompt(String requestSystemPrompt, List<RetrievedChunk> chunks,
                                   MemoryContext memory, StructuredFacts facts) {
        return ragPromptBuilder.build(
                systemPromptOrDefault(requestSystemPrompt),
                chunks,
                memory.hasHistory(),
                memory.sessionSummary(),
                facts);
    }

    /**
     * 这次问答要不要额外带一份结构化事实（阶段 5.9）。
     *
     * <h3>★ 判据在意图树里，不在这个方法里</h3>
     *
     * <p>「哪类问题需要政策硬数据」是<b>树里的声明</b>
     * （{@code structured_facts: POLICY}），不是 Java 该判断的事 ——
     * 同 {@link #isToolIntent} 那条「不要把 YAML 里的知识挪进 Java」。
     *
     * <p>⚠️ 和 {@code retrieveSafely} / {@code classifySafely} 一样，
     * <b>这里也自己吞异常</b>。理由完全一样：查政策表失败混进外层那个
     * {@code catch} 会被记成「模型链路失败」，
     * 于是阶段 7 把「政策表读挂了」统计成「模型挂了」—— 归因彻底错乱。
     * 而且这次更严重：<b>硬数据只是锦上添花，缺了它检索照常工作</b>，
     * 没有任何理由让它影响一次问答。
     *
     * @return 永远不会返回 null（失败时返回 {@link StructuredFacts#EMPTY}）
     */
    private StructuredFacts structuredFactsSafely(IntentClassification intent) {
        if (intent == null || !intent.isClassified()) {
            return StructuredFacts.EMPTY;
        }
        IntentTree.StructuredFact kind = intentTree.get().structuredFactOf(intent.code());
        if (kind == IntentTree.StructuredFact.NONE) {
            return StructuredFacts.EMPTY;
        }
        try {
            return switch (kind) {
                case POLICY -> policyFactProvider.load();
                // ★ 加新的 StructuredFact 值时，编译器会在这里逼你补一支 ——
                //   这正是用枚举而不是布尔/字符串的收益
                case NONE -> StructuredFacts.EMPTY;
            };
        } catch (Exception e) {
            log.error("★ 读取结构化事实失败，本次不带硬数据继续（检索部分不受影响）"
                    + "意图={}: {}", intent.code(), e.getMessage(), e);
            return StructuredFacts.EMPTY;
        }
    }

    /**
     * 组装 {@code qa_log.references} / {@code chat_message.references} 的内容。
     *
     * <p>结构：切片 ID + 重排分数 + 标题路径。<b>不含正文</b> ——
     * 正文在 {@code kb_chunk} 里，按 id 取回即可；
     * 塞进来会让每一次问答都多写几千字节。
     *
     * <p>没有引用时返回 <b>null</b> 而不是空数组，
     * 与 {@code degradation_events} 的既有约定一致，
     * 便于写 {@code WHERE references IS NOT NULL} 的查询。
     */
    private List<Map<String, Object>> buildReferences(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> refs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("no", i + 1);                       // 对应 prompt 里的 [编号]
            ref.put("chunk_id", chunk.id());
            ref.put("document_id", chunk.documentId());
            ref.put("score", chunk.score());
            ref.put("heading_path", chunk.headingPath());
            refs.add(ref);
        }
        return refs;
    }

    // ============================================================
    // 非流式
    // ============================================================

    @Override
    public ChatAskResponse ask(ChatAskRequest request, Long userId) {
        return ask(request, userId, CallContext.fresh());
    }

    /**
     * 非流式问答（阶段 6：带调用上下文）。
     *
     * <p>★ 上面那个两参版本只做一件事：造一个「没排队」的上下文然后委托过来。
     * <b>把它写成「两参 = 三参的特例」，而不是两份平行实现</b> ——
     * 后者正是 {@link #askStream} 的注释里反复提到的那个坑
     * （本项目已经因为「两条平行路径改了一条」踩过 ADR-047）。
     */
    @Override
    public ChatAskResponse ask(ChatAskRequest request, Long userId, CallContext ctx) {
        long startNanos = System.nanoTime();
        ctx = ctxOrFresh(ctx);
        String traceId = ctx.traceId();

        ChatSession session = resolveSession(request.sessionNo(), request.question());

        // ★★ 5.5：读历史必须在 saveUserMessage 【之前】。
        //
        //   ChatRequest.history 的契约是「不含本轮提问」。反过来的话，
        //   窗口里会有一份本轮的提问，而它马上又会作为 userQuestion 再发一次 ——
        //   模型收到两条一模一样的用户消息。这个 bug 从日志和落库数据上
        //   都看不出来（两边各自都是对的，错的是它们的相对顺序），
        //   现象只是「模型偶尔答非所问」。详见 ConversationMemory 类注释第二节。
        //
        //   ⚠️ 澄清路径用不到历史（它不调生成模型），但这里仍然先读了。
        //      代价是一次走 idx_chat_message_session 的范围查询；
        //      换来的是「不需要把本轮消息的 id 一路传进来做排除」——
        //      顺序本身就是最好的约束，而多一条贯穿的返回值是负债。
        //
        //   ★ 5.6：一次拿到【摘要 + 窗口原文】。两者必须来自同一次读 ——
        //     它们是同一条时间轴上相邻的两段，分两次读会让接缝错位，
        //     而错位产生的是【空洞】：那几条消息永远不会被补上。
        //     详见 MemoryContext 和 ConversationMemory 的类注释第四、五节。
        MemoryContext memory = conversationMemory.load(session.getId());

        saveUserMessage(session.getId(), request.question());

        // ★★ 意图识别插在【用户消息落库之后、检索之前】。
        //
        //   为什么必须在检索【之前】：澄清要短路掉整条链路 ——
        //   等检索跑完再决定澄清，那几百毫秒和向量化的钱就白花了，
        //   而且用户得到的仍然是那句反问，不会因为检索过而更好。
        //
        //   （★ askStream 里插在 sink.onStart 【之后】，理由见那个方法。）
        IntentClassification intent = classifySafely(request.question());
        ClarificationDecider.Decision decision =
                clarificationDecider.decide(request.question(), intent);
        if (decision.shouldClarify()) {
            return answerWithClarification(ctx, session, request.question(),
                    intent, decision.clarifyText(), startNanos);
        }

        // ★★ 工具分支（阶段 5.8）—— 插在【澄清之后、检索之前】。
        //
        //   三个位置都是刻意选的：
        //
        //   ① 在澄清【之后】：澄清是「我的业务但你得再说点」，
        //      它连检索都不做，自然也不该调工具。顺序反了的话，
        //      「那个订单怎么样了」会先去查一个不存在的订单号。
        //
        //   ② 在检索【之前】：★★ 这是关键的一条。
        //      工具类意图在意图树里声明的是 doc_types: [] = 【不限制】
        //      （ADR-044）。如果让 KB 检索先跑，它会拿整池切片做一次
        //      全量双路召回 —— 用户问「我的订单到哪了」，
        //      召回的全是「一般发货要几天」这类通用规则，
        //      然后<b>模型会拿它们编一个具体的订单状态出来</b>。
        //      所以工具意图必须【短路掉检索】，一次都不能跑。
        //
        //   ③ 在 saveUserMessage【之后】：顺序和检索那条一样 ——
        //      先落用户消息，工具失败时问题仍然在库里，评测数据是完整的。
        //
        //   ⚠️ 只有 intent 分类成功、且它声明的 retrieval 是 TOOL 才走这里。
        //      分类失败/关闭时 retrievalOf 返回 null，走原来的检索路径 ——
        //      那等于阶段 5.7 之前的行为，不引入回归。
        if (isToolIntent(intent)) {
            return answerWithTools(ctx, session, request, memory, intent, userId, startNanos);
        }

        // ★ 检索插在【用户消息落库之后】。顺序是有意的：
        //   先落用户消息，检索失败时问题仍然在库里，评测数据是完整的。
        // ★ 意图定向检索（5.4）：把分类结果翻译成 doc_type 范围。
        //   注意这只是【声明】—— 要不要真的下推由 RetrievalPipeline 决定
        //   （池子太小它会拒绝、过滤后一无所获它会回落）。理由见那个类的注释第四节，
        //   核心是：调试探针和线上必须走同一套规则，所以判断只能有一处。
        RetrievalTrace retrievalTrace = new RetrievalTrace(traceId);
        List<RetrievedChunk> chunks = retrieveSafely(
                request.question(), retrievalOptions(intent), retrievalTrace);

        ModelCallTrace trace = new ModelCallTrace(traceId);
        ChatRequest modelRequest = ChatRequest.of(
                ragSystemPrompt(request.systemPrompt(), chunks, memory,
                        structuredFactsSafely(intent)),
                memory.history(),
                request.question());

        try {
            ChatResponse response = router.chat(modelRequest, trace);

            saveAssistantMessage(session.getId(), response, chunks, intent);
            saveQaLogSuccess(ctx, session, request.question(), trace, retrievalTrace,
                    response, chunks, startNanos, intent);
            touchSession(session);

            // ★ 5.6：压缩任务丢给后台，立即返回。它只做一次提交（微秒级），
            //   真正的检查与生成在线程池里 —— 用户一秒都不多等。
            //   ⚠️ 放在【助手消息落库之后】：压缩按 id 区间取数，
            //      本轮的回答必须先落库，否则那一轮会被算进「未覆盖区间」却读不到。
            sessionSummarizer.maybeSummarizeAsync(session.getId());

            ChatAskResponse result = buildResponse(traceId, session, trace, response, chunks,
                    startNanos, intent);
            log.info("问答完成 {} | {} | {}", trace.summary(), retrievalTrace.summary(),
                    memory.summary());
            return result;

        } catch (Exception e) {
            // ★ 失败也要落 qa_log —— 它是评测的唯一数据来源，
            //   而且「哪类问题答不上来」本身就是重要信息
            //
            //   ★ 注意这里的 e 【只可能来自模型链路】—— 检索与意图分类的异常
            //     已经被 retrieveSafely / classifySafely 各自吞掉了。混进来会让归因错乱
            saveQaLogFailure(ctx, session, request.question(), trace, retrievalTrace,
                    chunks, e, startNanos, intent);
            touchSession(session);
            throw e;
        }
    }

    // ============================================================
    // ★ 工具路径（阶段 5.8，只有非流式）
    // ============================================================

    /**
     * 这次该走工具吗 —— <b>纯查表，不含任何业务判断</b>。
     *
     * <p>★ 判据是意图树里声明的 {@code retrieval} 字段，不是 code 的字面量。
     * 写 {@code "ORDER_LOGISTICS".equals(intent.code())} 也能跑，
     * 但那把「订单物流这类问题该调工具」这条知识<b>从 YAML 挪进了 Java</b> ——
     * 以后在意图树里加第四个工具类意图时，这里会被忘记改，
     * 而症状是「新加的那类问题永远查不到实时数据」，
     * 日志里一行异常都没有。
     *
     * <p>⚠️ 分类失败 / 关闭 / 模型编了个不存在的 code 时，
     * {@code retrievalOf} 返回 {@code null}，这里返回 {@code false} ——
     * 走原来的检索路径，等于 5.7 之前的行为，<b>不引入回归</b>。
     */
    private boolean isToolIntent(IntentClassification intent) {
        if (intent == null || !intent.isClassified()) {
            return false;
        }
        return intentTree.get().retrievalOf(intent.code()) == IntentTree.Retrieval.TOOL;
    }

    /**
     * 工具路径（阶段 5.8）。
     *
     * <h3>★ 它和知识库路径有三处刻意的不同</h3>
     *
     * <p><b>① 不检索，一次都不跑。</b>
     * {@code retrievalTrace} 和 {@code chunks} 一路传 {@code null} ——
     * 于是 {@code qa_log} 的检索那几列全是 NULL。
     * 这正是 {@code ADR-041} 那条「没有发生就说没有发生」：
     * {@code qa_log.status} 是 1（我们确实答了），
     * 但 {@code retrieval_detail} 是 NULL（检索确实没跑）。
     * 传一个空的 {@code RetrievalTrace} 进去的话，序列化出来是
     * {@code {"vector_hits":[],...}} —— 和「检索跑了但什么都没召回到」
     * <b>逐字相同</b>，而这两件事的排查方向完全相反。
     *
     * <p><b>② 身份要一路传下去。</b>
     * 它是工具那层唯一的越权防线（见 {@code McpToolContext}）。
     * {@code userId} 为 null 时 {@code ToolLoop} 会短路不调模型，
     * 回一句「需要先知道你是哪位」—— 这里不做任何特殊处理，
     * 因为「什么情况下能答」是工具层的事。
     *
     * <p><b>③ 模型可能被调用【多次】。</b>
     * 所以 trace 是逐轮累加出来的（见 {@code ModelCallTrace.mergeRound}），
     * 成本、token、耗时都是<b>合计值</b>。
     *
     * <h3>异常</h3>
     *
     * <p>★ 能被这里捕获的异常<b>只有模型链路的</b>——
     * 工具的任何失败都已经被 {@code ToolLoop} 转成了工具结果
     * （见那个类的注释第二节）。所以下面那个 catch 的语义
     * 「模型失败了」是准确的，不会把工具故障误记成模型故障。
     */
    private ChatAskResponse answerWithTools(CallContext ctx, ChatSession session,
                                            ChatAskRequest request, MemoryContext memory,
                                            IntentClassification intent, Long userId,
                                            long startNanos) {
        String traceId = ctx.traceId();

        ModelCallTrace trace = new ModelCallTrace(traceId);

        // ★ system prompt 和知识库路径用同一套组装逻辑，只是 chunks 为空。
        //   RagPromptBuilder 在空上下文时不会写「以下是相关资料：（空）」，
        //   而是换成一句「本次未检索到知识库内容」的说明 —— 对工具路径来说
        //   这句话是对的：这次确实没检索，而且查到的实时数据会以
        //   role=tool 消息的形式进来，不占 system 的位置
        // ★ 结构化事实这里【固定传 EMPTY】而不是 structuredFactsSafely(intent)：
        //   走工具这条路的意图检索都不检索，而加载期已经强制
        //   「非 KB 的叶子不能声明 structured_facts」——
        //   所以这里传 EMPTY 是【设计】，不是「恰好查不到」。
        //   写成 structuredFactsSafely(intent) 也能跑，但那会让人以为
        //   工具意图将来可能带上硬数据 —— 而那需要一个不存在的 prompt 组装时机
        String systemPrompt = ragSystemPrompt(request.systemPrompt(), List.of(), memory,
                StructuredFacts.EMPTY);

        try {
            ToolLoop.Result result = toolLoop.run(
                    new ToolLoop.Input(systemPrompt, memory.history(),
                            request.question(), userId),
                    trace);

            saveAssistantToolAnswer(session.getId(), result, intent);
            saveQaLogTool(ctx, session, request.question(), trace, result, startNanos, intent);
            touchSession(session);

            // ★ 5.6 的摘要压缩照常触发 —— 工具回答也是会话的一部分，
            //   不压的话这段历史会一直占着窗口。
            //   ⚠️ 和知识库路径一样，必须放在助手消息落库【之后】
            sessionSummarizer.maybeSummarizeAsync(session.getId());

            log.info("工具问答完成 {} | {} 轮 | 调用 {} 次 | intent={}",
                    trace.summary(), result.rounds(), result.calls().size(), intentCode(intent));

            return buildToolResponse(traceId, session, trace, result, startNanos, intent);

        } catch (Exception e) {
            // ★ 只有模型链路失败才会到这里。检索那两列传 null —— 这条路径没检索过
            saveQaLogFailure(ctx, session, request.question(), trace, null, null,
                    e, startNanos, intent);
            touchSession(session);
            throw e;
        }
    }

    /**
     * 把工具回答拼成一次调用的结果 —— 好让 {@code saveQaLogSuccess} /
     * {@code buildResponse} 这两个既有方法原样复用。
     *
     * <p>★ <b>为什么绕这一下：</b>{@code ToolLoop} 返回的是一个字符串
     * （它不关心「供应商、用量、成本」这些属于模型调用层的概念），
     * 而落库和响应组装需要的是一个 {@link ChatResponse}。
     * 中间造一个「把合计值装进去」的 ChatResponse，比给那两个方法
     * 各写一个接受字符串的重载要好 —— 后者会让「一次问答的元信息怎么组装」
     * 这件事有两份实现，而它们迟早会漂移。
     *
     * @param answer 最终回答
     * @param trace  已经累加过所有轮次的轨迹
     */
    private static ChatResponse asResponse(String answer, ModelCallTrace trace) {
        return ChatResponse.text(
                answer,
                "stop",
                trace.usage(),
                trace.route(),
                trace.llmLatencyMs());
    }

    /**
     * 写工具路径的助手消息。
     *
     * <p>★ 它和澄清路径一样<b>要</b>写助手消息：模型确实说了这句话，
     * 会话回放时用户就该看到它。（和失败路径相反 ——
     * 那里写「服务异常」会污染评测语料。）
     *
     * <p>⚠️ {@code references} 留 <b>null</b> —— 这次引用的是<b>实时数据</b>，
     * 不是知识库切片。引用记录在 {@code qa_log.tool_calls} 里。
     */
    private void saveAssistantToolAnswer(Long sessionId, ToolLoop.Result result,
                                         IntentClassification intent) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(2);                     // 2 = 助手
        message.setContent(result.answer());
        message.setReferences(null);            // ★ 不是「空数组」，是没有
        message.setIntent(intentCode(intent));
        chatMessageService.save(message);
    }

    /**
     * 工具路径的 {@code qa_log}。
     *
     * <p>和 {@link #saveQaLogSuccess} 的唯一区别是多填了一列
     * {@code tool_calls}，以及检索那几列走 {@code null} 的既有语义。
     */
    private void saveQaLogTool(CallContext ctx, ChatSession session, String question,
                               ModelCallTrace trace, ToolLoop.Result result,
                               long startNanos, IntentClassification intent) {
        QaLog log = baseLog(ctx, session, question, trace, null, null, startNanos, intent);
        log.setFinalAnswer(result.answer());
        log.setToolCalls(serializeToolCalls(result));
        log.setStatus(QaLog.STATUS_SUCCESS);
        writeQaLog(log);
    }

    /**
     * 工具调用的记录 → JSON。
     *
     * <p>★ <b>没调过工具时返回 {@code null}，不是 {@code "[]"}</b> ——
     * 这是整个文件里反复出现的那条约定：「没发生」和「发生了但是空的」
     * 必须能区分开。见 {@code V8} 迁移的第三节。
     *
     * <p>⚠️ 短路路径（没有身份，{@code rounds=0}）也会走到这里，
     * 拿到 null。那是对的：那次问答<当然>一次工具都没调。
     */
    private String serializeToolCalls(ToolLoop.Result result) {
        if (result == null || !result.usedTools()) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>(result.calls().size());
        for (ToolLoop.CallRecord call : result.calls()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round", call.round());
            row.put("tool", call.toolName());
            row.put("isError", call.isError());
            row.put("detail", call.detail());
            rows.add(row);
        }
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            // 同 serializeEvents：序列化失败只记 ERROR，绝不抛 ——
            // 「记录过程信息」这件事失败，不该让已经拿到的回答作废
            log.error("tool_calls 序列化失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 工具路径的响应体。
     *
     * <p>★ {@code references} 传 {@code null}（不是空列表）：
     * 这次没有引用任何知识库切片。理由同 {@code baseLog} 里写的那条。
     */
    private ChatAskResponse buildToolResponse(String traceId, ChatSession session,
                                              ModelCallTrace trace, ToolLoop.Result result,
                                              long startNanos, IntentClassification intent) {
        ChatResponse response = asResponse(result.answer(), trace);
        var route = trace.route();
        return new ChatAskResponse(
                traceId,
                session.getSessionNo(),
                result.answer(),
                route == null ? null : route.provider(),
                route == null ? null : route.modelId(),
                response.usage(),
                trace.cost(),
                trace.llmLatencyMs(),
                elapsedMs(startNanos),
                trace.degraded(),
                trace.events(),
                null,                       // references —— 没检索
                intentCode(intent));
    }

    // ============================================================
    // 流式
    // ============================================================

    @Override
    public void askStream(ChatAskRequest request, ChatStreamSink sink) {
        askStream(request, sink, CallContext.fresh());
    }

    /**
     * 流式问答（阶段 6：带调用上下文）。
     *
     * <p>★ 排队层在主调这一个是<b>刻意的</b>：
     * traceId 在排队开始的那一刻就产生了，一路用到 {@code qa_log.trace_id}。
     * 见 {@code ChatService#askStream(ChatAskRequest, ChatStreamSink, CallContext)} 的说明。
     */
    @Override
    public void askStream(ChatAskRequest request, ChatStreamSink sink, CallContext ctx) {
        long startNanos = System.nanoTime();
        ctx = ctxOrFresh(ctx);
        String traceId = ctx.traceId();

        ChatSession session = resolveSession(request.sessionNo(), request.question());

        // ★★ 5.5：读历史必须在 saveUserMessage 【之前】。
        //
        //   ChatRequest.history 的契约是「不含本轮提问」。反过来的话，
        //   窗口里会有一份本轮的提问，而它马上又会作为 userQuestion 再发一次 ——
        //   模型收到两条一模一样的用户消息。这个 bug 从日志和落库数据上
        //   都看不出来（两边各自都是对的，错的是它们的相对顺序），
        //   现象只是「模型偶尔答非所问」。详见 ConversationMemory 类注释第二节。
        //
        //   ⚠️ 澄清路径用不到历史（它不调生成模型），但这里仍然先读了。
        //      代价是一次走 idx_chat_message_session 的范围查询；
        //      换来的是「不需要把本轮消息的 id 一路传进来做排除」——
        //      顺序本身就是最好的约束，而多一条贯穿的返回值是负债。
        //
        //   ★ 5.6：一次拿到【摘要 + 窗口原文】。两者必须来自同一次读 ——
        //     它们是同一条时间轴上相邻的两段，分两次读会让接缝错位，
        //     而错位产生的是【空洞】：那几条消息永远不会被补上。
        //     详见 MemoryContext 和 ConversationMemory 的类注释第四、五节。
        MemoryContext memory = conversationMemory.load(session.getId());

        saveUserMessage(session.getId(), request.question());

        // 先告诉前端 traceId 和 sessionNo，别让它对着空白页等首字节
        sink.onStart(traceId, session.getSessionNo());

        // ★★ 意图识别也插在 sink.onStart 【之后】，理由和检索完全一样 ——
        //   一次分类是 0.5~2.5 秒的模型往返（走完整降级链，P0 是推理模型），
        //   插在 onStart 前面会把这段时间原封不动地加在用户看到任何反馈之前，
        //   【正好抵消掉 onStart 的全部价值】。
        IntentClassification intent = classifySafely(request.question());
        ClarificationDecider.Decision decision =
                clarificationDecider.decide(request.question(), intent);
        if (decision.shouldClarify()) {
            answerStreamWithClarification(ctx, session, request.question(),
                    intent, decision.clarifyText(), sink, startNanos);
            return;
        }

        // ★★ 检索必须插在 sink.onStart 【之后】。
        //
        //   上一次调用 onStart 的全部意义就是「别让前端对着空白页等首字节」。
        //   而一次检索要花几百毫秒（一次向量化 + 两次数据库查询 + 一次重排），
        //   插在 onStart 前面会把这几百毫秒原封不动地加在用户看到任何反馈之前 ——
        //   【正好抵消掉 onStart 的全部价值】。
        //
        //   插在 router.chatStream 之后又毫无意义（那时候已经在生成了）。
        //   所以唯一正确的位置就是 onStart 与 chatStream 之间。
        // ★ 意图定向检索（5.4）：把分类结果翻译成 doc_type 范围。
        //   注意这只是【声明】—— 要不要真的下推由 RetrievalPipeline 决定
        //   （池子太小它会拒绝、过滤后一无所获它会回落）。理由见那个类的注释第四节，
        //   核心是：调试探针和线上必须走同一套规则，所以判断只能有一处。
        RetrievalTrace retrievalTrace = new RetrievalTrace(traceId);
        List<RetrievedChunk> chunks = retrieveSafely(
                request.question(), retrievalOptions(intent), retrievalTrace);

        ModelCallTrace trace = new ModelCallTrace(traceId);
        ChatRequest modelRequest = ChatRequest.of(
                ragSystemPrompt(request.systemPrompt(), chunks, memory,
                        structuredFactsSafely(intent)),
                memory.history(),
                request.question());

        // ★ 边推边攒：流式的正文是分批散出去给前端的，
        //   但 qa_log.final_answer 需要完整回答 —— 阶段 7 的
        //   「答案质量」类指标全部依赖它。不攒的话那张表里永远是空的。
        //
        //   内存开销可以忽略：单次回答上限约 2048 token，撑死几 KB。
        StringBuilder answerBuffer = new StringBuilder();

        try {
            StreamResult streamResult = router.chatStream(modelRequest, trace, delta -> {
                answerBuffer.append(delta);
                sink.onDelta(delta);
            });

            String fullAnswer = answerBuffer.toString();

            saveAssistantStreamMessage(session.getId(), trace, streamResult, fullAnswer, chunks,
                    intent);
            saveQaLogStreamSuccess(ctx, session, request.question(), trace, retrievalTrace,
                    streamResult, fullAnswer, chunks, startNanos, intent);
            touchSession(session);

            sink.onComplete(buildStreamResponse(traceId, session, trace, streamResult, chunks,
                    startNanos, intent));

            // ★ 5.6：压缩任务丢给后台。⚠️ 必须在 saveAssistantStreamMessage 【之后】——
            //   理由同 ask()：压缩按 id 区间取数，本轮回答不落库就会读不到。
            //   ⚠️ 放在 onComplete 【之后】是有意的：先把最后一批字推给用户，
            //      再做这件与本次回答无关的事。反过来的话，那几微秒的提交
            //      会挤在「最后一个字」和「流结束」之间。提交本身是微秒级，
            //      但顺序表达了意图。
            sessionSummarizer.maybeSummarizeAsync(session.getId());

            log.info("流式问答完成 {} | {} | {}", trace.summary(), retrievalTrace.summary(),
                    memory.summary());

        } catch (Exception e) {
            saveQaLogFailure(ctx, session, request.question(), trace, retrievalTrace,
                    chunks, e, startNanos, intent);
            touchSession(session);

            log.warn("流式问答失败 traceId={} : {}", traceId, e.getMessage());
            sink.onError(userFacingMessage(e), traceId);
        }
    }

    // ============================================================
    // ★ 澄清反问（阶段 5.3 新增）
    //
    // ⚠️ 和检索、意图一样，这一对方法被 ask() / askStream() 【两条路径各调用一次】。
    //    两条是平行代码，改一个记得改另一个。
    //
    // 澄清路径上【不检索、不调生成模型】—— 那是它存在的全部意义：
    // 用户问了一句答不了的话，系统立刻反问一句，
    // 而不是先花几百毫秒检索、再花几秒生成一段答非所问的内容。
    // ============================================================

    /**
     * 澄清路径（非流式）。
     *
     * <h3>★ 响应体里那几个 null 是有含义的，不是缺数据</h3>
     *
     * <p>{@code provider} / {@code model} / {@code usage} / {@code cost}
     * 全部为 <b>null</b>，{@code llmLatencyMs} 为 <b>0</b> ——
     * 因为它们描述的是「生成这个回答的那次模型调用」，而这次<b>没有</b>。
     *
     * <p>（分类确实调了一次模型，但那不是生成回答的调用；
     * 把它的耗时填进 {@code llmLatencyMs} 会让人以为答案是模型生成的。）
     *
     * <p>调用方靠 {@code intent = "NEEDS_CLARIFICATION"} 认出这是一次反问。
     */
    private ChatAskResponse answerWithClarification(CallContext ctx, ChatSession session,
                                                    String question, IntentClassification intent,
                                                    String clarifyText, long startNanos) {
        String traceId = ctx.traceId();
        saveAssistantClarification(session.getId(), clarifyText, intent);
        saveQaLogClarification(ctx, session, question, intent, clarifyText, startNanos);
        touchSession(session);

        log.info("澄清反问（非流式）traceId={} intent={}", traceId, intentCode(intent));
        return new ChatAskResponse(
                traceId,
                session.getSessionNo(),
                clarifyText,
                null,                       // provider —— 没有发生生成调用
                null,                       // model
                null,                       // usage
                null,                       // cost
                0,                          // llmLatencyMs
                elapsedMs(startNanos),
                false,                      // degraded —— 没走过降级链
                List.of(),                  // degradationEvents
                null,                       // references —— 没检索
                intentCode(intent));
    }

    /**
     * 澄清路径（流式）。
     *
     * <p>正文只有一段，一次性推出去，然后立刻 {@code onComplete} ——
     * 不走 {@code router.chatStream}。用户看到的是「打字机瞬间打完一句话」。
     *
     * @see #answerWithClarification 关于响应体里那些 null 的说明
     */
    private void answerStreamWithClarification(CallContext ctx, ChatSession session, String question,
                                               IntentClassification intent, String clarifyText,
                                               ChatStreamSink sink, long startNanos) {
        String traceId = ctx.traceId();
        try {
            sink.onDelta(clarifyText);
            sink.onComplete(new ChatAskResponse(
                    traceId, session.getSessionNo(), null,
                    null, null, null, null, 0, elapsedMs(startNanos),
                    false, List.of(), null, intentCode(intent)));
            log.info("澄清反问（流式）traceId={} intent={}", traceId, intentCode(intent));
        } catch (Exception e) {
            // 推送失败（客户端断开等）。★ 复用失败路径的写法，但要记成
            // status=3 而不是 2 —— 澄清判定本身是成功的，挂掉的是推送
            log.warn("澄清反问推送失败 traceId={} : {}", traceId, e.getMessage());
            sink.onError(userFacingMessage(e), traceId);
        } finally {
            saveAssistantClarification(session.getId(), clarifyText, intent);
            saveQaLogClarification(ctx, session, question, intent, clarifyText, startNanos);
            touchSession(session);
        }
    }

    /**
     * 写澄清的助手消息。
     *
     * <p>★ 澄清<b>要</b>写助手消息（和失败路径相反）：失败时不写是因为
     * 「服务异常」那句话会污染评测语料，看起来像模型真的说了那句话。
     * 而澄清反问<b>确实就是助手说的话</b>，会话回放时用户就该看到它。
     */
    private void saveAssistantClarification(Long sessionId, String clarifyText,
                                            IntentClassification intent) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(2);                     // 2 = 助手
        message.setContent(clarifyText);
        message.setIntent(intentCode(intent));
        // token / provider / model / latency 全部留空 —— 没有发生生成调用。
        // 留空而不是填 0：0 个 token 是一个数值，null 才是「没有这回事」
        chatMessageService.save(message);
    }

    /**
     * 写澄清的 {@code qa_log}，状态是 {@link QaLog#STATUS_CLARIFY}。
     *
     * <p>★ <b>不是</b> {@code STATUS_SUCCESS} 也不是 {@code STATUS_FAILED} ——
     * 澄清既没有生成答案，也没有失败。理由见 V7 迁移的注释。
     *
     * <p>{@code retrieval_detail} 会是 {@code null}（没检索），
     * 这本身就是一条信息：「这一次没有走检索链路」。
     */
    private void saveQaLogClarification(CallContext ctx, ChatSession session, String question,
                                        IntentClassification intent, String clarifyText,
                                        long startNanos) {
        // ★ retrievalTrace 传【null】而不是空对象 —— 语义是「没有发生检索」，
        //   于是几个检索列一起写 NULL。传空对象会得到一份和
        //   「检索跑了但什么都没召回」逐字相同的 JSON，两者分不开。
        //   ModelCallTrace 同样传 null，理由一样（没有生成调用）。
        QaLog log = baseLog(ctx, session, question,
                null, null, List.of(), startNanos, intent);
        log.setStatus(QaLog.STATUS_CLARIFY);
        // ★ final_answer 存的是【反问句】。阶段 7 必须靠 status 把它筛掉，
        //   否则答案质量指标会给一句反问打分
        log.setFinalAnswer(clarifyText);
        writeQaLog(log);
    }

    // ============================================================
    // 会话
    // ============================================================

    /**
     * 解析会话：传了 sessionNo 就查，没传就新建。
     *
     * <p>传了但查不到时也新建（并把 sessionNo 用上传的那个）——
     * 这样前端缓存了一个已经不存在的会话号时不会直接报错，
     * 而是无声地开一个新会话。
     */
    private ChatSession resolveSession(String sessionNo, String question) {
        if (sessionNo != null && !sessionNo.isBlank()) {
            ChatSession existing = chatSessionService.lambdaQuery()
                    .eq(ChatSession::getSessionNo, sessionNo)
                    .one();
            if (existing != null) {
                return existing;
            }
            log.debug("sessionNo={} 不存在，将新建会话", sessionNo);
        }

        ChatSession session = new ChatSession();
        session.setSessionNo(sessionNo != null && !sessionNo.isBlank()
                // ★ 会话号复用同一个生成器：它和 traceId 一样是「一段不可猜测的随机串」，
                //   没有理由为它再写一份 UUID 处理。★ 但两者【语义不同】——
                //   一个标识会话，一个标识这一次请求，别把它们当成同一个东西。
                ? sessionNo : TraceId.newId());
        session.setUserId(null);            // 阶段 2 还没有登录体系，支持匿名会话
        session.setTitle(truncateTitle(question));
        session.setMessageCount(0);
        session.setStatus(1);               // 1 = 进行中
        session.setLastActiveAt(OffsetDateTime.now());
        chatSessionService.save(session);

        log.debug("新建会话 id={} sessionNo={}", session.getId(), session.getSessionNo());
        return session;
    }

    /** 更新会话的活跃时间与消息计数 */
    private void touchSession(ChatSession session) {
        try {
            ChatSession update = new ChatSession();
            update.setId(session.getId());
            // ★ last_active_at 必须手工 set —— 自动填充只管 updated_at
            update.setLastActiveAt(OffsetDateTime.now());
            update.setMessageCount(
                    (session.getMessageCount() == null ? 0 : session.getMessageCount()) + 2);
            chatSessionService.updateById(update);
        } catch (Exception e) {
            // 会话元信息更新失败不该影响主流程 —— 问答本身已经完成了
            log.warn("更新会话活跃时间失败 sessionId={}: {}", session.getId(), e.getMessage());
        }
    }

    // ============================================================
    // 消息落库
    // ============================================================

    private void saveUserMessage(Long sessionId, String question) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(1);                 // 1 = 用户
        message.setContent(question);
        // ★ 用户消息的 token_count 留 null。
        //   不要图省事把本次请求的 prompt_tokens 塞进来 ——
        //   prompt 里含 system 提示词和历史对话，不是「这条消息的长度」。
        //   真正的用量落在 qa_log 里（那边有 prompt/completion 的细分）。
        message.setTokenCount(null);
        chatMessageService.save(message);
    }

    private void saveAssistantMessage(Long sessionId, ChatResponse response,
                                      List<RetrievedChunk> chunks, IntentClassification intent) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(2);                 // 2 = 助手
        message.setContent(response.content());
        message.setTokenCount(response.usage() == null ? null : response.usage().completionTokens());
        message.setProvider(response.descriptor().provider());
        message.setModel(response.descriptor().modelId());
        message.setLatencyMs(response.latencyMs());
        // ★ 引用了哪些切片。会话回放时靠它还原「这条回答依据了什么」
        message.setReferences(serializeReferences(chunks));
        // ★ 意图写进 chat_message（V5 就给这一列留了位置）。
        //   它是【助手消息】的属性 —— 会话回放时能看出「这一轮被判成了什么」
        message.setIntent(intentCode(intent));
        chatMessageService.save(message);
    }

    private void saveAssistantStreamMessage(Long sessionId, ModelCallTrace trace,
                                            StreamResult streamResult, String fullAnswer,
                                            List<RetrievedChunk> chunks,
                                            IntentClassification intent) {
        var route = trace.route();
        if (route == null) {
            return;                         // 理论上不会发生，防御性处理
        }
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(2);
        message.setContent(fullAnswer);
        message.setTokenCount(streamResult.usage() == null
                ? null : streamResult.usage().completionTokens());
        message.setProvider(route.provider());
        message.setModel(route.modelId());
        message.setLatencyMs(streamResult.totalMs());
        message.setReferences(serializeReferences(chunks));
        message.setIntent(intentCode(intent));
        chatMessageService.save(message);
    }

    /**
     * 把引用列表序列化成 JSON 字符串，写进 {@code references} 列。
     *
     * <p>和 {@code degradation_events} 一样：<b>一律用 Jackson，绝不手拼</b>。
     * 那是 JSONB 列，字符串里混进一个未转义的引号就会让整条记录写不进去。
     *
     * <p>空引用返回 <b>null</b> 而不是 {@code "[]"}，
     * 便于写 {@code WHERE references IS NOT NULL} 的查询。
     */
    private String serializeReferences(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> refs = buildReferences(chunks);
        if (refs == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(refs);
        } catch (Exception e) {
            log.error("references 序列化失败：{}", e.getMessage());
            return null;
        }
    }

    // ============================================================
    // qa_log 落库 ★ 项目最重要的表
    // ============================================================

    private void saveQaLogSuccess(CallContext ctx, ChatSession session, String question,
                                  ModelCallTrace trace, RetrievalTrace retrievalTrace,
                                  ChatResponse response, List<RetrievedChunk> chunks,
                                  long startNanos, IntentClassification intent) {
        QaLog log = baseLog(ctx, session, question, trace, retrievalTrace, chunks,
                startNanos, intent);
        log.setFinalAnswer(response.content());
        log.setStatus(QaLog.STATUS_SUCCESS);
        writeQaLog(log);
    }

    private void saveQaLogStreamSuccess(CallContext ctx, ChatSession session, String question,
                                        ModelCallTrace trace, RetrievalTrace retrievalTrace,
                                        StreamResult streamResult,
                                        String fullAnswer, List<RetrievedChunk> chunks,
                                        long startNanos, IntentClassification intent) {
        QaLog entity = baseLog(ctx, session, question, trace, retrievalTrace, chunks,
                startNanos, intent);
        entity.setStatus(QaLog.STATUS_SUCCESS);
        // ★ 完整回答已经由 askStream 累积好了，这里落库。
        //   阶段 7 的答案质量类指标全靠这一列。
        entity.setFinalAnswer(fullAnswer);
        writeQaLog(entity);
    }

    private void saveQaLogFailure(CallContext ctx, ChatSession session, String question,
                                  ModelCallTrace trace, RetrievalTrace retrievalTrace,
                                  List<RetrievedChunk> chunks, Exception e, long startNanos,
                                  IntentClassification intent) {
        // ★ 失败路径【也要】写检索字段。
        //   这正是 V5 里 retrieval_detail 那列注释要回答的问题：
        //   「召回失败是因为向量检索没找到，还是重排排错了，还是切分粒度不对」
        //   —— 只在成功时记，就永远回答不了它
        QaLog log = baseLog(ctx, session, question, trace, retrievalTrace, chunks,
                startNanos, intent);
        log.setStatus(QaLog.STATUS_FAILED);
        log.setErrorMsg(truncate(e.getMessage(), 1000));
        writeQaLog(log);
    }

    /**
     * 组装 qa_log 的公共字段。
     *
     * <p>成功和失败共用这段逻辑，保证「用量/成本/降级轨迹」在两条路径上
     * 都按同样的规则填写 —— 否则阶段 7 做统计时会出现
     * 「成功的有成本、失败的没成本」这种难以解释的偏差。
     */
    private QaLog baseLog(CallContext ctx, ChatSession session, String question,
                          ModelCallTrace trace, RetrievalTrace retrievalTrace,
                          List<RetrievedChunk> chunks, long startNanos,
                          IntentClassification intent) {
        QaLog log = new QaLog();
        log.setTraceId(ctx.traceId());

        // ★★ 排队两列（V9 新增）—— 【在这里填，不在各条路径上填】。
        //
        //   这是本方法存在的同一个理由：成功、失败、工具、澄清、
        //   以及未来任何一条新路径，都从这里经过。把这两行写在别处的话，
        //   每加一条路径就多一次「忘了填」的机会 —— 而漏填的症状是
        //   那一列恒为 NULL，从数据上完全看不出来。
        //   （本项目为「两条平行路径漏改一条」已经踩过 ADR-047。）
        //
        //   ⚠️ ctx.queueMs() 为 null 是【常态】而不是异常：绝大多数请求
        //      名额够用、根本没排队。写成 `queueMs == null ? 0 : queueMs`
        //      会把「平均等多久」稀释到接近 0，看起来像排队功能没生效。
        //      见 CallContext 类注释第二节。
        log.setQueueMs(ctx.queueMs());
        log.setQueuePosition(ctx.queuePosition());

        log.setSessionId(session.getId());
        log.setUserId(session.getUserId());
        log.setQuestion(question);

        // ★ trace 为 null 表示【没有发生生成调用】（澄清反问路径）——
        //   于是用量、成本、降级全部留 NULL。理由同下面 retrievalTrace 的说明：
        //   「没发生」和「发生了但没数据」必须能区分开。
        //   totalLatencyMs 不在此列 —— 它是【整次请求】的耗时，
        //   澄清请求 тоже 花了时间，那几百毫秒是真实的
        if (trace != null) {
            var route = trace.route();
            if (route != null) {
                log.setProvider(route.provider());
                log.setModel(route.modelId());
            }

            var usage = trace.usage();
            if (usage != null) {
                log.setPromptTokens(usage.promptTokens());
                log.setCompletionTokens(usage.completionTokens());
                log.setTotalTokens(usage.totalTokens());
            }

            log.setCost(trace.cost());
            log.setLlmLatencyMs(trace.llmLatencyMs());
            log.setDegradationEvents(serializeEvents(trace));
        }
        log.setTotalLatencyMs(elapsedMs(startNanos));

        // ── ★ 检索相关字段（阶段 4 新增，此前一直为空）──
        //
        // retrieval_latency_ms 是【整条检索链路的耗时】，
        // rerank_latency_ms 是其中【重排那一段】的耗时 ——
        // 两者是包含关系，不是并列关系。这样拆是为了回答
        // 「P95 变慢了，慢在召回还是慢在重排」。
        //
        // ★★ {@code retrievalTrace} 为 {@code null} 表示
        //   【这一次根本没有检索】（澄清反问路径）。此时几个检索列
        //   <b>全部写 NULL</b>。
        //
        //   ⚠️ 【不能】传一个空的 RetrievalTrace 进来 ——
        //   那样序列化出来是 {@code {"vector_hits":[],"keyword_hits":[],...}}，
        //   和「检索跑了、两路都没召回任何东西」<b>逐字相同</b>。
        //   而这两件事的排查方向完全相反：
        //     前者说明链路没走到检索（看 intent 和 status 就知道）
        //     后者说明召回有问题（要去查切分、分词、TopK）
        //   同 {@code references} 那条约定：没有就是 NULL，不是空数组。
        if (retrievalTrace != null) {
            log.setRetrievalLatencyMs(retrievalTrace.retrievalLatencyMs() == 0
                    ? null : retrievalTrace.retrievalLatencyMs());
            log.setRerankLatencyMs(retrievalTrace.rerankLatencyMs() == 0
                    ? null : retrievalTrace.rerankLatencyMs());
            log.setRetrievalDetail(serializeRetrievalDetail(retrievalTrace));
            log.setReferences(serializeReferences(chunks));

            // ★ 没开重写时这里必须是 null，【不能填原问题】——
            //   否则阶段 7 分不清「没开重写」和「开了但模型没改动」，
            //   而那是两个完全不同的实验条件。同 ADR-010 的 NULL 原则
            log.setRewrittenQuestion(retrievalTrace.rewrittenQuestion());
        }

        // ── ★ 意图（阶段 5.3 新增）──
        //
        // ★★ 没开意图识别、或分类失败时，这一列写 NULL ——【不能】填
        //    「UNKNOWN」之类的占位串。否则阶段 7 分不清
        //    「没开」和「开了但没分出来」，而那是两个完全不同的实验条件。
        //    同上面 rewritten_question 的理由，也同 ADR-010 的 NULL 原则。
        //
        // ⚠️ 不写 intent_confidence：**实测证明它和「该不该澄清」不相关**
        //    （详见 ClarificationDecider 的类注释），存一个没校准的数字
        //    早晚会被当成阈值用。留着 NULL 比填一个反相关的值好。
        log.setIntent(intentCode(intent));
        return log;
    }

    /**
     * 把检索轨迹序列化成 {@code retrieval_detail}。
     *
     * <p>和 {@link #serializeEvents} 完全同构：Jackson 序列化、
     * 空的时候返回 {@code null}（不是 {@code "{}"}）、失败只记 ERROR 不抛。
     *
     * <p>★ 这里调的是 {@link RetrievalDetailBuilder}，
     * <b>和调试接口 {@code /api/debug/kb/retrieve} 是同一个方法</b> ——
     * 于是「验收标准 1 看到的中间输出」和「线上真正落库的内容」在物理上
     * 就是同一份，永远不会漂移。
     */
    private String serializeRetrievalDetail(RetrievalTrace retrievalTrace) {
        Map<String, Object> detail = retrievalDetailBuilder.build(retrievalTrace);
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            log.error("retrieval_detail 序列化失败（不影响主流程）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把降级事件序列化成 JSON 字符串。
     *
     * <p>★★ <b>必须用 Jackson 序列化，绝不手拼字符串。</b>
     * {@code degradation_events} 是 JSONB 列，PostgreSQL 会做严格的 JSON 解析 ——
     * 手拼时只要 {@code reason} 或消息里混进一个引号，就会直接报
     * {@code invalid input syntax for type json}，整条 qa_log 写不进去。
     * 而错误消息里带引号是常态。
     *
     * <p>没有降级时写 {@code null} 而不是 {@code "[]"} ——
     * 这样运维查询可以写成 {@code WHERE degradation_events IS NOT NULL}
     * 一眼筛出所有降级过的请求。
     */
    private String serializeEvents(ModelCallTrace trace) {
        List<?> events = trace.events();
        if (events.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            log.error("降级事件序列化失败（不影响主流程）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写 qa_log。
     *
     * <p>★ 这里只记日志、<b>不往外抛</b>：qa_log 写失败不该让用户拿不到
     * 已经生成好的回答。但必须打成 ERROR 级别 ——
     * 它是评测的唯一数据来源，静默丢失意味着阶段 7 平白少了样本。
     */
    private void writeQaLog(QaLog entity) {
        try {
            qaLogService.save(entity);
        } catch (Exception e) {
            log.error("★ qa_log 写入失败，评测数据丢失 traceId={}: {}",
                    entity.getTraceId(), e.getMessage(), e);
        }
    }

    // ============================================================
    // 响应组装
    // ============================================================

    private ChatAskResponse buildResponse(String traceId, ChatSession session,
                                          ModelCallTrace trace, ChatResponse response,
                                          List<RetrievedChunk> chunks, long startNanos,
                                          IntentClassification intent) {
        var route = response.descriptor();
        return new ChatAskResponse(
                traceId,
                session.getSessionNo(),
                response.content(),
                route.provider(),
                route.modelId(),
                response.usage(),
                trace.cost(),
                response.latencyMs(),
                elapsedMs(startNanos),
                trace.degraded(),
                trace.events(),
                buildReferences(chunks),
                intentCode(intent));
    }

    /** 意图 code，没分类时为 {@code null}。★ 不填占位串，理由见 {@code ChatAskResponse.intent} */
    private static String intentCode(IntentClassification intent) {
        return intent == null ? null : intent.code();
    }

    private ChatAskResponse buildStreamResponse(String traceId, ChatSession session,
                                                ModelCallTrace trace, StreamResult streamResult,
                                                List<RetrievedChunk> chunks, long startNanos,
                                                IntentClassification intent) {
        var route = trace.route();
        return new ChatAskResponse(
                traceId,
                session.getSessionNo(),
                null,                           // 流式的正文已经分批推走了，这里不再重复返回
                route == null ? null : route.provider(),
                route == null ? null : route.modelId(),
                streamResult.usage(),
                trace.cost(),
                streamResult.totalMs(),
                elapsedMs(startNanos),
                trace.degraded(),
                trace.events(),
                buildReferences(chunks),
                intentCode(intent));
    }

    // ============================================================
    // 工具
    // ============================================================

    private static String systemPromptOrDefault(String custom) {
        return (custom == null || custom.isBlank()) ? DEFAULT_SYSTEM_PROMPT : custom;
    }

    private static String truncateTitle(String question) {
        if (question == null) {
            return "";
        }
        String q = question.strip();
        return q.length() <= TITLE_MAX_LENGTH ? q : q.substring(0, TITLE_MAX_LENGTH);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 给用户看的错误说明 —— 不暴露内部细节，但带上可查证的线索 */
    private static String userFacingMessage(Exception e) {
        return "抱歉，智能助手暂时不可用，请稍后重试。";
    }

    private static int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }

    /**
     * 上下文缺失或 traceId 为空时，回落到一个「没排队」的新上下文。
     *
     * <p>★ <b>不抛异常</b>：它是观测数据，不该因为调用方忘了传
     * 而让用户的问答失败。这个取舍和「检索失败不影响问答」是同一条 ——
     * <b>旁路信息缺失时，主流程该继续跑，而不是停下来。</b>
     *
     * <p>⚠️ 回落出来的上下文里排队两列是 <b>null 而不是 0</b>：
     * 我们确实不知道它排没排过队，而「不知道」和「没排队」在这里
     * 恰好是同一个意思 —— 两者都不该被算进「平均等了多久」。
     */
    private static CallContext ctxOrFresh(CallContext ctx) {
        if (ctx == null || ctx.traceId() == null || ctx.traceId().isBlank()) {
            return CallContext.fresh();
        }
        return ctx;
    }
}
