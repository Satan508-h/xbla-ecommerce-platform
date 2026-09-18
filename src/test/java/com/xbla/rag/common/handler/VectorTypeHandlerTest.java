package com.xbla.rag.common.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link VectorTypeHandler} 的文本 ↔ float[] 转换单测。
 *
 * <p>只测纯函数部分（{@code toLiteral} / {@code parse}），不碰数据库。
 * 数据库那一侧的真实往返由 {@code EntityMappingTest} 的集成测试覆盖 ——
 * 两者职责不同：这里保证<b>转换逻辑本身</b>对，
 * 那里保证<b>转换器和 JDBC 参数绑定真的能配合上</b>。
 *
 * <p><b>为什么值得单测</b>：这个转换器是向量进出数据库的唯一通道，
 * 它错了不会有异常，只会让检索悄悄变差 ——
 * 比如精度被截断、维数错位，日志里一个报错都没有。
 */
@DisplayName("VectorTypeHandler —— pgvector 文本格式转换")
class VectorTypeHandlerTest {

    // ============================================================
    // 写入方向：float[] → 文本
    // ============================================================

    @Nested
    @DisplayName("toLiteral：float[] → \"[...]\"")
    class ToLiteral {

        @Test
        @DisplayName("基本格式：带方括号、逗号分隔、★逗号后没有空格")
        void basicFormat() {
            String literal = VectorTypeHandler.toLiteral(new float[]{0.1f, 0.2f, 0.3f});

            // ★ 关键：不是 Arrays.toString() 的结果。
            //   Arrays.toString 会输出 "[0.1, 0.2, 0.3]"（逗号后带空格）。
            //   这里必须用点号断言，而不是 isEqualTo(Arrays.toString(...))，
            //   否则就把「带空格」这个错误行为固化成期望值了。
            assertThat(literal).isEqualTo("[0.1,0.2,0.3]");
            assertThat(literal).doesNotContain(", ");
        }

        @Test
        @DisplayName("整数和负数都能正确输出")
        void integersAndNegatives() {
            assertThat(VectorTypeHandler.toLiteral(new float[]{1f, -1f, 0f}))
                    .isEqualTo("[1.0,-1.0,0.0]");
        }

        @Test
        @DisplayName("null 进去 null 出来（让数据库收到 NULL，而不是字符串 \"null\"）")
        void nullVector() {
            // 这个断言比看起来重要：如果这里返回字符串 "null"，
            // pgvector 会尝试解析它并抛异常，而错误信息会说「无效的向量文本」，
            // 完全指不到「Java 侧传了 null」这个真正的原因
            assertThat(VectorTypeHandler.toLiteral(null)).isNull();
        }

        @Test
        @DisplayName("1024 维：正好 1024 个逗号，长度符合预期")
        void fullDimension() {
            float[] v = new float[1024];
            for (int i = 0; i < 1024; i++) {
                v[i] = i / 1024f;
            }

            String literal = VectorTypeHandler.toLiteral(v);

            assertThat(literal).startsWith("[0.0,");
            assertThat(literal).endsWith("]");
            // 1024 个数字之间有 1023 个逗号
            assertThat(literal.chars().filter(c -> c == ',').count()).isEqualTo(1023);
        }
    }

    // ============================================================
    // 读取方向：文本 → float[]
    // ============================================================

    @Nested
    @DisplayName("parse：\"[...]\" → float[]")
    class Parse {

        @Test
        @DisplayName("基本解析")
        void basic() {
            assertThat(VectorTypeHandler.parse("[0.1,0.2,0.3]"))
                    .containsExactly(0.1f, 0.2f, 0.3f);
        }

        @Test
        @DisplayName("★ 容忍逗号后的空格（不同版本的 pgvector 输出可能带空格）")
        void toleratesSpaces() {
            // 自己的 toLiteral 不产生空格，但读回来的数据可能是
            // 别的工具（psql 手工插入、pg_dump 恢复）写进去的。
            // 解析器比生成器宽容一点，是划算的
            assertThat(VectorTypeHandler.parse("[0.1, 0.2, 0.3]"))
                    .containsExactly(0.1f, 0.2f, 0.3f);
        }

        @Test
        @DisplayName("科学计数法（极小值/极大值时的正常输出形态）")
        void scientificNotation() {
            assertThat(VectorTypeHandler.parse("[1.0E-4,1.5E-8,-2.3E5]"))
                    .containsExactly(1.0E-4f, 1.5E-8f, -2.3E5f);
        }

        @Test
        @DisplayName("null 和空向量是两件事：null → null，\"[]\" → 空数组")
        void nullVersusEmpty() {
            // 这个区分有业务含义：
            //   null   = 切片已写入但还没向量化完（阶段 3 的中间状态）
            //   空数组 = 向量化跑了但结果是空的（异常情况，应该报警）
            // 混成一个值的话，运维查询就没法把这两种情况分开了
            assertThat(VectorTypeHandler.parse(null)).isNull();
            assertThat(VectorTypeHandler.parse("[]")).isEmpty();
        }

        @Test
        @DisplayName("1024 维往返：parse 出来的维度和维度数完全一致，一个不少")
        void roundTrip1024() {
            float[] original = new float[1024];
            for (int i = 0; i < 1024; i++) {
                original[i] = (i % 100) / 100f;
            }

            float[] parsed = VectorTypeHandler.parse(VectorTypeHandler.toLiteral(original));

            assertThat(parsed).hasSize(1024);
            assertThat(parsed).containsExactly(original);
        }

        @Test
        @DisplayName("★ 往返后数值精度完全一致（float 直传，没有经过 double 中转）")
        void roundTripKeepsExactPrecision() {
            // 用一串「十进制下无法精确表示」的值。如果中间经过了
            // double → BigDecimal → String 之类的转换，这里就会不等
            float[] original = {0.1f, 0.2f, 0.3f, 1f / 3f, 2f / 7f};

            float[] parsed = VectorTypeHandler.parse(VectorTypeHandler.toLiteral(original));

            assertThat(parsed).containsExactly(original);
        }

        @Test
        @DisplayName("没有方括号的裸文本也能解析（防御性）")
        void withoutBrackets() {
            assertThat(VectorTypeHandler.parse("1.0,2.0")).containsExactly(1.0f, 2.0f);
        }

        @Test
        @DisplayName("畸形输入不崩：多余逗号时结果被截断，不留下尾部 0")
        void malformedDoesNotLeaveTrailingZeros() {
            // "[1,,2]" —— 中间有个空片段。
            // 如果预分配了 3 个位置却只填了 2 个，第三个位置就是 0.0f，
            // 而 0.0f 会被当成真实向量分量算进余弦相似度，悄悄污染检索结果。
            // 这种 bug 不会报错，只会让排序变得莫名其妙
            assertThatCode(() -> VectorTypeHandler.parse("[1,,2]")).doesNotThrowAnyException();
            assertThat(VectorTypeHandler.parse("[1,,2]")).containsExactly(1.0f, 2.0f);
        }
    }

    // ============================================================
    // 往返一致性
    // ============================================================

    @Test
    @DisplayName("★ 随机向量往返 100 轮，逐位相等")
    void randomRoundTrip() {
        java.util.Random random = new java.util.Random(42);   // 固定种子，失败可复现

        for (int round = 0; round < 100; round++) {
            float[] v = new float[1024];
            for (int i = 0; i < 1024; i++) {
                // 模拟真实向量：大部分是小数值，正负都有
                v[i] = (random.nextFloat() - 0.5f) * 2f;
            }

            assertThat(VectorTypeHandler.parse(VectorTypeHandler.toLiteral(v)))
                    .as("第 %d 轮往返", round)
                    .containsExactly(v);
        }
    }
}
