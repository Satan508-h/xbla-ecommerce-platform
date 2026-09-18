package com.xbla.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xbla.rag.entity.KbChunk;
import com.xbla.rag.rag.retrieve.VectorHit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库切片表 Mapper。
 *
 * <p>对应数据库表 {@code kb_chunk}。
 *
 * <p>继承 {@code BaseMapper<KbChunk>} 后自带 insert / selectById / updateById /
 * deleteById / selectList / selectCount 等方法，无需手写 SQL。
 * 复杂查询（向量检索、阶段 5 的 MCP 工具 SQL）用注解 SQL 写在这里。
 */
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * ★ <b>物理删除</b>某份文档的所有切片。
     *
     * <p><b>为什么不能直接用 {@code BaseMapper.delete(...)}</b>
     *
     * <p>因为项目在 {@code application.yml} 里配置了全局逻辑删除：
     * <pre>
     *   mybatis-plus.global-config.db-config:
     *     logic-delete-field: deleted
     *     logic-delete-value: 1
     * </pre>
     * 只要实体上有 {@code deleted} 字段，MyBatis-Plus 就会<b>自动</b>把
     * {@code delete} 改写成 {@code UPDATE ... SET deleted = 1}。
     * 所以调 {@code delete()} 得到的<b>不是物理删除</b>，
     * 而这一点在代码上看不出来 —— 这是最容易踩的坑之一。
     *
     * <p><b>为什么这里需要真正的物理删除</b>
     * <ul>
     *   <li><b>重新入库前清场</b>：同一份文档重新切分后旧切片必须消失。
     *       逻辑删除虽然查不到，但行还占着表空间和 HNSW 索引节点，
     *       反复入库几次索引里就积了一堆死数据。</li>
     *   <li><b>入库失败后的补偿</b>：见 {@code DocumentIngestWorker}，
     *       失败时要把这次写的半截切片清掉。这些内容从来没被成功入库过，
     *       留痕没有意义。</li>
     * </ul>
     *
     * <p>逻辑删除是为「业务上删除但要留痕」设计的（见 docs/04 §1.2 的表分类），
     * 而这里两个场景都不属于「留痕」。
     *
     * <p>⚠️ 用注解 SQL 而不是 XML：这是单表条件删除，注解一行能写清楚。
     * 复杂的多表查询再考虑 XML。
     */
    @Delete("DELETE FROM kb_chunk WHERE document_id = #{documentId}")
    int physicalDeleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 只查切片的 id 列表，按文档 ID。<b>不查 embedding 列</b>。
     *
     * <p>为什么要单独一个方法：{@code selectList} 会把 {@code embedding} 一起读出来，
     * 而那是 1024 个 float —— 一份 300 片的文档就是 300 × 1024 × 4 字节 ≈ 1.2 MB
     * 的无用数据，全部走一遍 JDBC 解析。
     * <b>只选需要的列</b>是查询优化的第一条基本功。
     *
     * <p>用途：验证入库结果、阶段 7 统计切片分布。
     */
    @org.apache.ibatis.annotations.Select(
            "SELECT id FROM kb_chunk WHERE document_id = #{documentId} AND deleted = 0 ORDER BY chunk_index")
    List<Long> selectIdsByDocumentId(@Param("documentId") Long documentId);

    /**
     * ★ 向量召回：按余弦相似度取最相似的 topK 个切片。
     *
     * <p>这是 RAG 检索链路的核心查询。阶段 4 的 {@code VectorRetriever}
     * 会在这条 SQL 上叠加意图过滤（{@code doc_type} / {@code related_product_id} /
     * 时效条件）—— 那也正是 {@code kb_chunk} 要冗余这几个字段的原因：
     * 过滤条件和向量排序能走同一次索引扫描，不用 join 回 {@code kb_document}。
     *
     * <h4>SQL 里三个关键点</h4>
     *
     * <p><b>① {@code <=>} 是余弦「距离」，不是相似度。</b>
     * 实测语义（在本项目数据库上跑出来的，不是查文档）：
     * <pre>
     *   [1,0,0] &lt;=&gt; [1,0,0]   = 0    相同
     *   [1,0,0] &lt;=&gt; [0,1,0]   = 1    正交
     *   [1,0,0] &lt;=&gt; [-1,0,0]  = 2    相反
     * </pre>
     * 所以 {@code ORDER BY embedding <=> ?} 默认是<b>升序</b>（距离小的在前）。
     * 返回值里用 {@code 1 - 距离} 换成相似度，让人看着直观。
     *
     * <p><b>② ★ {@code ORDER BY ... , id} 里那个 {@code id} 不能省。</b>
     * HNSW 是<b>近似</b>索引，它不保证距离相等的向量以固定顺序返回 ——
     * 底层是图遍历，顺序取决于遍历路径。
     * 少了这个兜底键，「同一个问题检索两次结果一致」这条验收标准就<b>会随机失败</b>，
     * 而且失败时看起来像是「检索不稳定」，排查方向会完全跑偏。
     * 加上 {@code id} 之后排序是全序的，结果可复现。
     *
     * <p><b>③ {@code CAST(#{vector} AS vector)} 的显式转换。</b>
     * 参数是 Java String，靠 JDBC URL 的 {@code stringtype=unspecified}
     * 以「未指定类型」发给 PostgreSQL。显式 CAST 让 PG 不用去猜
     * 这个未知类型的参数该按哪种类型解析。
     *
     * @param vector 查询向量的文本形式 {@code [0.1,0.2,...]}。
     *               可以用 {@code VectorTypeHandler.toLiteral(float[])} 生成
     * @param topK   返回条数
     */
    @org.apache.ibatis.annotations.Select("""
            SELECT id,
                   document_id,
                   chunk_index,
                   content,
                   heading_path,
                   (1 - (embedding <=> CAST(#{vector} AS vector))) AS score
            FROM kb_chunk
            WHERE deleted = 0
              AND embedding IS NOT NULL
            ORDER BY embedding <=> CAST(#{vector} AS vector), id
            LIMIT #{topK}
            """)
    List<VectorHit> searchByVector(@Param("vector") String vector, @Param("topK") int topK);
}
