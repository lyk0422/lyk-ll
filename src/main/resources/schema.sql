-- 农残快检与上市准入联动服务建表脚本（H2 MODE=MySQL 兼容语法）
-- 所有时间字段均为 UTC；状态字段取值见对应 Entity Javadoc。

CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '报备幂等键',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的哈希',
    origin VARCHAR(128) NOT NULL COMMENT '产地',
    variety VARCHAR(128) NOT NULL COMMENT '品种',
    quantity DECIMAL(12,2) NOT NULL COMMENT '进场数量，单位千克',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：REGISTERED 已报备 / SUSPECTED 疑似冻结 / RELEASED 放行(终态) / DISPOSED 处置结案(终态)',
    created_at TIMESTAMP NOT NULL COMMENT '报备时间(UTC)',
    updated_at TIMESTAMP NOT NULL COMMENT '最近状态变更时间(UTC)',
    CONSTRAINT uk_batch_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '抽样幂等键',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹',
    batch_id BIGINT NOT NULL COMMENT '进场批次ID',
    item VARCHAR(64) NOT NULL COMMENT '检测项目（如 有机磷）',
    mode VARCHAR(16) NOT NULL COMMENT '抽检方式：NORMAL 常规 / ENHANCED 加严（产地预警期间）',
    created_at TIMESTAMP NOT NULL COMMENT '抽样时间(UTC)',
    CONSTRAINT uk_sample_request UNIQUE (request_id),
    CONSTRAINT uk_sample_batch_item UNIQUE (batch_id, item),
    CONSTRAINT fk_sample_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);

CREATE TABLE IF NOT EXISTS rapid_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '快检幂等键',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹',
    batch_id BIGINT NOT NULL COMMENT '进场批次ID',
    item VARCHAR(64) NOT NULL COMMENT '检测项目',
    inhibition_rate DECIMAL(5,2) NOT NULL COMMENT '快检抑制率，单位%，>=50 判疑似超标',
    verdict VARCHAR(16) NOT NULL COMMENT '判定结果：PASS 合格 / SUSPECTED 疑似超标',
    created_at TIMESTAMP NOT NULL COMMENT '检测时间(UTC)',
    CONSTRAINT uk_rapid_test_request UNIQUE (request_id),
    CONSTRAINT uk_rapid_test_batch_item UNIQUE (batch_id, item),
    CONSTRAINT fk_rapid_test_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);

CREATE TABLE IF NOT EXISTS recheck (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '复检幂等键',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹',
    batch_id BIGINT NOT NULL COMMENT '进场批次ID',
    result VARCHAR(16) NOT NULL COMMENT '定量复检结果：PASS 合格 / FAIL 不合格',
    created_at TIMESTAMP NOT NULL COMMENT '复检时间(UTC)',
    CONSTRAINT uk_recheck_request UNIQUE (request_id),
    CONSTRAINT fk_recheck_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);

CREATE TABLE IF NOT EXISTS retention (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL COMMENT '进场批次ID',
    retain_until TIMESTAMP NOT NULL COMMENT '留样截止时间(UTC)，处置结案后 48 小时',
    created_at TIMESTAMP NOT NULL COMMENT '留样登记时间(UTC)',
    CONSTRAINT uk_retention_batch UNIQUE (batch_id),
    CONSTRAINT fk_retention_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);

CREATE TABLE IF NOT EXISTS origin_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    origin VARCHAR(128) NOT NULL COMMENT '产地',
    variety VARCHAR(128) NOT NULL COMMENT '品种',
    alert_date DATE NOT NULL COMMENT '预警日期(UTC 日界)',
    trigger_count INT NOT NULL COMMENT '触发时当日累计疑似批次数',
    status VARCHAR(16) NOT NULL COMMENT '预警状态：ACTIVE 生效中',
    created_at TIMESTAMP NOT NULL COMMENT '预警触发时间(UTC)',
    CONSTRAINT uk_origin_alert UNIQUE (origin, variety, alert_date)
);

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL COMMENT '进场批次ID',
    event_type VARCHAR(32) NOT NULL COMMENT '迟到事件类型：RAPID_TEST 快检 / RECHECK 复检',
    detail VARCHAR(512) NOT NULL COMMENT '事件说明',
    created_at TIMESTAMP NOT NULL COMMENT '记录时间(UTC)',
    CONSTRAINT fk_late_event_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);
