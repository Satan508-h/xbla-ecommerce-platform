<script setup>
/**
 * 限流实况页 —— 阶段 6 那个装置的可视化。
 *
 * ## ★★ 它证明的不是「有限流」，而是「限流是对的」
 *
 * ```
 *   名额在用 / 总数       ← 有没有超过总数？超了就是【超卖】
 *   队列长度              ← 有没有人卡在队里不动？
 *   线程池 active/max     ← 「被拒」到底是因为名额还是因为池满
 *   累计放弃数            ← 拒绝是【在正常工作】，不是故障
 * ```
 *
 * ★ 阶段 6 验的是「100 并发无超卖无死锁」。这一页让那件事**当场可见**：
 * 打开另一个标签页狂点，回来看这里 `permitsInUse` 永远 ≤ `permits`。
 *
 * ## ★★ 数据源是 `/api/status/ratelimit`，**不是** debug 端点
 *
 * ```
 *   /api/debug/ratelimit/state   ✗ @Profile("local")，生产不存在；
 *                                  而且带 queueHead（别人的 traceId）
 *   /api/status/ratelimit        ✓ 生产也有、只读、白名单字段
 * ```
 *
 * ★ 取舍写在服务端 `RateLimitStatus` 的类注释里。
 * 前端这里**只读它给的那些字段**，不去猜还有没有别的。
 *
 * ## ⚠️ 轮询间隔 3 秒
 *
 * 数据源是几个 `ZCARD`（O(1)），压力很小，但它是**公网可访问**的
 * —— 所以间隔不取 500ms 那种「看起来很实时」的值。
 * 这个页面的用途是「看一眼状态」，不是监控大屏。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { getRateLimitStatus } from '../api.js'

const POLL_MS = 3000

const status = ref(null)
const error = ref('')
const lastAt = ref(null)
let timer = null

async function poll() {
  try {
    status.value = await getRateLimitStatus()
    error.value = ''
    lastAt.value = new Date()
  } catch (e) {
    // ★ 失败时【不清空】上一次的数据，只在顶上显示一句 ——
    //   清空会让页面看起来像「限流停掉了」，而真相只是这一次请求失败了
    error.value = e.message
  }
}

onMounted(() => {
  poll()
  timer = setInterval(poll, POLL_MS)
})

onBeforeUnmount(() => {
  // ★ 一定要清：组件卸载后还在轮询的话，用户切到别的页面
  //   仍然每 3 秒打一次后端 —— 而且没有任何迹象
  if (timer) clearInterval(timer)
})

const pct = (used, total) =>
  total > 0 ? Math.min(100, Math.round((used / total) * 100)) : 0

/**
 * 使用率的颜色。
 * ★ 判据是「有没有满」，不是「好不好看」—— 满 = 有人要排队了。
 */
const barStatus = (used, total) => {
  if (total <= 0) return 'info'
  const p = used / total
  if (p >= 1) return 'exception'
  if (p >= 0.75) return 'warning'
  return 'success'
}
</script>

<template>
  <el-scrollbar class="status">
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

      <el-skeleton v-if="!status && !error" :rows="5" animated />

      <template v-else-if="status">
        <el-alert
          v-if="!status.enabled"
          type="info"
          :closable="false"
          show-icon
          class="mb"
          title="限流当前是关的（xbla.ratelimit.enabled=false）"
        >
          下面这些数都是「没在跑」的样子，不是「没有人在用」。
        </el-alert>

        <!-- 一、名额：这一格就是「有没有超卖」 -->
        <el-card shadow="never" class="card">
          <template #header>
            <span class="card-title">名额（并发上限）</span>
          </template>

          <div class="big">
            <span class="num">{{ status.permitsInUse }}</span>
            <span class="slash">/</span>
            <span class="total">{{ status.permits }}</span>
          </div>
          <el-progress
            :percentage="pct(status.permitsInUse, status.permits)"
            :status="barStatus(status.permitsInUse, status.permits)"
            :stroke-width="14"
          />
          <div class="hint">
            ★ 这个数<b>永远不该大于右边的总数</b>。大于就是<b>超卖</b> ——
            阶段 6 的压测就是每 20ms 采一次它并记下最大值来验这一条。
          </div>
          <div class="hint dim">
            名额租期 {{ status.permitTtlMs }} ms —— 持有者必须在这个时间内续期，
            否则名额会被回收（防「进程被强杀后名额永久泄漏」）
          </div>
        </el-card>

        <!-- 二、队列 -->
        <el-card shadow="never" class="card">
          <template #header>
            <span class="card-title">队列</span>
          </template>

          <div class="big">
            <span class="num">{{ status.queueSize }}</span>
            <span class="slash">/</span>
            <span class="total">{{ status.maxQueue }}</span>
          </div>
          <el-progress
            :percentage="pct(status.queueSize, status.maxQueue)"
            :status="barStatus(status.queueSize, status.maxQueue)"
            :stroke-width="14"
          />
          <div class="hint dim">
            超过上限是<b>同步拒绝</b>（不排队），客户端会拿到 HTTP 503 + Retry-After。
            ⚠️ 队列长度含<b>已过期但还没被清理</b>的成员 —— 它可能略大于「真正在等的人」。
          </div>
        </el-card>

        <!-- 三、线程池：区分「被拒」的两种成因 -->
        <el-card shadow="never" class="card">
          <template #header>
            <span class="card-title">排队线程池</span>
          </template>

          <div class="big">
            <span class="num">{{ status.queuePoolActive }}</span>
            <span class="slash">/</span>
            <span class="total">{{ status.queuePoolMax }}</span>
          </div>
          <el-progress
            :percentage="pct(status.queuePoolActive, status.queuePoolMax)"
            :status="barStatus(status.queuePoolActive, status.queuePoolMax)"
            :stroke-width="14"
          />
          <div class="hint">
            ★ <b>「被拒」有两种成因，而它们要调的是不同的旋钮</b>：
            名额满了 → 调 <code>permits</code>；池满了 → 调 <code>queue-pool</code>。
            ⚠️ <code>qa_log.queue_ms</code> <b>区分不了</b>这两者
            （实测 0 在两种情况下都出现过），判据只能是 <code>error_msg</code> 里的原因串。
          </div>
          <div class="hint dim">
            看的是 active 而不是 poolSize：core 线程默认不回收，空闲时 poolSize 仍然拉满，
            用它会永远显示「忙」。
          </div>
        </el-card>

        <!-- 四、累计放弃 -->
        <el-card shadow="never" class="card">
          <template #header>
            <span class="card-title">累计放弃排队</span>
          </template>
          <div class="big">
            <span class="num">{{ status.givenUpTotal }}</span>
          </div>
          <div class="hint">
            队列满或等太久时递增。<b>它非 0 不是故障</b> ——
            恰恰是限流在正常工作的证据。它单调递增，进程重启归零。
          </div>
        </el-card>

        <div class="foot mono">
          <template v-if="lastAt">上次刷新 {{ lastAt.toLocaleTimeString() }}</template>
          <template v-else>—</template>
          · 每 {{ POLL_MS / 1000 }} 秒自动刷新
        </div>
      </template>
    </div>
  </el-scrollbar>
</template>

<style scoped>
.status {
  flex: 1;
  min-height: 0;
  background: #f5f7fa;
}

.inner {
  max-width: 720px;
  margin: 0 auto;
  padding: 20px 24px 60px;
}

.mb {
  margin-bottom: 14px;
}

.card {
  margin-bottom: 14px;
}

.card-title {
  font-weight: 600;
  color: #606266;
  font-size: 14px;
}

.big {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 10px;
}

.num {
  font-size: 34px;
  font-weight: 600;
  color: #303133;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.slash {
  font-size: 20px;
  color: #c0c4cc;
}

.total {
  font-size: 20px;
  color: #909399;
  font-variant-numeric: tabular-nums;
}

.hint {
  margin-top: 10px;
  font-size: 12px;
  color: #606266;
  line-height: 1.7;
}

.hint.dim {
  color: #909399;
}

.foot {
  text-align: center;
  font-size: 11px;
  color: #c0c4cc;
  margin-top: 20px;
}
</style>
