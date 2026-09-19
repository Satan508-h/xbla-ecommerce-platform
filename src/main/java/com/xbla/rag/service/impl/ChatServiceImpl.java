package com.xbla.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.rag.RetrievalDetailBuilder;
import com.xbla.rag.rag.RetrievalPipeline;
import com.xbla.rag.rag.RetrievalTrace;
import com.xbla.rag.rag.prompt.RagPromptBuilder;
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
import java.util.UUID;

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
    private List<RetrievedChunk> retrieveSafely(String question, RetrievalTrace trace) {
        try {
            return retrievalPipeline.retrieve(question, trace);
        } catch (Exception e) {
            // 理论上 RetrievalPipeline 内部已经处理了所有失败路径，
            // 这里是最后一道闸 —— 防御的是「我们没预料到的运行时异常」
            log.error("★ 检索发生未预期的异常，退化为无知识库上下文 traceId={}: {}",
                    trace.traceId(), e.getMessage(), e);
            trace.event("retrieval_crashed: " + e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 把检索结果与调用方给的 system prompt 拼成最终的 system prompt。
     *
     * <p>两条路径共用。空检索结果不会产生空标题，而是换成一句
     * 「本次未检索到」的说明 —— 见 {@link RagPromptBuilder}。
     */
    private String ragSystemPrompt(String requestSystemPrompt, List<RetrievedChunk> chunks) {
        return ragPromptBuilder.build(systemPromptOrDefault(requestSystemPrompt), chunks);
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
    public ChatAskResponse ask(ChatAskRequest request) {
        long startNanos = System.nanoTime();
        String traceId = newTraceId();

        ChatSession session = resolveSession(request.sessionNo(), request.question());
        saveUserMessage(session.getId(), request.question());

        // ★ 检索插在【用户消息落库之后】。顺序是有意的：
        //   先落用户消息，检索失败时问题仍然在库里，评测数据是完整的。
        //   （★ askStream 里的插入位置不同，见那个方法里的说明。）
        RetrievalTrace retrievalTrace = new RetrievalTrace(traceId);
        List<RetrievedChunk> chunks = retrieveSafely(request.question(), retrievalTrace);

        ModelCallTrace trace = new ModelCallTrace(traceId);
        ChatRequest modelRequest = ChatRequest.of(
                ragSystemPrompt(request.systemPrompt(), chunks), request.question());

        try {
            ChatResponse response = router.chat(modelRequest, trace);

            saveAssistantMessage(session.getId(), response, chunks);
            saveQaLogSuccess(traceId, session, request.question(), trace, retrievalTrace,
                    response, chunks, startNanos);
            touchSession(session);

            ChatAskResponse result = buildResponse(traceId, session, trace, response, chunks, startNanos);
            log.info("问答完成 {} | {}", trace.summary(), retrievalTrace.summary());
            return result;

        } catch (Exception e) {
            // ★ 失败也要落 qa_log —— 它是评测的唯一数据来源，
            //   而且「哪类问题答不上来」本身就是重要信息
            //
            //   ★ 注意这里的 e 【只可能来自模型链路】—— 检索的异常
            //     已经被 retrieveSafely 吞掉了。混进来会让归因错乱
            saveQaLogFailure(traceId, session, request.question(), trace, retrievalTrace,
                    chunks, e, startNanos);
            touchSession(session);
            throw e;
        }
    }

    // ============================================================
    // 流式
    // ============================================================

    @Override
    public void askStream(ChatAskRequest request, ChatStreamSink sink) {
        long startNanos = System.nanoTime();
        String traceId = newTraceId();

        ChatSession session = resolveSession(request.sessionNo(), request.question());
        saveUserMessage(session.getId(), request.question());

        // 先告诉前端 traceId 和 sessionNo，别让它对着空白页等首字节
        sink.onStart(traceId, session.getSessionNo());

        // ★★ 检索必须插在 sink.onStart 【之后】。
        //
        //   上一次调用 onStart 的全部意义就是「别让前端对着空白页等首字节」。
        //   而一次检索要花几百毫秒（一次向量化 + 两次数据库查询 + 一次重排），
        //   插在 onStart 前面会把这几百毫秒原封不动地加在用户看到任何反馈之前 ——
        //   【正好抵消掉 onStart 的全部价值】。
        //
        //   插在 router.chatStream 之后又毫无意义（那时候已经在生成了）。
        //   所以唯一正确的位置就是 onStart 与 chatStream 之间。
        RetrievalTrace retrievalTrace = new RetrievalTrace(traceId);
        List<RetrievedChunk> chunks = retrieveSafely(request.question(), retrievalTrace);

        ModelCallTrace trace = new ModelCallTrace(traceId);
        ChatRequest modelRequest = ChatRequest.of(
                ragSystemPrompt(request.systemPrompt(), chunks), request.question());

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

            saveAssistantStreamMessage(session.getId(), trace, streamResult, fullAnswer, chunks);
            saveQaLogStreamSuccess(traceId, session, request.question(), trace, retrievalTrace,
                    streamResult, fullAnswer, chunks, startNanos);
            touchSession(session);

            sink.onComplete(buildStreamResponse(traceId, session, trace, streamResult, chunks, startNanos));
            log.info("流式问答完成 {} | {}", trace.summary(), retrievalTrace.summary());

        } catch (Exception e) {
            saveQaLogFailure(traceId, session, request.question(), trace, retrievalTrace,
                    chunks, e, startNanos);
            touchSession(session);

            log.warn("流式问答失败 traceId={} : {}", traceId, e.getMessage());
            sink.onError(userFacingMessage(e), traceId);
        }
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
                ? sessionNo : newTraceId());
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
                                      List<RetrievedChunk> chunks) {
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
        chatMessageService.save(message);
    }

    private void saveAssistantStreamMessage(Long sessionId, ModelCallTrace trace,
                                            StreamResult streamResult, String fullAnswer,
                                            List<RetrievedChunk> chunks) {
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

    private void saveQaLogSuccess(String traceId, ChatSession session, String question,
                                  ModelCallTrace trace, RetrievalTrace retrievalTrace,
                                  ChatResponse response, List<RetrievedChunk> chunks,
                                  long startNanos) {
        QaLog log = baseLog(traceId, session, question, trace, retrievalTrace, chunks, startNanos);
        log.setFinalAnswer(response.content());
        log.setStatus(1);                   // 1 = 成功
        writeQaLog(log);
    }

    private void saveQaLogStreamSuccess(String traceId, ChatSession session, String question,
                                        ModelCallTrace trace, RetrievalTrace retrievalTrace,
                                        StreamResult streamResult,
                                        String fullAnswer, List<RetrievedChunk> chunks,
                                        long startNanos) {
        QaLog entity = baseLog(traceId, session, question, trace, retrievalTrace, chunks, startNanos);
        entity.setStatus(1);
        // ★ 完整回答已经由 askStream 累积好了，这里落库。
        //   阶段 7 的答案质量类指标全靠这一列。
        entity.setFinalAnswer(fullAnswer);
        writeQaLog(entity);
    }

    private void saveQaLogFailure(String traceId, ChatSession session, String question,
                                  ModelCallTrace trace, RetrievalTrace retrievalTrace,
                                  List<RetrievedChunk> chunks, Exception e, long startNanos) {
        // ★ 失败路径【也要】写检索字段。
        //   这正是 V5 里 retrieval_detail 那列注释要回答的问题：
        //   「召回失败是因为向量检索没找到，还是重排排错了，还是切分粒度不对」
        //   —— 只在成功时记，就永远回答不了它
        QaLog log = baseLog(traceId, session, question, trace, retrievalTrace, chunks, startNanos);
        log.setStatus(2);                   // 2 = 失败
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
    private QaLog baseLog(String traceId, ChatSession session, String question,
                          ModelCallTrace trace, RetrievalTrace retrievalTrace,
                          List<RetrievedChunk> chunks, long startNanos) {
        QaLog log = new QaLog();
        log.setTraceId(traceId);
        log.setSessionId(session.getId());
        log.setUserId(session.getUserId());
        log.setQuestion(question);

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
        log.setTotalLatencyMs(elapsedMs(startNanos));
        log.setDegradationEvents(serializeEvents(trace));

        // ── ★ 检索相关字段（阶段 4 新增，此前一直为空）──
        //
        // retrieval_latency_ms 是【整条检索链路的耗时】，
        // rerank_latency_ms 是其中【重排那一段】的耗时 ——
        // 两者是包含关系，不是并列关系。这样拆是为了回答
        // 「P95 变慢了，慢在召回还是慢在重排」。
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
                                          List<RetrievedChunk> chunks, long startNanos) {
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
                buildReferences(chunks));
    }

    private ChatAskResponse buildStreamResponse(String traceId, ChatSession session,
                                                ModelCallTrace trace, StreamResult streamResult,
                                                List<RetrievedChunk> chunks, long startNanos) {
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
                buildReferences(chunks));
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

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
