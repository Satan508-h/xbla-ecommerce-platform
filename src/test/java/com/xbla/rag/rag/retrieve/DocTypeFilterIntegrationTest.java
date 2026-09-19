package com.xbla.rag.rag.retrieve;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xbla.rag.common.handler.VectorTypeHandler;
import com.xbla.rag.entity.KbChunk;
import com.xbla.rag.entity.KbDocument;
import com.xbla.rag.mapper.KbChunkMapper;
import com.xbla.rag.mapper.KbDocumentMapper;
import com.xbla.rag.rag.tokenize.BigramCjkTokenizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code doc_type} 过滤的集成测试（阶段 5.4）—— 真连 PostgreSQL。
 *
 * <h3>★ 为什么必须连真库</h3>
 *
 * <p>过滤条件是一段 SQL（{@code #{docTypes} IS NULL OR doc_type = ANY(...)}）。
 * 它最危险的地方在于<b>两种错误都是静默的</b>：
 *
 * <ul>
 *   <li><b>{@code null} 分支写漏了</b> —— {@code doc_type = ANY(CAST(NULL AS int[]))}
 *       求值为 {@code NULL}，在 {@code WHERE} 里等于是 false。<b>不报错</b>，
 *       症状是「不过滤的时候一条都查不到」。这个在 Java 侧完全看不出来。</li>
 *   <li><b>字面量格式不对</b> —— {@code CAST('2,4' AS int[])} 会报错，
 *       但 {@code CAST('{}' AS int[])} 不会 —— 它合法且恒为 false。</li>
 * </ul>
 *
 * <h3>★ 一条断言配一条对照</h3>
 *
 * <p>「过滤后返回的行都在集合内」这句话，在<b>返回空列表</b>时也成立。
 * 所以每一条过滤断言都必须配一条「不过滤时确实返回了集合外的行」的对照 ——
 * 否则把过滤条件写成恒 false 都能让测试通过。
 *
 * <p>⚠️ <b>测试数据一律用生僻字</b>（犇骉鑫焱…），理由同
 * {@code KeywordRetrieverIntegrationTest}：库里已经有 1640 条真实切片。
 *
 * <p><b>运行前提</b>：docker compose 的 postgres 必须在跑。
 */
@SpringBootTest
@Transactional
@DisplayName("doc_type 过滤 —— 下推到 SQL 的 WHERE 条件")
class DocTypeFilterIntegrationTest {

    /** 生僻字标记，保证不会和真实语料撞车 */
    private static final String MARK = "犇骉鑫焱";
    private static final String MARK2 = "麤龘靐齉";

    @Autowired
    private KbChunkMapper chunkMapper;

    @Autowired
    private KbDocumentMapper documentMapper;

    @Autowired
    private BigramCjkTokenizer tokenizer;

    private Long documentId;

    /** 三条已知身份的切片：一条 doc_type=1、一条 2、一条 4 */
    private Long chunkDocType1;
    private Long chunkDocType2;
    private Long chunkDocType4;

    @BeforeEach
    void setUp() {
        KbDocument doc = new KbDocument();
        doc.setDocNo("D-DTFILTER-" + System.nanoTime());
        doc.setTitle("doc_type 过滤测试文档");
        doc.setDocType(2);
        doc.setSourceType(1);
        doc.setStatus(3);
        documentMapper.insert(doc);
        documentId = doc.getId();

        chunkDocType1 = insertChunk(0, 1, MARK + " 商品详情里的那条");
        chunkDocType2 = insertChunk(1, 2, MARK + " 售后政策里的那条");
        chunkDocType4 = insertChunk(2, 4, MARK + " 常见问答里的那条");
    }

    private Long insertChunk(int index, int docType, String content) {
        KbChunk chunk = new KbChunk();
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setDocType(docType);
        // search_text 由分词器产出，和 DocumentIngestWorker 做的事一致
        chunk.setSearchText(tokenizer.tokenize(content).tokenText());
        chunkMapper.insert(chunk);
        return chunk.getId();
    }

    private String orQuery(String question) {
        return tokenizer.tokenizeQuery(question, 64).orQuery();
    }

    /** 按 id 反查 doc_type —— VectorHit 本身不带这个字段（它不该为过滤承担字段） */
    private Map<Long, Integer> docTypesOf(List<Long> ids) {
        return chunkMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(KbChunk::getId, KbChunk::getDocType));
    }

    // ============================================================
    // 一、★ 关键词路
    // ============================================================

    @Nested
    @DisplayName("一、★ 关键词路的过滤")
    class Keyword {

        @Test
        @DisplayName("★ 对照组优先：不过滤时三条都在，而且确实横跨三种 doc_type")
        void withoutFilterReturnsAll() {
            List<Long> ids = chunkMapper.searchByKeyword(orQuery(MARK), 50, null)
                    .stream().map(VectorHit::id).toList();

            Map<Long, Integer> types = docTypesOf(ids);

            assertThat(ids)
                    .as("三条测试切片都要召回")
                    .contains(chunkDocType1, chunkDocType2, chunkDocType4);
            assertThat(types.values())
                    .as("★ 这条对照是下面所有过滤断言的前提："
                            + "如果不过滤时结果本来就全在 {2,4} 里，"
                            + "那「过滤后只在 {2,4} 里」这句话就是恒真的、什么都没证明")
                    .contains(1, 2, 4);
        }

        @Test
        @DisplayName("★ 过滤到 {2,4}：doc_type=1 的那条被排除，另外两条留着")
        void filterExcludesOutOfScope() {
            List<Long> ids = chunkMapper.searchByKeyword(orQuery(MARK), 50, "{2,4}")
                    .stream().map(VectorHit::id).toList();

            assertThat(ids)
                    .as("★ 用 containsExactly 而不是 contains —— "
                            + "这条同时证明了「该留的留着」和「该走的不见」")
                    .containsExactlyInAnyOrder(chunkDocType2, chunkDocType4);
            assertThat(ids)
                    .as("doc_type=1 的那条必须消失")
                    .doesNotContain(chunkDocType1);
        }

        @Test
        @DisplayName("★ 过滤到 {1}：只剩 doc_type=1 的那条")
        void filterToSingleType() {
            List<Long> ids = chunkMapper.searchByKeyword(orQuery(MARK), 50, "{1}")
                    .stream().map(VectorHit::id).toList();

            assertThat(ids)
                    .as("★ 方向和上一条【相反】—— 两条一起看才排除了"
                            + "「过滤条件被忽略」和「过滤条件恒 false」两种解释")
                    .containsExactly(chunkDocType1);
        }

        @Test
        @DisplayName("★ 过滤到一个没数据的类型：返回空列表，而不是抛异常")
        void filterToEmptyTypeIsSafe() {
            // doc_type=3（促销规则）在全库只有 13 条，且没有一条含这个生僻字标记
            assertThat(chunkMapper.searchByKeyword(orQuery(MARK), 50, "{3}"))
                    .as("DB 里确实没有匹配的行时，过滤条件只是让结果为空 —— "
                            + "真正的「空池」判断在 RetrievalPipeline 里做，"
                            + "因为只有它知道用户等的是多少条")
                    .isEmpty();
        }
    }

    // ============================================================
    // 二、★ 向量路
    // ============================================================

    @Nested
    @DisplayName("二、★ 向量路的过滤")
    class Vector {

        /**
         * 一个非零的 1024 维查询向量。
         *
         * <p><b>为什么不能用全零向量</b>：余弦距离是
         * {@code 1 - (a·b)/(|a||b|)}，零向量的模是 0 —— 会得到 {@code 0/0}。
         * 拿退化的输入去测一个「不报错的静默错误」是最糟的组合。
         */
        private String probeVector() {
            float[] v = new float[1024];
            java.util.Arrays.fill(v, 1.0f / 1024);
            return VectorTypeHandler.toLiteral(v);
        }

        @Test
        @DisplayName("★ 过滤到 {2,4}：返回的每一条 doc_type 都在集合内")
        void filterRestrictsToDeclaredTypes() {
            List<Long> ids = chunkMapper.searchByVector(probeVector(), 20, "{2,4}")
                    .stream().map(VectorHit::id).toList();

            assertThat(ids).as("池子有 60+ 条，topK=20 应该取满").hasSize(20);

            Map<Long, Integer> types = docTypesOf(ids);
            assertThat(types.values())
                    .as("★ 核心断言")
                    .isSubsetOf(List.of(2, 4));
        }

        @Test
        @DisplayName("★ 对照：同样的查询不过滤时，确实混进了集合外的类型")
        void withoutFilterMixesTypes() {
            List<Long> ids = chunkMapper.searchByVector(probeVector(), 20, null)
                    .stream().map(VectorHit::id).toList();

            Map<Long, Integer> types = docTypesOf(ids);

            assertThat(types.values())
                    .as("★ 全库 95% 是 doc_type=1，所以不限制时 top-20 里几乎必然"
                            + "混着一堆商品详情。这条对照证明上一条的 "
                            + "isSubsetOf 不是恒真的")
                    .anyMatch(t -> t != 2 && t != 4);
        }

        @Test
        @DisplayName("★ 过滤到 {2,4} 的结果，是「在 {2,4} 里精确排序」而不是「近似检索碰巧只碰到这些」")
        void filteredVectorSearchIsExact() {
            // 实测：加了 doc_type 条件之后，PostgreSQL 会弃用 HNSW 索引，
            // 改成 idx_kb_chunk_doc_type + Sort —— 也就是在子集上【精确】扫描。
            // 这顺带说明 pgvector 那个「过滤时近邻数不够」的著名坑
            // 在当前规模下根本轮不到（见 docs/05）。
            //
            // 这条测试用「子集规模 < topK」来暴露近似检索的破绽：
            // HNSW 的 ef_search 默认 40，如果它真的参与了，
            // 一个只有 5 条的子集是取不满 5 条的。
            List<Long> ids = chunkMapper.searchByVector(probeVector(), 5, "{5}")
                    .stream().map(VectorHit::id).toList();

            long poolSize = chunkMapper.countByDocTypes("{5}");

            assertThat(ids)
                    .as("★ doc_type=5（说明书）在全库只有 %d 条，取 5 条就该拿到 5 条。"
                            + "这条断言在「规划器改用 HNSW + 过滤」之后会失败 —— "
                            + "那时就是该启用 hnsw.iterative_scan 的信号", poolSize)
                    .hasSize((int) Math.min(5, poolSize));
            assertThat(docTypesOf(ids).values())
                    .as("而且全在集合内")
                    .allMatch(t -> t == 5);
        }
    }

    // ============================================================
    // 三、★ 池子计数
    // ============================================================

    @Nested
    @DisplayName("三、★ 池子计数（「值不值得过滤」判据的输入）")
    class PoolSize {

        @Test
        @DisplayName("★ countByDocTypes 与用实体查询数出来的结果一致")
        void countMatchesIndependentQuery() {
            long expected = chunkMapper.selectCount(new LambdaQueryWrapper<KbChunk>()
                    .in(KbChunk::getDocType, List.of(2, 4)));

            assertThat(chunkMapper.countByDocTypes("{2,4}"))
                    .as("★ 这条断言防的是「写错列名 / 忘了 deleted = 0」这类错误。"
                            + "它是唯一一个独立算出来的对照 —— "
                            + "拿被测方法去验被测方法是没有意义的")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("★ 集合是可加的：{2,4} 的池子 = {2} 的 + {4} 的")
        void poolIsAdditiveAcrossDisjointTypes() {
            assertThat(chunkMapper.countByDocTypes("{2,4}"))
                    .as("doc_type 的值本身互斥，所以池子大小必须可加。"
                            + "不可加就说明 ANY(...) 的解析出了问题")
                    .isEqualTo(chunkMapper.countByDocTypes("{2}") + chunkMapper.countByDocTypes("{4}"));
        }

        @Test
        @DisplayName("★ 复现「只对窄类型过滤」这个设计依据的真实分布")
        void reproducesTheNarrowTypeRationale() {
            int total = chunkMapper.countByDocTypes("{1,2,3,4,5}");
            int narrow = chunkMapper.countByDocTypes("{2,4}");
            int broad = chunkMapper.countByDocTypes("{1,5}");

            // 这几条不是「断言某个数」，而是把设计依据本身变成一条会跑的测试。
            // 语料变了它们会红 —— 那时该重新做一次「哪些意图值得过滤」的判断，
            // 而不是把数字改掉
            assertThat(narrow)
                    .as("售后类（{2,4}）是全库的极少数，但 20 道基线题里有 11 道属于它")
                    .isLessThan(total / 10);
            assertThat(broad)
                    .as("★ {1,5}（商品详情+说明书）占了全库九成以上 —— "
                            + "过滤它等于没过滤。这就是「池子必须 ≥ vector-top-k」"
                            + "这条判据存在的原因之一")
                    .isGreaterThan(total * 90 / 100);
        }
    }
}
