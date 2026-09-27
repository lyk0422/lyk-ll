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

-- 定量联检：合成项目目录与机构能力（仅合成数据，不用于生产）
CREATE TABLE IF NOT EXISTS item_catalog (
    item VARCHAR(64) PRIMARY KEY,
    limit_value DECIMAL(10,4) NOT NULL,
    unit VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE item_catalog IS '合成项目目录：联检可选项目及默认限值（合成数据，不用于生产）';
COMMENT ON COLUMN item_catalog.item IS '检测项目名称';
COMMENT ON COLUMN item_catalog.limit_value IS '项目限值（单位见 unit，非负），联检创建时冻结到联检项目';
COMMENT ON COLUMN item_catalog.unit IS '限值单位（如 mg/kg）';
COMMENT ON COLUMN item_catalog.created_at IS '目录登记时间（UTC，合成固定值）';

CREATE TABLE IF NOT EXISTS agency_capability (
    agency_code VARCHAR(32) NOT NULL,
    item VARCHAR(64) NOT NULL,
    capability_version INT NOT NULL,
    CONSTRAINT pk_agency_capability PRIMARY KEY (agency_code, item)
);
COMMENT ON TABLE agency_capability IS '合成检验机构能力目录：机构对项目的能力版本（合成数据，不用于生产）';
COMMENT ON COLUMN agency_capability.agency_code IS '检验机构编码';
COMMENT ON COLUMN agency_capability.item IS '具备能力的检测项目';
COMMENT ON COLUMN agency_capability.capability_version IS '机构该项目能力版本号，联检创建时冻结';

CREATE TABLE IF NOT EXISTS joint_test_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    batch_id BIGINT NOT NULL,
    agency_code VARCHAR(32) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    overall_result VARCHAR(16),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_jto_request UNIQUE (request_id),
    CONSTRAINT uk_jto_batch UNIQUE (batch_id)
);
COMMENT ON TABLE joint_test_order IS '定量联检单：疑似冻结批次建立，每批次至多一单；版本从 1 开始，成功变更加 1';
COMMENT ON COLUMN joint_test_order.request_id IS '创建幂等请求号，全局唯一';
COMMENT ON COLUMN joint_test_order.fingerprint IS '结构化幂等指纹：requestId、版本、集合与精确时刻的长度前缀 SHA-256';
COMMENT ON COLUMN joint_test_order.batch_id IS '疑似冻结批次 ID，每批次至多一单';
COMMENT ON COLUMN joint_test_order.agency_code IS '承检机构编码（引用合成能力目录）';
COMMENT ON COLUMN joint_test_order.version IS '联检单乐观锁版本：从 1 开始，分样/结果/复测/综合判定成功各加 1';
COMMENT ON COLUMN joint_test_order.status IS '联检单状态：CREATED 已创建/SPLIT 已分样/JUDGED 已综合判定（终态）';
COMMENT ON COLUMN joint_test_order.overall_result IS '综合判定结果：QUALIFIED 合格/UNQUALIFIED 不合格，未判定为 NULL（待定不落库）';
COMMENT ON COLUMN joint_test_order.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN joint_test_order.updated_at IS '最近变更时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_test_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    item VARCHAR(64) NOT NULL,
    limit_value DECIMAL(10,4) NOT NULL,
    capability_version INT NOT NULL,
    CONSTRAINT uk_jti_order_item UNIQUE (order_id, item)
);
COMMENT ON TABLE joint_test_item IS '联检项目：创建时从合成目录冻结逐项限值与机构能力版本，至少两项且唯一';
COMMENT ON COLUMN joint_test_item.order_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_test_item.item IS '检测项目名称';
COMMENT ON COLUMN joint_test_item.limit_value IS '冻结限值（单位 mg/kg，非负），恰等限值判合格';
COMMENT ON COLUMN joint_test_item.capability_version IS '冻结的机构该项目能力版本号';

CREATE TABLE IF NOT EXISTS joint_test_split (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    item VARCHAR(64) NOT NULL,
    sample_no VARCHAR(64) NOT NULL,
    order_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_jts_request_item UNIQUE (request_id, item),
    CONSTRAINT uk_jts_order_item UNIQUE (order_id, item),
    CONSTRAINT uk_jts_sample_no UNIQUE (sample_no)
);
COMMENT ON TABLE joint_test_split IS '联检分样：覆盖全部项目、样本号全局唯一，任一项失败整单不分样';
COMMENT ON COLUMN joint_test_split.order_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_test_split.request_id IS '分样幂等请求号（同次分样各行相同，与项目联合唯一）';
COMMENT ON COLUMN joint_test_split.fingerprint IS '结构化幂等指纹：requestId、期望版本、分样集合与精确时刻';
COMMENT ON COLUMN joint_test_split.item IS '分样对应检测项目';
COMMENT ON COLUMN joint_test_split.sample_no IS '分样样本号，全局唯一';
COMMENT ON COLUMN joint_test_split.order_version IS '分样完成后的联检单版本号（重放取证用）';
COMMENT ON COLUMN joint_test_split.created_at IS '分样时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_test_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    order_id BIGINT NOT NULL,
    split_id BIGINT NOT NULL,
    item VARCHAR(64) NOT NULL,
    concentration DECIMAL(12,4) NOT NULL,
    qualified BOOLEAN NOT NULL,
    item_version INT NOT NULL,
    order_version INT NOT NULL,
    current_flag BOOLEAN NOT NULL,
    supplementary BOOLEAN NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_jtr_request UNIQUE (request_id)
);
COMMENT ON TABLE joint_test_result IS '联检结果：引用样本、项目与联检版本；复测保留旧结果并递增项目版本；判定后只记补充信息';
COMMENT ON COLUMN joint_test_result.request_id IS '结果幂等请求号，全局唯一';
COMMENT ON COLUMN joint_test_result.fingerprint IS '结构化幂等指纹：requestId、期望版本、项目、浓度与精确时刻';
COMMENT ON COLUMN joint_test_result.order_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_test_result.split_id IS '引用的分样样本 ID';
COMMENT ON COLUMN joint_test_result.item IS '检测项目';
COMMENT ON COLUMN joint_test_result.concentration IS '实测浓度（单位 mg/kg，有限非负）';
COMMENT ON COLUMN joint_test_result.qualified IS '单项结论：浓度 <= 冻结限值（恰等限值合格）';
COMMENT ON COLUMN joint_test_result.item_version IS '该项目结果版本：首次 1，复测递增，旧结果保留';
COMMENT ON COLUMN joint_test_result.order_version IS '录入完成后的联检单版本号（重放取证用）';
COMMENT ON COLUMN joint_test_result.current_flag IS '是否该项目当前有效结果（复测后旧结果为 FALSE）';
COMMENT ON COLUMN joint_test_result.supplementary IS '是否综合判定后的补充信息（TRUE 只记录不重开）';
COMMENT ON COLUMN joint_test_result.occurred_at IS '结果精确时刻（UTC，请求携带并入指纹）';
COMMENT ON COLUMN joint_test_result.created_at IS '记录时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_test_judgment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    order_id BIGINT NOT NULL,
    overall_result VARCHAR(16) NOT NULL,
    item_versions VARCHAR(2048) NOT NULL,
    order_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_jtj_request UNIQUE (request_id),
    CONSTRAINT uk_jtj_order UNIQUE (order_id)
);
COMMENT ON TABLE joint_test_judgment IS '联检综合判定：携带全部项目结果版本并冻结证据，每单至多一次；并发复测致版本失配则零写入';
COMMENT ON COLUMN joint_test_judgment.request_id IS '判定幂等请求号，全局唯一';
COMMENT ON COLUMN joint_test_judgment.fingerprint IS '结构化幂等指纹：requestId、期望版本、项目版本集合与精确时刻';
COMMENT ON COLUMN joint_test_judgment.order_id IS '所属联检单 ID，每单至多一条判定';
COMMENT ON COLUMN joint_test_judgment.overall_result IS '综合判定结果：QUALIFIED 合格/UNQUALIFIED 不合格（任一超限）';
COMMENT ON COLUMN joint_test_judgment.item_versions IS '冻结证据：各项目结果版本、浓度、限值与单项结论的结构化摘要';
COMMENT ON COLUMN joint_test_judgment.order_version IS '判定完成后的联检单版本号（重放取证用）';
COMMENT ON COLUMN joint_test_judgment.created_at IS '判定时间（UTC）';

-- 合成项目目录与机构能力种子（幂等 MERGE，仅合成数据）
MERGE INTO item_catalog KEY(item) VALUES
    ('有机磷', 0.0500, 'mg/kg', TIMESTAMP '2026-01-01 00:00:00'),
    ('克百威', 0.0200, 'mg/kg', TIMESTAMP '2026-01-01 00:00:00'),
    ('毒死蜱', 0.1000, 'mg/kg', TIMESTAMP '2026-01-01 00:00:00'),
    ('氧乐果', 0.0200, 'mg/kg', TIMESTAMP '2026-01-01 00:00:00'),
    ('多菌灵', 0.5000, 'mg/kg', TIMESTAMP '2026-01-01 00:00:00');

MERGE INTO agency_capability KEY(agency_code, item) VALUES
    ('LAB-A', '有机磷', 3),
    ('LAB-A', '克百威', 3),
    ('LAB-A', '毒死蜱', 2),
    ('LAB-A', '氧乐果', 2),
    ('LAB-B', '有机磷', 1),
    ('LAB-B', '克百威', 1),
    ('LAB-B', '多菌灵', 1);
