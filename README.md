# 休伯利安（XBLA）· 电商导购与售后 RAG 平台

> 面向电商场景（商品咨询 / 规格对比 / 促销政策 / 售后服务）的 RAG 智能问答平台。
> **核心不是「能聊天」，而是检索质量可量化、可复现、可优化。**

`Java 21` · `Spring Boot 3.5` · `PostgreSQL 17 + pgvector` · `Redis 7` · `Vue 3` · `Docker Compose`

---

## 一、这是什么

用户在电商场景里问一句话，系统**先判断该去哪找答案**（知识库 / 实时数据库 / 都不用），
找到之后**用检索到的原文作答并给出出处**；资料不够时**反问**，而不是猜一个答案。

具体来说：

- 问「退货要几天」→ 检索售后政策文档，回答带 `[1][4]` 引用编号和原文出处；
- 问「我的订单到哪了」→ 不查知识库，改调 MCP 工具查实时订单数据；
- 问「这个适合送长辈吗」→ 先检索商品资料，再把三个商品工具交给模型做候选与推荐；
- 问「那个怎么样」→ 识别为「信息不足」，先反问缺哪个信息，而不是硬猜；
- 问「今天天气怎么样」→ 明说超出服务范围，而不是编一个答案。

### 它不是

| 不是 | 因为 |
|---|---|
| 通用聊天机器人 | 范围锁在商品导购与售后；超范围会明说，不硬答 |
| 「包了一层大模型 API」的问答 | 回答依据是检索到的切片，出处随回答一起返回；没有依据时不假装有 |
| 一次性 demo | 检索质量可量化、可复现、可优化；每个 A/B 结论都要先跟噪声底比大小 |
| 能对外营业的服务 | 身份是明文头、没有登录、没有公网入口，见「已知边界」 |

---

## 二、核心能力

| 能力 | 做了什么 |
|---|---|
| **意图识别与路由** | 5 类业务意图 + `OUT_OF_SCOPE` / `NEEDS_CLARIFICATION` 两个非业务分支，16 个业务叶子；分类与「这次要不要检索」在**同一次模型调用**里给出 |
| **双路召回 + 重排** | pgvector 向量召回 + PostgreSQL 全文检索（中文双字切分），RRF 融合后用 `bge-reranker-v2-m3` 重排；召回 / 融合 / 重排 / 最终六段中间产物**全部落库**，与调试接口同源 |
| **Agentic RAG** | 6 个 MCP 工具（订单 / 库存 / 券 / 商品搜索 / 比价 / 推荐），按意图白名单裁剪；**白名单在服务端强制**，请求里不提供 ≠ 调不到 |
| **多轮会话** | 历史只喂生成、不喂分类；长会话异步摘要压缩；待澄清槽位「读后即清」，只注入下一轮一次 |
| **个性化推荐** | 偏好从订单**实时派生**（不建画像表、不缓存），只参与**重排**、不参与召回；偏好块以快照形式落库，与 prompt 中是同一个字符串 |
| **过载保护** | Redis ZSet 名额 + 租约 + 手写 Lua；容量 8、队列上限 500；排队位置通过 SSE 每 200ms 推送；100 并发实测无超卖、无死锁 |
| **流式前端** | Vue 3 + Vite + Element Plus；打字机、引用卡片、排队提示、技术面板、会话列表、在线指标页 |
| **评测体系** | 意图 / 检索 / 多轮 / 推荐多套标注集；命中率、召回、MRR、归因、过度检索；RAGAS 答案质量；噪声底、A/B 翻转矩阵、跑题对账 |

### 模型接入

两个供应商、三层降级链，全部经过 `client/` 层封装（熔断、降级、计费、日志统一生效）：

| 优先级 | 供应商 | 用途 | 模型 |
|---|---|---|---|
| P0 | DeepSeek 官方 | 对话生成 | `deepseek-flash` |
| P1 | 硅基流动 | 对话生成（同模型换源） | `deepseek-ai/DeepSeek-V3.2` |
| P2 | 硅基流动 | 对话生成（兜底降级） | `Qwen/Qwen3.5-9B` |
| — | 硅基流动 | 向量化（唯一来源） | `BAAI/bge-m3`（1024 维） |
| — | 硅基流动 | 重排序（唯一来源） | `BAAI/bge-reranker-v2-m3` |

> DeepSeek 官方不提供向量化与重排序，这是双供应商架构的根本原因。

---

## 三、架构

```
浏览器（Vue 3）
   │  POST /api/chat/stream（SSE）
   ▼
Nginx（Basic Auth / SSE 不缓冲）
   │
   ▼
Spring Boot
   ├─ 排队限流       Redis 名额 ZSet + 租约 + Lua（在业务层之外）
   ├─ 意图识别       分类 + 检索门控 + 槽位澄清（一次模型调用）
   ├─ 知识库检索     向量 + 关键词 → RRF 融合 → 重排
   ├─ MCP 工具       订单 / 库存 / 券 / 商品搜索 / 比价 / 推荐（按意图裁剪）
   ├─ Prompt 组装    结构化硬事实 + 偏好快照 + 检索切片
   └─ LLM 生成       DeepSeek → 硅基流动降级链（熔断）
   │
   ▼
PostgreSQL 17 + pgvector · Redis 7
```

分层依赖是单向的：`controller → service → client → 外部`。
RAG 链路手写，不使用 Spring AI / LangChain4j，每一行都能解释。

---

## 四、快速开始

### 前置条件

- JDK 21
- Docker + Docker Compose
- Node.js 20.19+（前端 Vite 8）
- Python 3.10+（评测脚本；灌知识库只用标准库）
- DeepSeek 与硅基流动的 API Key

### 方式一：本地开发（推荐）

中间件跑容器，应用跑在宿主机 `:8080`。`probe_*.py` / `eval_*.py` 默认就打这个地址（RAGAS 除外，它走 `.venv-eval/`）。

```bash
git clone git@github.com:Satan508-h/xbla-ecommerce-platform.git
cd xbla-ecommerce-platform

# ① 中间件配置：填数据库密码
cp .env.example .env

# ② 应用私有配置：填数据库密码 + 两个模型 API Key
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml

# ③ 起中间件（只起 postgres / redis / pgadmin）
docker compose up -d

# ④ 起应用，首次启动顺带灌业务数据（商品 / 订单 / 券 / 售后政策）
./mvnw spring-boot:run -Dspring-boot.run.profiles=local,seed

# ⑤ 另开一个终端：灌知识库（会调用向量化，产生少量费用）
python scripts/ingest.py

# ⑥ 前端
cd frontend && npm install && npm run dev
```

打开 <http://localhost:5173>。后端也自带一个演示页：<http://localhost:8080/chat.html>。

> ★ **灌数据是两步，顺序不能反**：知识库要从业务表同步出商品和售后政策文档。
> 先灌知识库会同步到 0 份，而且不报错。`SeedRunner` 按表幂等，重复跑是安全的。

### 方式二：容器全栈（部署演示）

中间件 + 应用 + Nginx 全部进容器，只暴露 Nginx 的 80 端口。应用容器**不映射 8080**，所以可以和方式一同时存在。

```bash
cp .env.example .env
# 额外填 DEEPSEEK_API_KEY / SILICONFLOW_API_KEY

bash deploy/make-htpasswd.sh                 # 生成 Basic Auth 口令
docker compose --profile full up -d --build

bash scripts/seed.sh                         # ① 业务数据
python scripts/ingest.py --url http://localhost -u xbla:<口令>   # ② 知识库

# 打开 http://localhost，输入 Basic Auth 口令
```

> `--profile full` 不能省。省了只起中间件 —— 那正是本地开发要的样子。

### 验证

```bash
# 1100+ 项自动化测试（需要 docker 的 postgres + redis；不花钱）
./mvnw clean test

# 部署验收：SSE、接口契约、时序（--no-model 跳过调模型的 3 项）
python scripts/probe_stage8.py --no-model

# 在线指标与埋点
python scripts/probe_stage96.py --no-model

# 暴露面审计（打容器全栈那一侧）：debug 端点 404 / 私有文档 404 / 数据库端口不可达
python scripts/probe_exposure.py --url http://localhost -u 用户:口令
```

> Windows 下命令行直接传中文会被 shell 破坏，请用 `scripts/probe_*.py` 或文件传输；
> Maven 中文乱码可加 `MAVEN_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"`。

---

## 五、项目结构

```
.
├── src/main/java/com/xbla/rag/
│   ├── controller/     对外 HTTP 接口（薄）
│   ├── service/        业务编排
│   ├── client/         模型接入层（全项目唯一发模型 HTTP 的地方）
│   ├── rag/            解析、切分、召回、融合、重排、Prompt 组装
│   ├── agent/          意图路由、澄清反问、会话记忆、工具循环
│   ├── mcp/            MCP Server（手写）+ Client（官方 SDK）
│   ├── ratelimit/      名额表、手写 Lua、ZSet 队列、Pub/Sub、看门狗
│   ├── mapper/ entity/ dto/ config/ common/
│   └── ...
├── src/main/resources/
│   ├── db/migration/   Flyway 迁移（V1 ~ V18）
│   ├── lua/            限流 Lua 脚本
│   └── application.yml
├── data/
│   ├── agent/          意图树、few-shot
│   ├── corpus/         仿真语料（商品 / 售后 / 促销 / FAQ / 说明书）
│   └── eval/           评测题库与推荐 gold
├── frontend/           Vue 3 + Vite + Element Plus
├── scripts/            seed / ingest / probe_* / eval_*
├── deploy/             Dockerfile、Nginx、Maven 镜像配置
├── docs/               设计文档与评测报告
└── docker-compose.yml
```

---

## 六、文档索引

| 文档 | 内容 |
|---|---|
| [01 · 产品需求文档](docs/01-产品需求文档.md) | 要什么、怎么算做到了（每条需求带判据与实测状态） |
| [02 · 技术选型与术语词典](docs/02-技术选型与术语词典.md) | 每个技术的白话解释 + 优缺点 |
| [03 · 系统架构设计](docs/03-系统架构设计.md) | 分层架构、端到端数据流、模块职责 |
| [04 · 数据库设计](docs/04-数据库设计.md) | 表结构、索引、pgvector 列设计 |
| [05 · 检索与智能体设计](docs/05-检索与智能体设计.md) | 意图树、双路召回、RRF、重排、会话记忆、摘要压缩 |
| [06 · 评测体系设计](docs/06-评测体系设计.md) | 指标定义、标注集规范、A/B 对比方法 |
| [07 · 部署手册](docs/07-部署手册.md) | 拓扑、两条运行方式、验收清单、常见故障 |
| [08 · 技术决策记录（ADR）](docs/08-技术决策记录%28ADR%29.md) | 每个决策的备选方案与被否决原因 |
| [10 · 开发路线图](docs/10-开发路线图.md) | 阶段划分、每阶段验收标准、踩过的坑 |
| [11 · 评测报告](docs/11-评测报告.md) | 可复算的指标与逐题结果 |

---

## 七、评测：这个项目的差异点

大多数问答系统的「效果」只能靠体感。本仓库把「效果」做成了一个**别人能重跑一遍的数字**：

- **噪声底**：同配置跑两轮，至少翻一格的题约占 13%~16%（每期实测值见 `docs/11` §1）。
  ⇒ 任何「提升了 X%」的结论，**先跟这个数比大小**，否则是在读噪声。
- **跑题对账**：每个提交的 `traceId` 都必须能查到且属于本轮，否则整轮作废。
- **一个 run id 只能跑一次**，保护判据问的是 `qa_log`（语义所在处），不是本地目录。
- **评测必须串行**：并发会让 `queue_ms` 混进延迟分位数，数字照样好看，但量的不再是那条链路。
- **判据用「实际发生」**，不用「门控说了什么」：检索决策取 `retrieval_detail` 非空，不取 `intent_plan.retrieve`。

```bash
python scripts/eval_run.py --set stage7 --repeat 3     # 跑题（串行，花钱）
python scripts/eval_ragas.py --run <id>                # RAGAS（慢，走 .venv-eval/）
python scripts/eval_ab.py --a <run1> --b <run2>        # A/B + 翻转矩阵
python scripts/eval_report.py --refresh                # 从端点刷新报告数据（只读）
```

> 延迟是**五段**且不相加等于 total：`rerank ⊂ retrieval` 是包含关系，而意图分类是一次
> 0.5~2.5 秒的模型往返，它进了 total 却不在任何一段里。别把「检索慢」和「分类慢」并成一格 ——
> 两者的修法完全相反。

---

## 八、已知边界

这一节是**如实说明**，不是待办清单：

- **`X-Xbla-User-Id` 是明文、未签名的请求头，不是认证。** 它做到的是「身份不进模型的可控范围」，另一半需要 OAuth —— 未做。
- **没有登录、没有多租户、没有权限体系**，本项目是单租户演示。
- **`/api/debug/**` 全部挂在 `local` profile 上**，生产环境必须 404；容器化必须显式 `SPRING_PROFILES_ACTIVE=prod`。
- **容器演示版唯一的访问控制是 Nginx Basic Auth**；数据库与 Redis 只绑 `127.0.0.1`。
- **内网穿透未开启** —— 开它等于把服务暴露到公网，是外向动作，需要单独决策。
- **知识库是仿真语料**，不是真实企业文档；它复刻了真实语料的难点（同一答案横跨多份文档、商品重名等）。
- **模型调用会产生费用**：灌知识库、跑评测、以及每一次真实问答都会产生调用。

---

## 九、许可

本仓库未附带开源许可证。
