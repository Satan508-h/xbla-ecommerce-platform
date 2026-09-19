package com.xbla.rag.agent.memory;

import com.xbla.rag.entity.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摘要 prompt 组装的纯单测（阶段 5.6）。
 *
 * <p>没有 Spring、没有数据库、不花钱 —— 被测的是个纯函数。
 */
@DisplayName("SummaryPromptBuilder · 摘要 prompt 组装")
class SummaryPromptBuilderTest {

    private static final int MAX_CHARS = 800;

    private final SummaryPromptBuilder builder = new SummaryPromptBuilder();

    private static ChatMessage msg(int role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    // ============================================================
    // 一、system 段
    // ============================================================

    @Nested
    @DisplayName("一、system 段：摘要规则")
    class SystemPrompt {

        @Test
        @DisplayName("★ 字数上限被写进规则里，且是传进去的那个值")
        void maxCharsIsInjectable() {
            assertThat(builder.systemPrompt(300, false)).contains("300 字");
            assertThat(builder.systemPrompt(1200, false)).contains("1200 字");
        }

        @Test
        @DisplayName("★★ 超预算时前置一段「强制压缩」，不带时一个字都没有")
        void overBudgetSwitchesMode() {
            String normal = builder.systemPrompt(MAX_CHARS, false);
            String forced = builder.systemPrompt(MAX_CHARS, true);

            assertThat(forced)
                    .as("★ 实测：光在静态规则里写「不超过 N 字」是不够的 ——"
                            + "模型会把追加当默认行为，一路涨到 1129 字（目标 800），"
                            + "最后撞上硬上限被截断。这句话带上的是【具体状态】"
                            + "（「你上次超了」），比抽象要求有效得多")
                    .contains("已经超过了字数上限")
                    .contains("重写")
                    .contains(String.valueOf(MAX_CHARS));

            assertThat(normal)
                    .as("★ 对照：没超预算时不该出现这段。每次都喊「狼来了」"
                            + "会让它变成背景噪音，真需要时也就不管用了")
                    .doesNotContain("已经超过了字数上限");

            assertThat(forced.indexOf("已经超过了字数上限"))
                    .as("★ 警告必须在规则【之前】—— 那是模型读到的第一句")
                    .isLessThan(forced.indexOf("你是一个对话摘要器"));
        }

        @Test
        @DisplayName("★ 强制压缩模式仍然带着原有的规则，不是替换掉")
        void overBudgetKeepsTheBaseRules() {
            String forced = builder.systemPrompt(MAX_CHARS, true);

            assertThat(forced)
                    .as("★ 是【追加一段】不是【换一套】—— 禁止推测、不要改写措辞"
                            + "这些约束在压缩模式下同样重要，而压缩恰恰是"
                            + "最容易改措辞、最容易丢具体数字的动作")
                    .contains("不要推测")
                    .contains("不要改写");
        }

        @Test
        @DisplayName("★ 三组关键约束都在（保留事实 / 禁止推测 / 增量不重写）")
        void allThreeConstraintGroupsArePresent() {
            String prompt = builder.systemPrompt(MAX_CHARS);

            assertThat(prompt)
                    .as("不写清楚要保留什么，模型会优先压掉「不重要的细节」——"
                            + "而预算、型号、用途恰恰是我们要它记住的那些")
                    .contains("预算")
                    .contains("商品型号");

            assertThat(prompt)
                    .as("★ 摘要是「记忆」，模型对它的信任度高于当轮资料 —— "
                            + "一条编造的事实会在后面【每一轮】里继续传下去")
                    .contains("不要推测");

            assertThat(prompt)
                    .as("★★ 传话游戏是本功能最大的结构性风险：摘要会被反复重压。"
                            + "把「追加」和「重写」区分开，才能让「两千左右」不会先变成"
                            + "「2000 元上下」、再变成「预算适中」，最后数字蒸发掉")
                    .contains("不要改写");
        }

        @Test
        @DisplayName("★ 明确禁止把助手的话写成用户说的")
        void forbidsSwappingSpeakers() {
            assertThat(builder.systemPrompt(MAX_CHARS))
                    .as("★ 反过来的错（把用户的偏好写成助手推荐的）会让模型"
                            + "「记住」一个用户从未表达过的需求，而且完全看不出异常")
                    .contains("不要把助手说过的话写成");
        }

        @Test
        @DisplayName("★★ 字数上限和「不许改写」打架时，给出【明确的丢弃优先级】")
        void lengthLimitWinsAndSaysWhatToDrop() {
            String prompt = builder.systemPrompt(MAX_CHARS);

            assertThat(prompt)
                    .as("★ 实测这一条不写清楚的话，摘要会一路涨："
                            + "2 条消息 → 209 字、5 条 → 435 字、8 条 → 740 字、11 条 → 1129 字。"
                            + "模型选择了「既要保留事实、又要不改写措辞」，"
                            + "而字数上限被当成了建议 —— 最后撞上硬上限被【截断】，"
                            + "截掉哪些内容完全随机")
                    .contains("以第 4 条为准")
                    .contains("丢弃");

            assertThat(prompt)
                    .as("★ 丢弃顺序必须是【可执行的】，不能只说「丢不重要的」——"
                            + "实测那种说法模型会当耳旁风。这里的关键洞察是："
                            + "助手的解释和办理流程【下次检索还能再查到】，"
                            + "而用户的预算/用途/型号【再检索一次也查不到】")
                    .contains("下次检索还能再查到")
                    .contains("再检索一次也查不到");
        }

        @Test
        @DisplayName("★★ 明说摘要的主角是【用户】，不是助手")
        void usersFactsOutrankAssistantAnswers() {
            assertThat(builder.systemPrompt(MAX_CHARS))
                    .as("★ 实测跑出来的摘要 90% 是「用户询问了 X。资料给出的口径：……」"
                            + "—— 把助手的回答整段搬了进去。而那些内容是可再生的，"
                            + "用户说过的自己才是不可再生的")
                    .contains("摘要的主角是「用户」")
                    .contains("无论如何都要保留");
        }
    }

    // ============================================================
    // 二、user 段：对话素材
    // ============================================================

    @Nested
    @DisplayName("二、user 段：对话素材")
    class Transcript {

        @Test
        @DisplayName("★ 按正序渲染，且带「用户：/助手：」前缀")
        void rendersInOrderWithSpeakers() {
            List<ChatMessage> messages = List.of(
                    msg(1, "问题一"),
                    msg(2, "回答一"),
                    msg(1, "问题二"),
                    msg(2, "回答二"));

            String transcript = builder.renderTranscript(null, messages);

            assertThat(transcript).contains("【新增对话】");
            assertThat(transcript.indexOf("问题一")).isLessThan(transcript.indexOf("回答一"));
            assertThat(transcript.indexOf("回答一")).isLessThan(transcript.indexOf("问题二"));
            assertThat(transcript).contains("用户：问题一").contains("助手：回答一");
        }

        @Test
        @DisplayName("★ 没有旧摘要时不出现「已有摘要」那一块（不是写「（无）」）")
        void noPreviousSummaryBlock() {
            String transcript = builder.renderTranscript(null, List.of(msg(1, "问题")));

            assertThat(transcript)
                    .as("★ 写「（无）」会把模型的注意力引向一个空的位置 —— "
                            + "同 RagPromptBuilder 对空资料的处理")
                    .doesNotContain("【已有摘要】")
                    .contains("【新增对话】");
        }

        @Test
        @DisplayName("★ 对照：有旧摘要时那一块必须在，且内容原样带过去")
        void previousSummaryIsCarried() {
            String transcript = builder.renderTranscript("用户预算两千左右", List.of(msg(1, "问题")));

            assertThat(transcript)
                    .contains("【已有摘要】")
                    .contains("用户预算两千左右");
            assertThat(transcript.indexOf("【已有摘要】"))
                    .as("★ 旧摘要在前、新对话在后 —— 反过来的话模型会以为"
                            + "「已有摘要」是对新增对话的总结")
                    .isLessThan(transcript.indexOf("【新增对话】"));
        }

        @Test
        @DisplayName("★ 空白摘要等同于没有（不能拼出一个空标题）")
        void blankPreviousSummaryIsIgnored() {
            assertThat(builder.renderTranscript("   \n  ", List.of(msg(1, "问题"))))
                    .doesNotContain("【已有摘要】");
        }

        @Test
        @DisplayName("★ 未知 role 标成「未知」，不静默当成助手")
        void unknownRoleIsNotSilentlyAssistant() {
            List<ChatMessage> messages = List.of(msg(3, "系统消息"), msg(1, "问题"));

            String transcript = builder.renderTranscript(null, messages);

            assertThat(transcript)
                    .as("★ 用三元式 role==1 ? 用户 : 助手 的话，一条 role=3 的消息会被"
                            + "标成「助手：系统消息」—— 那正是本节明令禁止的"
                            + "「把助手的话写成用户说的」的反向版本")
                    .contains("未知：系统消息")
                    .doesNotContain("助手：系统消息");
        }

        @Test
        @DisplayName("★ null role 也不抛异常")
        void nullRoleIsSafe() {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(msg(0, null));
            ChatMessage noRole = new ChatMessage();
            noRole.setContent("没有 role 的消息");
            messages.add(noRole);

            assertThat(builder.renderTranscript(null, messages))
                    .contains("未知：没有 role 的消息");
        }

        @Test
        @DisplayName("★ 超长消息被截断，并带上「已截断」标记")
        void overlongMessageIsTruncated() {
            String huge = "长".repeat(3000);

            String transcript = builder.renderTranscript(null, List.of(msg(1, huge)));

            assertThat(transcript)
                    .as("★ 同 ConversationMemory.MAX_CHARS_PER_TURN —— 这是防御，"
                            + "不是策略。正常数据里最长 352 字，永远轮不到它")
                    .contains("（已截断）");
            assertThat(transcript).hasSizeLessThan(2000);
        }

        @Test
        @DisplayName("★ 空消息列表不产生空白段落")
        void emptyMessagesIsExplicit() {
            assertThat(builder.renderTranscript(null, List.of()))
                    .as("★ 静默发一段空的素材出去，模型会【编一段摘要】出来 —— "
                            + "而那是纯粹的幻觉，还会被存进记忆里一直传下去")
                    .contains("没有新增对话");
        }
    }
}
