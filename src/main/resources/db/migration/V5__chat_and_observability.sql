-- ============================================================
-- V5 · 会话域 + 观测域
--
-- 表：chat_session / chat_message / chat_summary
--     qa_log / eval_question
-- 依据：docs/04-数据库设计.md 第九节、第十节
-- ============================================================


-- ============================================================
-- 会话域
-- ============================================================

CREATE TABLE chat_session
(
    id              BIGSERIAL    PRIMARY KEY,
    session_no      VARCHAR(64)  NOT NULL,
    user_id         BIGINT,
    title           VARCHAR(255),
    message_count   INT          NOT NULL DEFAULT 0,
    last_active_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status          SMALLINT     NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted         SMALLINT     NOT NULL DEFAULT 0,

    CONSTRAINT uk_chat_session_session_no UNIQUE (session_no),
    CONSTRAINT fk_chat_session_user
        FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_chat_session_status        CHECK (status IN (1, 2)),
    CONSTRAINT ck_chat_session_message_count CHECK (message_count >= 0)
);

COMMENT ON TABLE  chat_session                 IS '对话会话表';
COMMENT ON COLUMN chat_session.user_id         IS '可为空，支持匿名会话';
COMMENT ON COLUMN chat_session.title           IS '会话标题，由首轮问题生成';
COMMENT ON COLUMN chat_session.status          IS '1进行中 2已结束';

CREATE INDEX idx_chat_session_user_active ON chat_session (user_id, last_active_at DESC);


-- chat_message：★ 无软删除、无 updated_at
--   消息一旦写入不可修改，这是审计要求。阶段 7 评测要回溯对话原文。
CREATE TABLE chat_message
(
    id                BIGSERIAL   PRIMARY KEY,
    session_id        BIGINT      NOT NULL,
    role              SMALLINT    NOT NULL,
    content           TEXT        NOT NULL,
    token_count       INT,
    intent            VARCHAR(32),
    intent_confidence NUMERIC(5, 4),
    "references"      JSONB,
    provider          VARCHAR(32),
    model             VARCHAR(64),
    latency_ms        INT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_chat_message_session
        FOREIGN KEY (session_id) REFERENCES chat_session (id),

    CONSTRAINT ck_chat_message_role CHECK (role IN (1, 2, 3)),
    CONSTRAINT ck_chat_message_confidence CHECK (
        intent_confidence IS NULL OR (intent_confidence >= 0 AND intent_confidence <= 1)
    ),
    CONSTRAINT ck_chat_message_latency CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

COMMENT ON TABLE  chat_message                   IS '对话消息表。只增不改不删';
COMMENT ON COLUMN chat_message.role              IS '角色：1用户 2助手 3系统';
COMMENT ON COLUMN chat_message.intent            IS '识别出的意图（仅助手消息有）';
COMMENT ON COLUMN chat_message.intent_confidence IS '意图置信度 0~1。低于阈值会触发澄清反问';
COMMENT ON COLUMN chat_message."references"      IS '引用的知识片段：切片 ID + 相似度分数';
COMMENT ON COLUMN chat_message.provider          IS '★实际生效的供应商。降级后可能不是 P0，这是阶段 2 验收标准的直接证据';
COMMENT ON COLUMN chat_message.model             IS '实际使用的模型';

-- references 是 SQL 关键字（外键约束 REFERENCES），做列名必须加双引号。
-- 这里选择保留这个语义清晰的名字并用引号，代价是代码里查询也要加引号。
-- （对比 app_user 的选择：表名可以换，列名换来换去反而难懂，所以这里选择加引号）

CREATE INDEX idx_chat_message_session ON chat_message (session_id, id);
CREATE INDEX idx_chat_message_created ON chat_message (created_at DESC);


CREATE TABLE chat_summary
(
    id                BIGSERIAL   PRIMARY KEY,
    session_id        BIGINT      NOT NULL,
    summary_level     SMALLINT    NOT NULL DEFAULT 1,
    start_message_id  BIGINT      NOT NULL,
    end_message_id    BIGINT      NOT NULL,
    summary           TEXT        NOT NULL,
    token_count       INT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_chat_summary_session FOREIGN KEY (session_id)       REFERENCES chat_session (id),
    CONSTRAINT fk_chat_summary_start   FOREIGN KEY (start_message_id) REFERENCES chat_message (id),
    CONSTRAINT fk_chat_summary_end     FOREIGN KEY (end_message_id)   REFERENCES chat_message (id),

    CONSTRAINT ck_chat_summary_level CHECK (summary_level IN (1, 2)),
    CONSTRAINT ck_chat_summary_range CHECK (end_message_id >= start_message_id)
);

COMMENT ON TABLE  chat_summary                   IS '会话摘要表。滑动窗口保留最近 N 轮原文，更早的压缩成摘要';
COMMENT ON COLUMN chat_summary.summary_level     IS '1段落级摘要 2会话级摘要';
COMMENT ON COLUMN chat_summary.start_message_id  IS '摘要覆盖的起始消息 ID';
COMMENT ON COLUMN chat_summary.end_message_id    IS '摘要覆盖的结束消息 ID';

-- 双层记忆策略（面试重点）：
--   最近 3 轮  → 保留原文（细节不能丢，比如"我要买红色的"）
--   更早的对话 → 压缩成摘要（保留语义，省 token）
--
-- 为什么两层都要：
--   只保留摘要 → 会丢细节；
--   只保留原文 → 聊 20 轮后每轮都塞全部历史，token 爆炸、变慢变贵。
--   两层是精度和成本的折中。
--
-- start/end_message_id 记录摘要覆盖区间，保证原文窗口往前滚动时，
-- 新纳入摘要的消息不会和已有摘要重复计算。

CREATE INDEX idx_chat_summary_session ON chat_summary (session_id, summary_level);


-- ============================================================
-- 观测域 ★
-- ============================================================

-- ★★★ 本项目最重要的表 ★★★
-- CLAUDE.md 第 4 条：所有对数据库的写操作要能追溯到 qa_log，
-- 因为评测数据来源于此。阶段 7 的所有指标都从这张表算出来。
--
-- 只增不改不删 —— 没有 updated_at，没有 deleted。
CREATE TABLE qa_log
(
    id                   BIGSERIAL     PRIMARY KEY,
    trace_id             VARCHAR(64)   NOT NULL,
    session_id           BIGINT,
    user_id              BIGINT,
    question             TEXT          NOT NULL,
    rewritten_question   TEXT,
    intent               VARCHAR(32),
    intent_confidence    NUMERIC(5, 4),
    retrieval_detail     JSONB,
    final_answer         TEXT,
    "references"         JSONB,
    provider             VARCHAR(32),
    model                VARCHAR(64),
    degradation_events   JSONB,
    prompt_tokens        INT,
    completion_tokens    INT,
    total_tokens         INT,
    cost                 NUMERIC(10, 6),
    retrieval_latency_ms INT,
    rerank_latency_ms    INT,
    llm_latency_ms       INT,
    total_latency_ms     INT,
    status               SMALLINT      NOT NULL DEFAULT 1,
    error_msg            TEXT,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_qa_log_status CHECK (status IN (1, 2)),
    CONSTRAINT ck_qa_log_confidence CHECK (
        intent_confidence IS NULL OR (intent_confidence >= 0 AND intent_confidence <= 1)
    ),
    CONSTRAINT ck_qa_log_cost CHECK (cost IS NULL OR cost >= 0)
);

COMMENT ON TABLE  qa_log                    IS '★问答全链路日志。评测的唯一数据来源，只增不改不删';
COMMENT ON COLUMN qa_log.trace_id           IS '链路追踪 ID，串联一次问答的所有环节';
COMMENT ON COLUMN qa_log.rewritten_question IS '查询重写后的问题（阶段 4）';
COMMENT ON COLUMN qa_log.retrieval_detail   IS '★完整召回链路中间结果，见下方说明';
COMMENT ON COLUMN qa_log.degradation_events IS '★降级事件记录。阶段 2 验收标准第 2 条查的就是这个字段';
COMMENT ON COLUMN qa_log.retrieval_latency_ms IS '检索耗时';
COMMENT ON COLUMN qa_log.rerank_latency_ms    IS '重排耗时';
COMMENT ON COLUMN qa_log.llm_latency_ms       IS 'LLM 生成耗时';
COMMENT ON COLUMN qa_log.total_latency_ms     IS '端到端耗时';

-- retrieval_detail 的结构（阶段 4 验收标准第 1 条要用）：
-- {
--   "vector_hits":  [{"chunk_id":12,"score":0.87}, ...],     向量召回原始结果
--   "keyword_hits": [{"chunk_id":45,"score":3.2},  ...],     关键字召回原始结果
--   "fused":        [{"chunk_id":12,"rrf_score":0.032}, ...], RRF 融合后
--   "reranked":     [{"chunk_id":45,"rerank_score":0.95}, ...], 重排后
--   "final_top_k":  [45, 12, 8]                              最终送进 prompt 的
-- }
--
-- 没有这个字段，整个评测体系就无从谈起 —— 你无法回答
-- "召回失败是因为向量检索没找到，还是重排排错了，还是切分粒度不对"。
-- 把中间结果落库是让检索质量「可归因」的前提。

-- degradation_events 的结构（阶段 2 验收标准第 2 条要用）：
-- [{"from":"deepseek-p0","to":"siliconflow-p1","reason":"circuit_open","at":"..."}]

-- 为什么延迟要拆成四段：
--   只记一个 total_latency_ms 的话，P95 变慢了你也只能猜。
--   拆开后既能量化总延迟，也能说明「慢在哪一段」。

CREATE INDEX idx_qa_log_created_at ON qa_log (created_at DESC);
CREATE INDEX idx_qa_log_session    ON qa_log (session_id);
CREATE INDEX idx_qa_log_intent     ON qa_log (intent);
CREATE INDEX idx_qa_log_trace      ON qa_log (trace_id);


-- 人工标注的评测集
-- CLAUDE.md：评测的标准答案由人工标注，不用另一个模型生成 ——
-- 否则评测就变成了「模型给自己打分」。
CREATE TABLE eval_question
(
    id                  BIGSERIAL    PRIMARY KEY,
    question_no         VARCHAR(64)  NOT NULL,
    question            TEXT         NOT NULL,
    intent              VARCHAR(32)  NOT NULL,
    expected_answer     TEXT,
    expected_chunk_ids  BIGINT[],
    expected_doc_ids    BIGINT[],
    category            VARCHAR(64),
    difficulty          SMALLINT,
    is_baseline         BOOLEAN      NOT NULL DEFAULT false,
    source              VARCHAR(64),
    annotated_by        VARCHAR(64),
    annotated_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_eval_question_no UNIQUE (question_no),
    CONSTRAINT ck_eval_question_difficulty CHECK (difficulty IS NULL OR difficulty IN (1, 2, 3))
);

COMMENT ON TABLE  eval_question                     IS '人工标注的评测题集。阶段 7 的评测基准';
COMMENT ON COLUMN eval_question.intent              IS '★人工标注的正确意图，不是模型预测的';
COMMENT ON COLUMN eval_question.expected_answer     IS '人工撰写的标准答案';
COMMENT ON COLUMN eval_question.expected_chunk_ids  IS '应当被召回的切片 ID 列表，用于算召回命中率';
COMMENT ON COLUMN eval_question.is_baseline         IS '是否纳入基线评测集，用于 A/B 对比';

-- 为什么 expected_chunk_ids 用数组而不是关联表：
--   这里只是「一组 ID」，不需要携带额外属性（不像 user_coupon 要记领取时间、状态）。
--   数组类型足够表达，查询也直观：WHERE 12 = ANY(expected_chunk_ids)

-- 部分索引：评测集可能攒到几百题，但做 A/B 对比时只用基线集，
-- 只索引这部分数据能让索引体积更小、查询更快。
CREATE INDEX idx_eval_question_intent   ON eval_question (intent);
CREATE INDEX idx_eval_question_baseline ON eval_question (is_baseline) WHERE is_baseline = true;
