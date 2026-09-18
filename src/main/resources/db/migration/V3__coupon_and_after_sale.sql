-- ============================================================
-- V3 · 营销域 + 售后域
--
-- 表：coupon / user_coupon / after_sale_policy
-- 依据：docs/04-数据库设计.md 第六节、第七节
-- ============================================================


-- ============================================================
-- 营销域
-- ============================================================

CREATE TABLE coupon
(
    id                   BIGSERIAL     PRIMARY KEY,
    coupon_no            VARCHAR(64)   NOT NULL,
    name                 VARCHAR(128)  NOT NULL,
    type                 SMALLINT      NOT NULL,
    discount_value       NUMERIC(10, 2),
    discount_rate        NUMERIC(5, 4),
    threshold_amount     NUMERIC(12, 2) NOT NULL DEFAULT 0,
    applicable_category  VARCHAR(64),
    total_count          INT           NOT NULL,
    issued_count         INT           NOT NULL DEFAULT 0,
    valid_from           TIMESTAMPTZ   NOT NULL,
    valid_to             TIMESTAMPTZ   NOT NULL,
    status               SMALLINT      NOT NULL DEFAULT 1,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted              SMALLINT      NOT NULL DEFAULT 0,

    CONSTRAINT uk_coupon_coupon_no UNIQUE (coupon_no),

    CONSTRAINT ck_coupon_type          CHECK (type IN (1, 2, 3)),
    CONSTRAINT ck_coupon_status        CHECK (status IN (0, 1)),
    CONSTRAINT ck_coupon_threshold     CHECK (threshold_amount >= 0),
    CONSTRAINT ck_coupon_valid_range   CHECK (valid_to > valid_from),

    -- ★ 防超发：已领取量不能超过发行总量。
    --   这是一条「业务规则落到数据库」的例子——即使并发下应用层算错了，
    --   数据库也会拒绝写入，而不是发出一张不存在的优惠券。
    CONSTRAINT ck_coupon_issued        CHECK (issued_count <= total_count),
    CONSTRAINT ck_coupon_total_count   CHECK (total_count >= 0),
    CONSTRAINT ck_coupon_issued_count  CHECK (issued_count >= 0),

    -- 满减/立减必须有金额，折扣必须有折扣率，且折扣率在 0~1 之间
    CONSTRAINT ck_coupon_value CHECK (
        (type IN (1, 3) AND discount_value IS NOT NULL AND discount_value > 0)
        OR
        (type = 2 AND discount_rate IS NOT NULL AND discount_rate > 0 AND discount_rate < 1)
    )
);

COMMENT ON TABLE  coupon                  IS '优惠券模板表（券的定义，不是某个用户持有的券）';
COMMENT ON COLUMN coupon.type             IS '券类型：1满减 2折扣 3立减';
COMMENT ON COLUMN coupon.discount_value   IS '满减/立减的减免金额';
COMMENT ON COLUMN coupon.discount_rate    IS '折扣率，如 0.85 表示 85 折';
COMMENT ON COLUMN coupon.threshold_amount IS '使用门槛，订单满多少才能用。0 表示无门槛';
COMMENT ON COLUMN coupon.applicable_category IS '适用类目，NULL 表示全场通用';
COMMENT ON COLUMN coupon.total_count      IS '发行总量';
COMMENT ON COLUMN coupon.issued_count     IS '已领取量，受 CHECK 约束不得超过 total_count';

CREATE INDEX idx_coupon_status_valid ON coupon (status, valid_to) WHERE deleted = 0;


CREATE TABLE user_coupon
(
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    coupon_id   BIGINT      NOT NULL,
    status      SMALLINT    NOT NULL DEFAULT 1,
    order_id    BIGINT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at     TIMESTAMPTZ,
    expired_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_user_coupon_user   FOREIGN KEY (user_id)   REFERENCES app_user (id),
    CONSTRAINT fk_user_coupon_coupon FOREIGN KEY (coupon_id) REFERENCES coupon (id),
    CONSTRAINT fk_user_coupon_order  FOREIGN KEY (order_id)  REFERENCES orders (id),

    CONSTRAINT ck_user_coupon_status CHECK (status IN (1, 2, 3)),

    -- 状态和时间必须自洽：已使用的券必须有使用时间，未使用的不能有
    CONSTRAINT ck_user_coupon_used_at CHECK (
        (status = 2 AND used_at IS NOT NULL AND order_id IS NOT NULL)
        OR
        (status <> 2 AND used_at IS NULL)
    ),

    -- 同一个用户同一张券模板只能领一次
    CONSTRAINT uk_user_coupon UNIQUE (user_id, coupon_id)
);

COMMENT ON TABLE  user_coupon         IS '用户持有的优惠券（多对多关系表：用户 × 券模板）';
COMMENT ON COLUMN user_coupon.status  IS '券状态：1未使用 2已使用 3已过期';
COMMENT ON COLUMN user_coupon.order_id IS '用在哪笔订单上（未使用时为 NULL）';

-- MCP 工具「优惠券查询」的典型查询：
--   WHERE user_id = ? AND status = 1 AND expired_at > now()
-- 这个复合索引正好覆盖它
CREATE INDEX idx_user_coupon_user_status ON user_coupon (user_id, status);


-- ============================================================
-- 售后域
-- ============================================================

CREATE TABLE after_sale_policy
(
    id             BIGSERIAL    PRIMARY KEY,
    policy_no      VARCHAR(64)  NOT NULL,
    category       VARCHAR(64),
    title          VARCHAR(255) NOT NULL,
    content        TEXT         NOT NULL,
    return_days    INT,
    exchange_days  INT,
    conditions     TEXT,
    effective_from TIMESTAMPTZ,
    effective_to   TIMESTAMPTZ,
    version        INT          NOT NULL DEFAULT 1,
    status         SMALLINT     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted        SMALLINT     NOT NULL DEFAULT 0,

    CONSTRAINT uk_after_sale_policy_no UNIQUE (policy_no),
    CONSTRAINT ck_after_sale_policy_status  CHECK (status IN (0, 1)),
    CONSTRAINT ck_after_sale_policy_version CHECK (version >= 1),
    CONSTRAINT ck_after_sale_return_days    CHECK (return_days IS NULL OR return_days >= 0),
    CONSTRAINT ck_after_sale_exchange_days  CHECK (exchange_days IS NULL OR exchange_days >= 0),
    CONSTRAINT ck_after_sale_effective      CHECK (
        effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from
    )
);

COMMENT ON TABLE  after_sale_policy              IS '售后政策表。content 是知识库语料来源之一';
COMMENT ON COLUMN after_sale_policy.content      IS '政策正文，阶段 3 会被切分向量化进 kb_chunk';
COMMENT ON COLUMN after_sale_policy.return_days  IS '可退货天数。供 MCP 工具做精确查询';
COMMENT ON COLUMN after_sale_policy.exchange_days IS '可换货天数';
COMMENT ON COLUMN after_sale_policy.conditions   IS '附加条件，如"拆封后不支持无理由退货"';
COMMENT ON COLUMN after_sale_policy.version      IS '版本号。政策改版时新增记录而不是覆盖，便于评测追溯';

-- 为什么同时有 content（自由文本）和 return_days（结构化字段）：
--   两者服务不同场景，缺一不可 ——
--   content 供 RAG 语义检索，能回答"我拆封了还能退吗"这类答案藏在文本里的问题；
--   return_days 供 MCP 工具精确查询（"退货政策是几天"直接 SELECT，比向量检索更准更快）。
--   这是「结构化 + 非结构化双轨」的典型做法。

CREATE INDEX idx_after_sale_policy_category ON after_sale_policy (category) WHERE deleted = 0;
