package com.xbla.rag.service;

import com.xbla.rag.dto.MetricsSnapshot;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.entity.UserEvent;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.mapper.UserEventMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在线指标聚合（阶段 9.6b）—— <b>分母怎么算、什么不算进去、窗口怎么切</b>。
 *
 * <h2>★★★ 全部断言都是【差值】，不是绝对值</h2>
 *
 * <p>因为聚合 SQL 扫的是<b>整张表</b>，而库里本来就有几百行真实数据。
 * 断言「提问数 == 1」是错的（也从来不会绿）。
 *
 * <p>⇒ 每条用例先取一个基线快照，插自己的夹具，再取一次，
 * <b>断言差</b>。这样它在本机、在干净库、在别人的机器上都成立。
 *
 * <h2>★★ 三条最先被写坏的地方</h2>
 *
 * <pre>
 *   ① 分母把 [] 也算进去  → 率凭空变小，而它看起来只是「用户不太点」
 *   ② 分子不做交集        → 率可能 &gt; 1（跨窗口边界的点击）
 *   ③ 忘了排评测流量      → 评测跑一轮就把在线指标盖掉，
 *                           而那是本项目【已经踩过】的那个坑（ADR-086）
 * </pre>
 */
@SpringBootTest
@Transactional
@DisplayName("MetricsQueryService · 在线指标")
class MetricsQueryServiceIntegrationTest {

    private static final String WINDOW_ALL = "all";
    private static final String WINDOW_24H = "24h";

    @Autowired
    private MetricsQueryService metricsQueryService;

    @Autowired
    private QaLogMapper qaLogMapper;

    @Autowired
    private UserEventMapper userEventMapper;

    // ============================================================
    // 夹具
    // ============================================================

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    /**
     * 插一行 {@code qa_log}，<b>带时序列</b>（= 真实数据里 {@code status=1} 的形状）。
     *
     * <p>★ {@code createdAt} 显式给值 —— {@code MybatisPlusMetaObjectHandler}
     * 用的 {@code strictInsertFill} <b>不覆盖手动设过的值</b>
     * （那个类自己的注释里写着这条）。窗口用例全靠它。
     */
    private String insertQa(String references, Integer status, String evalRunId,
                            OffsetDateTime createdAt) {
        return insertQa(references, status, evalRunId, createdAt, true);
    }

    /**
     * @param withTimings 要不要写时序列。
     *                    ★ 传 {@code false} 会造出一个<b>真实数据里不存在</b>的形状
     *                    （{@code status=1} 却没写时序列，实测非评测 164 行里
     *                    三个计数完全相等）。只有一条用例刻意用它 ——
     *                    为了把「{@code totalN} 是样本数不是行数」这句话钉住。
     */
    private String insertQa(String references, Integer status, String evalRunId,
                            OffsetDateTime createdAt, boolean withTimings) {
        QaLog row = new QaLog();
        row.setTraceId(unique("METRICS-TRACE"));
        row.setQuestion("退货要几天");
        row.setReferences(references);
        row.setStatus(status);
        row.setEvalRunId(evalRunId);
        row.setCreatedAt(createdAt);
        if (withTimings) {
            row.setQueueMs(3);
            row.setRetrievalLatencyMs(120);
            row.setRerankLatencyMs(80);
            row.setLlmLatencyMs(900);
            row.setTotalLatencyMs(1500);
        }
        qaLogMapper.insert(row);
        return row.getTraceId();
    }

    /** 插一行「有引用、status=1、非评测」的问答 —— 大多数用例的基准物 */
    private String insertCitedReply() {
        return insertQa("[{\"no\":1,\"chunk_id\":348,\"document_id\":43,\"score\":0.9}]",
                1, null, OffsetDateTime.now());
    }

    private void insertEvent(String type, String traceId, String payloadJson) {
        UserEvent row = new UserEvent();
        row.setEventNo(unique("EV"));
        row.setEventType(type);
        row.setTraceId(traceId);
        row.setPayload(payloadJson);
        // ★ 截到微秒：PG 的 timestamptz 只到微秒，Java 到纳秒（实测差过 400ns）
        row.setOccurredAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        userEventMapper.insert(row);
    }

    private MetricsSnapshot snap(String window) {
        return metricsQueryService.snapshot(window);
    }

    private long cited(String window) {
        return snap(window).behavior().citedReplies();
    }

    private long clicked(String window) {
        return snap(window).behavior().clickedCitedReplies();
    }

    // ============================================================
    // 一、分母：什么算「有引用的回答」
    // ============================================================

    @Nested
    @DisplayName("一、分母")
    class Denominator {

        /**
         * ★★★ 三种形状里只有一种进分母。
         *
         * <p>而且这一条<b>顺带把那个 {@code <> '[]'::jsonb} 跑到了</b> ——
         * 否则它是一段「只被写过、没被跑过」的 SQL
         * （本项目对那种代码的态度：和没写过在验收上没区别）。
         */
        @Test
        @DisplayName("★★ 三种形状：NULL 不算、[] 不算、非空数组算")
        void onlyNonEmptyArrayCounts() {
            long before = cited(WINDOW_ALL);

            insertQa(null, 1, null, OffsetDateTime.now());                 // 没检索 / 没召回
            insertQa("[]", 1, null, OffsetDateTime.now());                 // ★ 空数组：不算
            insertQa("[{\"no\":1,\"chunk_id\":1}]", 1, null, OffsetDateTime.now()); // 算

            assertThat(cited(WINDOW_ALL) - before)
                    .as("★ 只有第三种进分母 —— [] 是「没有引用可点」，不是「有一条引用」")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("★ 反面：三段合起来只 +1，不是 +2 也不是 +3")
        void denominatorIsNotOvercounting() {
            long before = cited(WINDOW_ALL);
            insertQa(null, 1, null, OffsetDateTime.now());
            insertQa("[]", 1, null, OffsetDateTime.now());
            insertQa("[]", 1, null, OffsetDateTime.now());

            assertThat(cited(WINDOW_ALL) - before).isZero();
        }

        @Test
        @DisplayName("★★ 评测流量【不进】任何统计（ADR-086 推广到每一个聚合）")
        void evalTrafficIsExcluded() {
            long beforeQ = snap(WINDOW_ALL).traffic().questions();
            long beforeCited = cited(WINDOW_ALL);

            insertQa("[{\"no\":1,\"chunk_id\":1}]", 1, "some-eval-run", OffsetDateTime.now());
            insertQa("[{\"no\":1,\"chunk_id\":1}]", 1, "some-eval-run", OffsetDateTime.now());

            assertThat(snap(WINDOW_ALL).traffic().questions() - beforeQ)
                    .as("★ 忘了这一条，跑一轮评测就把在线指标盖掉")
                    .isZero();
            assertThat(cited(WINDOW_ALL) - beforeCited).isZero();
        }
    }

    // ============================================================
    // 二、★★★ 分子必须是分母的子集
    // ============================================================

    @Nested
    @DisplayName("二、★★★ 分子 ⊂ 分母")
    class Numerator {

        /**
         * ★★★ 全类最值钱的一条：点击了一个<b>没有引用的回答</b>，分子<b>不该涨</b>。
         *
         * <p>不涨的理由不是「不该点」，是那个回答<b>根本不在分母里</b> ——
         * 不计入的话率恒 ≤ 1；计入的话率会<b>大于 1</b>，
         * 而那是一个不可能的值，会一路印到界面上。
         */
        @Test
        @DisplayName("★★ 点了「没有引用的回答」→ 分子不动（否则率会 > 1）")
        void clickOnUncitedReplyDoesNotCount() {
            String uncitedTrace = insertQa(null, 1, null, OffsetDateTime.now());
            long before = clicked(WINDOW_ALL);

            insertEvent("ref_click", uncitedTrace, "{\"chunkId\":1,\"no\":1}");

            assertThat(clicked(WINDOW_ALL) - before)
                    .as("★ 这条回答不在分母里，所以它的点击也不能进分子")
                    .isZero();
        }

        @Test
        @DisplayName("★ 正面：点了有引用的回答 → 分子 +1、分母 +1、率随之变化")
        void clickOnCitedReplyCounts() {
            long citedBefore = cited(WINDOW_ALL);
            long clickedBefore = clicked(WINDOW_ALL);

            String traceId = insertCitedReply();
            insertEvent("ref_click", traceId, "{\"chunkId\":348,\"no\":1}");

            MetricsSnapshot after = snap(WINDOW_ALL);
            assertThat(after.behavior().citedReplies() - citedBefore).isEqualTo(1);
            assertThat(after.behavior().clickedCitedReplies() - clickedBefore).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 同一条回答点两次 → 分子只 +1（按【回答】去重，不是按点击）")
        void repeatedClicksCountOnce() {
            String traceId = insertCitedReply();
            long before = clicked(WINDOW_ALL);

            insertEvent("ref_click", traceId, "{\"chunkId\":348,\"no\":1}");
            insertEvent("ref_click", traceId, "{\"chunkId\":348,\"no\":2}");

            assertThat(clicked(WINDOW_ALL) - before)
                    .as("率量的是「有多少回答被点开过」，不是「点了几下」")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("★ 没有 traceId 的点击（历史消息那一格）→ 不进分子")
        void clickWithoutTraceDoesNotCount() {
            long before = clicked(WINDOW_ALL);
            insertEvent("ref_click", null, "{\"chunkId\":1,\"no\":1}");

            assertThat(clicked(WINDOW_ALL) - before).isZero();
        }

        /**
         * ★★ 结构性的：率<b>要么是 null，要么 ≤ 1</b>。
         *
         * <p>⚠️ <b>这条断言的局限要写清楚</b>：它只有在「分母真的为 0」时
         * 才对「分母 0 却返回 0」那个实现有分辨力 —— 而分母是全局的，
         * 我控制不了它为 0。所以这里同时断言<b>一致性</b>
         * （rate == clicked/cited），那是任何数据上都有意义的判据。
         */
        @Test
        @DisplayName("★ 率恒 ≤ 1，且与分子分母自洽（分母 0 ⇒ null）")
        void rateIsBoundedAndConsistent() {
            MetricsSnapshot s = snap(WINDOW_ALL);
            var b = s.behavior();

            if (b.citedReplies() == 0) {
                assertThat(b.referenceClickRate())
                        .as("分母为 0 时是 null —— 那一刻我们不知道有没有人点")
                        .isNull();
            } else {
                assertThat(b.referenceClickRate())
                        .isNotNull()
                        .isLessThanOrEqualTo(1.0)
                        .isEqualTo((double) b.clickedCitedReplies() / b.citedReplies());
            }
        }
    }

    // ============================================================
    // 三、窗口
    // ============================================================

    @Nested
    @DisplayName("三、窗口")
    class Windows {

        @Test
        @DisplayName("★★ 三天前的行：24h 看不到，all 看得到")
        void oldRowIsOutside24hButInsideAll() {
            long before24 = snap(WINDOW_24H).traffic().questions();
            long beforeAll = snap(WINDOW_ALL).traffic().questions();

            OffsetDateTime old = OffsetDateTime.now().minusDays(3);
            insertCitedReplyAt(old);

            assertThat(snap(WINDOW_24H).traffic().questions() - before24).isZero();
            assertThat(snap(WINDOW_ALL).traffic().questions() - beforeAll)
                    .as("★ 差值断言：all 必须看到它，否则上面那条可能是「什么都没插进去」")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("★ 刚刚的行：两个窗口都看得到")
        void freshRowIsInsideBoth() {
            long before24 = snap(WINDOW_24H).traffic().questions();
            insertCitedReply();

            assertThat(snap(WINDOW_24H).traffic().questions() - before24).isEqualTo(1);
        }

        private void insertCitedReplyAt(OffsetDateTime at) {
            insertQa("[{\"no\":1,\"chunk_id\":348}]", 1, null, at);
        }
    }

    // ============================================================
    // 四、固定键集与坏数据
    // ============================================================

    @Nested
    @DisplayName("四、固定键集与坏数据")
    class Shapes {

        @Test
        @DisplayName("★★ 四个 status 键恒在 —— 这一格没被走到也要出现")
        void statusKeysAlwaysPresent() {
            Map<String, Long> byStatus = snap(WINDOW_ALL).traffic().byStatus();

            assertThat(byStatus).containsKeys("1", "2", "3", "4");
        }

        @Test
        @DisplayName("★★ 事件类型键恒在（表是空的时候也必须出现）")
        void eventTypeKeysAlwaysPresent() {
            assertThat(snap(WINDOW_ALL).behavior().byEventType())
                    .containsKeys("ref_click", "feedback");
        }

        @Test
        @DisplayName("★ 票型读不出来的 feedback → 进 feedbackOther，不并进 👍/👎")
        void voteLessFeedbackGoesToOther() {
            long beforeOther = snap(WINDOW_ALL).behavior().feedbackOther();
            long beforeUp = snap(WINDOW_ALL).behavior().feedbackUp();

            insertEvent("feedback", null, "{}");              // 没有 vote
            insertEvent("feedback", null, null);              // 连 payload 都没有

            assertThat(snap(WINDOW_ALL).behavior().feedbackOther() - beforeOther)
                    .as("★ 并进 up/down 会把「读不出来」混进「用户不满意」")
                    .isEqualTo(2);
            assertThat(snap(WINDOW_ALL).behavior().feedbackUp() - beforeUp).isZero();
        }

        @Test
        @DisplayName("★ 正常的 👍 / 👎 各归各位")
        void votesAreBucketed() {
            long upBefore = snap(WINDOW_ALL).behavior().feedbackUp();
            long downBefore = snap(WINDOW_ALL).behavior().feedbackDown();

            insertEvent("feedback", null, "{\"vote\":\"up\"}");
            insertEvent("feedback", null, "{\"vote\":\"down\"}");

            assertThat(snap(WINDOW_ALL).behavior().feedbackUp() - upBefore).isEqualTo(1);
            assertThat(snap(WINDOW_ALL).behavior().feedbackDown() - downBefore).isEqualTo(1);
        }
    }

    // ============================================================
    // 五、延迟
    // ============================================================

    @Nested
    @DisplayName("五、延迟只统计 status = 1")
    class Latency {

        /**
         * ★★ 判据的正-反对照：同样两行，唯一差别是 {@code status}。
         *
         * <p>少了反面那条，「什么都算进去」的实现也能让正面通过。
         */
        @Test
        @DisplayName("★★ 正：status=1 的行进延迟统计")
        void successRowCounts() {
            long before = snap(WINDOW_ALL).latency().totalN();
            insertQa("[{\"no\":1,\"chunk_id\":1}]", 1, null, OffsetDateTime.now());
            assertThat(snap(WINDOW_ALL).latency().totalN() - before).isEqualTo(1);
        }

        @Test
        @DisplayName("★★ 反：status=2 / 3 的行【不】进延迟统计")
        void nonSuccessRowsDoNotCount() {
            long before = snap(WINDOW_ALL).latency().totalN();
            insertQa(null, 2, null, OffsetDateTime.now());
            insertQa(null, 3, null, OffsetDateTime.now());

            assertThat(snap(WINDOW_ALL).latency().totalN() - before)
                    .as("★ 放进来只会稀释分母，却让 totalN 与「提问数」对不上 —— "
                            + "而那看起来像丢数据")
                    .isZero();
        }

        @Test
        @DisplayName("★ 提问数与 totalN 是两个数：前者的分母包含所有 status")
        void questionsAndTotalNAreDifferentDenominators() {
            long qBefore = snap(WINDOW_ALL).traffic().questions();
            long lBefore = snap(WINDOW_ALL).latency().totalN();

            insertQa(null, 2, null, OffsetDateTime.now());

            assertThat(snap(WINDOW_ALL).traffic().questions() - qBefore).isEqualTo(1);
            assertThat(snap(WINDOW_ALL).latency().totalN() - lBefore).isZero();
        }

        /**
         * ★★ {@code totalN} 是<b>样本数</b>，不是<b>行数</b>。
         *
         * <p>这一条刻意造一个<b>真实数据里不存在</b>的形状：{@code status=1}
         * 却没写时序列。实测非评测 164 行里「{@code status=1} 的行数」、
         * 「{@code total} 非空」和「{@code llm} 非空」<b>三个数完全相等</b> ——
         * 因为服务端那条写路径总是同时写这两列。
         *
         * <p>★ 所以这条用例不是在测一个会发生的 bug，是在<b>钉住一个定义</b>：
         * 两个数一旦对不上，那句话的含义是「有 {@code status=1} 的行没写时序列」
         * —— 去看写路径，而不是看这条查询。
         */
        @Test
        @DisplayName("★★ totalN 是样本数不是行数：没有时序列的 status=1 行不算进去")
        void totalNCountsSamplesNotRows() {
            long qBefore = snap(WINDOW_ALL).traffic().questions();
            long lBefore = snap(WINDOW_ALL).latency().totalN();

            insertQa("[{\"no\":1,\"chunk_id\":1}]", 1, null, OffsetDateTime.now(), false);

            assertThat(snap(WINDOW_ALL).traffic().questions() - qBefore)
                    .as("提问数涨了 —— 它是【行数】")
                    .isEqualTo(1);
            assertThat(snap(WINDOW_ALL).latency().totalN() - lBefore)
                    .as("延迟样本数没涨 —— 这一行没有时序列，它不是一份样本")
                    .isZero();
        }
    }

    // ============================================================
    // 六、窗口参数的处置
    // ============================================================

    @Nested
    @DisplayName("六、窗口参数的处置")
    class WindowParam {

        @Test
        @DisplayName("★★ 不认识的值 → 换成缺省，但 requestedWindow 里留原值")
        void unknownWindowIsReplacedButVisible() {
            MetricsSnapshot s = snap("99y");

            assertThat(s.window()).isEqualTo("24h");
            assertThat(s.requestedWindow())
                    .as("★ 只留替换后的值的话，「你传错了」和「只有三个窗口」长得一样")
                    .isEqualTo("99y");
            assertThat(s.notes()).anyMatch(n -> n.contains("99y"));
        }

        @Test
        @DisplayName("★ 缺省不刷 note —— 没传是正常调用，不是替换")
        void defaultDoesNotWarn() {
            assertThat(snap(null).notes())
                    .as("没传 window 是正常的取缺省行为，为它刷一句提示会让这一栏恒有噪声")
                    .noneMatch(n -> n.contains("不是合法取值"));
            assertThat(snap(null).window()).isEqualTo("24h");
        }

        @Test
        @DisplayName("★ all 没有下界（from 为 null），且不改写 requestedWindow")
        void allHasNoLowerBound() {
            MetricsSnapshot s = snap("all");
            assertThat(s.window()).isEqualTo("all");
            assertThat(s.from()).isNull();
            assertThat(s.requestedWindow()).isEqualTo("all");
        }

        @Test
        @DisplayName("★ 大小写与空白容错：' 7D ' 认得出")
        void caseAndWhitespaceTolerated() {
            assertThat(snap(" 7D ").window()).isEqualTo("7d");
        }

        @Test
        @DisplayName("★★ notes 里带着 ADR-083 那句 —— 口径必须和数字同行")
        void notesCarryTheCaveat() {
            assertThat(snap(WINDOW_ALL).notes())
                    .as("数字会被贴到别处去，口径得跟着走")
                    .anyMatch(n -> n.contains("不相加"))
                    .anyMatch(n -> n.contains("埋点上线"));
        }
    }
}
