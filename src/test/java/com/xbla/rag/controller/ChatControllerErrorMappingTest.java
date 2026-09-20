package com.xbla.rag.controller;

import com.xbla.rag.client.ModelCallException;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.service.CallContext;
import com.xbla.rag.service.ChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>{@code submitAndWait} 抛出来的异常，能不能原样到达 HTTP 层</b>（阶段 6.7）。
 *
 * <h2>★★★ 这个类守的是一个【没有一个单元测试能发现】的 bug</h2>
 *
 * <p>非流式路径把工作丢给 {@code answer-} 线程，然后用一个
 * {@code CompletableFuture} 把结果（或异常）接回来。
 * 而 <b>{@code CompletableFuture} 会把异常裹进 {@code ExecutionException}</b>。
 *
 * <p>问题在于：{@code @ExceptionHandler} 是<b>按异常类型匹配</b>的。
 *
 * <pre>
 *   ChatServiceImpl 抛出 ModelCallException（余额不足）
 *       → future 里存的是 ExecutionException(cause = ModelCallException)
 *       → 如果原样往外抛
 *       → Spring 找不到匹配的 handler（它只认 ModelCallException）
 *       → 落到兜底分支 → HTTP 500「服务内部错误，请稍后重试」
 *
 *   ✗ GlobalExceptionHandler 里那条「余额不足 → 503 + 一句人话」的规则【白写了】
 *   ✗ 用户看到的是一句没头没脑的 500
 * </pre>
 *
 * <p>★ 而这个 bug 的形态特别隐蔽：
 *
 * <ul>
 *   <li><b>不会有异常漏出来</b> —— 500 看起来就是个「合理的失败」；</li>
 *   <li><b>不会有单元测试变红</b> —— 那些测试直接调 service，压根不经过 HTTP 层；</li>
 *   <li><b>演示时也不会发现</b> —— 演示用的是正常请求，不会抛异常。</li>
 * </ul>
 *
 * <p>只有<b>真的发一个会触发领域异常的 HTTP 请求</b>才会看到。
 * 这就是这个类的全部价值。
 *
 * <h2>★★ 正-反对照是怎么做的</h2>
 *
 * <p>两个测试<b>唯一的差别是异常的类型</b>，其余完全一样：
 *
 * <pre>
 *   领域异常（ModelCallException） → 必须 503，而且是【它自己那句话】
 *   非领域异常（RuntimeException） → 必须 500
 * </pre>
 *
 * <p>★ 少了第二条的话，第一条可能是恒真的 —— 比如某个把什么都变成 503 的实现。
 * 而有了第二条，「类型有没有被保住」这件事才真的被测到了。
 *
 * <p>⚠️ <b>把 {@code unwrap} 换成「包一层再抛」（{@code new IllegalStateException(..., e)}）
 * 是这里最可能发生的退化</b> —— 那个改动会<b>只</b>让第一个测试变红。
 *
 * <h2>★ 桩掉 ChatService，不花钱也不碰数据库</h2>
 *
 * <p>{@code @MockitoBean ChatService} 之后 {@code ChatServiceImpl} 根本不会被创建，
 * 所以模型调用、{@code qa_log} 写入、检索全都不发生。
 * 而桩出来的异常<b>确实是从 {@code answer-} 线程经 future 传回来的</b> ——
 * 那正是我们要测的那一段路。
 */
@SpringBootTest(properties = {
        // ★ 名额给够，让请求【不排队】直接跑到 work —— 这个类测的不是排队
        "xbla.ratelimit.permits=8",
        "xbla.ratelimit.key-prefix=xbla:rl:{http-err}",
        "xbla.ratelimit.channel=xbla:rl:http-err-events"
})
@AutoConfigureMockMvc
@DisplayName("ChatController · 领域异常必须原样到达 handler")
class ChatControllerErrorMappingTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chatService;

    private void stubThrow(RuntimeException toThrow) {
        when(chatService.ask(any(ChatAskRequest.class), any(), any(CallContext.class)))
                .thenThrow(toThrow);
    }

    private org.springframework.test.web.servlet.ResultActions postQuestion() throws Exception {
        return mvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"退货要几天\"}"));
    }

    // ============================================================
    // 正-反对照
    // ============================================================

    @Test
    @DisplayName("★★ 正：领域异常必须原样到达 handler —— 503 且是【它自己那句话】")
    void domainExceptionKeepsItsOwnMapping() throws Exception {
        // AUTH → handler 映射成 503 + 「模型服务认证失败，请联系管理员检查密钥配置」
        stubThrow(ModelCallException.fromHttpStatus(401, "", "deepseek", "deepseek-flash"));

        postQuestion()
                // ★★ 如果 unwrap 被去掉 / 换成「包一层再抛」，
                //   这里会变成 500 —— 就是这一条在守它。
                .andExpect(status().isServiceUnavailable())

                // ★ 而且必须是【那条规则自己写的话】。只断言状态码是不够的：
                //   万一将来有人给兜底分支也加上 503，状态码就对不上了。
                .andExpect(jsonPath("$.message")
                        .value(containsString("认证失败")));
    }

    @Test
    @DisplayName("★★ 反：不是领域异常的，必须还是 500 —— 证明上面那条不是因为「什么都 503」")
    void nonDomainExceptionStillFallsThroughTo500() throws Exception {
        stubThrow(new IllegalStateException("这是 work 内部一个普通的 bug"));

        postQuestion()
                // ★★ 这条是让上面那条有意义的那个。
                //   它证明「503」这个结果是【由异常类型决定的】，
                //   而不是「这个 handler 把什么都接走了」。
                //
                //   ⚠️ 顺带也守住了兜底分支的本职：
                //     真的出了 bug 时，用户该看到的是 500（去找我们），
                //     不是 503（等一下再来）—— 后者会让用户一直重试一个永远失败的东西。
                .andExpect(status().isInternalServerError())

                // ★ 兜底分支【不能】把原始消息透出去 ——
                //   它可能含表名、类名、第三方地址。
                .andExpect(jsonPath("$.message").value("服务内部错误，请稍后重试"));
    }
}
