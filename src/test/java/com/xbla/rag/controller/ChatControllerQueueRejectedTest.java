package com.xbla.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.mcp.protocol.McpProtocol;
import com.xbla.rag.service.ChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 非流式 {@code POST /api/chat} <b>被排队层拒绝时</b>，HTTP 层到底长什么样（阶段 6.7）。
 *
 * <h2>★★★ 这条测试守的是「忙」和「坏了」在 HTTP 上必须是两回事</h2>
 *
 * <p>加 {@code QueueRejectedException} 和那个 handler 之前，被限流会落到
 * {@code GlobalExceptionHandler} 的兜底分支：
 *
 * <pre>
 *   HTTP 500 「服务内部错误，请稍后重试」
 * </pre>
 *
 * <p>而那是<b>一个具体的、会真实发生的错误归因</b>：
 *
 * <ul>
 *   <li>用户看到 500 会去截图报障、怀疑数据出问题了 —— 而系统其实好好的；</li>
 *   <li>运维看到 500 会去翻日志找 bug —— 而那里只有一个正常工作的限流器；</li>
 *   <li>★ 更糟的是：<b>真正的 500 会被这堆假的 500 淹没</b>，
 *       于是「错误率」这个指标彻底失去意义。</li>
 * </ul>
 *
 * <p>★ 而 <b>503 用一句话把这件事说清楚了</b>：「暂时忙，等一下再来」。
 * 加上 {@code Retry-After}，客户端连「等多久」都不用猜。
 *
 * <h2>★ 怎么构造出「一定被拒绝」—— 一个不需要任何准备的办法</h2>
 *
 * <p>通常要「先占住名额、再发请求、等它超时」，那要好几个步骤还不稳定。
 * 这里用了一组退化配置：
 *
 * <pre>
 *   permits   = 0   →  一个名额都没有，acquire.lua 永远授权不了
 *   max-queue = 0   →  队列也不让排，于是它【立刻】返回 QUEUE_FULL
 * </pre>
 *
 * <p>两个 0 合起来 = 「这个服务一点容量都没有」——
 * 于是<b>每一个请求都会被如实拒绝，而且立刻</b>，不用等任何超时。
 * ★ 这是刻意构造的实验条件，不是生产配置。
 *
 * <h2>⚠️ 它会被写一行 qa_log，所以必须自己清掉</h2>
 *
 * <p>被拒绝的请求会写一行 {@code status=4} 的 qa_log（这是阶段 6 的刻意设计：
 * 不写的话被限流的请求<b>在数据上完全不存在</b>）。而那一行是在
 * <b>{@code queue-} 线程</b>上写的，<b>{@code @Transactional} 回滚不到它</b>。
 *
 * <p>★ 这一行必须被清掉，理由比「干净」硬得多：
 * <b>{@code qa_log} 是阶段 7 评测数据的来源</b>。测试留下的行会进评测集，
 * 于是「准确率」里混进了一批「因为测试环境没容量所以没答上」的样本。
 *
 * <p>所以：请求带一个哨兵 {@code userId}，跑完按它删。
 */
@SpringBootTest(properties = {
        "xbla.ratelimit.enabled=true",
        "xbla.ratelimit.permits=0",          // ★ 见类注释第二节
        "xbla.ratelimit.max-queue=0",
        "xbla.ratelimit.key-prefix=xbla:rl:{http-503}",
        "xbla.ratelimit.channel=xbla:rl:http-503-events"
})
@AutoConfigureMockMvc
@DisplayName("ChatController · 被排队层拒绝时的 HTTP 契约")
class ChatControllerQueueRejectedTest {

    /**
     * 哨兵身份 —— <b>只为了能把自己写的那行 qa_log 删干净</b>。
     *
     * <p>★ 用一个明显不属于任何真实用户的值（{@code app_user} 里没有它），
     * 这样「按它删」这个动作不可能误伤真实数据。
     */
    private static final long SENTINEL_USER_ID = 999_999L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private QaLogMapper qaLogMapper;

    /**
     * ★ 桩掉 {@code ChatService} 不是为了控制它的行为（这个类里它一次都不该被调用），
     * 而是为了能<b>断言它一次都没被调用</b>。
     *
     * <p>那个断言才是重点：<b>「名额都没拿到，就不该碰模型」</b>——
     * 如果哪天有人在排队之前就调了 ChatService，这条会红。
     */
    @MockitoBean
    private ChatService chatService;

    @AfterEach
    void removeQaLogRowsWrittenByThisTest() {
        qaLogMapper.delete(new LambdaQueryWrapper<QaLog>()
                .eq(QaLog::getUserId, SENTINEL_USER_ID));
    }

    @Test
    @DisplayName("★★ 队列满 → HTTP 503 + Retry-After + 一句人话，【不是】500")
    void queueFullYieldsServiceUnavailable() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(McpProtocol.HEADER_USER_ID, String.valueOf(SENTINEL_USER_ID))
                        .content("{\"question\":\"退货要几天\"}"))

                .andExpect(status().isServiceUnavailable())

                // ★★ 这一条是【整个类存在的理由】。
                //   它变成 500 就意味着被限流被报成了「服务内部错误」。
                .andExpect(jsonPath("$.code").value(503))

                // ★ 消息要是那句已经写好的人话。空着、或者变成
                //   「服务内部错误，请稍后重试」，用户就分不清该等一会儿还是该报障。
                .andExpect(jsonPath("$.message").value(containsString("排队")))

                // ★ 「多久之后可以重试」是 503 能告诉客户端的最有用的一件事。
                //   没有它，客户端只能瞎猜，而一群客户端瞎猜的结果是
                //   它们会在同一瞬间一起涌回来。
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("★★ 名额都没拿到，就不该碰模型 —— 一次都不行")
    void modelIsNeverCalledWhenRejected() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(McpProtocol.HEADER_USER_ID, String.valueOf(SENTINEL_USER_ID))
                        .content("{\"question\":\"退货要几天\"}"))
                .andExpect(status().isServiceUnavailable());

        // ★★ 这条看着像废话，其实守的是一条【顺序】不变量：
        //   排队必须在调模型【之前】。反过来的话，排队就变成了一句空话 ——
        //   下游早就被打满了，排队只是在给已经造成的伤害计时。
        verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("★ 参数校验在排队【之前】 —— 非法请求不占任何队列位置")
    void validationHappensBeforeQueueing() throws Exception {
        // ★ 空问题会触发 @NotBlank。
        //   ★ 这条的价值在于顺序：@Valid 由 Spring 在进方法体之前执行，
        //     所以这个请求【根本没有进入排队层】。
        //     反过来（先排队再校验）会让每个手滑的请求都白占一个队列位置。
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(McpProtocol.HEADER_USER_ID, String.valueOf(SENTINEL_USER_ID))
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }
}
