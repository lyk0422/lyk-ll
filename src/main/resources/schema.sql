-- 食品配方合规服务建表脚本（H2 MODE=MySQL，单位与时间含义见各表注释）

-- 食品分类目录
CREATE TABLE IF NOT EXISTS food_category (
    category_code VARCHAR(64) PRIMARY KEY,
    category_name VARCHAR(128) NOT NULL
);

-- 添加剂目录：function_code 为功能类别（PRESERVATIVE 防腐剂 / COLORANT 着色剂 等同功能归组键）
CREATE TABLE IF NOT EXISTS additive (
    additive_code VARCHAR(64) PRIMARY KEY,
    additive_name VARCHAR(128) NOT NULL,
    function_code VARCHAR(64) NOT NULL
);

-- 复合配料目录
CREATE TABLE IF NOT EXISTS compound_ingredient (
    ingredient_code VARCHAR(64) PRIMARY KEY,
    ingredient_name VARCHAR(128) NOT NULL
);

-- 复合配料中添加剂本底含量：concentration 单位 g/kg（每千克配料中添加剂克数）
CREATE TABLE IF NOT EXISTS ingredient_additive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ingredient_code VARCHAR(64) NOT NULL,
    additive_code VARCHAR(64) NOT NULL,
    concentration DECIMAL(18,6) NOT NULL
);

-- 添加剂限量标准：max_amount 单位 g/kg（每千克终产品中允许的最大克数），恰等限量合法
CREATE TABLE IF NOT EXISTS additive_limit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    additive_code VARCHAR(64) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    max_amount DECIMAL(18,6) NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (additive_code, category_code)
);

-- 配方主表：state 为 SUBMITTED/REVIEWED/APPROVED 状态；版本在批准时冻结快照
CREATE TABLE IF NOT EXISTS formula (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_code VARCHAR(64) NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    state VARCHAR(32) NOT NULL,
    request_id VARCHAR(96),
    review_passed BOOLEAN,
    review_json CLOB,
    reviewed_at BIGINT,
    -- 批准时冻结的标准限量与逐项判定快照（JSON），量产备案按该快照执行
    approved_snapshot CLOB,
    approved_at BIGINT,
    created_at BIGINT NOT NULL,
    UNIQUE (product_code, version)
);

-- 配方直接添加的添加剂：amount 单位 g/kg 终产品
CREATE TABLE IF NOT EXISTS formula_direct_additive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    formula_id BIGINT NOT NULL,
    additive_code VARCHAR(64) NOT NULL,
    amount DECIMAL(18,6) NOT NULL
);

-- 配方使用的复合配料：ratio 为配料占终产品质量比（0~1 小数，无量纲）
CREATE TABLE IF NOT EXISTS formula_ingredient (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    formula_id BIGINT NOT NULL,
    ingredient_code VARCHAR(64) NOT NULL,
    ratio DECIMAL(18,6) NOT NULL
);

-- 量产备案：status 为 ACTIVE 有效 / REVIEW_WATCH 复审观察 / SUSPENDED 暂停 / CANCELLED 注销终态
CREATE TABLE IF NOT EXISTS filing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    formula_id BIGINT NOT NULL UNIQUE,
    product_code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_id VARCHAR(96),
    snapshot CLOB,
    -- 复审观察起始 UTC 日（epochDay），为空表示未进入观察；受理截止日 = 起始日 + 90
    watch_start_day BIGINT,
    suspend_reason VARCHAR(64),
    created_at BIGINT NOT NULL,
    finalized_at BIGINT
);

-- 同一产品当前唯一有效（ACTIVE / REVIEW_WATCH 均视为有效占用）备案占用表
CREATE TABLE IF NOT EXISTS active_filing (
    product_code VARCHAR(64) PRIMARY KEY,
    filing_id BIGINT NOT NULL
);

-- 备案复审记录
CREATE TABLE IF NOT EXISTS filing_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    filing_id BIGINT NOT NULL,
    request_id VARCHAR(96),
    passed BOOLEAN NOT NULL,
    report_json CLOB,
    created_at BIGINT NOT NULL
);

-- 量产上报记录：仅 ACTIVE（含复审观察受理期内）备案可写入；
-- line_code/voucher_id/line_occupancy_version 仅受控量产入口写入，旧入口记录为 NULL
CREATE TABLE IF NOT EXISTS production_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    filing_id BIGINT NOT NULL,
    batch_no VARCHAR(96) NOT NULL,
    quantity_kg DECIMAL(18,6) NOT NULL,
    reported_at BIGINT NOT NULL,
    -- 受控量产的生产线编码；旧入口（未指定生产线）为 NULL
    line_code VARCHAR(64),
    -- 本次量产核销的清线凭证；无需凭证或旧入口为 NULL
    voucher_id BIGINT,
    -- 本次量产声明并占用的线路占用版本（受理时校验、成功后递增）
    line_occupancy_version INT
);

-- 过敏原合成目录
CREATE TABLE IF NOT EXISTS allergen (
    allergen_code VARCHAR(64) PRIMARY KEY,
    allergen_name VARCHAR(128) NOT NULL
);

-- 配方版本过敏原登记：version 从 1 开始，每次成功变更 +1（修改须携带 expectedVersion 乐观锁）
CREATE TABLE IF NOT EXISTS formula_allergen (
    formula_id BIGINT PRIMARY KEY,
    version INT NOT NULL,
    request_id VARCHAR(96),
    updated_at BIGINT NOT NULL
);

-- 配方版本过敏原集合明细
CREATE TABLE IF NOT EXISTS formula_allergen_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    formula_id BIGINT NOT NULL,
    allergen_code VARCHAR(64) NOT NULL,
    UNIQUE (formula_id, allergen_code)
);

-- 生产线：last_formula_id 为最近一次量产配方版本（NULL 表示尚未量产）；
-- occupancy_version 为递增占用版本，从 1 开始，每笔受控量产成功 +1
CREATE TABLE IF NOT EXISTS production_line (
    line_code VARCHAR(64) PRIMARY KEY,
    last_formula_id BIGINT,
    occupancy_version INT NOT NULL,
    request_id VARCHAR(96),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- 清线凭证：操作人与复核人须为两名不同人员共同确认；
-- 固定线路、清线时刻（cleaned_at，UTC epoch 毫秒）及确认时占用版本；
-- 确认后 2 小时内（UTC）有效，恰满 2 小时失效；线路随后任何量产先提交即失效；
-- status 为 ISSUED 有效 / CONSUMED 已核销
CREATE TABLE IF NOT EXISTS cleaning_voucher (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_code VARCHAR(64) NOT NULL,
    cleaned_at BIGINT NOT NULL,
    confirmed_at BIGINT NOT NULL,
    occupancy_version INT NOT NULL,
    operator_name VARCHAR(64) NOT NULL,
    reviewer_name VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(96),
    consumed_at BIGINT,
    created_at BIGINT NOT NULL
);

-- 清线凭证覆盖的过敏原集合明细
CREATE TABLE IF NOT EXISTS cleaning_voucher_allergen (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_id BIGINT NOT NULL,
    allergen_code VARCHAR(64) NOT NULL,
    UNIQUE (voucher_id, allergen_code)
);

-- requestId 幂等记录：fingerprint 覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(96) PRIMARY KEY,
    fingerprint VARCHAR(128) NOT NULL,
    status_code INT NOT NULL,
    response_json CLOB,
    created_at BIGINT NOT NULL
);
