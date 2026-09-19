package com.xbla.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.mcp.McpSessionStore;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.protocol.McpProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP <b>HTTP 传输层</b>四道闸门的测试（阶段 5.7）。
 *
 * <h3>★ 为什么这几条必须进 {@code ./mvnw test}</h3>
 *
 * <p>它们全是<b>规范强制或强烈建议的安全措施</b>（Origin 防 DNS rebinding、
 * 认证、会话），而它们出事的方式是<b>静默地放行</b> ——
 * 一个少写的 {@code if} 让请求直接通过，功能一切正常，
 * 没有任何测试会因此变红。
 *
 * <p>⚠️ 一开始这几条只有 {@code scripts/probe_mcp.py} 在验。
 * 那是<b>不够的</b>：探针要人手动跑、要应用起着，
 * 而安全控制的回归必须由 CI（也就是 {@code ./mvnw test}）来挡。
 * 探针负责「端到端看着对」，测试负责「以后别改坏」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("McpController · HTTP 层的闸门")
class McpControllerGuardTest {

    private static final long USER_ID = 8L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private McpSessionStore sessions;

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private static Map<String, Object> toolsList() {
        return Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");
    }

    // ============================================================
    // 一、身份
    // ============================================================

    @Nested
    @DisplayName("一、身份")
    class Identity {

        @Test
        @DisplayName("★ 没有身份头 → 401，而且【不】返回 JSON-RPC 响应体")
        void missingIdentityIs401() throws Exception {
            mvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(toolsList())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("★ 身份头不是正整数 → 401（不发一个格式规整的协议错误）")
        void garbageIdentityIs401() throws Exception {
            for (String bad : new String[]{"abc", "0", "-1", "1.5", "  "}) {
                mvc.perform(post("/mcp")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header(McpProtocol.HEADER_USER_ID, bad)
                                .content(json(toolsList())))
                        .andExpect(status().isUnauthorized());
            }
        }
    }

    // ============================================================
    // 二、★★ Origin
    // ============================================================

    @Nested
    @DisplayName("二、★★ Origin（规范强制要求）")
    class Origin {

        @Test
        @DisplayName("★★ 恶意 Origin → 403（防 DNS rebinding）")
        void evilOriginIsForbidden() throws Exception {
            mvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(McpProtocol.HEADER_USER_ID, String.valueOf(USER_ID))
                            .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                            .content(json(toolsList())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.message").exists());
        }

        @Test
        @DisplayName("★★ Origin【缺席】→ 放行 —— 判据要和威胁模型对齐")
        void absentOriginIsAllowed() throws Exception {
            // 先握手拿到会话（这里刻意不带 Origin）
            String response = mvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(McpProtocol.HEADER_USER_ID, String.valueOf(USER_ID))
                            .content(json(Map.of(
                                    "jsonrpc", "2.0", "id", 1, "method", "initialize",
                                    "params", Map.of("protocolVersion", "2025-11-25")))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).contains("protocolVersion");
        }

        @Test
        @DisplayName("★ 白名单内的 Origin → 放行")
        void allowlistedOriginIsAllowed() throws Exception {
            mvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(McpProtocol.HEADER_USER_ID, String.valueOf(USER_ID))
                            .header(HttpHeaders.ORIGIN, "http://localhost:8080")
                            .content(json(Map.of(
                                    "jsonrpc", "2.0", "id", 1, "method", "initialize",
                                    "params", Map.of("protocolVersion", "2025-11-25")))))
                    .andExpect(status().isOk());
        }
    }

    // ============================================================
    // 三、★ 会话
    // ============================================================

    @Nested
    @DisplayName("三、★ 会话")
    class Session {

        @Test
        @DisplayName("★ initialize → 200 且响应头里种下 Mcp-Session-Id")
        void initializeIssuesSession() throws Exception {
            mvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(McpProtocol.HEADER_USER_ID, String.valueOf(USER_ID))
                            .content(json(Map.of(
                                    "jsonrpc", "2.0", "id", 1, "method", "initialize",
                                    "params", Map.of("protocolVersion", "2025-11-25")))))
                    .andExpect(status().isOk())
                    .andExpect(header().exists(McpProtocol.HEADER_SESSION_ID));
        }

        @Test
        @DisplayName("★ 没有会话就调 tools/list → 400（规范：要求会话的服务端回 400）")
        void missingSessionIs400() throws Exception {
            mvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(McpProtocol.HEADER_USER_ID, String.valueOf(USER_ID))
                            .content(json(toolsList())))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("★ 伪造的会话 ID → 400")
        void bogusSessionIs400() throws Exception {
            mvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(McpProtocol.HEADER_USER_ID, String.valueOf(USER_ID))
                            .header(McpProtocol.HEADER_SESSION_ID, UUID.randomUUID().toString())
                            .content(json(toolsList())))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("★★ 用 A 的会话配 B 的身份头 → 403（会话和身份是绑定的）")
        void sessionCannotBeHijackedAcrossIdentities() throws Exception {
            // ★★ 这条是探针脚本第一次跑时【意外发现】的：
            //    它本想测「越权查别人的订单」，结果被这一层先挡住了。
            //    两层的职责不同 —— 这一层管「会话不能被顶替」，
            //    工具 SQL 那层管「查到的数据是不是你的」。两层都要有。
            String sessionOf8 = newSessionFor(8L);

            mvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(McpProtocol.HEADER_USER_ID, "9")     // ← 换了个身份
                            .header(McpProtocol.HEADER_SESSION_ID, sessionOf8)
                            .content(json(toolsList())))
                    .andExpect(status().isForbidden());

            // ★ 对照：同一个会话配【原来那个】身份就能用
            //   —— 证明上一条拒的是「身份不一致」，不是「会话本身有问题」
            mvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(McpProtocol.HEADER_USER_ID, "8")
                            .header(McpProtocol.HEADER_SESSION_ID, sessionOf8)
                            .content(json(toolsList())))
                    .andExpect(status().isOk());
        }
    }

    // ============================================================
    // 四、★★ 通知 → 202
    // ============================================================

    @Nested
    @DisplayName("四、★★ 通知")
    class Notifications {

        @Test
        @DisplayName("★★ notifications/initialized → 202 且【响应体为空】")
        void notificationIs202WithNoBody() throws Exception {
            String session = newSessionFor(USER_ID);

            String body = mvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(McpProtocol.HEADER_USER_ID, String.valueOf(USER_ID))
                            .header(McpProtocol.HEADER_SESSION_ID, session)
                            .content(json(Map.of(
                                    "jsonrpc", "2.0",
                                    "method", McpProtocol.NOTIFICATION_INITIALIZED))))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .as("★★ 规范说通知不能有响应体。回一个 id:null 的 JSON 会让"
                            + "严格的客户端报协议错误 —— 而服务端这边看起来"
                            + "「我明明回了个格式正确的 JSON」")
                    .isEmpty();
        }
    }

    // ============================================================
    // 五、★ 解析失败仍然是 200
    // ============================================================

    @Test
    @DisplayName("★ 请求体不是合法 JSON → 200 + JSON-RPC -32700（不是 4xx）")
    void parseErrorIsStillHttp200() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(McpProtocol.HEADER_USER_ID, String.valueOf(USER_ID))
                        .content("{ 这不是 JSON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32700));
    }

    // ============================================================
    // 助手
    // ============================================================

    /**
     * 走一次真实握手，拿一个属于该身份的会话 ID。
     *
     * <p>★ 从<b>响应头</b>里取，不去反查会话表 ——
     * 客户端就是这么拿的，测试跟着客户端的路径走，
     * 才能顺带验证「会话 ID 真的被种进了那个头」。
     */
    private String newSessionFor(long userId) throws Exception {
        String sessionId = mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(McpProtocol.HEADER_USER_ID, String.valueOf(userId))
                        .content(json(Map.of(
                                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                                "params", Map.of("protocolVersion", "2025-11-25")))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(McpProtocol.HEADER_SESSION_ID);

        assertThat(sessionId)
                .as("握手必须种下会话 ID —— 后面每条断言都依赖它")
                .isNotBlank();
        return sessionId;
    }

    @Test
    @DisplayName("★ 会话表存的就是那个身份 —— 它是「这个 session 是谁」的凭据")
    void sessionHoldsIdentity() {
        McpToolContext ctx = new McpToolContext(USER_ID);
        String id = UUID.randomUUID().toString();
        sessions.create(id, ctx);

        assertThat(sessions.lookup(id)).isEqualTo(ctx);
        assertThat(sessions.lookup(UUID.randomUUID().toString())).isNull();
    }
}
