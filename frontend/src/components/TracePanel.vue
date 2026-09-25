<script setup>
/**
 * 技术细节面板 —— 阶段 8 新增，也是这个项目**最该被看见的一屏**。
 *
 * ## ★★ 它回答的是「这句话是怎么来的」
 *
 * ```
 *   意图          识别成什么 → 决定了检索范围
 *   检索范围      doc_types 下推了没有 / 还是没下推（以及为什么）
 *   五段延迟      时间花在哪
 *   供应商/成本   有没有降级、这一次花了多少钱
 *   工具调用      MCP 那条路走了没有
 * ```
 *
 * ★ 没有它，演示时这些数字只能靠我口头讲 —— 而**讲出来的数字是没法被检验的**。
 *
 * ## ★★★ 五段延迟这一块是【最容易画错】的地方
 *
 * 那两个结构性事实（`docs/08` ADR-083）：
 *
 * ```
 *   ① rerank ⊂ retrieval  —— 包含关系，不是并列
 *   ② 「未归类」是减出来的（total − queue − retrieval − llm），
 *      里面主要是意图分类那一次模型往返
 * ```
 *
 * ★ 所以这里**不能**画成一个「重排、检索、生成各占一段」的堆叠条 ——
 * 那等于把 `rerank` 从 `retrieval` 里拆出来当兄弟，两段加起来会重复计算。
 *
 * 画法：
 * ```
 *   [queue][retrieval][llm][未归类]        ← 四段，加起来 = 100%
 *             └ 其中重排 380ms             ← 重排【标在检索里面】，用注解而不是独立一段
 * ```
 *
 * ⚠️ 而且**只有在四段都拿得到时才画条**。算不出「未归类」时画一个残缺的条，
 * 会让人以为剩下的那一块是「没有耗时」—— 那就又变成「读不到 = 没有做过」了。
 */
import { computed } from 'vue'

const props = defineProps({
  /** `/api/chat/trace/{traceId}` 的返回，或 null（还没加载 / 加载失败） */
  trace: { type: Object, default: null },
  /** 加载状态：`'idle' | 'loading' | 'ok' | 'missing'` */
  state: { type: String, default: 'idle' },
  /** 加载失败时给一句人话（比如 404 时是「细节暂不可得」） */
  error: { type: String, default: '' },
})

/** `qa_log.status` 的四个取值。★ 和 `QaLog.STATUS_*` 常量一一对应 */
const STATUS_TEXT = {
  1: '成功',
  2: '模型链路失败',
  3: '澄清反问',
  4: '被限流拒',
}

const statusText = computed(() =>
  props.trace?.status == null ? '—' : (STATUS_TEXT[props.trace.status] ?? `未知(${props.trace.status})`))

/**
 * 五段（实际画四段）。
 *
 * ★ 返回 null 表示**画不了** —— 那时界面上给一句「算不出」，而不是一个残条。
 */
const latencySegments = computed(() => {
  const l = props.trace?.latency
  if (!l || l.totalMs == null || l.totalMs <= 0 || l.unclassifiedMs == null) return null

  const total = l.totalMs
  // ★ queue / retrieval 为 null 表示「这一格没有发生」（限流关着、或这次没检索），
  //   在总量里就是 0 —— 服务端算 unclassified 时也是这么处理的
  const parts = [
    { key: 'queue', label: '排队', ms: l.queueMs ?? 0, color: '#909399' },
    { key: 'retrieval', label: '检索', ms: l.retrievalMs ?? 0, color: '#409eff' },
    { key: 'llm', label: '生成', ms: l.llmMs ?? 0, color: '#67c23a' },
    { key: 'unclassified', label: '未归类', ms: l.unclassifiedMs, color: '#e6a23c' },
  ]

  return parts
    .filter((p) => p.ms > 0)
    .map((p) => ({ ...p, pct: Math.round((p.ms / total) * 1000) / 10 }))
})

/** doc_types 的渲染 —— ★ 空数组的含义是「不限制」，绝不能说成「什么都不匹配」 */
const docTypesText = computed(() => {
  const r = props.trace?.retrieval
  if (!r || r.docTypes == null) return '—'
  if (r.docTypes.length === 0) return '[ ] 不限制'
  return `[${r.docTypes.join(', ')}]`
})

/** 检索那一段的结论 —— 「声明非空 + applied=false → 去看 reason」 */
const scopeText = computed(() => {
  const r = props.trace?.retrieval
  if (!r) return null
  if (r.filterApplied === null) return '读不到（这一行的 retrieval_detail 里没有 filter 段）'
  if (r.filterApplied) return `已下推（池子 ${r.poolSize ?? '—'} 条候选）`
  return `未下推 · 原因 ${r.filterReason ?? '—'}`
})

const fmtMs = (v) => (v == null ? '—' : `${v} ms`)
const fmtCost = (v) => (v == null ? '—' : `¥${Number(v).toFixed(6)}`)
</script>

<template>
  <div class="trace">
    <!-- 还没加载完 / 加载失败 —— 两种【不同】的状态，文案也不同 -->
    <div v-if="state === 'loading'" class="hint">正在读取技术细节…</div>

    <div v-else-if="state === 'missing'" class="hint warn">
      {{ error || '这次的技术细节暂不可得' }}
      <!--
        ★ 说「暂不可得」而不是「没有技术细节」：
          前者是「我们没读到」，后者是「这次什么都没发生」。
          qa_log 的落库在 done 事件【之前】，所以正常情况下不该发生 ——
          真发生了说明写入那一步出了问题，那句话要如实指向这个方向。
      -->
    </div>

    <template v-else-if="trace">
      <!-- 一、意图与检索范围 -->
      <div class="grid">
        <div class="k">意图</div>
        <div class="v">
          <code>{{ trace.intent || '—' }}</code>
          <span v-if="trace.intentConfidence != null" class="dim">
            置信度 {{ Number(trace.intentConfidence).toFixed(2) }}
          </span>
        </div>

        <div class="k">状态</div>
        <div class="v">{{ statusText }}</div>

        <div class="k">检索范围</div>
        <div class="v">
          <code>{{ docTypesText }}</code>
          <div v-if="scopeText" class="dim">{{ scopeText }}</div>
        </div>

        <template v-if="trace.retrieval">
          <div class="k">召回条数</div>
          <div class="v mono">
            <!--
              ★ 五个数可能全是 null（老数据没有 sizes 段）——
                那时显示「—」而不是 0。0 的意思是「一条都没召回」，是另一件事。
            -->
            向量 {{ trace.retrieval.vectorHits ?? '—' }} ·
            关键词 {{ trace.retrieval.keywordHits ?? '—' }} ·
            融合 {{ trace.retrieval.fused ?? '—' }} ·
            重排后 {{ trace.retrieval.reranked ?? '—' }} ·
            进 Prompt {{ trace.retrieval.finalTopK ?? '—' }}
          </div>
        </template>

        <div class="k">供应商</div>
        <div class="v">
          <code>{{ trace.provider || '—' }}</code>
          <span v-if="trace.model" class="dim">{{ trace.model }}</span>
          <el-tag v-if="trace.degraded" size="small" type="warning" class="ml">
            发生过降级
          </el-tag>
        </div>

        <div class="k">成本</div>
        <div class="v mono">
          {{ fmtCost(trace.cost) }}
          <span class="dim">
            token 入 {{ trace.promptTokens ?? '—' }} / 出 {{ trace.completionTokens ?? '—' }}
            / 合计 {{ trace.totalTokens ?? '—' }}
          </span>
        </div>
      </div>

      <!-- 二、五段延迟 -->
      <div v-if="trace.latency" class="latency">
        <div class="section-title">
          延迟
          <span class="dim">总计 {{ fmtMs(trace.latency.totalMs) }}</span>
        </div>

        <template v-if="latencySegments">
          <div class="bar">
            <div
              v-for="seg in latencySegments"
              :key="seg.key"
              class="seg"
              :style="{ width: seg.pct + '%', background: seg.color }"
              :title="`${seg.label} ${seg.ms}ms`"
            />
          </div>
          <div class="legend">
            <span v-for="seg in latencySegments" :key="seg.key" class="legend-item">
              <i :style="{ background: seg.color }" />
              {{ seg.label }} {{ seg.ms }}ms
            </span>
          </div>
          <div class="note">
            <!--
              ★★ 重排标在【检索里面】而不是独立一段 —— 它是包含关系。
                画成独立一段会让四段之和大于总计，而那个数字看起来还挺合理。
            -->
            <template v-if="trace.latency.rerankMs != null">
              · 「检索」里含重排 {{ trace.latency.rerankMs }}ms（重排 ⊂ 检索，不并列）
            </template>
            <br />
            · 「未归类」= 总计 − 排队 − 检索 − 生成，主要是<b>意图分类</b>那一次模型往返
          </div>
        </template>

        <div v-else class="hint warn">
          五段算不出来（总计或生成的耗时没记），只列原始值：
          排队 {{ fmtMs(trace.latency.queueMs) }} ·
          检索 {{ fmtMs(trace.latency.retrievalMs) }} ·
          重排 {{ fmtMs(trace.latency.rerankMs) }} ·
          生成 {{ fmtMs(trace.latency.llmMs) }}
          <!--
            ★ 不画残缺的条：那会让人以为剩下那块「没有耗时」，
              而真相是「我们算不出来」。同「读不到 ≠ 没有做过」。
          -->
        </div>
      </div>

      <!-- 三、工具调用 -->
      <div v-if="trace.toolCalls && trace.toolCalls.length" class="tools">
        <div class="section-title">工具调用 ({{ trace.toolCalls.length }})</div>
        <div v-for="(c, i) in trace.toolCalls" :key="i" class="tool">
          <code>{{ c.name }}</code>
          <el-tag v-if="c.isError" size="small" type="danger">isError</el-tag>
          <span v-if="c.round != null" class="dim">第 {{ c.round }} 跳</span>
        </div>
      </div>

      <!-- 四、降级轨迹 -->
      <div v-if="trace.degradationEvents && trace.degradationEvents.length" class="degrade">
        <div class="section-title">降级轨迹 ({{ trace.degradationEvents.length }})</div>
        <div v-for="(d, i) in trace.degradationEvents" :key="i" class="degrade-item">
          <code>{{ d.from }}</code> → <code>{{ d.to }}</code>
          <span class="dim">{{ d.reason }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.trace {
  margin-top: 10px;
  padding: 10px 12px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  font-size: 12px;
}

.hint {
  color: #909399;
}

.hint.warn {
  color: #e6a23c;
}

.grid {
  display: grid;
  grid-template-columns: 76px 1fr;
  gap: 6px 10px;
}

.k {
  color: #909399;
}

.v {
  color: #303133;
  word-break: break-all;
}

.dim {
  color: #909399;
  margin-left: 6px;
  font-size: 11px;
}

.ml {
  margin-left: 6px;
}

.latency,
.tools,
.degrade {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #e4e7ed;
}

.section-title {
  font-weight: 600;
  color: #606266;
  margin-bottom: 8px;
}

.bar {
  display: flex;
  height: 16px;
  border-radius: 3px;
  overflow: hidden;
  background: #ebeef5;
}

.seg {
  height: 100%;
  /* ★ 不加 min-width：段太小就让它消失，也别撑出一个假的宽度。
     具体数值在下面的图例里，那里一定写着。 */
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 6px;
}

.legend-item {
  color: #606266;
  display: inline-flex;
  align-items: center;
}

.legend-item i {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-right: 4px;
}

.note {
  margin-top: 6px;
  color: #909399;
  font-size: 11px;
  line-height: 1.6;
}

.tool,
.degrade-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
  color: #606266;
}
</style>
