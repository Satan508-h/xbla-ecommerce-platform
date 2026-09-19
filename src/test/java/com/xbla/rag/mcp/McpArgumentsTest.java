package com.xbla.rag.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具参数校验的纯单测（阶段 5.7）。
 *
 * <p>★ 这一组的主题只有一句话：<b>宁可响亮地拒绝，也不要静默地猜。</b>
 */
@DisplayName("McpArguments · 参数校验")
class McpArgumentsTest {

    private static final ToolField ORDER_NO = ToolField.requiredString("order_no", "订单号");
    private static final ToolField TOP_K = ToolField.optionalInt("top_k", "返回几条");
    private static final List<ToolField> DECLARED = List.of(ORDER_NO, TOP_K);

    // ============================================================
    // 一、必填
    // ============================================================

    @Nested
    @DisplayName("一、必填缺失")
    class Required {

        @Test
        @DisplayName("★ 缺必填 → 抛异常，且错误信息里带上这个字段是干什么的")
        void missingRequiredThrows() {
            assertThatThrownBy(() -> McpArguments.of(Map.of(), DECLARED))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("order_no")
                    .as("★ 带上 description 是因为这条消息会原样进模型的上下文。"
                            + "只说「缺 order_no」它不知道该填什么，"
                            + "说「订单号，形如 SO2026...」它就能自己纠正")
                    .hasMessageContaining("订单号");
        }

        @Test
        @DisplayName("★ 值为 null 和「字段不存在」一样算缺")
        void explicitNullCountsAsMissing() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("order_no", null);

            assertThatThrownBy(() -> McpArguments.of(args, DECLARED))
                    .as("★ 模型确实会发 {\"order_no\": null}。"
                            + "只判 containsKey 的话 null 会一路传到 SQL 里变成 "
                            + "WHERE order_no = NULL —— 那恒为 false，"
                            + "症状是「永远查不到订单」，而没有任何报错")
                    .isInstanceOf(McpToolException.class);
        }

        @Test
        @DisplayName("★ 对照：填了就不抛")
        void presentRequiredPasses() {
            assertThatCode(() -> McpArguments.of(Map.of("order_no", "SO1"), DECLARED))
                    .doesNotThrowAnyException();
        }
    }

    // ============================================================
    // 二、未知参数
    // ============================================================

    @Nested
    @DisplayName("二、★ 未知参数被【拒绝】，不是被忽略")
    class Unknown {

        @Test
        @DisplayName("★★ 多传一个 user_id → 拒绝（这是越权的入口）")
        void unexpectedUserIdIsRejected() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("order_no", "SO1");
            args.put("user_id", 9);

            assertThatThrownBy(() -> McpArguments.of(args, DECLARED))
                    .as("★★ 这条是本项目最重要的一条断言。"
                            + "模型被提示注入诱导时，最可能做的事就是【自己加一个 user_id 参数】。"
                            + "如果这里静默忽略它，模型会以为自己猜对了，下次继续猜 —— "
                            + "而那条调用链看起来完全正常。拒绝它，模型就会知道这条路不通")
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("user_id");
        }

        @Test
        @DisplayName("★ 错误信息里带上【可用的参数列表】，让模型能自己纠正")
        void errorListsAllowedParams() {
            assertThatThrownBy(() -> McpArguments.of(Map.of("typo", 1), DECLARED))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("order_no")
                    .hasMessageContaining("top_k");
        }

        @Test
        @DisplayName("★ 对照：只传声明的字段就不抛")
        void declaredFieldsPass() {
            assertThatCode(() -> McpArguments.of(Map.of("order_no", "SO1", "top_k", 5), DECLARED))
                    .doesNotThrowAnyException();
        }
    }

    // ============================================================
    // 三、★ 类型不做宽松转换
    // ============================================================

    @Nested
    @DisplayName("三、★ 类型错了就报错，不顺手转换")
    class Types {

        @Test
        @DisplayName("★★ 字符串字段收到数字 → 拒绝（不转成字符串）")
        void numberForStringIsRejected() {
            McpArguments args = McpArguments.unchecked(Map.of("order_no", 12345));

            assertThatThrownBy(() -> ORDER_NO.requireString(args))
                    .as("★★ 宽松转换的后果：模型发 12345 当订单号，我们转成 \"12345\" 去查库，"
                            + "查不到，于是它得到一个「订单不存在」的答案 —— "
                            + "而真正的问题是它发错了类型。"
                            + "**模型会把这次失败归因到业务上，永远学不会改类型**")
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("类型");
        }

        @Test
        @DisplayName("★★ 对照：字符串 \"5\" 也【不】当成整数 —— 两个方向都不转")
        void numericStringForIntegerIsRejected() {
            McpArguments args = McpArguments.unchecked(Map.of("top_k", "5"));

            assertThatThrownBy(() -> TOP_K.requireInt(args))
                    .as("★ 只做一半的宽松（只转数字→字符串）比全都转更糟："
                            + "两边的行为不一致，而 schema 在两处看起来都是对的")
                    .isInstanceOf(McpToolException.class);
        }

        @Test
        @DisplayName("★ 对的类型当然通过")
        void correctTypesPass() {
            McpArguments args = McpArguments.unchecked(Map.of("order_no", "SO1", "top_k", 5));

            assertThat(ORDER_NO.requireString(args)).isEqualTo("SO1");
            assertThat(TOP_K.requireInt(args)).isEqualTo(5);
        }

        @Test
        @DisplayName("★ 可选字段缺失 → 返回 null，不抛")
        void optionalMissingIsNull() {
            McpArguments args = McpArguments.unchecked(Map.of("order_no", "SO1"));

            assertThat(TOP_K.optionalInt(args)).isNull();
        }
    }

    // ============================================================
    // 四、边界
    // ============================================================

    @Nested
    @DisplayName("四、边界")
    class Edges {

        @Test
        @DisplayName("arguments 为 null（工具一个参数都不要）→ 等同空对象")
        void nullArgumentsMeansEmpty() {
            assertThatCode(() -> McpArguments.of(null, List.of()))
                    .as("★ tools/call 的 arguments 是可选字段。方法没有参数时客户端"
                            + "可能整个不发 —— 那不该校验失败")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("★ 声明的字段一个都不传（可选）→ 不抛")
        void allOptionalAndAbsent() {
            assertThatCode(() -> McpArguments.of(Map.of(), List.of(TOP_K)))
                    .doesNotThrowAnyException();
        }
    }
}
