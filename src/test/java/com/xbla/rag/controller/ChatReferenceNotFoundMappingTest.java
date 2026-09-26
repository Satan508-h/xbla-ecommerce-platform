package com.xbla.rag.controller;

import com.xbla.rag.entity.KbChunk;
import com.xbla.rag.entity.KbDocument;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.KbChunkMapper;
import com.xbla.rag.mapper.KbDocumentMapper;
import com.xbla.rag.mapper.QaLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「引用取不到」能不能<b>原样到达 HTTP 层</b>变成 404（9.6b 前置）。
 *
 * <h2>★★★ 它守的是【父类那句承诺】，而那句话此前没有测试</h2>
 *
 * <p>{@code ResourceNotFoundException} 的类注释里写着：
 *
 * <pre>
 *   「加一个新的 not-found 场景」不该变成「记得也加一个 handler」——
 *   所以所有子类共用一个 handler：新增一个子类就自动被覆盖。
 * </pre>
 *
 * <p>★ 这句话是<b>设计意图</b>，而 9.6b 前置一口气加了两个子类
 * （{@code ChatReferenceNotFoundException} / {@code KbChunkNotFoundException}）。
 * 也就是说：这条承诺从今天起被真实依赖了，而它<b>一个测试都没有</b>。
 *
 * <p>失效的形态和 {@link ChatHistoryNotFoundMappingTest} 里写的一模一样 ——
 * 兜底的 {@code @ExceptionHandler(Exception.class)} 把 404 接走，
 * 用户看到「服务内部错误，请稍后重试」，而真实情况是「这条引用不属于这次回答」。
 * 并且：<b>{@code ChatReferenceQueryIntegrationTest} 那 12 条一条都不会红</b>
 * （它们直接调 service，压根不经过 HTTP 层）。
 *
 * <h2>★★★ 三种「取不到」，三条 message —— 这才是这个类的主戏</h2>
 *
 * <pre>
 *   ① 引用里没这片 → 「这次回答的引用不存在：trace=…, chunk=…」
 *   ② 没有这个 trace → 「链路详情不存在：…」
 *   ③ 正文没了       → 「切片正文不存在：…」   ← 不可达，见下
 * </pre>
 *
 * <p>★ 三者<b>都是 404</b>，所以光断言状态码<b>分不开它们</b>。
 * 而排查时你手上只有那句话 —— 于是这里对每个场景都断言
 * <b>该出现的那句出现</b>、且<b>别的那句不出现</b>。
 *
 * <p>⚠️ ② 那一句不是洁癖：把「这次回答没引用过它」写成「链路不存在」，
 * 会把一个<b>前端读错字段</b>的 bug（把 {@code no} 当成 {@code chunk_id}）
 * 显示成「记录过期了」—— 于是没人会去查前端。
 *
 * <h2>★★ 正-反对照</h2>
 *
 * <p>四个用例，判据两两成对：
 *
 * <pre>
 *   引用里真有这一片   → 200 + code 0 + 正文
 *   引用里没有这一片   → 404
 *   没有这个 traceId   → 404，但说的是【链路】
 *   （少了「真有 → 200」，上面三条在「什么请求都 404」的实现上也全绿）
 * </pre>
 *
 * <h2>★ 两个数字都要对</h2>
 *
 * <p>HTTP 状态码给网关、Nginx、监控看；{@code ApiResponse.code} 给前端看。
 * 前端 {@code unwrap()} 判的是 {@code code !== 0}，所以只对一个是真会出事的 ——
 * 这里两个都断言，沿用 {@code ChatHistoryNotFoundMappingTest} 的规矩。
 *
 * <p>⚠️ <b>③ 那一格刻意不测</b>：它要求对 {@code kb_chunk} 做<b>物理删除</b>，
 * 而全仓没有任何地方做这件事 —— 为一条用例引入它，代价比收益大。
 * 它由 {@code ChatReferenceQueryIntegrationTest.chunkGoneIsStill404} 从类型上钉住
 * （是 404 家族，不是 500）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("引用原文接口 · 三种取不到的映射")
class ChatReferenceNotFoundMappingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QaLogMapper qaLogMapper;

    @Autowired
    private KbChunkMapper chunkMapper;

    @Autowired
    private KbDocumentMapper documentMapper;

    private static final String MISSING_TRACE = "REF404-" + System.nanoTime() + "-不存在";

    // ============================================================
    // 夹具
    // ============================================================

    private Long newChunk(String content) {
        KbDocument doc = new KbDocument();
        doc.setDocNo("D-REF404-" + System.nanoTime());
        doc.setTitle("引用 404 测试文档");
        doc.setDocType(2);
        doc.setSourceType(1);
        doc.setStatus(3);
        documentMapper.insert(doc);

        KbChunk chunk = new KbChunk();
        chunk.setDocumentId(doc.getId());
        chunk.setChunkIndex((int) (System.nanoTime() % 100000));
        chunk.setContent(content);
        chunk.setDocType(2);
        chunkMapper.insert(chunk);
        return chunk.getId();
    }

    /** 造一行引用着 {@code chunkId} 的 qa_log，返回它的 traceId */
    private String newQaLogCiting(Long chunkId) {
        QaLog row = new QaLog();
        row.setTraceId("REF404-" + System.nanoTime() + "-有");
        row.setQuestion("退货要几天");
        row.setReferences("[{\"no\":1,\"chunk_id\":" + chunkId
                + ",\"document_id\":43,\"score\":0.9,\"heading_path\":\"售后FAQ\"}]");
        qaLogMapper.insert(row);
        return row.getTraceId();
    }

    // ============================================================
    // 一、正-反对照
    // ============================================================

    @Test
    @DisplayName("★★ 正：引用里真有这一片 → 200 + code 0 + 正文")
    void citedChunkReturns200() throws Exception {
        Long chunkId = newChunk("七天无理由退货，自签收次日起算。");
        String traceId = newQaLogCiting(chunkId);

        mockMvc.perform(get("/api/chat/refs/{t}/{c}", traceId, chunkId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.chunkId").value(chunkId))
                // ★ 标题取自 references 那一条（不是现查 kb_chunk）——
                //   这条纪律在 service 层另有专门用例，这里只确认它真的传到了 HTTP 响应里
                .andExpect(jsonPath("$.data.headingPath").value("售后FAQ"))
                .andExpect(jsonPath("$.data.content")
                        .value("七天无理由退货，自签收次日起算。"));
    }

    @Test
    @DisplayName("★★ 反：引用里没这一片 → 404，且 code 也是 404")
    void uncitedChunkMapsTo404() throws Exception {
        Long cited = newChunk("被引用的");
        Long notCited = newChunk("没被引用的，但它真的在 kb_chunk 里");
        String traceId = newQaLogCiting(cited);

        // ★★ notCited 是【真实存在】的切片。这一点是要点：
        //    如果实现省掉了引用校验直接去取正文，它就取得到 ——
        //    于是这里会是 200，这条用例红。封条就是靠这个成立的
        mockMvc.perform(get("/api/chat/refs/{t}/{c}", traceId, notCited))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ============================================================
    // 二、★★ 三种取不到，三条 message
    // ============================================================

    @Test
    @DisplayName("★★ 「没引用过」说的是【引用】，不是【链路】")
    void uncitedSaysReferenceNotTrace() throws Exception {
        Long cited = newChunk("被引用的");
        String traceId = newQaLogCiting(cited);

        mockMvc.perform(get("/api/chat/refs/{t}/{c}", traceId, cited + 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("引用")))
                // ★★ 这条是正-反对照的另一半：两者都 404，
                //    只有 message 分得开 —— 而排查时手上只有它
                .andExpect(jsonPath("$.message").value(not(containsString("链路详情"))));
    }

    @Test
    @DisplayName("★★ traceId 不存在说的是【链路详情】，不是【引用】")
    void unknownTraceSaysTraceNotReference() throws Exception {
        mockMvc.perform(get("/api/chat/refs/{t}/{c}", MISSING_TRACE, 1L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(containsString("链路详情")))
                .andExpect(jsonPath("$.message").value(not(containsString("这次回答的引用"))));
    }
}
