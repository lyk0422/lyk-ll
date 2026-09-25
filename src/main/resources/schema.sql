-- 本地取水监管服务建表脚本（H2 MySQL 兼容模式，同时兼容 MySQL 基本语法）
-- 水量单位统一为立方米（m3），时刻统一为 UTC。

-- 取水户：每自然年持有相同的年度许可总量基数（合成数据）
CREATE TABLE IF NOT EXISTS water_user (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_code    VARCHAR(64)  NOT NULL COMMENT '取水户编码',
    user_name    VARCHAR(128) NOT NULL COMMENT '取水户名称',
    reach_code   VARCHAR(64)  NOT NULL COMMENT '所属河段编码',
    annual_quota DECIMAL(18,3) NOT NULL COMMENT '年度许可总量基数，单位立方米',
    CONSTRAINT uq_water_user_code UNIQUE (user_code)
);

-- 河段生态流量限取状态：行预先存在，触发即更新，便于与申报并发时加行锁排序
CREATE TABLE IF NOT EXISTS reach_restriction (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    reach_code   VARCHAR(64) NOT NULL COMMENT '河段编码',
    active       BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '是否处于限取状态：1 限取中，0 正常',
    effective_at TIMESTAMP(3) NULL COMMENT '限取生效时刻(UTC)，空表示未触发',
    CONSTRAINT uq_reach_restriction_code UNIQUE (reach_code)
);

-- 计量点：归属某一取水户
CREATE TABLE IF NOT EXISTS meter_point (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    point_code VARCHAR(64) NOT NULL COMMENT '计量点编码',
    user_id    BIGINT      NOT NULL COMMENT '所属取水户ID',
    CONSTRAINT uq_meter_point_code UNIQUE (point_code)
);

-- 年度额度台账：base_quota 为许可基数，carryover_deduction 为上年超限 1:1 结转扣减额
CREATE TABLE IF NOT EXISTS quota_ledger (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT NOT NULL COMMENT '取水户ID',
    quota_year          INT    NOT NULL COMMENT '额度年度',
    base_quota          DECIMAL(18,3) NOT NULL COMMENT '该年许可总量基数，单位立方米',
    carryover_deduction DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '上年超许可结转扣减额(1:1)，单位立方米，扣减本年额度',
    CONSTRAINT uq_quota_ledger_user_year UNIQUE (user_id, quota_year)
);

-- 月度取水计划申报：仅受理的确认版本入库，version 递增
CREATE TABLE IF NOT EXISTS monthly_declaration (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL COMMENT '取水户ID',
    plan_month      VARCHAR(7) NOT NULL COMMENT '申报自然月(UTC)，格式 yyyy-MM',
    version         INT NOT NULL COMMENT '该月已确认版本号，从1递增',
    planned_amount  DECIMAL(18,3) NOT NULL COMMENT '申报水量，单位立方米',
    accepted_amount DECIMAL(18,3) NOT NULL COMMENT '实际受理水量，限取期间为申报量一半，单位立方米',
    restricted      BOOLEAN NOT NULL DEFAULT FALSE COMMENT '受理时是否处于限取减半：1 是，0 否',
    request_id      VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    created_at      TIMESTAMP(3) NOT NULL COMMENT '确认入库时刻(UTC)',
    CONSTRAINT uq_declaration_user_month_version UNIQUE (user_id, plan_month, version)
);

-- 年度计划封存：封存后为终态
CREATE TABLE IF NOT EXISTS annual_plan_seal (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id   BIGINT NOT NULL COMMENT '取水户ID',
    quota_year INT   NOT NULL COMMENT '封存年度',
    sealed_at TIMESTAMP(3) NOT NULL COMMENT '封存时刻(UTC)',
    CONSTRAINT uq_plan_seal_user_year UNIQUE (user_id, quota_year)
);

-- 申报幂等记录：requestId 为主键，记录首次指纹与首次结果
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id      VARCHAR(64) PRIMARY KEY COMMENT '幂等请求ID',
    user_id         BIGINT NOT NULL COMMENT '取水户ID',
    plan_month      VARCHAR(7) NOT NULL COMMENT '申报月份 yyyy-MM',
    planned_amount  DECIMAL(18,3) NOT NULL COMMENT '首次请求的申报水量指纹，单位立方米',
    status          VARCHAR(16) NOT NULL COMMENT '首次结果状态：CONFIRMED 已确认 / REJECTED 已拒绝',
    accepted_amount DECIMAL(18,3) NOT NULL COMMENT '首次结果受理水量，单位立方米',
    declaration_id  BIGINT NULL COMMENT '首次确认生成的申报版本ID，拒绝时为空',
    reason          VARCHAR(64) NULL COMMENT '拒绝原因码：QUOTA_EXCEEDED 超额 / PLAN_SEALED 已封存，确认时为空',
    created_at      TIMESTAMP(3) NOT NULL COMMENT '首次请求时刻(UTC)'
);

-- 计量读数：按计量点+表底时刻去重，增量按表底时刻归月
CREATE TABLE IF NOT EXISTS meter_reading (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    meter_point        VARCHAR(64) NOT NULL COMMENT '计量点编码',
    user_id            BIGINT NOT NULL COMMENT '读数归属取水户ID',
    read_at            TIMESTAMP(3) NOT NULL COMMENT '表底时刻(UTC)',
    bucket_month       VARCHAR(7) NOT NULL COMMENT '按表底时刻归月(UTC)，格式 yyyy-MM',
    cumulative_reading DECIMAL(18,3) NOT NULL COMMENT '表底累计读数，单位立方米',
    created_at         TIMESTAMP(3) NOT NULL COMMENT '读数入库时刻(UTC)',
    CONSTRAINT uq_meter_reading_point_time UNIQUE (meter_point, read_at)
);
