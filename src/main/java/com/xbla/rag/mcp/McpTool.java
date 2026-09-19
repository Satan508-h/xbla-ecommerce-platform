package com.xbla.rag.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一个 MCP 工具 —— <b>元数据和执行体在同一个类里。</b>
 *
 * <p>实现类请把每个字段声明成 {@code static final ToolField} 常量，
 * 然后 <b>生成 schema</b> 和 <b>取值</b> 都走那个常量。
 * 见 {@link ToolField} 的类注释 —— 那是本包唯一一条「不能违反」的纪律，
 * 它让「schema 和实现漂移」在物理上不可能发生。
 *
 * <h2>只读是默认，也是最该声明的</h2>
 *
 * <p>本项目四个业务工具全是查询。
 * {@code annotations.readOnlyHint} 会让客户端在 UI 上把「需要人工确认」的提示降级 ——
 * 但 ★ <b>规范明说 annotations 在客户端看来是不可信的</b>（除非服务端可信），
 * 所以它只是<b>提示</b>，不是安全机制。真正的安全靠
 * 「身份不进模型的可控范围」（见 {@link McpToolContext}）和「工具本身只 SELECT」。
 */
public interface McpTool {

    /** 工具名。★ 全局唯一 —— 由 {@link McpToolRegistry} 在启动期强制 */
    String name();

    /** 给人看的短标题（客户端 UI 用） */
    String title();

    /**
     * 给<b>模型</b>看的功能描述 —— 它会直接进模型的上下文，
     * 所以写的是「什么时候该用这个工具」，而不是「这个函数做了什么」。
     */
    String description();

    /** 入参字段。顺序 = schema 里 {@code properties} 的顺序 */
    List<ToolField> inputFields();

    /**
     * 输出字段。返回空列表 = 不声明 {@code outputSchema}。
     *
     * <p>声明了就必须在 {@link McpToolResult#data()} 里给出对应的字段 ——
     * 声明和实际不符会让下游按 schema 取值时拿到 null。
     */
    default List<ToolField> outputFields() {
        return List.of();
    }

    /**
     * 执行。
     *
     * @param args    已校验的参数（缺必填、多未知参数在这里之前就已经被拦掉了）
     * @param context 身份。★ <b>查「我的」数据必须用它，不能用 {@code args}</b>
     * @throws McpToolException 参数在这个语义下不可用（→ 协议错误）
     */
    McpToolResult call(McpArguments args, McpToolContext context);

    // ============================================================
    // 生成 MCP 的 Tool 对象
    // ============================================================

    /**
     * 拼成 {@code tools/list} 里的一个元素。
     *
     * <p>结构（规范 2025-06-18 起）：
     * <pre>
     *   {
     *     "name": "...", "title": "...", "description": "...",
     *     "inputSchema":  {"type":"object","properties":{...},"required":[...],
     *                      "additionalProperties": false},
     *     "outputSchema": {...},          // 只在声明了 outputFields 时出现
     *     "annotations":  {"readOnlyHint": true}
     *   }
     * </pre>
     *
     * <p>★ {@code additionalProperties: false} 必须和 {@link McpArguments} 的
     * 「拒绝未知参数」保持一致 —— 一个声明允许、另一个拒绝，会让模型看到
     * 一个自相矛盾的契约。这条由 {@code McpToolSchemaTest} 钉住。
     */
    default Map<String, Object> toToolDefinition() {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name());
        definition.put("title", title());
        definition.put("description", description());
        definition.put("inputSchema", schemaOf(inputFields()));

        List<ToolField> outputs = outputFields();
        if (outputs != null && !outputs.isEmpty()) {
            // 规范里 outputSchema 是【可选】的。只在真有输出声明时才加 ——
            // 加一个空壳会让人以为「这个工具不返回任何东西」
            definition.put("outputSchema", schemaOf(outputs));
        }

        // ★ 本项目所有工具都是只读查询。声明它只是提示，见类注释
        definition.put("annotations", Map.of("readOnlyHint", true));
        return definition;
    }

    /**
     * 字段列表 → 一个 JSON Schema object。
     *
     * <p>{@code required} 只在<b>真有必填字段时</b>才出现 ——
     * 空的 {@code "required": []} 在 JSON Schema 里虽然合法，
     * 但会让 schema 变长且没有任何信息量。
     */
    private static Map<String, Object> schemaOf(List<ToolField> fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();

        for (ToolField field : fields) {
            properties.put(field.name(), field.toSchemaProperty());
            if (field.required()) {
                required.add(field.name());
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        // ★ 和 McpArguments 的「拒绝未知参数」必须一致，见方法注释
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 日志/探针用的一行描述 */
    default String display() {
        String inputs = inputFields().stream()
                .map(ToolField::display)
                .collect(Collectors.joining(", "));
        return name() + "(" + inputs + ")";
    }
}
