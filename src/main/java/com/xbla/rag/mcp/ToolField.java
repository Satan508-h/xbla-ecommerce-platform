package com.xbla.rag.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具的一个参数（或输出字段）的元数据 —— <b>★ 本包最重要的一个设计。</b>
 *
 * <h2>★★ 它同时是「schema 里写什么」和「从哪取值」的唯一来源</h2>
 *
 * <p>工具定义有两件事要描述同一个东西：
 *
 * <pre>
 *   ① 给模型看的 inputSchema：{"order_no": {"type":"string", ...}, "required":["order_no"]}
 *   ② 工具实现里真正去读的那个 key：arguments.get("order_no")
 * </pre>
 *
 * <p>如果这两件事分别手写，它们就会漂移 —— 而且漂移的方式是<b>静默</b>的：
 * schema 里写 {@code orderNo}、实现里读 {@code order_no}，模型照着 schema 发，
 * 实现拿到 null，工具报「缺参数」，而 schema 看起来完全正确。
 *
 * <p>所以本类把名字、类型、必填、描述收在一起，
 * <b>生成 schema 和读取取值都走同一个常量</b>：
 *
 * <pre>{@code
 * private static final ToolField ORDER_NO =
 *         ToolField.requiredString("order_no", "订单号，形如 SO2026...");
 *
 * public List<ToolField> inputFields() { return List.of(ORDER_NO); }   // ① 生成 schema
 *
 * public McpToolResult call(McpArguments args, McpToolContext ctx) {
 *     String orderNo = ORDER_NO.requireString(args);                   // ② 取值
 * }
 * }</pre>
 *
 * <p>名字只写了一次，所以<b>物理上不可能漂移</b>。
 *
 * <h2>为什么不做成注解 + 反射</h2>
 *
 * <p>那正是 5.8 要用的官方 SDK 的做法，本项目 5.7 是<b>手写</b> Server ——
 * 用注解等于在重写那个 SDK，而且反射会让「参数从哪来」不能逐行读，
 * 和 CLAUDE.md 第 1 条「可逐行解释」相悖。
 */
public record ToolField(String name, Type type, boolean required, String description) {

    /**
     * JSON Schema 的 {@code type}。只支持这两种 —— 多一种就要多一份校验代码。
     *
     * <p>★ 阶段 9.3 <b>删掉了原来的第三个值 {@code BOOLEAN}</b>。它从 5.7 起就躺在这里，
     * 但两条腿都是断的：
     * <ul>
     *   <li>没有任何工厂造得出它（只有 string / int 那四个）</li>
     *   <li>{@link McpArguments} 里也没有对应的读取器</li>
     * </ul>
     * 于是它成了一个<b>存在但不可达</b>的枚举值。下一个人照着写
     * {@code new ToolField("x", Type.BOOLEAN, ...)} 时编译器<b>不会拦他</b> ——
     * 类型是对的 —— 要等到 {@code toSchemaProperty()} 产出一份模型能照着发、
     * 而我们读不出来的 schema 才会暴露。
     *
     * <p>★ 判据：<b>删除是编译期可见的，留着是给下一个人埋雷。</b>
     * 真需要布尔参数时再加，那时连工厂和读取器一起加。
     */
    public enum Type {
        STRING("string"),
        INTEGER("integer");

        private final String jsonSchemaType;

        Type(String jsonSchemaType) {
            this.jsonSchemaType = jsonSchemaType;
        }

        public String jsonSchemaType() {
            return jsonSchemaType;
        }
    }

    public ToolField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具字段名不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("工具字段 " + name + " 缺少类型");
        }
    }

    // ============================================================
    // 声明
    // ============================================================

    public static ToolField requiredString(String name, String description) {
        return new ToolField(name, Type.STRING, true, description);
    }

    public static ToolField optionalString(String name, String description) {
        return new ToolField(name, Type.STRING, false, description);
    }

    public static ToolField requiredInt(String name, String description) {
        return new ToolField(name, Type.INTEGER, true, description);
    }

    public static ToolField optionalInt(String name, String description) {
        return new ToolField(name, Type.INTEGER, false, description);
    }

    // ============================================================
    // 生成 schema
    // ============================================================

    /**
     * 这一个字段在 {@code inputSchema.properties} 里的样子。
     *
     * <p>用 {@link LinkedHashMap} 保证 {@code type} 在 {@code description} 前面 ——
     * schema 是给人看也给人 diff 的，顺序稳定能让 diff 只有真正的改动。
     */
    public Map<String, Object> toSchemaProperty() {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type.jsonSchemaType());
        property.put("description", description);
        return property;
    }

    // ============================================================
    // 取值
    // ============================================================

    /**
     * 读一个必填的字符串参数。
     *
     * <p>★ 能走到这里就说明 {@link McpArguments} 在构造时已经校验过
     * 「这个 key 存在且不是 null」了 —— 所以这里不再判空。
     * 校验分两处会让「到底谁负责」变得含糊，而那正是漏检的来源。
     *
     * @throws McpToolException 类型不对（比如模型发了个数字）
     */
    public String requireString(McpArguments args) {
        return args.string(this, true);
    }

    /** 读一个可选的字符串参数。缺失时返回 {@code null} */
    public String optionalString(McpArguments args) {
        return args.string(this, false);
    }

    /** 读一个必填的整数参数 */
    public int requireInt(McpArguments args) {
        return args.integer(this, true);
    }

    /** 读一个可选的整数参数。缺失时返回 {@code null} */
    public Integer optionalInt(McpArguments args) {
        return args.integer(this, false);
    }

    /** 日志里用的短名 */
    public String display() {
        return name + (required ? "（必填）" : "（可选）");
    }
}
