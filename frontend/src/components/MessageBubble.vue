<script setup>
/**
 * 一条消息气泡（8.2）。
 *
 * ## ★ 它要同时服务两种来源，而这两者的字段【不一样】
 *
 * ```
 *   实时流（done 事件）   ChatAskResponse：有 cost / llmLatencyMs / totalLatencyMs /
 *                        intent / references / provider，【没有】doc_types 和五段延迟
 *   历史消息（接口）       ChatMessageView：有 intent / references / provider / model /
 *                        latencyMs，【没有】cost 和五段延迟
 * ```
 *
 * ★ 所以这个组件**不假设哪些字段一定在**：每个可选块都判空，
 * 而拿不到的东西**不摆空位**。
 *
 * ★★ 这一条不是洁癖 —— 是你在阶段 8 拍板「技术面板只服务当前回答」的直接后果：
 * 历史消息确实拿不到五段延迟（`chat_message` 里没有 trace_id，
 * 关联不回 `qa_log`）。既然拿不到，界面上就不该出现一个空的「延迟：—」
 * 让人以为是「这次没耗时」。
 *
 * ## ★ 澄清反问要渲染成【追问】，不是【回答】
 *
 * `intent === 'NEEDS_CLARIFICATION'` 时这次**没有检索也没有调模型**
 * （`provider` / `cost` / `references` 全是 null，那是「没有发生」的诚实表达）。
 * 把它渲染成一条普通回答，用户会以为系统答了 —— 而它其实在问你要信息。
 * ★ `ChatAskResponse.intent` 的 javadoc 里写着「有它才能区分，
 * 否则只能靠比对文本 —— 那就是在猜」，这条就是它的用武之地。
 */
import { computed } from 'vue'
import ReferenceList from './ReferenceList.vue'
import TracePanel from './TracePanel.vue'
import { track } from '../track.js'

const props = defineProps({
  /** `'user'` / `'assistant'` —— 见 `ChatMessageView.role` 的说明（服务端已翻好） */
  role: { type: String, required: true },
  content: { type: String, default: '' },
  /** 是否正在流式输出（决定是否显示闪烁光标） */
  streaming: { type: Boolean, default: false },
  /** 引用来源 */
  references: { type: Array, default: null },
  /** 意图码 */
  intent: { type: String, default: null },
  /** 技术细节（只对「当前这次回答」有） */
  trace: { type: Object, default: null },
  traceState: { type: String, default: 'idle' },
  traceError: { type: String, default: '' },
  /**
   * ★ 这次回答的链路 id。**它同时是两件事的判据**（见下面 `canReact`）。
   * `null` = 历史消息（`chat_message` 里没有 trace_id 那一列）。
   */
  traceId: { type: String, default: null },
  /** 已投的票：`null` / `'up'` / `'down'` —— ★ 状态在 ChatView 那边（见下） */
  feedback: { type: String, default: null },
})

const emit = defineEmits(['feedback'])

const isUser = computed(() => props.role === 'user')
const isClarify = computed(() => props.intent === 'NEEDS_CLARIFICATION')

/**
 * ★★★ 引用可点 / 反馈可投 —— **这两个判据是同一个**
 *
 * ```
 *   有 traceId  → 这次回答真的走完了（onDone 带回来的），
 *                 所以：引用取得回原文、反馈有东西可关联
 *   没有        → 历史消息（或失败的那次），两件事都做不了
 * ```
 *
 * ★★ 为什么不写 `!isUser && !streaming` 之类的「看起来等价」的条件：
 * 那个条件在**失败**的回答上也成立（`onFailed` 之后 `streaming` 也是 false），
 * 于是会给一条「回答失败」的气泡配上两个反馈按钮 —— 而**没有回答可以评价**。
 *
 * ⇒ 判据只能是「这次回答是否真的产出了东西」，而 `traceId` 正是那个证据：
 * 它**只**在 `done` 事件里出现。用一个已经在手边的、有明确来源的事实，
 * 比拼一个看起来对的条件可靠。
 *
 * ⚠️ 而且这不是「顺手复用」：反馈要能关联回 `qa_log`，**本来就需要 traceId**。
 * 所以 UI 判据和埋点的必要条件恰好重合 —— 这是好事，不是巧合。
 */
const canReact = computed(() => !isUser.value && !!props.traceId)

function vote(kind) {
  // ★ 投过就不能再改 —— 幂等由这里保证，不是等 9.6b 建表时靠 event_no 去重。
  //   前端能挡住的不该留给后端兜。
  if (props.feedback) return

  emit('feedback', kind)
  track('feedback', { traceId: props.traceId, vote: kind })
}

/**
 * ★ 处理中（正文还是空的）—— 给一个「正在思考」的占位。
 *
 * ⚠️ 不显示省略号动画之外的东西：**尤其是不要显示「正在检索…」**。
 * 那时我们只知道请求发出去了，不知道它卡在哪一段
 * （排队？分类？检索？生成？）—— 猜一个环节就是在编。
 */
const pending = computed(() => !isUser.value && !props.content && props.streaming)

/** ★ 技术面板只给助手消息，而且只在真的拿到 trace 时展开 */
const showTrace = computed(() => !isUser.value && props.traceState !== 'idle')
</script>

<template>
  <div class="row" :class="isUser ? 'row-user' : 'row-assistant'">
    <div class="bubble" :class="{ 'bubble-user': isUser, 'bubble-clarify': isClarify }">
      <!-- 澄清标签：让「它在问你要信息」和「它答了」在视觉上分开 -->
      <div v-if="isClarify" class="clarify-tag">需要补充信息</div>

      <div v-if="pending" class="pending">
        <span class="dot" />
        正在处理…
      </div>

      <!--
        ★ 正文用 white-space: pre-wrap：模型返回的换行要保留，
          而 HTML 默认会把连续空白折叠掉 —— 症状是「回答挤成一坨」。
      -->
      <div v-else class="content">{{ content }}<span v-if="streaming" class="caret" /></div>

      <ReferenceList v-if="!isUser" :references="references" :trace-id="traceId" />

      <!--
        ★ 反馈放在【引用之后、技术面板之前】：它评的是这条回答，
          而引用是这条回答的依据 —— 用户读完依据再表态，顺序是对的。
      -->
      <div v-if="canReact" class="feedback">
        <template v-if="feedback">
          <!-- ★ 投过之后只剩一句话，不再摆两个禁用的按钮：
                 摆着会让人以为「还能改」，而它改不了。 -->
          <span class="voted">
            已反馈 {{ feedback === 'up' ? '👍' : '👎' }}
          </span>
        </template>
        <template v-else>
          <span class="fb-label">这条回答有帮助吗</span>
          <button class="fb" type="button" title="有帮助" aria-label="有帮助"
                  @click="vote('up')">👍</button>
          <button class="fb" type="button" title="没帮助" aria-label="没帮助"
                  @click="vote('down')">👎</button>
        </template>
      </div>

      <TracePanel
        v-if="showTrace"
        :trace="trace"
        :state="traceState"
        :error="traceError"
      />
    </div>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  margin-bottom: 14px;
}

.row-user {
  justify-content: flex-end;
}

.row-assistant {
  justify-content: flex-start;
}

.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #e4e7ed;
  font-size: 14px;
  line-height: 1.7;
}

.bubble-user {
  background: #409eff;
  border-color: #409eff;
  color: #fff;
}

.bubble-clarify {
  /* 澄清用不同底色：它不是一条回答 */
  background: #fdf6ec;
  border-color: #f5dab1;
}

.clarify-tag {
  font-size: 11px;
  color: #e6a23c;
  font-weight: 600;
  margin-bottom: 4px;
}

.content {
  white-space: pre-wrap;
  word-break: break-word;
}

/*
 * 打字机光标。
 * ★ 用 CSS 动画而不是「每 500ms 切换一次下划线字符」——
 *   后者会往正文里插字符，复制出来的文本就带下划线了。
 */
.caret {
  display: inline-block;
  width: 2px;
  height: 1em;
  background: currentColor;
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 1s steps(1) infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.pending {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #909399;
  font-size: 13px;
}

/* ---- 反馈 ---- */

.feedback {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #dcdfe6;
}

.fb-label {
  font-size: 12px;
  color: #c0c4cc;
  margin-right: 2px;
}

.fb {
  /* ★ 复位浏览器默认按钮样式 —— 不复位的话这两个会是系统灰按钮，
     和整站的 Element Plus 风格对不上 */
  background: none;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 1px 8px;
  font-size: 13px;
  line-height: 20px;
  cursor: pointer;
}

.fb:hover {
  border-color: #409eff;
  background: #f5f7fa;
}

.fb:focus-visible {
  outline: 2px solid #409eff;
  outline-offset: 1px;
}

.voted {
  font-size: 12px;
  color: #909399;
}

/* 一个呼吸的小圆点 —— 比转圈图标轻，也不需要引图标库 */
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #409eff;
  animation: breathe 1.2s ease-in-out infinite;
}

@keyframes breathe {
  0%,
  100% {
    opacity: 0.25;
    transform: scale(0.85);
  }
  50% {
    opacity: 1;
    transform: scale(1);
  }
}
</style>
