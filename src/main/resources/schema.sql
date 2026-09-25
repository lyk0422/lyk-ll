-- 爆破作业审批与振动监测联动服务建表脚本（H2，MODE=MySQL）
-- 所有时间列均存储 UTC 时刻；金额/药量/振速单位见列注释。

CREATE TABLE IF NOT EXISTS mine (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    velocity_threshold DECIMAL(10, 3) NOT NULL,
    frozen BOOLEAN NOT NULL DEFAULT FALSE,
    frozen_at TIMESTAMP NULL,
    freeze_reason VARCHAR(256) NULL
);
COMMENT ON TABLE mine IS '矿区；frozen 为冻结标记，冻结立即生效';
COMMENT ON COLUMN mine.velocity_threshold IS '振动速度阈值，单位 cm/s，任一测点超过即触发冻结';
COMMENT ON COLUMN mine.frozen_at IS '冻结生效时刻（UTC），未冻结为 NULL';
COMMENT ON COLUMN mine.freeze_reason IS '冻结原因（超限测点描述），未冻结为 NULL';

CREATE TABLE IF NOT EXISTS blast_zone (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mine_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    max_charge_per_delay DECIMAL(10, 3) NOT NULL
);
COMMENT ON TABLE blast_zone IS '爆区，隶属于矿区';
COMMENT ON COLUMN blast_zone.max_charge_per_delay IS '备案的单段药量上限，单位 kg，超过直接拒绝';

CREATE TABLE IF NOT EXISTS monitor_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_id BIGINT NOT NULL,
    point_code VARCHAR(64) NOT NULL,
    CONSTRAINT uk_monitor_point UNIQUE (zone_id, point_code)
);
COMMENT ON TABLE monitor_point IS '爆区配置的振动监测测点；效果评估要求全部测点回传齐全';

CREATE TABLE IF NOT EXISTS blast_permit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    zone_id BIGINT NOT NULL,
    applicant VARCHAR(128) NOT NULL,
    planned_blast_time TIMESTAMP NOT NULL,
    charge_per_delay DECIMAL(10, 3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reject_reason VARCHAR(256) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_permit_request UNIQUE (request_id)
);
COMMENT ON TABLE blast_permit IS '爆破作业申请单；状态 PENDING/APPROVED/REJECTED/VOID/FILED/SEALED';
COMMENT ON COLUMN blast_permit.request_id IS '幂等键，由调用方提供';
COMMENT ON COLUMN blast_permit.fingerprint IS 'requestId 与全部业务字段的 SHA-256 指纹，同键异参返回 409';
COMMENT ON COLUMN blast_permit.planned_blast_time IS '计划起爆时刻（UTC），允许时段为每日 06:00（含）至 22:00（不含）';
COMMENT ON COLUMN blast_permit.charge_per_delay IS '单段药量，单位 kg';
COMMENT ON COLUMN blast_permit.status IS 'PENDING 在途 / APPROVED 已批准 / REJECTED 已拒绝 / VOID 冻结作废 / FILED 已起爆备案 / SEALED 评估封档（终态）';
COMMENT ON COLUMN blast_permit.reject_reason IS '拒绝或作废原因，其余状态为 NULL';

CREATE TABLE IF NOT EXISTS approved_slot (
    zone_id BIGINT NOT NULL,
    planned_blast_time TIMESTAMP NOT NULL,
    permit_id BIGINT NOT NULL,
    PRIMARY KEY (zone_id, planned_blast_time)
);
COMMENT ON TABLE approved_slot IS '已批准作业占用的爆区时刻槽位，主键保证同一爆区同一时刻仅一个已批准作业';

CREATE TABLE IF NOT EXISTS blast_filing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    permit_id BIGINT NOT NULL,
    filed_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_filing_request UNIQUE (request_id),
    CONSTRAINT uk_filing_permit UNIQUE (permit_id)
);
COMMENT ON TABLE blast_filing IS '起爆备案；仅已批准作业可备案，request_id 幂等';
COMMENT ON COLUMN blast_filing.filed_at IS '备案时刻（UTC）';

CREATE TABLE IF NOT EXISTS vibration_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    permit_id BIGINT NOT NULL,
    zone_id BIGINT NOT NULL,
    point_code VARCHAR(64) NOT NULL,
    sampled_at TIMESTAMP NOT NULL,
    velocity DECIMAL(10, 3) NOT NULL,
    received_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_reading_dedup UNIQUE (zone_id, point_code, sampled_at)
);
COMMENT ON TABLE vibration_reading IS '振动监测回传；按爆区+测点+采样时刻去重';
COMMENT ON COLUMN vibration_reading.velocity IS '质点振动速度，单位 cm/s';
COMMENT ON COLUMN vibration_reading.sampled_at IS '采样时刻（UTC）';

CREATE TABLE IF NOT EXISTS freeze_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    mine_id BIGINT NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    reviewed_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_review_request UNIQUE (request_id)
);
COMMENT ON TABLE freeze_review IS '监管复核解除冻结记录，request_id 幂等';

CREATE TABLE IF NOT EXISTS evaluation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    permit_id BIGINT NOT NULL,
    conclusion VARCHAR(256) NOT NULL,
    sealed_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_evaluation_request UNIQUE (request_id),
    CONSTRAINT uk_evaluation_permit UNIQUE (permit_id)
);
COMMENT ON TABLE evaluation IS '效果评估，封档为终态；重复封档返回幂等成功';
COMMENT ON COLUMN evaluation.sealed_at IS '封档时刻（UTC）';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    permit_id BIGINT NOT NULL,
    zone_id BIGINT NOT NULL,
    point_code VARCHAR(64) NOT NULL,
    sampled_at TIMESTAMP NOT NULL,
    recorded_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE late_event IS '封档后到达的迟到振动读数事件，只记录不改评估结论';
