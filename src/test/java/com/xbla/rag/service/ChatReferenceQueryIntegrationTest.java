package com.xbla.rag.service;

import com.xbla.rag.dto.ReferenceDetail;
import com.xbla.rag.entity.KbChunk;
import com.xbla.rag.entity.KbDocument;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.KbChunkMapper;
import com.xbla.rag.mapper.KbDocumentMapper;
import com.xbla.rag.mapper.QaLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 引用原文查询（9.6b 前置）—— <b>「这道口子只放行被引用过的那几片」</b>。
 *
 * <h2>★★★ 这个类守的是【封条】，不是【功能】</h2>
 *
 * <p>功能那一半（有引用 → 返回正文）只有三条用例，而其中两条是在证明
 * <b>「两个字段来自两个地方」</b>这个设计真的生效了。真正占篇幅的是反面：
 *
 * <pre>
 *   没有这道校验，{@code GET /api/chat/refs/{traceId}/{chunkId}} 就是
 *   「知道 chunkId 就能读任意切片」—— 而 kb_chunk.id 是 BIGSERIAL，
 *   连续自增（实测当前 1600+ 条），枚举成本约等于零。
 * </pre>
 *
 * <p>⇒ 能读到的切片必须<b>已经被某条真实回答引用过</b>，
 * 而 {@code traceId} 是一次性、不可猜的。
 *
 * <h2>★★ 反面用例必须是【构造出来的】，不能只靠「碰巧不匹配」</h2>
 *
 * <p>「随便填个大 id 就 404」这种断言是<b>恒真</b>的 —— 它在一个完全不做校验的
 * 实现上也会绿。所以下面做的是：
 *
 * <pre>
 *   ① 造一条【真实存在于 kb_chunk】的切片，但它【不在】references 里
 *      → 必须抛 ChatReferenceNotFoundException
 *   ② 再断言它抛的【不是】KbChunkNotFoundException
 * </pre>
 *
 * <p>★ ② 那一句是这一个类里最值钱的断言：它同时钉住了两件事 ——
 * <b>「引用校验确实跑了」</b>（跑在前面）和<b>「两种取不到是不同的错」</b>。
 * 一行断言，两个不变式。而且它<b>不依赖 mock</b>，所以不会随实现的重构而失效。
 *
 * <h2>★ 用真实 PG（本项目集成测试的统一做法）</h2>
 *
 * <p>{@code @Transactional} 让每条用例自己回滚，测试之间不互相看见。
 */
@SpringBootTest
@Transactional
@DisplayName("ChatReferenceQueryService · 引用原文")
class ChatReferenceQueryIntegrationTest {

    @Autowired
    private ChatReferenceQueryService refQueryService;

    @Autowired
    private QaLogMapper qaLogMapper;

    @Autowired
    private KbChunkMapper chunkMapper;

    @Autowired
    private KbDocumentMapper documentMapper;

    // ============================================================
    // 夹具
    // ============================================================

    /** 每次用例各自造文档 —— 不共用，避免用例之间通过 slice 互相看见 */
    private Long documentId;

    @BeforeEach
    void setUp() {
        KbDocument doc = new KbDocument();
        doc.setDocNo("D-REF-" + System.nanoTime());
        doc.setTitle("引用原文测试文档");
        doc.setDocType(2);
        doc.setSourceType(1);
        doc.setStatus(3);
        documentMapper.insert(doc);
        documentId = doc.getId();
    }

    /** 造一条真的落在 kb_chunk 里的切片，返回它的 id */
    private Long newChunk(String content, String headingPath) {
        KbChunk chunk = new KbChunk();
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex((int) (System.nanoTime() % 100000));
        chunk.setContent(content);
        chunk.setHeadingPath(headingPath);
        chunk.setDocType(2);
        chunkMapper.insert(chunk);
        return chunk.getId();
    }

    /**
     * 造一行 qa_log，{@code references} 里放给定的切片 id 列表。
     *
     * <p>★ {@code document_id} 与 {@code heading_path} 刻意写成
     * <b>「和 kb_chunk 那一行不同」</b>的值（{@code -1} / 指定字符串）——
     * 那两个字段该从哪里取，就是下面第一条用例要钉住的事。
     */
    private String newQaLogWithRefs(Long... chunkIds) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < chunkIds.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"no\":").append(i + 1)
              .append(",\"chunk_id\":").append(chunkIds[i])
              // ★ 这三个故意和 kb_chunk 里那一行不一致
              .append(",\"document_id\":-1")
              .append(",\"score\":0.9")
              .append(",\"heading_path\":\"来自-references-的标题\"}");
        }
        return sb.append(']').toString();
    }

    private String insertQaLog(String traceId, String referencesJson) {
        QaLog row = new QaLog();
        row.setTraceId(traceId);
        row.setQuestion("退货要几天");
        row.setReferences(referencesJson);
        qaLogMapper.insert(row);
        return traceId;
    }

    private String trace(String suffix) {
        return "REF-TRACE-" + System.nanoTime() + "-" + suffix;
    }

    // ============================================================
    // 一、取原文
    // ============================================================

    @Nested
    @DisplayName("一、取原文")
    class HappyPath {

        @Test
        @DisplayName("引用里真有这一片 → 返回正文")
        void returnsContent() {
            Long chunkId = newChunk("七天无理由退货，自签收次日起算。", "售后政策 > 退货");
            String t = insertQaLog(trace("hit"), newQaLogWithRefs(chunkId));

            ReferenceDetail d = refQueryService.referenceDetail(t, chunkId);

            assertThat(d.chunkId()).isEqualTo(chunkId);
            assertThat(d.content()).isEqualTo("七天无理由退货，自签收次日起算。");
        }

        /**
         * ★★ 正面：正文<b>跟着 kb_chunk 变</b> —— 证明它是现读的。
         *
         * <p>反面对照就是下一条用例（标题<b>不</b>跟着变）。
         */
        @Test
        @DisplayName("★ 正文来自 kb_chunk【现读】：库里改了，接口跟着改")
        void contentIsLive() {
            Long chunkId = newChunk("旧正文", "售后政策 > 退货");
            String t = insertQaLog(trace("live"), newQaLogWithRefs(chunkId));

            assertThat(refQueryService.referenceDetail(t, chunkId).content())
                    .isEqualTo("旧正文");

            KbChunk update = new KbChunk();
            update.setId(chunkId);
            update.setContent("新正文（切片被重新解析过）");
            chunkMapper.updateById(update);

            assertThat(refQueryService.referenceDetail(t, chunkId).content())
                    .isEqualTo("新正文（切片被重新解析过）");
        }

        /**
         * ★★ 反面：{@code document_id} / {@code heading_path} <b>不</b>跟着 kb_chunk 变。
         *
         * <p>夹具里那两列故意和 {@code kb_chunk} 里那一行不同（{@code -1} /
         * 「来自-references-的标题」），而断言要求返回的正是 references 那一份。
         *
         * <h3>为什么这值得一条用例</h3>
         *
         * <p>因为「同一条数据在两处显示不一致」是最容易被读成 bug 的现象：
         * 用户在<b>列表里</b>看到标题 A、点开<b>弹窗</b>看到标题 B，
         * 他的第一反应是「这系统数据是乱的」，而不是「这是两个来源」。
         * 所以标题以 {@code references}（= 列表显示的那一份）为准，
         * 而这条纪律没有任何编译期保证 —— 只能靠这条用例钉住。
         */
        @Test
        @DisplayName("★★ 标题与文档 id 来自 references【那一条】，不是现查 kb_chunk")
        void titleComesFromReferencesNotLive() {
            Long chunkId = newChunk("正文", "kb_chunk-里的标题");
            String t = insertQaLog(trace("title"), newQaLogWithRefs(chunkId));

            ReferenceDetail d = refQueryService.referenceDetail(t, chunkId);

            assertThat(d.headingPath())
                    .as("必须和列表里显示的那一份逐字一致")
                    .isEqualTo("来自-references-的标题")
                    .isNotEqualTo("kb_chunk-里的标题");
            assertThat(d.documentId()).isEqualTo(-1L);
        }

        @Test
        @DisplayName("heading_path 缺失 → null，不是「(无标题路径)」那种界面文案")
        void nullHeadingStaysNull() {
            Long chunkId = newChunk("正文", null);
            String t = insertQaLog(trace("nohead"),
                    "[{\"no\":1,\"chunk_id\":" + chunkId + ",\"score\":0.9}]");

            assertThat(refQueryService.referenceDetail(t, chunkId).headingPath()).isNull();
        }
    }

    // ============================================================
    // 二、★★★ 封条
    // ============================================================

    @Nested
    @DisplayName("二、★★★ 封条：不是这条回答引用过的，读不到")
    class Seal {

        /**
         * ★★★ 全类最值钱的一条。
         *
         * <p>造一片<b>真实存在</b>于 {@code kb_chunk} 的切片，但它不在 references 里。
         * 期望：抛 {@link ChatReferenceNotFoundException}，
         * <b>而不是</b> {@link KbChunkNotFoundException}。
         *
         * <p>反过来读：如果实现省掉了引用校验、直接去取正文，
         * 那么这一片<b>取得到</b>（它真的在库里）—— 于是这条用例会红在
         * 「没抛异常」上。封条就是靠这一句成立的。
         */
        @Test
        @DisplayName("★★ 切片真实存在但没被引用过 → 拒；而且拒的理由是【引用】不是【正文】")
        void existingChunkNotCited() {
            Long cited = newChunk("被引用的那一片", "A");
            Long notCited = newChunk("没被引用的那一片", "B");
            String t = insertQaLog(trace("seal"), newQaLogWithRefs(cited));

            assertThatThrownBy(() -> refQueryService.referenceDetail(t, notCited))
                    .as("切片在 kb_chunk 里真的存在，取不到的原因只能是【没被引用过】")
                    .isInstanceOf(ChatReferenceNotFoundException.class)
                    .isNotInstanceOf(KbChunkNotFoundException.class);
        }

        @Test
        @DisplayName("连着试一批 id 都拒 —— 不能只有某一个碰巧被挡住")
        void sealsEveryUncitedId() {
            Long cited = newChunk("唯一被引用的那一片", "A");
            String t = insertQaLog(trace("seal-many"), newQaLogWithRefs(cited));

            // ★ 这些**不是**随机大数，而是真实存在的其它切片 id ——
            //   随机大数在「完全不校验」的实现上也会 404（库里没有它），
            //   那样这条断言就是恒真的
            for (int i = 0; i < 5; i++) {
                Long other = newChunk("第 " + i + " 片没被引用的", "X" + i);
                Long id = other;
                assertThatThrownBy(() -> refQueryService.referenceDetail(t, id))
                        .isInstanceOf(ChatReferenceNotFoundException.class);
            }
        }

        @Test
        @DisplayName("references 为 null（没检索/工具轮）→ 拒，不是「返回空对象」")
        void nullReferencesIsNotOpenBar() {
            Long chunkId = newChunk("正文", "A");
            String t = insertQaLog(trace("nullrefs"), null);

            assertThatThrownBy(() -> refQueryService.referenceDetail(t, chunkId))
                    .isInstanceOf(ChatReferenceNotFoundException.class);
        }

        @Test
        @DisplayName("references 是【空数组】→ 也拒（「没有引用」不是「不限制」）")
        void emptyArrayIsNotOpenBar() {
            Long chunkId = newChunk("正文", "A");
            String t = insertQaLog(trace("emptyrefs"), "[]");

            assertThatThrownBy(() -> refQueryService.referenceDetail(t, chunkId))
                    .as("★ 这里最容易写错成「空数组 = 不限制」—— 那是 doc_types 的语义（ADR-044），"
                            + "不是 references 的")
                    .isInstanceOf(ChatReferenceNotFoundException.class);
        }

        /**
         * ★ {@code chunk_id} 不是数字的行<b>不能</b>被当成 id=0。
         *
         * <p>用 {@code path("chunk_id").asLong()} 取的话，键缺失时它给 <b>0</b> ——
         * 而 0 是一个合法的 id 形状，于是「这条没有 chunk_id」会被读成
         * 「它引用的是 0 号切片」。这跟坑 24（{@code exists()} 分不清
         * 「键不存在」和「键是 null」）是同一类错误。
         */
        @Test
        @DisplayName("★ chunk_id 不是数字的行 → 不能当成 id=0 匹配上")
        void nonNumericChunkIdIsNotZero() {
            Long chunkId = newChunk("正文", "A");
            String t = insertQaLog(trace("nonnum"),
                    "[{\"no\":1,\"chunk_id\":null,\"score\":0.9},"
                            + "{\"no\":2,\"chunk_id\":\"348\",\"score\":0.9},"
                            + "{\"no\":3,\"chunk_id\":" + chunkId + ",\"score\":0.9}]");

            // 真被引用的那一片仍然取得到（证明上面两行没把整流搅坏）
            assertThat(refQueryService.referenceDetail(t, chunkId).content()).isEqualTo("正文");

            // 而 0 不因为它出现在坏行里就被放行
            assertThatThrownBy(() -> refQueryService.referenceDetail(t, 0L))
                    .isInstanceOf(ChatReferenceNotFoundException.class);
        }
    }

    // ============================================================
    // 三、三种「取不到」是三句话
    // ============================================================

    @Nested
    @DisplayName("三、三种取不到分得开")
    class Distinguishable {

        @Test
        @DisplayName("traceId 不存在 → 说的是【链路详情】")
        void unknownTrace() {
            assertThatThrownBy(() -> refQueryService.referenceDetail("no-such-trace", 1L))
                    .isInstanceOf(ChatTraceNotFoundException.class)
                    .hasMessageContaining("链路详情");
        }

        @Test
        @DisplayName("★ 引用里没这片 → 说的是【引用】，不是【链路】")
        void unknownReference() {
            Long chunkId = newChunk("正文", "A");
            String t = insertQaLog(trace("distinct"), newQaLogWithRefs(chunkId));

            assertThatThrownBy(() -> refQueryService.referenceDetail(t, chunkId + 999999))
                    .isInstanceOf(ChatReferenceNotFoundException.class)
                    .hasMessageContaining("引用")
                    // ★ 反面：不能是链路那条 —— 两者都 404，只有 message 分得开
                    .hasMessageNotContaining("链路详情");
        }

        /**
         * ★ 这一条把 {@link KbChunkNotFoundException} 的存在钉住。
         *
         * <p>它在正常情况下<b>不可达</b>（{@code kb_chunk} 只软删，而取正文那条
         * 查询不过滤 {@code deleted}）。所以它不是靠「跑得到」来证明自己的 ——
         * 它靠<b>不可达时也不出 500</b> 来证明。
         *
         * <p>⚠️ 这里<b>不</b>去构造那个场景（要物理删一行，而那是全仓唯一一处
         * 会对 kb_chunk 做物理删除的代码 —— 为了一条用例引入它，代价比收益大）。
         * 所以这一条只断言「类型存在且是 404 家族」，不假装跑过了那条路。
         */
        @Test
        @DisplayName("★ KbChunkNotFoundException 是 404 家族 —— 不可达的分支也不许变成 500")
        void chunkGoneIsStill404() {
            KbChunkNotFoundException e = new KbChunkNotFoundException(348L);

            assertThat(e).isInstanceOf(ResourceNotFoundException.class);
            assertThat(((ResourceNotFoundException) e).getKind()).isEqualTo("切片正文");
        }
    }
}
