-- 农残快检与上市准入联动服务建表脚本（H2，MODE=MySQL）
-- 时间字段统一为 UTC 墙钟时间（TIMESTAMP，无时区），由应用以 UTC 写入。

CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    origin VARCHAR(128) NOT NULL,
    variety VARCHAR(128) NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    quantity DECIMAL(12,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    sampling_level VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_batch_request UNIQUE (request_id),
    CONSTRAINT uk_batch_biz UNIQUE (origin, variety, batch_no)
);
COMMENT ON TABLE batch IS '进场批次：产地报备创建，状态机 REPORTED/SAMPLED/FROZEN/RELEASED/DISPOSED';
COMMENT ON COLUMN batch.request_id IS '报备幂等请求号，全局唯一';
COMMENT ON COLUMN batch.fingerprint IS '幂等指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN batch.origin IS '产地名称';
COMMENT ON COLUMN batch.variety IS '农产品品种';
COMMENT ON COLUMN batch.batch_no IS '批次号，与产地、品种共同唯一';
COMMENT ON COLUMN batch.quantity IS '进场数量（单位：吨），必填';
COMMENT ON COLUMN batch.status IS '批次状态：REPORTED 已报备/SAMPLED 已抽样/FROZEN 疑似冻结/RELEASED 放行（终态）/DISPOSED 处置结案（终态）';
COMMENT ON COLUMN batch.sampling_level IS '抽检级别：NORMAL 常规/STRICT 加严（产地预警期间报备的批次）';
COMMENT ON COLUMN batch.created_at IS '报备时间（UTC）';
COMMENT ON COLUMN batch.updated_at IS '最近状态变更时间（UTC）';

CREATE TABLE IF NOT EXISTS sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    item VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_sample_batch_item UNIQUE (batch_id, item)
);
COMMENT ON TABLE sample IS '抽样记录：按批次+检测项目去重';
COMMENT ON COLUMN sample.batch_id IS '所属批次 ID';
COMMENT ON COLUMN sample.item IS '检测项目名称（如有机磷类）';
COMMENT ON COLUMN sample.created_at IS '抽样时间（UTC）';

CREATE TABLE IF NOT EXISTS test_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    batch_id BIGINT NOT NULL,
    item VARCHAR(64) NOT NULL,
    inhibition_rate DECIMAL(5,2) NOT NULL,
    result VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_test_request UNIQUE (request_id),
    CONSTRAINT uk_test_batch_item UNIQUE (batch_id, item)
);
COMMENT ON TABLE test_record IS '快检检测记录：按批次+项目去重，重复送检拒绝';
COMMENT ON COLUMN test_record.request_id IS '快检幂等请求号，全局唯一';
COMMENT ON COLUMN test_record.fingerprint IS '幂等指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN test_record.batch_id IS '所属批次 ID';
COMMENT ON COLUMN test_record.item IS '检测项目名称';
COMMENT ON COLUMN test_record.inhibition_rate IS '抑制率（单位：%），达到 50 判疑似超标（恰 50 判疑似）';
COMMENT ON COLUMN test_record.result IS '快检结果：NORMAL 正常/SUSPECT 疑似超标，由抑制率口径可复算';
COMMENT ON COLUMN test_record.created_at IS '检测时间（UTC），产地预警按 UTC 日界统计';

CREATE TABLE IF NOT EXISTS recheck (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    batch_id BIGINT NOT NULL,
    item VARCHAR(64) NOT NULL,
    qualified BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_recheck_request UNIQUE (request_id),
    CONSTRAINT uk_recheck_batch_item UNIQUE (batch_id, item)
);
COMMENT ON TABLE recheck IS '定量复检记录：疑似冻结批次送检，合格放行、不合格转监督处置';
COMMENT ON COLUMN recheck.request_id IS '复检幂等请求号，全局唯一';
COMMENT ON COLUMN recheck.fingerprint IS '幂等指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN recheck.batch_id IS '所属批次 ID';
COMMENT ON COLUMN recheck.item IS '复检项目名称';
COMMENT ON COLUMN recheck.qualified IS '复检结论：TRUE 合格/FALSE 不合格';
COMMENT ON COLUMN recheck.created_at IS '复检时间（UTC）';

CREATE TABLE IF NOT EXISTS admission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    released_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_admission_batch UNIQUE (batch_id)
);
COMMENT ON TABLE admission IS '准入放行记录：与判定在同一事务内完成，每批次至多一条';
COMMENT ON COLUMN admission.batch_id IS '放行批次 ID';
COMMENT ON COLUMN admission.released_at IS '放行时间（UTC）';

CREATE TABLE IF NOT EXISTS disposal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    disposed_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_disposal_batch UNIQUE (batch_id)
);
COMMENT ON TABLE disposal IS '监督处置结案记录：复检不合格转入，每批次至多一条';
COMMENT ON COLUMN disposal.batch_id IS '处置批次 ID';
COMMENT ON COLUMN disposal.reason IS '处置原因';
COMMENT ON COLUMN disposal.disposed_at IS '结案时间（UTC）';

CREATE TABLE IF NOT EXISTS retention (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    retain_hours INT NOT NULL,
    retain_until TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_retention_batch UNIQUE (batch_id)
);
COMMENT ON TABLE retention IS '留样记录：处置结案批次留样 48 小时';
COMMENT ON COLUMN retention.batch_id IS '留样批次 ID';
COMMENT ON COLUMN retention.retain_hours IS '留样时长（单位：小时），固定 48';
COMMENT ON COLUMN retention.retain_until IS '留样截止时间（UTC）= 结案时间 + 48 小时';
COMMENT ON COLUMN retention.created_at IS '留样登记时间（UTC）';

CREATE TABLE IF NOT EXISTS origin_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    origin VARCHAR(128) NOT NULL,
    variety VARCHAR(128) NOT NULL,
    alert_date DATE NOT NULL,
    trigger_count INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_alert_origin_variety_date UNIQUE (origin, variety, alert_date)
);
COMMENT ON TABLE origin_alert IS '产地预警台账：同产地同品种当日（UTC 日界）累计 3 批疑似触发，恰 3 批触发，可复算';
COMMENT ON COLUMN origin_alert.origin IS '产地名称';
COMMENT ON COLUMN origin_alert.variety IS '农产品品种';
COMMENT ON COLUMN origin_alert.alert_date IS '预警日期（UTC 日界）';
COMMENT ON COLUMN origin_alert.trigger_count IS '触发时疑似批次数（>=3）';
COMMENT ON COLUMN origin_alert.status IS '预警状态：ACTIVE 生效中/CLOSED 已解除';
COMMENT ON COLUMN origin_alert.created_at IS '预警登记时间（UTC）';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE late_event IS '迟到事件：批次终态后迟到的检测/复检结果只记录不改状态';
COMMENT ON COLUMN late_event.batch_id IS '所属批次 ID';
COMMENT ON COLUMN late_event.event_type IS '事件类型：LATE_TEST 迟到快检/LATE_RECHECK 迟到复检';
COMMENT ON COLUMN late_event.payload IS '迟到结果摘要';
COMMENT ON COLUMN late_event.created_at IS '事件记录时间（UTC）';
