package com.xbla.rag.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>请求体根本读不出来时，状态码是什么</b> —— 「坑 30 的另一半」。
 *
 * <h2>★★★ 这个类守的是一个【全项目每一个 POST 端点】都有的 bug</h2>
 *
 * <pre>
 *   POST /api/chat   body="{bad json"          → 500   应该 400
 *   POST /api/chat   Content-Type: text/plain  → 500   应该 415
 * </pre>
 *
 * <p>成因：{@code GlobalExceptionHandler} 末尾那条兜底的
 * {@code @ExceptionHandler(Exception.class)} 把 Spring <b>自己的</b>两个异常接走了：
 *
 * <pre>
 *   HttpMessageNotReadableException      请求体解析失败（畸形 JSON / 字段类型对不上）
 *   HttpMediaTypeNotSupportedException   媒体类型不支持
 * </pre>
 *
 * <p>★★ <b>而 {@code handleNoResource} 的注释里早就写下了正确的判据</b>：
 * 「任何想要特定状态码的异常都必须显式注册」。那一次修的是 <b>404</b> ——
 * <b>同一句话当时只兑现了一半。</b>
 *
 * <h2>★★ 危害不是「功能坏了」，是「信号坏了」</h2>
 *
 * <pre>
 *   ① 客户端分不清「我发的 JSON 是坏的」和「服务炸了」
 *   ② ★ 监控按 500 计数时，一次【客户端错误】被报成【服务故障】—— 会触发告警
 *   ③ 每次都写一行 ERROR + 堆栈，而这类请求每天都在发生
 * </pre>
 *
 * <h2>★★★ 它是怎么被发现的（这一条比 bug 本身值钱）</h2>
 *
 * <p>不是读代码看出来的 —— 是给 9.6b 的新端点写 HTTP 层用例时<b>顺手撞出来的</b>，
 * 而那条用例的本意只是「空请求体不该 500」。
 *
 * <p>⇒ <b>给新端点补 HTTP 层用例，会顺带体检整个 handler 链</b>：
 * 那些用例会走「请求根本进不到方法里」那几条路，而它们以前从来没有人走过。
 *
 * <h2>★ 为什么用 {@code /api/chat} 而不是那个新端点</h2>
 *
 * <p>因为这个 bug 是<b>跨端点</b>的。用一个<b>早已存在</b>的端点来验，
 * 这条用例就与「9.6b 那个新端点」完全无关 ——
 * 换掉那个端点、甚至删掉它，这条判据仍然成立。
 *
 * <h2>★★ 正-反对照</h2>
 *
 * <pre>
 *   坏 JSON      → 400 + 「请求体不是合法的 JSON」
 *   错的类型头   → 415 + 「Content-Type 不支持」   ← ★ 两个【不同】的码，
 *                                                    证明不是「什么都 400」
 * </pre>
 *
 * <p>而「兜底有没有被削弱」那一半由 {@code UnmappedPathMappingTest
 * #unexpectedExceptionIsStill500} 守着（真的内部错误仍然 500）——
 * 这里不重复它，但这一条和它是一对。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("请求体绑定失败 · 必须 400/415 不是 500")
class RequestBindingErrorMappingTest {

    /** 一个早就存在的端点。★ 刻意不用 9.6b 那个新端点，理由见类注释 */
    private static final String ENDPOINT = "/api/chat";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("★★ 畸形 JSON → 400（不是 500），且 body.code 也是 400")
    void malformedJsonIs400() throws Exception {
        mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"退货要几天\","))   // 半截 JSON
                .andExpect(status().isBadRequest())
                // ★ 两个数字都要对：HTTP 状态码给网关和监控，body.code 给前端
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体不是合法的 JSON"));
    }

    @Test
    @DisplayName("★ 字段类型对不上（不是 JSON 语法错，是绑定失败）→ 也是 400")
    void wrongFieldTypeIs400() throws Exception {
        // ★ 这一条和上一条【不是同一个异常路径】：上一条是语法错，
        //   这一条 JSON 本身合法、是 Jackson 绑定时类型对不上 ——
        //   两者都抛 HttpMessageNotReadableException，但成因不同
        mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": {\"nested\": 1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("★★ 错的 Content-Type → 415（不是 500，也不是 400）")
    void wrongContentTypeIs415() throws Exception {
        mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"question\":\"退货要几天\"}"))
                // ★★ 415 而不是 400 —— 这正是「两个不同的码」那一半：
                //    如果只注册了一个 handler 把什么都变成 400，这条会红
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(415))
                .andExpect(jsonPath("$.message").value(containsString("Content-Type")));
    }

    @Test
    @DisplayName("★ 消息里不泄露 Jackson 的解析细节（它带出错位置的原文片段）")
    void messageDoesNotLeakParseDetail() throws Exception {
        mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"内部字段名不该出现\" "))
                .andExpect(status().isBadRequest())
                // ★ Jackson 的原始消息会带上出错位置的原文 —— 那可能包含
                //   客户端发来的任何东西。同兜底 handler 那条「不返回原始消息」
                .andExpect(jsonPath("$.message").value(not(containsString("question"))));
    }

    @Test
    @DisplayName("★ 日志级别是 DEBUG 不是 ERROR —— 这类请求是正常事件，不该告警")
    void itIsNotTreatedAsAnAlarm() throws Exception {
        // ⚠️ 这一条断言不了日志级别（那是日志框架的事），它断言的是
        //    【可观测的后果】：响应体不能长得像「服务故障」。
        //    ★ 真要看级别，去 GlobalExceptionHandler 里看那两行 log.debug ——
        //      那是唯一出处，这里只是把「不该是 5xx」再钉一遍
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().is4xxClientError());
    }

    // ============================================================
    // 同一批审计抓出来的另外两条（2026-09-26）
    // ============================================================

    @Nested
    @DisplayName("三、用错方法 / 参数类型不对")
    class AuditFindings {

        /**
         * ★★ 这条的来历：做暴露面审计时，我用「错的 HTTP 方法」去探端点存不存在
         * （405 = 存在，404 = 不存在）—— <b>结果全是 500</b>。
         *
         * <p>★ 那条探测的<b>本意不是查 bug</b>，只是想确认「这个端点在不在」。
         * 同一天里同一个手法已经抓出两批了。
         */
        @Test
        @DisplayName("★★ 用错 HTTP 方法 → 405（不是 500）")
        void wrongMethodIs405() throws Exception {
            // ★ `/api/chat` 只收 POST（GET 的是 /api/chat/stream）——
            //   而且它是【早就存在】的端点，与本项目任何新端点无关
            mvc.perform(get("/api/chat"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value(405));
        }

        /**
         * ★★ 405 <b>必须</b>带 {@code Allow} 头（RFC 9110 §15.5.6）。
         *
         * <p>缺了它，调用方只能猜「到底该用什么方法」。
         * ★ Spring 已经把那个集合准备好了 —— 是<b>我们自己的</b>路由信息，
         * 回出去安全。
         */
        @Test
        @DisplayName("★★ 405 带 Allow 头（RFC 要求），且里面是 POST")
        void methodNotAllowedCarriesAllowHeader() throws Exception {
            mvc.perform(get("/api/chat"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(header().string("Allow", containsString("POST")));
        }

        @Test
        @DisplayName("★★ 查询参数类型不对 → 400（不是 500）")
        void badParamTypeIs400() throws Exception {
            // `limit` 是 int，传 abc ⇒ MethodArgumentTypeMismatchException
            mvc.perform(get("/api/chat/sessions").param("limit", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }

        /**
         * ★★ 消息里回<b>参数名</b>（我们自己的），<b>不回客户端传的那个值</b>。
         *
         * <p>把客户端发来的字符串原样回进 body 就是一次反射式内容注入 ——
         * 同这个类里那几条「不回 e.getMessage()」。
         */
        @Test
        @DisplayName("★★ 回参数名，绝不回客户端传的那个值")
        void messageNamesParameterButEchoesNoInput() throws Exception {
            mvc.perform(get("/api/chat/sessions").param("limit", "<script>alert(1)</script>"))
                    .andExpect(status().isBadRequest())
                    // ★ 参数名要回 —— 调用方立刻知道该改哪个
                    .andExpect(jsonPath("$.message").value(containsString("limit")))
                    // ★★ 而客户端发来的那个串一个字符都不能出现
                    .andExpect(jsonPath("$.message").value(not(containsString("script"))));
        }

        @Test
        @DisplayName("★ 反：合法参数 → 200（不是「什么都 400」）")
        void validParamIsFine() throws Exception {
            mvc.perform(get("/api/chat/sessions").param("limit", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
    }
}
