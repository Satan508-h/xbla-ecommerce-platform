/**
 * 埋点 —— 阶段 9.6b。
 *
 * ## ★★★ 全部埋点只有这一个出口
 *
 * ```
 *   组件          只调 track('ref_click', {...})，不知道上报方式
 *   本文件        组装事件 + 发出去
 *   后端          POST /api/events
 * ```
 *
 * ★ 收口成一个函数的意义：**换上报方式时只改这里**。如果每个组件各写一次
 * 上报，「换成 sendBeacon」就变成一次全局搜索 —— 而漏掉一处的症状是
 * 「那个事件永远收不到」，没有任何报错。
 *
 * ## ★★ 为什么是 `sendBeacon`，以及它带来的两个硬约束
 *
 * 用户点完引用可能**立刻关页面 / 跳转**。`fetch` 在 unload 时会被浏览器
 * 取消，那一条事件就静静地没了 —— 而它是唯一能说明「用户真的去看了」的证据。
 * `sendBeacon` 把请求交给浏览器，页面关掉也照样发。
 *
 * ⚠️ **约束一：sendBeacon 不能设自定义请求头。**
 * ⇒ 身份（`userId`）只能放在**请求体**里，不能走 `X-Xbla-User-Id`。
 * 这不是设计偏好，是 API 限制。别「顺手改成走 header」——那样改完编译通过、
 * 单测通过，而浏览器发出去的身份会变成 null，
 * 症状是「user_event.user_id 全是空」，看起来像用户都是匿名的。
 *
 * ⚠️ **约束二：body 必须是一个 type 为 application/json 的 Blob。**
 * 传字符串的话 sendBeacon 会发 `Content-Type: text/plain`，
 * 而 Spring 的 `@RequestBody` 认不出它 ⇒ **415**，事件全丢。
 *
 * ## ★ 失败一律静默
 *
 * 埋点是尽力而为的。发不出去就发不出去 —— **不弹提示、不打断用户、不重试**。
 * ⚠️ 但「不打扰用户」不等于「不留痕迹」：控制台永远有一条 `[track]`，
 * 打开 F12 就能看见「到底发出去了什么」。这一条也是这个模块唯一的验收方式。
 *
 * @see #track
 */

/** 上报地址。★ 同源，不需要跨域处理 */
const ENDPOINT = '/api/events'

/**
 * 当前埋点的上下文 —— 谁、在哪个会话里。
 *
 * ★★ 它是**模块级**状态，而不是逐层传的 props。理由有两条：
 *
 * ```
 *   ① 它本来就是全局会话状态，不是组件自己的数据
 *   ② 靠 props 逐层传的话，每加一个会埋点的组件都要再接一遍，
 *      而漏传的症状是「user_id 全是空」—— 静默，且看起来像「都是匿名用户」
 * ```
 *
 * 由 `ChatView` 在身份或会话号变化时调 {@link setTrackContext} 更新。
 */
let context = { userId: null, sessionNo: null }

/**
 * 更新埋点上下文。`ChatView` 在两个源上都挂 `watch`。
 *
 * @param {{userId?: string|number|null, sessionNo?: string|null}} next
 */
export function setTrackContext(next) {
  context = { ...context, ...next }
}

/**
 * ★ 身份可以是字符串 `'8'`（它来自输入框）、数字 `8`、或者空串。
 *
 * ```
 *   '8'  → 8      输入框里的数字
 *   '  ' → null   全是空白 = 没填
 *   ''   → null   ★ 空串和「没填」是同一件事，别送一个 0 上去
 *   null → null
 * ```
 *
 * ⚠️ 用 `Number(x)` 而不是 `parseInt(x)`：`parseInt('8abc')` 给 8，
 * 那是在猜。`Number('8abc')` 给 NaN，而 NaN 会被下面的判据挡成 null —— **如实**。
 */
function normalizeUserId(raw) {
  if (raw === null || raw === undefined || String(raw).trim() === '') return null
  const n = Number(String(raw).trim())
  return Number.isFinite(n) ? n : null
}

/**
 * 生成幂等键。
 *
 * ## ⚠️ 为什么不能只写 `crypto.randomUUID()`
 *
 * 因为 **`crypto.randomUUID` 只在安全上下文里存在**（https 或 localhost）。
 * 通过内网穿透用 http 打开演示页时它是 `undefined` —— 调用会抛错。
 *
 * ★★ 而症状是**静默的**：`eventNo` 缺失 ⇒ 服务端按白名单丢弃
 * ⇒ 埋点一条都进不去，而界面上一切正常。
 *
 * ⇒ 退回到 `crypto.getRandomValues`（它在**所有**上下文里都有），
 * 手工拼一个 v4 UUID。
 */
function newEventNo() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // 手工 v4 —— 形状与 randomUUID 一致（36 字符、第 13 位是 4）
  const b = new Uint8Array(16)
  crypto.getRandomValues(b)
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const hex = [...b].map((x) => x.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/**
 * 发出去。★ **永远不抛、永远不 await、永远不弹窗。**
 *
 * @returns {boolean} 浏览器有没有接收这个请求。
 *   ⚠️ `true` 只表示「浏览器说它会发」，**不表示服务端收到了** ——
 *   这正是 sendBeacon 的语义，而它对这个用途正好够。
 */
function send(event) {
  const body = JSON.stringify(event)
  try {
    if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
      // ★ 必须包成 Blob 并给 type —— 见文件头「约束二」
      const ok = navigator.sendBeacon(ENDPOINT, new Blob([body], { type: 'application/json' }))
      if (ok) return true
      // 返回 false = 浏览器的发送队列满了。★ 不重试：埋点丢一条是可以接受的，
      // 而重试会在用户网络差的时候既拖慢页面又一再失败
    }
  } catch {
    /* 落到下面的 fetch 兜底 */
  }
  try {
    // 兜底路径（老浏览器 / sendBeacon 队列满）：keepalive 让它在 unload 时也能发完
    fetch(ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      keepalive: true,
    }).catch(() => {})
  } catch {
    /* 静默 */
  }
  return false
}

/**
 * 记一条行为事件。
 *
 * ## 用法
 *
 * ```js
 * track('ref_click', { traceId, chunkId: r.chunk_id, no: r.no })
 * track('feedback',  { traceId, vote: 'up' })
 * ```
 *
 * ## ★★ `traceId` 会被提到顶层，其余进 `payload`
 *
 * 因为它对应 `user_event` 的**一列**（`trace_id`），而它是唯一把行为挂回
 * `qa_log` 的骨 —— 放进 `payload` 的话，SQL 得写 `payload->>'traceId'`，
 * 而那个表达式**查不了索引**，且「这条点击属于哪次回答」这句关系
 * 就藏进了一个 JSON 里。
 *
 * ## ★★ `userId` / `sessionNo` 从上下文来，不从参数来
 *
 * 见 {@link setTrackContext}。调用方只关心「发生了什么」。
 *
 * ## ⚠️ `occurredAt` 在客户端取，不是服务端
 *
 * 「用户什么时候点的」和「服务端什么时候收到的」在网络抖动时会差开，
 * 而时间窗口径要的是前者。★ 服务端另存一列 `received_at`，
 * 两者的差就是 **客户端时钟偏差** 的证据（`MetricsSnapshot.Behavior.clockSkewP50Ms`）。
 *
 * @param {string} type 事件类型。**必须在服务端白名单里**
 *   （`UserEventRequest.SUPPORTED_TYPES`：`ref_click` / `feedback`），
 *   否则整条会被静默丢弃
 * @param {object} [data] 事件数据。`traceId` 提到顶层，其余进 `payload`
 * @returns {object} 组装好的事件对象 —— ★ 返回它是为了让调用方（和测试）
 *   能看见「到底发出去了什么」，而不是只能靠抓包
 */
export function track(type, data = {}) {
  const { traceId = null, ...payload } = data

  const event = {
    eventNo: newEventNo(),
    eventType: type,
    userId: normalizeUserId(context.userId),
    sessionNo: context.sessionNo || null,
    traceId,
    payload: Object.keys(payload).length ? payload : null,
    occurredAt: new Date().toISOString(),
  }

  // ★★ 这一行不是调试残留 —— 它是这个模块【唯一】的验收手段。
  //    F12 里看得见，才谈得上「有事件可埋」是被证过的，而不是一句承诺。
  console.info('[track]', type, event)

  send(event)
  return event
}
