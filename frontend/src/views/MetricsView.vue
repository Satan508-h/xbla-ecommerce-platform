<script setup>
/**
 * 在线指标页（阶段 9.6b）—— `/api/status/metrics` 的可视化。
 *
 * ## ★★★ 这一页最难的地方不是画图，是【不让两组数被混着读】
 *
 * ```
 *   ① traffic / latency   来自 qa_log     —— 服务端一直在记，【有 8 天历史】
 *   ② behavior            来自 user_event —— 前端埋点，【从零开始】
 * ```
 *
 * ★ 两组画成两张卡片，**组② 顶上永远挂着那句话** ——
 * 不挂的话，一个「引用点击率 0.8%」会被读成「用户不太点」，
 * 而真相可能是「埋点是昨天才上的」。
 *
 * ## ★★ 三处「宁可不显示数字，也不显示一个假的数字」
 *
 * ```
 *   率的【分母为 0】     → 显示「—」，不显示 0%
 *                         那一刻我们【不知道】，因为没有东西可点
 *   延迟每一段都带 n     → n=0 时 p50 显示「—」
 *                         percentile_cont 跳过 NULL，不带 n 的话
 *                         「只排过 4 次队」会被读成「排队很快」
 *   byStatus 四格恒在    → 值为 0 也画出来
 *                         缺一格会被读成「不存在」，而它只是「没被走到」
 * ```
 *
 * ★ 这三条都不是设计洁癖 —— 它们各自对应本项目踩过的一次坑
 * （见 `docs/10` 与 `docs/04` 那条「要写形状表，不要写『它总是 X』」）。
 *
 * ## ★ `notes` 原样摆在最下面，不折叠
 *
 * 服务端把口径**跟着数字一起发**（ADR-083 那句、埋点没有历史那句）。
 * 折叠起来就等于没发 —— 而数字会被截图贴到别处去。
 *
 * ## ⚠️ 轮询 5 秒，比限流实况那页（3 秒）慢
 *
 * 因为代价不一样：限流那页读的是几个 `ZCARD`（O(1)），
 * 而这一页要跑几条 `GROUP BY` + `percentile_cont`（扫 `qa_log` 全窗口）。
 * 本机规模下是毫秒级，但它是**公网可访问**的端点 ——
 * 间隔按「代价」定，不按「看起来有多实时」定。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getMetrics } from '../api.js'

const POLL_MS = 5000

/**
 * ★ 默认窗口是 `all`，和端点的缺省（24h）**刻意不同**。
 *
 * 实测本机的真实流量摊在 8 天里（12/103/47/1/5/35/3）——
 * `24h` 打开常常是个位数行，而人会把它读成「这套东西没数据」。
 * 想看「最近」自己切到 24h。
 */
const window = ref('all')
const data = ref(null)
const error = ref('')
let timer = null

async function poll() {
  try {
    data.value = await getMetrics(window.value)
    error.value = ''
  } catch (e) {
    // ★ 失败时【不清空】上一次的数据，只在顶上显示一句 ——
    //   清空会让页面看起来像「指标停了」，而真相只是这一次请求失败了
    //   （同 StatusView 那条）
    error.value = e.message
  }
}

function restart() {
  if (timer) clearInterval(timer)
  poll()
  timer = setInterval(poll, POLL_MS)
}

onMounted(restart)
onBeforeUnmount(() => {
  // ★ 一定要清：组件卸载后还在轮询的话，用户切到别的页面仍然每 5 秒打一次后端
  if (timer) clearInterval(timer)
})

// ============================================================
// 格式化 —— ★ 三处「没有数就显示「—」」
// ============================================================

/** 毫秒。★ `null` 是「这一段没有样本」，不是 0 —— 显示「—」 */
const ms = (v) => (v === null || v === undefined ? '—' : `${Math.round(v)} ms`)

/** 率。★ `null` 是「分母为 0 ⇒ 不知道」，不是 0% */
const rate = (v) => (v === null || v === undefined ? '—' : `${(v * 100).toFixed(2)}%`)

/**
 * 延迟那一段的「可信度」提示。
 *
 * ★ 判据是**样本数**而不是分位数本身：`n=0` 时那个 p50 是「没有样本」，
 * 而它显示出来会是 `—`（上面已经处理）。这里管的是 `n` 很小但非 0 的情况 ——
 * 「4 次」算出来的 p50 没有代表性，而它**看起来和 4000 次的一模一样**。
 */
const thin = (n) => n > 0 && n < 10

/** ★ 时钟偏差超过 60 秒就标出来 —— 那说明客户端时间是坏的 */
const skewBad = (v) => v !== null && v !== undefined && Math.abs(v) > 60000

const STATUS_LABEL = {
  1: '成功生成',
  2: '模型链路失败',
  3: '澄清反问',
  4: '被限流',
}

/**
 * 事件类型的中文名。
 * ⚠️ 认不出的键**原样显示键名**，不吞掉 —— 那说明后端加了新事件类型，
 *    而它必须被看见（同一个道理：`docs/10` 那条「追加而不是丢弃」）。
 */
const EVENT_LABEL = {
  ref_click: '引用被点开',
  feedback: '反馈投票',
}

/**
 * ★ 延迟那五行是**从响应里组装出来的**，不是写死的表。
 *
 * ⚠️ 它必须留在 `<script setup>` 里（而不是另开一个 `export default { computed }`）——
 * 两个 script 块虽然都能用，但普通块里的 `this.data` **看不到** setup 里的
 * `data` 这个 ref，于是表永远是空的，而**控制台一个字都不会报**。
 */
const latencyRows = computed(() => {
  const l = data.value?.latency
  if (!l) return []
  return [
    { name: '排队', p50: l.queueP50Ms, p95: l.queueP95Ms, n: l.queueN, note: '限流没开就没有排队 ⇒ n 常常是 0' },
    { name: '检索', p50: l.retrievalP50Ms, p95: l.retrievalP95Ms, n: l.retrievalN, note: '工具轮 / 澄清轮不检索' },
    { name: '重排', p50: l.rerankP50Ms, p95: l.rerankP95Ms, n: l.rerankN, note: '★ 包含在「检索」之内，不是并列' },
    { name: '生成', p50: l.llmP50Ms, p95: l.llmP95Ms, n: l.llmN, note: '实测这一格从不缺样本' },
    { name: '总计', p50: l.totalP50Ms, p95: l.totalP95Ms, n: l.totalN, note: '它是分母，也是判断上面那些 n 够不够看的基准' },
  ]
})
</script>

<template>
  <el-scrollbar class="metrics">
    <div class="inner">
      <el-alert
        v-if="error"
        type="warning"
        :closable="false"
        show-icon
        class="mb"
        title="这一次刷新失败了（下面显示的是上一次的值）"
      >
        {{ error }}
      </el-alert>

      <el-skeleton v-if="!data && !error" :rows="6" animated />

      <template v-else-if="data">
        <!-- 窗口切换 -->
        <div class="toolbar">
          <el-radio-group v-model="window" size="small" @change="restart">
            <el-radio-button value="24h">最近 24 小时</el-radio-button>
            <el-radio-button value="7d">最近 7 天</el-radio-button>
            <el-radio-button value="all">全部历史</el-radio-button>
          </el-radio-group>
          <span class="stamp">
            快照于 {{ new Date(data.generatedAt).toLocaleTimeString() }}
            <template v-if="data.from === null">· 不设下界</template>
          </span>
        </div>

        <!-- ============================================================ -->
        <!-- 组①：问答本身（有历史）                                        -->
        <!-- ============================================================ -->
        <div class="group-head">
          <span class="group-title">一、问答本身</span>
          <span class="group-sub">来自 <code>qa_log</code> · 服务端一直在记，<b>有历史</b></span>
        </div>

        <div class="cards">
          <el-card shadow="never" class="card metric">
            <div class="num">{{ data.traffic.questions }}</div>
            <div class="lbl">提问数</div>
          </el-card>
          <el-card shadow="never" class="card metric">
            <div class="num">{{ data.traffic.sessions }}</div>
            <div class="lbl">活跃会话（去重）</div>
          </el-card>
        </div>

        <el-card shadow="never" class="card">
          <template #header><span class="card-title">按结果分（四格恒在，值为 0 也画）</span></template>
          <div class="cards">
            <div v-for="(cnt, key) in data.traffic.byStatus" :key="key" class="mini">
              <div class="num-sm">{{ cnt }}</div>
              <div class="lbl">{{ STATUS_LABEL[key] || `未知状态 ${key}` }}</div>
            </div>
          </div>
          <p class="foot">
            ★ 3 和 4 <b>不是失败</b>（澄清是我们主动追问，限流是我们自己挡掉的）——
            所以这里刻意不合成一个「成功率」，合成会让它随流量结构变化。
          </p>
        </el-card>

        <el-card shadow="never" class="card">
          <template #header>
            <span class="card-title">五段延迟</span>
            <span class="card-note">★ 只统计 status=1；每一段都带自己的 n</span>
          </template>
          <el-table :data="latencyRows" size="small" class="lat-table">
            <el-table-column prop="name" label="段" width="110" />
            <el-table-column label="p50" width="110">
              <template #default="{ row }">{{ ms(row.p50) }}</template>
            </el-table-column>
            <el-table-column label="p95" width="110">
              <template #default="{ row }">{{ ms(row.p95) }}</template>
            </el-table-column>
            <el-table-column label="n（真的发生了的行数）" min-width="180">
              <template #default="{ row }">
                <span :class="{ warn: thin(row.n) }">{{ row.n }}</span>
                <span v-if="thin(row.n)" class="thin-tip">样本太少，别下结论</span>
              </template>
            </el-table-column>
            <el-table-column prop="note" label="" min-width="180" />
          </el-table>
          <p class="foot">
            ⚠️ <b>五段不相加等于 total</b>：<code>rerank ⊂ retrieval</code> 是包含关系，
            而意图分类那一次模型往返进了 total 却不在任何一段里。
            <b>别把五行加起来对 total。</b>
          </p>
        </el-card>

        <!-- ============================================================ -->
        <!-- 组②：用户行为（从零开始）                                      -->
        <!-- ============================================================ -->
        <div class="group-head">
          <span class="group-title">二、用户行为</span>
          <span class="group-sub">来自 <code>user_event</code></span>
        </div>

        <!-- ★★ 这句话永远挂在组② 顶上，不折叠 -->
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          class="mb"
          title="这一组只有【埋点上线之后】的数据（2026-09-26 起）"
        >
          在那之前是零，而那是「还没有埋点」，<b>不是「没有人点」</b>。
          另外 —— 这个项目里<b>没有「转化」</b>：回答正文是纯文本、商品号点不动，
          也没有下单链路。<b>引用点击率量的是「想看原文」</b>。
        </el-alert>

        <div class="cards">
          <el-card shadow="never" class="card metric">
            <div class="num">{{ data.behavior.refClicks }}</div>
            <div class="lbl">引用被点开（次）</div>
          </el-card>
          <el-card shadow="never" class="card metric">
            <div class="num">{{ data.behavior.feedbackUp }}</div>
            <div class="lbl">👍</div>
          </el-card>
          <el-card shadow="never" class="card metric">
            <div class="num">{{ data.behavior.feedbackDown }}</div>
            <div class="lbl">👎</div>
          </el-card>
          <el-card shadow="never" class="card metric" :class="{ warncard: data.behavior.feedbackOther > 0 }">
            <div class="num">{{ data.behavior.feedbackOther }}</div>
            <div class="lbl">票型读不出来的</div>
          </el-card>
        </div>

        <el-card shadow="never" class="card">
          <template #header>
            <span class="card-title">引用点击率</span>
            <span class="card-note">有引用的回答里，被点开过至少一次的占比</span>
          </template>
          <div class="rate-row">
            <div class="rate-big">{{ rate(data.behavior.referenceClickRate) }}</div>
            <div class="rate-detail">
              {{ data.behavior.clickedCitedReplies }} / {{ data.behavior.citedReplies }}
              <div class="lbl">分子 / 分母</div>
            </div>
          </div>
          <!-- ★★ 分母为 0 时那句提示 —— 它是「不知道」，不是「0%」 -->
          <p v-if="data.behavior.citedReplies === 0" class="foot warn-text">
            ⚠️ 分母为 0 ⇒ 显示「—」而不是 0%。
            那一刻我们<b>不知道</b>有没有人点 —— 因为没有东西可点。
          </p>
          <p class="foot">
            ★ 分子在<b>构造上</b>是分母的子集（服务端那条 SQL 带一个 EXISTS），
            所以这个率<b>恒 ≤ 1</b>，不需要额外判据。
          </p>
        </el-card>

        <el-card shadow="never" class="card">
          <template #header>
            <span class="card-title">按事件类型</span>
            <span class="card-note">★ 固定键集，值为 0 也出现</span>
          </template>
          <div class="cards">
            <div v-for="(cnt, key) in data.behavior.byEventType" :key="key" class="mini">
              <div class="num-sm">{{ cnt }}</div>
              <div class="lbl">{{ EVENT_LABEL[key] || key }}</div>
            </div>
          </div>
        </el-card>

        <el-card shadow="never" class="card" :class="{ warncard: skewBad(data.behavior.clockSkewP50Ms) }">
          <template #header>
            <span class="card-title">客户端时钟偏差（中位数）</span>
          </template>
          <div class="num-sm">{{ ms(data.behavior.clockSkewP50Ms) }}</div>
          <p class="foot">
            服务端收到的时间 − 客户端报的时间。★ <b>它是对上面那些数的有效性检查</b>：
            偏差大得离谱 ⇒ 客户端时钟是坏的 ⇒ 别拿「用户什么时候点的」下任何结论。
            <br />（统计窗口本身按<b>服务端</b>时钟切，所以不受它影响 ——
            这正是 `occurred_at` 与 `received_at` 要分两列的理由。）
          </p>
        </el-card>

        <!-- ============================================================ -->
        <!-- 口径 —— ★ 原样摆着，不折叠                                     -->
        <!-- ============================================================ -->
        <el-card shadow="never" class="card notes">
          <template #header><span class="card-title">口径（服务端随数字一起发过来的）</span></template>
          <ul>
            <li v-for="(n, i) in data.notes" :key="i">{{ n }}</li>
          </ul>
        </el-card>
      </template>
    </div>
  </el-scrollbar>
</template>

<style scoped>
.metrics {
  flex: 1;
  min-height: 0;
}

.inner {
  padding: 20px 24px 40px;
  max-width: 900px;
  margin: 0 auto;
}

.mb {
  margin-bottom: 14px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.stamp {
  font-size: 12px;
  color: #c0c4cc;
}

.group-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 22px 0 10px;
}

.group-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.group-sub {
  font-size: 12px;
  color: #909399;
}

.cards {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.card {
  margin-bottom: 12px;
}

.metric {
  flex: 1;
  min-width: 130px;
}

.card-title {
  font-weight: 600;
  color: #303133;
}

.card-note {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}

.num {
  font-size: 26px;
  font-weight: 600;
  color: #409eff;
  font-variant-numeric: tabular-nums;
}

.num-sm {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  font-variant-numeric: tabular-nums;
}

.mini {
  min-width: 110px;
}

.lbl {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.foot {
  margin: 10px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}

.warn-text {
  color: #e6a23c;
}

/* 样本太少 */
.warn {
  color: #e6a23c;
  font-weight: 600;
}

.thin-tip {
  margin-left: 6px;
  font-size: 11px;
  color: #e6a23c;
}

/* 需要被一眼看到的那两格 */
.warncard {
  border-color: #f5dab1;
  background: #fdf6ec;
}

.lat-table {
  width: 100%;
}

.rate-row {
  display: flex;
  align-items: baseline;
  gap: 14px;
}

.rate-big {
  font-size: 30px;
  font-weight: 600;
  color: #67c23a;
  font-variant-numeric: tabular-nums;
}

.rate-detail {
  font-size: 14px;
  color: #606266;
}

.notes ul {
  margin: 0;
  padding-left: 18px;
}

.notes li {
  font-size: 12px;
  color: #909399;
  line-height: 1.8;
  margin-bottom: 6px;
}
</style>
