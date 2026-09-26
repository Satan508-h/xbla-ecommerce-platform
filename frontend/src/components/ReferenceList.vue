<script setup>
/**
 * 引用来源展示（8.4）+ 点开看原文（9.6b 前置）。
 *
 * ## ★ 它证明的是「这句话是从知识库里来的」
 *
 * 一个只显示回答的聊天框，和一个编造回答的聊天框，**长得一模一样**。
 * 引用列表是能区分它们的最便宜的证据：回答正文里的 `[1]` 对应这里的第 1 条。
 *
 * ## ★★ 「没有引用」和「没检索」在界面上是两件事
 *
 * ```
 *   references 为 null  → 「这次回答没有引用资料」
 *                         可能是：这次没检索（工具/澄清），或者检索了但零召回
 *   列表非空            → 列出来
 * ```
 *
 * ★ 这里**不**去猜是哪一种，因为前端拿不到那个信息 ——
 * 要区分它们得看 `trace` 里的 `retrieval`（有没有发生）。
 * 技术细节面板里有；这个组件里只如实说「没有引用」。
 * **不知道的事不装作知道。**
 *
 * ## ★★★ 卡片【可不可点】是一张形状表，不是一句「它总是可点」
 *
 * ```
 *   有 traceId（刚答完的这次回答） → <button>，点了弹原文
 *   没有 traceId（历史消息）       → <span>，不可点，且【明说为什么】
 * ```
 *
 * 为什么历史消息拿不到 traceId：`chat_message` 表里**没有**那一列
 * （阶段 8 拍板「技术面板只服务当前回答」的直接后果，见 `ChatMessageView`）。
 * ⇒ 那里的引用**构造不出**「取原文」那个请求 —— 而这是**已批准的边界**。
 *
 * ★ 关键是**不可点的时候要说出来**。照「不知道的事不装作知道」那条纪律：
 * 一个可点但点了没反应的卡片，比一个明确写着「这里点不开」的卡片坏得多 ——
 * 前者会被读成 bug，而后者是一个事实。
 *
 * ## ★ 为什么是 `<button>` 而不是 `<div @click>`
 *
 * 键盘 Tab 能聚焦、回车能触发、`role` 天然正确。这三件事都是浏览器
 * 免费给的，而写成 `div` 就全丢了 —— 代价是零，收益是「它真的是个按钮」。
 * 同理**不用 `<a href>`**：这里没有地址可跳，`href="#"` 会污染历史栈。
 */
import { ref } from 'vue'
import { getReferenceDetail } from '../api.js'
import { track } from '../track.js'

const props = defineProps({
  /**
   * `[{no, chunk_id, document_id, score, heading_path}]`
   * ★ 两种来源形状一致：流式的 `done` 事件、以及历史接口解析后的 JSON。
   */
  references: { type: Array, default: null },

  /**
   * 这次回答的链路 id。**只有它存在时引用才可点。**
   * `null` = 历史消息 —— 那时连请求都构造不出来，见文件头那张形状表。
   */
  traceId: { type: String, default: null },
})

/** 相似度显示成 3 位小数 —— 库里存的就是这个精度，多显示是假精确 */
const fmtScore = (v) =>
  typeof v === 'number' ? v.toFixed(3) : '—'

/** 无 traceId 时整列都不可点，所以判一次就够 —— 不逐条判 */
const clickable = () => !!props.traceId

// ============================================================
// 原文弹窗
// ============================================================

const dialog = ref(false)

/**
 * 弹窗状态。
 * ★ `state` 是**三态**，不是「加载中 / 加载完」两态：
 * ```
 *   loading → 正在取
 *   ok      → 拿到了
 *   error   → 如实把服务端那句话显示出来
 * ```
 * ⚠️ 失败时**不留一个空正文框** —— 那会被读成「这条切片是空的」，
 * 而「取不到」和「取到的是空」是两件事（同 `TrackPanel` 的降级纪律）。
 */
const detail = ref({ state: 'loading', data: null, error: '', ref: null })

/** 弹窗标题用的引用序号 —— 点了哪条就显示哪条的 `[n]` */
const current = ref(null)

async function open(r) {
  if (!clickable()) return

  current.value = r
  detail.value = { state: 'loading', data: null, error: '', ref: r }
  dialog.value = true

  // ★★ 先埋点、再请求。
  //
  //   反过来（拿到正文才埋）的话，量到的就不是「用户点了」，
  //   而是「用户点了**并且**服务端成功返回了」—— 后者不是行为，是结果，
  //   而两者在网络抖动时会差很多。点击这个动作发生在请求【之前】，
  //   埋点就该在请求之前。
  track('ref_click', { traceId: props.traceId, chunkId: r.chunk_id, no: r.no })

  try {
    const data = await getReferenceDetail(props.traceId, r.chunk_id)
    // ★ 竞态：用户点完这条又点了另一条时，先返回的响应不能覆盖后点的。
    //   判据是「弹窗里现在显示的还是不是我这次要的那条」。
    //   ⚠️ 不判的话，症状是「偶尔显示了上一条的原文」—— 极难复现。
    if (detail.value.ref !== r) return
    detail.value = { state: 'ok', data, error: '', ref: r }
  } catch (e) {
    if (detail.value.ref !== r) return
    detail.value = { state: 'error', data: null, error: e.message, ref: r }
  }
}
</script>

<template>
  <div v-if="references && references.length" class="refs">
    <div class="refs-head">
      引用资料 <span class="count">({{ references.length }})</span>
      <!--
        ★ 不可点的时候【明说】。一个可点但点了没反应的卡片会被读成 bug；
          「这里点不开」是一个事实。
      -->
      <span v-if="!clickable()" class="hint">· 历史消息取不到原文</span>
      <span v-else class="hint">· 点一条看原文</span>
    </div>

    <component
      :is="clickable() ? 'button' : 'span'"
      v-for="r in references"
      :key="r.chunk_id ?? r.no"
      class="ref"
      :class="{ 'ref-clickable': clickable() }"
      :type="clickable() ? 'button' : undefined"
      :title="clickable() ? '点开看这条引用的原文' : '历史消息：这次回答的链路信息没有存下来'"
      @click="open(r)"
    >
      <span class="no">[{{ r.no }}]</span>
      <span class="heading">{{ r.heading_path || '(无标题路径)' }}</span>
      <span class="score" :title="`重排分数 ${r.score}`">{{ fmtScore(r.score) }}</span>
      <span class="ids">切片 {{ r.chunk_id }} · 文档 {{ r.document_id }}</span>
    </component>
  </div>

  <!--
    ★ 弹窗放在 v-if 之外 —— 列表为空时也要能正常卸载它，
      写在里面的话关掉弹窗会连组件一起拆掉（当前回答被清空时）。
  -->
  <el-dialog
    v-model="dialog"
    :title="current ? `引用 [${current.no}] 原文` : '引用原文'"
    width="640px"
    append-to-body
  >
    <div v-if="detail.state === 'loading'" class="dlg-loading">正在取原文…</div>

    <div v-else-if="detail.state === 'error'" class="dlg-error">
      {{ detail.error }}
    </div>

    <template v-else>
      <div class="dlg-meta">
        <span class="k">标题路径</span>
        <span class="v">{{ detail.data.headingPath || '(无标题路径)' }}</span>
      </div>
      <div class="dlg-meta">
        <span class="k">切片 / 文档</span>
        <span class="v">
          {{ detail.data.chunkId }} / {{ detail.data.documentId ?? '—' }}
        </span>
      </div>
      <!--
        ★ 正文用 pre-wrap：切片里的换行是语料的一部分，折叠掉就读不通了。
          同 MessageBubble 正文那条注释。
      -->
      <div class="dlg-content">{{ detail.data.content }}</div>
    </template>

    <template #footer>
      <span class="dlg-foot">
        {{ detail.state === 'ok' ? `${detail.data.content.length} 字` : '' }}
      </span>
      <el-button @click="dialog = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.refs {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #dcdfe6;
}

.refs-head {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.count {
  color: #c0c4cc;
}

.hint {
  color: #c0c4cc;
  font-size: 11px;
}

/* ★ 默认（不可点）的形态 —— 和 8.4 时逐字一致，历史消息不该看起来变样了 */
.ref {
  display: flex;
  align-items: baseline;
  gap: 8px;
  width: 100%;
  font-size: 12px;
  color: #606266;
  padding: 2px 4px;
  text-align: left;
  /* button 复位：不加的话可点的那些会带上浏览器默认的边框和灰底 */
  background: none;
  border: none;
  border-radius: 4px;
  font-family: inherit;
}

.ref-clickable {
  cursor: pointer;
}

.ref-clickable:hover {
  background: #f5f7fa;
}

.ref-clickable:focus-visible {
  outline: 2px solid #409eff;
  outline-offset: -2px;
}

.no {
  color: #409eff;
  font-weight: 600;
  flex: none;
}

.heading {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.score {
  flex: none;
  color: #67c23a;
  font-variant-numeric: tabular-nums;
}

.ids {
  flex: none;
  color: #c0c4cc;
  font-size: 11px;
}

/* ---- 弹窗 ---- */

.dlg-loading {
  color: #909399;
  font-size: 13px;
}

.dlg-error {
  color: #f56c6c;
  font-size: 13px;
}

.dlg-meta {
  display: flex;
  gap: 8px;
  font-size: 12px;
  margin-bottom: 4px;
}

.dlg-meta .k {
  flex: none;
  width: 72px;
  color: #909399;
}

.dlg-meta .v {
  color: #606266;
  word-break: break-all;
}

.dlg-content {
  margin-top: 10px;
  padding: 10px 12px;
  max-height: 46vh;
  overflow: auto;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 13px;
  line-height: 1.8;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}

.dlg-foot {
  float: left;
  color: #c0c4cc;
  font-size: 12px;
  line-height: 32px;
}
</style>
