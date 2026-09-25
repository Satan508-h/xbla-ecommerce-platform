/**
 * 后端接口封装 —— 阶段 8。
 *
 * ## ★★ 为什么是 `fetch` 而不是 `axios`
 *
 * 流式那条路**必须**用 `fetch` + `ReadableStream`：
 * 浏览器的 `XMLHttpRequest`（axios 在浏览器里就是它）拿不到「边收边读」的
 * 增量流 —— 它只能等整个响应结束。而打字机效果的全部意义就是不等。
 *
 * ★ 既然流式非 `fetch` 不可，那么普通请求也统一用 `fetch` ——
 * **一个项目里两套 HTTP 客户端**意味着拦截器、错误处理、超时都要写两份，
 * 而两份会漂移。这个项目对「两处各写一份」的容忍度是零。
 *
 * ## ★★ 为什么不用 `EventSource`（阶段 2 的演示页用的那个）
 *
 * 因为 `EventSource` 只支持 GET，而问题文本要放进 URL query。
 * 中文经百分号编码会变成 **3 倍字节**，一个稍长的提问就会撞上 URL 长度上限
 * —— 而各浏览器、各代理的上限还不一样，症状是「有时候能发、有时候发不出去」，
 * 是最难查的那一类。
 *
 * 代价是 SSE 解析要自己写（下面 `parseSseBlock`）。这个取舍在阶段 2
 * 就写进了 `docs/08` ADR-011，阶段 8 是来还这笔账的。
 *
 * ★ 顺带一个好处：**`EventSource` 会自动重连，`fetch` 不会。**
 * 演示页那个版本必须记住「每个终止分支都要 close()」，否则同一个问题
 * 会被反复提问、反复计费。换成 fetch 之后这个坑从根上消失了。
 */

/** 后端统一响应封装 `{code, message, data}`，成功时 `code === 0`（不是 HTTP 200） */
const CODE_SUCCESS = 0

/** 身份头。★ 它**不是认证**，只是「让身份不进模型的可控范围」，见 ADR-054 */
const HEADER_USER_ID = 'X-Xbla-User-Id'

/**
 * 取后端响应体里的 `data`，失败就抛。
 *
 * ★ 判据是 `code === 0` 而**不是** HTTP 200：本项目的约定写在
 * `ApiResponse.CODE_SUCCESS`，两个数字都要对。只判 HTTP 状态码的话，
 * 一个 `code = 500` 但 HTTP 200 的响应会被当成成功。
 */
async function unwrap(res) {
  let body
  try {
    body = await res.json()
  } catch {
    // 拿到非 JSON（比如 Nginx 的 502 HTML 页）时，别让 JSON 解析错误
    // 掩盖真正的状态码 —— 那句话对排查毫无帮助
    throw new Error(`后端返回了非 JSON 响应（HTTP ${res.status}）`)
  }
  if (body.code !== CODE_SUCCESS) {
    throw new Error(body.message || `请求失败（code=${body.code}）`)
  }
  return body.data
}

/** 普通 GET */
export async function getJson(path, params) {
  const url = new URL(path, window.location.origin)
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, v)
    }
  }
  return unwrap(await fetch(url))
}

/** 会话列表。★ 后端已经排除了评测流量（4022/4111 那个数字） */
export const listSessions = (limit = 30) => getJson('/api/chat/sessions', { limit })

/** 某个会话的全部消息 */
export const listMessages = (sessionNo) =>
  getJson(`/api/chat/sessions/${encodeURIComponent(sessionNo)}/messages`)

/** 一次问答的技术细节（意图 / doc_types / 五段延迟 / 工具调用）。★ 零成本 */
export const getTrace = (traceId) =>
  getJson(`/api/chat/trace/${encodeURIComponent(traceId)}`)

/** 限流实况 */
export const getRateLimitStatus = () => getJson('/api/status/ratelimit')

/**
 * 解析一个 SSE 事件块（`parseSseBlock` 的输入是**完整**的一段，
 * 以空行结束，见下面的缓冲逻辑）。
 *
 * 格式（`SseEmitter` 实际写出来的）：
 *
 * ```
 *   event:delta
 *   data:{"v":"你"}
 *
 * ```
 *
 * ## ★ 三条按规范写的容忍
 *
 * ```
 *   ① `\r` —— Spring 只写 `\n`，但规范允许 `\r\n`，删掉更稳
 *   ② 冒号后**一个**空格 —— 规范说它属于分隔符，要吃掉（不是一个字节一个字节地 trim：
 *      `data:  x` 里的第二个空格是【数据】的一部分）
 *   ③ 以 `:` 开头的行是**注释**（也用作心跳）—— 直接跳过
 * ```
 *
 * ★ 还有一条不在规范里、但 SSR 解析必须有的：`data` 可以出现**多行**，
 * 拼接时用 `\n`。Spring 会把数据里的换行拆成多个 `data:` 行 ——
 * 而本项目的 payload 是 JSON，JSON 里的换行是转义的 `\n`（两个字符），
 * 不会真的产生换行字节。所以实际上每个事件只有一行 data，
 * **但解析器不能假设这一点** —— 那是协议给的能力，不是我们能依赖的巧合。
 */
function parseSseBlock(block) {
  let event = 'message' // SSE 的默认事件名（本项目的服务端总是显式给 event:）
  const dataLines = []

  for (const rawLine of block.split('\n')) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (line === '' || line.startsWith(':')) continue

    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    if (field === 'event') event = value
    else if (field === 'data') dataLines.push(value)
  }

  return { event, data: dataLines.join('\n') }
}

/**
 * 流式问答 —— **本文件的核心**。
 *
 * ## 事件契约（六个，payload 全是 JSON）
 *
 * | 事件 | payload | 含义 |
 * |---|---|---|
 * | `queued` | `{traceId, position, waitedMs}` | 在排队。★ `position` 是**「前面还有几个人」，0-based** |
 * | `admitted` | `{traceId, queueMs, queuedAhead}` | 拿到名额开始回答。`queuedAhead` 为 `null` = 没排队 |
 * | `meta` | `{traceId, sessionNo}` | 拿到 traceId/会话号，**在检索之前**（避免对着空白页等） |
 * | `delta` | `{v}` | 一段正文（打字机） |
 * | `done` | 完整的 `ChatAskResponse` | 结束 |
 * | `failed` | `{message, traceId}` | 失败 |
 *
 * ## ★★ `queued` 的 `position` 不要再 ±1
 *
 * 服务端那边写着「`position` 是『前面还有几个人』，**前端要显示的那句话就是它**」。
 * 我在这里加 1 的话，用户会看到「前面还有 1 位」而其实轮到他了 —— 这种差一
 * 在两端各写一份时必然发生，所以这里**只是取用，什么都不做**。
 *
 * ## ★★ 中文会被切在 chunk 边界上
 *
 * 一个 UTF-8 的中文字是 3 个字节，而 TCP 的 chunk 边界可以落在**任何**位置。
 * `TextDecoder` 必须带 `{stream: true}`，它会把「不完整的字符」暂存起来
 * 等下一个 chunk。不带的话，被切开的那半个字符会变成 `U+FFFD`（�）——
 * 而它在日志和界面上都是一个「看起来像编码问题」的现象，很容易被归因成
 * 「后端返回的编码不对」。同 `CLAUDE.md` 里那条 Git Bash 中文的坑。
 *
 * @param {{question: string, sessionNo?: string, userId?: string|number, signal?: AbortSignal}} req
 * @param {{onQueued?, onAdmitted?, onMeta?, onDelta?, onDone?, onFailed?}} handlers
 */
export async function streamChat(req, handlers = {}) {
  const headers = { 'Content-Type': 'application/json' }
  // ★ 身份只在真有时才发头：发一个空串和「没有身份」是两件事，
  //   后端对空的处理是「当没有」——但那是后端的宽容，不是我们的契约
  if (req.userId !== undefined && req.userId !== null && req.userId !== '') {
    headers[HEADER_USER_ID] = String(req.userId)
  }

  const res = await fetch('/api/chat/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify({
      sessionNo: req.sessionNo ?? null,
      question: req.question,
      systemPrompt: null,
    }),
    signal: req.signal,
  })

  // ★ 校验失败（400）、没登录（401）这类**在流开始之前**就返回了，
  //   此时 body 是普通 JSON 而不是 SSE。不先分开的话，
  //   下面的解析器会把那段 JSON 当成一个畸形事件，报一句看不懂的错。
  if (!res.ok) {
    let msg = `请求失败（HTTP ${res.status}）`
    try {
      const body = await res.json()
      if (body?.message) msg = body.message
    } catch {
      /* 保持默认消息 */
    }
    throw new Error(msg)
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')

  /** 还没凑成一个完整事件的字节/字符 —— 见下面「为什么要缓冲」 */
  let buffer = ''

  const dispatch = (block) => {
    const { event, data } = parseSseBlock(block)
    if (!data) return

    let payload
    try {
      payload = JSON.parse(data)
    } catch {
      // ★ 解析不了就**别猜**。原样报出来，让读到的人能立刻看出
      //   「是协议变了」而不是「业务出错了」
      console.error('[sse] 事件 payload 不是合法 JSON', event, data)
      return
    }

    switch (event) {
      case 'queued':
        handlers.onQueued?.(payload)
        break
      case 'admitted':
        handlers.onAdmitted?.(payload)
        break
      case 'meta':
        handlers.onMeta?.(payload)
        break
      case 'delta':
        handlers.onDelta?.(payload.v)
        break
      case 'done':
        handlers.onDone?.(payload)
        break
      case 'failed':
        handlers.onFailed?.(payload)
        break
      default:
        // ★ 认不出的事件名打出来而不是忽略：服务端加事件时前端忘了处理，
        //   症状是「某个功能静默不工作」，而这里的一句 console 是唯一的线索
        console.warn('[sse] 未知事件名:', event, payload)
    }
  }

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break

    // ★ {stream: true} 见上面「中文会被切在 chunk 边界上」
    buffer += decoder.decode(value, { stream: true })

    // ★★ 为什么要缓冲：一个 SSE 事件可能**跨两个 chunk**。
    //    只有看到空行（事件分隔符）才说明这一段是完整的。
    //    直接对每个 chunk 调 parse 的话，被切开的 JSON 会解析失败 ——
    //    而失败频率取决于网络分片，本地测试几乎碰不到，线上偶发。
    let sep
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      dispatch(buffer.slice(0, sep))
      buffer = buffer.slice(sep + 2)
    }
  }

  // 收尾：读完了但还剩半段（正常结束的服务端一定会以空行收尾，
  // 所以这通常只有异常断流才会走到）。留着不处理会让最后一段正文静默丢失。
  buffer += decoder.decode()
  if (buffer.trim() !== '') {
    console.warn('[sse] 流结束时还剩不完整的事件，已尽力解析:', buffer)
    dispatch(buffer)
  }
}
