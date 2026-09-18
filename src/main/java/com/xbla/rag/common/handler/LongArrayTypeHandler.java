package com.xbla.rag.common.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code Long[]} ↔ PostgreSQL {@code BIGINT[]} 的类型转换器。
 *
 * <p><b>为什么必须自己写？</b>
 * MyBatis 内置的 TypeHandler 覆盖了常见 Java 类型，但<b>数组只注册了
 * {@code Object[]}</b>（{@code ObjectArrayTypeHandler}），没有注册 {@code Long[]}。
 * 直接用 {@code Long[]} 会报：
 * <pre>
 * Type handler was null on parameter mapping for property 'expectedChunkIds'.
 * It was either not specified and/or could not be found for the
 * javaType ([Ljava.lang.Long;) : jdbcType (null) combination.
 * </pre>
 *
 * <p><b>怎么用？</b>在实体字段上标注：
 * <pre>{@code
 * @TableName(value = "eval_question", autoResultMap = true)   // ★ 别忘了 autoResultMap
 * public class EvalQuestion {
 *     @TableField(typeHandler = LongArrayTypeHandler.class)
 *     private Long[] expectedChunkIds;
 * }
 * }</pre>
 *
 * <p>⚠️ {@code @TableName} 上的 {@code autoResultMap = true} 不能漏。
 * 自定义 TypeHandler 要生效，MyBatis-Plus 必须为这个实体生成 resultMap；
 * 不开启的话，<b>写入正常但读取会静默失败</b>（返回 null 或类型转换异常）——
 * 这种「一半能跑」的 bug 最难查。
 *
 * @see <a href="https://www.postgresql.org/docs/current/arrays.html">PostgreSQL 数组类型</a>
 */
@MappedTypes(Long[].class)
public class LongArrayTypeHandler extends BaseTypeHandler<Long[]> {

    /**
     * 写入：Java 数组 → PostgreSQL 数组。
     *
     * <p>{@code createArrayOf("bigint", parameter)} 让 JDBC 驱动把 Java 数组
     * 包装成 PostgreSQL 的 {@code bigint[]}。"bigint" 是 PostgreSQL 的类型名
     * （不是 SQL 标准的 BIGINT 写法，PostgreSQL 两种都认）。
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
                                    Long[] parameter, JdbcType jdbcType) throws SQLException {
        Array array = ps.getConnection().createArrayOf("bigint", parameter);
        ps.setArray(i, array);
    }

    @Override
    public Long[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toLongArray(rs.getArray(columnName));
    }

    @Override
    public Long[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toLongArray(rs.getArray(columnIndex));
    }

    @Override
    public Long[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toLongArray(cs.getArray(columnIndex));
    }

    /**
     * 读取：PostgreSQL 数组 → Java 数组。
     *
     * <p>不直接强转 {@code (Long[]) array.getArray()}，而是逐个用
     * {@link Number#longValue()} 转换。因为不同 JDBC 驱动返回的元素类型
     * 可能是 {@code Long}、{@code Integer} 甚至 {@code BigInteger}，
     * 强转在换驱动或换数据库时会炸。走 Number 更稳。
     */
    private Long[] toLongArray(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            Object[] raw = (Object[]) array.getArray();
            Long[] result = new Long[raw.length];
            for (int i = 0; i < raw.length; i++) {
                result[i] = (raw[i] == null) ? null : ((Number) raw[i]).longValue();
            }
            return result;
        } finally {
            // java.sql.Array 持有数据库游标资源，用完必须释放，
            // 否则连接池里的连接会攒下未释放的资源
            array.free();
        }
    }
}
