package com.xbla.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话相关的配置，对应 {@code application.yml} 里的 {@code xbla.chat}。
 *
 * <p>注册方式：启动类上的 {@code @ConfigurationPropertiesScan} 会自动扫描
 * {@code com.xbla.rag} 包下所有带 {@code @ConfigurationProperties} 的类，
 * 不需要在这里写 {@code @Component}。
 *
 * <h2>★ 为什么新开一个前缀，而不是塞进 {@code xbla.agent}</h2>
 *
 * <p>{@link AgentProperties} 现有的两个字段（意图树路径、意图识别）都围绕
 * <b>「这句话属于哪一类」</b>。而会话记忆回答的是另一个问题 ——
 * <b>「这句话是在什么背景下说的」</b>。两者共享「输入侧」这个位置，
 * 但没有任何一个参数同时影响它们。
 *
 * <p>还有一个更实际的理由：5.6 的摘要压缩果然往这里加了一批配置
 * （触发阈值、长度预算、生成用的 max-tokens）。那些参数属于会话生命周期，
 * 和意图路由无关 —— 5.6 落地时加了 {@link #summary} 一整块，
 * 而没有在最忙的那个配置类里再塞一层。
 *
 * <h2>和 {@code xbla.rag} 的分工</h2>
 *
 * <p>{@code xbla.rag} 管的是「怎么把资料找出来」（召回条数、RRF 权重、重排），
 * {@code xbla.chat} 管的是「怎么把这轮对话放回上下文里」。
 * 检索的输入是<b>问题</b>，会话记忆的输入是<b>会话</b>。
 */
@Data
@ConfigurationProperties(prefix = "xbla.chat")
public class ChatProperties {

    private History history = new History();

    private Summary summary = new Summary();

    /**
     * 滑动窗口记忆（阶段 5.5）。
     */
    @Data
    public static class History {

        /**
         * 总开关。
         *
         * <p><b>默认开</b>，但留这个开关是为了<b>阶段 7 的 A/B</b>：
         * 「带上历史对最终答案质量到底有没有提升」这个问题，
         * 只有拿「开」和「关」两组同样的题跑一遍才能回答。
         * 同 {@code xbla.agent.intent.enabled}、
         * {@code xbla.rag.rewrite.enabled} 的先例。
         *
         * <p>⚠️ 关掉时 <b>什么都不记也不读</b> —— 不是「读了不拼」。
         * 否则阶段 7 分不清「没开记忆」和「开了但历史是空的」，
         * 而这是两个不同的实验条件。（同 ADR-010「拿不到就记 NULL」的原则。）
         */
        private boolean enabled = true;

        /**
         * 窗口大小，单位<b>轮</b>（一问一答算一轮）。
         *
         * <h4>为什么按轮数，而不是按 token 数</h4>
         *
         * <p>实测本项目真实数据的长度：
         * <pre>
         *   用户消息  平均  9 字（最长 25）
         *   助手消息  平均 105 字（最长 352）
         * </pre>
         * 所以 10 轮历史 ≈ <b>1200 字</b>，而 {@code max_tokens} 是 2048 ——
         * <b>这个规模下 token 上限根本碰不到</b>。
         *
         * <p>为碰不到的上限引入一个 token 估算器不划算，而且<b>中文的 token
         * 估算本身就不准</b>：估出来的值一旦被当阈值用，它就会开始"咬人"，
         * 而咬的方式是「历史偶尔少一轮」这种查不出来的现象。
         *
         * <p>★ 5.6 上了摘要压缩之后**仍然没有引入 token 估算器** ——
         * 走到最后还是「按轮数滑窗 + 摘要兜远处」。摘要有它自己的
         * 长度预算（{@link Summary#getMaxChars()}），和窗口的轮数预算是两件事。
         *
         * <h4>为什么 10 这个数</h4>
         *
         * <p>阶段 5 的验收标准第 3 条是「连续 <b>10 轮</b>对话后，
         * 早期提到的商品偏好仍被记住」。窗口取 10 让那条标准
         * <b>字面对应一个可解释的数</b>，而不是「大概够吧」。
         *
         * <p>它是个<b>初始假设，不是调优结果</b> —— 真正的结论要等
         * 阶段 7 的「多轮追问」题集出来才作数。
         */
        private int maxTurns = 10;
    }

    /**
     * 会话摘要压缩（阶段 5.6）。
     *
     * <h2>它补的是滑动窗口的哪个洞</h2>
     *
     * <p>滑动窗口只保留最近 {@code max-turns} 轮，<b>更早的会被静默丢掉</b>。
     * 用户聊到第 11 轮时，第 1 轮说的预算就再也看不见了 ——
     * 而第 1 轮那句话恰恰是整场对话里信息密度最高的一条。
     *
     * <p>摘要层把「掉出窗口的原文」压成一段话接着用，于是：
     *
     * <pre>
     *   送给模型的上下文 = 【摘要（更早的对话）】 + 【最近 N 轮原文】
     *                       └─ 有损，但不会断 ─┘   └─ 无损，覆盖最近的细节 ─┘
     * </pre>
     *
     * <p>这正是 V5 建 {@code chat_summary} 表时写的「双层记忆策略」：
     * 只留摘要会丢细节，只留原文会撑爆 token，两层是精度和成本的折中。
     */
    @Data
    public static class Summary {

        /**
         * 总开关。
         *
         * <p>同 {@link History#isEnabled()}：留它是为了阶段 7 的 A/B
         * （「摘要层对答案质量到底有没有提升」）。
         * ⚠️ 关掉时<b>不生成也不读</b>。
         */
        private boolean enabled = true;

        /**
         * 触发阈值：掉出窗口的消息攒到这么多条，才生成一次摘要。
         *
         * <h4>为什么默认是 2（也就是「溢出就压」）</h4>
         *
         * <p>因为阈值直接决定了「摘要层有没有用」。
         * 设成 {@code 2 × max-turns}（= 一个窗口那么多）看起来很省，
         * 但后果是：
         *
         * <pre>
         *   第 11 轮  →  第 1 轮掉出窗口，【摘要还没生成】
         *   第 11–20 轮 → 第 1 轮彻底看不见
         *   第 20 轮  →  终于攒够 20 条，压出摘要，第 1 轮回来了
         * </pre>
         *
         * <p>而本项目真实会话最长就 10 轮。也就是说<b>大阈值下摘要层
         * 几乎永远不会生效</b> —— 它会是一个跑通了但没用的功能。
         *
         * <p>设成 2（一对问答）的代价是：从第 11 轮起，<b>每轮多一次 LLM 调用</b>
         * （后台异步，用户不等）。但那段区间本来就只有长会话才到得了，
         * 而长会话恰恰是唯一需要摘要的场景。
         *
         * <h4>失调的信号</h4>
         *
         * <p>调大它不会让摘要变差，只会让「摘要覆盖到哪」落后更多 ——
         * 表现是「明明聊了 30 轮，模型还是想不起第 1 轮说的预算」。
         */
        private int triggerMessages = 2;

        /**
         * 摘要正文的目标长度（字），写进 prompt 里当指令。
         *
         * <p>不是硬上限 —— 硬上限是它的两倍（见 {@code SessionSummarizer}）。
         * 真的超了会截断 + 打 WARN，因为「摘要长到失控」比「摘要被截掉一点」
         * 更危险：前者会无限膨胀，而它每轮都要进 prompt。
         */
        private int maxChars = 800;

        /**
         * 生成摘要请求的 max_tokens。<b>留空 = 用全局默认值（2048）。</b>
         *
         * <p>⚠️ 同 {@code xbla.agent.intent.max-tokens}：<b>绝不能给小。</b>
         * 降级链的 P0 是 {@code deepseek-flash} 推理模型（实测推理 token
         * 占输出 87%），额度给小了推理会把额度吃光、{@code content} 返回空串，
         * 表现为「摘要永远生成失败」而日志里没有任何异常。
         *
         * <p>摘要比分类更要注意这一点：分类输出几个 token，
         * 而摘要要输出几百字 —— 留给正文的额度更少。
         */
        private Integer maxTokens;

        /**
         * 生成摘要请求的 temperature。
         *
         * <p>同分类设 0.0：摘要是「压缩已有文本」，不是创作。
         * 但注意它<b>不等于确定性</b>（见 {@code xbla.agent.intent.temperature}
         * 那段实测）—— 同一段对话压两次结果会有措辞差异，这是正常的。
         */
        private Double temperature = 0.0;
    }
}
