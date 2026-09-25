<script setup>
/**
 * 引用来源展示（8.4）。
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
 */
defineProps({
  /**
   * `[{no, chunk_id, document_id, score, heading_path}]`
   * ★ 两种来源形状一致：流式的 `done` 事件、以及历史接口解析后的 JSON。
   */
  references: { type: Array, default: null },
})

/** 相似度显示成 3 位小数 —— 库里存的就是这个精度，多显示是假精确 */
const fmtScore = (v) =>
  typeof v === 'number' ? v.toFixed(3) : '—'
</script>

<template>
  <div v-if="references && references.length" class="refs">
    <div class="refs-head">
      引用资料 <span class="count">({{ references.length }})</span>
    </div>
    <div v-for="r in references" :key="r.chunk_id ?? r.no" class="ref">
      <span class="no">[{{ r.no }}]</span>
      <span class="heading">{{ r.heading_path || '(无标题路径)' }}</span>
      <span class="score" :title="`重排分数 ${r.score}`">{{ fmtScore(r.score) }}</span>
      <span class="ids">切片 {{ r.chunk_id }} · 文档 {{ r.document_id }}</span>
    </div>
  </div>
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

.ref {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  color: #606266;
  padding: 2px 0;
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
</style>
