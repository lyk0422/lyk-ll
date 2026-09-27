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
-- line_code/occupied_version 仅受控量产入口写入，原入口保持 NULL；
-- (line_code, occupied_version) 唯一：同一线路同一占用版本至多一笔量产成功
CREATE TABLE IF NOT EXISTS production_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    filing_id BIGINT NOT NULL,
    batch_no VARCHAR(96) NOT NULL,
    quantity_kg DECIMAL(18,6) NOT NULL,
    reported_at BIGINT NOT NULL,
    line_code VARCHAR(64),
    occupied_version INT,
    clearance_certificate_id BIGINT,
    UNIQUE (line_code, occupied_version)
);

-- 过敏原合成目录（仅合成数据，不用于生产）
CREATE TABLE IF NOT EXISTS allergen (
    allergen_code VARCHAR(64) PRIMARY KEY,
    allergen_name VARCHAR(128) NOT NULL
);

-- 配方版本过敏原登记：同一 (product_code, formula_version) 一条；
-- version 为登记乐观版本，从 1 开始，成功变更加 1
CREATE TABLE IF NOT EXISTS formula_allergen (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_code VARCHAR(64) NOT NULL,
    formula_version INT NOT NULL,
    allergens CLOB NOT NULL,
    version INT NOT NULL,
    registered_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (product_code, formula_version)
);

-- 生产线：last_formula_version 为最近一次量产的配方版本（空表示尚未量产）；
-- occupied_version 为递增占用版本，从 1 开始，每次受控量产成功加 1
CREATE TABLE IF NOT EXISTS production_line (
    line_code VARCHAR(64) PRIMARY KEY,
    line_name VARCHAR(128) NOT NULL,
    last_product_code VARCHAR(64),
    last_formula_version INT,
    occupied_version INT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- 清线凭证：操作人与复核人两名不同人员共同确认后生效，固定线路、清线时刻与当时占用版本；
-- UTC 确认后 2 小时内有效（恰满 2 小时失效）；线路随后任何量产提交成功即失效；
-- status 为 ACTIVE 有效 / CONSUMED 已核销 / SUPERSEDED 已被后续量产作废
CREATE TABLE IF NOT EXISTS clearance_certificate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    certificate_no VARCHAR(96) NOT NULL UNIQUE,
    line_code VARCHAR(64) NOT NULL,
    cleared_allergens CLOB NOT NULL,
    operator_code VARCHAR(64) NOT NULL,
    reviewer_code VARCHAR(64) NOT NULL,
    cleared_at BIGINT NOT NULL,
    occupied_version INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    confirmed_at BIGINT NOT NULL,
    consumed_at BIGINT
);

-- requestId 幂等记录：fingerprint 覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(96) PRIMARY KEY,
    fingerprint VARCHAR(128) NOT NULL,
    status_code INT NOT NULL,
    response_json CLOB,
    created_at BIGINT NOT NULL
);
