<script setup>
/**
 * 问答主页面 —— 8.2（气泡+打字机）/ 8.3（排队提示）/ 8.4（引用来源）
 * + 会话历史切换 + 技术细节面板。
 *
 * ## ★★★ 一个必须写在前面的坑：流式的 `done.answer` 恒为 `null`
 *
 * 服务端 `ChatServiceImpl.buildStreamResponse` 里那一行长这样：
 *
 * ```java
 *   return new ChatAskResponse(
 *       traceId,
 *       session.getSessionNo(),
 *       null,        // 流式的正文已经分批推走了，这里不再重复返回
 *       ...
 * ```
 *
 * ★ 所以**绝不能**写「收到 done 就 `content = payload.answer`」——
 * 那会在回答完成的那一刻把它整条清空。现象是「答案闪一下就没了」，
 * 而它看起来像渲染 bug，其实是**把一个字段的两种含义当成了同一种**。
 *
 * ```
 *   POST /api/chat（非流式）  answer = 正文
 *   POST /api/chat/stream     answer = null          ← 正文在 delta 里
 * ```
 *
 * ★ 这条已经在本项目咬过一次（`docs/08` ADR-080 记着：拿非流式的形状去断言
 * 流式，白红了一次）。所以这里正文**只有一个来源**：累加的 `delta`。
 *
 * ## ★ `references` / `intent` / `cost` 这些【只在 done 里有】
 *
 * delta 只带正文。引用、意图、成本、traceId 全部在 done 事件的
 * `ChatAskResponse` 里 —— 所以它们是「回答结束时一次性到位」，
 * 而不是边流边出现。这不是缺陷：那些值在生成结束前本来就不确定。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { getTrace, listMessages, listSessions, streamChat } from '../api.js'
import MessageBubble from '../components/MessageBubble.vue'
import QueueBanner from '../components/QueueBanner.vue'
import SessionList from '../components/SessionList.vue'

// ============================================================
// 会话列表
// ============================================================

const sessions = ref([])
const sessionsLoading = ref(false)
const activeSessionNo = ref(null)

/**
 * 消息列表。
 *
 * 每条的形状：
 * ```
 *   { role, content, intent, references, traceId, feedback, trace, traceState, traceError }
 * ```
 * ★ 故意用 `reactive` 数组 + 可变对象，而不是每次替换整个数组：
 *   流式期间我们要**按引用**改最后一条的 `content`，
 *   替换数组会让 Vue 重建那些节点，打字机效果会闪。
 *
 * ## ★★★ `traceId` 是「这次回答真的走完了」的唯一证据
 *
 * 它**只**在流式的 `done` 事件里出现。所以：
 *
 * ```
 *   有 traceId  → 引用可点开看原文 + 反馈可投（MessageBubble.canReact）
 *   没有        → 历史消息 / 失败的那次，两件事都做不了
 * ```
 *
 * ⚠️ **历史消息拿不到它**：`chat_message` 表里没有 `trace_id` 那一列
 * （阶段 8 拍板「技术面板只服务当前回答」的直接后果）。
 * ⇒ 从历史里翻出来的引用**点不开原文**，这是**已批准的边界**，
 * 界面上照实说，不是让用户点了没反应。
 *
 * ## ★ `feedback` 现在只活在这一个页面会话里
 *
 * 9.6b 的 `user_event` 表还没建，所以投票**没有落库** ——
 * 刷新页面 / 切走再回来就没了。这是**如实的当前状态**，不是 bug。
 * ⚠️ 所以刻意**不写**「感谢反馈，我们会改进」那种文案 ——
 * 那会描述一个还不存在的行为（同「不知道的事不装作知道」）。
 */
const messages = reactive([])

async function refreshSessions() {
  sessionsLoading.value = true
  try {
    sessions.value = await listSessions(50)
  } catch (e) {
    ElMessage.error(`会话列表加载失败：${e.message}`)
  } finally {
    sessionsLoading.value = false
  }
}

async function openSession(sessionNo) {
  if (streaming.value) {
    ElMessage.warning('正在回答中，等它结束再切会话')
    return
  }
  activeSessionNo.value = sessionNo
  messages.splice(0, messages.length)
  try {
    const rows = await listMessages(sessionNo)
    for (const m of rows) {
      messages.push({
        role: m.role,
        content: m.content,
        intent: m.intent,
        references: m.references,
        // ★ 历史消息【没有】技术细节 —— chat_message 里没有 trace_id，
        //   关联不回 qa_log。这是你在阶段 8 拍板「技术面板只服务当前回答」
        //   的直接后果，所以这里 traceState 保持 'idle'：面板根本不出现。
        //   ★ 不摆一个空的「延迟：—」让人以为是「这次没耗时」。
        trace: null,
        traceState: 'idle',
        traceError: '',
        // ★★ 同一个原因 ⇒ 同一个 null：历史消息里的引用【点不开原文】、
        //    也给不出反馈，两者判据都是它。见文件头那张形状表
        traceId: null,
        feedback: null,
      })
    }
  } catch (e) {
    ElMessage.error(`消息加载失败：${e.message}`)
  }
}

function newSession() {
  if (streaming.value) {
    ElMessage.warning('正在回答中，等它结束再开新对话')
    return
  }
  activeSessionNo.value = null
  messages.splice(0, messages.length)
}

// ============================================================
// 提问
// ============================================================

const question = ref('')
const streaming = ref(false)

/**
 * 身份。★ 工具题（我的订单 / 我的券 / 库存）**必须有它**，
 * 否则后端会回一句诚实的「没有身份，查不了」。
 *
 * ⚠️ 它**不是认证** —— 服务端那个头是明文未签名的，谁都能填别人的 id。
 * 它做到的只是「让身份不进模型的可控范围」（ADR-054）。
 * 界面上也不必假装它是个登录 —— 所以这里就是一个直白的输入框。
 */
const userId = ref('8')

const queue = reactive({
  position: null,
  waitedMs: 0,
  queuedAhead: null,
  admitted: false,
})

function resetQueue() {
  queue.position = null
  queue.waitedMs = 0
  queue.queuedAhead = null
  queue.admitted = false
}

/** 当前是否有排队/回答在进行 —— 用来禁用输入和切换 */
const busy = computed(() => streaming.value)

async function send() {
  const q = question.value.trim()
  if (!q || busy.value) return

  question.value = ''
  resetQueue()
  streaming.value = true

  messages.push({ role: 'user', content: q, intent: null, references: null,
    trace: null, traceState: 'idle', traceError: '', traceId: null, feedback: null })

  // ★ 助手气泡先建出来（内容是空的），流式的 delta 往里累加。
  //   用可变对象而不是每次 push 新的 —— 见上面 messages 的注释
  const reply = reactive({
    role: 'assistant',
    content: '',
    intent: null,
    references: null,
    trace: null,
    traceState: 'idle',
    traceError: '',
    // ★ traceId 要等 done 事件才有（delta 只带正文）——
    //   在那之前是 null，所以「回答进行中」时引用和反馈都不可用。
    //   这是对的：那时回答还没定型，没有东西可以评价。
    traceId: null,
    feedback: null,
  })
  messages.push(reply)
  scrollToBottom()

  try {
    await streamChat(
      { question: q, sessionNo: activeSessionNo.value, userId: userId.value },
      {
        onQueued: (p) => {
          // ★ p.position 直接用 —— 服务端说的是「前面还有几个人」，
          //   这里 **+1 就是错的**。见 QueueBanner 的注释
          queue.position = p.position
          queue.waitedMs = p.waitedMs
        },
        onAdmitted: (p) => {
          queue.admitted = true
          queue.queuedAhead = p.queuedAhead
        },
        onMeta: (p) => {
          // ★ 会话号在这里才拿得到（新会话时后端才生成）。
          //   记下来，这样下一轮提问才会落在同一个会话里 ——
          //   忘了这一步的症状是「每问一句都开一个新会话」，
          //   而会话记忆（5.5/5.6）就完全看不出来了
          if (p.sessionNo) activeSessionNo.value = p.sessionNo
        },
        onDelta: (text) => {
          reply.content += text
          scrollToBottom()
        },
        onDone: (payload) => {
          // ★★★ 这里【不要】写 reply.content = payload.answer ——
          //   流式路径下那个字段恒为 null（见文件头的说明）。
          //   写了的话，每一条回答都会在完成的那一刻整条消失。
          //
          //   ★ 那 done 里的正文答案在哪？**没有**，也不需要：
          //     上面 onDelta 累加出来的就是正文，而且是权威版本
          //     （服务端落库用的 fullAnswer 也是同一个缓冲区拼出来的）。
          reply.intent = payload.intent
          reply.references = payload.references
          // ★★ 记下 traceId —— 它是「这次回答真的走完了」的唯一证据，
          //    引用能否点开原文、能否投反馈，都判它。
          //    ⚠️ 不能放到 `if (payload.traceId)` 里面去：那样 traceId 为空的
          //    那次，气泡会一直停在「没有 traceId」的形状上，
          //    而它其实【是】答完了 —— 只是没有链路 id 可关联。
          //    这两件事的界面表达不同（一个有反馈按钮、一个没有），
          //    所以要如实记下「拿到了什么」，而不是只记「有没有拿到」。
          reply.traceId = payload.traceId ?? null
          // ★ 回答结束才去取技术细节。qa_log 的落库在 done 之前，
          //   所以这一步不会落空 —— 但仍要能容忍它失败（见下面 catch）
          if (payload.traceId) loadTrace(reply, payload.traceId)
        },
        onFailed: (payload) => {
          reply.content = reply.content || `回答失败：${payload.message || '未知原因'}`
          ElMessage.error(payload.message || '回答失败')
        },
      },
    )
  } catch (e) {
    // ★ 走到这里通常是「请求根本没发出去」：后端 4xx/5xx、或者网络断了。
    //   把它落在这条气泡上，而不是只弹一个 toast ——
    //   toast 几秒就没了，而对话里的失败应该留在对话里
    reply.content = reply.content || `请求失败：${e.message}`
    ElMessage.error(e.message)
  } finally {
    streaming.value = false
    resetQueue()
    scrollToBottom()
    // ★ 刷新列表：新会话要出现，标题和 messageCount 也要更新
    refreshSessions()
  }
}

/**
 * 取这次回答的技术细节。
 *
 * ★ 失败**不弹错误框**：它是增强信息，不是回答的一部分。
 * 弹框会让人以为「回答出问题了」，而回答其实是好的。
 * 所以失败只写进面板自己那一行。
 */
async function loadTrace(reply, traceId) {
  reply.traceState = 'loading'
  try {
    reply.trace = await getTrace(traceId)
    reply.traceState = 'ok'
  } catch (e) {
    reply.traceState = 'missing'
    reply.traceError = `技术细节暂不可得：${e.message}`
  }
}

// ============================================================
// 滚动
// ============================================================

const scroller = ref(null)

/**
 * 滚到底。
 *
 * ★ 用 `nextTick` 之后的 `scrollTop = scrollHeight`，而不是 `scrollIntoView`
 * —— 后者会把**整页**滚动到那个元素，在有固定头部时会顶偏。
 *
 * ⚠️ 这里没做「用户往上翻时不打断」的智能判断。那需要监听 scroll 算
 * 「离底部多远」，属于体验优化；演示场景里流式很快，
 * 强行跟随反而更简单可预期。
 */
function scrollToBottom() {
  requestAnimationFrame(() => {
    const el = scroller.value?.wrapRef
    if (el) el.scrollTop = el.scrollHeight
  })
}

/** 回车发送，Shift+Enter 换行 */
function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    // ★ isComposing：中文输入法选词时的回车【不能】当成发送。
    //   不判这个的话，用拼音打字的用户会在选词时被误发出去半句话 ——
    //   而那看起来像「输入法坏了」
    e.preventDefault()
    send()
  }
}

onMounted(refreshSessions)
</script>

<template>
  <div class="chat">
    <aside class="side">
      <SessionList
        :sessions="sessions"
        :active-session-no="activeSessionNo"
        :loading="sessionsLoading"
        @select="openSession"
        @refresh="refreshSessions"
        @create="newSession"
      />
    </aside>

    <section class="main">
      <el-scrollbar ref="scroller" class="messages">
        <div class="messages-inner">
          <el-empty
            v-if="!messages.length"
            description="问点什么吧 —— 试试「退货要几天」或者「我的券怎么用」"
          />

          <MessageBubble
            v-for="(m, i) in messages"
            :key="i"
            :role="m.role"
            :content="m.content"
            :streaming="streaming && i === messages.length - 1"
            :references="m.references"
            :intent="m.intent"
            :trace="m.trace"
            :trace-state="m.traceState"
            :trace-error="m.traceError"
            :trace-id="m.traceId"
            :feedback="m.feedback"
            @feedback="(kind) => (m.feedback = kind)"
          />
        </div>
      </el-scrollbar>

      <div class="composer">
        <QueueBanner
          :position="queue.position"
          :waited-ms="queue.waitedMs"
          :queued-ahead="queue.queuedAhead"
          :admitted="queue.admitted"
        />

        <div class="composer-row">
          <el-input
            v-model="question"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="问点什么…（Enter 发送 / Shift+Enter 换行）"
            :disabled="busy"
            @keydown="onKeydown"
          />
          <el-button
            type="primary"
            :loading="busy"
            :disabled="!question.trim()"
            class="send"
            @click="send"
          >
            发送
          </el-button>
        </div>

        <div class="composer-foot">
          <!--
            ★ 身份输入框做得显眼，因为不填它的话三个工具意图
              （我的订单 / 我的券 / 库存）会答「查不了」——
              而那看起来像模型不行，其实是没给身份。
          -->
          <span class="foot-label">身份 ID</span>
          <el-input
            v-model="userId"
            size="small"
            class="uid"
            placeholder="留空 = 匿名"
            :disabled="busy"
          />
          <span class="foot-hint">
            工具类问题（我的订单 / 我的券）需要它。⚠️ 不是认证，只是演示用
          </span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  flex: 1;
  min-height: 0;
}

.side {
  width: 240px;
  flex: none;
  min-height: 0;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  /* ★ 同上：flex 子项要 min-height: 0，否则内部滚动条不出来 */
}

.messages {
  flex: 1;
  min-height: 0;
}

.messages-inner {
  padding: 20px 24px;
  max-width: 900px;
  margin: 0 auto;
}

.composer {
  flex: none;
  border-top: 1px solid #e4e7ed;
  background: #fff;
  padding: 12px 24px 16px;
}

.composer-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.send {
  height: 54px;
  flex: none;
}

.composer-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.foot-label {
  font-size: 12px;
  color: #909399;
}

.uid {
  width: 120px;
}

.foot-hint {
  font-size: 11px;
  color: #c0c4cc;
}
</style>
