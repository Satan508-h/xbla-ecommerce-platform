package com.xbla.rag.common.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code float[] ↔ PostgreSQL pgvector 的 vector 类型} 的类型处理器。
 *
 * <p><b>为什么必须自己写这个类</b>
 *
 * <p>{@code vector(1024)} 不是 PostgreSQL 的内置类型，而是 pgvector 扩展定义的。
 * JDBC 驱动只认识 SQL 标准类型，拿到 vector 列完全不知道该怎么办；
 * MyBatis 内置的类型处理器里也没有 {@code float[] ↔ vector} 的转换规则。
 * 不写这个类，写入时会直接报类型转换失败。
 *
 * <p><b>写入路径</b>（三步，缺一不可）
 * <pre>
 *   float[]  ──① 拼成 "[0.1,0.2,...]" 文本──>  String
 *                                                │
 *                       ② 以「未指定类型」发给 PostgreSQL
 *                          （靠 JDBC URL 末尾的 stringtype=unspecified）
 *                                                │
 *                       ③ PostgreSQL 用 vector 类型自己的输入函数解析这段文本
 *                                                ▼
 *                                          vector(1024)
 * </pre>
 *
 * <p>②这一步是最容易被忽略的。pgjdbc 默认把 Java {@code String} 当作 {@code varchar}
 * 发送，而 PostgreSQL <b>不会</b>把 varchar 隐式转换成 vector，会报：
 * <pre>
 *   column "embedding" is of type vector but expression is of type character varying
 * </pre>
 * 加上 {@code stringtype=unspecified} 之后，驱动改以「未指定类型」发送，
 * 由 PostgreSQL 按目标列的声明类型自行推断。这是 pgjdbc 官方推荐的
 * 处理 jsonb / 自定义类型（如 pgvector）的标准做法。
 *
 * <p><b>读取路径</b>：pgvector 把 vector 输出成 {@code [0.1,0.2,...]} 文本，
 * 这里解析回 {@code float[]}。
 *
 * <p><b>怎么用</b>：字段上标注 typeHandler，★ 并且类上必须加 {@code autoResultMap = true}。
 *
 * <pre>
 *   &#64;TableName(value = "kb_chunk", autoResultMap = true)   // ★ 不能漏
 *   public class KbChunk {
 *       &#64;TableField(typeHandler = VectorTypeHandler.class)
 *       private float[] embedding;
 *   }
 * </pre>
 *
 * <p><b>为什么 {@code autoResultMap = true} 不能漏</b>：
 * 漏了的话<b>写入正常、读取静默失败</b>（查出来永远是 null）。
 * 因为 MyBatis-Plus 默认用的是自动生成的 ResultMap，不认识自定义 typeHandler；
 * 只有开启 autoResultMap 才会把字段上的 typeHandler 注册进查询用的 ResultMap。
 * 这类「一半能跑」的 bug 最难排查 —— 插入没报错，查询也没报错，就是没数据。
 * 同类问题在 {@link LongArrayTypeHandler} 上也踩过一次（见 docs/10 坑 8）。
 *
 * <p><b>精度说明</b>：向量用 {@code float}（单精度）而不是 {@code double}。
 * bge-m3 输出的本来就是 32 位浮点，1024 维用 double 存会白白多占一倍空间和带宽，
 * 而检索用的是余弦相似度，单精度的误差（约 1e-7）远小于模型本身的噪声。
 */
@MappedTypes(float[].class)
public class VectorTypeHandler extends BaseTypeHandler<float[]> {

    /** 左括号，pgvector 文本格式的起始符 */
    private static final char OPEN = '[';
    /** 右括号，pgvector 文本格式的结束符 */
    private static final char CLOSE = ']';
    /** 分隔符 */
    private static final char SEP = ',';

    // ============================================================
    // 写入：float[] → "[...]" 文本
    // ============================================================

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, float[] parameter, JdbcType jdbcType)
            throws SQLException {
        // ★ 用 setObject 而不是 setString。
        //   配合 JDBC URL 里的 stringtype=unspecified，驱动会把参数以「未指定类型」
        //   发给 PostgreSQL，由 PG 按目标列（vector）推断该怎么解析。
        //   用 setString 会被明确标成 varchar，反而失去这个推断能力。
        ps.setObject(i, toLiteral(parameter));
    }

    // ============================================================
    // 读取：三种取值方式，都解析回 float[]
    // ============================================================

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    // ============================================================
    // 文本 ↔ float[] 的转换
    //
    // ★ 这两个方法声明成 public 而不是 private，是因为它们【不只服务于本类】：
    // 手写 SQL 时也要把 float[] 转成 pgvector 字面量塞进查询里 ——
    // 比如阶段 4 的向量召回 `WHERE embedding <=> CAST(? AS vector)`，
    // 参数就得是这种文本格式。放在这个类里是因为「什么格式能被 pgvector 解析」
    // 这个知识属于这里，散到别处就会变成两份可能不一致的实现。
    // ============================================================

    /**
     * {@code float[]} → pgvector 文本格式 {@code [0.1,0.2,0.3]}。
     *
     * <p>不用 {@code Arrays.toString()}：那个会输出 {@code [0.1, 0.2]} ——
     * 逗号后面<b>多一个空格</b>。pgvector 的解析器虽然大多能容忍，
     * 但没必要在一个要发给数据库的字符串里留这种不确定性。
     *
     * <p>也不用 {@code String.join} + 装箱：1024 个 float 装箱成 Float 会额外分配
     * 1024 个对象，而一次入库要处理几千个切片。用 StringBuilder 定长预分配最省。
     */
    public static String toLiteral(float[] vector) {
        if (vector == null) {
            return null;
        }
        // 估算容量：每个 float 大约 12 个字符（"-0.0123456," 量级），少估了 StringBuilder
        // 会自动扩容，但预估准确能省掉扩容时的数组复制
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2);
        sb.append(OPEN);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(SEP);
            }
            sb.append(vector[i]);
        }
        sb.append(CLOSE);
        return sb.toString();
    }

    /**
     * pgvector 文本格式 {@code [0.1,0.2,0.3]} → {@code float[]}。
     *
     * <p>返回 {@code null} 的两种情况：入参为 null、或数据库里这一列就是 NULL。
     * 「向量为 NULL」在业务上是有意义的（切片已写入但还没向量化完），
     * 所以这里不抛异常，交由上层判断。
     */
    public static float[] parse(String literal) {
        if (literal == null) {
            return null;
        }
        int len = literal.length();
        // 掐头去尾：去掉首尾的 [ ]
        int start = (len > 0 && literal.charAt(0) == OPEN) ? 1 : 0;
        int end = (len > start && literal.charAt(len - 1) == CLOSE) ? len - 1 : len;
        if (start >= end) {
            // "[]" —— 长度为 0 的向量。返回空数组而不是 null，
            // 因为「查询成功但结果是空向量」和「这一列是 NULL」是两件事
            return new float[0];
        }

        // 先数逗号，一遍预分配，避免 ArrayList 装箱
        int count = 1;
        for (int i = start; i < end; i++) {
            if (literal.charAt(i) == SEP) {
                count++;
            }
        }

        float[] result = new float[count];
        int idx = 0;
        int tokenStart = start;
        for (int i = start; i <= end; i++) {
            // i == end 是哨兵，用来收尾最后一个数字，省掉循环外的重复代码
            if (i == end || literal.charAt(i) == SEP) {
                if (i > tokenStart) {
                    result[idx++] = parseFloat(literal, tokenStart, i);
                }
                tokenStart = i + 1;
            }
        }

        if (idx == count) {
            return result;
        }
        // 文本里有空片段（如 "[1,,2]"）时实际解析出的个数会少于预估，
        // 这时候要截断，否则尾部会留一串 0 —— 那是会被当成真实向量算进相似度的
        float[] trimmed = new float[idx];
        System.arraycopy(result, 0, trimmed, 0, idx);
        return trimmed;
    }

    /**
     * 解析 {@code [from, to)} 区间里的一个浮点数。
     *
     * <p><b>诚实说明</b>：这里就是 {@code Float.parseFloat(substring)}，
     * 每个数字会多分配一个临时 String。1024 维向量一次解析 = 1024 个小字符串。
     *
     * <p>曾考虑避开这个分配，但<b>查证后确认 JDK 没有可用的零分配重载</b>：
     * {@code Integer.parseInt(CharSequence, int, int, int)} 是 JDK 9 加的，
     * 而 {@code Float.parseFloat} <b>只有接受 String 的那一个重载</b>，没有 CharSequence 版本。
     * 自己手写浮点解析则要处理指数、符号、精度舍入，出错风险远大于这点收益。
     *
     * <p>所以维持现状。这个开销相对于一次 JDBC 网络往返和 1024 个 float 的解析本身
     * 可以忽略；真到需要优化时，该优化的是「少查几遍数据库」而不是这里。
     */
    private static float parseFloat(String s, int from, int to) {
        return Float.parseFloat(s.substring(from, to));
    }
}
