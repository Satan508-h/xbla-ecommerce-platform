package com.xbla.rag.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把模型的回复解析成「一个 code + 一个计划」（阶段 9.2）。
 *
 * <h2>★★ 两步，而且顺序不能反</h2>
 *
 * <pre>
 *   ① JSON   去掉围栏 → 花括号配对取第一个 {…} → Jackson 读成 Map
 *            → intent 必须在意图树里合法，否则【整体失败，不回退到 ②】
 *   ② CODE   老路径原样搬过来：去围栏 / 取首行非空 / 去两端引号标点 / 大写
 *            → findTarget 精确匹配
 *   ③ 都失败 → UNPARSED → 分类整体失败（Outcome.UNKNOWN_CODE）
 * </pre>
 *
 * <h2>★ ① 失败时为什么不回退到 ②</h2>
 *
 * <p>「半截 JSON」和「模型按老格式答的」是两件事，而它们的修法相反：
 * 前者要去改 prompt（模型没跟上契约），后者只是模型偷懒 —— 而且后者
 * <b>不影响正确性</b>（老的 code 路径是好用的）。
 *
 * <p>回退会把前者<b>伪装成</b>后者：日志上看不见，而「门控一次都没生效」
 * 这件事就没有任何迹象了。所以 JSON 一旦被识别出来（有花括号）却<b>内容不合法</b>，
 * 就直接判失败，并把原文带出来。
 *
 * <h2>★★ 允许「在回复里找 JSON」，却【不】允许「在回复里搜一个像 code 的词」</h2>
 *
 * <p>这两件事看起来双标，其实判据不同：
 *
 * <pre>
 *   找 JSON   找的是【我们明确要求过的输出格式】，而且找到之后
 *             【每一个字段都要被校验】—— intent 非法就整体失败
 *   搜 code   找的是一个【碰巧长得像 code 的单词】
 *             → 会把「模型在胡说，只是话里凑巧有个 RETURN_EXCHANGE」当成成功
 * </pre>
 *
 * <p>所以前者是「解析我们自己的协议」，后者是「猜模型的意图」。见
 * {@code LlmIntentClassifier} 类注释第三节（那条纪律从阶段 5.2 就在）。
 */
@Component
public class IntentReplyParser {

    private final ObjectMapper objectMapper;

    public IntentReplyParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析结果。
     *
     * @param code 合法的分类 code；解析失败时为 {@code null}
     * @param plan 计划；失败时为 {@code null}
     * @param note 给日志看的一句话。成功且走 JSON 时为 {@code null}
     */
    public record ParsedReply(String code, IntentPlan plan, String note) {

        public boolean ok() {
            return code != null;
        }

        static ParsedReply fail(String note) {
            return new ParsedReply(null, null, note);
        }
    }

    /**
     * @param rawReply 模型的原始回复（未清洗）
     * @param tree     当前加载的意图树 —— 用来校验 {@code intent} 是否合法
     */
    public ParsedReply parse(String rawReply, IntentTree.Tree tree) {
        if (rawReply == null || rawReply.isBlank()) {
            return ParsedReply.fail("模型回复为空");
        }

        // ① JSON —— 先试新契约
        String json = extractJsonObject(rawReply);
        if (json != null) {
            return parseJson(json, tree);
        }

        // ★★ 有 { 却没有配对的 } —— 模型【尝试了】新契约但没写完。
        //    它和「模型按老格式答的」是两件事，原因必须说准：
        //    说成「既不是 JSON 也不是 code」会把读者引向「模型在乱答」，
        //    而真正该做的是回去看 prompt 是不是太长/太难。
        //    ★ 结论（失败）和下面裸码路径失败时一样，变的只是那句原因 ——
        //      而排查时唯一的线索就是那句话（同 docs/10 坑 20）。
        if (rawReply.indexOf('{') >= 0) {
            return ParsedReply.fail("花括号没有闭合（半截 JSON）");
        }

        // ② CODE —— 老路径（模型没按新契约答，但答对了）
        String candidate = normalize(rawReply);
        if (tree.findTarget(candidate).isEmpty()) {
            return ParsedReply.fail("既不是合法的 JSON 计划，也不是合法的 code");
        }
        // ★ 走这条路拿不到计划和槽位 —— 检索取保守值 true，
        //   也就是【9.2 之前的行为】。不这么做的话，模型偶尔偷个懒
        //   就会让那一次问答的检索行为发生变化，而那是个随机事件。
        return new ParsedReply(candidate, IntentPlan.bareCode(),
                "模型没有按 JSON 契约作答，走回退路径（拿不到计划）");
    }

    /**
     * 解析一个已经提取出来的 JSON 对象字符串。
     *
     * <p>★ 校验规则（每一条都刻意选<b>不回退</b>或<b>回退到保守值</b>）：
     *
     * <pre>
     *   intent  缺失 / 不是字符串 / 不在树里  → 【整体失败】（见类注释第二节）
     *   retrieve 缺失 / 类型不对             → 取 true（保守 = 不改变原有行为）
     *   missing  缺失 / 不是字符串数组        → 取空表
     * </pre>
     *
     * <p>后两条为什么不判失败：它们缺了<b>不影响这次问答能答出来</b>，
     * 只是少了点信息。为它们把整次分类判死，是拿一个「可用」换一个「精确」——
     * 而分类失败的代价是<b>退化成全池检索</b>（慢且更吵）。
     */
    private ParsedReply parseJson(String json, IntentTree.Tree tree) {
        Map<?, ?> map;
        try {
            map = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            // 有花括号但读不成对象 —— 半截 JSON，判失败（不猜）
            return ParsedReply.fail("JSON 解析失败：" + e.getMessage());
        }

        Object rawIntent = map.get("intent");
        if (!(rawIntent instanceof String intent) || intent.isBlank()) {
            return ParsedReply.fail("JSON 里没有 intent 字段");
        }
        String candidate = normalize(intent);
        if (tree.findTarget(candidate).isEmpty()) {
            return ParsedReply.fail("JSON 里的 intent 不是合法 code：" + intent);
        }

        return new ParsedReply(candidate,
                new IntentPlan(readRetrieve(map.get("retrieve")),
                        readMissing(map.get("missing")),
                        IntentPlan.Shape.JSON),
                null);
    }

    /** {@code retrieve} 缺省取 true —— 保守值，见 parseJson 的说明 */
    private static boolean readRetrieve(Object raw) {
        return raw instanceof Boolean b ? b : true;
    }

    /** {@code missing} 只收非空字符串，顺序保留（它是给 9.4 看的，顺序有意义） */
    private static List<String> readMissing(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> slots = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                slots.add(s.trim());
            }
        }
        return slots;
    }

    /**
     * 从回复里取出<b>第一个完整的 JSON 对象</b>，取不到返回 {@code null}。
     *
     * <p>★ 用<b>花括号配对</b>而不是「第一个 { 到最后一个 }」：
     * 模型很爱在 JSON 后面再补一句解释（甚至再给一个例子），
     * 取到最后一个 {@code }} 会把两段粘成一段非法 JSON。
     *
     * <p>★ 而且配对时要<b>跳过字符串字面量里的花括号</b> ——
     * 否则一个内容里带 {@code "}"} 的值就会让配对提前结束，
     * 症状是「多数时候正常、偶尔解析失败」，最难查的那一类。
     */
    static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;    // 花括号没闭合 = 半截 JSON，交给调用方判失败
    }

    /**
     * 清洗模型返回，让它能被精确匹配。
     *
     * <p>只做<b>不改变语义</b>的处理，步骤和顺序都在下面写清楚了 ——
     * 这是本类唯一一处「猜模型想说什么」的地方，所以要能逐行解释。
     *
     * <p>★ 它从 {@code LlmIntentClassifier} 搬过来（阶段 9.2），逻辑一个字没改 ——
     * 搬家的理由是本类才是「解析模型回复」这件事的归属地。
     */
    static String normalize(String reply) {
        if (reply == null) {
            return null;
        }

        // ① 去掉 Markdown 代码围栏。模型很爱把答案包在 ``` 里，
        //    而围栏是格式噪声，去掉它不会改变模型想表达的东西
        String text = reply.replace("```", "").trim();

        // ② 取第一行非空。prompt 明确要求「只输出一个 code」，
        //    所以答案一定在第一行；后面的内容无论是什么都是多余的
        for (String line : text.split("\\R")) {
            String candidate = line.trim();
            if (!candidate.isEmpty()) {
                text = candidate;
                break;
            }
        }

        // ③ 去掉两端的引号类和尾部标点。★ 都只动【两端】，
        //    不碰中间 —— 中间出现标点说明这多半不是我们想要的格式，
        //    那种情况应该走 UNKNOWN_CODE 把原话报出来
        text = text.replaceAll("^[`'\"*\\s]+", "")
                   .replaceAll("[`'\"*.。,，;；:：、\\s]+$", "");

        // ④ 统一大写。意图码本身是大写下划线，模型偶尔会回小写，
        //    这是纯粹的书写差异，不该算分类失败
        return text.toUpperCase(Locale.ROOT);
    }
}
