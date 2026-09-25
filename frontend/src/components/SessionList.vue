<script setup>
/**
 * 左侧的会话列表（8.6 的「会话历史 + 切换」）。
 *
 * ## ★ 它显示的是【后端过滤过】的列表
 *
 * 后端 `GET /api/chat/sessions` 已经排除了评测流量 —— 那不是优化而是功能本身：
 * 库里 4111 个会话里有 **4022 个是阶段 7 跑评测时建的**（97.8%）。
 * 不过滤的话这个列表就是「商品支持七天无理由退货吗」刷屏。
 *
 * ★ 前端这里**不做任何过滤**，也不做「猜哪个是评测」的启发式
 * —— 判据只能有一处，而且它在数据那一侧（`qa_log.eval_run_id`），
 * 不在这一侧。前端再判一次就是第二个事实来源，且它会与后端漂移。
 *
 * ## ★ 为什么没有「删除会话」
 *
 * 因为 `chat_message` 表的设计是「只增不改不删」（`docs/04`），
 * 会话是审计材料。加一个删除按钮会让界面承诺一件数据层不打算支持的事。
 * ★ 界面能做的事应当是数据承诺过的子集，不是它的超集。
 */
import { computed } from 'vue'

const props = defineProps({
  /** `[{sessionNo, title, messageCount, lastActiveAt, createdAt}]` */
  sessions: { type: Array, default: () => [] },
  activeSessionNo: { type: String, default: null },
  loading: { type: Boolean, default: false },
})

const emit = defineEmits(['select', 'refresh', 'create'])

/** 把 ISO 时间戳转成「刚刚 / 5 分钟前 / 3 小时前 / 09-24」 */
function relativeTime(iso) {
  if (!iso) return ''
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return ''

  const diffMs = Date.now() - then
  const minutes = Math.floor(diffMs / 60000)

  // ★ 负数说明客户端时钟比服务端慢（或者时区没对上）。
  //   不特判的话会显示「-3 分钟前」—— 看起来像 bug，其实是时钟问题，
  //   所以这里退回显示具体日期，而不是一个负数
  if (minutes < 0) return formatDate(iso)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`

  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`

  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`

  return formatDate(iso)
}

function formatDate(iso) {
  const d = new Date(iso)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 标题可能是 null（会话刚建、首轮还没答完），给一句占位而不是空白 */
const titleOf = (s) => s.title || '(未命名会话)'
</script>

<template>
  <div class="session-list">
    <div class="head">
      <span class="head-title">会话</span>
      <div class="head-actions">
        <el-button
          size="small"
          text
          :loading="loading"
          title="刷新列表"
          @click="emit('refresh')"
        >
          刷新
        </el-button>
        <el-button size="small" type="primary" plain @click="emit('create')">
          新对话
        </el-button>
      </div>
    </div>

    <el-scrollbar class="body">
      <el-empty
        v-if="!sessions.length && !loading"
        description="还没有会话"
        :image-size="60"
      />

      <div
        v-for="s in sessions"
        :key="s.sessionNo"
        class="item"
        :class="{ active: s.sessionNo === props.activeSessionNo }"
        @click="emit('select', s.sessionNo)"
      >
        <div class="item-title">{{ titleOf(s) }}</div>
        <div class="item-meta">
          <!--
            ★ messageCount 是【每轮 +2】维护的（一条用户 + 一条助手），
              所以它是消息条数而不是轮数 —— 文案上不写「轮」，
              写「条」，免得读的人按轮数去理解。
          -->
          <span>{{ s.messageCount ?? 0 }} 条</span>
          <span>{{ relativeTime(s.lastActiveAt) }}</span>
        </div>
      </div>
    </el-scrollbar>
  </div>
</template>

<style scoped>
.session-list {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  /* ★ 同上：flex 子项要显式 min-height: 0 才能让内部滚动生效 */
  min-height: 0;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid #ebeef5;
  flex: none;
}

.head-title {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
}

.head-actions {
  display: flex;
  gap: 4px;
}

.body {
  flex: 1;
  min-height: 0;
}

.item {
  padding: 10px 12px;
  cursor: pointer;
  border-left: 3px solid transparent;
  border-bottom: 1px solid #f5f7fa;
}

.item:hover {
  background: #f5f7fa;
}

.item.active {
  background: #ecf5ff;
  border-left-color: #409eff;
}

.item-title {
  font-size: 13px;
  color: #303133;
  /* 长标题截断，不要让它把侧栏撑宽 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 11px;
  color: #909399;
}
</style>
