# CLAUDE.md — 休伯利安（XBLA）电商导购与售后 RAG 平台

> 这份文件是**索引 + 硬性约定**，Claude Code 每次对话都会加载它，所以保持精简。
> 详细内容全部在 `docs/` 下，按需查阅。

---

## 一、项目是什么

面向电商场景（商品咨询、规格对比、促销政策、售后服务）的企业级 **RAG 智能问答平台**。

核心不是"能聊天"，而是**检索质量可量化、可优化、可复现**：有意图路由、多路召回、重排序、会话记忆、MCP 工具调用，以及一套端到端评测体系。

**当前阶段**：阶段 3（RAG 核心 —— 文档入库）
**详细路线图**：`docs/10-开发路线图.md`

---

## 二、技术栈速查

| 层 | 技术 | 版本策略 |
|---|---|---|
| 语言/运行时 | Java（JDK 21 LTS） | 固定 |
| 框架 | Spring Boot 3.x | 以搭建当日最新稳定版为准 |
| ORM | MyBatis-Plus | 必须选支持 Spring Boot 3 的版本 |
| 数据库 | PostgreSQL + **pgvector** | Docker 镜像 `pgvector/pgvector:pgXX` |
| 缓存/限流 | Redis + Redisson | Docker 镜像 `redis:7-alpine` |
| 文档解析 | Apache Tika 2.x | — |
| 熔断 | Resilience4j | `resilience4j-spring-boot3` |
| MCP | 官方 Java SDK（Client）+ 手写 Server | — |
| 前端 | Vue 3 + Vite + Element Plus | — |
| 编排 | Docker Compose | — |
| 评测 | Python + RAGAS | 独立脚本，非主服务 |

### 模型供应商（双供应商，配置驱动）

| 优先级 | 供应商 | 用途 | 模型 |
|---|---|---|---|
| P0 | DeepSeek 官方 | 对话生成（主力） | `deepseek-flash` |
| P1 | 硅基流动 | 对话生成（同模型换源） | `deepseek-ai/DeepSeek-V4-Flash` |
| P2 | 硅基流动 | 对话生成（兜底降级） | `Qwen/Qwen3-8B`（非推理，免费） |
| — | 硅基流动 | **向量化**（唯一来源） | `BAAI/bge-m3`（1024 维，免费） |
| — | 硅基流动 | **重排序**（唯一来源） | `BAAI/bge-reranker-v2-m3`（免费） |

> ✅ 上表全部 ID 于 **2026-09-18 经 `GET /v1/models` + 真实调用实测确认**。
> 当时核实出两处问题：① 原写的「DeepSeek-V4.1-Flash」**不存在**，实际是 `DeepSeek-V4-Flash`；
> ② `bge-m3` 实测输出确实为 **1024 维**，与建表时的 `vector(1024)` 一致。

> ⚠️ **DeepSeek 官方不提供向量化和重排序能力**，这两项只能用硅基流动。这是双供应商架构的根本原因。
>
> ⚠️ 模型 ID 会变动。**每次动工涉及模型的代码前，先用 `GET /v1/models` 拉真实列表核实**，不要凭记忆硬编码。

> ⚠️ **P0/P1 的 `deepseek-flash` 是「推理模型」**，与普通对话模型的行为不同，写代码时务必注意：
> - 响应 `message` 里多一个**非标准字段 `reasoning_content`**（推理过程），
>   且 `usage.completion_tokens_details.reasoning_tokens` 单独统计推理消耗。
> - **实测推理 token 占总输出的 87%**（124 个输出 token 里 108 个是推理）。
> - **`max_tokens` 给小了会返回空 `content`** —— 推理把额度吃光，最终回答是空字符串。
>   这个 bug 在线上表现为「AI 不说话」，日志里看不出原因。
> - 本项目**决策：不存储也不返回 `reasoning_content`**，只取最终回答。P2 的 `Qwen3-8B` 是非推理模型，
>   响应里没有这个字段，因此 `LlmClient` 需要能同时兼容两种响应形态。

---

## 三、架构分层与包结构

```
com.xbla.rag
├── controller/    对外 HTTP 接口（薄，不写业务逻辑）
├── service/       业务编排
├── mapper/        MyBatis-Plus Mapper
├── entity/        数据库实体
├── dto/           请求/响应对象
├── config/        配置类（模型供应商、Resilience4j、Redis 等）
├── client/        ★ 模型接入层：LlmClient / EmbeddingClient / RerankClient（手写 HTTP）
├── rag/           ★ RAG 核心：解析、切分、召回、融合、重排
├── agent/         ★ 智能体层：意图路由、澄清反问、会话记忆
├── mcp/           ★ MCP Server（手写）+ Client（官方 SDK）
├── ratelimit/     ★ 高可用：Redis 信号量、Lua 脚本、ZSet 队列、Pub/Sub
└── common/        通用工具、异常、统一响应封装
```

**数据流**（详见 `docs/03-系统架构设计.md`）：
`用户提问 → 意图识别 → [知识库检索 | MCP 工具调用] → 重排 → Prompt 组装 → LLM 生成 → SSE 流式返回`

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
| `docs/00-项目总览.md` | 一句话定位、简历故事线、面试叙述脚本 |
| `docs/01-产品需求文档.md` | 用户场景、功能清单、非功能需求、验收标准 |
| `docs/02-技术选型与术语词典.md` | ★ **每个技术的白话解释 + 优缺点**（看不懂术语先查这里） |
| `docs/03-系统架构设计.md` | 分层架构、端到端数据流、模块划分 |
| `docs/04-数据库设计.md` | 全部表结构、索引、pgvector 列设计 |
| `docs/05-检索与智能体设计.md` | 意图树、双路召回、RRF、重排、会话记忆、MCP 工具 |
| `docs/06-评测体系设计.md` | 指标定义与公式、标注集规范、A/B 对比方法 |
| `docs/07-部署手册.md` | Docker Compose、Nginx、内网穿透、排障 |
| `docs/08-技术决策记录(ADR).md` | ★ **每个决策的备选方案与被否决原因**（面试利器） |
| `docs/09-面试问答准备.md` | 预判追问 + 标准答案 |
| `docs/10-开发路线图.md` | 8 周阶段划分、每阶段验收标准、进度勾选 |

---

## 六、常用命令

```bash
# 启动中间件
docker compose up -d

# 查看中间件状态
docker compose ps

# 连接数据库
docker compose exec postgres psql -U xbla -d xbla_rag

# 后端启动（密钥从 application-local.yml 读，那个文件已 gitignore）
./mvnw spring-boot:run

# 跑测试（128 个：EntityMappingTest 回归 + 解析/切分/向量转换的单测）
./mvnw test
```

### 阶段 2 新增：模型接入层的调试探针

> ⚠️ 这些接口标了 `@Profile("local")`，只在本地开发时存在 ——
> 它们可以无条件消耗 API 额度，**绝不能暴露到生产**。

```bash
# 降级链与熔断器实时状态
curl -s localhost:8080/api/debug/llm/chain | python -m json.tool

# 直连某一档（绕过熔断和降级），用于单独验证每个模型
curl -s -G localhost:8080/api/debug/llm/chat \
  --data-urlencode "q=你好" --data-urlencode "model=deepseek-flash"
# model 传 auto 或不传 = 走完整降级链

# 向量化（看 dimension 是不是 1024）
curl -s -G localhost:8080/api/debug/embedding --data-urlencode "q=退货政策"

# 语义相似度对比
curl -s -G localhost:8080/api/debug/similarity \
  --data-urlencode "a=退货政策怎么规定" --data-urlencode "b=我想退货"

# 重排序（相关文档得分应显著高于不相关的）
curl -s -G localhost:8080/api/debug/rerank \
  --data-urlencode "q=怎么退货" --data-urlencode "docs=七天无理由退货,发货时效,优惠券规则"

# 流式打字机（逐行 event: delta 陆续到达）
curl -N -G localhost:8080/api/debug/llm/stream --data-urlencode "q=你好"
```

### 业务接口

```bash
# 非流式问答
curl -s -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"商品支持七天无理由退货吗"}'

# 流式问答（打字机效果）
curl -N -G localhost:8080/api/chat/stream --data-urlencode "question=你好"
# 浏览器演示页：http://localhost:8080/chat.html

# 熔断器状态
curl -s localhost:8080/actuator/circuitbreakers | python -m json.tool
```

### 阶段 3 新增：知识库文档入库

```bash
# ① 生成仿真语料（6 份：PDF / Word / Markdown / Excel），输出到 data/corpus/
python scripts/generate_corpus.py
#    依赖：pip install reportlab python-docx openpyxl

# ② 批量灌语料（异步，立刻返回 docId 列表）
curl -s -X POST "localhost:8080/api/kb/documents/scan?docType=2"

# ③ 数据库同步：把 after_sale_policy + product 表渲染成知识库文档
curl -s -X POST localhost:8080/api/kb/documents/sync

# ④ 上传单个文件（multipart）
curl -s -X POST localhost:8080/api/kb/documents \
  -F "file=@data/corpus/售后政策汇编.docx" -F "docType=2"

# ⑤ 查入库状态（前端轮询的就是这个；finished=true 表示可以停止轮询）
curl -s localhost:8080/api/kb/documents/1 | python -m json.tool
```

### 阶段 3 新增：知识库检索探针（同样 @Profile("local")）

> ⚠️ **中文请用 `scripts/probe_kb.py`，不要直接用 curl。**
> Windows + Git Bash 下有**两层**编码陷阱（shell 破坏命令行参数、
> Python 按 GBK 读 UTF-8 响应），直接用 curl 会得到「像乱码又像 bug」的结果。

```bash
# 向量检索，看最相关的切片
python scripts/probe_kb.py search 退货要几天
python scripts/probe_kb.py search 这个适合送长辈吗 --topk 5

# ★ 稳定性自检：同一问题查 N 次，比对 ID 序列是否逐位一致
python scripts/probe_kb.py stability 退货要几天 --repeat 5

# 查某份文档的入库状态
python scripts/probe_kb.py status 1
```

> **关于「结果稳定」的正确预期**：
> 命中的 **ID 序列**是完全稳定的（靠 SQL 里 `ORDER BY ..., id` 的兜底键）；
> 但**分值不会逐位相同** —— 实测向量化接口本身有约 **3e-4** 的漂移
> （同一问题连续调 6 次出现 2 种结果），这是 GPU 浮点并行归约的固有性质。
> **别把「检索结果不稳定」当成 bug 去查**，先看 ID 序列是否一致。

### ⚠️ Windows 环境下的两个坑

1. **命令行里的中文会被 shell 破坏。** Git Bash 传中文给 `curl -d` 会变成
   `U+FFFD` 替换字符（`efbfbd`），服务端收到乱码。
   **测试中文请用文件传输**：`curl --data-binary @payload.json`，
   或者用 Python 做百分号编码（`urllib.parse.quote`）。
   *浏览器不受影响 —— 它会正确地做百分号编码。*
2. **Maven 输出的中文会乱码。** 加环境变量：
   `export MAVEN_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"`
