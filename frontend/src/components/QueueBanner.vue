<script setup>
/**
 * 排队状态提示（8.3）。
 *
 * ## ★★★ `position` 直接用，**不要 +1**
 *
 * 服务端那边（`ChatController.QueueEventListener.onQueued`）写着：
 *
 * ```
 *   // ★ position 是「前面还有几个人」（0-based）。★ 这里【不要】+1：
 *   //   前端要显示的那句话就是「你前面还有 N 位」，而 N 就是它。
 * ```
 *
 * ★ 这个「差一」是**两端各写一份时必然发生**的那类错误，而且它是**自洽的**：
 * 前端 +1、后端也 +1，测试全绿，只有用户看到的位置永远差一位。
 * 所以这条约定必须只有一处 —— 在服务端，因为它是产生这个数的地方。
 *
 * ## ★ 为什么这个提示值得做
 *
 * 阶段 6 的准入是「8 个名额 + 一个队列」，最坏情况下用户要等**一整个租期**
 * （TTL 默认 45 秒）。没有提示的话，用户面对的是一个**完全不动的白屏** ——
 * 而白屏和「服务挂了」在他眼里没有区别。
 *
 * ★ 有提示之后，等待变成一个**有进度感**的事：
 * 「你前面还有 2 位」会随着前面的人被服务而变小，用户能看出它在动。
 * 这就是 `docs/03` 里那句「排队期间推给前端的位置事件」为什么必须在
 * `sink.onStart` 之前就带上 traceId。
 */
defineProps({
  /** 前面还有几个人（0-based）。`null` = 没在排队 */
  position: { type: Number, default: null },
  /** 已经等了多久（毫秒） */
  waitedMs: { type: Number, default: 0 },
  /** 拿到名额后回填：进来时前面有几个人。`null` = 没排过队 */
  queuedAhead: { type: Number, default: null },
  /** 已经拿到名额（排队结束） */
  admitted: { type: Boolean, default: false },
})

const fmtSeconds = (ms) => (ms == null ? '0' : (ms / 1000).toFixed(1))
</script>

<template>
  <!--
    ★ 排队中：显示位置。
       admitted 之后这个块整体消失 —— 因为那时「排队」这件事已经结束了，
       而一个停在「你前面还有 0 位」的横幅会让人以为还在等。
  -->
  <div v-if="!admitted && position !== null" class="banner">
    <span class="dot" />
    <span class="text">
      正在排队 —— <b>你前面还有 {{ position }} 位</b>
    </span>
    <span class="dim">已等 {{ fmtSeconds(waitedMs) }} 秒</span>
  </div>

  <!--
    ★ 拿到名额后给一句回执，然后【不】常驻。
       实测过一次「名次从 2 跳到 7」的错觉（ADRs-075：重抢时保留原 score 就是为了它），
       所以这里只把「进来时的位置」报一次，之后不再变动。
  -->
  <div v-else-if="admitted && queuedAhead !== null" class="banner done">
    <span class="text">
      已排到 —— 进来时前面有 {{ queuedAhead }} 位
    </span>
  </div>
</template>

<style scoped>
.banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  margin-bottom: 12px;
  background: #ecf5ff;
  border: 1px solid #b3d8ff;
  border-radius: 6px;
  font-size: 13px;
  color: #409eff;
}

.banner.done {
  background: #f0f9eb;
  border-color: #c2e7b0;
  color: #67c23a;
}

.text {
  flex: 1;
}

.dim {
  color: #909399;
  font-size: 12px;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
  animation: breathe 1.2s ease-in-out infinite;
}

@keyframes breathe {
  0%,
  100% {
    opacity: 0.25;
  }
  50% {
    opacity: 1;
  }
}
</style>
