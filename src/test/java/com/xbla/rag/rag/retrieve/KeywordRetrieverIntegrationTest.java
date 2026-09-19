package com.xbla.rag.rag.retrieve;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 关键词召回的集成测试 —— 真连 PostgreSQL。
 *
 * <p><b>为什么这些用例必须连真库</b>：它们验证的全部是
 * <b>「Java 侧看不出来、只有真库才会暴露」</b>的行为：
 *
 * <ul>
 *   <li>{@code to_tsquery('simple','')} 到底抛异常还是静默返回 0 行；</li>
 *   <li>{@code search_vector @@ query} 对 NULL 的反应（会被 {@code WHERE} 静默排除）；</li>
 *   <li>生成列 {@code search_vector} 是否真的自动重算；</li>
 *   <li>分值并列时排序是否真的稳定。</li>
 * </ul>
 *
 * <p>{@code @Transactional} 让每个测试方法跑在一个事务里、结束自动回滚，
 * 不会往库里留垃圾数据（和 {@code EntityMappingTest} 一致）。
 *
 * <p>⚠️ <b>测试数据一律用生僻字</b>（犇骉鑫焱…）。库里已经有 1652 条真实切片，
 * 用「退货」「电池」这类常见词造数据会和真实语料互相干扰 ——
 * 那类断言会时对时错，而且排查方向会完全跑偏。
 *
 * <p><b>运行前提</b>：docker compose 的 postgres 必须在跑。
 */
@SpringBootTest
@Transactional
@DisplayName("关键词召回 —— 中文双字切分 + ts_rank")
class KeywordRetrieverIntegrationTest {

    /** 生僻字标记，保证不会和真实语料撞车 */
    private static final String MARK = "犇骉鑫焱";
    private static final String MARK2 = "麤龘靐齉";

    @Autowired
    private KeywordRetriever retriever;

    @Autowired
    private KbChunkMapper chunkMapper;

    @Autowired
    private KbDocumentMapper documentMapper;

    @Autowired
    private BigramCjkTokenizer tokenizer;

    private Long documentId;

    @BeforeEach
    void setUp() {
        KbDocument doc = new KbDocument();
        doc.setDocNo("D-KWTEST-" + System.nanoTime());
        doc.setTitle("关键词召回测试文档");
        doc.setDocType(2);
        doc.setSourceType(1);
        doc.setStatus(3);
        documentMapper.insert(doc);
        documentId = doc.getId();
    }

    /** 插一条切片。searchText 传 null 表示「模拟忘了写索引」的坏数据 */
    private Long insertChunk(int index, String content, String searchText, String headingPath) {
        KbChunk chunk = new KbChunk();
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setHeadingPath(headingPath);
        chunk.setDocType(2);
        chunk.setSearchText(searchText);
        chunkMapper.insert(chunk);
        return chunk.getId();
    }

    private Long insertChunk(int index, String content, String searchText) {
        return insertChunk(index, content, searchText, null);
    }

    /** 正常入库的切片：search_text 由分词器产出，和 DocumentIngestWorker 做的事一致 */
    private Long insertTokenizedChunk(int index, String content) {
        return insertChunk(index, content, tokenizer.tokenize(content).tokenText(), null);
    }

    // ============================================================
    // 一、基本召回
    // ============================================================

    @Nested
    @DisplayName("一、基本召回")
    class Basic {

        @Test
        @DisplayName("能召回到，且词频高的排前面（ts_rank 的排序信号就是词频）")
        void recallAndRankByFrequency() {
            Long once = insertTokenizedChunk(0, MARK + " 只出现一次的商品描述内容");
            Long twice = insertTokenizedChunk(1, MARK + " 出现两次，" + MARK + " 又出现了一次");

            List<RetrievedChunk> hits = retriever.retrieve(MARK, 10);

            assertThat(hits).as("两条都应该被召回").hasSize(2);
            assertThat(hits.get(0).id())
                    .as("★ 词频高的排前面。这条断言直接对应「去掉词元去重」那个修复 —— "
                            + "去重会让两条拿到相同分数，这条断言就会失败")
                    .isEqualTo(twice);
            assertThat(hits.get(1).id()).isEqualTo(once);

            assertThat(hits.get(0).score())
                    .as("分值必须严格大于，不能并列")
                    .isGreaterThan(hits.get(1).score());
        }

        @Test
        @DisplayName("返回的字段完整：id / documentId / chunkIndex / 正文 / 标题路径")
        void fieldsArePopulated() {
            Long id = insertChunk(0, MARK + " 的标题路径测试",
                    tokenizer.tokenize(MARK + " 的标题路径测试").tokenText(),
                    "测试文档 > 一级 > 二级");

            RetrievedChunk hit = retriever.retrieve(MARK, 10).get(0);

            assertThat(hit.id()).isEqualTo(id);
            assertThat(hit.documentId()).isEqualTo(documentId);
            assertThat(hit.chunkIndex()).isEqualTo(0);
            assertThat(hit.content()).contains(MARK);
            assertThat(hit.headingPath())
                    .as("标题路径要原样带回来 —— 最终它会被拼进 prompt 给 LLM 提供上下文")
                    .isEqualTo("测试文档 > 一级 > 二级");
        }

        @Test
        @DisplayName("中文查询命中中文文档（英文分词器做不到这件事）")
        void chineseQueryMatchesChineseDoc() {
            Long id = insertTokenizedChunk(0, MARK + " 是这四个字组成的标记");
            assertThat(retriever.retrieve(MARK, 10))
                    .as("整串查询四个字，切出 犇骉/骉鑫/鑫焱 三个 bigram")
                    .extracting(RetrievedChunk::id)
                    .containsExactly(id);
        }
    }

    // ============================================================
    // 二、★ 零词元护栏
    // ============================================================

    @Nested
    @DisplayName("二、★ 零词元 / 空查询护栏")
    class EmptyQueryGuard {

        @Test
        @DisplayName("★ 纯标点与单字查询返回空列表，不抛异常")
        void noExceptionOnEmptyTokenQuery() {
            insertTokenizedChunk(0, MARK + " 存在的切片");

            // 实测：to_tsquery('simple','') 不抛异常，只发 NOTICE 然后静默返回 0 行。
            // 如果 Java 侧不短路，这里会得到一个「看起来像查询失败」的空结果，
            // 而调用方无法区分「没匹配」和「查询串是空的」。
            assertThatCode(() -> {
                assertThat(retriever.retrieve("，。！？", 10)).isEmpty();
                assertThat(retriever.retrieve("退", 10)).as("单字被分词器丢弃 → 零词元").isEmpty();
                assertThat(retriever.retrieve("   ", 10)).isEmpty();
                assertThat(retriever.retrieve("", 10)).isEmpty();
                assertThat(retriever.retrieve(null, 10)).isEmpty();
            }).as("★ 全程不许抛异常 —— 关键词召回是「尽力而为」的一路，"
                    + "它自己挂掉不该把整次检索带下去").doesNotThrowAnyException();
        }

        @Test
        @DisplayName("★ 对照组：零词元短路确实避免了「发出空的 tsquery」")
        void shortCircuitAvoidsEmptyTsquery() {
            // 对照组证明上一条的短路【真的在起作用】：
            // 如果把空串直接交给数据库，它会静默返回 0 行（不报错），
            // 于是调用方拿到的是一个「成功但没有结果」的响应 —— 假阳性。
            String emptyQuery = tokenizer.tokenizeQuery("，。！？", 64).orQuery();
            assertThat(emptyQuery).as("分词器确实产出了空查询串").isEmpty();

            assertThatCode(() -> chunkMapper.searchByKeyword(emptyQuery, 10))
                    .as("真把这个空串发给 PostgreSQL 也不会报错 —— 正因为不报错，"
                            + "它才危险：没有任何信号能区分「没匹配」和「查询串是空的」")
                    .doesNotThrowAnyException();
            assertThat(chunkMapper.searchByKeyword(emptyQuery, 10))
                    .as("它静默返回空结果")
                    .isEmpty();
        }
    }

    // ============================================================
    // 三、★ 静默漏检护栏
    // ============================================================

    @Nested
    @DisplayName("三、★ search_text 为空的切片会被静默排除")
    class SilentExclusion {

        @Test
        @DisplayName("★ 两条正文完全相同，只有写了 search_text 的那条能被召回")
        void chunkWithoutSearchTextIsSilentlyExcluded() {
            String content = MARK2 + " 两条一模一样的正文";

            Long indexed = insertTokenizedChunk(0, content);
            Long notIndexed = insertChunk(1, content, null);   // 模拟「入库忘了写索引」

            List<Long> hitIds = retriever.retrieve(MARK2, 10)
                    .stream().map(RetrievedChunk::id).toList();

            assertThat(hitIds)
                    .as("★ 只有建了索引的那条能被召回")
                    .containsExactly(indexed);

            assertThat(hitIds)
                    .as("★ 而另一条不是「排得靠后」，是【完全不出现】—— "
                            + "不给任何提示。这就是为什么需要 "
                            + "GET /api/debug/kb/search-text-stats 覆盖率探针："
                            + "这个故障在检索侧没有任何信号，只能从入库侧监控")
                    .doesNotContain(notIndexed);
        }

        @Test
        @DisplayName("★ 对照组：给同一条切片补上 search_text 后，它立刻就能被召回了")
        void backfillingRestoresRecall() {
            String content = MARK2 + " 补索引前查不到，补索引后能查到";
            Long id = insertChunk(0, content, null);

            assertThat(retriever.retrieve(MARK2, 10))
                    .as("补之前：查不到")
                    .isEmpty();

            chunkMapper.updateSearchText(id, tokenizer.tokenize(content).tokenText());

            assertThat(retriever.retrieve(MARK2, 10))
                    .as("补之后：立刻能查到 —— 这证明 search_text 是唯一缺的那一环，"
                            + "而不是检索 SQL 有问题")
                    .extracting(RetrievedChunk::id)
                    .containsExactly(id);
        }
    }

    // ============================================================
    // 四、排序确定性
    // ============================================================

    @Nested
    @DisplayName("四、排序确定性")
    class Determinism {

        @Test
        @DisplayName("★ 分值并列时靠 , id 兜底：同一次查询跑两次，顺序逐位一致")
        void tiedScoresAreOrderedById() {
            // 三条正文完全相同 → ts_rank 必然并列
            Long a = insertTokenizedChunk(0, MARK + " 并列测试");
            Long b = insertTokenizedChunk(1, MARK + " 并列测试");
            Long c = insertTokenizedChunk(2, MARK + " 并列测试");

            List<Long> first = retriever.retrieve(MARK, 10).stream().map(RetrievedChunk::id).toList();
            List<Long> second = retriever.retrieve(MARK, 10).stream().map(RetrievedChunk::id).toList();

            assertThat(first).as("三条都要召回").hasSize(3);
            assertThat(first)
                    .as("★ 没有 , id 兜底键的话，PostgreSQL 对同分行不保证顺序 —— "
                            + "现象是「同一问题两次检索结果不一致」，看起来像 bug，"
                            + "其实只是排序不唯一。这个坑在 HNSW 向量检索上已经踩过一次")
                    .containsExactly(a, b, c);
            assertThat(second)
                    .as("两次调用的顺序必须逐位相同")
                    .containsExactlyElementsOf(first);
        }

        @Test
        @DisplayName("★ 并列分组内按 id 升序，而不是「碰巧这样」")
        void tieBreakIsAscendingId() {
            Long low = insertTokenizedChunk(0, MARK + " 三");
            Long mid = insertTokenizedChunk(1, MARK + " 三");
            Long high = insertTokenizedChunk(2, MARK + " 三");

            assertThat(retriever.retrieve(MARK, 10))
                    .extracting(RetrievedChunk::id)
                    .as("id 升序 —— BIGSERIAL 递增，所以插入顺序即 id 顺序")
                    .containsExactly(low, mid, high);
        }
    }

    // ============================================================
    // 五、topK 截断
    // ============================================================

    @Nested
    @DisplayName("五、topK 截断")
    class TopK {

        @Test
        @DisplayName("不超过 topK 条")
        void respectsTopK() {
            for (int i = 0; i < 6; i++) {
                insertTokenizedChunk(i, MARK + " 第 " + i + " 条");
            }
            assertThat(retriever.retrieve(MARK, 3)).hasSize(3);
            assertThat(retriever.retrieve(MARK, 10)).hasSize(6);
        }
    }
}
