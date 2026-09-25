package com.xbla.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.agent.intent.ClarificationDecider;
import com.xbla.rag.agent.intent.IntentClassification;
import com.xbla.rag.agent.intent.IntentPlan;
import com.xbla.rag.agent.intent.IntentTree;
import com.xbla.rag.agent.intent.LlmIntentClassifier;
import com.xbla.rag.agent.intent.PendingClarify;
import com.xbla.rag.agent.intent.RetrievalGate;
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
import com.xbla.rag.service.AppUserService;
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

    /**
     * 检索门控（阶段 9.2）—— <b>「这一次要不要检索」的唯一判断点</b>。
     *
     * <p>★ 它同时接管了阶段 5.8 那个 {@code isToolIntent}：工具分支也是它的一种结论。
     * 把两者合成一个的原因不是「少写一个方法」，是<b>它们本来就是同一个判断</b>
     * （「这次的数据从哪来：知识库、实时工具、还是都不要」），
     * 拆成两处会让「工具类意图要不要检索」这个问题有两个答案。
     */
    private final RetrievalGate retrievalGate;

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
     * <p>★ 阶段 5.8 时它<b>只被 {@code ask()} 用到</b>；阶段 9 起
     * {@code askStream()} 也接上了（{@code answerStreamWithTools}）。
     * 在那之前，SSE 上的工具意图是<b>静默降级</b>成普通 KB 问答的 ——
     * 而前端只用流式，所以那条边界实际是「工具功能对用户不可见」。
     */
    private final ToolLoop toolLoop;

    /**
     * 用户（阶段 9 新增）。
     *
     * <p>★ 只有一个用途：{@code resolveSession} 在把身份写进
     * {@code chat_session.user_id} 之前<b>核对这个人真的存在</b>。
     *
     * <p>★★ 这一步不是「多此一举的校验」，是 FK 逼出来的：
     * {@code chat_session.user_id} 上有 {@code FOREIGN KEY → app_user(id)}
     * （V5 迁移）。而 {@code X-Xbla-User-Id} 是<b>明文未签名的</b>（ADR-054），
     * 所以任何一个客户端发一个 {@code X-Xbla-User-Id: 999} 都会让
     * <b>每一次对话在建会话那一步 500</b>。
     * 查不到就如实记 NULL（= 匿名），这才是与「它不是认证」相符的处置。
     */
    private final AppUserService appUserService;

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
    private IntentClassification classifySafely(String question, PendingClarify pending) {
        if (!agentProperties.getIntent().isEnabled()) {
            return null;
        }
        try {
            return intentClassifier.classify(question, pending);
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
     * 同 {@link RetrievalGate} 那条「不要把 YAML 里的知识挪进 Java」。
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
        ctx = convergeIdentity(userId, ctxOrFresh(ctx));
        String traceId = ctx.traceId();

        ChatSession session = resolveSession(request.sessionNo(), request.question(), userId);

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

        // ★★ 阶段 9.4：把上一轮悬着的澄清读出来（并立刻清空）。
        //
        //   位置在【分类之前】—— 它就是分类的输入之一
        //   （「上一轮我问了他用途，这句『送长辈』很可能是在回答它」）。
        //
        //   ★ 读后即清（consumePendingClarify 里做的）：状态是一次性的。
        //     这样它不会悬过一轮又一轮 —— 用户换了话题时，窗口最多只有一轮。
        PendingClarify pending = consumePendingClarify(session);
        if (pending != null) {
            ctx = ctx.withClarifyResumed();
        }

        // ★★ 意图识别插在【用户消息落库之后、检索之前】。
        //
        //   为什么必须在检索【之前】：澄清要短路掉整条链路 ——
        //   等检索跑完再决定澄清，那几百毫秒和向量化的钱就白花了，
        //   而且用户得到的仍然是那句反问，不会因为检索过而更好。
        //
        //   （★ askStream 里插在 sink.onStart 【之后】，理由见那个方法。）
        IntentClassification intent = classifySafely(request.question(), pending);
        ClarificationDecider.Decision decision =
                clarificationDecider.decide(request.question(), intent);
        if (decision.shouldClarify()) {
            return answerWithClarification(ctx, session, request.question(),
                    intent, decision, startNanos);
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
        // ★★ 检索门控（阶段 9.2）—— 判断收在 RetrievalGate 一处，这里只读结论。
        //    见那个类的注释：它有【三条路】要用（ask / askStream / 调试探针），
        //    而漏改一条的症状不是报错，是「那条路上的问题答得更差」。
        //    ★ 它同时替代了 9.1 之前那个 isToolIntent —— 工具分支也是它的一种结论，
        //      所以这里【只算一次】，不是「先问一次要不要工具、再问一次要不要检索」。
        RetrievalGate.Decision gate = retrievalGate.decide(intent);

        // ★ 检索插在【用户消息落库之后】。顺序是有意的：
        //   先落用户消息，检索失败时问题仍然在库里，评测数据是完整的。
        // ★ 意图定向检索（5.4）：把分类结果翻译成 doc_type 范围。
        //   注意这只是【声明】—— 要不要真的下推由 RetrievalPipeline 决定
        //   （池子太小它会拒绝、过滤后一无所获它会回落）。理由见那个类的注释第四节，
        //   核心是：调试探针和线上必须走同一套规则，所以判断只能有一处。
        //
        // ★★ 「不检索」的那条路（阶段 9.2）：retrievalTrace 保持 null、
        //    chunks 用【空列表】。传 null 的 trace 会让 qa_log 那几个检索列
        //    全写 NULL —— 那正是「检索确实没发生」的诚实表达（ADR-041）。
        //    ⚠️ 千万不能传一个空的 RetrievalTrace：序列化出来和
        //    「检索跑了但两路都没召回」逐字相同，而那两件事的排查方向完全相反。
        RetrievalTrace retrievalTrace = gate.shouldRetrieve() ? new RetrievalTrace(traceId) : null;
        List<RetrievedChunk> chunks = gate.shouldRetrieve()
                ? retrieveSafely(request.question(), retrievalOptions(intent), retrievalTrace)
                : List.of();

        // ★★ 工具路的两个形态（阶段 9.3）：
        //
        //     纯工具轮   gate=TOOLS，没检索，chunks 是空列表
        //     混合轮     gate=RETRIEVE 且这个叶子挂了工具，切片【已经检索好了】
        //
        //   ★ 判据是 gate.hasTools()，【不是】path == TOOLS。
        //     判 path 会让混合轮静默走成纯知识库问答 —— 模型手上一件工具都没有，
        //     而回答读起来完全正常，没有任何指标会红。
        //   ★ 位置必须在检索【之后】：混合轮要把 chunks 一起带进 system prompt。
        if (gate.hasTools()) {
            return answerWithTools(ctx, session, request, memory, intent, userId,
                    gate.tools(), retrievalTrace, chunks, startNanos);
        }

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
            log.info("问答完成 {} | {} | {}", trace.summary(),
                    retrievalSummary(retrievalTrace, gate), memory.summary());
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
    // ★ 工具路径（阶段 5.8；阶段 9.1 接进流式）
    // ============================================================

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
                                            List<String> tools,
                                            RetrievalTrace retrievalTrace,
                                            List<RetrievedChunk> chunks,
                                            long startNanos) {
        String traceId = ctx.traceId();

        ModelCallTrace trace = new ModelCallTrace(traceId);

        // ★ system prompt 和知识库路径用【同一套】组装逻辑。
        //   ★ 9.3 起这不是「可以复用」而是「必须复用」：混合轮走的就是这条路，
        //     而它手上确实有 chunks。纯工具轮传空列表，此时 RagPromptBuilder
        //     不会写「以下是相关资料：（空）」，而是换成一句
        //     「本次未检索到知识库内容」—— 对纯工具轮来说这句话是对的。
        //
        // ★★ 结构化事实传 structuredFactsSafely(intent)，【不再写死 EMPTY】。
        //   9.2 之前这里写死 EMPTY，推理是「走这条路的意图都不检索，而加载期
        //   强制非 KB 的叶子不能声明 structured_facts」。
        //   9.3 加了混合轮之后那个推理断了：混合轮走的就是这条路，
        //   而它的叶子【是】KB 叶子，合法地可以声明 structured_facts。
        //   ★ 改回来不引入任何变化：TOOL 类意图传进来的是顶层码，
        //     而 structuredFactOf 只查叶子 —— 返回 NONE。
        //     也就是纯工具轮的结果和写死 EMPTY 逐字相同。
        //     那条不变式现在由【意图树】保证，不再由这一行代码保证 ——
        //     这正是它该待的地方。
        String systemPrompt = ragSystemPrompt(request.systemPrompt(), chunks, memory,
                structuredFactsSafely(intent));

        try {
            ToolLoop.Result result = toolLoop.run(
                    new ToolLoop.Input(systemPrompt, memory.history(), request.question(),
                            userId, tools, !chunks.isEmpty()),
                    trace);

            saveAssistantToolAnswer(session.getId(), result, intent);
            saveQaLogTool(ctx, session, request.question(), trace, result,
                    retrievalTrace, chunks, startNanos, intent);
            touchSession(session);

            // ★ 5.6 的摘要压缩照常触发 —— 工具回答也是会话的一部分，
            //   不压的话这段历史会一直占着窗口。
            //   ⚠️ 和知识库路径一样，必须放在助手消息落库【之后】
            sessionSummarizer.maybeSummarizeAsync(session.getId());

            log.info("工具问答完成 {} | {} 轮 | 调用 {} 次 | 本次可用工具 {} 个 | intent={}",
                    trace.summary(), result.rounds(), result.calls().size(),
                    tools.size(), intentCode(intent));

            return buildToolResponse(traceId, session, trace, result, chunks, startNanos, intent);

        } catch (Exception e) {
            // ★ 只有模型链路失败才会到这里。
            //   ★ 9.3：检索那两列【原样传下去】而不是写死 null —— 混合轮确实检索过，
            //     写死 null 会让「这次到底有没有检索」在库里变成一句假话，
            //     而那正是 9.2 那条一致性判据要读的两列
            saveQaLogFailure(ctx, session, request.question(), trace, retrievalTrace, chunks,
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
     * <p>和 {@link #saveQaLogSuccess} 的区别是它多填了一列 {@code tool_calls}。
     *
     * <p>★★ <b>检索那几列从 9.3 起【不再写死 null】。</b>
     * 这条路径现在有两个形态，而它们在库里的形状<b>必须不同</b>：
     *
     * <pre>
     *   纯工具轮  没检索过  → retrieval_detail IS NULL       （ADR-041 那条老语义）
     *   混合轮    检索过    → retrieval_detail IS NOT NULL   ★ 9.3 新出现的形状
     * </pre>
     *
     * <p>写死 null 会让混合轮在库里长得和纯工具轮一模一样 ——
     * 「这次到底有没有检索」这句话就再也问不出来了，
     * 而它正是 9.2 那条一致性判据（{@code retrieve=false ∧ retrieval_detail
     * IS NOT NULL 必须为 0}）要读的列。
     *
     * <p>★ 于是混合轮在库里是<b>第一行同时有检索字段和 tool_calls 的数据</b>。
     * 这不是巧合，是它的定义。
     */
    private void saveQaLogTool(CallContext ctx, ChatSession session, String question,
                               ModelCallTrace trace, ToolLoop.Result result,
                               RetrievalTrace retrievalTrace, List<RetrievedChunk> chunks,
                               long startNanos, IntentClassification intent) {
        QaLog log = baseLog(ctx, session, question, trace, retrievalTrace, chunks,
                startNanos, intent);
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
                                              List<RetrievedChunk> chunks,
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
                // ★★ 阶段 9.3：这里原来写死 null（「工具轮没检索」）——
                //   那个推理在【混合轮】上断了：混合轮真的检索过，
                //   而且模型会照着 prompt 里的编号在正文里写 [1][2]。
                //   写死 null 的话，前端「引用」那一栏是空的，
                //   而回答里却挂着指向不存在的编号。
                //   ★ 纯工具轮传进来的是空列表 → buildReferences 返回 null，
                //     和以前【逐字相同】，没有回归
                buildReferences(chunks),
                intentCode(intent));
    }

    /**
     * 工具路径的流式版本（阶段 9）。
     *
     * <p>它是 {@code answerWithTools} 的孪生体 —— 流程、落库、异常处理都一样，
     * <b>只有「正文怎么交给用户」不同</b>（那里是返回，这里是推事件）。
     * 之所以不把两者合并成一个「接受一个可选的 sink」的方法：
     * 那样每条路径里都会多出 {@code sink != null} 的分支，
     * 而「哪条路该做什么」会变成运行时的判断 —— 这里它是编译期的事实。
     *
     * <h3>★ 正文是一次性推的，没有打字机效果 —— 这是有意的边界</h3>
     *
     * <p>因为这条路上的<b>每一轮都走 {@code router.chat}（非流式）</b>：
     * 工具决策轮的正文本来就是空串（见 {@code WireChatResponse.hasAnyContent}），
     * 真正的正文只在最后一轮产生。要让它逐字推，得先扩展
     * {@code WireStreamChunk} 支持 {@code tool_calls} 增量装配 ——
     * 而那是 {@code client/} 里按 ADR-061 已经坏过两次的地方，值得单开一步。
     *
     * <p>★★ 但<b>「答对」和「有打字机」是两件事，这一版解决的是前者</b>：
     * 在此之前，SSE 上的工具意图走的是普通 KB 问答 ——
     * 用户问「我的订单到哪了」，模型拿通用发货规则<b>编一个订单状态</b>。
     * 那是一个错误答案；而现在它是一个没有打字机的正确答案。
     */
    private void answerStreamWithTools(CallContext ctx, ChatSession session,
                                       ChatAskRequest request, MemoryContext memory,
                                       IntentClassification intent, Long userId,
                                       List<String> tools,
                                       RetrievalTrace retrievalTrace,
                                       List<RetrievedChunk> chunks,
                                       ChatStreamSink sink, long startNanos) {
        String traceId = ctx.traceId();
        ModelCallTrace trace = new ModelCallTrace(traceId);

        // ★ 和 answerWithTools 逐字同构，包括 chunks 和结构化事实的处理 ——
        //   混合轮在流式路径上同样要看得见切片与硬数据。
        //   ⚠️ 这两处是平行代码，本项目的教训是「只改了一条」编译能过、测试能绿
        String systemPrompt = ragSystemPrompt(request.systemPrompt(), chunks, memory,
                structuredFactsSafely(intent));

        try {
            ToolLoop.Result result = toolLoop.run(
                    new ToolLoop.Input(systemPrompt, memory.history(), request.question(),
                            userId, tools, !chunks.isEmpty()),
                    trace);

            saveAssistantToolAnswer(session.getId(), result, intent);
            saveQaLogTool(ctx, session, request.question(), trace, result,
                    retrievalTrace, chunks, startNanos, intent);
            touchSession(session);

            // ★ 正文一次推完。★ 空串也要推吗 —— 不。result.answer() 理论上不会是空
            //   （ToolLoop 的每一轮都要求 hasAnyContent），但真为空时推一个空 delta
            //   等于让前端渲染一个空气泡；跳过它，让 done 事件自己说明。
            if (result.answer() != null && !result.answer().isEmpty()) {
                sink.onDelta(result.answer());
            }

            sink.onComplete(buildStreamToolResponse(traceId, session, trace, result, chunks,
                    startNanos, intent));

            // ★ 同 askStream：压缩必须在助手消息落库【之后】
            sessionSummarizer.maybeSummarizeAsync(session.getId());

            log.info("流式工具问答完成 {} | {} 轮 | 调用 {} 次 | 本次可用工具 {} 个 | intent={}",
                    trace.summary(), result.rounds(), result.calls().size(),
                    tools.size(), intentCode(intent));

        } catch (Exception e) {
            // ★ 只有模型链路失败才会到这里（工具的任何失败都已被 ToolLoop 转成工具结果）
            //   ★ 检索那两列原样传下去 —— 同 answerWithTools，混合轮确实检索过
            saveQaLogFailure(ctx, session, request.question(), trace, retrievalTrace, chunks,
                    e, startNanos, intent);
            touchSession(session);
            log.warn("流式工具问答失败 traceId={} : {}", traceId, e.getMessage());
            sink.onError(userFacingMessage(e), traceId);
        }
    }

    /**
     * 工具路径的流式响应体 —— 和 {@link #buildToolResponse} 的唯一区别是
     * <b>{@code answer} 为 {@code null}</b>。
     *
     * <h3>★★ 为什么这不是多余的洁癖</h3>
     *
     * <p>{@code ChatAskResponse.answer} 这个字段在两条路上是两个含义：
     *
     * <pre>
     *   非流式        → 正文
     *   流式          → 刻意的 null（正文只在 delta 事件里）
     * </pre>
     *
     * <p>前端在 {@code done} 到达时做的事是「收尾」，而历史上有过
     * 「收到 done 就 {@code content = payload.answer}」的写法 ——
     * 那会在流式的每一条回答上<b>把刚刚逐字渲染出来的正文整条抹掉</b>。
     * 所以流式契约里 {@code answer} 必须是 null。
     *
     * <p>如果这里偷懒复用 {@code buildToolResponse}，这个字段就会变成
     * <b>三种</b>含义（非流式 / 流式 / 流式工具），而第三个含义
     * 「流式工具轮它<b>有</b>正文」正好会把上面那个规则戳出一个洞 ——
     * 前端要么为它写一个特例，要么在工具轮上把正文渲染两遍。
     *
     * <p>★ 一句话：<b>流式契约是传输层的契约，工具轮也是流式轮。</b>
     */
    private ChatAskResponse buildStreamToolResponse(String traceId, ChatSession session,
                                                    ModelCallTrace trace, ToolLoop.Result result,
                                                    List<RetrievedChunk> chunks,
                                                    long startNanos, IntentClassification intent) {
        ChatResponse response = asResponse(result.answer(), trace);
        var route = trace.route();
        return new ChatAskResponse(
                traceId,
                session.getSessionNo(),
                null,                       // ★★ 正文已经推走了 —— 同 buildStreamResponse
                route == null ? null : route.provider(),
                route == null ? null : route.modelId(),
                response.usage(),
                trace.cost(),
                trace.llmLatencyMs(),
                elapsedMs(startNanos),
                trace.degraded(),
                trace.events(),
                // ★ 同 buildToolResponse：混合轮有引用，纯工具轮是 null
                buildReferences(chunks),
                intentCode(intent));
    }

    // ============================================================
    // 流式
    // ============================================================

    @Override
    public void askStream(ChatAskRequest request, ChatStreamSink sink) {
        // ★ 两参版本 = 「没有身份」那条路（测试、探针）。线上不走这里 ——
        //   见了 userId 为 null，工具路径会回一句「需要先知道你是哪位」。
        askStream(request, sink, null, CallContext.fresh());
    }

    /**
     * 流式问答（阶段 6：带调用上下文；阶段 9：带身份）。
     *
     * <p>★ 排队层在主调这一个是<b>刻意的</b>：
     * traceId 在排队开始的那一刻就产生了，一路用到 {@code qa_log.trace_id}。
     * 见 {@code ChatService#askStream(ChatAskRequest, ChatStreamSink, Long, CallContext)} 的说明。
     */
    @Override
    public void askStream(ChatAskRequest request, ChatStreamSink sink, Long userId, CallContext ctx) {
        long startNanos = System.nanoTime();
        ctx = convergeIdentity(userId, ctxOrFresh(ctx));
        String traceId = ctx.traceId();

        ChatSession session = resolveSession(request.sessionNo(), request.question(), userId);

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

        // ★★ 阶段 9.4：上一轮悬着的澄清状态（读后即清）——
        //   与 ask() 同序（分类之前），只是被 onStart 挤到了它后面。
        //   ⚠️ 它是一次主键 UPDATE + 一次内存赋值，不是模型调用，
        //     所以放在 onStart 之后不会抵消 onStart 的价值（见下面那段注释）。
        PendingClarify pending = consumePendingClarify(session);
        if (pending != null) {
            ctx = ctx.withClarifyResumed();
        }

        // ★★ 意图识别也插在 sink.onStart 【之后】，理由和检索完全一样 ——
        //   一次分类是 0.5~2.5 秒的模型往返（走完整降级链，P0 是推理模型），
        //   插在 onStart 前面会把这段时间原封不动地加在用户看到任何反馈之前，
        //   【正好抵消掉 onStart 的全部价值】。
        IntentClassification intent = classifySafely(request.question(), pending);
        ClarificationDecider.Decision decision =
                clarificationDecider.decide(request.question(), intent);
        if (decision.shouldClarify()) {
            answerStreamWithClarification(ctx, session, request.question(),
                    intent, decision, sink, startNanos);
            return;
        }

        // ★★ 工具分支（阶段 9）—— 与非流式路径【同序、同判据】：
        //   澄清之后、检索之前。那三条位置理由见 ask() 里对应位置的长注释，
        //   这里不重复；要点是「工具意图必须短路掉检索」——
        //   否则模型会拿通用规则编一个具体的订单状态出来（ADR-044）。
        //
        //   ⚠️⚠️ 在此之前，这条路上的工具意图是【静默降级】的：
        //   askStream 没有 userId、不挂 tools，于是它走成一次普通的 KB 问答，
        //   而 qa_log 里 intent=ORDER_STATUS、status=1，看起来完全正常。
        //   这是阶段 5.8 划下的边界（当时只把工具接进非流式），
        //   而前端只用流式 —— 所以线上从来没有一条工具问答走到过用户面前。
        // ★★ 检索门控（阶段 9.2）—— 与 ask() 同序、同判据，只算一次
        RetrievalGate.Decision gate = retrievalGate.decide(intent);

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
        // ⚠️⚠️ 下面这一段和 ask() 里那一份是【平行代码】，而本项目的教训是
        //    「只改了一条」编译能过、测试能绿（ADR-047；9.1 修的正是它的一个实例）。
        //    防线是「判断收在 RetrievalGate 一处」—— 这里只读结论，不复现逻辑。
        RetrievalTrace retrievalTrace = gate.shouldRetrieve() ? new RetrievalTrace(traceId) : null;
        List<RetrievedChunk> chunks = gate.shouldRetrieve()
                ? retrieveSafely(request.question(), retrievalOptions(intent), retrievalTrace)
                : List.of();

        // ★★ 工具路 —— 和 ask() 里那一份【逐字同构】，包括判据和位置。
        //    ⚠️ 别在这里复现门控逻辑，也别改判据：两条流式/非流式路径
        //    「只改了一条」是本项目反复出现的失败形态（ADR-047 / 9.1 / 坑 35）。
        if (gate.hasTools()) {
            answerStreamWithTools(ctx, session, request, memory, intent, userId,
                    gate.tools(), retrievalTrace, chunks, sink, startNanos);
            return;
        }

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

            log.info("流式问答完成 {} | {} | {}", trace.summary(),
                    retrievalSummary(retrievalTrace, gate), memory.summary());

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
                                                    ClarificationDecider.Decision decision,
                                                    long startNanos) {
        String traceId = ctx.traceId();
        String clarifyText = decision.clarifyText();
        saveAssistantClarification(session.getId(), clarifyText, intent);
        rememberPendingClarify(session, decision.pending());
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
                                               IntentClassification intent,
                                               ClarificationDecider.Decision decision,
                                               ChatStreamSink sink, long startNanos) {
        String traceId = ctx.traceId();
        String clarifyText = decision.clarifyText();
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
            // ★ 与 ask() 那条路同序、同判据（两条是平行代码）
            rememberPendingClarify(session, decision.pending());
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
     *
     * <h3>★★ 阶段 9：会话的归属（{@code chat_session.user_id}）</h3>
     *
     * <p>在此之前这一列<b>恒为 NULL</b>（写着「阶段 2 还没有登录体系」）。
     * 阶段 9 起它记的是<b>会话归属</b>，规则是 <b>write-once</b>：
     *
     * <pre>
     *   新建 + 身份有效       →  记下它
     *   新建 + 身份无效/缺席   →  NULL（匿名是合法的，不是失败）
     *   已有 + 该列是 NULL     →  认领（条件 UPDATE，并发安全、幂等）
     *   已有 + 已是别人的      →  【不覆盖】，打 WARN
     * </pre>
     *
     * <p>★ 最后一条是关键：覆盖会让一个会话的历史归属<b>随最后一个请求漂移</b>，
     * 而那种漂移没有任何日志能看出来。同 ADR-055「会话 ID 与身份绑定」的判据 ——
     * 先声明的赢。
     *
     * <p>⚠️⚠️ <b>它和 {@code qa_log.user_id} 是两个不同粒度的东西，不要合并：</b>
     *
     * <pre>
     *   chat_session.user_id   会话粒度，write-once，回答「这个会话是谁开的」
     *   qa_log.user_id         请求粒度，逐条写，回答「这一问是谁提的」
     * </pre>
     *
     * <p>客户端在同一个 sessionNo 上换了头时，两者会不同 ——
     * 那是两个问题的两个答案，不是数据不一致。{@code qa_log} 取的是
     * {@code ctx.userId()}（和工具那条路同源），理由见 {@code baseLog}。
     */
    private ChatSession resolveSession(String sessionNo, String question, Long userId) {
        // ★ 先把身份核对成「真的存在的人」，不存在就是 null（匿名）。
        //   不做这一步的话，一个伪造的 X-Xbla-User-Id 会让【每一次】对话
        //   在建会话那一步撞 FK 约束 → 500。见本类 appUserService 字段的说明。
        Long owner = verifiedUserId(userId);

        if (sessionNo != null && !sessionNo.isBlank()) {
            ChatSession existing = chatSessionService.lambdaQuery()
                    .eq(ChatSession::getSessionNo, sessionNo)
                    .one();
            if (existing != null) {
                claimSession(existing, owner);
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
        session.setUserId(owner);
        session.setTitle(truncateTitle(question));
        session.setMessageCount(0);
        session.setStatus(1);               // 1 = 进行中
        session.setLastActiveAt(OffsetDateTime.now());
        chatSessionService.save(session);

        log.debug("新建会话 id={} sessionNo={} 归属={}",
                session.getId(), session.getSessionNo(),
                owner == null ? "匿名" : "user#" + owner);
        return session;
    }

    /**
     * 身份核对 —— 查得到才认，查不到就是匿名。
     *
     * <p>★ 成本是每次问答一次主键查询。它换来的是「伪造的头不会让服务 500」，
     * 而那是 FK 约束逼出来的必答题（见本类 {@code appUserService} 字段的说明）。
     *
     * <p>⚠️ 查不到时写 <b>NULL 而不是抛异常</b>：{@code X-Xbla-User-Id}
     * 不是认证，一个不存在的 id 和一个没带头在语义上没有区别 ——
     * 都是「我们不知道你是谁」。工具那条路也照旧会拿请求头里的值去查，
     * 查不到就照它自己的既有逻辑回一句实话。
     */
    private Long verifiedUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        boolean exists = appUserService.getById(userId) != null;
        if (!exists) {
            log.debug("身份 user#{} 在 app_user 里不存在，本次按匿名处理", userId);
            return null;
        }
        return userId;
    }

    /**
     * 认领一个还没有归属的会话；已经有归属的一律不动。
     *
     * <p>★ 认领用条件 UPDATE（{@code ... AND user_id IS NULL}）而不是
     * 先读后写：两个请求同时认领同一个会话时，先读后写会两个都以为自己赢了，
     * 而条件 UPDATE 的第二个会更新 0 行、静默让位。<b>幂等且并发安全。</b>
     */
    private void claimSession(ChatSession existing, Long owner) {
        Long current = existing.getUserId();
        if (current != null) {
            if (owner != null && !current.equals(owner)) {
                // ★ 这是「改绑」意图 —— 不覆盖，只报告。见方法注释上面的规则表
                log.warn("★ 会话 id={} 已归属 user#{}，本次请求身份是 user#{} —— 不覆盖",
                        existing.getId(), current, owner);
            }
            return;
        }
        if (owner == null) {
            return;                     // 没身份可认领，保持匿名
        }
        try {
            boolean claimed = chatSessionService.lambdaUpdate()
                    .eq(ChatSession::getId, existing.getId())
                    .isNull(ChatSession::getUserId)
                    .set(ChatSession::getUserId, owner)
                    .update();
            if (claimed) {
                existing.setUserId(owner);      // 让内存里的对象和库里一致
                log.debug("会话 id={} 被 user#{} 认领", existing.getId(), owner);
            }
        } catch (Exception e) {
            // ★ 认领失败不该让问答失败 —— 它只是一条归属信息，
            //   而且失败时那一列仍然是 NULL，是「不知道」的诚实表达
            log.warn("会话 id={} 认领失败：{}", existing.getId(), e.getMessage());
        }
    }

    /**
     * 读出并清空上一轮悬着的澄清状态（阶段 9.4）。
     *
     * <h3>★★ 为什么是「读后即清」而不是「用完再清」</h3>
     *
     * <p>「用完再清」看起来更精确（这一轮真的用上了才清），但它有两个坏处：
     *
     * <pre>
     *   ① 中途抛异常时状态残留 → 下一轮把它当成「刚问过的」再注入一次 ——
     *      而那一轮用户早就换了话题
     *   ② 「用上了」需要一个判据，而那个判据只能在分类【之后】才知道 ——
     *      于是清空点散落在 4~5 处出口上（成功/失败/澄清/工具/异常），
     *      漏一处就是永久残留（ADR-049 那个形态：一个坏状态永久卡住会话）
     * </pre>
     *
     * <p>读后即清把窗口压到<b>恰好一轮</b>：状态要么被这一轮用掉，要么消失。
     * 代价是「这一轮分类失败」时它也没了 —— 那可以接受，
     * 因为用户重问一次就是了，而残留的代价是<b>每一轮都被误导</b>。
     *
     * <h3>★ 坏数据也要清</h3>
     *
     * <p>JSON 读不出来时<b>照样清空</b>：留着它会让每一轮都重新解析一遍、
     * 重新 WARN 一遍，而日志里那条 WARN 会淹没在正常的噪音里。
     */
    private PendingClarify consumePendingClarify(ChatSession session) {
        String raw = session.getPendingClarify();
        if (raw == null || raw.isBlank()) {
            return null;            // 常态：上一轮不是澄清
        }

        // ★★ 开关关着时：把残留【清掉】，但【不注入】也不标 resumed。
        //
        //   「关掉 = 与 9.3 逐字节相同」这句话必须连 `intent_plan.resumed` 那一格
        //   也成立 —— 只清不注入的话，库里会出现「resumed=true 而 prompt 里
        //   根本没有那一段」的行，而那一格的全部用途就是「恢复路径触发了几次」。
        //   清理仍然要做：不然一个旧状态会在开关重新打开的那天突然生效。
        if (!agentProperties.getSlots().isEnabled()) {
            session.setPendingClarify(null);
            writePendingClarify(session.getId(), null);
            log.debug("槽位功能关着，清掉残留的 pending_clarify（不注入）");
            return null;
        }

        PendingClarify pending = PendingClarify.read(objectMapper, raw);

        // ★ 先清内存再清库：即使下面那条 UPDATE 失败，这一轮也不会重复消费
        session.setPendingClarify(null);
        writePendingClarify(session.getId(), null);

        if (pending != null) {
            log.info("★ 取出上一轮悬着的澄清（{}）—— 本轮分类会带上它", pending.describe());
        }
        return pending;
    }

    /**
     * 记下这一轮的反问，供<b>下一轮</b>分类时使用（阶段 9.4）。
     *
     * <p>位置与 {@code saveAssistantClarification} 相邻（都在澄清的落库段），
     * 但它们是两件事：那条写的是<b>用户能看到的那句话</b>（chat_message），
     * 这条写的是<b>给下一轮的机器状态</b>（chat_session）。
     *
     * <p>★ 不写检索/生成那两列，也不进 qa_log —— 它只服务分类那一次调用，
     * 而它的样子在下一轮的 {@code intent_plan.resumed} 上可见。
     */
    private void rememberPendingClarify(ChatSession session, PendingClarify pending) {
        if (pending == null) {
            return;                 // 槽位功能关着（Decision.pending() 为 null）
        }
        String json = PendingClarify.write(objectMapper, pending);
        if (json == null) {
            return;                 // 序列化失败已经记了 ERROR，退化成 9.3 的行为
        }
        session.setPendingClarify(json);
        writePendingClarify(session.getId(), json);
        log.info("★ 记下待澄清状态（{}），下一轮分类会带上它", pending.describe());
    }

    /**
     * 只写 {@code chat_session.pending_clarify} 这一列。
     *
     * <h3>★★ 为什么必须用 {@code lambdaUpdate().set(...)}，不能 {@code updateById}</h3>
     *
     * <p>MyBatis-Plus 的默认更新策略是 {@code NOT_NULL}：<b>{@code updateById}
     * 会静默跳过值为 null 的字段</b>（{@code touchSession} 的注释里记着这条）。
     * 而这里「清空」正是 {@code null} —— 用 {@code updateById} 的话，
     * <b>清空这个动作会变成一次空更新，什么都不改，也不报错</b>。
     *
     * <pre>
     *   后果：状态永不清空 → 每一轮都把那个旧反问注入一次
     *   → 用户换了话题也照样被它误导，而日志里只有一条「取出上一轮悬着的澄清」
     * </pre>
     *
     * <p>★ {@code .set(column, null)} 生成的 {@code SET pending_clarify = NULL}
     * 才是这里要的语义（同 {@code claimSession} 用条件 UPDATE 而不是先读后写的理由：
     * <b>「想做的动作」和「实际写下去的语句」必须对得上</b>）。
     *
     * <p>⚠️ 失败只 WARN，不让问答失败 —— 它是一条会话状态，
     * 丢了最坏的结果是「这一轮的反问没被记住」，也就是退回 9.3 的行为。
     */
    private void writePendingClarify(Long sessionId, String json) {
        if (sessionId == null) {
            return;
        }
        try {
            chatSessionService.lambdaUpdate()
                    .eq(ChatSession::getId, sessionId)
                    .set(ChatSession::getPendingClarify, json)
                    .update();
        } catch (Exception e) {
            log.warn("更新 pending_clarify 失败 sessionId={}：{}", sessionId, e.getMessage());
        }
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

        // ★★ 评测标记（V10 新增，阶段 7）—— 同样只在【这一处】填。
        //
        //   ctx.eval() 为 null 是【常态】：绝大多数请求是真实用户发的，
        //   它们两个头都不带。NULL 是「不是评测流量」的诚实表达 ——
        //   填一个 "" 或 "none" 会让 `WHERE eval_run_id IS NULL`
        //   这个「只统计真实使用」的筛子**静默漏掉一部分真实行**。
        //
        //   ⚠️ 被排队拒绝的那些行【不经过这里】，它们在
        //      ChatAdmissionService.rateLimitedLog 里手写这两列 ——
        //      本方法是全项目唯一的落库点，那个方法是唯一的例外。
        if (ctx.eval() != null) {
            log.setEvalRunId(ctx.eval().runId());
            log.setEvalQuestionNo(ctx.eval().questionNo());
        }

        log.setSessionId(session.getId());

        // ★★ 身份（阶段 9 起才真的有值）—— 取【请求粒度】的 ctx.userId()，
        //    而不是会话粒度的 session.getUserId()。
        //
        //    两个数在「同一个 sessionNo 上换了头」时会不同，而那不是数据不一致：
        //       session.user_id  这个会话是谁开的（write-once）
        //       qa_log.user_id   这一问是谁提的（逐条）
        //    评测要回答的是后者（「这道题是谁问的」），所以取 ctx。
        //    ★ 它和工具那条路用的是同一个值 —— 工具查的是谁的订单，
        //      这一列就该记谁，否则「查了 A 的订单但记成 B」会静默成立。
        //
        // ★★ 这一列从阶段 9 起是一张【形状表】，不是一句「它总是 X」：
        //      阶段 9 之前的行        恒为 NULL（历史不可追）
        //      之后 · 头缺席           NULL（匿名，合法）
        //      之后 · 头指向不存在的人  【原样记下那个值】（如 999999）—— 见下
        //      之后 · 正常             请求头里那个 id
        //
        // ★★★ 最后那一格和 chat_session.user_id 【刻意不同】，别去「修」它：
        //      chat_session.user_id 在写之前会核对 app_user（那一列上有 FK），
        //      查不到就记 NULL；而这一列不核对，原样记。
        //
        //      理由是「工具那条路拿的就是这个值」：
        //        QueryOrderStatusTool 执行的是 WHERE user_id = <请求头里的值>，
        //      所以 999999 才是【这一次真正被使用的身份】。
        //      记成 NULL 的话，「这次用的是哪个身份」就答不出来了。
        //
        //      ★ 而且「没有身份」和「伪造了身份」是两件不同的事 ——
        //        合并成 NULL 会把「有人拿伪造的头打了一发」这个证据毁掉，
        //        而那正是排查坑 33 时唯一想看的线索。
        //
        //      ⚠️ 代价（用时必须知道）：这一列【可能不存在于 app_user】。
        //        所以任何 JOIN app_user 都会静默丢掉那些行，
        //        distinct(user_id) 也会被伪造的头灌水。
        log.setUserId(ctx.userId());
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

        // ── ★★ 结构化计划（阶段 9.2 新增）──
        //
        // ★ 它和 queue_ms 是同一个理由：在【这一处】填，不在各条路径上填。
        //   成功、失败、工具、澄清都从这里经过；写在别处的话，
        //   每加一条路径就多一次「忘了填」的机会。
        //
        // ★★ 门控那两格（retrieve / gate）是【用同一个纯函数在这里再算一次】得到的，
        //    不是从调用点传下来的。这么做有一个必须写下来的前提：
        //    RetrievalGate.decide() 是【纯函数】—— 输入相同则输出逐字相同。
        //    ⚠️ 如果将来给它加了缓存或状态，「再算一次」和「调用点那次」就可能不一致，
        //       那时必须改成显式传参（会让 5 个落库方法一起编译不过，跑不掉的）。
        //
        // ★ 记的是【生效的结论】（gate 的输出），不是模型的原话。
        //   两者的差别在工具意图上最明显：模型可能说 retrieve=true，
        //   而工具意图根本不检索 —— 记原话会让「retrieve=true 却没检索」
        //   看起来像 bug。★ 模型的原话在 shape/retrieve 的原始值里另有体现
        //   （见 IntentPlan），而这里要的是「实际发生了什么」。
        log.setIntentPlan(serializeIntentPlan(intent, ctx.clarifyResumed()));

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
     * {@code intent_plan.v} 的当前值。模型那个 {@code v} 是它自己写的，这个是我们的。
     *
     * <p>★ 9.3 从 1 升到 2：多了一格 {@code tools}。
     * ★ 9.4 从 2 升到 3：多了一格 {@code resumed}。
     * v=1 的行有 6 格、v=2 有 7 格、v=3 有 8 格 —— 下游脚本按 {@code v} 分派，
     * 就不会在「某个键突然不存在」上栽跟头。
     */
    private static final int PLAN_VERSION = 3;

    /**
     * 把结构化计划序列化成 {@code intent_plan}（阶段 9.2）。
     *
     * <p>形状固定六格：
     *
     * <pre>
     *   {"v":1,"intent":"SPEC_QUERY","retrieve":true,"gate":"KB","missing":[],"shape":"JSON"}
     * </pre>
     *
     * <table border="1">
     *   <caption>每一格回答什么问题</caption>
     *   <tr><th>格</th><th>取值</th><th>它回答的问题</th></tr>
     *   <tr><td>{@code intent}</td><td>code</td>
     *       <td>★ 冗余自 {@code qa_log.intent}，<b>故意冗余</b>：
     *           这一列要能单独看懂，而分析时反复 JOIN 同一行的另一列没有意义</td></tr>
     *   <tr><td>{@code retrieve}</td><td>true/false</td>
     *       <td>★★ <b>生效的结论</b>（门控的输出），不是模型的原话。
     *           工具意图上两者会不同（模型说 true，而工具意图不检索）——
     *           记原话会让「retrieve=true 却没检索」看起来像 bug</td></tr>
     *   <tr><td>{@code gate}</td><td>{@code KB} / {@code PLAN_OFF} / {@code TOOL} /
     *           {@code NONE_INTENT} / {@code NONE_DISABLED} / {@code NO_CLASSIFY}</td>
     *       <td>谁下的决定。★ 没有它就无法把 {@code TOOL}（该走工具）和
     *           {@code PLAN_OFF}（模型主动关掉）分开 —— 两者都是「没检索」</td></tr>
     *   <tr><td>{@code tools}</td><td>工具名数组（9.3 加）</td>
     *       <td>★ 这一次<b>裁剪后</b>真正可用的工具，和 {@code retrieve} 一样是
     *           <b>生效的结论</b>。没有它，「按意图裁剪到底生效没有」在库里
     *           是一个答不出来的问题 —— 判据同 {@code shape}，
     *           只是它拦的是另一种静默失败：白名单过滤写成恒等，
     *           于是工具题一切照旧，而裁剪一次都没生效</td></tr>
     *   <tr><td>{@code missing}</td><td>槽位名数组</td>
     *       <td>9.4 做槽位填充的输入。★ 9.2 <b>只解析、只落库、不消费</b>。
     *           ⚠️ 它是<b>模型的原话</b>（不做白名单过滤）——
     *           过滤只发生在消费端，见 {@code ClarifySlots}</td></tr>
     *   <tr><td>{@code shape}</td><td>{@code JSON} / {@code CODE}</td>
     *       <td>★★★ <b>这一格是本阶段最重要的一格。</b>见下
     *           <p>⚠️ 文档里一度写着还有 {@code UNPARSED} —— 那个值
     *           <b>从来没有被构造过</b>（解析两边都失败时 plan 直接是 null，
     *           整列写 NULL）。9.4 把文档改成了事实</td></tr>
     *   <tr><td>{@code resumed}</td><td>true / false（9.4 加）</td>
     *       <td>★ 这一次分类<b>有没有带上上一轮悬着的澄清状态</b>
     *           （{@code chat_session.pending_clarify}）。
     *           没有它，「多轮澄清一次都没生效」在数据上和
     *           「生效了但没用」长得一模一样 —— 同 {@code shape} 那条理由，
     *           只是它拦的是另一种静默失败</td></tr>
     * </table>
     *
     * <h3>★★★ 为什么 {@code shape} 是这一项最重要的一格</h3>
     *
     * <p>没有它，本阶段最可能发生的那种失败是<b>完全静默的</b>：
     *
     * <pre>
     *   prompt 改成了「输出 JSON」，但模型照旧只吐一个裸 code
     *   → 解析器走回退路径（IntentReplyParser 的 ② CODE）
     *   → 一切看起来正常：分类照样成功、问答照样回答
     *   → 而【门控一次都没生效过】
     *   → 报告上所有旧指标一格不动，没有任何东西会红
     * </pre>
     *
     * <p>★ {@code shape} 的分布一印出来，这件事当场可见：
     * 正常的 {@code JSON} 占比应该接近 100%，掉下来就说明模型没跟上契约。
     *
     * <h3>★ 为什么返回 {@code null} 而不是 {@code "{}"}</h3>
     *
     * <p>同 {@code tool_calls} / {@code retrieval_detail} / {@code references} 那条
     * 贯穿全文件的约定：<b>「没发生」和「发生了但是空的」必须能区分开</b>。
     * 这里的「没发生」= 这次分类没有产出计划
     * （分类整个失败，或者是测试直接构造的结果）。
     */
    private String serializeIntentPlan(IntentClassification intent, boolean clarifyResumed) {
        if (intent == null || !intent.isClassified()) {
            return null;
        }
        IntentPlan plan = intent.plan();
        if (plan == null) {
            return null;
        }

        RetrievalGate.Decision gate = retrievalGate.decide(intent);

        // ★ LinkedHashMap 而不是 Map.of：键序稳定，人能直接 diff 两次输出
        //   （同 McpToolRegistry / ToolSpec 那条纪律，只是这里不进 prompt 前缀）
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", PLAN_VERSION);
        row.put("intent", intent.code());
        row.put("retrieve", gate.shouldRetrieve());
        row.put("gate", gate.reason());
        // ★ 9.3：裁剪后【真正可用】的工具。★ 和 retrieve 一样记的是门控的结论，
        //   不是意图树的声明 —— 两者在 NO_CLASSIFY 上会不同（树里可能写着工具，
        //   而分类都没成，门控给的是空清单），记声明会让那个区别看不出来
        row.put("tools", gate.tools());
        row.put("missing", plan.missingSlots());
        row.put("shape", plan.shape().name());
        // ★ 9.4：这一次分类有没有带上上一轮的澄清状态。★ 它记的是【事实】
        //   （我们从库里取出了一份 pending），而不是「模型有没有用上它」——
        //   后者模型自己都不会说，也没有可判定的判据。
        //   ⚠️ 它在【澄清行】上恒为 false：澄清行当然不是「恢复轮」。
        row.put("resumed", clarifyResumed);

        try {
            return objectMapper.writeValueAsString(row);
        } catch (Exception e) {
            // 同 serializeEvents / serializeToolCalls：序列化失败只记 ERROR，绝不抛 ——
            // 「记录过程信息」失败不该让已经拿到的回答作废
            log.error("intent_plan 序列化失败：{}", e.getMessage());
            return null;
        }
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

    /**
     * 日志里那一段「检索」的摘要（阶段 9.2）。
     *
     * <p>★★ <b>它存在的理由是「不检索」成了一条正常路径。</b>
     * 在此之前 {@code retrievalTrace} 只在澄清路径上是 null，而那条路
     * 压根走不到这两行日志；9.2 之后「模型说不用检索」也会让它为 null，
     * 于是 {@code retrievalTrace.summary()} 直接 NPE ——
     * <b>每一句「你好」都会 500</b>。
     *
     * <p>★ 这是<b>集成测试抓到的</b>，纯函数的门控测试一个都抓不到：
     * 它们证明了「判据是对的」，证不了「调用点用了它之后还活着」。
     *
     * <p>★ 没检索时用门控的 reason 顶替 —— 日志里仍然要能看出
     * <b>为什么</b>没检索（是模型关的、还是这个意图本来就不检索）。
     */
    private static String retrievalSummary(RetrievalTrace trace, RetrievalGate.Decision gate) {
        return trace != null ? trace.summary() : "未检索（" + gate.reason() + "）";
    }

    /**
     * 把「方法参数里的身份」和「上下文里的身份」收敛成一个（阶段 9）。
     *
     * <h3>★ 为什么会有两个来源</h3>
     *
     * <p>线上<b>不会有</b>：两条流式接口和非流式接口都在控制器里解析<b>一次</b>请求头，
     * 同一个值分别塞进 {@code Admission}（进而进 {@code CallContext}）和
     * 方法参数。所以正常路径上两者逐字相同。
     *
     * <p>不一致只可能来自<b>调用方写错了</b> —— 测试、探针、或者将来的新入口。
     * 所以这里的处理是：<b>以参数为准 + 打 WARN</b>，而不是静默择一。
     * 静默择一的后果是「工具查了别人的订单」，而日志里一行异常都没有。
     *
     * <p>★ 参数为 {@code null} 时<b>什么都不做</b>（保留上下文里的）。
     * 这与 {@code CallContext.withUserId} 的语义一致：null 是「不改」，不是「清空」。
     * 反过来写的话，{@code ask(request, null, ctx)} 会把排队层解析好的身份擦掉 ——
     * 而那个调用形态在测试里很常见。
     */
    private static CallContext convergeIdentity(Long userId, CallContext ctx) {
        if (userId == null) {
            return ctx;
        }
        if (ctx.userId() != null && !ctx.userId().equals(userId)) {
            log.warn("★ 身份有两个来源且不一致：方法参数=user#{} 上下文=user#{}（traceId={}）"
                            + " —— 以方法参数为准",
                    userId, ctx.userId(), ctx.traceId());
        }
        return ctx.withUserId(userId);
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
