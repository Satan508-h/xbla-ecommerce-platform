package com.xbla.rag.agent.memory;

import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.config.ChatProperties;
import com.xbla.rag.entity.ChatMessage;
import com.xbla.rag.entity.ChatSummary;
import com.xbla.rag.service.ChatMessageService;
import com.xbla.rag.service.ChatSummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 会话摘要压缩（阶段 5.6）—— 把掉出滑动窗口的对话压成一段摘要，存进 {@code chat_summary}。
 *
 * <h2>一、★ 核心不变式：摘要和窗口严丝合缝，一起覆盖整场会话</h2>
 *
 * <pre>
 *   ├──────────── 摘要 ────────────┼──────── 窗口原文 ────────┤
 *   msg 1 ................. msg N  │  msg N+1 ......... msg M
 *                                  ↑
 *                       chat_summary.end_message_id == N
 * </pre>
 *
 * <p>{@code end_message_id} 是一个<b>游标</b>，不是「摘要写了多少」。
 * 它唯一的工作是回答：<b>「摘要覆盖到哪一条为止」</b>。
 * 于是「还有没有没压的东西」变成了一个纯比较：
 *
 * <pre>
 *   窗口起点 W  >  end_message_id + 1   →  中间那段没人管，该压了
 *   窗口起点 W  == end_message_id + 1   →  严丝合缝，什么都不用做
 * </pre>
 *
 * <h2>二、★ 为什么压缩的终点取「窗口起点 - 1」，而不是「溢出多少条」</h2>
 *
 * <p>这是个关键选择，两者看着差不多，出错的方式完全不同。
 *
 * <p>如果按「溢出几条就压几条」算，压缩的终点和窗口的起点<b>各走各的</b> ——
 * 只要两者步调差一点，接缝处就会出现<b>空洞</b>：那几条消息既掉出了窗口、
 * 又不在任何一次压缩的区间里，于是<b>永远消失</b>，而且没有任何报错。
 *
 * <p>取「窗口起点 - 1」则把终点<b>锚死在窗口上</b>，接缝由构造保证。
 * 代价是每次要压的区间<b>长度不固定</b>（取决于距上次压缩过了几轮），
 * 所以有这个类里唯一的那个循环保障：一次读不完就分多次，
 * 每次把游标往前推到读到的最后一条。
 *
 * <h2>三、★ 异步，因为用户不该为「维护未来的上下文」买单</h2>
 *
 * <p>压缩要花一次 LLM 调用（1–2 秒）。同步做的话，每 N 轮就有一次问答
 * 的耗时凭空翻倍，而用户会以为卡了。
 *
 * <p><b>异步是安全的，因为输入是不可变的过去。</b>
 * 待压缩的消息早已落库、内容不会变；窗口那边读的是另一段区间。
 * 唯一的竞态是「同一会话的两次压缩同时跑」，而那是被
 * {@code memoryExecutor} 的<b>单线程</b>挡住的 —— 见 {@code AsyncConfig}。
 *
 * <p><b>而且压缩是自愈的</b>：这次没跑成（被拒绝、模型挂了、进程被杀），
 * 游标停在原地，下次触发会把这一段<b>连同新掉出来的一起</b>压进去。
 * 这就是 {@link #maybeSummarizeAsync} 敢于「失败就算了」的底气 ——
 * 也是它和 {@code KbIngestService} 那种「非幂等、宁可慢不可丢」的本质区别。
 *
 * <h2>四、永不抛异常，永不阻塞调用方</h2>
 *
 * <p>{@link #maybeSummarizeAsync} 只做一次提交就返回，队列满时<b>静默放弃</b>
 * （打 INFO，因为这不值得惊动人）。所有异常都吞在这个类里 ——
 * 摘要失败该退化成「记忆里少一块」，不该让一次成功的问答变成 500。
 * 同 {@code ConversationMemory} 第四节、{@code RetrievalPipeline} 的失败语义。
 */
@Component
public class SessionSummarizer {

    private static final Logger log = LoggerFactory.getLogger(SessionSummarizer.class);

    /** {@code chat_message.role} 的取值 */
    private static final int ROLE_USER = 1;
    private static final int ROLE_ASSISTANT = 2;

    /** {@code chat_summary.summary_level}：1 = 段落级摘要。2 是预留的死列，见类注释 */
    private static final short LEVEL_SECTION = 1;

    /**
     * 单次压缩最多读多少条消息。
     *
     * <p>防御性上限，<b>正常情况下永远轮不到</b>：{@code trigger-messages} 是 2，
     * 每轮压一次，一次要压的区间就是 2 条左右。
     *
     * <p>它拦的是另一件事：摘要功能上线前就存在的长会话。那种会话的游标是空的，
     * 「未覆盖区间」等于<b>整个会话的历史</b>。没有这个上限就会把几百条消息
     * 一次性塞进 prompt —— 不报错，只是贵得离谱且大概率超出上下文窗口。
     *
     * <p>★ 截取的是<b>最旧</b>的那一批（SQL 里正序 + LIMIT），因为游标是从旧往新推的。
     * 剩下的下一轮接着压。
     */
    private static final int MAX_MESSAGES_PER_RUN = 100;

    /** 摘要正文的硬上限 = 目标上限的这么多倍，超了就截断（见 {@link #normalizeSummary}） */
    private static final double HARD_LIMIT_FACTOR = 2.0;

    private static final String TRUNCATED_SUFFIX = "…（摘要过长已截断）";

    private final ChatMessageService chatMessageService;
    private final ChatSummaryService chatSummaryService;
    private final ConversationMemory conversationMemory;
    private final ChatModelRouter router;
    private final ChatProperties properties;
    private final SummaryPromptBuilder promptBuilder;
    private final ThreadPoolTaskExecutor executor;

    public SessionSummarizer(ChatMessageService chatMessageService,
                             ChatSummaryService chatSummaryService,
                             ConversationMemory conversationMemory,
                             ChatModelRouter router,
                             ChatProperties properties,
                             SummaryPromptBuilder promptBuilder,
                             @Qualifier("memoryExecutor") ThreadPoolTaskExecutor executor) {
        this.chatMessageService = chatMessageService;
        this.chatSummaryService = chatSummaryService;
        this.conversationMemory = conversationMemory;
        this.router = router;
        this.properties = properties;
        this.promptBuilder = promptBuilder;
        this.executor = executor;
    }

    // ============================================================
    // 入口
    // ============================================================

    /**
     * 交给后台线程去检查并（必要时）压缩。<b>立即返回，永不抛异常。</b>
     *
     * <p>调用点在「生成已经完成、响应即将返回」之后 ——
     * 那时本轮的助手消息已经落库，窗口的形态已经定型。
     *
     * <p>⚠️ 提交失败（队列满、线程池正在关闭）时<b>什么都不做</b>，
     * 只打一行 INFO。因为下一个掉出窗口的轮次会重新触发，
     * 而窗口起点只会往前走，迟到的压缩区间只会更大，不会漏。
     *
     * @param sessionId 会话主键。为 null 时直接返回
     */
    public void maybeSummarizeAsync(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        if (!properties.getSummary().isEnabled()) {
            return;
        }
        try {
            executor.execute(() -> summarize(sessionId));
        } catch (TaskRejectedException e) {
            // ★ 不是错误：队列满说明此刻太忙，而这件事可以推迟。
            //   和 ingestExecutor 的 CallerRuns 相反 —— 摘要不紧急，不值得阻塞任何人
            log.info("摘要任务被线程池拒绝，本次跳过（游标不动，下次连这段一起压）sessionId={}",
                    sessionId);
        } catch (Exception e) {
            // 线程池正在关闭时会抛别的。同样不该影响调用方
            log.debug("摘要任务提交失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 同步执行一次压缩检查。<b>永不抛异常。</b>
     *
     * <p>包成包内可见是为了让测试能绕开线程池直接调 ——
     * 异步入口在测试里没法断言「跑完了没有」。
     */
    void summarize(Long sessionId) {
        // ★ 开关必须在这里也判一次，不能只放在 maybeSummarizeAsync。
        //   那边判的是「要不要提交任务」（省一次线程池往返），
        //   这里判的才是「要不要花钱」—— 真正的闸门应该装在花钱的地方。
        //   同一个开关判断两次不算冗余：它拦的是两条不同的路径，
        //   而且这条路径有测试直接调（异步入口断言不了「跑完了没有」）。
        if (sessionId == null || !properties.getSummary().isEnabled()) {
            return;
        }
        try {
            doSummarize(sessionId);
        } catch (Exception e) {
            // ★ 保留旧摘要，游标不动。下次触发会把这一段一起压进去
            log.warn("会话摘要生成失败，保留旧摘要并停止推进游标 sessionId={}: {}",
                    sessionId, e.getMessage(), e);
        }
    }

    // ============================================================
    // 主流程
    // ============================================================

    private void doSummarize(Long sessionId) {
        int windowMessages = properties.getHistory().getMaxTurns() * 2;
        if (windowMessages <= 0) {
            // 窗口关掉了（max-turns <= 0）。这种情况 ConversationMemory 也不读历史，
            // 那摘要就没有消费方 —— 不生成
            return;
        }

        // ── ① 窗口的起点（接缝锚点）──
        //
        //   ★★ 从 ConversationMemory 拿，不在本类里再算一遍。
        //      那是「接缝在哪里」这个定义的【单一出处】：摘要层拿它决定游标推到哪、
        //      窗口层拿它做越界检查 —— 两边各写一份「碰巧一样」的表达式，
        //      正是会有消息永久消失（既不在摘要里也不在窗口里）的那种耦合。
        Long windowStartId = conversationMemory.windowStartId(sessionId);
        if (windowStartId == null) {
            return;     // 窗口还没满 = 没有任何东西掉出来 = 无可压缩
        }

        // ── ② 摘要已经覆盖到哪 ──
        ChatSummary current = loadCurrent(sessionId);
        Long coveredEnd = current == null ? null : current.getEndMessageId();
        if (coveredEnd != null && coveredEnd >= windowStartId - 1) {
            // 严丝合缝（==），或者摘要比窗口还新（>，只可能发生在 max-turns 被调小时）。
            // 两种都不需要做事
            return;
        }

        // ── ③ 读未覆盖区间 (coveredEnd, windowStartId) ──
        List<ChatMessage> uncovered = loadUncovered(sessionId, coveredEnd, windowStartId);
        int trigger = properties.getSummary().getTriggerMessages();
        if (uncovered.size() < trigger) {
            return;
        }

        // ── ④ 调模型 ──
        String summary = generate(current, uncovered);
        if (summary == null) {
            return;     // 已经打过日志了
        }

        // ── ⑤ 推进游标 ──
        Long newEnd = uncovered.get(uncovered.size() - 1).getId();
        Long newStart = current == null
                ? uncovered.get(0).getId()
                : current.getStartMessageId();
        boolean written = write(sessionId, current, newStart, newEnd, summary);

        if (written) {
            log.info("会话摘要已更新 sessionId={} 游标 [{},{}] 本次压了 {} 条 → {} 字",
                    sessionId, newStart, newEnd, uncovered.size(), summary.length());
        }
    }

    // ============================================================
    // 取数
    // ============================================================

    /**
     * 当前摘要行。
     *
     * <p>理论上每个会话只有一行（单行滚动，{@link #write} 用 CAS 保证），
     * 但表上<b>没有 UNIQUE 约束</b>（见 V5 迁移），所以这里按游标倒序取一行 ——
     * 万一真出现了第二行，取最靠前的那条是唯一安全的选择。
     */
    private ChatSummary loadCurrent(Long sessionId) {
        return chatSummaryService.lambdaQuery()
                .eq(ChatSummary::getSessionId, sessionId)
                .eq(ChatSummary::getSummaryLevel, LEVEL_SECTION)
                .orderByDesc(ChatSummary::getEndMessageId)
                .last("LIMIT 1")
                .one();
    }

    /**
     * 读未覆盖区间的消息，正序，最多 {@link #MAX_MESSAGES_PER_RUN} 条。
     *
     * @param coveredEnd 已覆盖到的位置。为 null 表示从会话开头开始
     */
    private List<ChatMessage> loadUncovered(Long sessionId, Long coveredEnd, Long windowStartId) {
        return chatMessageService.lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .in(ChatMessage::getRole, ROLE_USER, ROLE_ASSISTANT)
                .gt(coveredEnd != null, ChatMessage::getId, coveredEnd)
                .lt(ChatMessage::getId, windowStartId)
                .orderByAsc(ChatMessage::getId)
                .last("LIMIT " + MAX_MESSAGES_PER_RUN)
                .list();
    }

    // ============================================================
    // 生成
    // ============================================================

    /**
     * 调模型生成摘要。
     *
     * @return 摘要正文；失败时返回 {@code null}（<b>已经记过日志</b>）
     */
    private String generate(ChatSummary current, List<ChatMessage> uncovered) {
        ChatProperties.Summary config = properties.getSummary();

        String previous = current == null ? null : current.getSummary();
        // ★ 超预算时换成「强制压缩」模式 —— 光在静态规则里写字数上限没用，
        //   实测模型会一路追加到撞硬上限，而硬上限是【从尾部】切的，
        //   切掉的正好是它刚追加的最新内容。见 SummaryPromptBuilder
        boolean overBudget = previous != null && previous.length() > config.getMaxChars();

        ChatRequest request = new ChatRequest(
                promptBuilder.systemPrompt(config.getMaxChars(), overBudget),
                List.of(),                                        // 不是多轮对话，见 SummaryPromptBuilder 第一节
                promptBuilder.renderTranscript(previous, uncovered),
                config.getMaxTokens(),
                config.getTemperature(),
                null);  // 摘要不打工具

        ModelCallTrace trace = new ModelCallTrace("summary-" + shortId());
        ChatResponse response;
        try {
            // ★ 走完整降级链，不是指定某一档。同意图分类的先例：
            //   摘要的可用性和问答同档。代价是默认落在推理模型上（慢、贵）
            response = router.chat(request, trace);
        } catch (Exception e) {
            log.warn("摘要模型调用失败：{}", e.getMessage());
            return null;
        }

        // ★★ 推理模型把 max-tokens 吃光的探测器。CLAUDE.md 里记着的那个坑：
        //    HTTP 200、content 是空串、日志里没有任何异常。
        //    摘要比分类更危险 —— 分类失败我们判成「未分类」，而摘要是空串
        //    会覆盖掉一份【本来好用的旧摘要】
        if (response.isEmptyContent()) {
            log.warn("★ 摘要拿到了空正文 —— 多半是 max-tokens 被推理吃光。"
                            + "检查 xbla.chat.summary.max-tokens 是否为 null（用全局 2048）。{}",
                    response.describe());
            return null;
        }

        String summary = normalizeSummary(response.content(), config.getMaxChars());
        if (summary == null) {
            return null;
        }

        log.debug("摘要生成完成 {} | cost={} | 输入 {} 条消息",
                response.describe(), trace.cost(), uncovered.size());
        return summary;
    }

    /**
     * 洗一遍模型输出，并施加硬上限。
     *
     * <p>两件事：
     * <ol>
     *   <li>去掉首尾空白。prompt 里明说了不要前缀，但模型偶尔还是会加
     *       「以下是摘要：」—— 只做 strip 不做更激进的清洗，
     *       因为任何「去掉第一行」的规则都可能切掉一条事实</li>
     *   <li>★ 超过 {@code maxChars × 2} 就截断。{@code maxChars} 本身
     *       是 prompt 里的<b>目标</b>，不是硬约束；而摘要的失控增长必须有个界 ——
     *       它每一轮都要进 prompt，一段无限膨胀的摘要是慢性的 token 泄漏</li>
     * </ol>
     *
     * <h4>★★ 截断是从【尾部】切的，这是有意的</h4>
     *
     * <p>摘要是按时间顺序写的（最早的对话在前），所以切掉尾部 =
     * <b>丢掉最新的那一段</b>。看起来反直觉，但它的收益方向是对的：
     *
     * <pre>
     *   开头：「用户预算两千左右，用途是和孙子视频通话」  ← 再检索一次也查不到
     *   结尾：「助手解释了退货流程的三个步骤」            ← 下次检索还能查到
     * </pre>
     *
     * <p>越靠后的内容越可能是「助手的回答」，而那是<b>可再生的</b>；
     * 越靠前的内容越可能是「用户说过的自己」，那是<b>不可再生的</b>。
     * 切尾部正好丢的是可再生的那一端。
     *
     * <p>⚠️ 截断本身仍然是个失败信号 —— 它意味着上面的强制压缩没管住，
     * 所以这里打 WARN。正常运行时不应该出现。
     *
     * @return 处理后的摘要；空串时返回 {@code null}
     */
    private static String normalizeSummary(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }

        int hardLimit = (int) (maxChars * HARD_LIMIT_FACTOR);
        if (trimmed.length() <= hardLimit) {
            // 超过目标但没超硬上限：接受，但记一笔 —— 它是「prompt 该调了」的信号
            if (trimmed.length() > maxChars) {
                log.debug("摘要长度 {} 字，超过了目标 {} 字（未超硬上限 {}，接受）",
                        trimmed.length(), maxChars, hardLimit);
            }
            return trimmed;
        }

        log.warn("★ 摘要长度 {} 字超过了硬上限 {} 字，已截断 —— "
                        + "检查 xbla.chat.summary.max-chars 是否太小，或模型是否没按指令压缩",
                trimmed.length(), hardLimit);
        return trimmed.substring(0, hardLimit) + TRUNCATED_SUFFIX;
    }

    // ============================================================
    // 落库
    // ============================================================

    /**
     * 推进游标。<b>用 CAS，不是无条件覆盖。</b>
     *
     * <p>单线程池已经保证了同一进程内不会并发，那 CAS 是防什么的？
     * 两个理由，都值得留着：
     * <ol>
     *   <li><b>它是一句断言</b>：{@code end_message_id} 必须还是我读到的那个值。
     *       如果 CAS 失败，说明「读摘要」和「写摘要」之间有人动过它 ——
     *       那意味着这个类的核心不变式被破坏了，必须留下痕迹而不是静默覆盖</li>
     *   <li>将来线程池从单线程放宽、或者起了第二个实例，它立刻就是必需的</li>
     * </ol>
     *
     * <p>⚠️ {@code token_count} 故意<b>留 NULL</b>，不走 {@code usage.completionTokens()}。
     * 那一列的含义是「这段摘要占多少 prompt 预算」，而 completionTokens
     * <b>包含推理 token</b>（实测占输出 87%）—— 存进去会让它系统性偏大，
     * 变成一个「有值但用途错」的列，比 NULL 更危险。同 ADR-010 原则。
     * 这次调用的真实花费在 INFO 日志里。
     *
     * @return 是否成功写入
     */
    private boolean write(Long sessionId, ChatSummary current,
                          Long newStart, Long newEnd, String summary) {
        if (current == null) {
            ChatSummary row = new ChatSummary();
            row.setSessionId(sessionId);
            row.setSummaryLevel((int) LEVEL_SECTION);
            row.setStartMessageId(newStart);
            row.setEndMessageId(newEnd);
            row.setSummary(summary);
            row.setTokenCount(null);            // 见方法注释：刻意的
            chatSummaryService.save(row);
            return true;
        }

        boolean ok = chatSummaryService.lambdaUpdate()
                .eq(ChatSummary::getSessionId, sessionId)
                .eq(ChatSummary::getSummaryLevel, LEVEL_SECTION)
                .eq(ChatSummary::getEndMessageId, current.getEndMessageId())   // ★ CAS
                .set(ChatSummary::getStartMessageId, newStart)
                .set(ChatSummary::getEndMessageId, newEnd)
                .set(ChatSummary::getSummary, summary)
                .update();

        if (!ok) {
            // ★ 这不是「写失败」，是「游标被别人推进了」。两种含义完全不同 ——
            //   前者要重试，后者必须丢弃本次结果（否则会把游标拖回去）
            log.warn("摘要游标已被并发推进，丢弃本次结果（下次会基于新游标重算）"
                            + " sessionId={} 我读到的 end={} 想写到 end={}",
                    sessionId, current.getEndMessageId(), newEnd);
        }
        return ok;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
