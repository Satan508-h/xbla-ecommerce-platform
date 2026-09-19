package com.xbla.rag.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具结果的纯单测（阶段 5.7）。
 *
 * <h3>★ 这个类存在的理由：一个真实踩到的 NPE</h3>
 *
 * <p>第一版用的防御性拷贝是 {@code Map.copyOf(data)} —— 那是很自然的直觉。
 * 但它<b>拒绝 null 值</b>，而「key 在、值是 null」恰恰是 JSON Schema 里
 * <b>可选字段</b>的合法形态。第一笔「待发货」的订单就把它炸了：
 * {@code logistics_no} 是 null，构造 {@link McpToolResult} 时抛 NPE，
 * 而栈顶指向构造函数，不指向那个 null 字段。
 */
@DisplayName("McpToolResult · 工具结果")
class McpToolResultTest {

    @Nested
    @DisplayName("一、★★ null 值必须被允许")
    class NullValues {

        @Test
        @DisplayName("★★ 值为 null 的 key 要保留 —— 那是可选字段的合法形态")
        void nullValuesAreAllowed() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("logistics_no", null);
            data.put("order_no", "SO1");

            assertThatCode(() -> McpToolResult.ok("有物流单号的订单", data))
                    .as("★★ Map.copyOf / Map.of 都会在这里抛 NPE。"
                            + "而「未发货的订单没有物流单号」是完全正常的数据 ——"
                            + "把它炸掉等于「工具查到数据了但包装失败」")
                    .doesNotThrowAnyException();

            assertThat(McpToolResult.ok("x", data).data())
                    .as("★ key 必须还在，只是值为 null —— 缺 key 和值为 null "
                            + "在 JSON Schema 里是两件事，下游取值的行为也不同")
                    .containsKey("logistics_no")
                    .containsEntry("logistics_no", null);
        }

        @Test
        @DisplayName("★ 对照：Map.of 确实会炸 —— 证明上一条不是恒真")
        void mapOfWouldThrow() {
            assertThatThrownBy(() -> Map.of("logistics_no", null))
                    .as("★ 这条钉的是「为什么不能用 Map.of」这件事本身。"
                            + "将来有人把实现改回 Map.of，上面那条会立刻变红")
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("★ data 为 null 是允许的（工具没有结构化输出）")
        void nullDataIsFine() {
            assertThat(McpToolResult.ok("只有文本").data()).isNull();
            assertThat(McpToolResult.ok("只有文本").hasData()).isFalse();
        }
    }

    @Nested
    @DisplayName("二、正文不能为空")
    class Text {

        @Test
        @DisplayName("★ 空正文 → 拒绝（空文本会让模型以为工具什么都没说）")
        void blankTextIsRejected() {
            assertThatThrownBy(() -> McpToolResult.ok("   "))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> McpToolResult.ok(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("三、★ isError 的两个工厂")
    class ErrorFlag {

        @Test
        @DisplayName("★★ ok() 和 failure() 的语义差别 —— 查不到走 ok")
        void okMeansToolAnswered() {
            assertThat(McpToolResult.ok("没有找到订单 SO1").isError())
                    .as("★★ 「查无此单」是一个完整的答案，不是工具失败。"
                            + "标成 isError 的话模型会说「系统出了点问题，请稍后再试」——"
                            + "而用户重试一万次也变不出那个订单")
                    .isFalse();

            assertThat(McpToolResult.failure("数据库连不上").isError())
                    .as("★ 这才是真的失败：工具【没能】给出答案")
                    .isTrue();
        }
    }
}
