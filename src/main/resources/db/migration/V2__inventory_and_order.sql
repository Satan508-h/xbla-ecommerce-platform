-- ============================================================
-- V2 · 库存域 + 交易域
--
-- 表：inventory / orders / order_item
-- 依据：docs/04-数据库设计.md 第四节、第五节
--
-- ⚠️ 已执行过的迁移文件不要改，要改请新增 V6
-- ============================================================


-- ============================================================
-- 库存域
-- ============================================================

CREATE TABLE inventory
(
    id               BIGSERIAL   PRIMARY KEY,
    sku_id           BIGINT      NOT NULL,
    total_stock      INT         NOT NULL DEFAULT 0,
    available_stock  INT         NOT NULL DEFAULT 0,
    locked_stock     INT         NOT NULL DEFAULT 0,
    warehouse        VARCHAR(64),
    version          INT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 一个 SKU 对应一条库存记录
    CONSTRAINT uk_inventory_sku_id UNIQUE (sku_id),
    CONSTRAINT fk_inventory_sku
        FOREIGN KEY (sku_id) REFERENCES product_sku (id),

    -- ★ 这三条 CHECK 是「最后一道防线」：
    --   即使应用层逻辑写错了，数据库也会拒绝把库存扣成负数。
    --   阶段 6 压测 100 并发时，如果出现超卖，这些约束会直接让事务失败，
    --   而不是静静地写进一条负库存记录。
    CONSTRAINT ck_inventory_available CHECK (available_stock >= 0),
    CONSTRAINT ck_inventory_locked    CHECK (locked_stock >= 0),
    CONSTRAINT ck_inventory_total     CHECK (total_stock >= 0)
);

COMMENT ON TABLE  inventory                  IS '库存表，一个 SKU 一条记录';
COMMENT ON COLUMN inventory.available_stock  IS '可售库存。MCP 工具「库存查询」读的就是这个字段';
COMMENT ON COLUMN inventory.locked_stock     IS '锁定库存（已下单未付款）。付款后扣减，取消订单则归还到可售';
COMMENT ON COLUMN inventory.version          IS '乐观锁版本号。并发扣减时 WHERE version = ? 防止覆盖';

-- 为什么拆成「可售/锁定」两个字段而不是只留一个 stock：
--   下单流程是「锁定 → 付款 → 真正扣减」，中间有个时间窗口。
--   只用一个字段的话，下单瞬间库存就减了，用户不付款就一直占着库存（恶意占库存）。
--   拆开后：下单 available-=n, locked+=n；付款 locked-=n；取消则反向。


-- ============================================================
-- 交易域
-- ============================================================

-- 表名用复数 orders：order 是 SQL 关键字（ORDER BY），单数做表名容易踩坑
CREATE TABLE orders
(
    id                BIGSERIAL     PRIMARY KEY,
    order_no          VARCHAR(64)   NOT NULL,
    user_id           BIGINT        NOT NULL,
    total_amount      NUMERIC(12, 2) NOT NULL,
    discount_amount   NUMERIC(12, 2) NOT NULL DEFAULT 0,
    pay_amount        NUMERIC(12, 2) NOT NULL,
    status            SMALLINT      NOT NULL,
    receiver_name     VARCHAR(64),
    receiver_phone    VARCHAR(32),
    receiver_address  VARCHAR(512),
    logistics_no      VARCHAR(64),
    logistics_company VARCHAR(64),
    paid_at           TIMESTAMPTZ,
    shipped_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    cancelled_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted           SMALLINT      NOT NULL DEFAULT 0,

    CONSTRAINT uk_orders_order_no UNIQUE (order_no),
    CONSTRAINT fk_orders_user
        FOREIGN KEY (user_id) REFERENCES app_user (id),

    -- 实付金额不能是负数（优惠金额超过了商品总额说明逻辑有问题）
    CONSTRAINT ck_orders_pay_amount CHECK (pay_amount >= 0),
    CONSTRAINT ck_orders_total_amount CHECK (total_amount >= 0),
    CONSTRAINT ck_orders_discount_amount CHECK (discount_amount >= 0),

    -- 状态机取值范围。用数字存状态而不是中文：
    --   存 '待发货' 这种文案，将来改文案要 UPDATE 全表；
    --   存数字，展示文案由应用层映射，改文案不动数据。
    CONSTRAINT ck_orders_status CHECK (status IN (10, 20, 30, 40, 50))
);

COMMENT ON TABLE  orders          IS '订单主表';
COMMENT ON COLUMN orders.status   IS '订单状态：10待付款 20待发货 30已发货 40已完成 50已取消';
COMMENT ON COLUMN orders.pay_amount IS '实付金额 = total_amount - discount_amount';

-- 状态机流转：
--   10 待付款 ──付款──> 20 待发货 ──发货──> 30 已发货 ──签收──> 40 已完成
--      │                  │
--      └──取消──> 50 已取消 <──┘

CREATE INDEX idx_orders_user_id    ON orders (user_id);
CREATE INDEX idx_orders_status     ON orders (status);
CREATE INDEX idx_orders_created_at ON orders (created_at DESC);


CREATE TABLE order_item
(
    id           BIGSERIAL     PRIMARY KEY,
    order_id     BIGINT        NOT NULL,
    product_id   BIGINT        NOT NULL,
    sku_id       BIGINT        NOT NULL,

    -- ↓ 这三个是「快照字段」，见下方说明
    product_name VARCHAR(255)  NOT NULL,
    spec_name    VARCHAR(255)  NOT NULL,
    price        NUMERIC(12, 2) NOT NULL,

    quantity     INT           NOT NULL,
    subtotal     NUMERIC(12, 2) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT fk_order_item_order   FOREIGN KEY (order_id)   REFERENCES orders (id),
    CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT fk_order_item_sku     FOREIGN KEY (sku_id)     REFERENCES product_sku (id),
    CONSTRAINT ck_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_item_subtotal CHECK (subtotal >= 0)
);

COMMENT ON TABLE  order_item              IS '订单明细表';
COMMENT ON COLUMN order_item.product_name IS '★快照：下单时的商品名。商品改名后历史订单必须显示当时的名字';
COMMENT ON COLUMN order_item.spec_name    IS '★快照：下单时的规格';
COMMENT ON COLUMN order_item.price        IS '★快照：下单时的单价。商品调价不影响历史订单';

-- 为什么把商品名/规格/单价冗余存一份（面试常问）：
--   商品是会变的。如果这里只存 product_id，回看历史订单时 join 到的是
--   「现在的」商品信息——用户会看到"我明明 99 买的，怎么显示 199"。
--   product_id 用于关联查询，快照字段用于展示，两者用途不同，都要保留。
--   这是交易系统的标准做法，叫「快照冗余」。

CREATE INDEX idx_order_item_order_id ON order_item (order_id);
CREATE INDEX idx_order_item_product  ON order_item (product_id);
