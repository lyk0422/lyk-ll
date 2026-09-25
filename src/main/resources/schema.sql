-- 渔业捕捞配额与渔捞日志核销服务建表脚本（H2 MySQL 兼容模式可执行）。
-- 所有时间字段均为 UTC 时刻；数量单位为千克（kg），保留 3 位小数。

CREATE TABLE IF NOT EXISTS vessel (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vessel_no VARCHAR(32) NOT NULL COMMENT '渔船编号，全局唯一',
    name VARCHAR(64) NOT NULL COMMENT '渔船名称',
    CONSTRAINT uk_vessel_no UNIQUE (vessel_no)
);

CREATE TABLE IF NOT EXISTS species (
    code VARCHAR(16) PRIMARY KEY COMMENT '鱼种编码',
    name VARCHAR(64) NOT NULL COMMENT '鱼种中文名',
    protected_flag BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否保护鱼种：TRUE 表示兼捕受比例限制'
);

CREATE TABLE IF NOT EXISTS quota_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vessel_id BIGINT NOT NULL COMMENT '渔船 ID',
    species_code VARCHAR(16) NOT NULL COMMENT '鱼种编码',
    quota_year INT NOT NULL COMMENT '配额年度（UTC 年）',
    total_quota DECIMAL(19,3) NOT NULL COMMENT '年度核定配额总量（kg）',
    used_quota DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '已核销使用量（kg）',
    frozen BOOLEAN NOT NULL DEFAULT FALSE COMMENT '复核冻结标记：TRUE 期间禁止该船配额核销',
    CONSTRAINT uk_ledger_vessel_species_year UNIQUE (vessel_id, species_code, quota_year)
);

CREATE TABLE IF NOT EXISTS voyage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    voyage_no VARCHAR(40) NOT NULL COMMENT '航次编号，全局唯一',
    vessel_id BIGINT NOT NULL COMMENT '渔船 ID',
    status VARCHAR(16) NOT NULL COMMENT '航次状态：DEPARTED/ARRIVED/SETTLED/CLOSED，CLOSED 为终态',
    departure_time TIMESTAMP NOT NULL COMMENT '出港时刻（UTC）',
    arrival_time TIMESTAMP NULL COMMENT '进港时刻（UTC），未进港为 NULL',
    closed_at TIMESTAMP NULL COMMENT '结案时刻（UTC），未结案为 NULL',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻（UTC）',
    CONSTRAINT uk_voyage_no UNIQUE (voyage_no)
);

CREATE TABLE IF NOT EXISTS departure_declaration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求 ID',
    voyage_id BIGINT NOT NULL COMMENT '航次 ID',
    vessel_id BIGINT NOT NULL COMMENT '渔船 ID',
    fishing_area VARCHAR(32) NOT NULL COMMENT '申报渔区',
    created_at TIMESTAMP NOT NULL COMMENT '申报受理时刻（UTC）',
    CONSTRAINT uk_declaration_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS declaration_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    declaration_id BIGINT NOT NULL COMMENT '出港申报 ID',
    species_code VARCHAR(16) NOT NULL COMMENT '鱼种编码',
    quantity_kg DECIMAL(19,3) NOT NULL COMMENT '申报捕捞量（kg）'
);

CREATE TABLE IF NOT EXISTS logbook_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求 ID',
    voyage_id BIGINT NOT NULL COMMENT '航次 ID',
    vessel_id BIGINT NOT NULL COMMENT '渔船 ID',
    log_date DATE NOT NULL COMMENT '日志归属日期（UTC，按作业时刻日界划分）',
    fishing_area VARCHAR(32) NOT NULL COMMENT '渔区',
    bycatch_flagged BOOLEAN NOT NULL DEFAULT FALSE COMMENT '兼捕超标标注：TRUE 表示保护鱼种占比超过 5%',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻（UTC）',
    CONSTRAINT uk_logbook_vessel_date_area UNIQUE (vessel_id, log_date, fishing_area)
);

CREATE TABLE IF NOT EXISTS logbook_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    logbook_id BIGINT NOT NULL COMMENT '渔捞日志 ID',
    species_code VARCHAR(16) NOT NULL COMMENT '鱼种编码',
    quantity_kg DECIMAL(19,3) NOT NULL COMMENT '捕捞量（kg）',
    operation_time TIMESTAMP NOT NULL COMMENT '作业时刻（UTC），用于跨日归属与乱序归位'
);

CREATE TABLE IF NOT EXISTS arrival_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求 ID',
    voyage_id BIGINT NOT NULL COMMENT '航次 ID',
    arrival_time TIMESTAMP NOT NULL COMMENT '进港时刻（UTC）',
    deviation_flagged BOOLEAN NOT NULL DEFAULT FALSE COMMENT '卸渔偏差标注：TRUE 表示与日志累计偏差超过 3%',
    difference_note VARCHAR(512) NULL COMMENT '差异说明，偏差超 3% 时必填',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻（UTC）',
    CONSTRAINT uk_arrival_request UNIQUE (request_id),
    CONSTRAINT uk_arrival_voyage UNIQUE (voyage_id)
);

CREATE TABLE IF NOT EXISTS arrival_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    arrival_id BIGINT NOT NULL COMMENT '进港报告 ID',
    species_code VARCHAR(16) NOT NULL COMMENT '鱼种编码',
    unloaded_kg DECIMAL(19,3) NOT NULL COMMENT '卸渔量（kg）'
);

CREATE TABLE IF NOT EXISTS settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求 ID',
    voyage_id BIGINT NOT NULL COMMENT '航次 ID',
    settled_at TIMESTAMP NOT NULL COMMENT '核销时刻（UTC）',
    CONSTRAINT uk_settlement_request UNIQUE (request_id),
    CONSTRAINT uk_settlement_voyage UNIQUE (voyage_id)
);

CREATE TABLE IF NOT EXISTS settlement_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    settlement_id BIGINT NOT NULL COMMENT '核销单 ID',
    species_code VARCHAR(16) NOT NULL COMMENT '鱼种编码',
    quantity_kg DECIMAL(19,3) NOT NULL COMMENT '核销扣减量（kg）'
);

CREATE TABLE IF NOT EXISTS review_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    voyage_id BIGINT NOT NULL COMMENT '航次 ID',
    review_type VARCHAR(16) NOT NULL COMMENT '审查类型：BYCATCH 兼捕超标 / DEVIATION 卸渔偏差 / LATE_LOG 结案后迟到日志',
    status VARCHAR(16) NOT NULL COMMENT '审查状态：OPEN 待复核 / RESOLVED 已复核',
    reason VARCHAR(512) NOT NULL COMMENT '触发原因说明',
    resolution VARCHAR(512) NULL COMMENT '复核结论，未复核为 NULL',
    created_at TIMESTAMP NOT NULL COMMENT '立案时刻（UTC）',
    resolved_at TIMESTAMP NULL COMMENT '复核办结时刻（UTC），未复核为 NULL'
);

CREATE TABLE IF NOT EXISTS late_log_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    voyage_id BIGINT NOT NULL COMMENT '航次 ID',
    request_id VARCHAR(64) NOT NULL COMMENT '迟到日志请求 ID',
    detail VARCHAR(512) NOT NULL COMMENT '迟到日志内容摘要',
    created_at TIMESTAMP NOT NULL COMMENT '事件记录时刻（UTC）'
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求 ID，全局唯一',
    endpoint VARCHAR(64) NOT NULL COMMENT '受理接口标识',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹：requestId 与全部业务字段的 SHA-256',
    response_status INT NOT NULL COMMENT '首次受理的 HTTP 状态码',
    response_body CLOB NOT NULL COMMENT '首次受理的响应报文（JSON）',
    created_at TIMESTAMP NOT NULL COMMENT '首次受理时刻（UTC）'
);
