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

CREATE TABLE IF NOT EXISTS item_catalog (
    item VARCHAR(64) PRIMARY KEY,
    limit_value DECIMAL(10,4) NOT NULL
);
COMMENT ON TABLE item_catalog IS '合成检测项目目录：仅演示用合成限值，非真实监管口径';
COMMENT ON COLUMN item_catalog.item IS '检测项目名称（合成）';
COMMENT ON COLUMN item_catalog.limit_value IS '合成限值（单位：mg/kg），恰等限值判合格';

CREATE TABLE IF NOT EXISTS agency_capability (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agency_code VARCHAR(32) NOT NULL,
    item VARCHAR(64) NOT NULL,
    capability_version INT NOT NULL,
    CONSTRAINT uk_agency_item UNIQUE (agency_code, item)
);
COMMENT ON TABLE agency_capability IS '合成检验机构能力目录：机构-项目-能力版本，仅演示用';
COMMENT ON COLUMN agency_capability.agency_code IS '检验机构编码（合成）';
COMMENT ON COLUMN agency_capability.item IS '具备能力的检测项目名称';
COMMENT ON COLUMN agency_capability.capability_version IS '机构该项目能力版本，联检单创建时一并冻结';

CREATE TABLE IF NOT EXISTS joint_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    batch_id BIGINT NOT NULL,
    test_id BIGINT NOT NULL,
    agency_code VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_joint_request UNIQUE (request_id)
);
COMMENT ON TABLE joint_order IS '定量联检单：疑似冻结批次建立，版本从 1 开始、成功变更加 1';
COMMENT ON COLUMN joint_order.request_id IS '联检创建幂等请求号，全局唯一';
COMMENT ON COLUMN joint_order.fingerprint IS '幂等指纹：requestId 与全部业务字段的结构化 SHA-256（长度前缀无歧义）';
COMMENT ON COLUMN joint_order.batch_id IS '所属批次 ID（须疑似冻结状态）';
COMMENT ON COLUMN joint_order.test_id IS '引用的冻结快检记录 ID（结果须为疑似）';
COMMENT ON COLUMN joint_order.agency_code IS '承检机构编码（合成）';
COMMENT ON COLUMN joint_order.status IS '联检状态：OPEN 已创建待分样/ALLOCATED 已分样待判定/DECIDED 已综合判定（终态）';
COMMENT ON COLUMN joint_order.version IS '联检单版本：从 1 开始，每次成功变更加 1，修改须携带 expectedVersion';
COMMENT ON COLUMN joint_order.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN joint_order.updated_at IS '最近变更时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    joint_id BIGINT NOT NULL,
    item VARCHAR(64) NOT NULL,
    limit_value DECIMAL(10,4) NOT NULL,
    capability_version INT NOT NULL,
    sample_no VARCHAR(64),
    CONSTRAINT uk_joint_item UNIQUE (joint_id, item),
    CONSTRAINT uk_joint_sample_no UNIQUE (sample_no)
);
COMMENT ON TABLE joint_item IS '联检项目快照：创建时冻结逐项限值与机构能力版本，分样后回填样本号';
COMMENT ON COLUMN joint_item.joint_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_item.item IS '检测项目名称';
COMMENT ON COLUMN joint_item.limit_value IS '冻结限值（单位：mg/kg），恰等判合格';
COMMENT ON COLUMN joint_item.capability_version IS '冻结的机构该项目能力版本';
COMMENT ON COLUMN joint_item.sample_no IS '分样样本号，全局唯一；未分样为 NULL';

CREATE TABLE IF NOT EXISTS joint_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    joint_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_alloc_joint UNIQUE (joint_id),
    CONSTRAINT uk_alloc_request UNIQUE (request_id)
);
COMMENT ON TABLE joint_allocation IS '分样分配记录：覆盖全部项目的一次性分样，任一项失败整单不分样';
COMMENT ON COLUMN joint_allocation.joint_id IS '所属联检单 ID，每单至多一条分样记录';
COMMENT ON COLUMN joint_allocation.request_id IS '分样幂等请求号，全局唯一';
COMMENT ON COLUMN joint_allocation.fingerprint IS '幂等指纹：requestId、版本与分样集合的结构化 SHA-256';
COMMENT ON COLUMN joint_allocation.created_at IS '分样时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    joint_id BIGINT NOT NULL,
    item VARCHAR(64) NOT NULL,
    sample_no VARCHAR(64) NOT NULL,
    concentration DECIMAL(12,4) NOT NULL,
    version INT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_joint_result_item_version UNIQUE (joint_id, item, version),
    CONSTRAINT uk_joint_result_request UNIQUE (request_id)
);
COMMENT ON TABLE joint_result IS '联检逐项结果：复测替换当前结果并保留旧结果，项内版本从 1 递增';
COMMENT ON COLUMN joint_result.joint_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_result.item IS '检测项目名称';
COMMENT ON COLUMN joint_result.sample_no IS '检测样本号，须与分样一致';
COMMENT ON COLUMN joint_result.concentration IS '实测浓度（单位：mg/kg），有限非负';
COMMENT ON COLUMN joint_result.version IS '该项结果版本：从 1 开始，复测递增；同项最大版本为当前结果';
COMMENT ON COLUMN joint_result.request_id IS '结果录入幂等请求号，全局唯一';
COMMENT ON COLUMN joint_result.fingerprint IS '幂等指纹：requestId、联检版本、样本、浓度与精确时刻的结构化 SHA-256';
COMMENT ON COLUMN joint_result.created_at IS '结果录入时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_decision (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    joint_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    verdict VARCHAR(16) NOT NULL,
    evidence VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_decision_joint UNIQUE (joint_id),
    CONSTRAINT uk_decision_request UNIQUE (request_id)
);
COMMENT ON TABLE joint_decision IS '联检综合判定：携带全部项版本冻结证据，每单至多一条；合格放行、不合格转处置';
COMMENT ON COLUMN joint_decision.joint_id IS '所属联检单 ID，每单至多一条判定';
COMMENT ON COLUMN joint_decision.request_id IS '判定幂等请求号，全局唯一';
COMMENT ON COLUMN joint_decision.fingerprint IS '幂等指纹：requestId、联检版本与全部项版本集合的结构化 SHA-256';
COMMENT ON COLUMN joint_decision.verdict IS '综合判定：QUALIFIED 合格/UNQUALIFIED 不合格（任一超限即不合格，恰等限值合格）';
COMMENT ON COLUMN joint_decision.evidence IS '冻结证据：判定时全部项当前结果版本快照';
COMMENT ON COLUMN joint_decision.created_at IS '判定时间（UTC）';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE late_event IS '迟到事件：批次终态后迟到的检测/复检结果只记录不改状态';
COMMENT ON COLUMN late_event.batch_id IS '所属批次 ID';
COMMENT ON COLUMN late_event.event_type IS '事件类型：LATE_TEST 迟到快检/LATE_RECHECK 迟到复检/LATE_JOINT_RESULT 联检判定后补充结果';
COMMENT ON COLUMN late_event.payload IS '迟到结果摘要';
COMMENT ON COLUMN late_event.created_at IS '事件记录时间（UTC）';
