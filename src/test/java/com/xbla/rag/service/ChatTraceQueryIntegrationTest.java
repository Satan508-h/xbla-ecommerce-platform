package com.xbla.rag.service;

import com.xbla.rag.dto.TraceDetail;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.QaLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 技术细节查询（阶段 8）—— <b>五段延迟怎么算、以及「读不到」不许变成 0</b>。
 *
 * <h2>★★★ 这个类守的第一件事：老数据的 {@code sizes} 段是【不存在】的</h2>
 *
 * <p>{@code retrieval_detail.sizes} 是<b>阶段 7.4 才加进去的</b>。
 * 在那之前写进库的行根本没有这个段，而 {@code docs/10} 明确记了这件事
 * （「旧数据只能重跑才能对齐，重算报告没用」）。
 *
 * <pre>
 *   用 asInt(0) 兜底 → 老数据显示「召回 0 条」
 *                      ✗ 撒谎，而且看起来完全正常
 *   用 null         → 前端显示「不可得」
 *                      ✓ 读不到就是读不到
 * </pre>
 *
 * <p>★ 这是本项目那句纪律：<b>「读不到」（我们输入的问题）
 * 和「没有做过」（世界的事实）永远不许渲染成一个样子。</b>
 *
 * <h2>★★ 第二件事：「未归类」的算式，以及 null 在里面当 0 用的前提</h2>
 *
 * <p>{@code unclassified = total − queue − retrieval − llm}。
 * 而 {@code queue_ms} 实测 3641 行里 3637 行是 <b>null</b>（限流关着就没排队），
 * 所以 null 必须当 0，否则这一格几乎永远算不出来。
 *
 * <p>★ 但「null 当 0」是个危险默认 —— 它只在「null 意味着没发生」
 * 这个前提成立时才对。下面用<b>两条对照用例</b>把这个前提钉住：
 * 有 queue 时它要参与减法，没有时它等于 0。
 */
@SpringBootTest
@Transactional
@DisplayName("ChatTraceQueryService · 技术细节")
class ChatTraceQueryIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private ChatTraceQueryService traceQueryService;

    @Autowired
    private QaLogMapper qaLogMapper;

    private String nextTraceId() {
        return "TRACE-" + System.nanoTime() + "-" + SEQ.incrementAndGet();
    }

    /** 最小可用的一行：只填 NOT NULL 的列 */
    private QaLog newRow(String traceId) {
        QaLog row = new QaLog();
        row.setTraceId(traceId);
        row.setQuestion("退货要几天");
        qaLogMapper.insert(row);
        return row;
    }

    private QaLog insert(String traceId, java.util.function.Consumer<QaLog> mutate) {
        QaLog row = newRow(traceId);
        mutate.accept(row);
        qaLogMapper.updateById(row);
        return qaLogMapper.selectById(row.getId());
    }

    // ============================================================
    // 一、五段延迟
    // ============================================================

    @Nested
    @DisplayName("一、五段延迟")
    class Latency {

        @Test
        @DisplayName("★ 未归类 = total − queue − retrieval − llm")
        void unclassifiedIsTheRemainder() {
            String id = nextTraceId();
            insert(id, r -> {
                r.setQueueMs(120);
                r.setRetrievalLatencyMs(600);
                r.setRerankLatencyMs(380);
                r.setLlmLatencyMs(1100);
                r.setTotalLatencyMs(2700);
            });

            TraceDetail.Latency latency = traceQueryService.traceDetail(id).latency();

            assertThat(latency.queueMs()).isEqualTo(120);
            assertThat(latency.retrievalMs()).isEqualTo(600);
            assertThat(latency.rerankMs())
                    .as("★ 重排段【原样保留】—— 它是 retrieval 的一部分，"
                            + "所以上面那个减法里【不能】再减它一次（否则重复计算）")
                    .isEqualTo(380);
            assertThat(latency.llmMs()).isEqualTo(1100);
            assertThat(latency.unclassifiedMs())
                    .as("2700 − 120 − 600 − 1100 = 880。★ 这 880ms 主要是"
                            + "【意图分类那一次模型往返】—— 它进了 total 却不在任何一列里")
                    .isEqualTo(880);
        }

        @Test
        @DisplayName("★★ queue 为 null 时当 0 用（实测 99.9% 的行都是这样）")
        void nullQueueCountsAsZero() {
            String id = nextTraceId();
            insert(id, r -> {
                r.setQueueMs(null);             // ← 限流关着，没有排队
                r.setRetrievalLatencyMs(600);
                r.setLlmLatencyMs(1100);
                r.setTotalLatencyMs(2000);
            });

            TraceDetail.Latency latency = traceQueryService.traceDetail(id).latency();

            assertThat(latency.queueMs())
                    .as("★ 显示上原样保留 null —— 前端能看出「这次没排队」，"
                            + "而不是看到一个 0")
                    .isNull();
            assertThat(latency.unclassifiedMs())
                    .as("★ 算术上当 0：2000 − 0 − 600 − 1100 = 300。"
                            + "不做这个转换的话，这一格几乎永远算不出来，"
                            + "状态页就废了")
                    .isEqualTo(300);
        }

        @Test
        @DisplayName("★★ 对照：queue 有值时它【确实】参与减法")
        void nonNullQueueActuallySubtracts() {
            String id = nextTraceId();
            insert(id, r -> {
                r.setQueueMs(500);
                r.setRetrievalLatencyMs(600);
                r.setLlmLatencyMs(1100);
                r.setTotalLatencyMs(2700);
            });

            assertThat(traceQueryService.traceDetail(id).latency().unclassifiedMs())
                    .as("★ 少了这条对照，「null 当 0」那条可能只是因为"
                            + "【根本没做减法】而通过 —— 比如一个把 queue 直接忽略的实现")
                    .isEqualTo(500);            // 2700 − 500 − 600 − 1100
        }

        @Test
        @DisplayName("★ total 或 llm 缺失 → 未归类给 null，不编一个数")
        void missingInputsYieldNullNotAGuess() {
            String id = nextTraceId();
            insert(id, r -> {
                r.setRetrievalLatencyMs(600);
                r.setLlmLatencyMs(null);
                r.setTotalLatencyMs(2000);
            });

            assertThat(traceQueryService.traceDetail(id).latency().unclassifiedMs())
                    .as("★ 算不出来就是算不出来。给 0 会让它看起来像"
                            + "「所有时间都被归类了」，而那是另一个意思")
                    .isNull();
        }
    }

    // ============================================================
    // 二、★★★ sizes 缺失（老数据）
    // ============================================================

    @Nested
    @DisplayName("二、★★★ sizes 缺失的老数据")
    class MissingSizes {

        /** 阶段 7.4 之前写进去的行的形状：有 6 段，没有 sizes */
        private static final String OLD_DETAIL = """
                {"filter":{"reason":"no_declaration","applied":false,"doc_types":[],"pool_size":null},
                 "fused":[{"chunk_id":7,"rrf_score":0.03}],
                 "reranked":[{"chunk_id":7}],
                 "final_top_k":[{"chunk_id":7}],
                 "vector_hits":[{"chunk_id":7}],
                 "keyword_hits":[]}
                """;

        @Test
        @DisplayName("★★★ 五个条数全是 null，【不是 0】")
        void countsAreNullNotZero() {
            String id = nextTraceId();
            insert(id, r -> r.setRetrievalDetail(OLD_DETAIL));

            TraceDetail.Retrieval retrieval = traceQueryService.traceDetail(id).retrieval();

            assertThat(retrieval).isNotNull();
            assertThat(retrieval.vectorHits())
                    .as("★★★ 这条是这个测试类的核心。用 asInt(0) 兜底的话这里是 0 —— "
                            + "而 0 的意思是「向量路一条都没召回」，"
                            + "和「当时没记这个数」是两件事，"
                            + "且前者看起来完全正常，没人会去查")
                    .isNull();
            assertThat(retrieval.keywordHits()).isNull();
            assertThat(retrieval.fused()).isNull();
            assertThat(retrieval.reranked()).isNull();
            assertThat(retrieval.finalTopK()).isNull();
        }

        @Test
        @DisplayName("★ 对照：filter 段在，所以它照常给值")
        void filterSectionStillWorks() {
            String id = nextTraceId();
            insert(id, r -> r.setRetrievalDetail(OLD_DETAIL));

            TraceDetail.Retrieval retrieval = traceQueryService.traceDetail(id).retrieval();

            assertThat(retrieval.filterApplied())
                    .as("★ 老数据的 retrieval_detail 里【有】filter 段（5.4 就加了），"
                            + "所以它是 false 而不是 null —— 两条时间线不同")
                    .isFalse();
            assertThat(retrieval.filterReason()).isEqualTo("no_declaration");
        }
    }

    // ============================================================
    // 三、检索范围
    // ============================================================

    @Nested
    @DisplayName("三、检索范围")
    class RetrievalScope {

        /** 关掉意图过滤时那一行的形状：声明非空、但没被用上 */
        private static final String DISABLED_DETAIL = """
                {"filter":{"reason":"disabled_by_config","applied":false,
                           "doc_types":[2,4],"pool_size":null},
                 "sizes":{"vector_hits":20,"keyword_hits":3,"fused":21,
                          "reranked":5,"final_top_k":5}}
                """;

        @Test
        @DisplayName("★ 声明了但没下推：docTypes 非空 而 applied=false")
        void declaredButNotApplied() {
            String id = nextTraceId();
            insert(id, r -> r.setRetrievalDetail(DISABLED_DETAIL));

            TraceDetail.Retrieval retrieval = traceQueryService.traceDetail(id).retrieval();

            assertThat(retrieval.docTypes())
                    .as("★ doc_types 【如实记录调用方的声明】—— 它确实声明了 [2,4]，"
                            + "只是没被用上。这个区别就是 reason 那一列存在的理由")
                    .containsExactly(2, 4);
            assertThat(retrieval.filterApplied()).isFalse();
            assertThat(retrieval.filterReason())
                    .as("★ 阶段 7 专门为 A/B 加了这个取值，而不是复用 no_declaration —— "
                            + "否则「开关关掉了」和「本来就没声明」在报告里长得一样")
                    .isEqualTo("disabled_by_config");
        }

        @Test
        @DisplayName("★ 各段条数照常给值（有 sizes 段的新数据）")
        void sizesRoundTrip() {
            String id = nextTraceId();
            insert(id, r -> r.setRetrievalDetail(DISABLED_DETAIL));

            TraceDetail.Retrieval retrieval = traceQueryService.traceDetail(id).retrieval();

            assertThat(retrieval.vectorHits()).isEqualTo(20);
            assertThat(retrieval.keywordHits()).isEqualTo(3);
            assertThat(retrieval.fused()).isEqualTo(21);
            assertThat(retrieval.reranked()).isEqualTo(5);
            assertThat(retrieval.finalTopK()).isEqualTo(5);
        }

        @Test
        @DisplayName("★★ 没检索的行 → 整个 retrieval 是 null")
        void noRetrievalMeansNull() {
            String id = nextTraceId();
            insert(id, r -> r.setRetrievalDetail(null));      // 工具意图 / 澄清

            assertThat(traceQueryService.traceDetail(id).retrieval())
                    .as("★ 「没有发生」的诚实表达。给一个空的 retrieval 对象，"
                            + "和「检索跑了但零召回」序列化出来就分不清了 —— "
                            + "这是 ADR-041 那条纪律")
                    .isNull();
        }
    }

    // ============================================================
    // 四、不泄露 + 找不到
    // ============================================================

    @Nested
    @DisplayName("四、边界")
    class Boundaries {

        @Test
        @DisplayName("★ toolCalls 按真 JSON 返回（不是装着 JSON 的字符串）")
        void toolCallsAreParsed() {
            String id = nextTraceId();
            insert(id, r -> {
                r.setIntent("ORDER_LOGISTICS");
                r.setToolCalls("[{\"name\":\"query_order_status\",\"round\":1}]");
            });

            var detail = traceQueryService.traceDetail(id);

            assertThat(detail.toolCalls().isArray()).isTrue();
            assertThat(detail.toolCalls().get(0).get("name").asText())
                    .isEqualTo("query_order_status");
        }

        @Test
        @DisplayName("★ 没有工具调用时是 null，不是空数组")
        void noToolCallsIsNull() {
            String id = nextTraceId();
            insert(id, r -> r.setToolCalls(null));

            assertThat(traceQueryService.traceDetail(id).toolCalls()).isNull();
        }

        @Test
        @DisplayName("★ 降级事件原样带上，degraded 由它是否存在决定")
        void degradationIsCarried() {
            String id = nextTraceId();
            insert(id, r -> {
                r.setProvider("siliconflow");
                r.setModel("deepseek-ai/DeepSeek-V3.2");
                r.setDegradationEvents(
                        "[{\"from\":\"deepseek\",\"to\":\"siliconflow\",\"reason\":\"timeout\"}]");
                r.setCost(new BigDecimal("0.001234"));
            });

            var detail = traceQueryService.traceDetail(id);

            assertThat(detail.degraded()).isTrue();
            assertThat(detail.provider())
                    .as("★ 降级后 provider 不是 P0 —— 这是阶段 2 验收标准的直接证据")
                    .isEqualTo("siliconflow");
            assertThat(detail.degradationEvents().isArray()).isTrue();
            assertThat(detail.cost()).isEqualByComparingTo("0.001234");
        }

        @Test
        @DisplayName("★★ traceId 不存在 → 抛异常（由父类的 handler 转 404）")
        void unknownTraceThrows() {
            String missing = "TRACE-" + System.nanoTime() + "-根本不存在";

            assertThatThrownBy(() -> traceQueryService.traceDetail(missing))
                    .isInstanceOf(ChatTraceNotFoundException.class)
                    .hasMessageContaining(missing);
        }

        @Test
        @DisplayName("★ 找不到的异常是 ResourceNotFoundException 的子类 —— 一个 handler 覆盖全部")
        void notFoundExceptionSharesOneHandler() {
            assertThat(new ChatTraceNotFoundException("x"))
                    .as("★ 挂在基类上的 handler 才能覆盖所有「找不到」的场景。"
                            + "挂子类的话，下次加一个新的 not-found 场景就会漏注册，"
                            + "而症状是那个接口回 500")
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(new ChatSessionNotFoundException("y"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
