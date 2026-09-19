package com.xbla.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xbla.rag.entity.KbChunk;
import com.xbla.rag.rag.retrieve.VectorHit;
import com.xbla.rag.rag.tokenize.SearchTextSource;
import com.xbla.rag.rag.tokenize.SearchTextStats;
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

    // ================================================================
    // 关键词召回（阶段 4 · 4.2）
    // ================================================================

    /**
     * ★ <b>关键词召回</b>：用中文双字切分后的 tsquery 做全文检索，按 {@code ts_rank} 排序。
     *
     * <p><b>① 为什么必须用 {@code to_tsquery} 拼 {@code |}，不能用 {@code plainto_tsquery}</b>
     *
     * <p>{@code plainto_tsquery} 生成的是 <b>AND 语义</b>。中文查询「退货要几天」
     * 会被拆成 {@code 退 &amp; 货 &amp; 要 &amp; 几 &amp; 天}，
     * 要求五个字<b>全部</b>出现在同一个切片里 —— 实测命中 <b>0 行</b>。
     * 必须手工拼 {@code |} 再靠 {@code ts_rank} 排序。
     *
     * <p><b>② 为什么 {@code ORDER BY} 必须带 {@code , id} 兜底键</b>
     *
     * <p>和 {@link #searchByVector} 是<b>完全同一类问题</b>：
     * bigram OR 查询的 {@code ts_rank} 分数很粗，<b>并列极其常见</b>，
     * 而 PostgreSQL 的排序不保证稳定。少了兜底键，
     * 同一个问题两次检索会给出不同的顺序，看起来像「检索不稳定」。
     *
     * <p><b>③ 为什么 {@code search_vector IS NOT NULL} 这个条件不能省</b>
     *
     * <p>不是性能优化，是<b>正确性</b>：{@code NULL @@ tsquery} 的结果是 NULL，
     * 在 {@code WHERE} 里等于是 false（不报错）。理论上 {@code @@} 已经把它排除了，
     * 显式写出来是为了让「这一行没有索引」这件事对读代码的人可见 ——
     * 配套的覆盖率探针见 {@link #searchTextStats()}。
     *
     * @param query 由 {@code SearchText.orQuery()} 生成的 {@code |} 分隔查询串。
     *              ⚠️ <b>调用方必须先检查 {@code SearchText.isEmpty()}</b> ——
     *              实测 {@code to_tsquery('simple','')} <b>不抛异常</b>，
     *              只发一个 NOTICE 然后返回空 tsquery，{@code @@} 恒为 false，
     *              <b>静默返回 0 行</b>，和「真的没有匹配」无法区分
     * @param topK  返回条数
     */
    @org.apache.ibatis.annotations.Select("""
            SELECT id,
                   document_id,
                   chunk_index,
                   content,
                   heading_path,
                   ts_rank(search_vector, CAST(#{query} AS tsquery)) AS score
            FROM kb_chunk
            WHERE deleted = 0
              AND search_vector IS NOT NULL
              AND search_vector @@ CAST(#{query} AS tsquery)
            ORDER BY score DESC, id
            LIMIT #{topK}
            """)
    List<VectorHit> searchByKeyword(@Param("query") String query, @Param("topK") int topK);

    // ================================================================
    // search_text 的维护（阶段 4 · 回填与重建）
    // ================================================================

    /**
     * 按主键游标分批取「需要重新分词」的切片。
     *
     * <p><b>① 为什么用 keyset 分页而不是 {@code OFFSET}</b>
     *
     * <p>{@code OFFSET} 要让数据库扫描并丢弃前 N 行，翻到后面越来越慢。
     * 而更实际的问题是<b>不可续跑</b>：中途失败重来时 offset 要从头数。
     * keyset（{@code WHERE id > 上一批的最大 id}）天然可续跑 ——
     * 再点一次接口就接着上次的位置继续。
     *
     * <p><b>② 为什么只选 id 和 content</b>
     *
     * <p>{@code kb_chunk} 有一个 1024 维的 {@code embedding}。
     * 用 {@code selectList} 之类的全列查询会把
     * 1652 × 1024 × 4B ≈ <b>6.8MB</b> 的向量白白读进内存，
     * 而且<b>不会报错、只会变慢</b>。返回类型用
     * {@link SearchTextSource}（只有两个字段），这种误用在类型层面就写不出来。
     *
     * @param lastId      上一批的最大 id；传 0 从头开始
     * @param onlyMissing true 时只取 {@code search_text} 为空的行（日常回填）；
     *                    false 时取全部（<b>换了分词器后的全量重建</b>）
     * @param size        本批条数
     */
    @org.apache.ibatis.annotations.Select("""
            SELECT id, content
            FROM kb_chunk
            WHERE deleted = 0
              AND id > #{lastId}
              AND (#{onlyMissing} = false
                   OR search_text IS NULL
                   OR search_text = '')
            ORDER BY id
            LIMIT #{size}
            """)
    List<SearchTextSource> selectForReindex(@Param("lastId") long lastId,
                                            @Param("onlyMissing") boolean onlyMissing,
                                            @Param("size") int size);

    /**
     * 写入一个切片的 {@code search_text}。
     *
     * <p>{@code search_vector} <b>不用管</b> —— 它是生成列，
     * 数据库会自动重算，GIN 索引也会自动维护。
     * 这正是选生成列而不是手工维护 tsvector 的原因之一。
     *
     * <p>{@code updated_at} 手工 set：本项目的时间戳自动填充
     * （{@code MybatisPlusMetaObjectHandler}）只对 MyBatis-Plus 的
     * {@code updateById} 生效，走原生 SQL 时要自己写。
     * （同一个坑在阶段 2 的 {@code chat_session.last_active_at} 上踩过一次。）
     */
    @org.apache.ibatis.annotations.Update("""
            UPDATE kb_chunk
            SET search_text = #{searchText},
                updated_at  = now()
            WHERE id = #{id}
            """)
    int updateSearchText(@Param("id") Long id, @Param("searchText") String searchText);

    /**
     * {@code search_text} 的覆盖率与规模统计。
     *
     * <p>★ 判定「漏了」的条件是 {@code IS NULL OR = ''} <b>两个</b>：
     * {@code to_tsvector('simple','')} 得到的是<b>空 tsvector 而不是 NULL</b>，
     * 所以只查 {@code IS NULL} 会漏掉「写了空串」这一类 ——
     * 而它们同样会被关键词检索静默排除。
     *
     * <p>用 {@code cardinality} 而不是 {@code array_length(...,1)} 数词元：
     * 后者对空数组返回 NULL 而不是 0，会让整行统计变成 NULL。
     *
     * <p>这里是<b>唯一</b>允许返回裸统计结构而不是实体的地方 ——
     * 它不映射任何一张表的完整行。
     */
    @org.apache.ibatis.annotations.Select("""
            SELECT count(*)                                                            AS total,
                   count(*) FILTER (WHERE search_text IS NULL OR search_text = '')
                                                                                       AS missing,
                   coalesce(min(cardinality(string_to_array(search_text, ' '))), 0)    AS min_tokens,
                   coalesce(round(avg(cardinality(string_to_array(search_text, ' ')))), 0)
                                                                                       AS avg_tokens,
                   coalesce(max(cardinality(string_to_array(search_text, ' '))), 0)    AS max_tokens
            FROM kb_chunk
            WHERE deleted = 0
            """)
    SearchTextStats searchTextStats();

    /**
     * 按「内容锚点」反查切片 ID —— 评测集加载用（阶段 4 · 4.8）。
     *
     * <p><b>为什么评测集不直接存 chunk_id</b>：那个值是 BIGSERIAL，
     * 每台机器、每次重灌语料都不一样。硬编码的 id 提交进 git 后，
     * 换台机器跑基线 <b>不会报错</b>，只会让 Recall@K 静默变成 0 或随机数 ——
     * 这是「阶段 7 的 A/B 对比做不了」最隐蔽的一种成因。
     *
     * <p>改为存「该切片里独有的一段原文」，加载时反查。
     * <b>调用方必须检查返回条数</b>：0 条说明语料变了，多条说明锚点不够独特 ——
     * 两种情况都要抛异常。静默取第一条会让基线悄悄漂移，
     * 而评测系统最不能犯的错就是「悄悄」。
     */
    @org.apache.ibatis.annotations.Select("""
            SELECT id
            FROM kb_chunk
            WHERE deleted = 0
              AND content LIKE '%' || #{anchor} || '%'
            ORDER BY id
            """)
    List<Long> findIdsByContentAnchor(@Param("anchor") String anchor);
}
