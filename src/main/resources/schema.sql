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

-- ---------------- 生产线过敏原切换清线凭证门禁 ----------------

-- 过敏原合成目录：仅合成规则，不用于生产
CREATE TABLE IF NOT EXISTS allergen (
    allergen_code VARCHAR(64) PRIMARY KEY,
    allergen_name VARCHAR(128) NOT NULL
);

-- 配方版本过敏原登记（表头行）：version 为登记自身的乐观锁版本，首次登记为 1，每次修改成功 +1；
-- allergen_codes 为过敏原编码集合的结构化 JSON（数组，编码区分大小写，不靠分隔符拼接）；
-- 未登记的配方版本视为未声明任何过敏原
CREATE TABLE IF NOT EXISTS formula_allergen (
    formula_id BIGINT PRIMARY KEY,
    version INT NOT NULL,
    allergen_codes CLOB NOT NULL,
    request_id VARCHAR(96),
    updated_at BIGINT NOT NULL
);

-- 生产线：last_formula_id 为最近一次受控量产的配方版本（NULL 表示线路登记后尚无生产，首产无需凭证）；
-- occupancy_version 为递增占用版本，线路登记时从 1 开始，每笔受控量产成功原子 +1，
-- 受控量产以 expectedVersion 做条件更新，两笔并发最多一笔使用同一占用版本
CREATE TABLE IF NOT EXISTS production_line (
    line_code VARCHAR(64) PRIMARY KEY,
    line_name VARCHAR(128) NOT NULL,
    last_formula_id BIGINT,
    occupancy_version INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- 清线凭证：status 为 VALID 有效 / CONSUMED 已被量产核销 / SUPERSEDED 被后续生产作废旧证；
-- operator_id 操作人与 reviewer_id 复核人必须为两名不同人员；
-- occupied_formula_id 固定凭证确认时线路最近量产配方版本（线路版本），线路推进后旧凭证不可再用；
-- cleaned_at 为清线确认精确时刻（UTC epoch 毫秒），有效期 [cleaned_at, cleaned_at + 2h)，恰满 2 小时失效；
-- allergen_codes 为覆盖过敏原编码集合的结构化 JSON
CREATE TABLE IF NOT EXISTS cleaning_certificate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_code VARCHAR(64) NOT NULL,
    expected_version INT NOT NULL,
    occupied_formula_id BIGINT,
    operator_id VARCHAR(64) NOT NULL,
    reviewer_id VARCHAR(64) NOT NULL,
    cleaned_at BIGINT NOT NULL,
    allergen_codes CLOB NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(96),
    consumed_at BIGINT,
    created_at BIGINT NOT NULL
);

-- 受控量产记录（经过敏原清线门禁）：occupancy_version 为受理时使用的线路占用版本，
-- certificate_id 为本次引用的清线凭证（无需凭证时为 NULL）；
-- occurred_at 为客户端声明的精确生产时刻（UTC epoch 毫秒）
CREATE TABLE IF NOT EXISTS line_production_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_code VARCHAR(64) NOT NULL,
    occupancy_version INT NOT NULL,
    formula_id BIGINT NOT NULL,
    filing_id BIGINT NOT NULL,
    certificate_id BIGINT,
    batch_no VARCHAR(96) NOT NULL,
    quantity_kg DECIMAL(18,6) NOT NULL,
    occurred_at BIGINT NOT NULL,
    request_id VARCHAR(96),
    created_at BIGINT NOT NULL,
    UNIQUE (line_code, occupancy_version)
);
