# CLAUDE.md — 休伯利安（XBLA）电商导购与售后 RAG 平台

> 这份文件是**索引 + 硬性约定**，Claude Code 每次对话都会加载它，所以保持精简。
> 详细内容全部在 `docs/` 下，按需查阅。

---

## 一、项目是什么

面向电商场景（商品咨询、规格对比、促销政策、售后服务）的企业级 **RAG 智能问答平台**。

核心不是"能聊天"，而是**检索质量可量化、可优化、可复现**：有意图路由、多路召回、重排序、会话记忆、MCP 工具调用，以及一套端到端评测体系。

**当前阶段**：阶段 2（模型接入层 + 最小可用闭环）
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

## 六、常用命令（环境搭好后补充）

```bash
# 启动中间件
docker compose up -d

# 查看中间件状态
docker compose ps

# 连接数据库
docker compose exec postgres psql -U xbla -d xbla_rag

# 后端启动
./mvnw spring-boot:run

# 前端启动
cd web && npm run dev
```
