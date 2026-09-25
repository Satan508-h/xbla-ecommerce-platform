package com.xbla.rag.controller;

import com.xbla.rag.service.ChatHistoryQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 请求一个不存在的路径 → <b>404，不是 500</b>（阶段 8 修的真 bug）。
 *
 * <h2>★★★ 这个 bug 是「一整个类的请求被错误地归了类」</h2>
 *
 * <p>实测（2026-09-24，容器里打不存在的路径）：
 *
 * <pre>
 *   $ curl -u user:pw http://host/api/debug/ratelimit/state
 *   500          ← 期望 404
 *
 *   ERROR GlobalExceptionHandler : 未预期的异常
 *   org.springframework.web.servlet.resource.NoResourceFoundException:
 *       No static resource api/debug/ratelimit/state.
 * </pre>
 *
 * <p>机制：Spring Boot 3.2 起，未匹配的路径<b>不是机械地回 404</b>，
 * 而是抛 {@code NoResourceFoundException}；而
 * {@code @ExceptionHandler(Exception.class)} 那个兜底把它接走 → 500。
 *
 * <h2>★★ 它是阶段 8 的验收判据的前置条件</h2>
 *
 * <p>阶段 8 要证明「公网访问 {@code /api/debug/**} 返回 404」
 * （那些探针是 {@code @Profile("local")} 的，生产上不该存在）。
 * 而 <b>500 让那条判据无法成立</b>：
 *
 * <pre>
 *   404 → 控制器【没有注册】     ✓ 正是我们要证明的
 *   500 → 说不清是「没注册」还是「注册了但炸了」   ✗ 判据失效
 * </pre>
 *
 * <h2>★★ 正-反对照</h2>
 *
 * <p>修法的风险是「顺手把真异常也变成 404」——那会让所有故障看起来像「路径不存在」。
 * 所以两个用例必须同时成立：
 *
 * <pre>
 *   不存在的路径        → 404
 *   存在的路径抛异常     → 500   ← ★ 兜底分支【没有】被削弱
 * </pre>
 *
 * <p>★ 少了第二条，一个「把 {@code Exception} 也映射成 404」的实现
 * 会让第一条通过 —— 而那个实现比原来的 bug 更糟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("不存在的路径 · 必须 404 不是 500")
class UnmappedPathMappingTest {

    /** 一个永远不存在的路径。★ 用明确的假名字，别蹭真实的接口前缀 */
    private static final String NOWHERE = "/api/definitely-not-a-real-endpoint";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatHistoryQueryService chatHistoryQueryService;

    @Test
    @DisplayName("★ 不存在的路径 → 404 且 body.code = 404")
    void unmappedPathIs404() throws Exception {
        mockMvc.perform(get(NOWHERE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("★★ 对照：存在的路径抛未预期异常 → 仍然 500（兜底没被削弱）")
    void unexpectedExceptionIsStill500() throws Exception {
        when(chatHistoryQueryService.listMessages(anyString()))
                .thenThrow(new IllegalStateException("模拟一个没人想过的故障"));

        mockMvc.perform(get("/api/chat/sessions/whatever/messages"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));
    }
}
