-- ============================================================
-- V1 · 用户域 + 商品域
--
-- 表：app_user / product / product_sku / product_attribute
-- 依据：docs/04-数据库设计.md 第二节、第三节
--
-- ⚠️ 这个文件一旦执行过就不要再改！Flyway 会校验 checksum，
--    改了会导致应用启动失败。要改表结构请新增 V6__xxx.sql。
-- ============================================================


-- ============================================================
-- 用户域
-- ============================================================

-- 为什么不叫 user：USER 是 SQL 标准保留字（等价于 CURRENT_USER），
-- 用它做表名以后每条 SQL 都得写成 SELECT * FROM "user"，漏引号就报错。
CREATE TABLE app_user
(
    id           BIGSERIAL    PRIMARY KEY,
    user_no      VARCHAR(64)  NOT NULL,
    nickname     VARCHAR(64)  NOT NULL,
    phone        VARCHAR(32),
    member_level SMALLINT     NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      SMALLINT     NOT NULL DEFAULT 0,

    CONSTRAINT uk_app_user_user_no UNIQUE (user_no),
    -- 会员等级限定取值范围，防止写入无意义的值
    CONSTRAINT ck_app_user_member_level CHECK (member_level BETWEEN 1 AND 4)
);

COMMENT ON TABLE  app_user              IS '用户表';
COMMENT ON COLUMN app_user.user_no      IS '用户编号，对外展示用，不暴露自增 ID';
COMMENT ON COLUMN app_user.phone        IS '手机号，脱敏存储（如 138****8888）';
COMMENT ON COLUMN app_user.member_level IS '会员等级：1普通 2银卡 3金卡 4钻石';
COMMENT ON COLUMN app_user.deleted      IS '软删除：0正常 1已删除';


-- ============================================================
-- 商品域
-- ============================================================

CREATE TABLE product
(
    id              BIGSERIAL     PRIMARY KEY,
    product_no      VARCHAR(64)   NOT NULL,
    name            VARCHAR(255)  NOT NULL,
    category        VARCHAR(64)   NOT NULL,
    sub_category    VARCHAR(64),
    brand           VARCHAR(64),
    price           NUMERIC(12, 2) NOT NULL,
    original_price  NUMERIC(12, 2),
    description     TEXT,
    -- ↓ 这两个字段是为 RAG 场景专门加的，见设计文档第三节
    selling_points  TEXT,
    suitable_for    VARCHAR(512),
    status          SMALLINT      NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted         SMALLINT      NOT NULL DEFAULT 0,

    CONSTRAINT uk_product_product_no UNIQUE (product_no),
    -- 金额不能为负。数据库层兜底，防止应用层算错写出脏数据
    CONSTRAINT ck_product_price CHECK (price >= 0),
    CONSTRAINT ck_product_original_price CHECK (original_price IS NULL OR original_price >= 0),
    CONSTRAINT ck_product_status CHECK (status IN (0, 1))
);

COMMENT ON TABLE  product                 IS '商品表（SPU，即"某款商品"，具体型号在 product_sku）';
COMMENT ON COLUMN product.product_no      IS '商品编号，对外展示用';
COMMENT ON COLUMN product.selling_points  IS '卖点，如"续航18小时/重量1.2kg"。为 RAG 语义检索准备';
COMMENT ON COLUMN product.suitable_for    IS '适用人群/场景，如"适合送长辈、商务人士"。解决"这个能送老人吗"类问题的检索泛化';
COMMENT ON COLUMN product.status          IS '上架状态：1上架 0下架';

-- 按类目筛选是最高频的查询，建索引
CREATE INDEX idx_product_category ON product (category) WHERE deleted = 0;
-- 按价格排序（"3000 元以内的手机"）
CREATE INDEX idx_product_price    ON product (price)    WHERE deleted = 0;


CREATE TABLE product_sku
(
    id          BIGSERIAL     PRIMARY KEY,
    product_id  BIGINT        NOT NULL,
    sku_no      VARCHAR(64)   NOT NULL,
    spec_name   VARCHAR(255)  NOT NULL,
    spec_json   JSONB,
    price       NUMERIC(12, 2) NOT NULL,
    barcode     VARCHAR(64),
    status      SMALLINT      NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted     SMALLINT      NOT NULL DEFAULT 0,

    CONSTRAINT uk_product_sku_sku_no UNIQUE (sku_no),
    CONSTRAINT fk_product_sku_product
        FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT ck_product_sku_price CHECK (price >= 0),
    CONSTRAINT ck_product_sku_status CHECK (status IN (0, 1))
);

COMMENT ON TABLE  product_sku            IS '商品 SKU 表（可购买的具体型号）';
COMMENT ON COLUMN product_sku.spec_name  IS '规格描述，如"256G 星空黑"';
COMMENT ON COLUMN product_sku.spec_json  IS '结构化规格，如 {"颜色":"星空黑","存储":"256G"}。不同类目规格维度不同，用 JSONB 避免建大量空列';

CREATE INDEX idx_product_sku_product_id ON product_sku (product_id);
-- GIN 索引让 spec_json @> '{"颜色":"星空黑"}' 这类包含查询能走索引
CREATE INDEX idx_product_sku_spec_json  ON product_sku USING gin (spec_json);


CREATE TABLE product_attribute
(
    id          BIGSERIAL    PRIMARY KEY,
    product_id  BIGINT       NOT NULL,
    attr_group  VARCHAR(64)  NOT NULL,
    attr_name   VARCHAR(64)  NOT NULL,
    attr_value  VARCHAR(255) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_product_attribute_product
        FOREIGN KEY (product_id) REFERENCES product (id),
    -- 同一商品的同一分组下，参数名不能重复
    CONSTRAINT uk_product_attribute UNIQUE (product_id, attr_group, attr_name)
);

COMMENT ON TABLE  product_attribute            IS '商品参数表（如"屏幕尺寸 6.7英寸"），与 product_sku（可购买型号）是两回事';
COMMENT ON COLUMN product_attribute.attr_group IS '参数分组，如"基本参数"、"屏幕"、"摄像头"';

CREATE INDEX idx_product_attribute_product_id ON product_attribute (product_id);
