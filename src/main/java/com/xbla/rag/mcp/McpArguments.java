package com.xbla.rag.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 {@code tools/call} 的参数，<b>已经按工具声明的字段校验过</b>。
 *
 * <p>调用方（{@link McpServer}）负责把「原始 JSON + 工具声明的字段列表」变成它，
 * 校验就在构造期一次做完：
 *
 * <ol>
 *   <li>有没有缺必填字段</li>
 *   <li>有没有多出没声明过的字段</li>
 * </ol>
 *
 * <p>类型校验不在这里 —— 它发生在<b>取值</b>的时候（{@link ToolField#requireString}），
 * 因为「这个字段该是什么类型」是字段自己知道的，不是这个容器知道的。
 *
 * <h2>★ 为什么【拒绝】未知参数，而不是忽略</h2>
 *
 * <p>模型多传一个参数的原因通常有两种，两种都值得让它知道：
 *
 * <ul>
 *   <li>它<b>猜</b>了 schema 里没有的字段（比如自己加一个 {@code user_id}）。
 *       忽略掉的话它会以为自己猜对了，下次继续猜 —— 而且那条调用链看起来完全正常。</li>
 *   <li>schema 和实现真的不一致（有人改了字段名没改全）。
 *       忽略掉就会静默用默认值跑，而 {@code additionalProperties: false}
 *       是把这处不一致<b>变成一次响亮的报错</b>。</li>
 * </ul>
 *
 * <p>★ 顺带一提，第 2 种情况如果发生在 {@code user_id} 上，那正是越权的入口 ——
 * 见 {@link McpToolContext}。
 */
public final class McpArguments {

    private final Map<String, Object> raw;

    private McpArguments(Map<String, Object> raw) {
        this.raw = raw;
    }

    /**
     * 校验并包装。
     *
     * @param raw            模型发来的原始 arguments。允许为 null（等于空对象）
     * @param declaredFields 该工具声明的入参字段
     * @throws McpToolException 缺必填、或出现未声明的字段
     */
    public static McpArguments of(Map<String, Object> raw, List<ToolField> declaredFields) {
        Map<String, Object> safeRaw = raw == null ? Map.of() : raw;

        // ── ① 不能有没声明过的字段 ──
        for (String key : safeRaw.keySet()) {
            boolean declared = declaredFields.stream().anyMatch(f -> f.name().equals(key));
            if (!declared) {
                throw new McpToolException(
                        "未知参数：" + key + "。本工具只接受：" + names(declaredFields),
                        "unknown_param:" + key);
            }
        }

        // ── ② 必填的不能缺 ──
        for (ToolField field : declaredFields) {
            if (!field.required()) {
                continue;
            }
            if (!safeRaw.containsKey(field.name()) || safeRaw.get(field.name()) == null) {
                throw new McpToolException(
                        "缺少必填参数 " + field.name() + "（" + field.description() + "）",
                        "missing_required:" + field.name());
            }
        }

        return new McpArguments(safeRaw);
    }

    /** 工具代码自己造参数时用（比如在测试里直接调 {@code tool.call}） */
    public static McpArguments unchecked(Map<String, Object> raw) {
        return new McpArguments(raw == null ? Map.of() : raw);
    }

    // ============================================================
    // 取值 —— 只有 ToolField 会调，工具代码请走自己的字段常量
    // ============================================================

    String string(ToolField field, boolean required) {
        Object value = raw.get(field.name());
        if (value == null) {
            if (required) {
                // 构造期已经查过必填了，走到这里说明有人绕过了 of()
                throw new McpToolException(
                        "缺少必填参数 " + field.name() + "（" + field.description() + "）",
                        "missing_required:" + field.name());
            }
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        // ★ 不把数字/布尔「顺手转成字符串」。宽松转换会让 schema 形同虚设 ——
        //   模型发 12345 当订单号，我们转成 "12345" 再查库，查不到，
        //   于是它得到一个「订单不存在」的答案，而真正的问题是它发错了类型
        throw typeMismatch(field, value, "字符串");
    }

    Integer integer(ToolField field, boolean required) {
        Object value = raw.get(field.name());
        if (value == null) {
            if (required) {
                throw new McpToolException(
                        "缺少必填参数 " + field.name() + "（" + field.description() + "）",
                        "missing_required:" + field.name());
            }
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        // JSON 解析器把整数读成 Integer，但超出 int 范围会读成 Long。
        // 这里接受 Long 但要求它能无损收窄 —— 不是「顺手转」，是「本来就是整数」
        if (value instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return l.intValue();
        }
        // ⚠️ 字符串 "8" 也【不】接受。理由同上：那会让「模型发错了类型」
        //    伪装成一次正常的调用，而它本该被纠正
        throw typeMismatch(field, value, "整数");
    }

    private static McpToolException typeMismatch(ToolField field, Object actual, String expected) {
        String actualType = actual.getClass().getSimpleName();
        return new McpToolException(
                "参数 " + field.name() + " 类型不对：期望" + expected + "，实际收到 " + actualType
                        + "。请按 schema 里的类型重新发送。",
                "type_mismatch:" + field.name() + ":" + expected);
    }

    private static String names(List<ToolField> fields) {
        return fields.stream().map(ToolField::name).reduce((a, b) -> a + ", " + b).orElse("（无参数）");
    }

    /** 打日志用 —— 避免把完整参数打进日志（将来可能有敏感字段） */
    public Map<String, Object> asMap() {
        return new LinkedHashMap<>(raw);
    }

    public boolean isEmpty() {
        return raw.isEmpty();
    }
}
