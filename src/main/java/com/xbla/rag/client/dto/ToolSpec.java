package com.xbla.rag.client.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个<b>可以给模型看的</b>工具描述 —— 「你有什么工具可用」。
 *
 * <h2>★ 为什么要有这个类，而不是直接把 SDK 的 Tool 传下来</h2>
 *
 * <p>5.7 手写的 {@code McpTool} 用 {@code ToolField} 描述参数，
 * 5.8 的官方 SDK 用 {@code McpSchema$Tool} 描述参数，而模型只认
 * OpenAI 协议的 {@code {"type":"function","function":{...}}}。
 *
 * <p><b>这三种形状必须在一个地方收敛，而且只能在一个地方。</b>
 * 如果让 {@code agent/} 层直接拿到 SDK 的 {@code McpSchema$Tool}，
 * 那么「换个 SDK 版本」或「将来接第二个 MCP 供应商（比如 stdio）」
 * 就会波及智能体层 —— 而智能体层本来只关心「模型要调用什么」。
 *
 * <p>所以方向是单向的：
 * <pre>
 *   SDK 的 Tool  ──翻译──▶  ToolSpec  ──序列化──▶  OpenAI 协议的 tools 字段
 *      (mcp/client)          (client/dto)              (WireChatRequest)
 * </pre>
 *
 * <p>这和 {@code ChatRequest}（领域对象）vs {@code WireChatRequest}（线格式）
 * 是同一条纪律：<b>靠近外部的那一层负责翻译，里面的人只见一种形状。</b>
 *
 * @param name        工具名。★ 模型下一轮会用它，所以它必须和工具注册表里的键
 *                    <b>逐字相同</b> —— 差一个字符的后果是「模型调了一个不存在的工具」，
 *                    而那在日志里看起来像模型的问题
 * @param description 给模型看的用途说明。这是<b>模型选不选它的唯一依据</b>，
 *                    写法直接决定工具调用的准确率
 * @param inputSchema 参数的 JSON Schema（{@code {"type":"object","properties":{...}}}）。
 *                    ★ 保留成 {@code Map} 而不是建一套类型：schema 是<b>给模型看的</b>
 *                    数据，我们从头到尾不解析它，只搬运
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {

    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空 —— 模型靠它选中工具");
        }

        // ★★ 这里【不能】用 Map.copyOf，理由有两层，第二层更隐蔽：
        //
        //   ① 它拒绝 null 值。JSON Schema 的字段描述合法地可以是 null ——
        //      「这个参数没有说明」是正常状态。NPE 会抛在【构造函数】里，
        //      堆栈指向 ToolSpec 而不是那个 null 字段，排查方向直接跑偏。
        //      （同 {@code McpToolResult} 的 ADR-058。）
        //
        //   ② ★ 它【打乱顺序】。Map.copyOf 返回的是 ImmutableCollections.MapN，
        //      迭代顺序由 hash 决定，而 JDK 9+ 的 hash 里掺了一个每次 JVM
        //      启动随机生成的 SALT。实测三个独立 JVM 跑同一份已排序的
        //      LinkedHashMap，得到三种不同的顺序。
        //
        //      后果：这个 schema 会被序列化进 tools 数组，而 tools 数组是
        //      Prompt 前缀的一部分。顺序一变 → 每次重启后第一个请求整段
        //      未命中 DeepSeek 的上下文缓存 → cache-hit-input 0.02 和
        //      input 1.0 差 50 倍。
        //      （同 {@code McpToolRegistry} 类注释里记的那次。）
        inputSchema = inputSchema == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }

    /**
     * 转成 OpenAI 协议的 {@code tools} 数组里的一项。
     *
     * <pre>{@code
     *   {"type":"function",
     *    "function":{"name":"query_order_status","description":"…","parameters":{…}}}
     * }</pre>
     *
     * <p>★ 多出来的那一层 {@code "type":"function"} 是协议的形状要求，
     * 不是冗余 —— {@code tools} 数组理论上还能放别的类型（比如内置的
     * {@code code_interpreter}）。我们只支持 function，但结构不能省。
     *
     * <p>★★ <b>全程用 {@link LinkedHashMap}，一个 {@code Map.of} 都不能有。</b>
     *
     * <p>这个 JSON 会被序列化进请求体的 {@code tools} 数组，而 {@code tools}
     * 是 Prompt 前缀的一部分 —— DeepSeek 的上下文缓存按前缀匹配，
     * 命中价 {@code 0.02}、未命中 {@code 1.0}，差 50 倍。
     *
     * <p>而 {@code Map.of} 的<b>迭代顺序是未定义的</b>：实测三个独立 JVM
     * 对同样三个键得到三种顺序：
     * <pre>
     *   run 1: {"parameters", "description", "name"}
     *   run 2: {"name", "description", "parameters"}
     *   run 3: {"description", "name", "parameters"}
     * </pre>
     *
     * <p>原因是 JDK 9+ 的 {@code ImmutableCollections} 用一个
     * <b>每次 JVM 启动随机生成</b>的 SALT 扰动 hash。它对「防 hash 碰撞攻击」
     * 是好事，代价就是这里的顺序不稳定 —— 每次重启后第一个请求整段未命中。
     *
     * <p>⚠️ 这个坑和 {@link com.xbla.rag.mcp.McpToolRegistry} 里记的那次
     * 是同一个机制。规律：<b>凡是会进 Prompt 前缀的 JSON，一律用 LinkedHashMap。</b>
     */
    public Map<String, Object> toWireTool() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description == null ? "" : description);
        function.put("parameters", inputSchema);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }
}
