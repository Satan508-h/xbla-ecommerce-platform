-- ============================================================
-- V4 · 知识库域 ★ 本项目最核心的两张表
--
-- 表：kb_document / kb_chunk（含 embedding vector(1024) + HNSW 索引）
-- 依据：docs/04-数据库设计.md 第八节
-- ============================================================


-- 安全网：正常情况下 vector 扩展由 sql/01-init.sql 在容器首次初始化时建好。
-- 但如果有人在「没跑过 init 脚本」的库上单独执行这个迁移，这里会兜底。
-- IF NOT EXISTS 保证重复执行不报错。
CREATE EXTENSION IF NOT EXISTS vector;


-- ============================================================
-- 文档表
-- ============================================================

CREATE TABLE kb_document
(
    id                 BIGSERIAL    PRIMARY KEY,
    doc_no             VARCHAR(64)  NOT NULL,
    title              VARCHAR(255) NOT NULL,
    doc_type           SMALLINT     NOT NULL,
    source_type        SMALLINT     NOT NULL,
    file_name          VARCHAR(255),
    file_path          VARCHAR(512),
    file_size          BIGINT,
    file_hash          VARCHAR(64),
    mime_type          VARCHAR(128),
    related_product_id BIGINT,
    effective_from     TIMESTAMPTZ,
    effective_to       TIMESTAMPTZ,
    version            INT          NOT NULL DEFAULT 1,
    status             SMALLINT     NOT NULL DEFAULT 1,
    chunk_count        INT          NOT NULL DEFAULT 0,
    error_msg          TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted            SMALLINT     NOT NULL DEFAULT 0,

    CONSTRAINT uk_kb_document_doc_no UNIQUE (doc_no),
    CONSTRAINT fk_kb_document_product
        FOREIGN KEY (related_product_id) REFERENCES product (id),

    CONSTRAINT ck_kb_document_doc_type    CHECK (doc_type BETWEEN 1 AND 5),
    CONSTRAINT ck_kb_document_source_type CHECK (source_type IN (1, 2)),
    CONSTRAINT ck_kb_document_status      CHECK (status BETWEEN 1 AND 4),
    CONSTRAINT ck_kb_document_version     CHECK (version >= 1),
    CONSTRAINT ck_kb_document_chunk_count CHECK (chunk_count >= 0),
    CONSTRAINT ck_kb_document_file_size   CHECK (file_size IS NULL OR file_size >= 0)
);

COMMENT ON TABLE  kb_document                 IS '知识库文档表（原始文档的元信息，正文切分后存 kb_chunk）';
COMMENT ON COLUMN kb_document.doc_type        IS '文档类型：1商品详情 2售后政策 3促销规则 4FAQ 5说明书';
COMMENT ON COLUMN kb_document.source_type     IS '来源：1文件上传 2数据库同步';
COMMENT ON COLUMN kb_document.file_hash       IS '文件内容 SHA-256。用于重复上传去重，省掉一次完整的解析+向量化（向量化要调模型 API，费钱费时）';
COMMENT ON COLUMN kb_document.status          IS '处理状态：1待处理 2处理中 3已入库 4处理失败';
COMMENT ON COLUMN kb_document.chunk_count     IS '切分出的切片数，处理完成后回填';
COMMENT ON COLUMN kb_document.error_msg       IS '处理失败时的错误信息，便于排查';
COMMENT ON COLUMN kb_document.version         IS '版本号。文档换版时新增记录，旧版本标记 effective_to';

-- 为什么 status 是个状态机而不是布尔值：
--   文档入库要经过 解析→切分→批量向量化→写库 四步，可能耗时几十秒到几分钟，
--   不可能在 HTTP 请求里同步完成。必须有字段跟踪"处理到哪一步了"，
--   前端才能轮询进度，失败时也才知道是哪个环节出的问题。

CREATE INDEX idx_kb_document_file_hash ON kb_document (file_hash);
CREATE INDEX idx_kb_document_status    ON kb_document (status) WHERE deleted = 0;
CREATE INDEX idx_kb_document_doc_type  ON kb_document (doc_type) WHERE deleted = 0;


-- ============================================================
-- 切片表 ★ 含向量列
-- ============================================================

CREATE TABLE kb_chunk
(
    id                 BIGSERIAL     PRIMARY KEY,
    document_id        BIGINT        NOT NULL,
    chunk_index        INT           NOT NULL,
    content            TEXT          NOT NULL,
    content_hash       VARCHAR(64),
    heading_path       VARCHAR(512),
    token_count        INT,
    -- ★ 向量列。1024 维对应 bge-m3 模型的输出维度，
    --   这个数字在整个项目里是硬约定，见 CLAUDE.md 第 8 条。
    embedding          vector(1024),
    metadata           JSONB,

    -- ↓ 从 kb_document 冗余过来的字段，见下方说明
    related_product_id BIGINT,
    doc_type           SMALLINT,
    effective_from     TIMESTAMPTZ,
    effective_to       TIMESTAMPTZ,

    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted            SMALLINT      NOT NULL DEFAULT 0,

    CONSTRAINT fk_kb_chunk_document
        FOREIGN KEY (document_id) REFERENCES kb_document (id),

    CONSTRAINT ck_kb_chunk_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_kb_chunk_token_count CHECK (token_count IS NULL OR token_count >= 0),
    CONSTRAINT ck_kb_chunk_doc_type    CHECK (doc_type IS NULL OR doc_type BETWEEN 1 AND 5),

    -- 同一文档内切片序号不能重复
    CONSTRAINT uk_kb_chunk_doc_index UNIQUE (document_id, chunk_index)
);

COMMENT ON TABLE  kb_chunk                    IS '知识库切片表。RAG 检索的最小单位';
COMMENT ON COLUMN kb_chunk.chunk_index        IS '在文档内的序号，从 0 开始';
COMMENT ON COLUMN kb_chunk.content_hash       IS '切片内容 SHA-256。文档局部改动后只重新向量化变化的部分';
COMMENT ON COLUMN kb_chunk.heading_path       IS '标题层级路径，如「售后政策 > 退货 > 七天无理由」。检索时可作为上下文补充给 LLM';
COMMENT ON COLUMN kb_chunk.embedding          IS '★ bge-m3 生成的向量，1024 维。余弦距离';
COMMENT ON COLUMN kb_chunk.metadata           IS '灵活扩展字段，避免为偶然需求频繁改表';
COMMENT ON COLUMN kb_chunk.related_product_id IS '★冗余自 kb_document。避免向量检索时 join 回表';
COMMENT ON COLUMN kb_chunk.doc_type           IS '★冗余自 kb_document。用于意图定向检索';
COMMENT ON COLUMN kb_chunk.effective_from     IS '★冗余自 kb_document。用于时效过滤';


-- ------------------------------------------------------------
-- 索引
-- ------------------------------------------------------------

-- ① ★ HNSW 向量索引：向量召回的加速器
--
-- 为什么用 cosine（余弦距离）而不是 L2（欧氏距离）：
--   文本向量关心的是「方向」而不是「长度」。"手机很好用" 和
--   "这款手机真的很好用" 语义相同但长度不同，欧氏距离会认为有差距，
--   余弦距离只看夹角，判定为高度相似。文本检索的通用选择。
--
-- 为什么用 HNSW 而不是 IVFFlat：
--   HNSW 查询快、召回率高、对增量插入友好（阶段 3 会不断灌文档）；
--   IVFFlat 建索引快、内存省，但需要一次性灌完数据再建，且召回率依赖聚类数调参。
--   本项目是「持续入库 + 频繁查询」，选 HNSW。
--
-- 参数 m=16 / ef_construction=64 是 pgvector 官方推荐默认值。
-- 先按默认跑，阶段 7 评测时再调优 —— 参数调优要基于指标，不能拍脑袋。
CREATE INDEX idx_kb_chunk_embedding ON kb_chunk
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- ② 外键查询
CREATE INDEX idx_kb_chunk_document_id ON kb_chunk (document_id);

-- ③ 意图定向检索：先按类型/商品过滤，再做向量检索
CREATE INDEX idx_kb_chunk_doc_type ON kb_chunk (doc_type) WHERE deleted = 0;
CREATE INDEX idx_kb_chunk_product  ON kb_chunk (related_product_id) WHERE deleted = 0;

-- ④ 内容去重
CREATE INDEX idx_kb_chunk_content_hash ON kb_chunk (content_hash);

-- ⑤ JSONB 元数据查询（如 metadata @> '{"source":"pdf"}'）
CREATE INDEX idx_kb_chunk_metadata ON kb_chunk USING gin (metadata);


-- ------------------------------------------------------------
-- 为什么要把 doc_type / related_product_id / effective_from 冗余到 kb_chunk？
-- ------------------------------------------------------------
--
-- 因为向量检索里「先过滤再算距离」和「先算距离再过滤」性能差几十倍。
--
-- 阶段 5 要做意图定向检索："用户问售后政策 → 只在 doc_type = 2 的切片里搜"。
-- 如果这几个字段只在 kb_document 上，每次检索都要 join 一次文档表才能过滤条件，
-- 而 HNSW 索引在 join 场景下往往用不上，会退化成全表扫描 + 逐条算距离 ——
-- 数据量大时这是灾难性的。
--
-- 冗余到 kb_chunk 后，过滤条件和向量索引可以走同一次索引扫描。
-- 用少量存储空间换检索性能，是检索系统的标准优化手段。
