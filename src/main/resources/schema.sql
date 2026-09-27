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

-- ============ 疑似批次多项目联合检测（合成规则，不用于生产，不代表真实收费/监管限值）============

CREATE TABLE IF NOT EXISTS joint_catalog_item (
    item_code VARCHAR(64) PRIMARY KEY,
    item_name VARCHAR(128) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    limit_value DECIMAL(12,4) NOT NULL,
    catalog_version VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE joint_catalog_item IS '合成检测项目目录：联检建单时逐项冻结名称/单位/限值与目录版本，仅为合成规则';
COMMENT ON COLUMN joint_catalog_item.item_code IS '合成项目编码（主键）';
COMMENT ON COLUMN joint_catalog_item.item_name IS '合成项目名称';
COMMENT ON COLUMN joint_catalog_item.unit IS '结果浓度单位（合成，如 mg/kg）';
COMMENT ON COLUMN joint_catalog_item.limit_value IS '合成限值（单位同 unit），浓度恰等限值判合格，超出判不合格';
COMMENT ON COLUMN joint_catalog_item.catalog_version IS '冻结时的合成目录版本';
COMMENT ON COLUMN joint_catalog_item.active IS '目录项是否启用：TRUE 可用于新建联检单/FALSE 已停用';
COMMENT ON COLUMN joint_catalog_item.created_at IS '目录项登记时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_lab (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lab_code VARCHAR(64) NOT NULL,
    lab_name VARCHAR(128) NOT NULL,
    capability_version VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_joint_lab_code UNIQUE (lab_code)
);
COMMENT ON TABLE joint_lab IS '合成检验机构：仅维护机构能力版本，不涉及真实检测收费或监管资质';
COMMENT ON COLUMN joint_lab.lab_code IS '合成机构编码，唯一';
COMMENT ON COLUMN joint_lab.lab_name IS '合成机构名称';
COMMENT ON COLUMN joint_lab.capability_version IS '机构能力版本，建单时冻结到联检单';
COMMENT ON COLUMN joint_lab.active IS '机构是否启用';
COMMENT ON COLUMN joint_lab.created_at IS '机构登记时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_lab_capability (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lab_id BIGINT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    capability_version VARCHAR(32) NOT NULL,
    CONSTRAINT uk_joint_lab_item UNIQUE (lab_id, item_code)
);
COMMENT ON TABLE joint_lab_capability IS '合成机构项目能力明细：机构须具备联检单全部项目能力，版本随单冻结';
COMMENT ON COLUMN joint_lab_capability.lab_id IS '所属合成机构 ID';
COMMENT ON COLUMN joint_lab_capability.item_code IS '具备能力的合成项目编码';
COMMENT ON COLUMN joint_lab_capability.capability_version IS '该能力登记时对应的机构能力版本';

CREATE TABLE IF NOT EXISTS joint_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    batch_id BIGINT NOT NULL,
    lab_id BIGINT NOT NULL,
    lab_code VARCHAR(64) NOT NULL,
    lab_name VARCHAR(128) NOT NULL,
    lab_capability_version VARCHAR(32) NOT NULL,
    catalog_version VARCHAR(32) NOT NULL,
    frozen_tests VARCHAR(1024) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    judged_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_joint_order_request UNIQUE (request_id),
    CONSTRAINT uk_joint_order_batch UNIQUE (batch_id)
);
COMMENT ON TABLE joint_order IS '联检单：疑似冻结批次多项目联合检测，每批次至多一单，版本从 1 起每次成功变更加 1';
COMMENT ON COLUMN joint_order.request_id IS '建单幂等请求号，全局唯一';
COMMENT ON COLUMN joint_order.fingerprint IS '建单幂等指纹：requestId 与全部业务参数（含集合、精确时刻）的结构化 SHA-256';
COMMENT ON COLUMN joint_order.batch_id IS '关联疑似批次 ID（建单时须为 FROZEN）';
COMMENT ON COLUMN joint_order.lab_id IS '承检合成机构 ID';
COMMENT ON COLUMN joint_order.lab_code IS '承检合成机构编码（冻结）';
COMMENT ON COLUMN joint_order.lab_name IS '承检合成机构名称（冻结）';
COMMENT ON COLUMN joint_order.lab_capability_version IS '机构能力版本（冻结）';
COMMENT ON COLUMN joint_order.catalog_version IS '合成项目目录版本（冻结）';
COMMENT ON COLUMN joint_order.frozen_tests IS '建单时引用的冻结快检结果快照（项目/抑制率/结论摘要）';
COMMENT ON COLUMN joint_order.status IS '联检单状态：CREATED 已建单/ASSIGNED 已全项分样/PENDING 结果待定/JUDGED 已综合判定';
COMMENT ON COLUMN joint_order.version IS '乐观版本：建单为 1，分样/结果/复测/综合判定每成功一次加 1；失配写入零行';
COMMENT ON COLUMN joint_order.judged_at IS '综合判定时间（UTC），未判定为空';
COMMENT ON COLUMN joint_order.created_at IS '建单时间（UTC）';
COMMENT ON COLUMN joint_order.updated_at IS '最近一次成功变更时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    item_name VARCHAR(128) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    limit_value DECIMAL(12,4) NOT NULL,
    CONSTRAINT uk_joint_order_item UNIQUE (order_id, item_code)
);
COMMENT ON TABLE joint_order_item IS '联检单项目：建单时从合成目录冻结，至少两种且单内唯一';
COMMENT ON COLUMN joint_order_item.order_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_order_item.item_code IS '合成项目编码（单内唯一）';
COMMENT ON COLUMN joint_order_item.item_name IS '合成项目名称（冻结）';
COMMENT ON COLUMN joint_order_item.unit IS '浓度单位（冻结）';
COMMENT ON COLUMN joint_order_item.limit_value IS '逐项合成限值（冻结），恰等合格';

CREATE TABLE IF NOT EXISTS joint_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    order_id BIGINT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    sample_no VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_joint_assign_request_item UNIQUE (request_id, item_code),
    CONSTRAINT uk_joint_assign_order_item UNIQUE (order_id, item_code),
    CONSTRAINT uk_joint_assign_sample_no UNIQUE (sample_no)
);
COMMENT ON TABLE joint_assignment IS '联检分样：一次分样请求覆盖联检单全部项目（每项目一行），任一失败整单回滚不分样';
COMMENT ON COLUMN joint_assignment.request_id IS '分样幂等请求号，同一请求内按项目唯一';
COMMENT ON COLUMN joint_assignment.fingerprint IS '分样幂等指纹（含版本、集合与精确时刻）';
COMMENT ON COLUMN joint_assignment.order_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_assignment.item_code IS '分样对应合成项目编码';
COMMENT ON COLUMN joint_assignment.sample_no IS '样本号，全局唯一（跨全部联检分样）';
COMMENT ON COLUMN joint_assignment.created_at IS '分样时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    order_id BIGINT NOT NULL,
    assignment_id BIGINT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    concentration DECIMAL(12,4) NOT NULL,
    qualified BOOLEAN NOT NULL,
    item_version INT NOT NULL,
    order_version INT NOT NULL,
    superseded BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE joint_result IS '联检结果：引用分样（样本）、项目与提交时联检版本；复测保留旧行并递增项目版本';
COMMENT ON COLUMN joint_result.request_id IS '结果提交/复测幂等请求号，全局唯一';
COMMENT ON COLUMN joint_result.fingerprint IS '结果幂等指纹（含版本、样本、浓度与精确时刻）';
COMMENT ON COLUMN joint_result.order_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_result.assignment_id IS '引用的分样记录 ID（样本）';
COMMENT ON COLUMN joint_result.item_code IS '结果对应合成项目编码';
COMMENT ON COLUMN joint_result.concentration IS '检出浓度（单位随单冻结），有限非负，恰等限值判合格';
COMMENT ON COLUMN joint_result.qualified IS '单项结论：TRUE 浓度<=限值（含恰等）/FALSE 超限';
COMMENT ON COLUMN joint_result.item_version IS '项目内结果版本：首条为 1，每次复测加 1';
COMMENT ON COLUMN joint_result.order_version IS '提交成功时的联检单版本快照';
COMMENT ON COLUMN joint_result.superseded IS '是否已被复测替换：TRUE 历史结果/FALSE 当前结果';
COMMENT ON COLUMN joint_result.created_at IS '结果提交时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_supplement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    order_id BIGINT NOT NULL,
    item_code VARCHAR(64),
    note VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_joint_supplement_request UNIQUE (request_id)
);
COMMENT ON TABLE joint_supplement IS '综合判定后的补充信息：只记录不重开判定、不改批次状态';
COMMENT ON COLUMN joint_supplement.request_id IS '补充信息幂等请求号，全局唯一';
COMMENT ON COLUMN joint_supplement.fingerprint IS '补充信息幂等指纹（含精确时刻）';
COMMENT ON COLUMN joint_supplement.order_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_supplement.item_code IS '补充信息关联项目编码，可为空表示整单补充';
COMMENT ON COLUMN joint_supplement.note IS '补充信息文本，不影响综合判定结论';
COMMENT ON COLUMN joint_supplement.created_at IS '补充信息记录时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_judgment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    order_id BIGINT NOT NULL,
    verdict VARCHAR(16) NOT NULL,
    item_versions VARCHAR(512) NOT NULL,
    evidence CLOB NOT NULL,
    evidence_fingerprint VARCHAR(128) NOT NULL,
    judged_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_joint_judgment_request UNIQUE (request_id),
    CONSTRAINT uk_joint_judgment_order UNIQUE (order_id)
);
COMMENT ON TABLE joint_judgment IS '综合判定：携带全部项目当前结果版本并冻结证据，每单至多一条';
COMMENT ON COLUMN joint_judgment.request_id IS '综合判定幂等请求号，全局唯一';
COMMENT ON COLUMN joint_judgment.fingerprint IS '综合判定幂等指纹（含期望版本与精确时刻）';
COMMENT ON COLUMN joint_judgment.order_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_judgment.verdict IS '综合结论：QUALIFIED 全部合格/UNQUALIFIED 任一超限';
COMMENT ON COLUMN joint_judgment.item_versions IS '判定时各项目当前结果版本清单（如 SYN-A=2;SYN-B=1）';
COMMENT ON COLUMN joint_judgment.evidence IS '冻结证据：全部项目结果 ID/版本/浓度/限值/单项结论的结构化 JSON';
COMMENT ON COLUMN joint_judgment.evidence_fingerprint IS '冻结证据的结构化指纹，与证据同事务写入';
COMMENT ON COLUMN joint_judgment.judged_at IS '综合判定时间（UTC）';
COMMENT ON COLUMN joint_judgment.created_at IS '记录创建时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_idempotency (
    request_id VARCHAR(64) PRIMARY KEY,
    fingerprint VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    order_id BIGINT NOT NULL,
    response_json CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE joint_idempotency IS '联检写操作幂等记录：同键同参重放首次冻结结果，异参冲突，失败不占键，与业务同事务提交';
COMMENT ON COLUMN joint_idempotency.request_id IS '幂等请求号（主键）';
COMMENT ON COLUMN joint_idempotency.fingerprint IS '首次请求的结构化指纹';
COMMENT ON COLUMN joint_idempotency.operation IS '操作类型：CREATE_ORDER/ASSIGN/RESULT/JUDGE/SUPPLEMENT';
COMMENT ON COLUMN joint_idempotency.order_id IS '关联联检单 ID';
COMMENT ON COLUMN joint_idempotency.response_json IS '首次成功响应快照（冻结结果），重放原样返回';
COMMENT ON COLUMN joint_idempotency.created_at IS '首次成功提交时间（UTC）';

-- 合成种子数据（仅合成规则，不代表真实农残限值、收费或监管要求）
MERGE INTO joint_catalog_item (item_code, item_name, unit, limit_value, catalog_version, active, created_at)
KEY(item_code) VALUES ('SYN-A', '合成项目甲', 'mg/kg', 0.1000, 'CAT-SYN-2026-1', TRUE, TIMESTAMP '2026-01-01 00:00:00');
MERGE INTO joint_catalog_item (item_code, item_name, unit, limit_value, catalog_version, active, created_at)
KEY(item_code) VALUES ('SYN-B', '合成项目乙', 'mg/kg', 0.2000, 'CAT-SYN-2026-1', TRUE, TIMESTAMP '2026-01-01 00:00:00');
MERGE INTO joint_catalog_item (item_code, item_name, unit, limit_value, catalog_version, active, created_at)
KEY(item_code) VALUES ('SYN-C', '合成项目丙', 'mg/kg', 0.5000, 'CAT-SYN-2026-1', TRUE, TIMESTAMP '2026-01-01 00:00:00');

MERGE INTO joint_lab (id, lab_code, lab_name, capability_version, active, created_at)
KEY(lab_code) VALUES (1, 'SYN-LAB-1', '合成检验机构一', 'CAP-SYN-2026-1', TRUE, TIMESTAMP '2026-01-01 00:00:00');
MERGE INTO joint_lab (id, lab_code, lab_name, capability_version, active, created_at)
KEY(lab_code) VALUES (2, 'SYN-LAB-2', '合成检验机构二', 'CAP-SYN-2026-1', TRUE, TIMESTAMP '2026-01-01 00:00:00');

MERGE INTO joint_lab_capability (lab_id, item_code, capability_version)
KEY(lab_id, item_code) VALUES (1, 'SYN-A', 'CAP-SYN-2026-1');
MERGE INTO joint_lab_capability (lab_id, item_code, capability_version)
KEY(lab_id, item_code) VALUES (1, 'SYN-B', 'CAP-SYN-2026-1');
MERGE INTO joint_lab_capability (lab_id, item_code, capability_version)
KEY(lab_id, item_code) VALUES (2, 'SYN-A', 'CAP-SYN-2026-1');
MERGE INTO joint_lab_capability (lab_id, item_code, capability_version)
KEY(lab_id, item_code) VALUES (2, 'SYN-B', 'CAP-SYN-2026-1');
MERGE INTO joint_lab_capability (lab_id, item_code, capability_version)
KEY(lab_id, item_code) VALUES (2, 'SYN-C', 'CAP-SYN-2026-1');
