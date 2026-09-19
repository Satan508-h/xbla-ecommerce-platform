package com.xbla.rag.agent.memory;

import com.xbla.rag.entity.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 组装「把一段对话压成摘要」的 prompt（阶段 5.6）。<b>纯函数，无依赖，可单测。</b>
 *
 * <h2>一、★ 为什么摘要是「一次文本处理」而不是「一次对话」</h2>
 *
 * <p>摘要请求的 {@code messages} 数组是：
 *
 * <pre>
 *   [system]  摘要规则（固定文本）
 *   [user]    【已有摘要】… 【新增对话】用户：… 助手：…
 * </pre>
 *
 * <p>而<b>不是</b>把待压缩的对话原样当成多轮 messages 发过去。
 * 理由是后者会让模型以为<b>自己正在这场对话里</b> ——
 * 它可能不回摘要，而是接着回答最后那个问题。
 * 这个失败模式很隐蔽：拿回来的是一段通顺的中文，只是<b>根本不是摘要</b>。
 *
 * <p>发成一次文本处理，模型的角色就明确是「加工者」而不是「参与者」。
 *
 * <h2>二、★★ 摘要的敌人是【失真累积】，不是「压得不够短」</h2>
 *
 * <p>摘要会被反复重压：第一次压对话 1–2，第二次压「上次的摘要 + 对话 3–4」，
 * 第三次再压「上次的摘要 + 对话 5–6」……这就是传话游戏。
 * 每一次都是<b>有损</b>的，压十次之后可能面目全非。
 *
 * <p>所以 prompt 里有一条专门的约束：
 *
 * <blockquote>
 *   已有摘要中的事实保持不变，<b>不要改写它们的措辞</b>。
 * </blockquote>
 *
 * <p>它把「增量更新」和「重写」区分开 —— 模型默认倾向于把整段文字
 * 重新组织一遍（那是它的强项），而组织就意味着改措辞，
 * 改措辞就意味着「两千左右」可能变成「2000 元上下」，再压一次变成「预算适中」，
 * <b>具体数字就这样蒸发掉了</b>。
 *
 * <h2>三、★★ 摘要里的幻觉会污染后面【所有】轮次</h2>
 *
 * <p>单次问答答错，错一轮就过去了。摘要编错了，<b>接下去的每一轮都带着它</b>，
 * 而且因为摘要是「记忆」，模型对它的信任度高于当轮检索到的资料。
 *
 * <p>所以「不许推测」这条写得比「尽量简洁」重得多。
 * 模型面对「用户说想给爸妈买手机」很自然会补一句「用户在考虑送长辈的礼物」——
 * 听起来无害，但那已经是<b>推断</b>，而推断链一旦进了摘要就会一直传下去。
 */
@Component
public class SummaryPromptBuilder {

    /**
     * 待压缩对话的渲染上限（条）。
     *
     * <p>和 {@code SessionSummarizer} 的取数上限一致，这里只是最后一道防御 ——
     * 真正控制条数的地方在 SQL 的 {@code LIMIT}。
     */
    private static final int MAX_RENDER_MESSAGES = 100;

    /** 单条消息渲染进 prompt 的上限，同 {@code ConversationMemory.MAX_CHARS_PER_TURN} */
    private static final int MAX_CHARS_PER_MESSAGE = 1000;

    private static final String TRUNCATED_SUFFIX = "…（已截断）";

    /**
     * 摘要规则。
     *
     * <p>⚠️ 它是<b>固定文本</b>（只有字数上限一个变量），所以每次请求的
     * system 段都高度一致 —— 但摘要请求本来就是每次不同的对话内容，
     * 缓存前缀的价值远不如问答那条链路，这里不为缓存做额外设计。
     *
     * <p>{@code %d} 是字数上限。
     */
    private static final String SYSTEM_PROMPT = """
            你是一个对话摘要器。你的唯一任务是把「一段客服对话」压缩成简洁的中文摘要。

            【必须保留】
            1. 用户明确说过的具体信息：预算、用途、商品型号、数量、时间、地点、称呼
            2. 用户已经排除或明确拒绝的选项
            3. 已经得出的结论（一句话即可，不要复述推理过程）

            【绝对禁止】
            1. 不要推测用户没有明说的事。用户说「给爸妈买」不等于「送长辈的礼物」，
               用户说「两千左右」不等于「预算两千元」—— 原样保留，不要换算、不要归纳
            2. 不要把助手说过的话写成「用户说」
            3. 不要添加评价、建议、推荐或任何新内容
            4. 不要在摘要里写「用户询问了…助手回答了…」这种流水账 ——
               直接陈述事实本身

            【输出要求】
            1. 直接输出摘要正文。不要标题、不要前缀、不要「以下是摘要」之类的开场白
            2. 用第三人称陈述，例如：用户预算两千左右，用途是和孙子视频通话
            3. 如果给了【已有摘要】，它是对更早对话的摘要：
               请把它和【新增对话】合并成一段完整摘要。
               已有摘要里的事实保持不变，不要改写它们的措辞 ——
               这一步是「追加」不是「重写」。
            4. 总长度不超过 %d 字。

            【★★ 摘要的主角是「用户」，不是「助手」】
            上面第 3 条和第 4 条会打架：既要保留事实、又要不超字数。
            打架时【以第 4 条为准】—— 超了就要【丢弃】内容，不要写成超长的一段。
            按下面的顺序丢（从先丢到后丢）：

              ① 助手回答里的解释、依据、引用编号、办理流程的步骤细节
                 —— 这些【下次检索还能再查到】
              ② 与用户目的无关的问答（用户只是随口一问的那种）
              ③ 用户已经明确排除掉的选项

            ★ 无论如何都要保留：用户的预算、用途、商品型号、数量、时间，
              以及用户明确说过的偏好和限制。这些是【再检索一次也查不到】的东西，
              丢掉它们等于这一整段对话白聊。
            """;

    /**
     * 超预算时前置的强制压缩指令。
     *
     * <h4>★ 为什么需要它（实测依据）</h4>
     *
     * <p>光在规则里写「不超过 N 字」<b>不够</b>。实测两次真实的 10 轮以上对话：
     *
     * <pre>
     *   修规则前：209 → 435 → 740 → 1129 字（目标 800）—— 一路涨
     *   修规则后：同样规模 415 字，但 20 轮时又到 885 字
     * </pre>
     *
     * <p>模型的默认行为是<b>追加</b>，而字数上限被它当成了「建议」。
     * 一直涨下去就会撞上硬上限被截断，而截断是<b>从尾部</b>切的 ——
     * 切掉的正好是它刚追加的、也就是<b>最新</b>的内容。
     *
     * <p>所以当检测到「上一次已经超了」，就把这个事实<b>明确告诉它</b>，
     * 并切换成一次真正的重写压缩。这比在静态规则里反复强调「请务必」有效：
     * 它带上了具体的状态（「你上次超了」），而不是一条抽象要求。
     */
    private static final String OVER_BUDGET_WARNING = """
            ★★ 重要：上一次生成的摘要【已经超过了字数上限】。
            这一次你必须做一次真正的压缩，而不是继续往后面追加：
            把已有摘要和新增对话合并后【重写】成不超过 %d 字的版本。
            按下面的优先级丢弃内容（从先丢到后丢）：
              ① 助手回答里的解释、依据、引用编号、办理流程的步骤细节
              ② 与用户目的无关的问答
              ③ 用户已经明确排除掉的选项
            必须保住用户的预算、用途、商品型号、偏好和限制。

            """;

    /**
     * 摘要规则（system 段）。等价于 {@code systemPrompt(maxChars, false)}。
     *
     * <p>保留这个重载是为了让「只想看看规则长什么样」的调用方（测试、调试）
     * 不用多写一个 {@code false}。
     */
    public String systemPrompt(int maxChars) {
        return systemPrompt(maxChars, false);
    }

    /**
     * 摘要规则（system 段）。
     *
     * @param maxChars   摘要正文的字数上限
     * @param overBudget 上一次生成的摘要<b>已经超过</b> {@code maxChars}。
     *                   为 {@code true} 时会在规则最前面加一段强制压缩指令 ——
     *                   见 {@link #OVER_BUDGET_WARNING} 的实测依据
     */
    public String systemPrompt(int maxChars, boolean overBudget) {
        String prompt = SYSTEM_PROMPT.formatted(maxChars);
        if (!overBudget) {
            return prompt;
        }
        return OVER_BUDGET_WARNING.formatted(maxChars) + prompt;
    }

    /**
     * 把待压缩的内容渲染成一条 user 消息。
     *
     * <p>结构：
     * <pre>
     *   【已有摘要】
     *   …（没有时就整块省略，不写「（无）」）
     *
     *   【新增对话】
     *   用户：…
     *   助手：…
     * </pre>
     *
     * <p>★ 没有旧摘要时<b>整块省略</b> —— 同 {@code RagPromptBuilder} 对空资料的
     * 处理：写「（无）」会把模型的注意力引向一个空的位置。
     *
     * @param previousSummary 已有摘要，可为 null
     * @param messages        待压缩的消息，按时间<b>正序</b>
     */
    public String renderTranscript(String previousSummary, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder(512);

        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("【已有摘要】（更早的对话）\n")
                    .append(previousSummary.strip())
                    .append("\n\n");
        }

        sb.append("【新增对话】\n");
        if (messages == null || messages.isEmpty()) {
            // 正常流程不会走到这里（调用方攒够了才调）。留一句明确的话而不是空着，
            // 因为「空输入」如果被静默发出去，模型会编一段摘要出来
            sb.append("（没有新增对话）\n");
        } else {
            int limit = Math.min(messages.size(), MAX_RENDER_MESSAGES);
            for (int i = 0; i < limit; i++) {
                ChatMessage m = messages.get(i);
                sb.append(speakerOf(m.getRole()))
                        .append('：')
                        .append(truncate(m.getContent()))
                        .append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * {@code chat_message.role} → 中文说话人。
     *
     * <p>不用 {@code role == 1 ? "用户" : "助手"} 这种三元式 ——
     * 一个意料之外的 role 会被<b>静默标成助手</b>，
     * 而那正是本类第三节明令禁止的「把助手的话写成用户说的」的反向版本。
     */
    private static String speakerOf(Integer role) {
        if (role == null) {
            return "未知";
        }
        return switch (role) {
            case 1 -> "用户";
            case 2 -> "助手";
            default -> "未知";
        };
    }

    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= MAX_CHARS_PER_MESSAGE) {
            return content;
        }
        return content.substring(0, MAX_CHARS_PER_MESSAGE) + TRUNCATED_SUFFIX;
    }
}
