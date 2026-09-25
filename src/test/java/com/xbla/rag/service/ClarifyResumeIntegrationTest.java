package com.xbla.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.agent.intent.ClarifySlots;
import com.xbla.rag.agent.intent.PendingClarify;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.rag.RetrievalPipeline;
import com.xbla.rag.rag.RetrievalTrace;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>阶段 9.4 的验收测试</b>：多轮澄清真的接通了。
 *
 * <h2>★★★ 它要证明的那件事</h2>
 *
 * <p>9.4 之前，「那个怎么样」→ 反问 → 用户答「送长辈」→ 分类器<b>只看到那三个字</b>
 * → 仍然是「信息不足」→ <b>再问一次</b>。
 * 实测那个比例是 <b>2/3</b>（{@code docs/05} §9.5 ②）。
 *
 * <p>所以本类的核心断言是<b>两段式</b>的，而第二段才是要害：
 *
 * <pre>
 *   第一轮：分类 = NEEDS_CLARIFICATION（缺 budget）
 *           → 反问文案必须是【问预算】那一句（不是固定的那句）
 *           → chat_session.pending_clarify 必须被写上
 *   第二轮：分类器收到的 prompt 里必须【带着上一轮的原问题和槽位】
 *           → qa_log.intent_plan.resumed 必须是 true
 *           → 那一列必须已经被清空（读后即清）
 * </pre>
 *
 * <h2>★ 为什么断言 prompt 而不是「回答变好了」</h2>
 *
 * <p>回答是模型写的，问它「有没有变好」等于问一个我们控制不了的东西。
 * 而「分类器有没有拿到那段上下文」是<b>我们自己的代码</b>决定的事实 ——
 * 用 {@code ArgumentCaptor} 把分类请求抓出来看 prompt，是最硬的那条判据
 * （同 9.3 那条纪律：先看工具说了什么，再看模型说了什么，然后比较）。
 *
 * <h2>真假对照</h2>
 *
 * <table border="1">
 *   <caption>这个测试里什么是真的、什么是桩</caption>
 *   <tr><th>组件</th><th>真假</th><th>说明</th></tr>
 *   <tr><td>{@code ChatModelRouter}</td><td><b>桩</b></td>
 *       <td>按剧本返回分类结果与回答。★ 不花钱</td></tr>
 *   <tr><td>{@code LlmIntentClassifier}</td><td><b>真</b></td>
 *       <td>★ 走真的解析与真的 prompt 拼装 —— 断言的就是它产出的那个 prompt</td></tr>
 *   <tr><td>{@code RetrievalPipeline}</td><td><b>桩</b></td>
 *       <td>真跑会调向量化接口（要花钱）</td></tr>
 *   <tr><td><b>数据库</b></td><td><b>真</b></td>
 *       <td>会话状态、{@code qa_log} 都是真的读写</td></tr>
 * </table>
 */
@SpringBootTest(properties = {
        // ★ 关掉记忆与摘要：摘要走【异步】后台线程，和 @Transactional 的回滚撞在一起会随机失败
        "xbla.chat.history.enabled=false",
        "xbla.chat.summary.enabled=false"
})
@Transactional
@DisplayName("ChatService · 多轮澄清 / 槽位填充（阶段 9.4 验收）")
class ClarifyResumeIntegrationTest {

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    /** 一句会被反问的问题，和它的答案 —— 两个常量是为了让断言读起来像对话 */
    private static final String VAGUE = "那个怎么样";
    private static final String ANSWER = "送长辈";

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private QaLogMapper qaLogMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatModelRouter router;

    @MockitoBean
    private RetrievalPipeline retrievalPipeline;

    // ============================================================
    // 夹具
    // ============================================================

    /** 分类契约的那一行 JSON（★ 9.2 起模型要按这个格式答） */
    private static String planJson(String intent, String... missing) {
        String slots = Arrays.stream(missing)
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(","));
        return "{\"v\":1,\"intent\":\"" + intent + "\",\"retrieve\":true,\"missing\":["
                + slots + "]}";
    }

    private static ChatResponse reply(String content) {
        return ChatResponse.text(content, "stop", null, DESCRIPTOR, 10);
    }

    /** 按顺序返回剧本里的回复；用完之后一直返回最后一条 */
    private void script(String... contents) {
        AtomicInteger n = new AtomicInteger();
        when(router.chat(any(), any())).thenAnswer(inv ->
                reply(contents[Math.min(n.getAndIncrement(), contents.length - 1)]));
    }

    private void stubRetrieval() {
        when(retrievalPipeline.retrieve(anyString(), any(), any(RetrievalTrace.class)))
                .thenReturn(List.<RetrievedChunk>of());
    }

    private ChatSession sessionOf(String sessionNo) {
        return chatSessionService.lambdaQuery()
                .eq(ChatSession::getSessionNo, sessionNo)
                .one();
    }

    private QaLog qaLogOf(String traceId) {
        List<QaLog> rows = qaLogMapper.selectList(
                new LambdaQueryWrapper<QaLog>().eq(QaLog::getTraceId, traceId));
        assertThat(rows).as("这次问答应该恰好写一行 qa_log，traceId=%s", traceId).hasSize(1);
        return rows.get(0);
    }

    /** JSONB 列一律解析成对象再断言 —— 别比文本，PG 会重排键并规范化空格 */
    private Map<String, Object> planOf(QaLog log) {
        assertThat(log.getIntentPlan()).as("intent_plan 不应该为 null").isNotNull();
        try {
            return objectMapper.readValue(log.getIntentPlan(), new TypeReference<>() {
            });
        } catch (Exception e) {
            // ★ 说准了的原因：不是「JSON 不在」，而是「它在但不是合法 JSON」
            throw new AssertionError("intent_plan 不是合法 JSON：" + e.getMessage(), e);
        }
    }

    /**
     * 抓出<b>最后一次</b>分类请求的 system prompt。
     *
     * <p>★ 判据是 prompt 的开头，不是调用顺序（顺序在桩里不可靠）。
     * ★★ 取<b>最后</b>一次而不是第一次：这些用例里往往一轮就是一次问答，
     * 而前一轮也可能有分类 —— 取第一次的话，断言看的是上一轮那份 prompt，
     * 于是「注入没生效」也会是绿的。
     */
    private static String lastClassifierPromptOf(List<ChatRequest> requests) {
        List<String> prompts = requests.stream()
                .map(ChatRequest::systemPrompt)
                .filter(p -> p != null && p.startsWith("你是电商问答平台的意图分类器"))
                .toList();
        if (prompts.isEmpty()) {
            throw new AssertionError("这一次问答没有发生分类调用");
        }
        return prompts.get(prompts.size() - 1);
    }

    /** 抓出全部请求（{@code atLeast} 会把每一次调用都收进来） */
    private static List<ChatRequest> captureRequests(ChatModelRouter router) {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, atLeast(1)).chat(captor.capture(), any(ModelCallTrace.class));
        return captor.getAllValues();
    }

    /** 收集 {@code onStart / onDelta / onComplete / onError} 的桩接收器（同 9.1 的写法） */
    private static final class CapturingSink implements ChatService.ChatStreamSink {
        final StringBuilder body = new StringBuilder();
        String startedTraceId;
        ChatAskResponse done;
        String error;

        @Override
        public void onStart(String traceId, String sessionNo) {
            this.startedTraceId = traceId;
        }

        @Override
        public void onDelta(String delta) {
            body.append(delta);
        }

        @Override
        public void onComplete(ChatAskResponse summary) {
            this.done = summary;
        }

        @Override
        public void onError(String message, String traceId) {
            this.error = message;
        }
    }

    // ============================================================
    // 一、★ 澄清轮：问对东西 + 记下状态
    // ============================================================

    @Nested
    @DisplayName("一、★ 澄清轮：按缺的槽位问，并把状态记下来")
    class ClarificationTurn {

        @Test
        @DisplayName("① ★★ 模型说缺 budget → 反问【预算】那一句，而不是固定的「哪款商品」")
        void asksTheMissingSlotNotTheFixedText() {
            script(planJson("NEEDS_CLARIFICATION", "budget"));

            ChatAskResponse response = chatService.ask(
                    new ChatAskRequest("s-resume-1", VAGUE, null), null);

            assertThat(response.intent()).isEqualTo("NEEDS_CLARIFICATION");
            assertThat(response.answer())
                    .as("★ 9.4 之前这里恒为那句固定的「你是想问哪款商品呢？」——"
                            + "而模型说的是缺预算，两者对不上")
                    .contains("预算")
                    .doesNotContain("哪款商品");
        }

        @Test
        @DisplayName("② ★★ 澄清轮会把状态写进 chat_session.pending_clarify")
        void writesPendingState() {
            script(planJson("NEEDS_CLARIFICATION", "budget"));

            chatService.ask(new ChatAskRequest("s-resume-2", VAGUE, null), null);

            ChatSession session = sessionOf("s-resume-2");
            PendingClarify pending = PendingClarify.read(objectMapper, session.getPendingClarify());
            assertThat(pending)
                    .as("★★ 没有它，下一轮的分类器就还是只看到那三个字 —— 这就是 9.4 的全部机制")
                    .isNotNull();
            assertThat(pending.question()).isEqualTo(VAGUE);
            assertThat(pending.asked()).isEqualTo(ClarifySlots.BUDGET);
            assertThat(pending.slots()).containsExactly("budget");
        }

        @Test
        @DisplayName("★ ③ 反对照：业务意图那一轮【不写】状态、也不改反问文案")
        void businessTurnWritesNothing() {
            script(planJson("RETURN_EXCHANGE"), "自签收之日起 7 天内可以无理由退货。");
            stubRetrieval();

            ChatAskResponse response = chatService.ask(
                    new ChatAskRequest("s-resume-3", "退货要几天", null), null);

            assertThat(response.intent()).isEqualTo("RETURN_EXCHANGE");
            assertThat(sessionOf("s-resume-3").getPendingClarify())
                    .as("★ 只有澄清轮才写状态 —— 写成「每轮都写」的话，"
                            + "下一轮永远带着一段没用的上下文进分类")
                    .isNull();
        }
    }

    // ============================================================
    // 二、★★ 恢复轮：分类器真的看得见上一轮
    // ============================================================

    @Nested
    @DisplayName("二、★★ 恢复轮：分类器收到了上一轮的反问")
    class ResumeTurn {

        @Test
        @DisplayName("④ ★★★ 「那个怎么样」→ 反问 → 用户答「送长辈」：分类 prompt 里带着上一轮")
        void classifierSeesThePreviousQuestion() {
            // 第一轮：澄清（缺 budget）
            script(planJson("NEEDS_CLARIFICATION", "budget"));
            chatService.ask(new ChatAskRequest("s-resume-4", VAGUE, null), null);

            // 第二轮：用户回答
                        script(planJson("SCENARIO_PICK"), "送长辈可以看看这几款……");
            stubRetrieval();
            ChatAskResponse second = chatService.ask(
                    new ChatAskRequest("s-resume-4", ANSWER, null), null);


            String prompt = lastClassifierPromptOf(captureRequests(router));
            assertThat(prompt)
                    .as("★★★ 这就是 9.4 的全部：分类器不再是「只看这一句话」")
                    .contains("## 上一轮的澄清")
                    .contains(VAGUE)          // 锚：上一轮问的是哪件事
                    .contains("budget")       // 缺的是哪一项
                    .contains(ANSWER);        // 也不该把这一句搞丢
            assertThat(prompt).contains("如果现在这句话是在回答上面那个问题");

            // ★ 而这一轮的落库要能看出来「恢复了」
            assertThat(planOf(qaLogOf(second.traceId())))
                    .containsEntry("resumed", true);
        }

        @Test
        @DisplayName("★★ ⑤ 读后即清：状态用完就没了，第三轮不再带上下文")
        void stateIsConsumedOnce() {
            script(planJson("NEEDS_CLARIFICATION", "budget"));
            chatService.ask(new ChatAskRequest("s-resume-5", VAGUE, null), null);

                        script(planJson("SCENARIO_PICK"), "送长辈可以看看这几款……");
            stubRetrieval();
            chatService.ask(new ChatAskRequest("s-resume-5", ANSWER, null), null);

            assertThat(sessionOf("s-resume-5").getPendingClarify())
                    .as("★★ 读后即清 —— 剩下它的话，用户下一轮换了话题也照样被这段上下文误导")
                    .isNull();

            // 第三轮：同一个会话，再问一句
                        script(planJson("SPEC_QUERY"), "这一款是……");
            stubRetrieval();
            ChatAskResponse response = chatService.ask(
                    new ChatAskRequest("s-resume-5", "它有多重", null), null);


            String prompt = lastClassifierPromptOf(captureRequests(router));
            assertThat(prompt)
                    .as("★★★ 一次性：窗口只有一轮。这一条防的是「状态残留 → 每轮都被误导」")
                    .doesNotContain("## 上一轮的澄清");
            assertThat(planOf(qaLogOf(response.traceId()))).containsEntry("resumed", false);
        }

        @Test
        @DisplayName("★★ ⑥ 反对照：没有上一轮澄清的会话 → prompt 里没有那一段，resumed=false")
        void noPendingNoInjection() {
                        script(planJson("SPEC_QUERY"), "这一款是……");
            stubRetrieval();

            ChatAskResponse response = chatService.ask(
                    new ChatAskRequest("s-resume-6", "这款手机多重", null), null);


            assertThat(lastClassifierPromptOf(captureRequests(router)))
                    .as("★ 反面证据：那一段是【条件插入】的 —— "
                            + "否则「基线不用重测」这个结论不成立")
                    .doesNotContain("## 上一轮的澄清");
            assertThat(planOf(qaLogOf(response.traceId())))
                    .containsEntry("resumed", false)
                    .containsEntry("v", 3);
        }

        @Test
        @DisplayName("★ ⑦ 形状不对的状态不炸：问答照常成功（退化成 9.3 的行为）")
        void unusableStateDoesNotBreakTheTurn() throws Exception {
            // 先造一个会话
            script(planJson("SPEC_QUERY"), "这一款是……");
            stubRetrieval();
            chatService.ask(new ChatAskRequest("s-resume-7", "第一句", null), null);

            // 手动写一份【形状不对】的状态（模拟：手改库、或者将来某次格式升级留下的旧数据）
            //
            // ★★ 注意这里写的是【合法 JSON】而不是「{半截」——
            //    第一次写这个用例时我塞了一段半截 JSON 进去，结果 PostgreSQL 直接
            //    DataIntegrityViolation：**jsonb 列存不进坏 JSON，数据库自己先拦了**。
            //    ⇒ 所以这一列上「坏数据」的真实形态只有一种：
            //      **合法 JSON、但形状不对**（缺 question / 不是对象 / 槽位不是数组）。
            //      真正语法坏掉的 JSON 只能来自「有人手动改了代码里的读法」，
            //      而那由 PendingClarifyTest 的单测覆盖。
            chatSessionService.lambdaUpdate()
                    .eq(ChatSession::getId, sessionOf("s-resume-7").getId())
                    .set(ChatSession::getPendingClarify, "{\"v\":1,\"slots\":[\"budget\"]}")
                    .update();

                        script(planJson("SPEC_QUERY"), "这一款是……");
            ChatAskResponse response = chatService.ask(
                    new ChatAskRequest("s-resume-7", "第二句", null), null);


            assertThat(response.answer()).as("问答不能被一个读不出来的状态搞挂").isNotNull();
            assertThat(lastClassifierPromptOf(captureRequests(router)))
                    .as("读不出锚（缺 question）→ 宁可不注入")
                    .doesNotContain("## 上一轮的澄清");
            assertThat(sessionOf("s-resume-7").getPendingClarify())
                    .as("★★ 读不出来的那份【也要清】—— 留着它会让每一轮都重新解析、"
                            + "重新 WARN 一遍，而那条 WARN 会淹没在正常噪音里")
                    .isNull();
        }
    }

    // ============================================================
    // 三、★ 流式那条路（平行代码，必须一起改）
    // ============================================================

    @Nested
    @DisplayName("三、★ 流式路径同样接通（两条路是平行代码）")
    class StreamPath {

        @Test
        @DisplayName("★★ ⑧ askStream 也会消费 pending，并且把 resumed 记进 qa_log")
        void streamResumesToo() {
            // 第一轮：澄清（走流式）
            script(planJson("NEEDS_CLARIFICATION", "budget"));
            CapturingSink first = new CapturingSink();
            chatService.askStream(new ChatAskRequest("s-resume-8", VAGUE, null), first, null,
                    CallContext.fresh("t-resume-8-a"));

            assertThat(first.body.toString())
                    .as("★ 反问文案按槽位选 —— 流式那条路要和 ask() 一模一样")
                    .contains("预算");
            assertThat(sessionOf("s-resume-8").getPendingClarify()).isNotNull();

            // 第二轮：回答（走流式）
                        script(planJson("SCENARIO_PICK"), "送长辈可以看看这几款……");
            stubRetrieval();
            CapturingSink second = new CapturingSink();
            chatService.askStream(new ChatAskRequest("s-resume-8", ANSWER, null), second, null,
                    CallContext.fresh("t-resume-8-b"));


            assertThat(lastClassifierPromptOf(captureRequests(router)))
                    .as("★★ 两条路是平行代码 —— 只在 ask() 里接的话，"
                            + "工具题在 SSE 上静默降级那一课就白上了（9.1 修过一次同型的问题）")
                    .contains("## 上一轮的澄清");
            assertThat(planOf(qaLogOf("t-resume-8-b"))).containsEntry("resumed", true);
            assertThat(sessionOf("s-resume-8").getPendingClarify()).isNull();
        }
    }

    // ============================================================
    // 四、★ 一次真实的「连环澄清」是怎么被打破的
    // ============================================================

    @Nested
    @DisplayName("四、★★ 端到端：那个 2/3 的洞")
    class TheRealDebt {

        @Test
        @DisplayName("★★★ ⑨ 「送长辈」这一句【单独拿出来】仍然会被判信息不足 —— 但带上上下文就不会")
        void theSameSentenceClassifiedBothWays() {
            // ① 单独分类：这是 9.4 之前的样子（也是那个 2/3 的成因）
            script(planJson("NEEDS_CLARIFICATION", "purpose"));
            ChatAskResponse alone = chatService.ask(
                    new ChatAskRequest("s-resume-9-plain", ANSWER, null), null);
            assertThat(alone.intent())
                    .as("★ 桩复现的就是真实行为：分类器只看这一句话时，"
                            + "「送长辈」确实是信息不足")
                    .isEqualTo("NEEDS_CLARIFICATION");

            // ② 带着上一轮的反问：同一句话，但分类器看得见「我在问什么」
            script(planJson("NEEDS_CLARIFICATION", "purpose"));
            chatService.ask(new ChatAskRequest("s-resume-9", VAGUE, null), null);
                        script(planJson("SCENARIO_PICK"), "送长辈可以看看这几款……");
            stubRetrieval();
            chatService.ask(new ChatAskRequest("s-resume-9", ANSWER, null), null);

            String prompt = lastClassifierPromptOf(captureRequests(router));

            // ★★ 这一条是整段的要害：两次分类用的是【同一句话】，
            //    差别只有 prompt 里那一段上下文 —— 所以行为变化只可能来自它
            assertThat(prompt).contains(ANSWER).contains("## 上一轮的澄清");
            assertThat(sessionOf("s-resume-9").getPendingClarify()).isNull();
        }
    }

}
