package com.xbla.rag.agent.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>悬着的澄清反问</b> —— 「上一轮我们问了什么，还没拿到回答」（阶段 9.4）。
 *
 * <h2>☆☆ 它要修的那个洞，有实测数字</h2>
 *
 * <pre>
 *   用户：那个怎么样      → 分类器判 NEEDS_CLARIFICATION → 反问「你是想问哪款商品呢？」
 *   用户：送长辈          → 分类器【只看到这三个字】→ 仍是「信息不足」→ 再问一次
 * </pre>
 *
 * <p>实测（{@code docs/05} §9.5 ②）：<b>最自然的追问方式有约 2/3 被澄清闸门挡掉</b>
 * （三次运行「澄清 / 放行 / 澄清」）。
 *
 * <p>本对象就是那三个字在下一轮的<b>唯一上下文</b>：
 * 「上一轮问的是什么、缺的是哪一项、用户那句话原本是什么」。
 *
 * <h2>★★ 与 ADR-046 的边界：这不是「把历史喂给分类器」</h2>
 *
 * <table border="1">
 *   <caption>两件事的区别</caption>
 *   <tr><th></th><th>会话历史（5.5 的机制）</th><th>本对象</th></tr>
 *   <tr><td>形态</td><td>自由文本，可能几十轮</td><td>一小段结构化状态，四个字段</td></tr>
 *   <tr><td>给谁看</td><td><b>只给生成</b>（ADR-046 的禁令）</td><td><b>只给分类</b>那一次调用</td></tr>
 *   <tr><td>出现几轮</td><td>每轮都在窗口里</td><td><b>只出现一轮</b>（读后即清）</td></tr>
 * </table>
 *
 * <p>★ 那条禁令防的是「分类器的输入变成可变长度的自由文本」——
 * 5.2 的准确率和 5.4 的「20/20 可证明安全」都建立在
 * 「分类器的输入只有这一句话」之上，而 20 道题的评测集测不出那个变化是好是坏。
 * 本对象是<b>定长、有结构、可枚举</b>的，且窗口只有一轮 ——
 * 有了它，「上一轮问过什么」这件事在分类时<b>可见且可测</b>。
 *
 * <h2>★ 生命周期：读后即清</h2>
 *
 * <pre>
 *   澄清轮结束  → 写（ChatServiceImpl 的落库段，与 touchSession 同一处）
 *   下一轮开始  → 读出来 + 立刻清空 → 之后这一轮怎么走都不再管它
 * </pre>
 *
 * <p>一次性。所以这一列没有 TTL 字段、也不需要定时清理 ——
 * 不存在「一个坏状态永久卡住那个会话」那种形态（ADR-049 的孤儿消息就是那个形态）。
 *
 * @param question 用户<b>最初</b>那句话（「那个怎么样」）。
 *                 ★ 它是注入给模型的锚 —— 只说「缺 product」模型不知道在问哪件事
 * @param slots    上一轮缺的槽位（<b>模型的原话</b>，可能是它自创的名字）。
 *                 ★ 这里不做白名单：过滤是<b>消费端</b>的事（见 {@link ClarifySlots}），
 *                 落库的这一份保留原话才看得见「模型开始编槽位了」
 * @param asked    ★ <b>我们实际问的是哪一个</b>（过滤 + 优先级之后的结果）。
 *                 它与 {@code slots} 会不同 —— 缺三个只问一个，而注入时要告诉模型
 *                 「你问的是这一项」，不是「你缺这三项」
 * @param askedAt  什么时候问的。<b>不参与任何判断</b>（没有过期逻辑），
 *                 只用于排查时读一眼「这个反问悬了多久」
 */
public record PendingClarify(String question, List<String> slots, String asked,
                             OffsetDateTime askedAt) {

    private static final Logger log = LoggerFactory.getLogger(PendingClarify.class);

    /** JSON 里的版本号。★ 与 {@code intent_plan.v} 同一个用法：下游按它分派 */
    public static final int VERSION = 1;

    public PendingClarify {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    public static PendingClarify of(String question, List<String> slots, String asked) {
        return new PendingClarify(question, slots, asked, OffsetDateTime.now());
    }

    /**
     * 序列化成 {@code chat_session.pending_clarify} 的那段 JSON。
     *
     * <p>★ {@code LinkedHashMap} 而不是 {@code Map.of} —— 同
     * {@code IntentPlan} / {@code McpToolSpec} 那条纪律：键序稳定，
     * 人能直接 diff 两次输出（它不进 prompt 前缀，但可读性值这份钱）。
     *
     * <p>★ 失败只记 ERROR 并返回 {@code null}（同 {@code serializeIntentPlan}）——
     * 「记录状态」失败不该让已经生成的回答作废。返回 null 的后果是
     * <b>这一轮的反问不会被记住</b>，退化成 9.3 的行为，不是故障。
     */
    public static String write(ObjectMapper mapper, PendingClarify pending) {
        if (pending == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", VERSION);
        row.put("question", pending.question());
        row.put("slots", pending.slots());
        row.put("asked", pending.asked());
        row.put("at", pending.askedAt() == null ? null : pending.askedAt().toString());
        try {
            return mapper.writeValueAsString(row);
        } catch (Exception e) {
            log.error("pending_clarify 序列化失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 从库里那一列读回来。<b>任何情况下都不抛</b>。
     *
     * <p>返回 {@code null} 的三种情况（调用方一视同仁：当作没有待澄清）：
     *
     * <pre>
     *   ① 这一列是 NULL / 空白      —— 常态，压根没问过反问
     *   ② JSON 坏了                 —— 记 WARN。★ 不抛：一个坏状态不该让整次问答失败
     *   ③ 缺 question 或它空白      —— 没有锚的注入是无用的，宁可不注入
     * </pre>
     *
     * <p>★ ②③ 都要 WARN 而不是 DEBUG：它们是<b>代码或数据的 bug</b>，
     * 而这一列在库里的样子（一个看不懂的 JSON）从任何指标上都看不出来。
     */
    public static PendingClarify read(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            String question = node.path("question").asText(null);
            if (question == null || question.isBlank()) {
                log.warn("★ pending_clarify 缺 question，本次当作没有待澄清：{}", abridge(json));
                return null;
            }
            List<String> slots = new ArrayList<>();
            for (JsonNode item : node.path("slots")) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    slots.add(item.asText());
                }
            }
            String asked = node.path("asked").asText(null);
            OffsetDateTime at = parseTime(node.path("at").asText(null));
            return new PendingClarify(question, slots, asked, at);
        } catch (Exception e) {
            // ★ 说准了的原因（docs/10 坑 20：错误分支只能报告它核实过的东西）
            log.warn("★ pending_clarify 解析失败（{}），本次当作没有待澄清：{}",
                    e.getMessage(), abridge(json));
            return null;
        }
    }

    private static OffsetDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (Exception e) {
            return null;        // 时间只用于排查，读不出来就算了 —— 不影响任何判断
        }
    }

    private static String abridge(String text) {
        String flat = text.replace("\n", "\\n");
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    /** 日志用的一行摘要（★ 不含用户原话全文 —— 同 ToolLoop 的日志纪律） */
    public String describe() {
        return "asked=" + asked + " slots=" + slots + " question=" + abridge(question);
    }
}
