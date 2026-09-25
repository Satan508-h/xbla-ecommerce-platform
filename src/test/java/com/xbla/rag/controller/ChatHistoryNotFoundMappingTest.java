package com.xbla.rag.controller;

import com.xbla.rag.entity.ChatMessage;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.service.ChatMessageService;
import com.xbla.rag.service.ChatSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「会话不存在」能不能<b>原样到达 HTTP 层</b>变成 404（阶段 8）。
 *
 * <h2>★★★ 这个类守的是一个【service 层永远看不到】的问题</h2>
 *
 * <p>{@code ChatHistoryQueryServiceImpl.listMessages} 抛
 * {@code ChatSessionNotFoundException} —— 这件事在 service 层是可见的、
 * 也有单测（{@code ChatHistoryQueryIntegrationTest.unknownSessionThrows}）。
 *
 * <p>但<b>抛出来之后会变成什么 HTTP 状态码</b>，service 层的测试一个字都管不着。
 * 而 {@code GlobalExceptionHandler} 里有一个<b>兜底的
 * {@code @ExceptionHandler(Exception.class)}</b>：
 *
 * <pre>
 *   不注册 handleSessionNotFound 的话：
 *       ChatSessionNotFoundException（一个 RuntimeException）
 *           → 没有任何 handler 匹配它
 *           → 落到兜底分支 → HTTP 500「服务内部错误，请稍后重试」
 *
 *   ✗ 用户看到的是「服务坏了」，而真实情况是「你手上那个链接过期了」
 * </pre>
 *
 * <p>★ 顺带守一个容易误会的点：<b>换成 Spring 自带的
 * {@code ResponseStatusException(HttpStatus.NOT_FOUND)} 也没用</b>。
 * 解析异常的顺序是 {@code ExceptionHandlerExceptionResolver}（本项目的
 * {@code @RestControllerAdvice}）→ {@code ResponseStatusExceptionResolver}
 * → {@code DefaultHandlerExceptionResolver}，所以自带 404 语义的异常
 * <b>在到达 Spring 自己的解析器之前就被兜底接走了</b>。
 * 想要特定状态码只能显式注册 —— 这条注释里写的判断，由下面的用例来证明。
 *
 * <h2>★★ 正-反对照</h2>
 *
 * <p>两个用例<b>唯一的差别是 sessionNo 存不存在</b>：
 *
 * <pre>
 *   存在的   → 200 且 code = 0
 *   不存在的 → 404 且 code = 404
 * </pre>
 *
 * <p>★ 少了第二个，第一个证明不了什么；<b>少了第一个，
 * 「所有请求都回 404」也能让第二个通过</b>。
 *
 * <h2>★ 两个数字都要对</h2>
 *
 * <p>HTTP 状态码给网关、Nginx、监控看；{@code ApiResponse.code} 给前端看。
 * 只对一个的接口会让其中一方得出相反的结论 —— 所以这里两个都断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("会话历史接口 · 会话不存在的映射")
class ChatHistoryNotFoundMappingTest {

    private static final String MISSING = "HIST404-" + System.nanoTime() + "-不存在";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private ChatMessageService chatMessageService;

    private ChatSession newSessionWithOneMessage() {
        ChatSession s = new ChatSession();
        s.setSessionNo("HIST404-" + System.nanoTime() + "-有");
        s.setTitle("有消息的会话");
        s.setMessageCount(2);
        s.setStatus(1);
        chatSessionService.save(s);

        ChatMessage m = new ChatMessage();
        m.setSessionId(s.getId());
        m.setRole(1);
        m.setContent("退货要几天");
        chatMessageService.save(m);
        return s;
    }

    @Test
    @DisplayName("★★ 会话不存在 → HTTP 404，且 body 里的 code 也是 404")
    void unknownSessionMapsTo404() throws Exception {
        mockMvc.perform(get("/api/chat/sessions/{sessionNo}/messages", MISSING))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("★★ 对照：会话存在 → 200 且 code = 0（不是「什么都回 404」）")
    void existingSessionMapsTo200() throws Exception {
        ChatSession s = newSessionWithOneMessage();

        mockMvc.perform(get("/api/chat/sessions/{sessionNo}/messages", s.getSessionNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[0].content").value("退货要几天"));
    }

    @Test
    @DisplayName("★ 会话列表端点也活着（200 + code 0 + data 是数组）")
    void sessionListEndpointWorks() throws Exception {
        mockMvc.perform(get("/api/chat/sessions").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }
}
