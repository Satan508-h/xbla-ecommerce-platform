# CLAUDE.md — 休伯利安（XBLA）电商导购与售后 RAG 平台

> **这份文件是索引 + 硬性约定。Claude Code 每次对话都会加载它，所以它必须短。**
> 只放两类东西：**不能违反的约束**、**会静默咬人的坑（一行）**。
> 为什么这么做、备选方案是什么、实测数据 —— 全部在 `docs/` 下，按需查阅。
>
> ⚠️ **不要把解释性内容写回这里。** 判断标准：删掉它会不会让人写出静默出错的代码？
> 会 → 留一行 + 指向 docs；不会 → 只留在 docs 里。

---

## 一、项目是什么

面向电商场景（商品咨询、规格对比、促销政策、售后服务）的企业级 **RAG 智能问答平台**。
核心不是"能聊天"，而是**检索质量可量化、可优化、可复现**。

**当前阶段**：阶段 8（前端 + 部署）—— **8.1 ~ 8.6 / 8.8 已实现【并实测】✅ 2026-09-24**，
**8.7（内网穿透）未做**（选型已定 cpolar，但**没有开** —— 开它 = 把服务暴露到公网，是外向动作）。
★ 测试数 824 → **860**。前端在 `frontend/`（Vue 3 + Vite + Element Plus），部署在 `deploy/`。

★★ **阶段 9（Agentic RAG）进行中 —— 9.1 已完成并实测 ✅ 2026-09-25，测试数 860 → 870**。
9.2~9.6（检索门控 / 工具扩容 / 多轮澄清 / 个性化 / 离线+在线指标）见 `docs/10`。
★ 用户已拍板的边界：检索决策**复用分类那一次调用**（不新增往返）、个性化**从订单实时派生**、
在线指标**前端埋点 + 事件表**、工具轮流式**先只做一次推完**。

决策 `docs/08`（**ADR-086~090 是阶段 8 的，ADR-091 起是阶段 9 的**），部署手册 `docs/07-部署手册.md`（★ 验收记录在里面），
路线图与验收记录 `docs/10-开发路线图.md`，报告本体 `docs/11-评测报告.md`。
⚠️ **`docs/01` / `09` 两个文件还没写**（07 已在阶段 8 补上）。
★ 容器全栈**已经真的跑起来并验过**（13 个迁移从空 schema 建成、端到端问答走通、
SSE 走 Nginx 首字节 29ms）。验收命令与输出在 `docs/07` §五。
★★ **干净环境的库是【全空】的** —— Flyway 只建表结构，`SeedRunner` 挂在
`@Profile("seed")` 上（不是 local）。所以部署后是**两步**：
`bash scripts/seed.sh` → `python scripts/ingest.py`（顺序不能反）。
★★★ **阶段 7 的产出不是功能，是「可复算的数字」** —— 引用任何数字前**先读报告 §1 的噪声底**：
同配置三轮、159 题、配置真差异 = 0，**至少翻一格的题 13.2% ~ 16.4%**。
★ 实测**噪声不随日期漂移**（同会话 16.4% **不**比跨日 13.2% 小）——
所以「换个时间重跑」消不掉它，只能把结论跟它比大小。
**任何 A/B 结论必须先跟这个数比大小**，否则是在读噪声。
★★ 客户端测的 `总耗时ms` 噪声底是 **±230ms** —— 延迟差异只有**服务端那几列**能判。
★ 阶段 6（高可用）已收尾：100 并发无超卖无死锁、位置与 `ZRANK` 逐字相等、名额不永久泄漏。

---

## 二、技术栈与模型

| 层 | 技术 | 版本策略 |
|---|---|---|
| 语言/运行时 | Java（JDK 21 LTS） | 固定 |
| 框架 | Spring Boot 3.x | 以搭建当日最新稳定版为准 |
| ORM | MyBatis-Plus | 必须选支持 Spring Boot 3 的版本 |
| 数据库 | PostgreSQL + **pgvector** | 镜像 `pgvector/pgvector:pgXX` |
| 缓存/限流 | Redis + **Lettuce + 手写 Lua**（★ 不是 Redisson，见 `docs/08` ADR-075） | 镜像 `redis:7-alpine` |
| 文档解析 | Apache Tika 2.x | — |
| 熔断 | Resilience4j | `resilience4j-spring-boot3` |
| MCP | 手写 Server + 官方 Java SDK（Client） | SDK 已核实为 **2.0.1**（只认到协议 `2025-11-25`）。⚠️ 依赖要写 `mcp-core` + `mcp-json-jackson2`，**别用聚合包 `mcp`**（它会拖进 Jackson 3） |
| 前端 | Vue 3 + Vite + Element Plus | — |
| 编排 | Docker Compose | — |
| 评测 | Python + RAGAS | 独立脚本，非主服务 |

### 模型供应商（双供应商，配置驱动）

| 优先级 | 供应商 | 用途 | 模型 |
|---|---|---|---|
| P0 | DeepSeek 官方 | 对话生成（主力） | `deepseek-flash` |
| P1 | 硅基流动 | 对话生成（同模型换源） | `deepseek-ai/DeepSeek-V3.2` |
| P2 | 硅基流动 | 对话生成（兜底降级） | `Qwen/Qwen3.5-9B` |
| — | 硅基流动 | **向量化**（唯一来源） | `BAAI/bge-m3`（1024 维，免费） |
| — | 硅基流动 | **重排序**（唯一来源） | `BAAI/bge-reranker-v2-m3`（免费） |

> ⚠️ **动模型相关代码前先 `GET /v1/models` 核实**，以 `application.yml` 的
> `xbla.llm.models` 为准（本表曾滞后于配置）。上表 ID 于 **2026-09-19** 复核，`bge-m3` 确为 1024 维。
> ⚠️ **DeepSeek 官方不提供向量化和重排序** —— 这是双供应商架构的根本原因。
> ⚠️ **`deepseek-flash` 是「推理模型」**（推理 token 占输出 87%），响应含非标准字段 `reasoning_content`。
> ★ **`max_tokens` 给小了会返回空 `content`**，现象是「AI 不说话」而日志无异常。项目决策：不存也不返回 `reasoning_content`。

---

## 三、架构分层与包结构

```
com.xbla.rag
├── controller/    对外 HTTP 接口（薄，不写业务逻辑）
├── service/       业务编排
├── mapper/        MyBatis-Plus Mapper
├── entity/        数据库实体
├── dto/           请求/响应对象
├── config/        配置类（模型供应商、Resilience4j、线程池、Redis 等）
├── client/        ★ 模型接入层：LlmClient / EmbeddingClient / RerankClient（手写 HTTP）
├── rag/           ★ RAG 核心：解析、切分、召回、融合、重排、Prompt 组装
├── agent/         ★ 智能体层：意图路由、澄清反问、会话记忆
├── mcp/           ★ MCP Server（手写）+ Client（官方 SDK）
├── ratelimit/     ★ 高可用：名额表（ZSet + 租约）、手写 Lua、ZSet 队列、Pub/Sub、看门狗
└── common/        通用工具、异常、统一响应封装
```

**数据流**：`用户提问 → ★ 排队限流 → 意图识别 → [知识库检索 | MCP 工具调用] → 重排 → Prompt 组装 → LLM 生成 → SSE`
（★ 阶段 6 的排队层跑在**整条链路之前**，且在 `ChatService` **之外** —— 见 `docs/08` ADR-077；
详见 `docs/03-系统架构设计.md`）

---

## 四、硬性约定（不要违反）

### 架构约束

1. **不使用 Spring AI / LangChain4j 做 RAG 编排。** RAG 链路必须是手写的、可逐行解释的。理由见 `docs/08-技术决策记录(ADR).md`。
2. **所有 LLM 调用必须经过 `client/` 层的封装**，不允许在 service 里直接写 HTTP 请求。这样熔断、降级、计费、日志才能统一生效。
3. **`controller/` 层不写业务逻辑**，只做参数校验和响应封装。
4. **所有对数据库的写操作要能追溯到 `qa_log`**，因为评测数据来源于此。

### 安全约束

5. **API Key 绝不写进代码、绝不提交到 git。** 统一走环境变量或 `application-local.yml`（该文件必须加入 `.gitignore`）。
6. **`.gitignore` 必须在第一次 commit 之前就位。**

### 命名约定

7. 数据库表名、字段名全小写 + 下划线（`snake_case`）；Java 用驼峰（`camelCase`）。
8. 向量列统一命名 `embedding`，类型 `vector(1024)`（对应 bge-m3 的维度）。
9. 新加的每个模块，都要在 `docs/` 对应文档里补一段说明。

### 工作方式约定（用户明确要求）

10. **教学方式**：先讲原理 → 给代码 → 逐行讲解。**不留改造练习**，目标是尽快跑通。
11. **所有技术决策必须先问用户**，不允许自行决定后闷头实现。

---

## 五、文档索引

| 文档 | 内容 |
|---|---|
| `docs/00-项目总览.md` | 一句话定位、简历故事线、面试叙述脚本 ⚠️ **不进公开仓库**（`.git/info/exclude`） |
| `docs/01-产品需求文档.md` | ⏳ **还没写** |
| `docs/02-技术选型与术语词典.md` | ★ **每个技术的白话解释 + 优缺点**（看不懂术语先查这里） |
| `docs/03-系统架构设计.md` | 分层架构、端到端数据流、模块划分 |
| `docs/04-数据库设计.md` | 全部表结构、索引、pgvector 列设计 |
| `docs/05-检索与智能体设计.md` | ★ 意图树、双路召回、RRF、重排、会话记忆、摘要压缩 |
| `docs/06-评测体系设计.md` | 指标定义与公式、标注集规范、A/B 对比方法 |
| `docs/07-部署手册.md` | ★ **阶段 8 写好了**：拓扑、两条运行方式、验收清单、常见故障 |
| `docs/08-技术决策记录(ADR).md` | ★ **每个决策的备选方案与被否决原因**（面试利器） |
| `docs/09-面试问答准备.md` | ⏳ **还没写**（阶段 8/9 的活） |
| `docs/10-开发路线图.md` | 阶段划分、每阶段验收标准、**坑列表**、进度勾选 |
| `docs/11-评测报告.md` | ★ 阶段 7 的交付物本体（可复算）；附录 `docs/11-附录-逐题.md` |

> ⚠️ **01 / 09 两个文件不存在**（2026-09-24 核实；07 已在阶段 8 补上）。
> 索引里标了 ⏳ 而不是删掉，是为了让「该写但没写」和「写了但索引漏了」能分开 ——
> **别照着一个不存在的路径去找**。
> ⚠️ `docs/00-项目总览.md` **在磁盘上但不在公开仓库**（`.git/info/exclude`）——
> 它是简历讲稿。★★ **而 docker 不认 git 的排除**：
> `deploy/Dockerfile.web` 里是**逐个 COPY** 两份 `docs/11-*`，不是 COPY 整个 docs/。

---

## 六、常用命令

```bash
# 中间件（★ 只起 postgres/redis/pgadmin —— 这是本地开发那条路，行为没变）
docker compose up -d
docker compose ps
docker compose exec postgres psql -U xbla -d xbla_rag

# ★★ 全栈（中间件 + 应用 + Nginx）—— 部署演示那条路，【必须带 --profile full】
#    ⚠️ 应用容器【不】映射 8080，所以它和下面 `mvnw spring-boot:run` 能同时跑
#    ⚠️ 需要 .env 里额外填 DEEPSEEK_API_KEY / SILICONFLOW_API_KEY
#    ★ 已在 2026-09-24 实测通过，验收命令与输出在 docs/07 §五
docker compose --profile full up -d --build
bash deploy/make-htpasswd.sh                 # 生成 Basic Auth 口令（产物已 gitignore）
# ★★ 灌数据是【两步】，顺序不能反（坑 31）
bash scripts/seed.sh                         # ① 业务数据（商品/订单/券）
python scripts/ingest.py --url http://localhost -u xbla:<口令>   # ② 知识库（花钱）
#    ★ 探针也能打 Nginx 那一侧（验 SSE 有没有被缓冲）：
python scripts/probe_stage8.py --url http://localhost -u xbla:<口令>

# 后端启动（密钥从 application-local.yml 读，那个文件已 gitignore）
./mvnw spring-boot:run

# 前端（开发，5173，已配 /api 代理 → 8080）
cd frontend && npm install && npm run dev

# 跑测试（860 个）
# ★ 改了接口或方法签名后【必须先 clean】—— 不 clean 时 maven 报
#   "Nothing to compile" 并返回成功，然后拿【针对旧签名编译的旧 class】去跑。
#   ⚠️ 同一个坑 `./mvnw test-compile` 也有（见 docs/10 坑 12）。
# ★★ 阶段 6 起 `./mvnw test` 需要 docker 的 **redis**，不再只是 postgres。
#   ⚠️ 而且 Redis 没有事务回滚 —— `@Transactional` 对 Redis 测试完全无效，
#      测试必须自己 `@AfterEach` 清 key，或者用【唯一的 key 前缀】隔离。
./mvnw clean test
```

### 业务接口

```bash
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"question":"商品支持七天无理由退货吗"}'          # 非流式
curl -N -G localhost:8080/api/chat/stream --data-urlencode "question=你好"   # 流式
curl -s localhost:8080/actuator/circuitbreakers | python -m json.tool
# 浏览器演示页：http://localhost:8080/chat.html
```

### 调试探针（全部 `@Profile("local")`）

> ⚠️ 它们可以无条件消耗 API 额度，**绝不能暴露到生产**。
> 完整清单见 `docs/10`；这里只列最常用的。

```bash
# ── 不花钱的（优先用这些）──
curl -s localhost:8080/api/debug/kb/search-text-stats        # 分词覆盖率，missing 必须是 0
curl -s localhost:8080/api/debug/agent/intent-tree | python -m json.tool
curl -s localhost:8080/api/debug/agent/intent-prompt         # 分类 prompt 原文 —— 「分类不准」先看这个
curl -s localhost:8080/api/debug/agent/memory | python -m json.tool
curl -s "localhost:8080/api/debug/agent/memory?sessionNo=xxx" | python -m json.tool  # 摘要的接缝

# ── 花钱的（调模型）──
python scripts/probe_kb.py search 退货要几天         # ★ 中文走脚本，别直接 curl
python scripts/probe_kb.py retrieve 送长辈合适吗     # 完整召回链路中间输出
python scripts/probe_kb.py retrieve 退货要几天 --docTypes 2,4
python scripts/probe_memory.py --rounds 20           # 5.6 验收：20 轮后还记得第 1 轮吗
curl -s -G localhost:8080/api/debug/agent/classify --data-urlencode "q=退货要几天"
curl -s -G localhost:8080/api/debug/llm/chat --data-urlencode "q=你好" \
  --data-urlencode "model=deepseek-flash"     # 直连某一档；传 auto/不传 = 走完整降级链

# ── 阶段 5.7：MCP ──
python scripts/probe_mcp.py                          # ★ 完整握手 + 工具调用 + 越权，12 项判定
curl -s localhost:8080/api/debug/mcp/tools | python -m json.tool   # 模型看到的工具清单

# ── 阶段 5.8 / 5.9：MCP Client + 工具调用 + 结构化硬数据 ──
python scripts/probe_tool.py                        # ★★ 真实验收：30 项，会调模型（花钱）
curl -s "localhost:8080/api/debug/mcp/client?userId=8" | python -m json.tool  # ★ 模型实际收到的报文
curl -s "localhost:8080/api/debug/mcp/qa-log?traceId=xxx" | python -m json.tool  # 看 tool_calls 落库没有
# ★ 直调工具看它的返回原文（走完整 MCP 链路，和模型拿到的一模一样）——【不花钱】
curl -s "localhost:8080/api/debug/mcp/call?tool=query_my_coupons&userId=8" | python -m json.tool
curl -s "localhost:8080/api/debug/agent/intent-tree" | python -m json.tool   # 看 structuredFactLeaves

# ── 阶段 6：排队限流（★ 全部不花钱）──
python scripts/probe_ratelimit.py                    # ★★ 29 项断言，八组，含 100 并发压测
python scripts/probe_ratelimit.py --real             # ★ 外加真实 10 并发（花钱）
curl -s localhost:8080/api/debug/ratelimit/state | python -m json.tool   # 名额/队列/本机登记/线程池/信号
curl -s localhost:8080/api/debug/ratelimit/slots | python -m json.tool   # ★ ZCARD 偏大时：多出来的是【谁】
curl -s "localhost:8080/api/debug/ratelimit/trace?traceId=xxx" | python -m json.tool  # 一个 id 在四个结构里的处境
curl -sN "localhost:8080/api/debug/ratelimit/fake-stream?holdMs=3000"    # ★ 走完整排队链路但不调模型
curl -s -X POST "localhost:8080/api/debug/ratelimit/leak?permits=8"      # 造「占着但没人续期」的僵尸
curl -s -X POST localhost:8080/api/debug/ratelimit/reset                 # 清空 4 个 key + 本机表 + 信号计数
docker compose exec -T redis redis-cli ZCARD "xbla:rl:{chat}:slots"      # 直接看 Redis

# ── 阶段 7：评测（★ 除 eval_run 外全部【不花钱】）──
curl -s -X POST localhost:8080/api/debug/eval/reload?set=stage7 | python -m json.tool
curl -s "localhost:8080/api/debug/eval/report?runId=20260922-stage7-run2" | python -m json.tool  # 全部指标（纯函数）
curl -s localhost:8080/api/debug/eval/config | python -m json.tool   # ★ 配置快照，从 Environment 取不是读 yml
python scripts/eval_run.py --set stage7 --repeat 3      # ★ 跑题，串行 + 对账（花钱）
python scripts/eval_ragas.py --run <id>                 # RAGAS（走 .venv-eval/，慢，约 2 小时/43 题）
python scripts/eval_ab.py --a <run1> --b <run2>         # A/B + 翻转矩阵
python scripts/eval_ab.py --selftest                    # ★ 11 项口径自检
python scripts/eval_report.py --selftest                # ★ 34 项口径自检
# ★★ 三个标志【互不叠加】，且 --refresh / --coverage 是【只读】的（不写 docs/11-*）：
#    标志拼接会静默短路 —— `--refresh --selftest` 只跑自检，一个字节都不刷（docs/10 坑 17）
python scripts/eval_report.py --refresh                 # 只从端点刷 report.json，不写交付物
python scripts/eval_report.py --coverage                # 只查漏，不写交付物
python scripts/eval_report.py                           # ★ 只有【不带标志】这一次写 docs/11-*
python scripts/eval_intent_probe.py                     # 高重复意图探针（不经过澄清闸门）

# ── 阶段 8：前端与部署（★ 前 3 项会调模型，其余【不花钱】）──
python scripts/probe_stage8.py                # ★★ 33 项，含【打印 SSE 原始字节】+ SSE 时序
python scripts/probe_stage8.py --no-model     # 跳过调模型的 3 项
python scripts/probe_stage8.py --url http://localhost -u 用户:口令   # ★ 打 Nginx 那一侧
#   ★ 它验的正是前端解析器的依据：event:/data: 之间没有空行、冒号后没空格、
#     每帧以空行结束、done.answer 是 null、refs 是真数组
#   ★★ 而【第 3 项】是验收标准 3 的判据：它按时间量 delta 的到达分布 ——
#      被 proxy_buffering 攒起来时收到的字节一模一样，只有时序不同。
#      ★ 实测：首字节 29ms / 总 4291ms / 185 帧、首末间隔 740ms = 在流
curl -s localhost:8080/api/chat/sessions | python -m json.tool          # 会话列表（★ 已排评测流量）
curl -s "localhost:8080/api/chat/trace/<traceId>" | python -m json.tool # 技术面板的数据
curl -s localhost:8080/api/status/ratelimit | python -m json.tool       # ★ 生产也存在的只读状态
#   ★ 注意 /api/status 和 /api/debug 的区别：后者 @Profile("local")，公网上必须 404

# ── 一次性 / 重建 ──
python scripts/generate_corpus.py                    # 生成仿真语料（依赖 reportlab python-docx openpyxl）
curl -s -X POST "localhost:8080/api/kb/documents/scan"    # 批量灌（异步）
curl -s -X POST "localhost:8080/api/debug/kb/reindex?force=true"
curl -s -X POST localhost:8080/api/debug/eval/reload      # 重解析评测集锚点
python scripts/eval_baseline.py --out eval_results/baseline-YYYYMMDD
```

**用环境变量覆盖配置跑 A/B**（Spring 的 relaxed binding 认这个，不用改文件）：

```bash
SPRING_APPLICATION_JSON='{"xbla":{"chat":{"history":{"enabled":false}}}}' ./mvnw spring-boot:run
SPRING_APPLICATION_JSON='{"xbla":{"chat":{"history":{"max-turns":4}}}}'   ./mvnw spring-boot:run
#                                     ↑ 窗口调小 = 10 轮就能触发摘要压缩（省钱的机制验证）
```

---

## 七、★ 改代码前必看的约定

> 一行一条。**都会被违反而没有任何报错**，所以列在这里。为什么这么做见括号里的文档。

### 数据与配置

- ⚠️ **新增语料文件必须登记进 `data/corpus/manifest.yml`**，否则 `doc_type` 静默用兜底值。
  词表 `1商品详情 2售后政策 3促销规则 4FAQ 5说明书`，标错的症状是「某一类查询永远返回空」。
- ⚠️ **`generate_corpus.py` 的产物必须字节确定**（时间戳已钉死）。产物一变，入库去重静默失效、重扫会重复灌并**真的花向量化钱**。（ADR-028）
- ★★ **`SeedRunner` 的幂等粒度是【按表】；新加的随机流必须用【自己的 `Random`】** ——
  `Random` 是位置依赖的，而按表幂等让「全量灌」和「只补一张表」成了两条路径，
  共用一条流会让**同一份代码产出两套数据**（症状是「我这儿 3 张券、你那儿 2 张」）。
  ⚠️ **别去统一改老方法** —— 那会改掉所有现有数据，包括已向量化的 1652 条切片。（ADR-074）
- ⚠️ **`user_coupon` 的种子数据是 5.9 才补上的**（之前 `SeedRunner` 注释里写了它、代码里没有）。
  `product.name` **没有唯一约束**，实测 200 个商品里 17 组重名 —— 它是**真实电商的常态**，不是数据 bug。
- ⚠️ **V1–V5 迁移一个字都不能改**（`validate-on-migrate: true`，改了起不来）。新改动一律新增 Vn。
- ⚠️ **意图树缺文件是【启动即崩】，语料清单缺文件是【WARN 回落】。** 这个不一致是刻意的。（ADR-034）
- ★ **`data/eval/baseline-questions.yml` 里 `intent` 必填**（存**叶子码**），缺了 `reload` 直接失败。

### 检索与评测

- ★★ **评测指标只统计 `qa_log.status = 1`。** `2`=模型链路失败，`3`=澄清反问（没生成也没失败）。
  常量是 `QaLog.STATUS_SUCCESS / STATUS_FAILED / STATUS_CLARIFY`。
- ★ **`retrieval_detail` 是 6 段**（`vector_hits`/`keyword_hits`/`fused`/`reranked`/`final_top_k`/`filter`），
  和调试接口是**同一个纯函数**产出的，永不漂移。（ADR-045）
- ★ **检索失败不影响问答**（退化成裸聊）；`status=2` **只表示模型链路失败**。
- ★ **命中的 ID 序列稳定，但分值有约 3e-4 漂移**（GPU 浮点归约）。别把「结果不稳定」当 bug 查，先看 ID 序列。
- ⚠️ **`#{docTypes} IS NULL` 会让 PostgreSQL 报 `could not determine data type of parameter $N`。**
  必须写 `CAST(#{docTypes} AS int[]) IS NULL` —— 那层 CAST 唯一的用途是给 PG 类型线索。
- ★★ **评测必须【串行】跑**（`eval_run_id` 那一批）。并发会让 `queue_ms` 混进延迟分位数 ——
  实测数字照样印出来、照样好看，**只是它量的不再是那条链路**。（`docs/06` §4.1）
- ★ **延迟是【五段】，且它们不相加等于 total。** 两个结构性问题：
  `rerank ⊂ retrieval` 是**包含关系**不是并列；而**意图分类是一次 0.5~2.5 秒的模型往返**，
  它进了 `total` 却**不在任何一段里**（p50 实测 823ms，就叫「未归类」）。
  ⚠️ 别把「检索慢」和「分类慢」并成一格 —— 两者的修法完全相反。（ADR-083）
- ★★ **评测标记会在某些路径上被静默丢掉。** `CallContext` 有 **≥4 个构造点**，
  漏一个就**编译通过、无日志**，评测流量伪装成真实用户。
  ★ 尤其 `rateLimitedLog`（被排队拒掉那条路，**绕过 `baseLog`**）也要写。
  所以跑题器**必须对账**：每个提交的 `traceId` 都要能查到且 `eval_run_id` 相符，否则整轮作废。（ADR-081）
- ★★ **`QaLogMapper.selectByEvalRun` 是【显式列清单】，它和服务的 getter 之间没有强制同步** ——
  「加一个新读取忘了加列」已经坏了**两次**（7.5 漏 `question`、7.6 漏 `prompt_tokens`/`completion_tokens`）。
  症状是**一个看起来合法的 0**（null 被 `!= null` 挡掉），**不报错**。
  改它之前把两边的字段清单拉出来逐个对照；`QaLogMapperProjectionTest` 会拦住漂移。
  ★ 免费判据：`prompt_tokens + completion_tokens == total_tokens`（全库实测 0 例外）。（`docs/10` 坑 18）

### 智能体层

- ★★ **三类非业务角色互斥**：`BUSINESS` / `OUT_OF_SCOPE`（不是我的业务）/ `CLARIFY`（是我的但没说清）。
  混起来用户会拿到错误回答 —— 问「那个怎么样」会收到「我只处理商品导购与售后问题」。
- ★ **澄清路径短路**：不检索不调模型，所以 `provider`/`cost`/`references`/`retrieval_detail` **全是 NULL** ——
  那是「没有发生」的诚实表达。⚠️ 别传空对象，空 trace 和「检索跑了但没召回」序列化出来逐字相同。（ADR-041）
- ⚠️ **`OUT_OF_SCOPE` 仍然走检索 + 生成**（树里标的是 `retrieval: NONE`，代码只对 `CLARIFY` 短路）——
  **已知的不一致**，见 `docs/05` §9.3 ⑩。
- ★★ **`doc_types: []` = 不限制**（不是「什么都不匹配」）。解读成不匹配会让三个工具意图**退化成裸聊**，
  而裸聊会**编一个订单状态出来**。（ADR-044）
- ★★ **过滤与否的判据是「池子绝对大小 ≥ `vector-top-k`」，不是「收窄倍数」** ——
  实测倍数 1.1×~328×，而**倍数越大越危险**。（ADR-043）
- ⚠️ **`temperature = 0.0` 不等于确定性。** 实测同一问题 8 次出现 7:1 分裂，单次准确率有 **±5%** 波动。（`docs/06` §1.4）
- ⚠️ **`xbla.agent.intent.max-tokens` 绝不能给小**（推理模型吃光额度 → 空 content → 「分类永远失败」而日志无异常）。
- ★★ **`intent-fewshot.yml` 和 `intent-tree.yml` 的 `examples` 不能合并** —— 后者与 20 道评测题
  **有 18 条逐字相同**，合并会让准确率测的是「照抄能力」。由 `IntentFewShotTest` 结构性强制。（ADR-036）
- ⚠️ **改完意图树跑 `IntentTreeConsistencyTest`**（gold `doc_types ⊆ declared`，当前 20/20）。

### 会话记忆与摘要（5.5 / 5.6）

- ★★ **历史只喂给生成，不喂给分类。** 这条不能动 —— 5.2 的 95% 和 5.4 的 20/20 都建立在
  「分类器的输入只有这一句话」之上。（ADR-046）
- ★★ **读记忆必须在 `saveUserMessage` 【之前】。** 反过来历史里会有本轮的提问，
  模型收到**两条一样的用户消息**，而日志和落库数据两边各自都是对的。（ADR-047）
- ★★ **孤儿用户消息要丢【末尾全部】，不是只丢最新那一条。** 只丢一条的话，
  **连续两次失败之后会话永久答不出话**（连续两条 user → 400 → 不降级）。（ADR-049）
- ★★ **摘要游标锚在「窗口起点 - 1」，宁可重叠绝不空洞。** 锚点是
  `ConversationMemory.windowStartId` **单一出处**。（ADR-048）
- ★★ **摘要压缩走异步单线程池**（最慢实测 41.5 秒；单线程是正确性要求，且 `maxPoolSize` 也要锁死）。（ADR-050）
- ★★ **摘要的主角是用户不是助手**（助手的回答可再检索，用户的预算/用途/型号不可再生）。
  ⚠️ 长度**没有完全压住**，稳定在 900–1100 字振荡，见 `docs/05` §9.6 ⑤。
- ⚠️ **`chat_summary` 两列「有列不用」**：`summary_level` 恒为 1、`token_count` 恒为 NULL。
- ⚠️ **摘要的花费不进 `qa_log`**（它的语义是「一次问答」）—— 第二个显式例外（第一个是 4.8 基线不写库）。
- ⚠️ **`history.enabled` / `summary.enabled` 关掉时什么都不读**（不是「读了不拼」）——
  否则阶段 7 分不清「没开」和「开了但是空的」。

### MCP（5.7 Server / 5.8 Client / 5.9 三个工具）

- ★★ **身份只能来自 `McpToolContext`，不能是工具参数。** 工具参数是**模型填的** ——
  加一个 `user_id` 参数就是**模型可控的越权入口**，一段提示注入就够了。（ADR-054）
- ⚠️ **`X-Xbla-User-Id` 不是认证**（明文未签名）。做到的是「身份不进模型的可控范围」，
  另一半需要 OAuth。**别在文档里含糊过去。**
- ★★ **工具的参数名只能写一次**（一个 `static final ToolField` 常量）——
  schema 生成和取值都走它。两处各写一份的漂移是**静默**的。（ADR-057）
- ★★ **`isError` 的判据是「工具有没有给出答案」，不是「答案是不是空的」。**
  「查无此单」是 `isError:false`；标成 `true` 会让模型去为系统故障道歉。（ADR-056）
- ★★ **协议版本是 `2025-11-25`，跟 SDK 对齐不跟规范仓库对齐**（规范已有 2026-07-28，
  但 SDK 2.0.1 不认识）。升级 SDK 时反编译 `ProtocolVersions` 重新核实。（ADR-053）
- ⚠️ **`Map.copyOf` / `Map.of` 拒绝 null 值** —— 而 JSON Schema 的可选字段
  合法地就是 null。工具体验里要包结构化输出时用 `Collections.unmodifiableMap(new LinkedHashMap<>(…))`。（ADR-058）
- ⚠️ **`Origin` 头缺席时放行**是刻意的（rebinding 必然由浏览器发起，而它总会带 Origin）。
  别「顺手改成拒绝」—— 那只会挡住官方 SDK。（ADR-055）
- ⚠️ **JSON-RPC 出错时 HTTP 仍是 200**；只有「身份缺失」和「会话失效」用 401/400。
- ⚠️ **通知（`notifications/initialized`）必须回 202 且响应体为空** —— 它没有 id。
- ⚠️ **`user_coupon` 表是空的，`app_user.id=1` 不存在。** 5.9 做优惠券工具前先补种子数据。
- ★★ **线格式的 `tool_calls` 是【嵌套】的**（`name` 在 `function` 里），领域对象是平的 ——
  **收发两个方向都要映射**（`WireToolCall`）。少一层：收→`name` 静默变 null；发→`422 missing field type`。（ADR-061）
- ★★ **DTO 的形状，只有真的序列化/反序列化过一次才算验证过。** 桩造的对象验证不了它自己。（ADR-061）
- ★★ **工具决策轮的「空正文」是成功**，判据用 `hasAnyContent()`。实测连续 5 次 `content` 全是空串。（ADR-060）
- ★★ **`reasoning_content` 必须原样带回第二跳**（丢了 400，不降级）。★ **「不存」不等于「不传」。**（ADR-060）
- ★★ **`tool_call` 的 id 和参数只能原样搬运**（改写会 400）。两家的 id 格式完全不同。（ADR-060）
- ★★ **工具失败一律降级成「工具结果」喂回模型**，不让问答失败。⚠️ 请求的**构造**也要在包装网里。（ADR-062）
- ★★ **工具意图【不检索知识库】**（判据是意图树的 `retrieval` 字段）。跑了会让模型编一个订单状态出来。
- ★ **多轮合并：用量/耗时/成本【累加】，路由【覆盖】，事件【追加】。** ⚠️ 累加是三态逻辑，
  写成「任一方 null 返回 null」会让 `qa_log.cost` **恒为 NULL**。（ADR-063）
- ⚠️ **第二跳末尾不能有用户提问** —— 多一条 user 会让模型再调一次工具，像「模型陷入循环」。
- ⚠️ **身份缺席时不是 401**，只有工具那条路回一句实话。全局 401 会打断演示页和所有 curl。（ADR-065）
- ★★ **售后政策【不是 MCP 工具】** —— `AFTER_SALE` 是 `retrieval: KB`，模型在那个意图下
  **拿不到任何工具**。它走 `structured_facts: POLICY` 的结构化注入。（ADR-067）
- ★★ **`structured_facts` 是【叶子】粒度，且【不参与分类】**（`classificationTargets` 只看 `retrieval`）
  —— 所以加它对 5.2 的准确率基线**零影响**。⚠️ 非 KB 的叶子声明它会**启动即崩**。（ADR-068）
- ★★ **硬数据只带天数，不带 `conditions`** —— 后者**已经在知识库里**（实测 12 条切片）。（ADR-069）
- ★★ **硬数据拼进【固定段】，检索为空时它仍然在。** 那正是最需要它的时刻。（ADR-070）
- ★★ **`query_inventory` 只匹配商品名**，不匹配类目/品牌 —— 品类词命中几十个再返回前 3 个，
  是**按 id 排的随机结果**，却看起来像答案。（ADR-071）
- ★★ **商品名【会重复】**（实测 200 个里 17 组、35 个重名，`name` 上没有唯一约束）——
  所以正文**总是**带 `product_no`。⚠️ 「总是」是要点：正文形状不能随数据变。（ADR-072）
- ★★ **券「可用」= `status == 1` ∧ 没过期**，不是一个条件。⚠️ 而 `expired_at` 为 **null 视为不过期**
  （`null = 已过期` 会把一张**真券藏起来**，用户无从发现）。（ADR-073）
- ★ **`GET /api/debug/mcp/call?tool=&userId=&args=`** 直调工具（**不花钱**）。
  它走**完整 MCP 链路**，拿到的和模型拿到的一模一样 —— 直接调 Bean 会跳过 schema 校验和身份注入。
  ★ 它让「模型答错了」和「工具给的就是错的」能分开，而这两件事的修法完全相反。
- ★★ **判据：先看工具说了什么，再看模型说了什么，然后比较。** 「回答里有某个词」会被模型的
  措辞绑架（5.8 的「没找到 → 没查到」）；「回答里的东西**工具确实说过**」不会。

### 排队限流（阶段 6）

- ★★★ **续期【不能创建】名额** —— `renew.lua` 里的 `ZSCORE` 判定不能删。
  普通 `ZADD` 会把已释放的名额**复活**（成员不存在时它创建），后果是**容量静默变少**
  —— 实测 8 个名额掉到 5 个，**没有异常、没有日志、没有指标**。（ADR-076）
- ★★★ **名额的释放在 `answer-` 线程的 `finally` 里，不在 `admit` 返回时** ——
  在那里释放就是「边跑边放名额」，直接超卖，且**不会报错**。（ADR-075）
- ★★ **排队必须在 `ChatService` 之外** —— 插在 `saveUserMessage` 之后会留下孤儿用户消息，
  **连续两次失败后那个会话永久答不出话**（ADR-049 的成因）。（ADR-077）
- ★★ **Pub/Sub 是优化，轮询是正确性来源** —— `await` 永远受 `poll-interval` 限制，
  所以消息丢了只是慢一点。**别把订阅成功当成功能可用的前提。**（ADR-078）
- ★★ **`RedisMessageListenerContainer.start()` 在订阅失败后是静默 no-op**（`started` 不重置）
  —— 所以每次重建都用**全新容器**，不要复用。（ADR-078）
- ★★ **`queue-` 线程池的 `queueCapacity` 必须是 0**，它不是调优参数 ——
  改成非 0 会让等待任务静止在执行器队列里，**没人能推位置 → 用户看到空白页**，且不报错。（ADR-077）
- ★★ **`qa_log.queue_ms` 【不是】「哪一种拒绝」的判据** —— 实测 `0` 在「队列满」和
  「池满」下都出现过。四种拒绝各自对症一个**不同的旋钮**，判据只能是 `error_msg` 的原因串。（ADR-080）
- ★★ **`acquire.lua` 里那行 `ZREMRANGEBYSCORE` 不能省** —— `ZCARD` 包含已过期的僵尸，
  只靠定时任务的话两次任务之间会**误判「满了」**。
- ★★ **重抢时【保留原 score】**（`ZSCORE == false` 才 `ZADD`）—— 换新序号会让用户
  看到自己从「前面 2 人」变成「前面 7 人」。（ADR-075）
- ★ **队列的 score 是 `INCR` 单调序号，不是毫秒时间戳** —— 同毫秒入队时
  `ZRANK` 会退化成 UUID 字典序（= 随机）。（ADR-075）
- ★ **释放【不】分配名额**（不给队首「提拔」）—— 队首可能已经断了，
  提拔它 = 名额被幽灵占着直到 TTL，直接威胁「无死锁」。代价是不保证 FIFO，这是刻意的。（ADR-075）
- ★ **客户端断开是一个正常事件**，不该落到兜底 handler（一次关页面 = ERROR + 60 行堆栈 +
  「异常处理器自己又抛了」）。同 `ExecutionException` 那类错误。（ADR-080）
- ★ **探针的 `leak` 端点刻意【不】登记进 `LocalPermitRegistry`** ——
  登记了就会被同进程心跳续期，于是「测试自己让自己通过」。（ADR-076）
- ★ **`/reset` 之后 `queue-` 池不会立刻空** —— 在途的等待者要靠下一次重试才发现名额空出来，
  大约 5 秒。**下一组测试必须等它排空**，否则整组会被「池满」拒掉（实测误报过一次）。

### 前端与部署（阶段 8）

- ★★★ **流式路径下 `ChatAskResponse.answer` 恒为 `null`**（`ChatServiceImpl.buildStreamResponse` 里写死的）。
  正文**只在 `delta` 事件里**。前端若写「收到 `done` 就 `content = payload.answer`」，
  **每一条回答都会在完成的那一刻整条消失**。⚠️ 非流式那条路 `answer` 是正文 —— 一个字段两条路两个含义。
- ★★ **会话列表必须排除评测流量**（4022 / 4111 是评测建的）。判据：
  `NOT EXISTS (qa_log WHERE session_id = s.id AND eval_run_id IS NOT NULL)`。
  ⚠️ 但**单会话消息接口故意不排** —— 那是精确键查找，回 404 会让人以为数据丢了。（ADR-086）
- ★★ **`/api/debug/**` 是 `@Profile("local")` 的**（7 个控制器，含 `POST /leak`、`/reset` 这类**写操作**）。
  对外要用的只读端点必须**新写**，且判据是**白名单**不是黑名单。（ADR-087）
- ★★★ **`application.yml` 里 `spring.profiles.active: local` 是【默认值】** ——
  容器化必须显式 `SPRING_PROFILES_ACTIVE=prod`，否则那 7 个 debug 控制器跟着公网一起暴露。
  ⚠️ profile 是**替换不是追加**：切到 prod 后 `application-local.yml` 的密钥不再加载。
- ★★ **`selected`/`result` 里的 JSONB 往返会改掉键序和空白**（Postgres 按键名长度重排）——
  **字节比较是非法判据**，只能比语义。（坑 22）
- ★ **PostgreSQL 的 `now()` 是【事务开始时间】** —— 同事务里插入的行时间戳完全相同。
  `ORDER BY 时间` 必须再跟一个单调键（`id`），否则顺序未定义。（坑 23）
- ★★ **`jsonPath(...).exists()` 分不清「键不存在」和「键存在但是 null」** ——
  测「字段在不在」要用 `JsonNode.has()`。（坑 24）
- ★★ **Compose 会插值【所有】服务，不管 profile** ——
  `:?` 必需变量写在 compose 上会让「只起中间件」也失败。校验要放在**消费它的那一层**。（坑 27 / ADR-088）
- ★★ **`.dockerignore` 里父目录被排除后，里面的文件用 `!` 放不回来** ——
  要按**名字**排除（`docs/00-*.md`），不能写 `docs/` 再想放回 `docs/11-*`。（坑 28）
- ★★★ **容器里的 Maven 不读你本地的 `~/.m2/settings.xml`**（那个文件在用户主目录，COPY 不进去）——
  于是容器直奔 `repo.maven.apache.org` 而国内连不上，**宿主机却能构建**。
  修法是 `deploy/maven-settings.xml`（只有阿里云那一个 URL，无凭据）+ `-s`。
  ★ 报错指向**网络**，会把排查带偏 —— 真正缺的是一个文件。（坑 29）
- ★ **构建脚本里别加 `-q`** —— 它会把「哪个依赖没下下来」一起吞掉。（坑 29）
- ★★★ **兜底 `@ExceptionHandler(Exception.class)` 会把 404 变成 500** ——
  Spring Boot 3.2 起未匹配的路径抛 `NoResourceFoundException`，兜底会接走它。
  本项目**每一个 404 都曾被渲染成 500**（阶段 8 才发现，因为那之前没有「必须 404」的判据）。
  已加显式 handler → 404 + DEBUG 级日志。（坑 30）
  ★ 判据：**任何想要特定状态码的异常都必须显式注册** —— 兜底 handler 的代价。
- ★★★ **`SeedRunner` 是 `@Profile("seed")`，不是 local** —— 干净环境的库**全空**。
  部署后必须 `bash scripts/seed.sh` 再 `python scripts/ingest.py`，**顺序不能反**
  （知识库要从业务表同步出商品和售后政策文档）。（坑 31）
- ★★ **管道后的 `$?` 是最后一个命令的退出码** —— 加 `grep` 就测不到真退出码了。
  同「别信 HTTP 200」那类判据错位。（坑 32）
- ★★ **`.gitignore` / `.git/info/exclude` 管不到 docker** —— 三套机制各自配各自的。
  `docs/00` 靠 git 的排除不进公开仓库，但它**照样在磁盘上**，容器文件系统里**没有 git**。
- ★★ **应用容器【不】映射 8080** —— 宿主机那个端口留给 `./mvnw spring-boot:run`。
  阶段 7 的 `eval_run.py` / `probe_*.py` 全部假设 `localhost:8080`，映射了就把那条链挤掉了。（ADR-089）
- ★★ **报告放行的白名单有三处，必须一致**：`deploy/Dockerfile.web`（COPY 哪两个）、
  `deploy/nginx.conf`（注释里点名）、`frontend/vite.config.js`（`PUBLIC_DOCS`）。
  ⚠️ 开发期如果比生产宽松 = 本地能开、上线 404；更严 = **本地测不出来、上线才发现泄露**。（ADR-090）
- ★ **前端没有 Pinia / axios** —— 全部用原生 `fetch`（流式非 `fetch` 不可，那就统一一套）。
  共享状态只有一处，一个 `reactive` composable 就够。
- ★ **SSE 解析器在 `frontend/src/api.js`**，手写的（ADR-011）。
  三件必须做对的事：`\n\n` 分帧、`TextDecoder` 带 `{stream:true}`（**中文会被切在 chunk 边界**）、
  服务端事件名是 `failed` 不是 `error`。

### 身份与 Agentic RAG（阶段 9）

- ★★★ **`askStream` 的三参版本已被【删除】、换成四参** `(request, sink, userId, ctx)` ——
  **别再加回重载**。加重载会让「忘了传身份」编译通过，而症状是工具题在 SSE 上
  **静默降级成普通 KB 问答**（模型拿通用规则编一个订单状态）。忘了传必须是编译错误。（ADR-091）
- ★★★ **身份写进 `chat_session.user_id` 之前必须核对 `app_user`** —— 那一列上有 FK
  （`fk_chat_session_user`），而 `X-Xbla-User-Id` 是**明文未签名**的。
  不核对 = 任何人发一个 `X-Xbla-User-Id: 999999` 就能让**每次对话 500**，且报错里
  一个字都不提那个头。查不到就记 NULL（= 匿名，是正确答案）。（ADR-091 / 坑 33）
- ★★ **`qa_log.user_id`（请求粒度）和 `chat_session.user_id`（会话粒度）不是同一个数。**
  前者逐条写、取 `ctx.userId()`（和工具那条路同源）；后者 **write-once**、已是别人的**不覆盖**
  （覆盖会让历史归属随最后一个请求漂移，且无日志）。★ 匿名请求沿用别人会话时**也不清空**归属。
- ★★★ **`qa_log.user_id` 不核对身份，原样记；`chat_session.user_id` 核对，查不到记 NULL。
  这两列【刻意不一致】，别去「修」成一致** —— 工具那条路拿的就是那个原样的值
  （`WHERE user_id = <请求头里的值>`），记成 NULL 就答不出「这次用的是哪个身份」，
  而且会把「有人拿伪造的头打了一发」这个证据毁掉。⚠️ 代价：这一列**可能不存在于
  `app_user`**，`JOIN app_user` 会静默丢行。（ADR-091 / 坑 34）
- ★★ **`qa_log.user_id` 是一张形状表，不是「它总是 X」** —— 阶段 9 之前的行恒为 NULL
  （而且它们才是大多数）。`WHERE user_id IS NOT NULL` 会把老行全丢掉。
- ★★ **`CallContext` 加分量是安全的（record 让所有构造点编译报错）**，
  但**身份进对象的前提是「每请求一次的不可变记录」** —— 变成单例 Bean 的字段就是 ADR-065 那条禁令。
- ★★ **流式工具轮的 `done.answer` 仍然是 null** —— 它由**新造的** `buildStreamToolResponse` 产出，
  **不要**改成复用 `buildToolResponse`。否则该字段有三种含义，前端会在工具轮上把正文渲染两遍。
- ★ **流式工具轮的正文是【一次推完】的**（没有打字机）—— 这是 9.1 拍板的边界。
  ⚠️ 因此 `probe_stage8.py` 第 3 项（按时间量 delta 分布）**会把工具题误报成
  「nginx 又缓冲了」**，跑那一项要用 KB 题或 `--no-model`。

### 代码风格（本项目强制）

- ★★ **同一列在多个路径上有多种形状时，任何一句「它总是 X」都注定是错的。**
  `qa_log.queue_ms` 的注释因此改过三次（「总是 0」→「不可能是 0」→「0 = 池满」），
  **三次全错**。**要写的是那张形状表，不是那句话。**（ADR-080）
- ★★ **一个字段在两条路上有两个含义时，断言必须说清是哪条路。**
  `ChatAskResponse.answer` 在非流式是正文，在流式**刻意是 null**（正文在 delta 里）——
  实测因为拿非流式的形状去断言流式，白红了一次。（ADR-080）
- ★ **`ApiResponse.CODE_SUCCESS = 0`**，不是 HTTP 的 200。判断成功要判 `code == 0`。
- ★ **纯单测必须写正-反对照**：断言 A 成立的同时，断言「不做 A 的那个版本确实不成立」，否则断言可能恒真。
- ★★ **凡是会进 Prompt 前缀的 JSON，一律用 `LinkedHashMap`，不用 `Map.of` / `Map.copyOf`** ——
  它们的迭代顺序由 hash 决定，而 JDK 9+ 的 hash 掺了一个**每次 JVM 启动随机**的 SALT。
  实测三个 JVM 三种顺序。代价是前缀缓存整段未命中（差 50 倍）。本项目已踩两次（ADR-058 / 064）。
- ★★ **同一个 SALT 也会让【测试】变成 flaky** —— 断言 `Set.copyOf([4,2]).toString() == "[4, 2]"`
  实测**约一半的 JVM 上会红**，而 `n≥3` 时盲区**不随元素个数下降**（稳定在 1/4），
  所以「多塞几个元素」修不好。**判据只能是「我们承诺过的东西」，不是「观察到的东西」** ——
  要一个确定的「不排序的样子」，用 `LinkedHashSet`（保留插入顺序）。（`docs/10` 坑 19）
- ★★ **错误分支只能报告它【核实过】的东西。** 写「多半是 X」和写「X」在报告里长得一样，
  而前者在你猜错时**会把读者指向反方向**。`except ... as e` 里 `e` 就在手上，别丢成 `None` 再猜一个。
  实测两次：§11 把「读不到」说成「没做过」（静默删掉一整节交付物）、
  缓存反推把「读漏了两列」说成「有降级行」。（`docs/10` 坑 20）
- ★ **别在 surefire 配置里加 `@Tag` 过滤** —— 写错会**静默漏跑**现有测试（文件还在、`mvn test` 还是绿的）。
- ★★ **输出里那个 `Tests run: 0` 是【容器类】，不是漏跑。** 本项目大量用 `@Nested`，
  surefire 会为外层类单印一行 `Tests run: 0`（它自己没有 `@Test`），
  **内层的用例在后面的行里**，最后再给一行总计。
  ★ 判据是**最后那一行总计**，不是中间那个 0。
  ★★ 2026-09-24 实测：`-Dtest=EvalReportServiceTest` → **22 个**、
  `-Dtest=QaLogEvalMarkTest` → **4 个**、`-Dtest=QaLogMapperProjectionTest` → **5 个**，
  三个「测试全在 `@Nested` 里」的类**都正常跑**。
  ⚠️ **别再写「`-Dtest` 对 `@Nested` 类静默 0 个」** —— 那是观察错误，
  曾经被当作结论写进计划文件（见 `docs/10` 坑 21）。
- ★ **测试用 `@MockitoBean`**（Boot 3.4+）。⚠️ **`./mvnw test` 需要 docker 的 postgres 和 redis**
  （阶段 6 起，见第六节）—— 只起 postgres 会失败。**不花钱**。

---

## 八、⚠️ Windows 环境下的两个坑

1. **命令行里的中文会被 shell 破坏。** Git Bash 传中文给 `curl -d` 会变成 `U+FFFD`（`efbfbd`），
   服务端收到乱码然后报 500「服务内部错误」。
   **中文请用文件传输**（`curl --data-binary @payload.json`）或 **Python 做百分号编码**
   （`urllib.parse.quote`），或者直接用 `scripts/probe_kb.py`。
   *浏览器不受影响 —— 它会正确地做百分号编码。*
   ⚠️ 反过来也成立：**Python 读服务端响应也可能按 GBK 解码而报错**，脚本里显式 `reconfigure(encoding="utf-8")`。
2. **Maven 输出的中文会乱码。** 加环境变量：
   `export MAVEN_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"`
