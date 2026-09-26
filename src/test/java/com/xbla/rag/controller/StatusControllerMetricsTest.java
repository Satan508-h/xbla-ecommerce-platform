package com.xbla.rag.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 在线指标端点（阶段 9.6b）—— <b>形状与窗口参数的处置</b>。
 *
 * <h2>★★ 为什么它值得一个 HTTP 层用例</h2>
 *
 * <p>{@code MetricsQueryService} 那 29 条已经把<b>算得对不对</b>测透了。
 * 这里守的是另一件事：<b>这个端点在不在、参数怎么处置、响应长什么样</b>。
 *
 * <p>★ 判据同上一轮那条教训（{@code ChatReferenceNotFoundMappingTest}）：
 * <b>service 层全绿，和「这个端点能不能用」是两件事。</b>
 * 一个没注册上的映射、一个漏了的 {@code @RequestParam}，
 * 在那些用例里一个字都看不到。
 *
 * <h2>★★ 固定键集要在【响应】里也成立</h2>
 *
 * <p>{@code byStatus} / {@code byEventType} 的「值为 0 也在」在
 * {@code MetricsQueryServiceImplTest} 里从纯函数角度测过。
 * 这里补的是<b>它真的传到了 JSON 里</b> —— 中间任何一层丢键都不会让那条红。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("StatusController · 在线指标端点")
class StatusControllerMetricsTest {

    @Autowired
    private MockMvc mvc;

    @Nested
    @DisplayName("一、端点在不在")
    class Exists {

        @Test
        @DisplayName("★ 不带参数 → 200 + code 0 + 四块数据都在")
        void defaultCallWorks() throws Exception {
            mvc.perform(get("/api/status/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.window").value("24h"))
                    // ★ 三个组各自在 —— 少了任何一个，前端会渲染出一个空白块
                    .andExpect(jsonPath("$.data.traffic.questions").exists())
                    .andExpect(jsonPath("$.data.latency.totalN").exists())
                    .andExpect(jsonPath("$.data.behavior.citedReplies").exists())
                    .andExpect(jsonPath("$.data.notes").isArray());
        }

        @Test
        @DisplayName("★ 它和 /api/status/ratelimit 是同一个类里的两个端点，互不影响")
        void rateLimitEndpointStillWorks() throws Exception {
            mvc.perform(get("/api/status/ratelimit"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.permits").exists());
        }
    }

    @Nested
    @DisplayName("二、窗口参数")
    class Window {

        @Test
        @DisplayName("★★ 非法值 → 200（不是 400），window 换成缺省、requestedWindow 留原值")
        void illegalWindowIsReplacedNotRejected() throws Exception {
            mvc.perform(get("/api/status/metrics").param("window", "99y"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.window").value("24h"))
                    .andExpect(jsonPath("$.data.requestedWindow").value("99y"))
                    .andExpect(jsonPath("$.data.notes[0]").value(containsString("99y")));
        }

        @Test
        @DisplayName("★ 三个合法值都认，且 requestedWindow 与 window 相同（没有替换就不留痕）")
        void legalWindowsAreNotFlagged() throws Exception {
            for (String w : new String[]{"24h", "7d", "all"}) {
                mvc.perform(get("/api/status/metrics").param("window", w))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.window").value(w))
                        .andExpect(jsonPath("$.data.requestedWindow").value(w))
                        // ★ 合法值不该触发那条替换提示 ——
                        //   否则 note 那一栏在正常调用下也恒有噪声
                        .andExpect(jsonPath("$.data.notes[0]", not(containsString("不是合法取值"))));
            }
        }

        @Test
        @DisplayName("★ all 的 from 是 null（不设下界）")
        void allHasNullFrom() throws Exception {
            mvc.perform(get("/api/status/metrics").param("window", "all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.from").doesNotExist());
        }
    }

    @Nested
    @DisplayName("三、★★ 固定键集传到了 JSON 里")
    class FixedKeys {

        @Test
        @DisplayName("★★ byStatus 四个键都在（哪怕某个是 0）")
        void statusKeysPresentInJson() throws Exception {
            mvc.perform(get("/api/status/metrics").param("window", "all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.traffic.byStatus['1']").exists())
                    .andExpect(jsonPath("$.data.traffic.byStatus['2']").exists())
                    .andExpect(jsonPath("$.data.traffic.byStatus['3']").exists())
                    .andExpect(jsonPath("$.data.traffic.byStatus['4']").exists());
        }

        @Test
        @DisplayName("★★ byEventType 两个键都在（埋点上线前表是空的，也必须出现）")
        void eventTypeKeysPresentInJson() throws Exception {
            mvc.perform(get("/api/status/metrics").param("window", "all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.behavior.byEventType.ref_click").exists())
                    .andExpect(jsonPath("$.data.behavior.byEventType.feedback").exists());
        }
    }

    @Nested
    @DisplayName("四、★★ 口径跟着数字走")
    class Notes {

        @Test
        @DisplayName("★★ notes 里带着 ADR-083 那句「五段不相加等于 total」")
        void carriesTheLatencyCaveat() throws Exception {
            mvc.perform(get("/api/status/metrics").param("window", "all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.notes[?(@ =~ /.*不相加.*/)]").exists());
        }

        @Test
        @DisplayName("★★ notes 里明说 behavior 那一组没有历史、也没有「转化」")
        void carriesTheBehaviorCaveat() throws Exception {
            mvc.perform(get("/api/status/metrics").param("window", "all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.notes[?(@ =~ /.*埋点上线.*/)]").exists());
        }
    }
}
