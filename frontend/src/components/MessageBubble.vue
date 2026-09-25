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
})

const isUser = computed(() => props.role === 'user')
const isClarify = computed(() => props.intent === 'NEEDS_CLARIFICATION')

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

      <ReferenceList v-if="!isUser" :references="references" />

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
