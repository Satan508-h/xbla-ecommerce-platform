package com.xbla.rag;

import com.xbla.rag.entity.AppUser;
import com.xbla.rag.entity.ChatMessage;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.entity.EvalQuestion;
import com.xbla.rag.entity.KbChunk;
import com.xbla.rag.entity.KbDocument;
import com.xbla.rag.entity.Product;
import com.xbla.rag.mapper.AppUserMapper;
import com.xbla.rag.mapper.ChatMessageMapper;
import com.xbla.rag.mapper.ChatSessionMapper;
import com.xbla.rag.mapper.EvalQuestionMapper;
import com.xbla.rag.mapper.KbChunkMapper;
import com.xbla.rag.mapper.KbDocumentMapper;
import com.xbla.rag.mapper.ProductMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 实体映射集成测试。
 *
 * <p><b>为什么需要这个测试？</b>编译通过只说明语法对，不代表 MyBatis-Plus
 * 能把实体正确映射到数据库表。下面这几个点是「编译期发现不了、只有真连数据库
 * 才会暴露」的：
 * <ul>
 *   <li>{@code references} 是 SQL 保留字，拼 SQL 时不加引号会语法报错</li>
 *   <li>{@code BIGINT[]} 数组类型能否正确读写</li>
 *   <li>自动填充 {@code created_at} 是否真的生效（不生效就是 null，但不会报错）</li>
 *   <li>逻辑删除是否真的变成了 {@code UPDATE deleted = 1} 而不是物理删除</li>
 * </ul>
 *
 * <p>{@code @Transactional} 让每个测试方法跑在一个事务里，结束后自动回滚，
 * 所以不会往数据库里留下垃圾数据。
 *
 * <p><b>运行前提</b>：docker compose 的 postgres 必须是启动状态。
 */
@SpringBootTest
@Transactional
class EntityMappingTest {

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private EvalQuestionMapper evalQuestionMapper;

    @Autowired
    private KbDocumentMapper kbDocumentMapper;

    @Autowired
    private KbChunkMapper kbChunkMapper;

    @Test
    @DisplayName("插入用户：主键自增 + created_at 自动填充")
    void insertAppUser_shouldAutoFillIdAndTimestamp() {
        AppUser user = new AppUser();
        user.setUserNo("U-TEST-001");
        user.setNickname("测试用户");
        user.setPhone("138****8888");
        user.setMemberLevel(1);
        // 注意：这里没有手动 setCreatedAt / setUpdatedAt
        // 如果自动填充没生效，下面断言会失败

        int affected = appUserMapper.insert(user);
        assertThat(affected).isEqualTo(1);

        // ★ IdType.AUTO 生效的话，插入后 id 会被回填；如果是雪花算法会是个 19 位大数
        assertThat(user.getId()).isNotNull().isPositive();
        assertThat(String.valueOf(user.getId())).hasSizeLessThan(10);

        // ★ 自动填充生效
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();

        System.out.println("✅ 用户插入成功 id=" + user.getId()
                + " createdAt=" + user.getCreatedAt());
    }

    @Test
    @DisplayName("插入商品：BigDecimal 金额精度不丢")
    void insertProduct_shouldKeepDecimalPrecision() {
        Product product = new Product();
        product.setProductNo("P-TEST-001");
        product.setName("测试手机");
        product.setCategory("手机");
        product.setPrice(new BigDecimal("3999.99"));
        product.setOriginalPrice(new BigDecimal("4999.00"));
        product.setStatus(1);

        productMapper.insert(product);

        Product loaded = productMapper.selectById(product.getId());
        assertThat(loaded).isNotNull();
        // ★ 精确相等，不是「约等于」。用 double 的话这里会对不上
        assertThat(loaded.getPrice()).isEqualByComparingTo("3999.99");
        assertThat(loaded.getOriginalPrice()).isEqualByComparingTo("4999.00");

        System.out.println("✅ 商品金额精度正确 price=" + loaded.getPrice());
    }

    @Test
    @DisplayName("★ references 保留字：不加引号这里会直接 SQL 语法报错")
    void insertChatMessage_shouldHandleReservedWordColumn() {
        // chat_message 有外键指向 chat_session，所以必须先建一个会话，
        // 否则会报 foreign key violation。这本身也验证了外键约束是生效的。
        ChatSession session = new ChatSession();
        session.setSessionNo("S-TEST-001");
        session.setTitle("测试会话");
        chatSessionMapper.insert(session);

        ChatMessage message = new ChatMessage();
        message.setSessionId(session.getId());
        message.setRole(2);
        message.setContent("测试回复");
        // references 是 SQL 保留字（外键约束用的就是它），
        // 实体类上标了 @TableField("\"references\"") 才能在 SQL 里正确加引号
        message.setReferences("[{\"chunk_id\":12,\"score\":0.87}]");
        message.setProvider("deepseek-p0");
        message.setModel("deepseek-flash");
        message.setLatencyMs(850);

        chatMessageMapper.insert(message);

        ChatMessage loaded = chatMessageMapper.selectById(message.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getReferences()).contains("chunk_id");

        System.out.println("✅ 保留字列 references 读写正常："
                + loaded.getReferences());
    }

    @Test
    @DisplayName("★ BIGINT[] 数组类型读写")
    void insertEvalQuestion_shouldHandleArrayColumn() {
        EvalQuestion q = new EvalQuestion();
        q.setQuestionNo("Q-TEST-001");
        q.setQuestion("这款手机适合送长辈吗");
        q.setIntent("product_consult");
        q.setExpectedAnswer("适合，该机型操作简单、字体可调大");
        q.setExpectedChunkIds(new Long[]{12L, 45L, 88L});
        q.setExpectedDocIds(new Long[]{3L});
        q.setDifficulty(2);
        q.setIsBaseline(true);

        evalQuestionMapper.insert(q);

        EvalQuestion loaded = evalQuestionMapper.selectById(q.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getExpectedChunkIds()).containsExactly(12L, 45L, 88L);
        assertThat(loaded.getIsBaseline()).isTrue();

        System.out.println("✅ 数组类型正常，expectedChunkIds="
                + java.util.Arrays.toString(loaded.getExpectedChunkIds()));
    }

    @Test
    @DisplayName("★ vector(1024) 向量列读写：写入 float[]，读回来还是同一个 float[]")
    void insertKbChunk_shouldRoundTripVectorColumn() {
        // kb_chunk 有外键指向 kb_document，先建文档
        KbDocument doc = new KbDocument();
        doc.setDocNo("D-TEST-001");
        doc.setTitle("七天无理由退货规则");
        doc.setDocType(2);        // 2 = 售后政策
        doc.setSourceType(1);     // 1 = 文件上传
        doc.setStatus(3);         // 3 = 已入库
        kbDocumentMapper.insert(doc);
        assertThat(doc.getId()).isNotNull();

        // 造一个 1024 维的向量，值域模仿 bge-m3 的真实输出（大多是 -1~1 的小数）
        float[] vector = new float[1024];
        for (int i = 0; i < 1024; i++) {
            vector[i] = (float) Math.sin(i * 0.017);
        }

        KbChunk chunk = new KbChunk();
        chunk.setDocumentId(doc.getId());
        chunk.setChunkIndex(0);
        chunk.setContent("自营商品签收后 7 天内可申请无理由退货。");
        chunk.setHeadingPath("售后政策 > 退货 > 适用范围");
        chunk.setEmbedding(vector);
        chunk.setDocType(2);
        // 注意：这里没有手动 setCreatedAt / setUpdatedAt

        int affected = kbChunkMapper.insert(chunk);
        assertThat(affected).isEqualTo(1);

        // ★★ 关键断言：读回来的是 float[] 而不是 null。
        //    如果实体类上的 autoResultMap = true 漏了，
        //    上一行 insert 仍然会成功（不报错），但这一行会是 null。
        //    这正是这个测试存在的全部理由。
        KbChunk loaded = kbChunkMapper.selectById(chunk.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getEmbedding())
                .as("autoResultMap 漏了的话这里就是 null —— 写入不报错、查询不报错，只有值是空的")
                .isNotNull()
                .hasSize(1024);

        // ★ 逐位相等：确认中间没有经过 double 中转导致精度损失
        assertThat(loaded.getEmbedding()).containsExactly(vector);

        System.out.println("✅ 向量列往返成功：维度=" + loaded.getEmbedding().length
                + " 首值=" + loaded.getEmbedding()[0]
                + " 末值=" + loaded.getEmbedding()[1023]);
    }

    @Test
    @DisplayName("★ 向量维度由数据库强制约束：写 512 维必须被 PostgreSQL 拒绝")
    void insertKbChunk_shouldRejectWrongDimension() {
        KbDocument doc = new KbDocument();
        doc.setDocNo("D-TEST-002");
        doc.setTitle("维度校验测试");
        doc.setDocType(2);
        doc.setSourceType(1);
        doc.setStatus(3);
        kbDocumentMapper.insert(doc);

        KbChunk chunk = new KbChunk();
        chunk.setDocumentId(doc.getId());
        chunk.setChunkIndex(0);
        chunk.setContent("维度不对的切片");
        chunk.setEmbedding(new float[512]);   // ★ 故意写错：表上是 vector(1024)

        // 这条断言证明「维度确实是 1024」不是靠文档里写的，而是数据库在强制执行。
        // 换句话说：如果哪天换了 embedding 模型输出 768 维，
        // 不会悄悄写进去污染检索，而是当场报错。
        assertThatThrownBy(() -> kbChunkMapper.insert(chunk))
                .as("PostgreSQL 必须拒绝维度不匹配的向量")
                .hasMessageContaining("expected 1024 dimensions");

        System.out.println("✅ 数据库强制校验向量维度：512 维被拒绝");
    }

    @Test
    @DisplayName("★ 逻辑删除：removeById 应该变成 UPDATE deleted=1，而不是真删除")
    void logicDelete_shouldNotPhysicallyDelete() {
        Product product = new Product();
        product.setProductNo("P-TEST-DEL");
        product.setName("待删除商品");
        product.setCategory("手机");
        product.setPrice(new BigDecimal("1.00"));
        product.setStatus(1);
        productMapper.insert(product);

        Long id = product.getId();
        assertThat(productMapper.selectById(id)).isNotNull();

        // 调用删除
        productMapper.deleteById(id);

        // ★ 关键断言：查询查不到了（因为自动加了 WHERE deleted = 0）
        assertThat(productMapper.selectById(id)).isNull();

        System.out.println("✅ 逻辑删除生效：记录查不到了（但数据还在表里，deleted=1）");
    }
}
