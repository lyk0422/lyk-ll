-- 天然气交接计量服务建表脚本（H2 MySQL 兼容模式；MySQL 下语法同样兼容）
-- 所有时刻均为 UTC，气量日按 UTC 日界划分。

CREATE TABLE IF NOT EXISTS station (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code VARCHAR(64)  NOT NULL COMMENT '交接站编码',
    station_name VARCHAR(128) NOT NULL COMMENT '交接站名称',
    party_a      VARCHAR(128) NOT NULL COMMENT '交出方名称',
    party_b      VARCHAR(128) NOT NULL COMMENT '接收方名称',
    created_at   TIMESTAMP    NOT NULL COMMENT '创建时刻(UTC)',
    CONSTRAINT uk_station_code UNIQUE (station_code)
);

CREATE TABLE IF NOT EXISTS daily_nomination (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code        VARCHAR(64)   NOT NULL COMMENT '交接站编码',
    gas_day             DATE          NOT NULL COMMENT '气量日(UTC)',
    nominated_energy_mj DECIMAL(18,3) NOT NULL COMMENT '日指定能量，单位 MJ',
    penalty_unit_price  DECIMAL(12,6) NOT NULL COMMENT '超出免考区间违约金单价，单位 元/MJ',
    created_at          TIMESTAMP     NOT NULL COMMENT '创建时刻(UTC)',
    CONSTRAINT uk_nomination_station_day UNIQUE (station_code, gas_day)
);

CREATE TABLE IF NOT EXISTS meter_reading (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code  VARCHAR(64)   NOT NULL COMMENT '交接站编码',
    meter_id      VARCHAR(64)   NOT NULL COMMENT '流量计编号',
    reading_time  TIMESTAMP(3)  NOT NULL COMMENT '表底时刻(UTC)',
    meter_index   DECIMAL(18,3) NOT NULL COMMENT '累计表底（标况体积），单位 Nm3',
    heating_value DECIMAL(10,4) NOT NULL COMMENT '该采样点热值，单位 MJ/Nm3',
    received_at   TIMESTAMP(3)  NOT NULL COMMENT '读数入库时刻(UTC)',
    is_late       BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '是否为对应凭证生成后到达的迟到读数',
    created_at    TIMESTAMP(3)  NOT NULL COMMENT '记录创建时刻(UTC)',
    CONSTRAINT uk_reading_station_time UNIQUE (station_code, reading_time)
);

CREATE TABLE IF NOT EXISTS voucher (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_no        VARCHAR(64)   NOT NULL COMMENT '凭证号',
    request_id        VARCHAR(64)   NOT NULL COMMENT '提交幂等键',
    station_code      VARCHAR(64)   NOT NULL COMMENT '交接站编码',
    gas_day           DATE          NOT NULL COMMENT '气量日(UTC)',
    voucher_type      VARCHAR(16)   NOT NULL COMMENT '凭证类型：NORMAL-正常凭证，ADJUSTMENT-调整凭证',
    volume            DECIMAL(18,3) NOT NULL COMMENT '交接体积（标况），单位 Nm3',
    avg_heating_value DECIMAL(10,4) NOT NULL COMMENT '加权平均热值，单位 MJ/Nm3',
    energy_mj         DECIMAL(18,2) NOT NULL COMMENT '折算能量=体积×加权热值，单位 MJ，保留两位小数',
    status            VARCHAR(16)   NOT NULL COMMENT '状态：DRAFT-待确认, EFFECTIVE-已生效, DISPUTED-争议挂起, SEALED-已封账终态, ADJUSTED-已被调整凭证调整(终态)',
    party_a_confirmed BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '交出方是否已确认',
    party_b_confirmed BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '接收方是否已确认',
    party_a_name      VARCHAR(128) COMMENT '交出方确认人',
    party_b_name      VARCHAR(128) COMMENT '接收方确认人',
    party_a_at        TIMESTAMP(3) COMMENT '交出方确认时刻(UTC)',
    party_b_at        TIMESTAMP(3) COMMENT '接收方确认时刻(UTC)',
    effective_at      TIMESTAMP(3) COMMENT '双方确认一致的生效时刻(UTC)',
    dispute_deadline  DATE COMMENT '争议期截止UTC日期：生效日+7个自然日，截止当日仍可发起争议',
    sealed_at         TIMESTAMP(3) COMMENT '封账时刻(UTC)',
    original_voucher_no VARCHAR(64) COMMENT '调整凭证引用的原凭证号；正常凭证为空',
    reason            VARCHAR(500) COMMENT '调整/争议说明',
    fingerprint       VARCHAR(64)   NOT NULL COMMENT 'requestId与全部业务字段的SHA-256指纹',
    created_at        TIMESTAMP(3)  NOT NULL COMMENT '创建时刻(UTC)',
    updated_at        TIMESTAMP(3)  NOT NULL COMMENT '最近更新时刻(UTC)',
    CONSTRAINT uk_voucher_no UNIQUE (voucher_no),
    CONSTRAINT uk_voucher_request UNIQUE (request_id),
    CONSTRAINT uk_voucher_station_day_type UNIQUE (station_code, gas_day, voucher_type)
);

CREATE TABLE IF NOT EXISTS dispute (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_no             VARCHAR(64)  NOT NULL COMMENT '争议凭证号',
    request_id             VARCHAR(64)  NOT NULL COMMENT '发起争议幂等键',
    party                  VARCHAR(16)  NOT NULL COMMENT '发起方：A-交出方, B-接收方',
    reason                 VARCHAR(500) NOT NULL COMMENT '争议理由',
    status                 VARCHAR(16)  NOT NULL COMMENT '状态：OPEN-挂起中, RESOLVED-已通过调整凭证解决, REJECTED-逾期/封账拒绝',
    adjustment_voucher_no  VARCHAR(64) COMMENT '解决该争议的调整凭证号',
    created_at             TIMESTAMP(3) NOT NULL COMMENT '发起时刻(UTC)',
    resolved_at            TIMESTAMP(3) COMMENT '解决时刻(UTC)',
    CONSTRAINT uk_dispute_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS reading_difference (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_no    VARCHAR(64)   NOT NULL COMMENT '已生成、不被迟到读数改动的凭证号',
    reading_id    BIGINT        NOT NULL COMMENT '迟到读数主键',
    station_code  VARCHAR(64)   NOT NULL COMMENT '交接站编码',
    gas_day       DATE          NOT NULL COMMENT '读数表底时刻所属气量日(UTC)',
    reading_time  TIMESTAMP(3)  NOT NULL COMMENT '表底时刻(UTC)',
    meter_index   DECIMAL(18,3) NOT NULL COMMENT '累计表底，单位 Nm3',
    heating_value DECIMAL(10,4) NOT NULL COMMENT '热值，单位 MJ/Nm3',
    note          VARCHAR(500) COMMENT '差异说明',
    created_at    TIMESTAMP(3) NOT NULL COMMENT '登记时刻(UTC)'
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id  VARCHAR(64)  NOT NULL COMMENT '幂等键',
    operation   VARCHAR(48)  NOT NULL COMMENT '操作类型，如 VOUCHER_SUBMIT/CONFIRM/SEAL/DISPUTE/READING_UPLOAD/ADJUSTMENT',
    fingerprint VARCHAR(64)  NOT NULL COMMENT 'requestId与全部业务字段的SHA-256指纹',
    http_status INT          NOT NULL COMMENT '首次请求的HTTP状态码',
    result_body CLOB         NOT NULL COMMENT '首次请求响应体快照',
    created_at  TIMESTAMP(3) NOT NULL COMMENT '首次请求时刻(UTC)',
    CONSTRAINT pk_idempotency PRIMARY KEY (request_id)
);
