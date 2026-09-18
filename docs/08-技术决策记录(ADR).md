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

## ADR-012 · Tika 用 3.3.2，不用最新的 4.0.0

**日期**：2026-09-18　**状态**：已采纳　**阶段**：3

### Context

阶段 3 要用 Apache Tika 做多格式文档解析。查 Maven Central，
最新版是 **4.0.0**（2026-08 发布），3.x 线最新是 **3.3.2**。
Spring Boot 3.5.16 的 BOM **不托管** Tika（grep 本地 BOM 确认命中数为 0），
版本必须自己写。

### Options

| 方案 | 优点 | 代价 |
|---|---|---|
| **3.3.2** | API 与网上绝大多数教程一致；中文资料多、踩坑少 | 不是最新大版本 |
| 4.0.0 | 最新 | 破坏性变更极多（见下） |
| 只引 `tika-core` + PDFBox/POI | 依赖树最干净 | 要自己装配 Parser 集合，且**没有 Markdown/HTML 支持** |

### Decision

**3.3.2。**

4.0.0 的破坏性变更清单（查官方迁移指南得到，不是推测）：

- **SPI 签名变更**：`Parser.parse` 的参数从 `InputStream` 改成 `TikaInputStream`，
  不再有 `InputStream` 重载；`Detector.detect` 的签名也跟着变了
- **配置格式从 XML 改成 JSON**，旧的 `TikaConfig` XML API 整个删除
- **Metadata 键全部加 `tk:` 前缀**，且 `set` 保留键会抛异常
- **默认开启 fork 解析**（子进程隔离），打包方式也从 fat jar 改成 zip 分发
- 直接调用具体解析器时，**嵌入文档会被静默跳过**（不报错、不抛异常、就是没内容）

本项目要的是「链路可逐行解释」和「踩坑少」，不是版本号最新。
而且 3.x 线仍在维护，不是 EOL。

### Consequences

- ✅ 文档、StackOverflow 答案、示例代码基本都能直接用
- ⚠️ 将来升 4.x 时上面每一条都要处理一遍
- 📌 **踩到的第一个坑**：`tika-parsers-standard-package` 的 POM 里
  `tika-core` 声明成 `<scope>provided</scope>`，而 **provided 不具有传递性** ——
  整包装了几十个解析器模块，唯独缺了核心引擎，编译直接报
  「程序包 org.apache.tika.parser 不存在」。
  **必须显式再引一次 `tika-core`**，且两者版本号必须严格一致（用 `${tika.version}` 属性统一）
- 📌 依赖体积：Tika 拉进来 22 个 `tika-parser-*-module` 加上
  PDFBox 3.0.8 / POI 5.5.1。实测**与 Spring Boot 的 BOM 无版本冲突**
  （`mvn dependency:tree` 里 `omitted for conflict` 命中数为 0）

---

## ADR-013 · 标题提取用三条策略，其中 PDF 那条最绕

**日期**：2026-09-18　**状态**：已采纳　**阶段**：3

### Context

路线图 3.3 要求「标题层级 + 定长 + 重叠窗口」切分。
没有标题层级就只能退化成定长切分，不同章节的内容会被切进同一个切片。
但 Tika 对四种格式的标题输出形态**完全不同**。

### 实测结果（照着 dump 出来的，不是推测）

| 格式 | Tika 输出的样子 | 提取方式 |
|---|---|---|
| `.docx` | 正文里直接内联 `<h1>` `<h2>` `<h3>` | **策略①** 读标签名 |
| `.pdf` | **正文里一个 h 标签都没有**。标题被抽成 `<body>` 末尾一个嵌套 `<ul>`（文档大纲），和正文完全分离 | **策略②** 从大纲建「标题文本→层级」映射表，再回正文按行精确匹配 |
| `.md` | 被识别为 `text/markdown`，但**Tika 3.3.2 根本没有 Markdown 解析器**（翻遍所有 tika jar 确认），实际由 `TextAndCSVParser` 兜底 —— 整个文件塞进**一个 `<p>`**，`#` 号原样留在文本里 | **策略③** 按行首模式判断 |
| `.xlsx` | sheet 名变成 `<h1>`，数据在 `<table><tr><td>` | sheet 名当一级标题，每行拼成一行文本 |

### Decision

三条策略并存，优先级从上到下。PDF 那条的做法：

**PDF 格式本身不存储「这是标题」这个信息** —— 它只记录「在 (x,y) 画一个
12 号字体的字符串」。标题和正文的区别只体现在字号上，而 Tika 的 XHTML
输出不带字号。所以能从 PDF 拿到结构信息的唯一来源就是**文档大纲**
（阅读器左侧那个目录树）。

但大纲只告诉你「有哪些标题、各是几级」，**不告诉你它们在正文的哪个位置**。
所以用「标题文本精确匹配」把两者关联起来：扫描正文每一行，
如果它恰好等于大纲里的某个标题，就认为它是标题。

### Consequences

- ✅ 四种格式都能提取出标题层级（实测：14 个标题 / 15 个 / 6 个 / 1 个）
- ⚠️ **已知失效场景**（诚实标注）：PDF 没有大纲时提不出任何标题，
  退化为纯定长切分。这是信息论上的限制，不是实现缺陷
- ⚠️ 正文里有一句话**恰好**等于某个标题文本时会被误判成标题 ——
  影响可控（只多切一刀，不丢内容）
- ★ **踩到的坑（极具迷惑性）**：Tika 生成的 PDF 大纲 HTML **不是良构的**，
  同样的层级关系会输出成两种嵌套形态：

  ```html
  形状 A：嵌套 <ul> 在 <li>【里面】
    <ul><li>七天无理由退货规则
      <ul><li>一、适用范围</li></ul>
    </ul>

  形状 B：嵌套 <ul> 是同级 <li> 的【兄弟】
    <ul><li>星辰 X1 智能手机用户手册</li>
      <ul><li>产品简介</li></ul>
    </ul>
  ```

  第一版只处理了形状 A。结果**两份 PDF 里一份完全正常、另一份的标题层级整个丢失**
  —— 而且不报错，只是悄悄退化成「一个标题 + 定长切分」，
  表现为检索质量变差，从日志里完全看不出原因。
  现在两种形状都处理，并且有专门的回归测试（`PdfOutlineTest`）
  用手工构造的 XHTML 把两种形状都钉死

---

## ADR-014 · 入库流水线不放在数据库事务里，用补偿删除

**日期**：2026-09-18　**状态**：已采纳（架构约束）　**阶段**：3

### Context

一份文档入库要经过「解析 → 切分 → 向量化 → 写库」四步。
最自然的写法是给整个流程加 `@Transactional`，失败自动回滚。

### Decision

**不加事务。失败时用「按 document_id 删掉这次写的切片」来补偿。**

理由是**向量化要调外部 API，一份几百片的文档要跑几十秒到几分钟**。
事务开着跨越这几分钟，意味着**一条 HikariCP 连接被独占了这几分钟**。
而连接池的 `maximum-pool-size` 只有 10 —— 4 个并发的入库任务
就能把池子占掉四成，再叠加正常的问答请求，**整个应用的数据库访问会集体排队**。

这是分布式系统里「不要用事务包裹远程调用」这条原则在单体应用里的翻版。

### Consequences

- ✅ 数据库连接不会被长时间占用
- ⚠️ 「④写库」这一步失去了原子性。用补偿删除弥补：
  任何一步失败就把这次写的切片按 `document_id` 全部删掉。
  **不删的话后果很具体**：检索只认 `kb_chunk.deleted = 0`、**不看文档状态**，
  于是「一份标着处理失败的文档，它的半截内容照样被检索命中」
- ⚠️ 补偿删除本身也可能失败（数据库挂了），这种情况只能靠日志告警 + 人工清理
- 📌 顺带确定了「重新入库」的语义：入库前先按 `document_id` 清场，
  所以重复入库是**替换**而不是累加

---

## ADR-015 · 表格行用 standalone 标记，不和相邻块合并

**日期**：2026-09-18　**状态**：已采纳　**阶段**：3　**来源**：**实测中发现**

### Context

切分器最初的设计是「同一个章节内的正文块合并成一个切片」。
这个假设对**散文段落**成立 —— 合并起来上下文更完整。

但入库一份 20 行的售后 FAQ 表（xlsx）之后，实测结果是这样：

```
chunk 0（467 字）：售后FAQ / 问题 | 答案 | 分类 / 退货需要多长时间 | ... / 退款多久到账 | ...
                   / 拆封了还能退货吗 | ... / 运费谁承担 | ... （共 13 条问答）
chunk 1（469 字）：... （另外 7 条）
```

### Decision

**给 `TextBlock` 加一个 `standalone` 标记，表格行标为 `true`，切分时按它分组。**

```
输入: [段落A][段落B][表格行1][表格行2][段落C]
分组: [A,B] [行1] [行2] [C]
```

规则只有一句：**standalone 的块自成一派，两边的普通块也不能和它合并。**

### Consequences

- ✅ 实测效果：FAQ 从 **2 个切片变成 21 个**，每条问答都成了独立的检索单元
- ✅ 散文段落的行为完全不变（默认 `standalone=false`）
- 📌 **为什么用布尔标记，而不是「把表格行伪装成标题」之类的技巧**：
  后者会让 `heading_path` 里出现「售后FAQ > 第 3 行」这种毫无意义的内容，
  还会被展示到引用来源里给用户看。
  **标记要表达真实语义，不能为了绕过一个实现细节而编造结构**
- 📌 这个问题的可怕之处在于**它不报错**：切片照样入库、照样有向量、
  检索照样能返回结果，唯一的症状是「答案质量莫名其妙地差」

---

## ADR-016 · 向量列从 String 改成 float[] + 自定义 TypeHandler

**日期**：2026-09-18　**状态**：已采纳　**阶段**：3

### Context

阶段 1 建表时 `KbChunk.embedding` 声明成了 `String` 占位，因为当时只建表、不读写向量。

### Options

| 方案 | 问题 |
|---|---|
| 保持 `String` | ★ **编译器拦不住任何错误**。传进去一个 512 维的文本、一段 JSON、甚至一句「待填」，都要等到运行时被 PostgreSQL 拒绝才知道 |
| `double[]` | 白白多占一倍空间和带宽 |
| **`float[]` + TypeHandler** | 要写一个类型处理器，还要记得加 `autoResultMap = true` |

### Decision

**`float[]` + `VectorTypeHandler`。**

写入路径三步：`float[]` → 拼成 `[0.1,0.2,...]` 文本 → 以「未指定类型」发给
PostgreSQL（靠 JDBC URL 的 `stringtype=unspecified`）→ PG 用 vector 类型
自己的输入函数解析。读取是反向的。

### Consequences

- ✅ 类型安全：维度不匹配在代码层面就能约束住
- ✅ 实测确认数据库真的在校验维度：写 512 维会被拒绝，
  报错信息是 `expected 1024 dimensions`。
  也就是说「维度是 1024」不是文档里写的，是**数据库在强制执行**
- ⚠️ **`autoResultMap = true` 不能漏**。漏了的症状极具迷惑性：
  **写入正常、查询也正常，就是查出来永远是 null**。
  为此专门写了一条集成测试，注释里写明了「这条测试存在的全部理由」
- 📌 `toLiteral` / `parse` 声明成 `public` 而不是 `private`：
  手写 SQL 做向量检索时也要把 `float[]` 转成 pgvector 字面量
  （`WHERE embedding <=> CAST(? AS vector)`），
  这个知识属于这个类，散出去就会变成两份可能不一致的实现

---

## ADR-017 · 数据库同步先渲染成 Markdown 落盘，走同一条流水线

**日期**：2026-09-18　**状态**：已采纳　**阶段**：3

### Context

知识库有两个来源（`kb_document.source_type`）：
文件上传（1）和数据库同步（2）。数据库同步要把
`after_sale_policy` 和 `product` 表的内容变成知识库文档。

### Options

| 方案 | 后果 |
|---|---|
| 数据库来源直接从内存字符串进流水线 | 入库要分叉成两条路径：一条从文件读、一条从内存读。**两套测试、两处可能不一致的状态处理** |
| **先渲染成 Markdown 落盘，再走标准流水线** | 多占一点磁盘（几百 KB 的文本文件） |

### Decision

**先渲染成 Markdown，用 `DocumentStore.saveText()` 落盘，然后走和文件上传
完全相同的路径。**

渲染成 Markdown 而不是纯文本，是因为这样 `#` 会被解析层的策略③ 识别成标题，
于是切分、向量化、写库的代码**一行都不用为数据源分叉**。

### Consequences

- ✅ 入库只有一条代码路径
- ✅ 附带收益：**可重放**。阶段 7 重新入库不用重新查源表，
  而且 `file_hash` 去重能直接生效（源表没改动的行不会被重复向量化）
- 📌 **商品描述刻意拆成四个二级标题**（基本信息 / 卖点 / 适用人群与场景 / 规格参数），
  这不是为了好看，是为了让**每个小节成为独立的检索单元**：

  | 用户问题 | 应该命中的小节 |
  |---|---|
  | 「这个手机多少钱」 | 基本信息 |
  | 「这个适合送长辈吗」 | **适用人群与场景** |

  如果整段挤在一起，向量会把「价格 3999」和「适合送长辈」平均成一个模糊的点，
  两个问题匹配到的都是同一坨内容，谁也答不准。
  **这正是 `product.suitable_for` 字段存在的意义** ——
  用数据建模解决检索问题，比事后调 prompt 更根本
- ⚠️ 实测发现渲染出来的小节普遍偏短（30~90 字），
  低于 `minChars=80` 的合并阈值。**判断是「不是问题」**：
  「规格参数 > 电池 / - 电池容量：5000mAh」这种 40 字的切片，
  是一个语义完整、指向明确的检索单元。
  真正该关心的不是「切片够不够长」，而是「一个切片里的内容是不是同一个主题」

---

## ADR-018 · 「检索结果稳定一致」成立在 ID 序列层面，不在分值层面

**日期**：2026-09-18　**状态**：已采纳　**阶段**：3　**来源**：**实测推翻了原有假设**

### Context

阶段 3 的验收标准 3：「同一个问题检索两次，结果稳定一致」。
看起来是句废话，但拆开看有**两个独立的环节**。

### 实测结果

**环节一：向量化是确定的吗？—— 不是。**

代码注释里原本写的是「同样的文本调两次 embedding 接口，返回的向量逐位相同，
这一点由模型服务保证」。**这个假设被实测推翻了**：

```
同一个问题连续调用 6 次 /v1/embeddings，比较返回向量的前 5 个分量：
  第 1 次  : [-0.034807, 0.014949, -0.012569, 0.00099,  -0.029749]
  第 2~6 次: [-0.034728, 0.014979, -0.012669, 0.000731, -0.02966 ]
→ 6 次调用出现了 2 种不同结果
```

差异量级约 **3e-4**。这**不是 bug，是 GPU 推理的固有性质** ——
分布式/并行计算里浮点加法不满足结合律，归约顺序不同结果就不同。

**环节二：排序是确定的吗？—— 靠代码保证，可以做到。**

HNSW 是**近似**索引，距离相同时返回顺序不保证（底层是图遍历，
顺序取决于遍历路径）。靠 SQL 里 `ORDER BY embedding <=> ?, id`
的兜底键解决。

### Decision

**判定「稳定」只看 ID 序列是否逐位一致，不看分值是否相等。**

- ✅ 命中的切片 ID 序列完全一致（实测 5 次逐位相同）
- ✅ 分值稳定到小数点后 3 位（同 5 次，最大漂移 3.767e-04）
- ❌ 分值**不会**逐位相同，别用 `equals` 比较两次检索的分数

### Consequences

- ✅ 端到端验收通过，而且**知道它为什么通过**
- 📌 **这个判据最初是写错的**：第一版自检代码用 `scoreRuns.equals()` 直接比
  double，于是永远报「不稳定」—— 而实际上链路是好的。
  **判据写错比没有判据更糟**，它会让人跑去查一个根本不存在的问题
- 📌 **兜底键没有牺牲索引效率**（用 `EXPLAIN` 验证过）：
  - 不带兜底键：`Index Scan using idx_kb_chunk_embedding`
  - 带兜底键：`Incremental Sort` → `Index Scan using idx_kb_chunk_embedding`
    （`Presorted Key` 利用索引已排好的前缀）

  也就是说**确定性和索引效率可以兼得**
- ⚠️ **什么时候这个漂移会真的咬人**：当两个切片的相似度差距小于 3e-4 时，
  它们的相对顺序可能在不同请求间翻转。本项目实际数据里相邻名次的差距
  在 0.002 量级（漂移的 6 倍以上），所以顺序稳定。
  但切片数量级上来、语义高度集中之后这个前提就不成立了，
  那时要靠结果缓存来保证可复现性
- ★ **这条发现的实用价值**：**别把「检索结果不稳定」当成 bug 去查**。
  先看 ID 序列是否一致 —— 一致就说明链路是好的，分数抖动属于正常范围

---

## ADR-019 · 定义任何 `ObjectMapper` Bean 都会静默关掉 Boot 的 Jackson 自动配置

**日期**：2026-09-18　**状态**：已采纳　**阶段**：3　**来源**：**运行时 500 报错**

### Context

新增的知识库状态查询接口一调用就 500：

```
Java 8 date/time type `java.time.OffsetDateTime` not supported by default:
add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310"
```

按提示去查依赖，发现 `jackson-datatype-jsr310:2.21.4` **明明就在 classpath 上**
（由 `spring-boot-starter-web` 传递引入）。所以不是「缺依赖」，是「模块没被注册」。

### Decision

**在 `JacksonConfig` 里显式声明一个 `@Primary` 的 `ObjectMapper`，
用 Spring Boot 的 `Jackson2ObjectMapperBuilder` 构建。**

### Consequences

**根因链条**（这是这个坑最值得记住的部分）：

```
① HttpClientConfig 定义了 @Bean("modelObjectMapper") ObjectMapper
                      ↓
② Spring Boot 的 JacksonAutoConfiguration.jacksonObjectMapper()
   标着 @ConditionalOnMissingBean —— 看到容器里已经有 ObjectMapper 了，
   于是【整个自动配置退让】，那个配好 JavaTimeModule 等一堆模块的
   primary mapper 从未被创建
                      ↓
③ Spring MVC 的 MappingJackson2HttpMessageConverter 是按类型注入 ObjectMapper 的，
   容器里只剩那个「模型协议专用」的 mapper，它没有 JavaTimeModule
                      ↓
④ OffsetDateTime 序列化失败
```

**可怕之处在于两件毫不相干的事被隐式绑在了一起**：
`modelObjectMapper` 是为「调外部模型 API」配的（丢弃 unknown 字段、序列化跳过 null），
而它顺手把「本应用对外返回的 JSON 长什么样」也接管了。
错误信息只会让人去查依赖，不会让人想到「是模型层的那个 Bean 干的」。

- ✅ 现在两套 mapper 职责显式分开：

  |  | `webObjectMapper` | `modelObjectMapper` |
  |---|---|---|
  | 服务对象 | 本应用对外的 HTTP 接口 | 调用外部模型的 HTTP 请求 |
  | 未知字段 | 报错（早暴露契约变更） | 忽略（模型会加非标准字段）|
  | null 字段 | 照常输出（契约要稳定）| 跳过（不传 ≠ 传 null）|
  | 时间类型 | JavaTimeModule | 用不到 |

- 📌 **同类陷阱的通用形式**：Spring Boot 里凡是标了
  `@ConditionalOnMissingBean` 的自动配置，只要你手动定义了同类型的 Bean，
  它就会**静默退让**（顶多一行 DEBUG 日志）。
  定义「某个专属用途」的 Bean 之前，先想想这个类型有没有被自动配置托管
- ⚠️ 顺带发现的第二个坑：`KbDocumentResponse` 里的 `finished()` 方法
  **不会被 Jackson 序列化**。Jackson 处理 record 时**只输出 record 组件**，
  写在 record 体里的普通方法不算属性。改成 record 组件才生效 ——
  而这一点最初是靠「响应 JSON 里没有这个字段」发现的，
  文档里写的「Jackson 会把它识别成 finished 属性」是错的

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
| 文档解析器 | Tika 3.3.2 | Tika 4.0.0 | 4.x 破坏性变更太多（SPI 签名、配置格式、Metadata 键全部改了）|
| PDF 标题来源 | 文档大纲 + 文本匹配 | 字号启发式 | Tika 的 XHTML 输出不带字号，拿不到 |
| 入库事务 | 补偿删除 | `@Transactional` | 向量化要几分钟，不能让数据库连接被占那么久 |
| 表格行切分 | `standalone` 标记 | 伪装成标题 | 会让 `heading_path` 出现「第 3 行」这种假结构 |
| 向量列类型 | `float[]` + TypeHandler | `String` | String 时编译器拦不住维度错误 |
| 数据库同步 | 渲染成 Markdown 落盘 | 内存字符串直送 | 入库路径会分叉成两条 |
| 「结果稳定」的判据 | 只看 ID 序列 | 连分值也比 | 向量化本身有 3e-4 漂移，比 double 会永远报不稳定 |
