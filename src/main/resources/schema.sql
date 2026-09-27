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

-- 量产上报记录：仅 ACTIVE（含复审观察受理期内）备案可写入
CREATE TABLE IF NOT EXISTS production_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    filing_id BIGINT NOT NULL,
    batch_no VARCHAR(96) NOT NULL,
    quantity_kg DECIMAL(18,6) NOT NULL,
    reported_at BIGINT NOT NULL
);

-- requestId 幂等记录：fingerprint 覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(96) PRIMARY KEY,
    fingerprint VARCHAR(128) NOT NULL,
    status_code INT NOT NULL,
    response_json CLOB,
    created_at BIGINT NOT NULL
);

-- ================= 换配方清线凭证（合成规则，仅演示，不用于生产） =================

-- 过敏原合成目录：allergen_code 为过敏原归组键（花生/乳/蛋等），全部为合成数据
CREATE TABLE IF NOT EXISTS allergen (
    allergen_code VARCHAR(64) PRIMARY KEY,
    allergen_name VARCHAR(128) NOT NULL
);

-- 配方版本过敏原登记：version 为乐观变更版本，登记时为 1，每次修改成功 +1（expectedVersion 并发控制）
CREATE TABLE IF NOT EXISTS formula_allergen_reg (
    formula_id BIGINT PRIMARY KEY,
    version INT NOT NULL,
    request_id VARCHAR(96),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- 配方版本过敏原明细：allergen_code 必须存在于过敏原合成目录；同一配方内不重复
CREATE TABLE IF NOT EXISTS formula_allergen_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    formula_id BIGINT NOT NULL,
    allergen_code VARCHAR(64) NOT NULL,
    UNIQUE (formula_id, allergen_code)
);

-- 生产线：last_formula_id 为最近一次受控量产受理的批准配方版本（NULL 表示登记后尚无量产）；
-- occupied_version 为递增占用版本，登记时为 1，每受理成功一笔受控量产 +1（量产声明显式携带当前值）；
-- version 为乐观变更版本，登记时为 1，每次成功变更加 1
CREATE TABLE IF NOT EXISTS production_line (
    line_code VARCHAR(64) PRIMARY KEY,
    line_name VARCHAR(128) NOT NULL,
    last_formula_id BIGINT,
    occupied_version INT NOT NULL,
    version INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- 清线凭证：操作人、复核人两名不同人员共同确认；固定线路、清线时刻（UTC 毫秒）及确认时占用版本；
-- covered_allergens 为覆盖过敏原编码的结构化 JSON 数组；UTC 确认后 2 小时内有效，恰满 2 小时失效；
-- status 为 VALID 有效 / CONSUMED 已核销；consumed_at 为核销时刻（UTC 毫秒），未核销为空
CREATE TABLE IF NOT EXISTS line_clearance_certificate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_code VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    reviewer_id VARCHAR(64) NOT NULL,
    cleaned_at BIGINT NOT NULL,
    confirmed_at BIGINT NOT NULL,
    occupied_version INT NOT NULL,
    covered_allergens CLOB NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(96),
    consumed_at BIGINT,
    controlled_production_id BIGINT,
    created_at BIGINT NOT NULL
);

-- 受控量产记录（新受控入口，历史不可变）：occupied_version 为该笔量产在该线路上使用的占用版本；
-- certificate_id 为引用的清线凭证（无过敏原切换时为空）；occurred_at 为申报的精确生产时刻（UTC 毫秒）
CREATE TABLE IF NOT EXISTS controlled_production_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_code VARCHAR(64) NOT NULL,
    filing_id BIGINT NOT NULL,
    formula_id BIGINT NOT NULL,
    batch_no VARCHAR(96) NOT NULL,
    quantity_kg DECIMAL(18,6) NOT NULL,
    occupied_version INT NOT NULL,
    certificate_id BIGINT,
    occurred_at BIGINT NOT NULL,
    request_id VARCHAR(96),
    created_at BIGINT NOT NULL
);
