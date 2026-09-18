# 技术决策记录（ADR）

> 记录每个**重要且不容易改**的技术决策：当时有哪些备选、为什么选了它、
> 以及这个选择带来了什么代价。
>
> 这份文档是面试时最有力的材料 —— 它能证明的不是「我会用某个技术」，
> 而是「我评估过多个方案，知道各自的代价」。

**格式**：Context（背景）→ Options（备选）→ Decision（决策）→ Consequences（代价与影响）

---

## ADR-001 · HTTP 客户端选 JDK 内置的

**日期**：2026-09-18　**状态**：已采纳　**阶段**：2

### Context

`client/` 层要手写 HTTP 调用（项目约定不用 Spring AI / LangChain4j，链路必须可逐行解释）。
需要一个能发 POST JSON、并且能**流式读取 SSE** 的客户端。

### Options

| 方案 | 优点 | 代价 |
|---|---|---|
| **JDK 内置 `java.net.http.HttpClient`** | 零新增依赖；`BodyHandlers.ofLines()` 原生支持流式；没有隐藏的连接管理魔法 | API 偏底层，超时语义需要自己搞清楚 |
| Apache HttpClient 5 | 工业级成熟，连接池/重试机制完善 | 多一个依赖；部分行为是库内部实现，不如手写清楚 |
| OkHttp | API 简洁，社区广 | 多一个依赖；方 block 式 API 与 Spring 生态融合度一般 |
| Spring WebClient | 响应式，背压支持好 | 要引入 WebFlux 整条响应式栈，与 Spring MVC 是两套心智模型 |

### Decision

**JDK 21 内置 `java.net.http.HttpClient`。**

理由：这是**唯一零依赖**的方案，而它恰好原生支持本项目的核心需求（SSE 流式读取）。
项目定位是「链路可逐行解释」，没有第三方库封装反而更符合。

### Consequences

- ✅ `pom.xml` 里没有一个 HTTP 客户端依赖
- ⚠️ **超时语义要自己搞清楚**（见 ADR-004）：`HttpRequest.timeout()` 对
  `ofLines` 只覆盖到响应头，对 `ofString` 覆盖整个响应体 —— 两者必须配不同的值
- ⚠️ 没有 per-read 超时，流式场景的空闲检测要靠上层（`SseEmitter` 的超时回调
  里关闭上游流）来兜底

---

## ADR-002 · 流式接口设计成「阻塞 + 回调」

**日期**：2026-09-18　**状态**：已采纳　**阶段**：2

### Context

流式调用既要**逐段把正文推出去**，又要在结束时**拿到 token 用量**。
需要一个方法签名同时表达这两件事。

### Options

| 方案 | 问题 |
|---|---|
| `Flux<Chunk>` | 要引入 WebFlux 响应式栈 |
| `Stream<Chunk>` | **惰性**执行，try-with-resources 一关就废；流中间抛的异常会被包装成 `UncheckedIOException`，**堆栈里完全看不出是哪家供应商出的问题** |
| **`chatStream(req, Consumer<String> onDelta)` 返回 `StreamResult`** | 回调式偏「老派」 |

### Decision

**阻塞 + 回调，usage 走返回值。**

`Consumer<String>` 天然表达「每来一个增量就做一次副作用」——
正好对应 SSE 的 `emitter.send()`。而 `Stream<Chunk>` 的异常包装问题
在降级链里是致命的：我们恰恰需要从异常里读出「是哪家失败、什么原因」。

### Consequences

- ✅ 能在任意时刻抛出**真正的业务异常**（`ModelCallException` 带 provider/model/状态码）
- ✅ service 层完全不用知道响应式编程
- ⚠️ 调用方必须注意：**回调里不能做耗时操作**，它会直接阻塞上游读取循环
- ⚠️ 回调抛出的异常会被归类为 `CLIENT_ABORTED`（用户断开），见 ADR-006

---

## ADR-003 · 自己 `@Bean` 熔断器注册表，不用自动配置

**日期**：2026-09-18　**状态**：已采纳　**阶段**：2

### Context

`resilience4j-spring-boot3` 的自动配置类
`AbstractCircuitBreakerConfigurationOnMissingBean` 上挂着：

```java
@Condition(AspectJOnClasspathCondition.class)
```

即「类路径上得有 AspectJ 才装配」。问题是这个注解挂在**父类**上，
而 `@Conditional` 不是 `@Inherited` 的 —— 它到底生不生效，
取决于 Spring 处理元数据注解继承的具体策略。

### Options

| 方案 | 问题 |
|---|---|
| 依赖自动配置 | Bean 可能**静默不创建**，日志里只有一行 WARN。配置全对、依赖也加了、Bean 就是没有 —— 这类问题排查成本极高 |
| 用 `@CircuitBreaker` 注解 | fallback 链表达不了「流式已吐字就不能降级」；状态机被藏在切面里，不符合「可逐行解释」 |
| **自己 `@Bean CircuitBreakerRegistry`** | 配置源要自己对接 |

### Decision

**在 `ChatChainConfig` 里自己建 registry**，配置统一来自 `xbla.llm`。

同时 `pom.xml` 里**仍然引 `spring-boot-starter-aop`** 作为保险，
也为了让熔断器的健康指标能挂进 `/actuator/health`。

`CircuitBreakerConfigurationOnMissingBean` 上有 `@ConditionalOnMissingBean`，
看到我们提供了就会自动退让，不冲突。
`/actuator/circuitbreakers` 端点也不关心 registry 是谁建的。

### Consequences

- ✅ 完全不依赖 AspectJ 是否在类路径 → 那个不确定性被彻底绕开
- ✅ 熔断器参数和模型配置在同一个前缀下，改一处就够
- ⚠️ 需要自己写「配置项 → Resilience4j 对象」的转换（`CircuitBreakerProps.toResilienceConfig()`）
- 📌 顺带解决：**预热**。自动配置是「第一次用到才创建」，
  启动后立刻看 `/actuator/circuitbreakers` 会是一片空白。我们预热了所有条目

---

## ADR-004 · 熔断器的耗时口径用 TTFB，不用整个流时长

**日期**：2026-09-18　**状态**：已采纳　**阶段**：2

### Context

Resilience4j 的熔断器有「慢调用率」阈值（`slow-call-rate-threshold`）。
需要决定：流式调用结束时，喂给 `onSuccess(duration, unit)` 的应该是什么？

### Options

| 方案 | 后果 |
|---|---|
| 整个流的持续时间 | ★ **一次 60 秒的正常长回答会被判定为慢调用** |
| **TTFB（建连 + 首字节）** | 需要额外测量和传递 |

### Decision

**用 TTFB。** `StreamResult.breakerObservedMs()` 明确返回 `ttfbMs`。

如果按整个流时长计，失败率一超阈值，熔断器就会在
**系统最健康、正在正常干重活**的时候跳闸 —— 这是最坏的反向优化：
越努力服务，越容易被判成故障。

TTFB 才是真正反映「这家供应商有没有问题」的指标：
正常 1-3 秒，超过配置的 15 秒（`slow-call-duration-threshold`）确实说明不对劲。

### Consequences

- ✅ 长回答不会被误判
- ⚠️ `StreamResult` 要多带一个 `ttfbMs` 字段
- 📌 失败路径仍用实际耗时 —— 一次跑 60 秒然后失败的调用，
  被判成「慢调用」是合理的

---

## ADR-005 · 流式降级有硬边界：吐过字就不能降级

**日期**：2026-09-18　**状态**：已采纳（架构约束，非偏好）　**阶段**：2

### Context

降级链在任何失败时都应该尝试下一家吗？

### Decision

**不是。** 只有「一个字节都没发给用户」时才能降级：

```
statusCode != 200 ─────────────────────→ 可以降级
statusCode == 200
  ├─ 首个 content 增量之前失败 ────────→ 可以降级
  └─ 已吐字后失败 ─────────────────────→ 不能降级
```

### Consequences

**这是 SSE 架构的固有约束，不是可以优化掉的东西。**

用户已经看到前面吐出来的字了。换一家从头重来，他会看到
**两段拼接起来的、前后矛盾的话** —— 比如前半句在讲退货流程，
后半句突然变成了发货时效。这比直接报错糟糕得多，因为用户可能真的信了。

- ✅ 语义诚实：能降级时降级，不能时如实报错
- ✅ 这种情况仍**计入熔断失败** —— 下一轮请求会提前降级到 P1
- ⚠️ 实现上：`contentChars` 的统计和分类发生在 `OpenAiCompatibleLlmClient` 内部
  （信息最全的地方），router 只读 `kind.isFallbackWorthy()`

---

## ADR-006 · 「用户断开」单独分一类错误

**日期**：2026-09-18　**状态**：已采纳　**阶段**：2

### Context

用户关闭页面时，`emitter.send()` 会抛 `IOException`（Broken pipe）。
这和「模型供应商出问题」抛出的异常**在类型上完全一样**。

### Decision

**新增 `ModelErrorKind.CLIENT_ABORTED`：既不计熔断失败，也不降级。**

不区分的后果有两个，都很恶劣：

1. **污染熔断统计**：用户每关一次页面就记一次供应商失败。
   几个人关几次标签页，一个完全健康的供应商就被熔断打开了 ——
   **系统自己把好服务判成坏的**。
2. **误报降级**：明明是我们自己断开的上游流，却去降级重试，白白多花一次钱。

### Consequences

- ✅ 熔断统计只反映供应商的真实健康度
- ✅ 顺带一个收益：**用户断开时我们主动关闭上游流** ——
  上游一关，模型不再产生 token，**计费停止**。
  否则用户点了关闭，我们还在为一个 4000 token 的回答付钱
- ⚠️ `chatStream` 的回调里要 catch 住下游异常并包装，不能让原始 `IOException`
  直接冒泡（那会被当成网络故障）

---

## ADR-007 · 402 归为「余额不足」而不是「请求错误」

**日期**：2026-09-18　**状态**：已采纳　**阶段**：2　**来源**：**测试中发现**

### Context

初版实现里 `fromHttpStatus` 把「其余 4xx」一律归到 `BAD_REQUEST`。
而 `BAD_REQUEST` 的策略是**既不降级也不计熔断**。

实测时硅基流动账户余额不足，返回：

```json
HTTP 402 {"code":30001,"message":"Sorry, your account balance is insufficient"}
```

日志里显示为：

```
模型调用失败 [bad_request] ... HTTP 402
```

**整条降级链在这一档直接终止了。**

### Decision

**新增 `ModelErrorKind.QUOTA_EXHAUSTED`（402），可降级 + 计熔断**，
并在 `fromHttpStatus` 里显式判断 402，不让它落到「其余 4xx」分支。

402 的真实含义是「这家服务不了我们」，和请求体对不对毫无关系。
而且**同一家供应商上不同模型的免费/收费状态可能不同** ——
实测时 `deepseek-ai/DeepSeek-V4-Flash` 能用而 `Qwen/Qwen3-8B` 报 402，
所以降级到同家的另一个模型是有意义的。

计入熔断也是对的：402 在充值之前不会自愈，让熔断器跳闸可以避免
每个请求都白白等一次往返。

### Consequences

- ✅ 余额不足时能自动降级到其他模型
- 📌 **这个 bug 是跑出来的，不是设计时想到的** ——
  错误分类表一开始根本没有 402 这一行。
  这正是「一步一验证」策略的价值：如果等到阶段 7 评测时才发现，
  排查方向会完全跑偏（会以为是「所有模型都挂了」）

---

## ADR-008 · 配置分三层：providers / models / chat-chain

**日期**：2026-09-18　**状态**：已采纳　**阶段**：2

### Context

降级链上 P0 是 DeepSeek 官方，P1 和 P2 是硅基流动上的**不同模型**。
配置怎么组织？

### Options

| 方案 | 问题 |
|---|---|
| 扁平：每个模型一个条目，连接信息重复写 | 硅基流动的 Key 要写两遍，改一处忘一处 |
| 按供应商分组 | 无法表达「P1/P2 同供应商不同模型」 |
| **三层：连接 / 模型 / 策略** | 配置项变多，要跨层引用 |

### Decision

```yaml
xbla.llm
├── providers    ① 连接：域名 + 密钥（可被多个模型复用）
├── models       ② 模型：provider 引用 + 真实模型 ID + 单价
└── chat-chain   ③ 策略：降级顺序（引用 models 的 key）
```

**供应商是「连接」，模型是「计价与协议」，链路是「策略」。**
三者职责不同，变化频率也不同 —— 分层之后，
「换个模型」只改 models，「调整降级顺序」只改 chat-chain，
「换供应商」只改 provider 引用。

### Consequences

- ✅ 密钥只写一份
- ✅ 启动期能做完整的引用校验（chain → models → providers），
  写错模型名直接启动失败，而不是运行时静默降级
- ⚠️ 配置项变多，需要 `spring-boot-configuration-processor` 提供 IDE 补全
- ⚠️ **Map 的 key 有命名限制**：Spring Boot 只允许小写字母、数字、短横线。
  所以 `deepseek-v3.2` 必须写成 `deepseek-v3-2`，
  否则要用 `[key]` 括号语法

---

## ADR-009 · 丢弃 `reasoning_content`，只取最终回答

**日期**：2026-09-18　**状态**：已采纳　**阶段**：2

### Context

`deepseek-flash` 是**推理模型**，响应里多一个非标准的 `reasoning_content` 字段。
实测推理 token 占总输出的 **87%**（124 个输出里 108 个是推理）。

### Options

| 方案 | 后果 |
|---|---|
| 存库 + 前端折叠展示 | 加一个 Flyway 迁移；`chat_message` 表要改 |
| **DTO 里不声明这个字段** | 将来想加回来要改表 + 历史数据已丢 |
| 只写日志 | 日志会非常长（推理内容是回答的 5-10 倍） |

### Decision

**DTO 里不声明，靠 Jackson 静默忽略。**

现有配置 `spring.jackson.deserialization.fail-on-unknown-properties: false`
已经开着了，所以这是**零代码实现** ——
`WireChatMessage.Message` 和 `WireDelta` 里只声明 `content`，
服务端多返回的字段自动被跳过。

### Consequences

- ✅ 零过滤代码。不需要在每个 chunk 里判断「这个增量是推理还是正式回答」——
  那种判断一旦漏了，会把模型的内心独白当成答案推给用户，
  而且这种 bug 在日志里完全看不出来
- ✅ `usage.completion_tokens_details.reasoning_tokens` 仍然保留在 DTO 里，
  用于**诊断**（推理占比异常高 → 该调大 max-tokens 或换模型）
- ⚠️ 代价：如果将来产品上想展示「思考过程」，要改表 + 重新灌数据
- 📌 **换成 `Qwen/Qwen3-8B` 等非推理模型时这个设计依然成立** ——
  没有 `reasoning_content` 字段，Jackson 就当它不存在

---

## ADR-010 · 成本拿不到用量时记 NULL，绝不估算

**日期**：2026-09-18　**状态**：已采纳　**阶段**：2

### Context

`qa_log` 有 `cost NUMERIC(10,6)` 列。但用量在某些情况下拿不到：
供应商不支持 `stream_options.include_usage`、连接在 usage 汇总 chunk
到达之前就断了、用户中途关闭页面。

### Options

| 方案 | 后果 |
|---|---|
| 按字符数估算 | 数字看起来「完整」，但**是编的** |
| **记 NULL** | 查询时要做 null 处理 |

### Decision

**记 NULL。** `ModelCostCalculator.calculate()` 在用量不可用时返回 `null`。

`qa_log` 是**只增不改不删**的表，评测数据全部来源于它。
编一个数字进去，会永久污染阶段 7 的所有成本指标，
而且事后**无法分辨「哪些是真实值、哪些是估算的」**。

NULL 至少是诚实的：看到 NULL 就知道「这次没拿到用量」，
可以单独统计「用量缺失率」这个指标。

### Consequences

- ✅ 数据可信
- ⚠️ 查询时要处理 NULL（`SUM(cost)` 会自动跳过 NULL，行为正确）
- 📌 顺带一个相关约定：降级事件为空时也写 `null` 而不是 `[]`，
  这样运维查询可以写 `WHERE degradation_events IS NOT NULL` 一眼筛出降级请求

---

## ADR-011 · 流式演示用 GET + EventSource

**日期**：2026-09-18　**状态**：已采纳（阶段 2 临时方案）　**阶段**：2

### Context

需要一个浏览器端演示验证「打字机效果」。

### Options

| 方案 | 代价 |
|---|---|
| **`GET /api/chat/stream` + `EventSource`** | URL 长度受限（中文百分号编码成 3 倍字节）；问题文本出现在 URL 里 |
| `POST /api/chat/stream` + `fetch` + `ReadableStream` | 要手写 SSE 解析（约 40 行），且浏览器兼容性要自己处理 |

### Decision

阶段 2 **用 GET + EventSource**。

理由：`EventSource` 是浏览器原生 API，演示页只要十几行代码，
能把注意力放在真正要验证的东西（流式是否生效）上。

### Consequences

- ✅ 演示页极简，一眼能看懂
- ⚠️ 阶段 8 做正式前端时**要换成 POST + fetch** ——
  用户的问题可能很长，URL 放不下
- 📌 **必须记住的两点**（都已经踩过）：
  1. **每个终止分支都要 `es.close()`** —— `EventSource` 在连接关闭后会
     **自动重连**，不 close 会导致同一个问题被反复提问、反复计费
  2. **事件名不能用 `error`** —— `EventSource` 有内置的 error 事件（连接层错误），
     同名会让业务错误和网络错误混在一起。所以用 `failed`

---

## 附录：被否决的方案汇总

| 决策点 | 选了 | 否决了 | 核心理由 |
|---|---|---|---|
| HTTP 客户端 | JDK 内置 | OkHttp / Apache HttpClient | 零依赖 + 原生 SSE |
| 流式接口形态 | 阻塞 + 回调 | `Flux` / `Stream<Chunk>` | `Stream` 的异常包装会丢失供应商信息 |
| 熔断器装配 | 自己 `@Bean` | 依赖自动配置 | 条件装配可能静默失效 |
| 熔断耗时口径 | TTFB | 整个流时长 | 长回答会被误判为慢调用 |
| 降级边界 | 只降「未吐字」 | 任何失败都降级 | 会拼出前后矛盾的回答 |
| 400 的处理 | 不降级不计熔断 | 当普通失败 | 会用一个自己的 bug 掩盖所有供应商 |
| 降级事件传递 | 显式参数传 `ModelCallTrace` | ThreadLocal | 推送线程不继承请求线程的 ThreadLocal |
| SSE payload | Map + JSON | 纯 String | 纯 String 中文会乱码 + 换行破坏分帧 |
| 成本缺失时 | 记 NULL | 按字符估算 | `qa_log` 只增不改不删，估算值会永久污染 |
| `list_active_at` | 手工 set | 依赖自动填充 | 自动填充只管 `updated_at` |
