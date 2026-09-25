package com.xbla.rag.agent.intent;

import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 判断一次问答要不要<b>走澄清反问</b>而不是照常检索（阶段 5.3）。
 *
 * <h2>一、★ 这个类为什么不叫「置信度判断」</h2>
 *
 * <p>路线图原本写的是「5.3 置信度计算 + 澄清反问」，隐含的假设是
 * <b>「分类置信度低 → 澄清」</b>。动工前的实测<b>推翻了这个假设</b>：
 *
 * <table border="1">
 *   <caption>三种置信度信号 vs 「该不该澄清」（2026-09-19 实测）</caption>
 *   <tr><th>信号</th><th>结果</th></tr>
 *   <tr><td>token logprobs（DeepSeek 支持）</td>
 *       <td>所有问题都是 <b>-0.000</b>，领先次候选 5~13 个 log 单位。
 *           <b>连那个已知会翻转的问题都报 -0.000</b> —— 没有区分度</td></tr>
 *   <tr><td>采样一致度（跑 8 次取众数）</td>
 *       <td>「那个怎么样」<b>8/8 = 1.00</b>（最该澄清的，零信号）；
 *           「退货要几天」<b>7/8 = 0.88</b>（完全正常的问题，反而更低）。
 *           <b>两个方向都反了</b></td></tr>
 *   <tr><td>模型自报 confidence</td>
 *       <td>不采信，理由见 {@code IntentClassification}</td></tr>
 * </table>
 *
 * <p><b>根因是这两个问题正交：</b>
 * <pre>
 *   信息完整但分类边界模糊（「退货要几天」）→ 分类器不确定，但【不该】澄清
 *   信息不足但分类器自信（「那个怎么样」）→ 分类器确定，但【该】澄清
 * </pre>
 *
 * <p>三种信号度量的都是<b>「分类有多确定」</b>，而这里要的是
 * <b>「这句话是否包含足够的信息来回答」</b>。所以本类判的是后者，
 * 依据是分类结果有没有落在 {@code role: CLARIFY} 的那个分支上 ——
 * 那是由分类 prompt 直接判出来的，不经过任何置信度中间量。
 *
 * <h2>二、★ 不做的事：不因为「分类失败」而澄清</h2>
 *
 * <p>分类失败（{@code CALL_FAILED} / {@code UNKNOWN_CODE}）时<b>不澄清</b>，
 * 照常检索（退化成阶段 4 的行为）。理由：
 * <ul>
 *   <li>「我分不清」和「你没说清」是两件事，前者不该拿去骚扰用户</li>
 *   <li>分类失败是<b>基础设施或 prompt</b> 的问题，把它暴露成「请再说清楚一点」
 *       会让排查方向彻底跑偏 —— 用户会以为是自己问得不好</li>
 *   <li>同 ADR-038：分类失败的合理兜底是退化成阶段 4 的行为，用户无感</li>
 * </ul>
 *
 * <h2>三、★ {@code history} 参数是个【放错了位置】的预留（2026-09-19 更正）</h2>
 *
 * <p>本类最初从 5.5 的角度预设了 {@code history}：把历史传进来，
 * 「那个怎么样」在有上文时（上一轮刚聊过星辰X1）<b>不该</b>澄清 ——
 * 「那个」是可以被消解的。
 *
 * <p><b>5.5 落地时发现这个预设是错的：本类消解不了指代。</b>
 * 它是一个纯判断器（输入分类结果，输出「澄清 / 不澄清」），
 * 而「那个」指什么<b>只有分类器能回答</b>。可行的链路是：
 *
 * <pre>
 *   历史里有「星辰X1 怎么样」
 *     ↓
 *   分类器看到历史 → 直接判成 SCENARIO_PICK
 *     ↓
 *   根本不是 CLARIFY 分支 → 本类压根不会被问到
 * </pre>
 *
 * <p>而把历史喂给分类器这条路，5.5 <b>明确没有走</b>
 * （用户拍板：历史只给生成）—— 理由是那会动到「分类器的输入只有这一句话」
 * 这个前提，而 5.2 的 95% 准确率和 5.4 的「20/20 可证明安全」
 * <b>都建立在那个前提上</b>，20 道题的评测集又测不出这个变化是好是坏。
 *
 * <p>所以现在的实情是：<b>这个参数会一直是空的</b>。
 * 保留三参数版本而不是删掉它，是因为删一个 public 方法属于另一个决定 ——
 * 但注释必须说实话，否则它就成了一个「看起来 5.5 会来接」的坑。
 *
 * <p>代价（明确记下来）：<b>「那个怎么样」在有上下文时仍然会触发澄清反问。</b>
 * 详见 {@code ConversationMemory} 类注释第一节。
 *
 * <h2>四、★★ 阶段 9.4 还的这笔债：多轮澄清</h2>
 *
 * <p>上面那条代价的<b>实测数字</b>在 {@code docs/05} §9.5 ②：
 * <b>最自然的追问方式有约 2/3 被澄清闸门挡掉</b> ——
 *
 * <pre>
 *   用户：那个怎么样  → 「信息不足」→ 反问「你是想问哪款商品呢？」
 *   用户：送长辈      → 分类器【只看到这三个字】→ 仍然是「信息不足」→ 再问一次
 * </pre>
 *
 * <p>9.4 的修法是给分类<b>补一小段上下文</b>（{@link PendingClarify}），
 * 而不是让它去读会话历史 —— 那两件事的边界见那个类的注释。
 *
 * <p>本类在其中的职责有两件（都在这一个方法里，因为它们是同一个判断的产物）：
 *
 * <ol>
 *   <li>★ <b>按缺的槽位选反问文案</b>：模型说缺 {@code budget}，就不再问「哪款商品」。
 *       文案在 {@code xbla.agent.slots.questions} 里配，缺了就回落到固定文案
 *       （{@code xbla.agent.intent.clarify-text}，即 9.3 那一句）。</li>
 *   <li>★ <b>产出下一轮要用的状态</b>（{@link Decision#pending()}）：
 *       用户原本问的是什么、缺哪几项、我们实际问了哪一项。
 *       ★ 它<b>只进分类 prompt</b>，不进生成、不进检索。</li>
 * </ol>
 *
 * <p>⚠️ 一个刻意保留的边界：本类<b>仍然不消解指代</b>。
 * 三参数 {@code decide(question, classification, history)} 那个历史参数
 * <b>依然是恒空的</b>（第 三 节的结论没变），
 * 9.4 补的上下文走的是分类器那一侧，不是这里。
 */
@Component
public class ClarificationDecider {

    private static final Logger log = LoggerFactory.getLogger(ClarificationDecider.class);

    private final IntentTree intentTree;
    private final AgentProperties properties;

    public ClarificationDecider(IntentTree intentTree, AgentProperties properties) {
        this.intentTree = intentTree;
        this.properties = properties;
    }

    /**
     * 一次澄清判断的结果。
     *
     * @param shouldClarify  true 时调用方应当<b>短路</b>：不检索、不调大模型，
     *                       直接把 {@code clarifyText} 回给用户
     * @param clarifyText    反问的话术。{@code shouldClarify} 为 false 时为 {@code null}
     * @param pending        ★ 阶段 9.4：<b>要记进 {@code chat_session.pending_clarify}
     *                       的状态</b>（「刚问了什么、缺哪一项」），供下一轮分类时
     *                       看得见。{@code null} = 不记（槽位功能关着，或者不澄清）。
     *                       <p>★ 它<b>由本类产出</b>而不是让调用方自己拼：槽位那一整套
     *                      判断（词表过滤、优先级、开关）都在本类里，
     *                       调用方只需要「拿到什么就存什么」
     * @param classification 原样带回来，方便调用方一次拿到全部信息
     */
    public record Decision(boolean shouldClarify, String clarifyText, PendingClarify pending,
                           IntentClassification classification) {

        static Decision proceed(IntentClassification classification) {
            return new Decision(false, null, null, classification);
        }

        /** 这一次实际问的是哪个槽位；{@code null} = 问的是固定文案里那几项 */
        public String askedSlot() {
            return pending == null ? null : pending.asked();
        }
    }

    /** 不带上下文的判断。等价于 {@code decide(question, classification, List.of())} */
    public Decision decide(String question, IntentClassification classification) {
        return decide(question, classification, List.of());
    }

    /**
     * 判断要不要澄清。
     *
     * @param question       用户原话。<b>现在没用到</b>（只用于日志）
     * @param classification 5.2 的分类结果。<b>为 {@code null} 表示「没做分类」</b>
     *                       （开关关着，或分类过程抛了未预期的异常）
     * @param history        对话历史。<b>恒为空</b> —— 见类注释第三节，
     *                       这个参数的位置是错的，5.5 没有用它消解指代
     */
    public Decision decide(String question, IntentClassification classification,
                           List<ChatRequest.Turn> history) {
        // ★ 没分类 / 分类失败，都不澄清。理由见类注释第二节 ——
        //   这是本类最容易写错的地方：「分类没成功」直觉上像是「系统不确定」，
        //   但把它变成反问用户，会把自己的故障伪装成用户的问题
        if (classification == null || !classification.isClassified()) {
            return Decision.proceed(classification);
        }

        boolean needsClarification = intentTree.get().clarify()
                .map(branch -> branch.code().equals(classification.code()))
                .orElse(false);

        if (!needsClarification) {
            return Decision.proceed(classification);
        }

        // ⚠️ 这里原来是「5.5 预留点：先用 history 消解指代」。
        //   5.5 落地时确认了本类消解不了指代（见类注释第三节），
        //   所以这个分支不会再去消解什么 —— 走到这里只可能说明
        //   有人从别的地方给本类传了历史，那是一个需要被看见的意外
        if (!history.isEmpty()) {
            log.warn("澄清判断收到了 {} 轮历史 —— 本类不该拿到历史（见类注释第三节），"
                    + "请检查调用方", history.size());
        }

        // ★★ 阶段 9.4：按【缺的槽位】选反问文案，并把状态交给调用方去记。
        //
        //   9.4 之前这里是一段【固定】文案（「你是想问哪款商品呢？…」），
        //   而模型报的可能是 budget —— 于是我们问了一个它没缺的东西。
        //   ★ 固定文案没有删：它是兜底（模型没给 missing、或给的槽位没模板时，
        //     它仍然是一句能用的反问，而且是 9.3 逐字节相同的那一句）。
        AgentProperties.Slots slots = properties.getSlots();
        List<String> missing = classification.plan() == null
                ? List.of() : classification.plan().missingSlots();
        String asked = slots.isEnabled() ? ClarifySlots.pickToAsk(missing) : null;
        String text = slots.isEnabled() ? slots.getQuestions().get(asked) : null;
        if (text == null) {
            // 走到这里有两种情况：开关关着，或者模型报的槽位我们没模板（含一个都没报）
            text = properties.getIntent().getClarifyText();
        }

        PendingClarify pending = slots.isEnabled()
                ? PendingClarify.of(question, missing, asked)
                : null;

        log.info("★ 需要澄清：问题「{}」被判为信息不足，不检索、不调模型{}",
                question, pending == null ? "" : "（缺 " + missing + "，问 " + asked + "）");
        return new Decision(true, text, pending, classification);
    }
}
