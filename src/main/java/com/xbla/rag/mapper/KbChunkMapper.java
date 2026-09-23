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
     * <p><b>④ ★ {@code docTypes} 是「意图定向检索」的过滤条件（阶段 5.4）。</b>
     * 传 {@code null} 表示不加限制。
     *
     * <p>它的写法 {@code (CAST(X AS int[]) IS NULL OR doc_type = ANY(...))} 和
     * {@link #selectForReindex} 的 {@code #{onlyMissing} = false OR ...} 是<b>同一个模式</b>：
     * 用一条固定 SQL 覆盖两种调用，而不是写两条几乎相同的语句。
     * 代价是 <b>null 判断必须写全</b> —— 少了 {@code IS NULL} 那一半，
     * {@code doc_type = ANY(CAST(NULL AS int[]))} 求值为 {@code NULL}，
     * 在 {@code WHERE} 里等于是 false，症状是「不过滤的时候一条都查不到」。
     *
     * <p>⚠️ <b>而且那个 {@code IS NULL} 的左边必须也套一层 CAST。</b>
     * 写成裸的 {@code #{docTypes} IS NULL} 会让 PostgreSQL 直接报
     * <b>{@code could not determine data type of parameter $N}</b> ——
     * 因为在 {@code $N IS NULL} 里没有任何东西能告诉它这个参数是什么类型。
     * 而 JDBC URL 上的 {@code stringtype=unspecified} 让驱动把字符串
     * <b>按「未知类型」</b>发出去，于是「推断不出来」就成了报错而不是默认值。
     *
     * <p>★ 这个坑值得单独记一笔，因为它<b>在 Java 侧完全看不出来</b>：
     * 参数、返回值类型、编译结果全都正常，只有真跑一次 SQL 才会炸。
     * 而 {@code CAST(X AS int[]) IS NULL} 和 {@code X IS NULL} 在语义上完全等价 ——
     * 那层 CAST 唯一的用途是<b>给 PostgreSQL 一个类型线索</b>。
     *
     * <p>⚠️ 值必须是<b>数组字面量字符串</b>（{@code "{2,4}"}），由
     * {@code RetrievalOptions.docTypesLiteral()} 生成 ——
     * 那里在结构上保证只有数字和逗号，所以不需要在这里做转义。
     *
     * <p><b>⑤ 加了这个条件之后，PostgreSQL 会换一个计划。</b>实测：
     * <pre>
     *   不过滤：Index Scan using idx_kb_chunk_embedding (HNSW)   ← 近似，可能漏
     *   过滤：  Index Scan using idx_kb_chunk_doc_type + Sort    ← 精确，全扫子集
     * </pre>
     * 规划器在有过滤条件时干脆<b>不用 HNSW</b>，改成在子集上精确扫描 + 排序。
     * 也就是说过滤顺带把这一路从「近似检索」变成了「精确检索」。
     * 当前规模（1640 行）下这是白拿的；表大了规划器会翻回 HNSW + 过滤，
     * 那时靠 {@code hnsw.iterative_scan}（默认 off）。见 {@code docs/05}。
     *
     * @param vector   查询向量的文本形式 {@code [0.1,0.2,...]}。
     *                 可以用 {@code VectorTypeHandler.toLiteral(float[])} 生成
     * @param topK     返回条数
     * @param docTypes 允许的 {@code doc_type} 数组字面量，如 {@code "{2,4}"}；
     *                 <b>{@code null} = 不限制</b>
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
              AND (CAST(#{docTypes,jdbcType=VARCHAR} AS int[]) IS NULL
                   OR doc_type = ANY(CAST(#{docTypes,jdbcType=VARCHAR} AS int[])))
            ORDER BY embedding <=> CAST(#{vector} AS vector), id
            LIMIT #{topK}
            """)
    List<VectorHit> searchByVector(@Param("vector") String vector,
                                   @Param("topK") int topK,
                                   @Param("docTypes") String docTypes);

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
     * @param query    由 {@code SearchText.orQuery()} 生成的 {@code |} 分隔查询串。
     *                 ⚠️ <b>调用方必须先检查 {@code SearchText.isEmpty()}</b> ——
     *                 实测 {@code to_tsquery('simple','')} <b>不抛异常</b>，
     *                 只发一个 NOTICE 然后返回空 tsquery，{@code @@} 恒为 false，
     *                 <b>静默返回 0 行</b>，和「真的没有匹配」无法区分
     * @param topK     返回条数
     * @param docTypes 允许的 {@code doc_type} 数组字面量，如 {@code "{2,4}"}；
     *                 <b>{@code null} = 不限制</b>。语义与坑④见 {@link #searchByVector}
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
              AND (CAST(#{docTypes,jdbcType=VARCHAR} AS int[]) IS NULL
                   OR doc_type = ANY(CAST(#{docTypes,jdbcType=VARCHAR} AS int[])))
            ORDER BY score DESC, id
            LIMIT #{topK}
            """)
    List<VectorHit> searchByKeyword(@Param("query") String query,
                                    @Param("topK") int topK,
                                    @Param("docTypes") String docTypes);

    /**
     * ★ <b>某个 {@code doc_type} 集合下有多少条切片</b> —— 5.4「值不值得过滤」判据的输入。
     *
     * <p>用途：{@code RetrievalPipeline} 在真正下推过滤条件之前问它一句
     * 「过滤之后还剩多少」。池子比 {@code vector-top-k} 还小就直接不过滤，
     * 理由见 {@code RetrievalPipeline} 的 {@code resolveScope}。
     *
     * <p><b>为什么不带该路自己的守卫条件</b>（向量路要 {@code embedding IS NOT NULL}、
     * 关键词路要 {@code search_vector IS NOT NULL}）：这个数只用来做一次
     * <b>量级判断</b>，不需要精确到「这一路实际能用几条」。而且两个守卫
     * 对应的失败（忘了写 {@code search_text}、向量没算出来）
     * 各自已经有探针了（{@code /api/debug/kb/search-text-stats}），
     * 在这里再算一遍会把「池子小」和「索引没建好」两种原因混成一个数。
     *
     * <p>⚠️ 这个数会进 {@code retrieval_detail.filter.pool_size} ——
     * 出问题时第一个该看的就是它：池子小说明意图声明得窄，
     * 池子大却召回不到说明是检索或切分的问题。
     */
    @org.apache.ibatis.annotations.Select("""
            SELECT count(*)
            FROM kb_chunk
            WHERE deleted = 0
              AND doc_type = ANY(CAST(#{docTypes} AS int[]))
            """)
    int countByDocTypes(@Param("docTypes") String docTypes);

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
     * 校正某份文档下所有切片的冗余 {@code doc_type}。
     *
     * <p><b>为什么需要单独一个方法</b>：{@code kb_chunk.doc_type} 是
     * <b>冗余</b>自 {@code kb_document} 的列（阶段 5 的意图定向检索靠它，
     * 这样过滤条件和向量排序能走同一次索引扫描，不用 join 回主表）。
     * 冗余就意味着<b>两处必须同步改</b> —— 只改主表，检索过滤用的还是旧值，
     * 而这种不一致在界面上完全看不出来。
     *
     * <p><b>为什么这条 UPDATE 不需要重新向量化</b>：{@code doc_type} 是元数据，
     * 不参与 {@code embedding} 的计算，也不参与 {@code search_text} 的分词。
     * 所以语料清单改了归类之后，只要改这一列即可 ——
     * 这正是「把 doc_type 做成可校正的」的价值。
     *
     * <p>{@code updated_at} 手工 set：时间戳自动填充
     * （{@code MybatisPlusMetaObjectHandler}）只对 MyBatis-Plus 的
     * {@code updateById} 生效，走原生 SQL 时要自己写。同一个坑见
     * {@link #updateSearchText}。
     */
    @org.apache.ibatis.annotations.Update("""
            UPDATE kb_chunk
            SET doc_type   = #{docType},
                updated_at = now()
            WHERE document_id = #{documentId}
            """)
    int updateDocTypeByDocumentId(@Param("documentId") Long documentId,
                                  @Param("docType") int docType);

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

    /**
     * 全库「切片 → doc_type」的对照表 —— 阶段 7 的<b>过度检索率</b>用。
     *
     * <p>评测报告要判的是一条切片有没有越界，而 {@code retrieval_detail} 的
     * {@code final_top_k} 里只有 id。<b>没有这张对照表，「越界」无从判起。</b>
     *
     * <h3>★ 为什么是「全表」而不是「按 id 批量查」</h3>
     *
     * <p>看着像 N+1 的懒惰做法，实际不是：
     * <ul>
     *   <li>规模是 <b>1640 行 × 两个短字段</b>（2026-09-21 实测），
     *       一次查询大约十几 KB —— 而它服务的是<b>一整轮报告的几百行</b>；
     *       按 id 批量查反而要为一轮报告拼一个几百元素的数组参数</li>
     *   <li>★ 按 id 查会撞上 CLAUDE.md 记的那条坑：
     *       {@code ANY(#{ids})} 在 PostgreSQL 上会报
     *       {@code could not determine data type of parameter $1}，
     *       得靠一层 {@code CAST} 给它类型线索 —— 为一个调试端点引入
     *       「参数类型推断」这种失败模式，不划算</li>
     *   <li>全表读的语义是<b>完整</b>的：查不到的 id 一定是「已经不在库里」，
     *       而不是「这次没传进参数」。归因里这两种要分开（见
     *       {@code EvalReportService} 里对 {@code docType == null} 的处理）</li>
     * </ul>
     *
     * <p>⚠️ 这条查询的代价随语料增长，而增长的是<b>切片数</b>（千级），
     * 不是题数。真到十万级切片时该换成按 id 查 —— 那时它也值得配一个 TypeHandler。
     *
     * <p>★ {@code deleted = 0}：软删的切片不算。若某条正解切片已被软删，
     * 它会<b>缺席</b>于这张表，报告端点必须把那种行单独数出来，
     * 而不是当成「不在期望范围内」—— 那是把「数据没了」读成「检索偏了」。
     */
    @org.apache.ibatis.annotations.Select("""
            SELECT id AS chunk_id, doc_type
            FROM kb_chunk
            WHERE deleted = 0
            ORDER BY id
            """)
    List<com.xbla.rag.rag.eval.ChunkDocType> findAllChunkDocTypes();

    /**
     * 按 id 取切片正文 —— 阶段 7.5 给 RAGAS 还原 {@code retrieved_contexts} 用。
     *
     * <p>{@code qa_log.references} 里只有 {@code chunk_id} 和标题路径，<b>没有正文</b>
     * （同 {@code retrieval_detail} 的约定：正文在 {@code kb_chunk} 里，按 id 取回）。
     *
     * <p>★ <b>为什么用 {@code <foreach>} 而不是 {@code ANY(#{ids}::bigint[])}</b>：
     * 那条路要 MyBatis 把一个 {@code List<Long>} 直接映射成 PG 的 {@code bigint[]}，
     * 得额外配 TypeHandler 并处理空列表（空列表会让 {@code ANY} 变成
     * 「匹配不到任何行」—— 那是对的，但 PG 那边会先因为 {@code $N} 的类型推断失败）。
     * {@code <script>} + {@code <foreach>} 是 MyBatis 的常规路径，没有这些坑。
     *
     * <p>⚠️ <b>不在这里截断正文</b>：截断规则属于 {@code RagPromptBuilder}
     * （它决定模型看到什么）。在 SQL 里再写一遍就是第二个事实来源 —— 见
     * {@link com.xbla.rag.rag.eval.ChunkContent} 的注释。
     *
     * <p>★ <b>不按 {@code deleted} 过滤</b>：这是刻意的。软删的切片仍然存在于
     * 这一轮评测的历史里，把它查出来交给报告去判「它已经不在了」，
     * 比在这里静默滤掉（表现为「引用里有这个 id 但取不到正文」）要好 ——
     * 后者会被读成「这次检索没召回」，而那是另一回事。
     *
     * @param ids 切片 id，<b>调用方保证非空</b>（空列表会生成 {@code IN ()} 语法错误）
     */
    @org.apache.ibatis.annotations.Select("""
            <script>
            SELECT id AS chunk_id, content
            FROM kb_chunk
            WHERE id IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<com.xbla.rag.rag.eval.ChunkContent> selectContentsByIds(
            @Param("ids") List<Long> ids);
}
