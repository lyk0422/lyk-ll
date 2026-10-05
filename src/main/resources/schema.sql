-- 天然气计量交接凭证与偏差考核：建表脚本（H2 MODE=MySQL 兼容，UTC 日界）

-- 计量站：交接双方固定为 A/B 两方
CREATE TABLE IF NOT EXISTS meter_station (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code VARCHAR(32)  NOT NULL COMMENT '交接站编码',
    station_name VARCHAR(128) NOT NULL COMMENT '交接站名称',
    party_a_name VARCHAR(128) NOT NULL COMMENT '交接方A名称',
    party_b_name VARCHAR(128) NOT NULL COMMENT '交接方B名称',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_station_code UNIQUE (station_code)
);

-- 管输合同：约定偏差考核违约金单价（单位：元/能量单位）
CREATE TABLE IF NOT EXISTS pipeline_contract (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_no        VARCHAR(64)    NOT NULL COMMENT '管输合同号',
    station_code       VARCHAR(32)    NOT NULL COMMENT '交接站编码',
    penalty_unit_price DECIMAL(20, 4) NOT NULL COMMENT '违约金单价，元/能量单位',
    created_at         TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_contract_no UNIQUE (contract_no)
);

-- 日指定气量：单位为能量单位（与凭证折算能量一致），按 UTC 气量日
CREATE TABLE IF NOT EXISTS daily_nomination (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code      VARCHAR(32)    NOT NULL COMMENT '交接站编码',
    gas_date          DATE           NOT NULL COMMENT '气量日（UTC）',
    nominated_energy  DECIMAL(20, 2) NOT NULL COMMENT '日指定能量，能量单位',
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_nomination_station_date UNIQUE (station_code, gas_date)
);

-- 计量读数：按“交接站＋表底时刻”去重；乱序提交按 gas_date 归位
CREATE TABLE IF NOT EXISTS meter_reading (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id     VARCHAR(64)    NOT NULL COMMENT '读数提交幂等键',
    station_code   VARCHAR(32)    NOT NULL COMMENT '交接站编码',
    meter_time     TIMESTAMP      NOT NULL COMMENT '表底时刻（UTC），区间读数截止时刻',
    gas_date       DATE           NOT NULL COMMENT '按表底时刻归位的气量日（UTC）',
    volume         DECIMAL(20, 4) NOT NULL COMMENT '本区间交接体积，体积单位',
    heating_value  DECIMAL(18, 6) NOT NULL COMMENT '本区间热值，能量单位/体积单位',
    created_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '读数入库时间',
    CONSTRAINT uk_reading_request UNIQUE (request_id),
    CONSTRAINT uk_reading_station_meter_time UNIQUE (station_code, meter_time)
);

-- 交接凭证：ORIGINAL 原始凭证 / ADJUSTMENT 双方签认的调整凭证
CREATE TABLE IF NOT EXISTS custody_voucher (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_no              VARCHAR(64)    NOT NULL COMMENT '凭证号（业务唯一）',
    request_id              VARCHAR(64)    NOT NULL COMMENT '凭证提交幂等键',
    voucher_type            VARCHAR(16)    NOT NULL COMMENT '凭证类型：ORIGINAL/ADJUSTMENT',
    original_voucher_no     VARCHAR(64)    NULL     COMMENT '调整凭证引用的原始凭证号；原始凭证为空',
    station_code            VARCHAR(32)    NOT NULL COMMENT '交接站编码',
    gas_date                DATE           NOT NULL COMMENT '气量日（UTC）',
    volume                  DECIMAL(20, 4) NOT NULL COMMENT '交接体积合计，体积单位',
    weighted_heating_value  DECIMAL(18, 6) NOT NULL COMMENT '加权平均热值，能量单位/体积单位',
    energy                  DECIMAL(20, 2) NOT NULL COMMENT '折算能量＝体积×加权热值，保留两位小数，能量单位',
    status                  VARCHAR(24)    NOT NULL COMMENT '状态：DRAFT/PARTIALLY_CONFIRMED/EFFECTIVE/DISPUTED/SEALED',
    first_confirm_party     VARCHAR(8)     NULL     COMMENT '首个确认方：A/B；未确认时为空',
    effective_date          DATE           NULL     COMMENT '生效日期（UTC），争议期起算日',
    effective_at            TIMESTAMP      NULL     COMMENT '双方确认生效时刻（UTC）',
    sealed_at               TIMESTAMP      NULL     COMMENT '封账时刻（UTC）',
    created_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_voucher_no UNIQUE (voucher_no),
    CONSTRAINT uk_voucher_request UNIQUE (request_id),
    -- 原始凭证 original_voucher_no 为空（唯一索引允许多个 NULL）；
    -- 调整凭证引用原凭证号，一张原始凭证至多一张调整凭证
    CONSTRAINT uk_voucher_original UNIQUE (original_voucher_no)
);

-- 同站同气量日只允许一张有效原始凭证（数据库级占用，防止并发生成两张）
CREATE TABLE IF NOT EXISTS station_day_voucher (
    station_code VARCHAR(32) NOT NULL COMMENT '交接站编码',
    gas_date     DATE        NOT NULL COMMENT '气量日（UTC）',
    voucher_no   VARCHAR(64) NOT NULL COMMENT '占用该站日的原始凭证号',
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_station_day_voucher PRIMARY KEY (station_code, gas_date)
);

-- 凭证确认记录：同一凭证同一方仅一条
CREATE TABLE IF NOT EXISTS voucher_confirmation (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_no  VARCHAR(64) NOT NULL COMMENT '凭证号',
    party       VARCHAR(8)  NOT NULL COMMENT '确认方：A/B',
    request_id  VARCHAR(64) NOT NULL COMMENT '确认幂等键',
    confirmed_at TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '确认时刻（UTC）',
    CONSTRAINT uk_confirmation_request UNIQUE (request_id),
    CONSTRAINT uk_confirmation_voucher_party UNIQUE (voucher_no, party)
);

-- 争议：一张凭证至多一条有效争议
CREATE TABLE IF NOT EXISTS voucher_dispute (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_no  VARCHAR(64)  NOT NULL COMMENT '凭证号',
    request_id  VARCHAR(64)  NOT NULL COMMENT '争议幂等键',
    party       VARCHAR(8)   NOT NULL COMMENT '发起争议方：A/B',
    reason      VARCHAR(512) NOT NULL COMMENT '争议原因',
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '争议状态：OPEN/RESOLVED',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发起时刻（UTC）',
    CONSTRAINT uk_dispute_request UNIQUE (request_id),
    CONSTRAINT uk_dispute_voucher UNIQUE (voucher_no)
);

-- 迟到读数差异记录：凭证生成后才入库、归属该气量日的读数
CREATE TABLE IF NOT EXISTS voucher_reading_diff (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_no     VARCHAR(64)    NOT NULL COMMENT '已生成、不被改写的凭证号',
    reading_id     BIGINT         NOT NULL COMMENT '迟到读数主键',
    station_code   VARCHAR(32)    NOT NULL COMMENT '交接站编码',
    gas_date       DATE           NOT NULL COMMENT '读数归属气量日（UTC）',
    volume         DECIMAL(20, 4) NOT NULL COMMENT '迟到区间体积',
    heating_value  DECIMAL(18, 6) NOT NULL COMMENT '迟到区间热值',
    reason         VARCHAR(32)    NOT NULL DEFAULT 'LATE' COMMENT '差异原因：LATE 表示晚于气量日24:00或凭证生成',
    created_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_diff_reading UNIQUE (reading_id)
);

-- 偏差考核明细：可逐日复算，结果稳定
CREATE TABLE IF NOT EXISTS daily_assessment (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code            VARCHAR(32)    NOT NULL COMMENT '交接站编码',
    gas_date                DATE           NOT NULL COMMENT '气量日（UTC）',
    voucher_no              VARCHAR(64)    NOT NULL COMMENT '考核依据的生效凭证号',
    nominated_energy        DECIMAL(20, 2) NOT NULL COMMENT '日指定能量',
    actual_energy           DECIMAL(20, 2) NOT NULL COMMENT '实际交接能量',
    deviation               DECIMAL(20, 2) NOT NULL COMMENT '偏差＝实际－指定，能量单位',
    deviation_abs           DECIMAL(20, 2) NOT NULL COMMENT '偏差绝对值，能量单位',
    tolerance_energy        DECIMAL(20, 2) NOT NULL COMMENT '3% 免考阈值能量（含边界）',
    exempt                  TINYINT        NOT NULL COMMENT '是否免考：1 免考，0 计违约金',
    penalty                 DECIMAL(20, 2) NOT NULL COMMENT '违约金，超出3%部分×单价，保留两位小数',
    created_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_assessment_station_date UNIQUE (station_code, gas_date)
);

-- 通用幂等记录：指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '业务幂等键',
    request_type  VARCHAR(32)  NOT NULL COMMENT '请求类型：READING/VOUCHER/CONFIRM/DISPUTE/SEAL/ADJUSTMENT/QUALITY_VERIFICATION/QUALITY_SIGNOFF',
    fingerprint   VARCHAR(64)  NOT NULL COMMENT '业务字段指纹（SHA-256）',
    response_json CLOB         NOT NULL COMMENT '首次响应快照（JSON）',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 组分热值表（合成数据）：气质核验按组分比例折算采样热值
CREATE TABLE IF NOT EXISTS component_heating_value (
    component_code VARCHAR(16)    NOT NULL PRIMARY KEY COMMENT '组分编码',
    component_name VARCHAR(64)    NOT NULL COMMENT '组分名称',
    heating_value  DECIMAL(18, 6) NOT NULL COMMENT '组分热值，能量单位/体积单位'
);

-- 气质核验单：关联交接站、气量日与已有凭证；修订追加版本，旧版本结论保留可查
CREATE TABLE IF NOT EXISTS quality_verification (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    verification_no        VARCHAR(64)     NOT NULL COMMENT '核验单号（业务唯一）',
    request_id             VARCHAR(64)     NOT NULL COMMENT '核验提交幂等键',
    station_code           VARCHAR(32)     NOT NULL COMMENT '交接站编码',
    gas_date               DATE            NOT NULL COMMENT '气量日（UTC）',
    voucher_no             VARCHAR(64)     NOT NULL COMMENT '关联交接凭证号',
    version                INT             NOT NULL COMMENT '版本号，同站同日自 1 递增',
    status                 VARCHAR(16)     NOT NULL COMMENT '版本状态：ACTIVE 生效 / SUPERSEDED 被修订取代',
    sample_volume          DECIMAL(20, 4)  NOT NULL COMMENT '采样体积合计，体积单位',
    verified_heating_value DECIMAL(18, 6)  NOT NULL COMMENT '核验热值：组分折算后按采样体积加权，能量单位/体积单位',
    voucher_heating_value  DECIMAL(18, 6)  NOT NULL COMMENT '凭证加权热值（偏差分母），能量单位/体积单位',
    deviation_rate         DECIMAL(12, 6)  NOT NULL COMMENT '绝对相对偏差＝|核验热值－凭证热值|/凭证热值',
    conclusion             VARCHAR(16)     NOT NULL COMMENT '核验结论：CONSISTENT 一致（偏差≤1%）/ QUALITY_DIFF 气质差异（>1%）',
    diff_status            VARCHAR(16)     NOT NULL COMMENT '差异状态：NONE 无差异 / OPEN 未解决 / RESOLVED 双方签认解决',
    confirmation_paused    TINYINT         NOT NULL COMMENT '是否暂停关联凭证双方确认：1 暂停，0 正常',
    created_at             TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '版本提交时刻（UTC）',
    CONSTRAINT uk_verification_no UNIQUE (verification_no),
    CONSTRAINT uk_verification_request UNIQUE (request_id),
    -- 同站同日版本号唯一：并发修订重复取号时由数据库裁决唯一结果
    CONSTRAINT uk_verification_version UNIQUE (station_code, gas_date, version)
);

-- 同站同气量日只允许一个生效核验版本（数据库级占用，防止并发双生效）
CREATE TABLE IF NOT EXISTS station_day_verification (
    station_code    VARCHAR(32) NOT NULL COMMENT '交接站编码',
    gas_date        DATE        NOT NULL COMMENT '气量日（UTC）',
    verification_no VARCHAR(64) NOT NULL COMMENT '当前生效核验单号',
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_station_day_verification PRIMARY KEY (station_code, gas_date)
);

-- 气质采样记录：按“交接站＋采样时刻”去重；组分比例快照存 JSON
CREATE TABLE IF NOT EXISTS quality_sample (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id           VARCHAR(64)     NOT NULL COMMENT '提交该采样的核验幂等键',
    verification_no      VARCHAR(64)     NOT NULL COMMENT '首次入账所属核验单号',
    station_code         VARCHAR(32)     NOT NULL COMMENT '交接站编码',
    sample_time          TIMESTAMP       NOT NULL COMMENT '采样时刻（UTC）',
    gas_date             DATE            NOT NULL COMMENT '按采样时刻归位的气量日（UTC）',
    sample_volume        DECIMAL(20, 4)  NOT NULL COMMENT '采样体积，体积单位',
    composition_json     CLOB            NOT NULL COMMENT '合成组分比例快照（JSON，合计恰为 100%）',
    sample_heating_value DECIMAL(18, 6)  NOT NULL COMMENT '本采样按组分热值表折算的热值，能量单位/体积单位',
    created_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '采样入库时刻（UTC）',
    CONSTRAINT uk_sample_station_time UNIQUE (station_code, sample_time)
);

-- 气质差异双方签认：同一核验单同一方仅一条
CREATE TABLE IF NOT EXISTS quality_diff_signoff (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    verification_no VARCHAR(64) NOT NULL COMMENT '核验单号',
    party           VARCHAR(8)  NOT NULL COMMENT '签认方：A/B',
    request_id      VARCHAR(64) NOT NULL COMMENT '签认幂等键',
    signed_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '签认时刻（UTC）',
    CONSTRAINT uk_signoff_request UNIQUE (request_id),
    CONSTRAINT uk_signoff_verification_party UNIQUE (verification_no, party)
);
