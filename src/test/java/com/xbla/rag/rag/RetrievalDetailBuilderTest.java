package com.xbla.rag.rag;

import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code retrieval_detail} 结构契约的单元测试。
 *
 * <p>纯计算，不起 Spring。
 *
 * <p><b>这个测试的全部价值在于「把 V5 迁移里写死的 JSON 结构变成可执行断言」。</b>
 * 那个结构是在 {@code V5__chat_and_observability.sql} 的注释里冻结的，
 * 阶段 7 的评测脚本和 A/B 对比工具都要按固定路径读它。
 * 没有这个测试，改坏结构不会有任何提示。
 */
@DisplayName("RetrievalDetailBuilder · retrieval_detail 结构契约")
class RetrievalDetailBuilderTest {

    private final RetrievalDetailBuilder builder = new RetrievalDetailBuilder();

    private static RetrievedChunk chunk(long id, double score) {
        return new RetrievedChunk(id, 1L, (int) id, "正文" + id, "路径" + id, score);
    }

    // ============================================================
    // 一、冻结的 7 个 key
    // ============================================================

    @Nested
    @DisplayName("一、★ 冻结结构（V5 写死 5 个 + 5.4 加的第 6 个 + 7.4 加的第 7 个）")
    class FrozenStructure {

        @Test
        @DisplayName("★ key 集合恰好是冻结的那 7 个")
        void hasExactlyFrozenKeys() {
            RetrievalTrace trace = new RetrievalTrace("t");
            trace.vectorHits(List.of(chunk(1, 0.9)));
            trace.keywordHits(List.of(chunk(2, 0.03)));
            trace.fused(List.of(chunk(1, 0.016)));
            trace.reranked(List.of(chunk(1, 0.95)));
            trace.finalChunks(List.of(chunk(1, 0.95)));

            assertThat(builder.build(trace).keySet())
                    .as("★ 用 hasSameElementsAs 而不是 containsExactly —— "
                            + "JSONB 会把键重排（按 key 长度+字节序），"
                            + "任何「从库里读出来断言 key 顺序」的测试都必然失败。"
                            + "这里断言的是集合相等，顺序无关。"
                            + "★★ 这条断言红过一次（阶段 7 加 sizes 时）—— 那是它【该红】："
                            + "扩冻结结构必须是一次【看见了的】决定，"
                            + "而不是改完没人知道。同 5.4 加 filter 那一次")
                    .hasSameElementsAs(List.of(
                            "vector_hits", "keyword_hits", "fused", "reranked", "final_top_k",
                            "filter", "sizes"));
        }

        /**
         * ★★★ 这一条是 {@code sizes} 存在的<b>全部理由</b>。
         *
         * <p>没有它，「这一段里没有正解」和「正解被截掉了」在数据上<b>完全一样</b>——
         * 而两者的结论相反：前者要去调召回/切分，后者什么都不用调。
         *
         * <p>判据就是一条算术：<b>记录条数 &lt; size ⟺ 被截过</b>。
         * 所以这里造 50 条，断言 size 是 50 而记录是 20 ——
         * 两个数摆在一起，截断就被读出来了。
         */
        @Test
        @DisplayName("★★ sizes 记的是【截断之前】的条数 —— 记录条数 < size ⟺ 被截过")
        void sizesRecordsTheLengthBeforeTruncation() {
            List<RetrievedChunk> fifty = new java.util.ArrayList<>();
            for (int i = 1; i <= 50; i++) {
                fifty.add(chunk(i, 1.0 / i));
            }
            RetrievalTrace trace = new RetrievalTrace("t");
            trace.vectorHits(fifty);
            trace.fused(fifty);
            trace.finalChunks(List.of(chunk(1, 1.0)));

            @SuppressWarnings("unchecked")
            Map<String, Object> sizes = (Map<String, Object>) builder.build(trace).get("sizes");

            assertThat(sizes).containsOnlyKeys(
                    "vector_hits", "keyword_hits", "fused", "reranked", "final_top_k");
            assertThat(sizes.get("vector_hits")).isEqualTo(50);
            assertThat(sizes.get("fused")).isEqualTo(50);
            assertThat(sizes.get("final_top_k")).isEqualTo(1);
            assertThat(sizes.get("keyword_hits")).isEqualTo(0);

            // ★★ 反对照：把「记录」和「真实」两个数放在一起，截断才算被读出来
            assertThat((List<?>) builder.build(trace).get("vector_hits"))
                    .as("★ 记录只有 " + RetrievalDetailBuilder.MAX_ENTRIES_PER_SECTION
                            + " 条而 size 是 50 —— 这个不等式【就是】截断的定义。"
                            + "少了 sizes，读的人只会看到 20 条，然后以为"
                            + "「向量路召回了 20 条」—— 那个数是假的")
                    .hasSize(RetrievalDetailBuilder.MAX_ENTRIES_PER_SECTION)
                    .hasSizeLessThan((Integer) sizes.get("vector_hits"));
        }

        @Test
        @DisplayName("★ 三段的分数【字段名各不相同】，不是笔误")
        void scoreFieldNamesDifferPerSection() {
            RetrievalTrace trace = new RetrievalTrace("t");
            trace.vectorHits(List.of(chunk(1, 0.9)));
            trace.keywordHits(List.of(chunk(1, 0.03)));
            trace.fused(List.of(chunk(1, 0.016)));
            trace.reranked(List.of(chunk(1, 0.95)));

            Map<String, Object> detail = builder.build(trace);

            assertThat(firstEntry(detail, "vector_hits")).containsKey("score");
            assertThat(firstEntry(detail, "keyword_hits")).containsKey("score");
            assertThat(firstEntry(detail, "fused"))
                    .as("融合用 rrf_score —— 和原始分数不可比，同名会诱使别人去比大小")
                    .containsKey("rrf_score")
                    .doesNotContainKey("score");
            assertThat(firstEntry(detail, "reranked"))
                    .as("重排用 rerank_score，同理")
                    .containsKey("rerank_score")
                    .doesNotContainKey("score");
        }

        @Test
        @DisplayName("final_top_k 是纯 ID 数组，不是对象数组")
        void finalTopKIsPlainIds() {
            RetrievalTrace trace = new RetrievalTrace("t");
            trace.finalChunks(List.of(chunk(7, 0.9), chunk(3, 0.8)));

            assertThat(builder.build(trace).get("final_top_k"))
                    .as("V5 里写的是 \"final_top_k\": [45, 12, 8] —— 纯 ID")
                    .isEqualTo(List.of(7L, 3L));
        }

        @Test
        @DisplayName("不记正文，只记 chunk_id 和分数")
        void contentIsNotIncluded() {
            RetrievalTrace trace = new RetrievalTrace("t");
            trace.vectorHits(List.of(chunk(1, 0.9)));

            Map<String, Object> detail = builder.build(trace);
            assertThat(detail.toString())
                    .as("正文在 kb_chunk 里，按 id 就能取回。塞进来会让这个 JSONB 字段"
                            + "的体积涨一个数量级，而 qa_log 是全项目写入最频繁的表")
                    .doesNotContain("正文1")
                    .doesNotContain("路径1");
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> firstEntry(Map<String, Object> detail, String section) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) detail.get(section);
            return list.get(0);
        }
    }

    // ============================================================
    // 二、空值与截断
    // ============================================================

    @Nested
    @DisplayName("二、空值与截断")
    class EmptyAndTruncation {

        @Test
        @DisplayName("★ trace 为 null 时返回 null（不是空 Map）")
        void nullTraceYieldsNull() {
            assertThat(builder.build(null))
                    .as("和 degradation_events 的约定一致：空就不写。"
                            + "这样才能写 WHERE retrieval_detail IS NOT NULL "
                            + "一眼筛出「这一次真的做了检索」的记录")
                    .isNull();
        }

        @Test
        @DisplayName("空 trace 仍然返回 7 个 key，值都是空列表 / 默认的 filter")
        void emptyTraceKeepsStructure() {
            Map<String, Object> detail = builder.build(new RetrievalTrace("t"));

            assertThat(detail).hasSize(7);
            assertThat(detail.get("vector_hits")).isEqualTo(List.of());
            assertThat(detail.get("final_top_k")).isEqualTo(List.of());

            // ★ sizes 也属于【恒定存在】的那一类：一次没检索过的 trace 里，
            //   「每一段都是 0 条」也是一个必须能被机器读到的事实
            @SuppressWarnings("unchecked")
            Map<String, Object> sizes = (Map<String, Object>) detail.get("sizes");
            assertThat(sizes).isNotNull();
            assertThat(sizes.values()).as("空 trace 的每一段都是 0，不是 null")
                    .containsOnly(0);
        }

        @Test
        @DisplayName("★ 从没声明过范围时，filter 那格是「没声明」而不是缺省")
        void filterDefaultsToNoDeclaration() {
            Map<String, Object> detail = builder.build(new RetrievalTrace("t"));

            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) detail.get("filter");

            assertThat(filter)
                    .as("★ filter 属于【恒定存在】的 key，不是「非空才出现」的附加字段。"
                            + "理由：『这次到底过滤了没有』是一次检索的固有属性，"
                            + "即使答案是「没有」也必须能被机器读到 —— "
                            + "做成「key 不在 == 没过滤」会让脚本依赖一个隐式约定，"
                            + "而隐式约定在改代码时会静默失效")
                    .isNotNull();
            assertThat(filter.get("doc_types"))
                    .as("没声明 → 空列表，不是 null")
                    .isEqualTo(List.of());
            assertThat(filter.get("applied")).isEqualTo(false);
            assertThat(filter.get("pool_size"))
                    .as("★ 没测量过就是 null。注意这个 key 不能省 —— "
                            + "省了就分不清「没测」和「忘了写」")
                    .isNull();
            assertThat(filter.get("reason")).isEqualTo("no_declaration");
        }

        @Test
        @DisplayName("★ 每段最多 20 条 —— qa_log 写入最频繁，不能无上限膨胀")
        void sectionsAreTruncated() {
            List<RetrievedChunk> fifty = new java.util.ArrayList<>();
            for (int i = 1; i <= 50; i++) {
                fifty.add(chunk(i, 1.0 / i));
            }
            RetrievalTrace trace = new RetrievalTrace("t");
            trace.vectorHits(fifty);

            assertThat((List<?>) builder.build(trace).get("vector_hits"))
                    .as("阶段 7 很可能把召回条数调大，不截断这张表会迅速膨胀")
                    .hasSize(RetrievalDetailBuilder.MAX_ENTRIES_PER_SECTION);
        }

        @Test
        @DisplayName("分数统一保留 6 位小数，两次运行的 JSON 可以直接 diff")
        void scoresAreRounded() {
            RetrievalTrace trace = new RetrievalTrace("t");
            trace.vectorHits(List.of(chunk(1, 0.6934000000000001)));

            assertThat(builder.build(trace).toString())
                    .as("double 的尾巴（…0001）会让两份报告无法直接比对")
                    .doesNotContain("0.6934000000000001")
                    .contains("0.6934");
        }
    }

    // ============================================================
    // 三、附加字段是加法式的
    // ============================================================

    @Nested
    @DisplayName("三、★ 附加字段（加法式扩展，不改动那 6 个 key）")
    class AdditiveFields {

        @Test
        @DisplayName("正常路径下没有 events 字段")
        void noEventsWhenClean() {
            RetrievalTrace trace = new RetrievalTrace("t");
            trace.vectorHits(List.of(chunk(1, 0.9)));

            assertThat(builder.build(trace))
                    .as("没出事就不加这个 key，保持和 V5 冻结结构逐字一致")
                    .doesNotContainKey("events")
                    .doesNotContainKey("sub_questions")
                    .doesNotContainKey("rewritten_question");
        }

        @Test
        @DisplayName("有失败事件时追加 events，但 6 个核心 key 不变")
        @SuppressWarnings("unchecked")
        void eventsAreAppended() {
            RetrievalTrace trace = new RetrievalTrace("t");
            trace.vectorHits(List.of(chunk(1, 0.9)));
            trace.legFailed("keyword", "连接超时");

            Map<String, Object> detail = builder.build(trace);

            assertThat(detail).containsKey("events");
            assertThat(detail.keySet())
                    .as("★ 7 个核心 key 必须仍然都在 —— 附加字段不能挤掉任何一个")
                    .containsAll(List.of(
                            "vector_hits", "keyword_hits", "fused", "reranked", "final_top_k",
                            "filter", "sizes"));
            assertThat((List<String>) detail.get("events"))
                    .as("失败事件是【给代码判断用】的结构化列表，不是一段自然语言")
                    .containsExactly("keyword_failed: 连接超时");
        }

        @Test
        @DisplayName("★ 没开重写时【不写】rewritten_question 字段")
        void noRewrittenQuestionWhenDisabled() {
            RetrievalTrace trace = new RetrievalTrace("t");
            trace.vectorHits(List.of(chunk(1, 0.9)));

            assertThat(builder.build(trace))
                    .as("★ 这里如果写了原问题，阶段 7 就无法区分"
                            + "「没开重写」和「开了但模型没改动」—— "
                            + "而那是两个完全不同的实验条件")
                    .doesNotContainKey("rewritten_question");
        }

        @Test
        @DisplayName("开了重写才写 rewritten_question")
        void rewrittenQuestionAppearsWhenPresent() {
            RetrievalTrace trace = new RetrievalTrace("t");
            trace.rewrittenQuestion("适合送长辈的商品");

            assertThat(builder.build(trace).get("rewritten_question"))
                    .isEqualTo("适合送长辈的商品");
        }
    }
}
