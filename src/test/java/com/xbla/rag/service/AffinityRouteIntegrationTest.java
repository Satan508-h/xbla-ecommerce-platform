package com.xbla.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.client.dto.StreamResult;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.entity.AppUser;
import com.xbla.rag.entity.OrderItem;
import com.xbla.rag.entity.Orders;
import com.xbla.rag.entity.Product;
import com.xbla.rag.entity.ProductSku;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>阶段 9.5 的验收测试</b>：偏好块真的接进了问答链路。
 *
 * <h2>★★★ 它要证明的那件事 —— 一个字符串进了两个地方</h2>
 *
 * <p>偏好块要同时出现在：
 *
 * <pre>
 *   ① system prompt 里（模型当时看到的）
 *   ② qa_log.affinity  里（评测事后还原「模型当时看到了什么」的唯一凭据）
 * </pre>
 *
 * <p>而这两处<b>必须是同一个字符串</b>。如果落库那一层自己再渲染一遍，
 * 两边就可能漂移 —— 症状是评测拿着日志去算 faithfulness 时偏低，
 * 而看报告的人会去调一个根本没坏的东西。
 *
 * <p>★★ 所以本类最硬的那条断言是：
 * <b>{@code qa_log.affinity} 必须是捕获到的 system prompt 的一个子串</b>。
 * 它一句话同时钉住了「注入了」和「两处逐字相同」。
 *
 * <h2>★★ 二、两条路都要测，这不是洁癖</h2>
 *
 * <p>{@code ask} 和 {@code askStream} 是<b>两条平行代码</b>，
 * 本项目为「只改了一条」已经踩过好几次（ADR-047 / 081 / 坑 40）。
 * 流式那条是前端唯一在用的（{@code api.js} 只用 SSE），
 * 所以「非流式对了」这件事<b>完全不能说明</b>线上是对的。
 *
 * <h2>三、四种「没有偏好」都必须是 NULL</h2>
 *
 * <p>匿名 / 用户不存在 / 订单不足 / 澄清轮（没生成）。它们的共同点是
 * <b>「本来就没有」</b>，而 {@code NULL} 是这句话的诚实表达 ——
 * 填空串会让「偏好一次都没生效过」在数据上完全看不出来（V16 的列注释）。
 *
 * <h2>真假对照</h2>
 *
 * <table border="1">
 *   <caption>这个测试里什么是真的、什么是桩</caption>
 *   <tr><th>组件</th><th>真假</th><th>说明</th></tr>
 *   <tr><td>{@code ChatModelRouter}</td><td><b>桩</b></td>
 *       <td>按剧本返回分类结果与回答。★ 不花钱</td></tr>
 *   <tr><td>{@code RetrievalPipeline}</td><td><b>桩</b></td>
 *       <td>真跑会调向量化接口（要花钱）</td></tr>
 *   <tr><td>{@code UserAffinityProvider}</td><td><b>真</b></td>
 *       <td>★ 走真的 SQL、真的阈值、真的排序 —— 断言的就是它的产出</td></tr>
 *   <tr><td>{@code RagPromptBuilder}</td><td><b>真</b></td>
 *       <td>★ 断言的就是它拼出来的那段 prompt</td></tr>
 *   <tr><td><b>数据库</b></td><td><b>真</b></td>
 *       <td>订单、商品、qa_log 都是真的读写</td></tr>
 * </table>
 */
@SpringBootTest(properties = {
        // ★ 关掉记忆与摘要：摘要走【异步】后台线程，和 @Transactional 的回滚撞在一起会随机失败
        "xbla.chat.history.enabled=false",
        "xbla.chat.summary.enabled=false"
})
@Transactional
@DisplayName("ChatService · 偏好块贯通（阶段 9.5 验收）")
class AffinityRouteIntegrationTest {

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    private static final String QUESTION = "送长辈买什么好";

    /**
     * 分类契约那一行 JSON（9.2 起模型要按这个格式答）。
     *
     * <p>★ 这里刻意用 {@code RETURN_EXCHANGE} 这个<b>纯 KB 叶子</b>，不用
     * {@code SCENARIO_PICK} —— 后者是混合轮（KB + 工具），会走进 {@code ToolLoop}
     * 并真的发起 MCP 调用，而本类要测的是 prompt 里有没偏好块。
     */
    private static final String PLAN_KB =
            "{\"v\":1,\"intent\":\"RETURN_EXCHANGE\",\"retrieve\":true,\"missing\":[]}";
    private static final String PLAN_CLARIFY =
            "{\"v\":1,\"intent\":\"NEEDS_CLARIFICATION\",\"retrieve\":true,"
                    + "\"missing\":[\"budget\"]}";

    @Autowired
    private ChatService chatService;

    @Autowired
    private QaLogMapper qaLogMapper;

    @Autowired
    private AppUserService appUserService;

    @Autowired
    private OrdersService ordersService;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductSkuService productSkuService;

    @MockitoBean
    private ChatModelRouter router;

    @MockitoBean
    private RetrievalPipeline retrievalPipeline;

    private int seq;

    // ============================================================
    // 夹具
    // ============================================================

    private static ChatResponse reply(String content) {
        return ChatResponse.text(content, "stop", null, DESCRIPTOR, 10);
    }

    private void script(String... contents) {
        AtomicInteger n = new AtomicInteger();
        when(router.chat(any(), any())).thenAnswer(inv ->
                reply(contents[Math.min(n.getAndIncrement(), contents.length - 1)]));
    }

    private void stubRetrieval() {
        when(retrievalPipeline.retrieve(anyString(), any(), any(RetrievalTrace.class)))
                .thenReturn(List.<RetrievedChunk>of());
    }

    /**
     * 流式路径的桩。
     *
     * <p>★ 它不是可有可无的：{@code askStream} 走的是 {@code router.chatStream}，
     * 而没桩的话 Mockito 返回 {@code null}，于是「生成」那一步 NPE ——
     * 症状是<b>流式用例报了一个和偏好毫无关系的错误</b>。
     *
     * <p>⚠️ 顺带记一个真实的观察：那条 NPE 会留下<b>两行 qa_log</b>
     * （先一行 status=1，再一行 status=2）—— 因为异常发生在
     * {@code saveQaLogStreamSuccess} <b>之后</b>的 {@code buildStreamResponse} 里。
     * 那是既有的行为，不是 9.5 引入的；本类不依赖它，只是别把它当成自己的 bug 去查。
     */
    private void scriptStream(String content) {
        when(router.chatStream(any(), any(ModelCallTrace.class), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = inv.getArgument(2);
            onDelta.accept(content);
            return new StreamResult(
                    new ChatUsage(10, 5, 15, null, null, null), "stop", 10, 100, 200);
        });
    }

    /**
     * 造一个有 {@code orders} 笔有效订单的用户，买的全是 {@code category} 类目。
     *
     * <p>★ 类目是本类区分「谁的偏好」的唯一手段：两个用户买不同类目，
     * 于是「A 的 prompt 里有没有 B 的类目」成了一个可判的问题。
     */
    private long userBuying(String category, int orders) {
        AppUser user = new AppUser();
        user.setUserNo("S-AFFR-" + System.nanoTime());
        user.setNickname("偏好贯通测试用户");
        user.setMemberLevel(1);
        appUserService.save(user);

        for (int i = 0; i < orders; i++) {
            Orders order = new Orders();
            order.setOrderNo("SOAFFR" + System.nanoTime() + "-" + (++seq));
            order.setUserId(user.getId());
            order.setTotalAmount(new BigDecimal("2000.00"));
            order.setDiscountAmount(BigDecimal.ZERO);
            order.setPayAmount(new BigDecimal("2000.00"));
            order.setStatus(40);
            order.setCreatedAt(OffsetDateTime.now());
            order.setDeleted(0);
            ordersService.save(order);

            Product product = new Product();
            product.setProductNo("AFFR-" + (++seq));
            product.setName("偏好贯通商品" + seq);
            product.setCategory(category);
            product.setBrand("贯通测试品牌");
            product.setPrice(new BigDecimal("2000.00"));
            product.setStatus(1);
            product.setDeleted(0);
            productService.save(product);

            ProductSku sku = new ProductSku();
            sku.setProductId(product.getId());
            sku.setSkuNo("AFFRSKU-" + product.getId());
            sku.setSpecName("标准版");
            sku.setPrice(new BigDecimal("2000.00"));
            sku.setStatus(1);
            sku.setDeleted(0);
            productSkuService.save(sku);

            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(product.getId());
            item.setSkuId(sku.getId());
            item.setProductName(product.getName());
            item.setSpecName("标准版");
            item.setPrice(new BigDecimal("2000.00"));
            item.setQuantity(1);
            item.setSubtotal(new BigDecimal("2000.00"));
            orderItemService.save(item);
        }
        return user.getId();
    }

    private QaLog qaLogOf(String traceId) {
        List<QaLog> rows = qaLogMapper.selectList(
                new LambdaQueryWrapper<QaLog>().eq(QaLog::getTraceId, traceId));
        assertThat(rows).as("这次问答应该恰好写一行 qa_log，traceId=%s", traceId).hasSize(1);
        return rows.get(0);
    }

    /** 抓出全部发给模型的请求 */
    private List<ChatRequest> captureRequests() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, atLeast(1)).chat(captor.capture(), any(ModelCallTrace.class));
        return captor.getAllValues();
    }

    /**
     * 抓出<b>生成那一次</b>请求的 system prompt。
     *
     * <p>★ 判据是「不是分类 prompt」而不是调用顺序 —— 每一次问答里
     * 分类也是一次 {@code router.chat}，顺序在桩里不可靠。
     */
    private String generationPrompt() {
        return captureRequests().stream()
                .map(ChatRequest::systemPrompt)
                .filter(p -> p != null && !p.startsWith("你是电商问答平台的意图分类器"))
                .reduce((first, second) -> second)      // 取最后一次（工具轮可能有多跳）
                .orElseThrow(() -> new AssertionError("这一次问答没有发生生成调用"));
    }

    /**
     * 抓出<b>流式那一次</b>生成请求的 system prompt。
     *
     * <p>★ 判据是「走的是哪个方法」而不是 prompt 的内容：流式的生成走
     * {@code router.chatStream}，而分类走 {@code router.chat} ——
     * 所以盲抓 {@code chat} 的话，流式用例会拿到分类那一次的 prompt，
     * 症状是「这一次问答没有发生生成调用」。
     */
    private String streamingPrompt() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, atLeast(1)).chatStream(captor.capture(), any(ModelCallTrace.class), any());
        List<ChatRequest> all = captor.getAllValues();
        return all.get(all.size() - 1).systemPrompt();
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

    private ChatAskResponse ask(String sessionNo, Long userId) {
        return chatService.ask(new ChatAskRequest(sessionNo, QUESTION, null), userId);
    }

    // ============================================================
    // 一、★★★ 两条路都注入，而且 prompt 与落库是同一个字符串
    // ============================================================

    @Nested
    @DisplayName("一、★★★ 非流式：注入 + 落库 + 两处逐字相同")
    class NonStreaming {

        @Test
        @DisplayName("★★★ qa_log.affinity 非空，且它是 system prompt 的一个子串")
        void loggedAffinityIsLiterallyInThePrompt() {
            script(PLAN_KB, "推荐这几款。");
            stubRetrieval();
            long user = userBuying("贯通测试类目甲", 3);

            ChatAskResponse response = ask("s-affr-1", user);

            String logged = qaLogOf(response.traceId()).getAffinity();
            assertThat(logged)
                    .as("★★ 这一列的全部用途是让评测还原「模型当时看到了什么」——"
                            + "它为 null 的话，报告里的 faithfulness 会静默偏低")
                    .isNotNull();

            String prompt = generationPrompt();
            assertThat(prompt)
                    .as("★★★ 一句话钉住两件事：注入发生了，而且 prompt 与落库是【同一个字符串】。"
                            + "在落库那一层重新渲染一遍的话，两边就可能漂移，"
                            + "而漂移的症状只有「报告里的数字有点低」")
                    .contains(logged);
            assertThat(logged).contains("贯通测试类目甲").contains("共 3 件商品");
        }

        @Test
        @DisplayName("★★ 反面对照：同一个夹具，只把身份换成匿名 → 两边都没有")
        void anonymousGetsNothing() {
            script(PLAN_KB, "推荐这几款。");
            stubRetrieval();
            userBuying("贯通测试类目乙", 3);       // 有订单，但这次不带身份

            ChatAskResponse response = ask("s-affr-2", null);

            assertThat(qaLogOf(response.traceId()).getAffinity())
                    .as("★ 没有这一条，上面那条可能只是因为「这个字段总是有值」而通过")
                    .isNull();
            assertThat(generationPrompt()).doesNotContain("【这位用户的购买记录】");
        }

        @Test
        @DisplayName("★★ 订单不足 min-orders → 不注入（阈值真的在链路里生效）")
        void belowMinOrdersGetsNothing() {
            script(PLAN_KB, "推荐这几款。");
            stubRetrieval();
            long user = userBuying("贯通测试类目丙", 2);

            ChatAskResponse response = ask("s-affr-3", user);

            assertThat(qaLogOf(response.traceId()).getAffinity())
                    .as("★★ 2 笔订单说不出一句关于偏好的话 —— 强行注入会写出"
                            + "「你只买过 X」，而那是关于平台的一句假话")
                    .isNull();
            assertThat(generationPrompt()).doesNotContain("【这位用户的购买记录】");
        }

        @Test
        @DisplayName("★★★ 换一个人 → prompt 里是【他自己的】类目，不是别人的")
        void identityDoesNotBleedAcrossUsers() {
            script(PLAN_KB, "推荐这几款。");
            stubRetrieval();
            long alice = userBuying("贯通测试阿丽丝类目", 3);
            long bob = userBuying("贯通测试鲍勃类目", 3);

            ask("s-affr-4", alice);
            String alicePrompt = generationPrompt();
            ask("s-affr-5", bob);
            String bobPrompt = generationPrompt();

            assertThat(alicePrompt)
                    .as("★★ 身份只能来自 McpToolContext / CallContext，绝不能是工具参数（ADR-054）——"
                            + "而这条断言是「偏好没有串到别人身上」的判据")
                    .contains("贯通测试阿丽丝类目")
                    .doesNotContain("贯通测试鲍勃类目");
            assertThat(bobPrompt)
                    .as("★ 反面对照：没有这一半，上面那条可能只是因为"
                            + "「这段 prompt 里从来不会出现鲍勃的类目」而通过")
                    .contains("贯通测试鲍勃类目")
                    .doesNotContain("贯通测试阿丽丝类目");
        }
    }

    // ============================================================
    // 二、★★ 流式那条路（前端唯一在用的那条）
    // ============================================================

    @Nested
    @DisplayName("二、★★ 流式：同序、同判据")
    class Streaming {

        @Test
        @DisplayName("★★★ 流式路径同样注入、同样落库、同样逐字相同")
        void streamingAlsoInjects() {
            script(PLAN_KB);
            scriptStream("推荐这几款。");
            stubRetrieval();
            long user = userBuying("贯通测试流式类目", 3);

            CapturingSink sink = new CapturingSink();
            chatService.askStream(new ChatAskRequest("s-affr-6", QUESTION, null), sink, user,
                    CallContext.fresh());

            assertThat(sink.error).as("流式路径不该失败").isNull();
            String logged = qaLogOf(sink.startedTraceId).getAffinity();
            assertThat(logged)
                    .as("★★ 前端只用流式（api.js 里只有 SSE）—— "
                            + "「非流式对了」完全不能说明线上是对的")
                    .isNotNull();
            assertThat(streamingPrompt()).contains(logged);
        }

        @Test
        @DisplayName("★★ 反面对照：流式 + 匿名 → NULL")
        void streamingAnonymousGetsNothing() {
            script(PLAN_KB);
            scriptStream("推荐这几款。");
            stubRetrieval();

            CapturingSink sink = new CapturingSink();
            chatService.askStream(new ChatAskRequest("s-affr-7", QUESTION, null), sink, null,
                    CallContext.fresh());

            assertThat(qaLogOf(sink.startedTraceId).getAffinity()).isNull();
            assertThat(streamingPrompt()).doesNotContain("【这位用户的购买记录】");
        }
    }

    // ============================================================
    // 三、★ 澄清轮没有 prompt，所以它是 NULL
    // ============================================================

    @Nested
    @DisplayName("三、★ 澄清轮的诚实值是 NULL")
    class ClarifyTurn {

        @Test
        @DisplayName("★★ 澄清反问不生成回答 → 没有 prompt → affinity 必须是 NULL")
        void clarifyTurnHasNoPrompt() {
            script(PLAN_CLARIFY);
            long user = userBuying("贯通测试澄清类目", 3);

            ChatAskResponse response = ask("s-affr-8", user);

            assertThat(response.intent()).as("这一轮应该走澄清").isEqualTo("NEEDS_CLARIFICATION");
            assertThat(qaLogOf(response.traceId()).getAffinity())
                    .as("★★ 它这一轮【根本没有生成调用】，也就没有 prompt 可言。"
                            + "写成非 NULL 的话，评测会拿一段【模型从来没见过】的文字"
                            + "去算 faithfulness —— 而那正是这一列要防的事。"
                            + "★ 它是「位置放在澄清分支之后」这条约束的落点")
                    .isNull();
        }
    }
}
