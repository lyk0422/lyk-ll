-- 干扰投诉处置链 schema（H2 MySQL 兼容模式；时间均为 UTC，TIMESTAMP 存瞬时值）

CREATE TABLE IF NOT EXISTS transmitter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL COMMENT '发射机编号（合成台账）',
    full_power_watts INT NOT NULL COMMENT '额定满功率，单位 W',
    UNIQUE KEY uk_transmitter_code (code)
);

CREATE TABLE IF NOT EXISTS transmitter_channel (
    transmitter_id BIGINT NOT NULL COMMENT '发射机 id',
    frequency_mhz DECIMAL(10,3) NOT NULL COMMENT '可使用频率，单位 MHz',
    UNIQUE KEY uk_transmitter_channel (transmitter_id, frequency_mhz)
);

CREATE TABLE IF NOT EXISTS protection_limit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    frequency_mhz DECIMAL(10,3) NOT NULL COMMENT '保护频率，单位 MHz',
    max_power_watts INT NOT NULL COMMENT '保护限值（允许的最大实际功率），单位 W',
    UNIQUE KEY uk_protection_frequency (frequency_mhz)
);

CREATE TABLE IF NOT EXISTS complaint (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求标识',
    station_id VARCHAR(64) NOT NULL COMMENT '监测站标识',
    frequency_mhz DECIMAL(10,3) NOT NULL COMMENT '被投诉频率，单位 MHz',
    sampled_at TIMESTAMP NOT NULL COMMENT '采样时刻（UTC）',
    field_strength_dbuv DECIMAL(10,2) NOT NULL COMMENT '实测场强，单位 dBµV/m',
    evidence_ref VARCHAR(128) NOT NULL COMMENT '合成证据标识，缺失则不进入正式处置',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务字段指纹（SHA-256）',
    created_at TIMESTAMP NOT NULL COMMENT '登记时刻（UTC）',
    UNIQUE KEY uk_complaint_biz (station_id, frequency_mhz, sampled_at)
);

CREATE TABLE IF NOT EXISTS interference_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    frequency_mhz DECIMAL(10,3) NOT NULL COMMENT '干扰频率，单位 MHz',
    status VARCHAR(20) NOT NULL COMMENT '状态：OPEN 待初查 / CONFIRMED 确认干扰限功率中 / REJECTED 初查排除 / CLOSED 结案',
    escalated BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已升级督办（创建满 24 小时 UTC 仍未初查）',
    escalated_at TIMESTAMP NULL COMMENT '升级督办时刻（UTC），未升级为 NULL',
    created_at TIMESTAMP NOT NULL COMMENT '事件创建时刻（UTC）',
    reviewed_at TIMESTAMP NULL COMMENT '初查时刻（UTC），未初查为 NULL',
    reviewer_id VARCHAR(64) NULL COMMENT '初查人标识',
    review_conclusion VARCHAR(32) NULL COMMENT '初查结论：CONFIRMED_INTERFERENCE / NOT_INTERFERENCE',
    closed_at TIMESTAMP NULL COMMENT '结案时刻（UTC），未结案为 NULL',
    closed_by VARCHAR(64) NULL COMMENT '结案人标识'
);

CREATE TABLE IF NOT EXISTS power_directive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL COMMENT '触发本指令的干扰事件 id',
    transmitter_id BIGINT NOT NULL COMMENT '目标发射机 id',
    limit_watts INT NOT NULL COMMENT '限功率值，单位 W',
    status VARCHAR(20) NOT NULL COMMENT '状态：EXECUTING 执行中 / SUPERSEDED 被更严限值取代 / RELEASED 已解除',
    executing_key BIGINT NULL COMMENT '执行中去重键：EXECUTING 时等于 transmitter_id，否则为 NULL，保证同发射机同时刻仅一条执行中指令',
    actual_power_watts INT NULL COMMENT '执行确认时上报的实际功率，单位 W；未确认为 NULL',
    confirmed_by VARCHAR(64) NULL COMMENT '执行确认人标识',
    confirmed_at TIMESTAMP NULL COMMENT '执行确认时刻（UTC）',
    created_at TIMESTAMP NOT NULL COMMENT '指令创建时刻（UTC）',
    superseded_at TIMESTAMP NULL COMMENT '被取代时刻（UTC）',
    released_at TIMESTAMP NULL COMMENT '解除时刻（UTC）',
    UNIQUE KEY uk_directive_executing (executing_key)
);

CREATE TABLE IF NOT EXISTS retest_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transmitter_id BIGINT NOT NULL COMMENT '复测发射机 id',
    result VARCHAR(20) NOT NULL COMMENT '复测结果：NO_INTERFERENCE 无干扰 / INTERFERENCE 仍有干扰',
    sampled_at TIMESTAMP NOT NULL COMMENT '复测采样时刻（UTC）',
    evidence_ref VARCHAR(128) NOT NULL COMMENT '复测合成证据标识',
    recorded_at TIMESTAMP NOT NULL COMMENT '记录时刻（UTC）'
);

CREATE TABLE IF NOT EXISTS release_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    directive_id BIGINT NOT NULL COMMENT '被解除的限功率指令 id',
    retest_id BIGINT NOT NULL COMMENT '关联复测记录 id',
    operation_staff_id VARCHAR(64) NOT NULL COMMENT '运行侧确认人标识',
    monitoring_staff_id VARCHAR(64) NOT NULL COMMENT '监测侧确认人标识（须与运行侧不同）',
    released_at TIMESTAMP NOT NULL COMMENT '解除时刻（UTC）'
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求标识',
    action VARCHAR(32) NOT NULL COMMENT '业务动作：COMPLAINT_REGISTER / EVENT_REVIEW',
    fingerprint VARCHAR(64) NOT NULL COMMENT '全部业务字段指纹（SHA-256）；同键异参返回 409',
    response_body CLOB NOT NULL COMMENT '首次处理结果快照（JSON），同键同参原样返回',
    created_at TIMESTAMP NOT NULL COMMENT '记录时刻（UTC）',
    UNIQUE KEY uk_idempotency (request_id, action)
);
