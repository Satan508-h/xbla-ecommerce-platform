package com.xbla.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.dto.UserEventRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 埋点上报端点（阶段 9.6b）—— <b>「它什么都不该报错」</b>。
 *
 * <h2>★★★ 这个类守的是【状态码】，而 service 层的用例守不到</h2>
 *
 * <p>{@code UserEventServiceIntegrationTest} 断言的是「丢不丢、掷什么」，
 * 它<b>压根不经过 HTTP 层</b>。而这里要守的是另一件事：
 *
 * <pre>
 *   一个坏掉的埋点请求，会不会让服务端回 400 / 500？
 * </pre>
 *
 * <p>★★ 会 —— 而且是在两个地方：
 *
 * <pre>
 *   ① @RequestBody 不加 required=false  → 空体抛 HttpMessageNotReadableException
 *                                          ⇒ 兜底 handler ⇒ 400/500
 *   ② 校验写成抛异常而不是返回 DISCARDED → 同上
 * </pre>
 *
 * <p>★ 而这两个失败都<b>不会在开发时暴露</b>：本地发的永远是一份合法 JSON。
 * 真实场景里它发生在「浏览器发了个我们没想到的东西」的时候 ——
 * 也就是最不该让服务端报错的时刻。
 *
 * <h2>★★ 正-反对照</h2>
 *
 * <pre>
 *   合法事件   → 200 + WRITTEN
 *   白名单外   → 200 + DISCARDED     ← ★ 也是 200，不是 400
 *   空请求体   → 200 + DISCARDED     ← ★ 不是 400
 * </pre>
 *
 * <p>少了前两条，「什么都回 200」的实现也能让后两条通过。
 *
 * <h2>⚠️ 有一格是 400，而且是刻意的</h2>
 *
 * <p><b>畸形 JSON</b>（比如 {@code {bad}）仍然回 400 —— 那是 Spring 在
 * <b>进入方法之前</b>抛的，拦不住。接受它：畸形 JSON 不是
 * 「我们的客户端发了点什么」，而是有人在手工打这个接口。
 * 下面有一条用例把它<b>写下来</b>，因为「我以为它不会 400」是一个
 * 会在排查时把人带偏的假设。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("EventController · 埋点上报的状态码")
class EventControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String body(UserEventRequest req) throws Exception {
        return objectMapper.writeValueAsString(req);
    }

    private UserEventRequest valid(String eventNo) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("chunkId", 348);
        payload.put("no", 1);
        return new UserEventRequest(eventNo, "ref_click", 8L, "S1", "T1", payload,
                OffsetDateTime.now());
    }

    // ============================================================
    // 正-反对照
    // ============================================================

    @Test
    @DisplayName("★ 正：合法事件 → 200 + WRITTEN")
    void validEventIsWritten() throws Exception {
        mvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(valid("EV-OK-" + System.nanoTime()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.outcome").value("WRITTEN"));
    }

    @Test
    @DisplayName("★★ 反：白名单外的类型 → 【200】+ DISCARDED，不是 400")
    void unknownTypeStillReturns200() throws Exception {
        UserEventRequest bad = new UserEventRequest("EV-BAD-" + System.nanoTime(),
                "product_click", 8L, null, null, null, OffsetDateTime.now());

        mvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(bad)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DISCARDED"));
    }

    @Test
    @DisplayName("★★ 空请求体 → 【200】+ DISCARDED（required=false 那一行的作用）")
    void emptyBodyReturns200() throws Exception {
        // ★ 少了 @RequestBody(required = false)，这一条会是 400
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DISCARDED"));
    }

    @Test
    @DisplayName("★ 缺幂等键 / 缺时间 → 200 + DISCARDED")
    void missingRequiredFieldsStillReturn200() throws Exception {
        UserEventRequest noKey = new UserEventRequest(null, "ref_click", 8L,
                null, null, null, OffsetDateTime.now());
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                        .content(body(noKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DISCARDED"));

        UserEventRequest noTime = new UserEventRequest("EV-NT-" + System.nanoTime(),
                "ref_click", 8L, null, null, null, null);
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                        .content(body(noTime)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DISCARDED"));
    }

    @Test
    @DisplayName("★ 同一个 eventNo 打两次 → 第二次 200 + DUPLICATE")
    void secondPostIsDuplicate() throws Exception {
        String eventNo = "EV-DUP-" + System.nanoTime();
        String json = body(valid(eventNo));

        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("WRITTEN"));

        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DUPLICATE"));
    }

    // ============================================================
    // ★ 那一格会 400，写下来
    // ============================================================

    @Test
    @DisplayName("⚠️ 畸形 JSON 仍然 400 —— 那是 Spring 在进方法【之前】抛的，拦不住")
    void malformedJsonIsStill400() throws Exception {
        // ★ 这条不是在要求它 400，是在【记录事实】。
        //   「我以为它不会 400」是一个会在排查时把人带偏的假设 ——
        //   同 docs/10 坑 20：错误分支只能报告你核实过的东西
        mvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★ 错的 Content-Type 也进不了方法（同上，记录事实）")
    void wrongContentTypeIsNotAccepted() throws Exception {
        mvc.perform(post("/api/events")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(body(valid("EV-CT-" + System.nanoTime()))))
                .andExpect(status().isUnsupportedMediaType());
    }
}
