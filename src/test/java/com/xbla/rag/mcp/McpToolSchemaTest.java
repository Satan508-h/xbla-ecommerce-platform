package com.xbla.rag.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具元数据 → JSON Schema 的纯单测（阶段 5.7）。
 *
 * <p>没有 Spring、没有数据库、不花钱。
 *
 * <h3>★ 这一组测试真正在钉的是什么</h3>
 *
 * <p>{@link ToolField} 的全部价值是「<b>schema 里写的名字</b>」和
 * 「<b>实现里读的 key</b>」是同一个来源。所以这里的核心断言是：
 * <b>用一个字段常量生成出来的 schema，那个 key 一定能被它自己读出来</b>。
 * 见 {@link Coupling}。
 */
@DisplayName("MCP 工具元数据 · JSON Schema 生成")
class McpToolSchemaTest {

    /** 一个最小的工具，只为了测默认方法 */
    private static McpTool toolWith(List<ToolField> inputs, List<ToolField> outputs) {
        return new McpTool() {
            @Override
            public String name() {
                return "t";
            }

            @Override
            public String title() {
                return "T";
            }

            @Override
            public String description() {
                return "d";
            }

            @Override
            public List<ToolField> inputFields() {
                return inputs;
            }

            @Override
            public List<ToolField> outputFields() {
                return outputs;
            }

            @Override
            public McpToolResult call(McpArguments args, McpToolContext context) {
                return McpToolResult.ok("x");
            }
        };
    }

    // ============================================================
    // 一、inputSchema 的形状
    // ============================================================

    @Nested
    @DisplayName("一、inputSchema 的形状")
    class InputSchema {

        @Test
        @DisplayName("★ 必填和可选分开：required 只装必填的")
        void requiredOnlyListsRequiredFields() {
            Map<String, Object> schema = schemaOf(toolWith(List.of(
                    ToolField.requiredString("a", "甲"),
                    ToolField.optionalString("b", "乙"),
                    ToolField.requiredInt("c", "丙")), List.of()));

            assertThat(schema).containsEntry("type", "object");
            assertThat(schema).containsEntry("additionalProperties", false);
            assertThat(schema.get("required")).isEqualTo(List.of("a", "c"));
            assertThat(properties(schema)).containsOnlyKeys("a", "b", "c");
        }

        @Test
        @DisplayName("★ 一个必填都没有时【不出现】required —— 空数组是噪音")
        void noEmptyRequiredArray() {
            Map<String, Object> schema = schemaOf(toolWith(
                    List.of(ToolField.optionalString("a", "甲")), List.of()));

            assertThat(schema)
                    .as("{\"required\":[]} 在 JSON Schema 里合法，但它让 schema 变长"
                            + "而且没有任何信息量 —— 模型读 schema 是要花 token 的")
                    .doesNotContainKey("required");
        }

        @Test
        @DisplayName("★ 类型映射到 JSON Schema 的类型名")
        void typesMapCorrectly() {
            Map<String, Object> schema = schemaOf(toolWith(List.of(
                    ToolField.requiredString("s", "字"),
                    ToolField.requiredInt("i", "数")), List.of()));

            assertThat(property(schema, "s")).containsEntry("type", "string");
            assertThat(property(schema, "i")).containsEntry("type", "integer");
        }

        @Test
        @DisplayName("★ properties 保持【声明顺序】—— schema 是给人读和 diff 的")
        void propertiesKeepDeclarationOrder() {
            Map<String, Object> schema = schemaOf(toolWith(List.of(
                    ToolField.requiredString("z", "最后声明的"),
                    ToolField.requiredString("a", "最先声明的")), List.of()));

            assertThat(properties(schema).keySet())
                    .as("★ 不能用 Map.of/Set —— 那会按哈希重排。schema 顺序一变，"
                            + "任何基于 prompt 前缀缓存的优化都失效，diff 也会全是噪音")
                    .containsExactly("z", "a");
        }

        @Test
        @DisplayName("★ 每个字段都带 description —— 那才是模型选工具的依据")
        void everyFieldHasDescription() {
            Map<String, Object> schema = schemaOf(toolWith(
                    List.of(ToolField.requiredString("a", "这个参数是干什么的")), List.of()));

            assertThat(property(schema, "a")).containsEntry("description", "这个参数是干什么的");
        }
    }

    // ============================================================
    // 二、★★ schema 和取值是同一个来源
    // ============================================================

    @Nested
    @DisplayName("二、★★ schema 和取值不能漂移")
    class Coupling {

        @Test
        @DisplayName("★★ 字段常量生成出的 schema，那个 key 一定能被【它自己】读出来")
        void fieldConstantIsItsOwnSourceOfTruth() {
            ToolField field = ToolField.requiredString("order_no", "订单号");

            // ① 按 schema 里写的名字造参数（模拟模型照着 schema 发）
            Map<String, Object> schema = schemaOf(toolWith(List.of(field), List.of()));
            String schemaKey = properties(schema).keySet().iterator().next();
            Map<String, Object> modelPayload = new LinkedHashMap<>();
            modelPayload.put(schemaKey, "SO2026");

            // ② 用【同一个字段常量】去读
            McpArguments args = McpArguments.of(modelPayload, List.of(field));
            String extracted = field.requireString(args);

            assertThat(extracted)
                    .as("★★ 这条断言就是 ToolField 存在的全部理由。"
                            + "如果 schema 的名字和取值的名字分别手写，"
                            + "它们漂移的方式是【静默】的：模型照着 schema 发，"
                            + "实现读到 null，报『缺参数』，而 schema 看起来完全正确")
                    .isEqualTo("SO2026");
            assertThat(schemaKey).isEqualTo("order_no");
        }

        @Test
        @DisplayName("★★ 对照：名字对不上就【走不到取值那一步】—— 证明上一条不是恒真")
        void handWrittenMismatchWouldBreak() {
            ToolField field = ToolField.requiredString("order_no", "订单号");

            // 模拟「手写 schema 时把 order_no 拼成 orderNo」
            Map<String, Object> wrongPayload = Map.of("orderNo", "SO2026");

            assertThatThrownBy(() -> McpArguments.of(wrongPayload, List.of(field)))
                    .as("★ 对照上一条：证明「能读出来」不是因为读什么都能读到。"
                            + "★ 而且它错得【更早】—— 在参数校验期就炸，"
                            + "根本走不到业务代码。这正是 additionalProperties:false "
                            + "想要的效果：把 schema 和实现的不一致变成一次响亮的报错")
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("orderNo")
                    .hasMessageContaining("order_no");

            // ★ 再验一次「就算绕过校验，取值也拿不到」—— 两道防线
            McpArguments bypassed = McpArguments.unchecked(wrongPayload);
            assertThatThrownBy(() -> field.requireString(bypassed))
                    .as("★ 这条走的是「有人用 unchecked 绕过了校验」那条路，"
                            + "验证字段自己也不会把别人的 key 当成自己的")
                    .isInstanceOf(McpToolException.class);
        }
    }

    // ============================================================
    // 三、outputSchema 和 annotations
    // ============================================================

    @Nested
    @DisplayName("三、outputSchema 与 annotations")
    class ToolDefinition {

        @Test
        @DisplayName("★ 声明了输出字段才出现 outputSchema")
        void outputSchemaOnlyWhenDeclared() {
            assertThat(toolWith(List.of(), List.of(ToolField.requiredString("x", "甲")))
                    .toToolDefinition())
                    .containsKey("outputSchema");

            assertThat(toolWith(List.of(), List.of()).toToolDefinition())
                    .as("★ 加一个空壳 outputSchema 会让人以为「这个工具不返回任何东西」，"
                            + "而实际上它可能返回文本内容 —— 「没声明」和「声明了是空的」"
                            + "是两件事")
                    .doesNotContainKey("outputSchema");
        }

        @Test
        @DisplayName("★ 每个工具都声明 readOnlyHint —— 但它只是提示，不是安全机制")
        void readOnlyHintIsDeclared() {
            assertThat(toolWith(List.of(), List.of()).toToolDefinition().get("annotations"))
                    .as("★ 规范明说 annotations 在客户端看来【不可信】。"
                            + "真正的安全靠「身份不进模型的可控范围」和「工具只 SELECT」")
                    .isEqualTo(Map.of("readOnlyHint", true));
        }

        @Test
        @DisplayName("★ Tool 对象的必需字段一个不少")
        void requiredToolFieldsArePresent() {
            Map<String, Object> def = toolWith(List.of(), List.of()).toToolDefinition();

            assertThat(def).containsKeys("name", "title", "description", "inputSchema");
            assertThat(def.get("name")).isEqualTo("t");
        }
    }

    // ============================================================
    // 取 schema 内部结构的小助手
    // ============================================================

    private static Map<String, Object> schemaOf(McpTool tool) {
        return asMap(tool.toToolDefinition().get("inputSchema"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String name) {
        return (Map<String, Object>) properties(schema).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
