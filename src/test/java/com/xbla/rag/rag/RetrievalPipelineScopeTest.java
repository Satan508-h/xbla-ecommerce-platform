package com.xbla.rag.rag;

import com.xbla.rag.config.RetrievalProperties;
import com.xbla.rag.mapper.KbChunkMapper;
import com.xbla.rag.rag.fuse.RrfFuser;
import com.xbla.rag.rag.query.QueryPlan;
import com.xbla.rag.rag.query.QueryPlanner;
import com.xbla.rag.rag.rerank.ChunkReranker;
import com.xbla.rag.rag.retrieve.RetrievalOptions;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
import com.xbla.rag.rag.retrieve.Retriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RetrievalPipeline} 的<b>范围决策</b>单测 —— 全部协作者都是桩，
 * 不连库、不花钱、毫秒级。
 *
 * <h3>★ 为什么这个必须有单测，而不是只靠集成测试</h3>
 *
 * <p>范围决策是一个<b>四分支</b>的逻辑，而其中一条分支
 * （过滤后一无所获 → 回落全池重查）在真实数据上<b>几乎不可能触发</b> ——
 * 前面那条「池子太小就不过滤」的规则已经把它挡掉了大半。
 *
 * <p>更要命的是，集成测试还测不了它：{@code @Transactional} 的测试
 * 插入的数据<b>对那两条并行的召回线程是不可见的</b>（它们各自从连接池
 * 拿自己的连接、跑在独立事务里），所以没法在测试里构造出
 * 「池子够大但一条都查不到」的库状态。这条分支只能在这里测。
 *
 * <h3>★ 这里真正要钉死的三件事</h3>
 *
 * <ol>
 *   <li><b>{@code options} 会原样送到两条路上</b> —— 过滤是在 SQL 里做的，
 *       编排层如果把它弄丢了，链路照样跑、结果照样有，只是<b>不生效</b>。</li>
 *   <li><b>回落时 trace 是被【覆盖】而不是追加</b> ——
 *       {@code retrieval_detail} 的每一格必须描述同一件事。</li>
 *   <li><b>不该过滤时不发计数查询</b> —— 没声明范围还去查一次库，
 *       是每请求一次白花的往返。</li>
 * </ol>
 */
@DisplayName("RetrievalPipeline · 意图定向范围的决策")
class RetrievalPipelineScopeTest {

    private static final String QUESTION = "退货要几天";

    /** doc_type=5（说明书）在全库只有 5 条 —— 小于 vector-top-k，是「小池子」场景 */
    private static final String DOC_TYPES_5 = "{5}";
    /** doc_type={2,4}（售后+FAQ）有 60+ 条 —— 是「值得过滤」的场景 */
    private static final String DOC_TYPES_2_4 = "{2,4}";

    private RetrievalPipeline pipeline;

    private Retriever vectorRetriever;
    private Retriever keywordRetriever;
    private KbChunkMapper chunkMapper;
    private ThreadPoolTaskExecutor executor;
    private RetrievalProperties properties;

    @BeforeEach
    void setUp() {
        vectorRetriever = mock(Retriever.class);
        keywordRetriever = mock(Retriever.class);
        when(vectorRetriever.name()).thenReturn("vector");
        when(keywordRetriever.name()).thenReturn("keyword");

        chunkMapper = mock(KbChunkMapper.class);

        QueryPlanner queryPlanner = mock(QueryPlanner.class);
        when(queryPlanner.plan(anyString())).thenAnswer(inv -> QueryPlan.identity(inv.getArgument(0)));

        // 重排关掉：本类测的是范围决策，不是重排。
        // 关掉之后 ChunkReranker 一次都不会被调用，桩不用写任何行为
        properties = new RetrievalProperties();
        properties.getRerank().setEnabled(false);

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("test-retrieve-");
        executor.initialize();

        pipeline = new RetrievalPipeline(
                List.of(vectorRetriever, keywordRetriever),
                queryPlanner,
                new RrfFuser(),
                mock(ChunkReranker.class),
                properties,
                executor,
                chunkMapper);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // ============================================================
    // 〇、★★ 总开关（阶段 7 新增）—— 排在「一」之前，
    //     因为它在 resolveScope 里就是【最先被判断】的那一格
    // ============================================================

    @Nested
    @DisplayName("〇、★★ 总开关：by-intent.enabled=false")
    class SwitchOff {

        /**
         * ★★ 正-反对照写【在同一个方法里】，不拆成两个测试。
         *
         * <p>拆成两个测试的话，反对照那条会在别的地方被改坏而没人注意 ——
         * 而它正是「开关确实是那个变量」的唯一证据。
         * 合在一起，改坏任何一半这个方法都会红。
         */
        @Test
        @DisplayName("★★ 开着→真的下推；关掉→条件从 SQL 里消失、理由变成 disabled_by_config")
        void disabledByConfig() {
            when(chunkMapper.countByDocTypes(DOC_TYPES_2_4)).thenReturn(67);
            stubHits(vectorRetriever, 3);
            stubHits(keywordRetriever, 2);

            // ── 反对照：开关【开】着，同一份声明 → 过滤真的下推到 SQL ──
            assertThat(properties.getRetrieve().getByIntent().isEnabled())
                    .as("★ 默认必须是 true —— 关掉的默认值会让所有历史数字在无人察觉的情况下换口径")
                    .isTrue();
            RetrievalTrace on = new RetrievalTrace("t-on");
            pipeline.retrieve(QUESTION, new RetrievalOptions(20, List.of(2, 4)), on);

            assertThat(on.filter().reason())
                    .isEqualTo(RetrievalTrace.FilterScope.Reason.OK);
            assertThat(on.filter().applied()).isTrue();
            assertThat(optionsSentTo(vectorRetriever).docTypesLiteral())
                    .as("反对照的落点：开关开着时这条语句【确实】带条件。"
                            + "没有这一句，下面那条 \"不过滤\" 的断言可能只是"
                            + "因为别的原因恒真")
                    .isEqualTo("{2,4}");

            // ── 正照：关掉，同一份声明 → 条件消失 ──
            properties.getRetrieve().getByIntent().setEnabled(false);
            RetrievalTrace off = new RetrievalTrace("t-off");
            pipeline.retrieve(QUESTION, new RetrievalOptions(20, List.of(2, 4)), off);

            assertThat(off.filter().reason())
                    .as("★★ 必须是 disabled_by_config，【不能】是 no_declaration —— "
                            + "调用方明明声明了。复用 no_declaration 的后果是："
                            + "读 trace 的人会去查那个调用方为什么没传 docTypes，"
                            + "而真正的原因在 application.yml 里。"
                            + "两件事的修法一个在代码、一个在配置，症状却逐字相同")
                    .isEqualTo(RetrievalTrace.FilterScope.Reason.DISABLED_BY_CONFIG);
            assertThat(off.filter().applied()).isFalse();
            assertThat(off.filter().docTypes())
                    .as("★ 声明的集合仍然【如实记录】—— 它确实声明了，只是没被用上。"
                            + "于是「docTypes 非空 且 applied=false」"
                            + "这个组合本身就是一封信：去看 reason")
                    .containsExactly(2, 4);
            assertThat(off.filter().poolSize())
                    .as("★ 与 no_declaration 那条一样：不打算用这个集合，就不发那次 count 查询。"
                            + "「关掉时池子多大」在报告里没有用武之地")
                    .isNull();
            verify(chunkMapper, times(1))
                    .countByDocTypes(anyString());   // 只被【开着】的那一次查过

            assertThat(optionsSentTo(vectorRetriever).docTypesLiteral())
                    .as("★ 关掉 = SQL 里没有条件 = 全池检索。这一条是开关的"
                            + "全部语义；它不成立的话，A/B 的两轮其实在跑同一个配置，"
                            + "而差异会全部落进噪声里 —— 且不报错")
                    .isNull();
        }

        @Test
        @DisplayName("★ 关掉时不看池子大小：池子再小也照样不过滤（顺序证明）")
        void disabledSkipsThePoolCheckEntirely() {
            // 池子小到会被 small_pool 拦下 —— 但开关关着，那条规则够不着
            when(chunkMapper.countByDocTypes(DOC_TYPES_5)).thenReturn(5);
            stubHits(vectorRetriever, 3);
            stubHits(keywordRetriever, 2);
            properties.getRetrieve().getByIntent().setEnabled(false);

            RetrievalTrace trace = new RetrievalTrace("t");
            pipeline.retrieve(QUESTION, new RetrievalOptions(20, List.of(5)), trace);

            assertThat(trace.filter().reason())
                    .as("★ 这条测的是【判断顺序】：关掉时 small_pool 那条分支"
                            + "根本不会被走到。顺序写反的话，两轮的 retrieval_detail"
                            + "会在不同的 reason 之间飘，A/B 的归因就不可读了")
                    .isEqualTo(RetrievalTrace.FilterScope.Reason.DISABLED_BY_CONFIG);
            verify(chunkMapper, never()).countByDocTypes(anyString());
        }
    }

    // ============================================================
    // 一、★ 三条正常分支
    // ============================================================

    @Nested
    @DisplayName("一、★ 三条正常分支")
    class Branches {

        @Test
        @DisplayName("★ 没声明范围：不下推过滤，也【不查池子大小】")
        void noDeclarationSkipsEverything() {
            RetrievalTrace trace = new RetrievalTrace("t");
            stubHits(vectorRetriever, 3);
            stubHits(keywordRetriever, 2);

            pipeline.retrieve(QUESTION, RetrievalOptions.unfiltered(20), trace);

            assertThat(trace.filter().reason())
                    .isEqualTo(RetrievalTrace.FilterScope.Reason.NO_DECLARATION);
            assertThat(trace.filter().applied()).isFalse();
            assertThat(trace.filter().poolSize())
                    .as("★ 没测量过就该是 null。发一次没用的 count 查询"
                            + "是每个请求白花的一次往返 —— 而它换来的信息"
                            + "（一个我们本来就不打算用的集合有多大）毫无用处")
                    .isNull();
            verify(chunkMapper, never()).countByDocTypes(anyString());

            assertThat(docTypesSentToVectorLeg())
                    .as("★ 两条路都必须拿到 null 字面量 = SQL 里不过滤")
                    .isNull();
        }

        @Test
        @DisplayName("★ 池子太小：丢掉过滤，但把池子大小记进 trace")
        void smallPoolSkipsFilter() {
            when(chunkMapper.countByDocTypes(DOC_TYPES_5)).thenReturn(5);   // < vector-top-k 20
            RetrievalTrace trace = new RetrievalTrace("t");
            stubHits(vectorRetriever, 3);

            pipeline.retrieve(QUESTION, new RetrievalOptions(20, List.of(5)), trace);

            assertThat(trace.filter().docTypes())
                    .as("★ 声明的集合要【如实记录】，即使最终没用上 —— "
                            + "「声明了但被跳过」和「压根没声明」是两种不同的错误，"
                            + "而它们只看 applied 区分不出来")
                    .containsExactly(5);
            assertThat(trace.filter().reason())
                    .isEqualTo(RetrievalTrace.FilterScope.Reason.SMALL_POOL);
            assertThat(trace.filter().applied()).isFalse();
            assertThat(trace.filter().poolSize())
                    .as("池子大小要留着 —— 它就是「为什么被跳过」的全部理由")
                    .isEqualTo(5);

            assertThat(docTypesSentToVectorLeg())
                    .as("★ 被跳过就意味着 SQL 里没有条件。"
                            + "doc_type=[5] 在全库只有 5 条，而 topK=20 —— "
                            + "过滤之后取出来的恒等于全池，「按相关度截断」这个动作"
                            + "会消失：问「开机键在哪」也会把 5 条说明书全塞进 prompt")
                    .isNull();
        }

        @Test
        @DisplayName("★ 池子够大：真的下推过滤，并把池子大小记进 trace")
        void bigPoolAppliesFilter() {
            when(chunkMapper.countByDocTypes(DOC_TYPES_2_4)).thenReturn(67);
            RetrievalTrace trace = new RetrievalTrace("t");
            stubHits(vectorRetriever, 3);

            pipeline.retrieve(QUESTION, new RetrievalOptions(20, List.of(2, 4)), trace);

            assertThat(trace.filter().reason())
                    .isEqualTo(RetrievalTrace.FilterScope.Reason.OK);
            assertThat(trace.filter().applied()).isTrue();
            assertThat(trace.filter().poolSize()).isEqualTo(67);

            assertThat(docTypesSentToVectorLeg())
                    .as("★ 这一条是本类最重要的断言：范围真的送到了 SQL 层。"
                            + "编排层把它弄丢的话，链路照样跑、结果照样有、"
                            + "日志看不出异常 —— 只是过滤【没生效】")
                    .isEqualTo("{2,4}");
        }
    }

    // ============================================================
    // 二、★★ 回落分支
    // ============================================================

    @Nested
    @DisplayName("二、★★ 过滤后一无所获 → 回落全池重查")
    class Fallback {

        @Test
        @DisplayName("★ 两路都空 → 用全池重查一次，并把 trace 覆盖成全池的结果")
        void emptyResultFallsBack() {
            when(chunkMapper.countByDocTypes(DOC_TYPES_2_4)).thenReturn(67);
            RetrievalTrace trace = new RetrievalTrace("t");

            // 第一次（带过滤）：两路都没有
            // 第二次（全池）：有结果
            when(vectorRetriever.retrieve(anyString(), any(RetrievalOptions.class)))
                    .thenReturn(List.of())
                    .thenReturn(List.of(chunk(11), chunk(12)));
            when(keywordRetriever.retrieve(anyString(), any(RetrievalOptions.class)))
                    .thenReturn(List.of())
                    .thenReturn(List.of(chunk(21)));

            List<RetrievedChunk> result = pipeline.retrieve(
                    QUESTION, new RetrievalOptions(20, List.of(2, 4)), trace);

            assertThat(result)
                    .as("★ 用户不该因为「过滤后没东西」而拿到一个裸聊回答")
                    .isNotEmpty();

            assertThat(trace.filter().reason())
                    .isEqualTo(RetrievalTrace.FilterScope.Reason.EMPTY_RESULT);
            assertThat(trace.filter().applied())
                    .as("★ applied 的定义是「这次检索【最终】用了过滤吗」。"
                            + "回落意味着最终跑的是全池查询，所以它是 false —— "
                            + "让 applied 和 vector_hits/fused/final_top_k 那几格"
                            + "说的是同一件事，这个 JSON 才自洽")
                    .isFalse();
            assertThat(trace.filter().docTypes())
                    .as("声明的集合仍然如实留着，回落的原因才解释得通")
                    .containsExactly(2, 4);
            assertThat(trace.filter().poolSize())
                    .as("池子大小不能因为回落就丢掉 —— 它正是排查「为什么"
                            + "池子有 67 条却一条都召不回」的起点")
                    .isEqualTo(67);

            assertThat(trace.vectorHits())
                    .as("★ 这里必须是【全池那一次】的结果，而不是空。"
                            + "如果 trace 保留的是第一次的空结果，"
                            + "retrieval_detail 就会显示「两路都没召回」而 final_top_k 非空 —— "
                            + "一个自相矛盾的 JSON，而且看起来像是 bug")
                    .extracting(RetrievedChunk::id)
                    .containsExactly(11L, 12L);
            assertThat(trace.keywordHits())
                    .extracting(RetrievedChunk::id)
                    .containsExactly(21L);

            assertThat(trace.events())
                    .as("回落必须留下痕迹 —— 它是「池子够大却查不到」的信号，"
                            + "而那种情况说明问题不在过滤，在 search_text 或向量")
                    .anyMatch(e -> e.startsWith("doc_type_filter_fallback"));
        }

        @Test
        @DisplayName("★ 两次调用：第一次带过滤、第二次不带")
        void secondCallDropsTheFilter() {
            when(chunkMapper.countByDocTypes(DOC_TYPES_2_4)).thenReturn(67);
            when(vectorRetriever.retrieve(anyString(), any(RetrievalOptions.class)))
                    .thenReturn(List.of())
                    .thenReturn(List.of(chunk(11)));

            pipeline.retrieve(QUESTION, new RetrievalOptions(20, List.of(2, 4)),
                    new RetrievalTrace("t"));

            ArgumentCaptor<RetrievalOptions> captor = ArgumentCaptor.forClass(RetrievalOptions.class);
            verify(vectorRetriever, times(2)).retrieve(anyString(), captor.capture());

            assertThat(captor.getAllValues().get(0).docTypesLiteral())
                    .as("第一次：带着声明好的范围")
                    .isEqualTo("{2,4}");
            assertThat(captor.getAllValues().get(1).docTypesLiteral())
                    .as("★ 第二次：范围被去掉了。这一条比「回落后有结果」更本质 —— "
                            + "它证明回落【真的重查了一遍】，"
                            + "而不是把第一次的空结果当成「就是没有」")
                    .isNull();
            assertThat(captor.getAllValues().get(1).topK())
                    .as("条数不带变的 —— 变了的话两次召回的结果就不可比了")
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("★ 只有一路空时不回落（单路失败/未命中不是回落的理由）")
        void singleEmptyLegDoesNotFallBack() {
            when(chunkMapper.countByDocTypes(DOC_TYPES_2_4)).thenReturn(67);
            RetrievalTrace trace = new RetrievalTrace("t");

            when(vectorRetriever.retrieve(anyString(), any(RetrievalOptions.class)))
                    .thenReturn(List.of(chunk(11)));
            when(keywordRetriever.retrieve(anyString(), any(RetrievalOptions.class)))
                    .thenReturn(List.of());     // 关键词路没匹配到

            pipeline.retrieve(QUESTION, new RetrievalOptions(20, List.of(2, 4)), trace);

            assertThat(trace.filter().reason())
                    .as("★ 两路的强弱项不重叠，「关键词没匹配到」是【正常结果】"
                            + "而不是失败 —— 拿它当回落的理由会让过滤在"
                            + "口语化问题上几乎永远不生效")
                    .isEqualTo(RetrievalTrace.FilterScope.Reason.OK);
            verify(vectorRetriever, times(1))
                    .retrieve(anyString(), any(RetrievalOptions.class));
        }
    }

    // ============================================================
    // 三、两条路各自拿到自己的 topK
    // ============================================================

    @Test
    @DisplayName("★ 范围是共用的，但 topK 是每路各自的（两路配置不同）")
    void perLegTopK() {
        properties.getRetrieve().setVectorTopK(20);
        properties.getRetrieve().setKeywordTopK(7);
        when(chunkMapper.countByDocTypes(DOC_TYPES_2_4)).thenReturn(67);
        // ★ 必须打桩。不打桩的话 mock 返回 null，会被 recordHits 归一成空列表 ——
        //   而「两路都空」会触发回落分支，于是这里捕到的是【第二次】调用的参数
        //   （不带过滤的那个），断言就会以一条看起来毫不相关的消息失败
        stubHits(vectorRetriever, 3);
        stubHits(keywordRetriever, 2);

        pipeline.retrieve(QUESTION, new RetrievalOptions(20, List.of(2, 4)),
                new RetrievalTrace("t"));

        assertThat(optionsSentTo(vectorRetriever).topK()).isEqualTo(20);
        assertThat(optionsSentTo(keywordRetriever).topK())
                .as("★ 关键词路的 topK 来自它自己的配置项。"
                        + "如果编排层图省事把调用方传的 topK 直接透传下去，"
                        + "两路的条数就会被悄悄拉平 —— 而 phase 7 的 A/B"
                        + "正是要靠这两个数分别调")
                .isEqualTo(7);
        assertThat(optionsSentTo(keywordRetriever).docTypesLiteral())
                .as("范围是两路共用的")
                .isEqualTo("{2,4}");
    }

    // ============================================================
    // 辅助
    // ============================================================

    private static RetrievedChunk chunk(long id) {
        return new RetrievedChunk(id, 1L, (int) id, "正文 " + id, "标题", 0.9);
    }

    /** 让某一路每次都返回 n 条 */
    private static void stubHits(Retriever retriever, int n) {
        when(retriever.retrieve(anyString(), any(RetrievalOptions.class)))
                .thenReturn(java.util.stream.LongStream.rangeClosed(1, n)
                        .mapToObj(RetrievalPipelineScopeTest::chunk)
                        .toList());
    }

    private RetrievalOptions optionsSentTo(Retriever retriever) {
        ArgumentCaptor<RetrievalOptions> captor = ArgumentCaptor.forClass(RetrievalOptions.class);
        verify(retriever, org.mockito.Mockito.atLeastOnce()).retrieve(anyString(), captor.capture());
        return captor.getValue();
    }

    /** 向量路第一次调用时拿到的范围字面量 */
    private String docTypesSentToVectorLeg() {
        return optionsSentTo(vectorRetriever).docTypesLiteral();
    }
}
