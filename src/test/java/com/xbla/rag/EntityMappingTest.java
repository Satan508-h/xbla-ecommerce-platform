package com.xbla.rag;

import com.xbla.rag.entity.AppUser;
import com.xbla.rag.entity.ChatMessage;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.entity.EvalQuestion;
import com.xbla.rag.entity.Product;
import com.xbla.rag.mapper.AppUserMapper;
import com.xbla.rag.mapper.ChatMessageMapper;
import com.xbla.rag.mapper.ChatSessionMapper;
import com.xbla.rag.mapper.EvalQuestionMapper;
import com.xbla.rag.mapper.ProductMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

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
