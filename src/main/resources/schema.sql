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

-- ============ 联检（疑似批次多项目联合检测）合成规则表 ============
-- 以下目录与机构能力均为合成数据，仅用于本服务演示与测试，不对应真实检测收费或监管规则。

CREATE TABLE IF NOT EXISTS catalog_item (
    item VARCHAR(64) PRIMARY KEY,
    limit_value DECIMAL(12,4) NOT NULL,
    catalog_version VARCHAR(32) NOT NULL
);
COMMENT ON TABLE catalog_item IS '合成项目目录：联检可选检测项目及限值，建单时逐项冻结';
COMMENT ON COLUMN catalog_item.item IS '检测项目名称（合成）';
COMMENT ON COLUMN catalog_item.limit_value IS '项目限值（单位：mg/kg，合成口径），浓度恰等限值判合格';
COMMENT ON COLUMN catalog_item.catalog_version IS '目录版本号，建单时随项目一并冻结';

MERGE INTO catalog_item (item, limit_value, catalog_version) KEY(item) VALUES
    ('毒死蜱', 0.0500, 'CAT-2026-01'),
    ('克百威', 0.0200, 'CAT-2026-01'),
    ('氧乐果', 0.0200, 'CAT-2026-01'),
    ('多菌灵', 0.5000, 'CAT-2026-01'),
    ('吡虫啉', 0.2000, 'CAT-2026-01');

CREATE TABLE IF NOT EXISTS agency (
    code VARCHAR(32) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    capability_version VARCHAR(32) NOT NULL
);
COMMENT ON TABLE agency IS '合成检验机构：分样时按机构能力校验，机构能力版本随分样冻结';
COMMENT ON COLUMN agency.code IS '机构编码（合成）';
COMMENT ON COLUMN agency.name IS '机构名称（合成）';
COMMENT ON COLUMN agency.capability_version IS '机构能力版本号';

MERGE INTO agency (code, name, capability_version) KEY(code) VALUES
    ('AG-A', '合成检测机构甲', 'CAP-A-2026-01'),
    ('AG-B', '合成检测机构乙', 'CAP-B-2026-01');

CREATE TABLE IF NOT EXISTS agency_capability (
    agency_code VARCHAR(32) NOT NULL,
    item VARCHAR(64) NOT NULL,
    CONSTRAINT uk_agency_capability UNIQUE (agency_code, item)
);
COMMENT ON TABLE agency_capability IS '合成机构能力：机构可承检的检测项目集合';
COMMENT ON COLUMN agency_capability.agency_code IS '机构编码';
COMMENT ON COLUMN agency_capability.item IS '可承检的检测项目名称';

MERGE INTO agency_capability (agency_code, item) KEY(agency_code, item) VALUES
    ('AG-A', '毒死蜱'),
    ('AG-A', '克百威'),
    ('AG-A', '氧乐果'),
    ('AG-B', '克百威'),
    ('AG-B', '多菌灵'),
    ('AG-B', '吡虫啉');

CREATE TABLE IF NOT EXISTS capability_directory (
    id BIGINT PRIMARY KEY,
    version VARCHAR(32) NOT NULL
);
COMMENT ON TABLE capability_directory IS '合成机构能力目录：单行，建单时冻结其版本号';
COMMENT ON COLUMN capability_directory.version IS '能力目录版本号';

MERGE INTO capability_directory (id, version) KEY(id) VALUES (1, 'CAPDIR-2026-01');

CREATE TABLE IF NOT EXISTS joint_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    source_test_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    capability_version VARCHAR(32) NOT NULL,
    overall_result VARCHAR(16),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_joint_order_batch UNIQUE (batch_id)
);
COMMENT ON TABLE joint_order IS '联检单：疑似冻结批次建立，引用冻结快检结果与合成项目目录；版本从 1 开始，成功变更加 1';
COMMENT ON COLUMN joint_order.batch_id IS '所属批次 ID，每批次至多一张联检单';
COMMENT ON COLUMN joint_order.source_test_id IS '引用的冻结快检记录 ID（疑似超标快检）';
COMMENT ON COLUMN joint_order.status IS '联检单状态：CREATED 已建单待分样/ASSIGNED 已分样待判定/DETERMINED 已综合判定（终态）';
COMMENT ON COLUMN joint_order.version IS '乐观版本号：从 1 开始，分样/结果/复测/综合判定成功各加 1';
COMMENT ON COLUMN joint_order.capability_version IS '建单时冻结的机构能力目录版本号';
COMMENT ON COLUMN joint_order.overall_result IS '综合判定结果：PASS 合格/FAIL 不合格，未判定为 NULL';
COMMENT ON COLUMN joint_order.created_at IS '建单时间（UTC）';
COMMENT ON COLUMN joint_order.updated_at IS '最近变更时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    item VARCHAR(64) NOT NULL,
    limit_value DECIMAL(12,4) NOT NULL,
    catalog_version VARCHAR(32) NOT NULL,
    CONSTRAINT uk_joint_order_item UNIQUE (order_id, item)
);
COMMENT ON TABLE joint_order_item IS '联检项目：建单时冻结的逐项限值与目录版本，每单至少两项且唯一';
COMMENT ON COLUMN joint_order_item.order_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_order_item.item IS '检测项目名称';
COMMENT ON COLUMN joint_order_item.limit_value IS '冻结限值（单位：mg/kg），浓度恰等限值判合格';
COMMENT ON COLUMN joint_order_item.catalog_version IS '冻结的合成项目目录版本号';

CREATE TABLE IF NOT EXISTS joint_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    item VARCHAR(64) NOT NULL,
    sample_no VARCHAR(64) NOT NULL,
    agency_code VARCHAR(32) NOT NULL,
    agency_capability_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_joint_assignment_sample UNIQUE (sample_no),
    CONSTRAINT uk_joint_assignment_order_item UNIQUE (order_id, item)
);
COMMENT ON TABLE joint_assignment IS '分样分配：覆盖联检单全部项目，任一项失败整单不分样；样本号全局唯一';
COMMENT ON COLUMN joint_assignment.order_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_assignment.item IS '检测项目名称';
COMMENT ON COLUMN joint_assignment.sample_no IS '分样样本号，全局唯一';
COMMENT ON COLUMN joint_assignment.agency_code IS '承检机构编码（合成）';
COMMENT ON COLUMN joint_assignment.agency_capability_version IS '分样时冻结的机构能力版本号';
COMMENT ON COLUMN joint_assignment.created_at IS '分样时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    item VARCHAR(64) NOT NULL,
    sample_no VARCHAR(64) NOT NULL,
    order_version INT NOT NULL,
    result_version INT NOT NULL,
    concentration DECIMAL(12,4) NOT NULL,
    qualified BOOLEAN NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_joint_result_version UNIQUE (order_id, item, result_version)
);
COMMENT ON TABLE joint_result IS '联检结果：引用样本、项目与联检版本；复测保留旧结果并递增结果版本';
COMMENT ON COLUMN joint_result.order_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_result.item IS '检测项目名称';
COMMENT ON COLUMN joint_result.sample_no IS '检测结果引用的分样样本号';
COMMENT ON COLUMN joint_result.order_version IS '结果录入时的联检单版本号（联检版本）';
COMMENT ON COLUMN joint_result.result_version IS '该项目结果版本：首次为 1，每次复测加 1，旧结果保留';
COMMENT ON COLUMN joint_result.concentration IS '检测浓度（单位：mg/kg），有限非负';
COMMENT ON COLUMN joint_result.qualified IS '单项结论：TRUE 合格（浓度小于等于限值，恰等合格）/FALSE 超限';
COMMENT ON COLUMN joint_result.occurred_at IS '检测发生时刻（UTC，由请求方提供的精确时刻）';
COMMENT ON COLUMN joint_result.created_at IS '结果登记时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_determination (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    overall_result VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_joint_determination_order UNIQUE (order_id)
);
COMMENT ON TABLE joint_determination IS '联检综合判定：携带全部项目结果版本并冻结证据，每单至多一次';
COMMENT ON COLUMN joint_determination.order_id IS '所属联检单 ID';
COMMENT ON COLUMN joint_determination.overall_result IS '综合判定结果：PASS 合格/FAIL 不合格（任一项目超限即不合格）';
COMMENT ON COLUMN joint_determination.created_at IS '综合判定时间（UTC）';

CREATE TABLE IF NOT EXISTS joint_determination_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    determination_id BIGINT NOT NULL,
    item VARCHAR(64) NOT NULL,
    result_version INT NOT NULL,
    concentration DECIMAL(12,4) NOT NULL,
    qualified BOOLEAN NOT NULL,
    CONSTRAINT uk_joint_det_item UNIQUE (determination_id, item)
);
COMMENT ON TABLE joint_determination_item IS '综合判定证据快照：判定时刻各项目的结果版本、浓度与结论，冻结不可改';
COMMENT ON COLUMN joint_determination_item.determination_id IS '所属综合判定 ID';
COMMENT ON COLUMN joint_determination_item.item IS '检测项目名称';
COMMENT ON COLUMN joint_determination_item.result_version IS '判定时该项目采用的结果版本';
COMMENT ON COLUMN joint_determination_item.concentration IS '判定时该项目浓度（单位：mg/kg）';
COMMENT ON COLUMN joint_determination_item.qualified IS '判定时该项目结论：TRUE 合格/FALSE 超限';

CREATE TABLE IF NOT EXISTS joint_request_log (
    request_id VARCHAR(64) PRIMARY KEY,
    fingerprint VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    response CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE joint_request_log IS '联检写操作幂等台账：与业务、版本同事务；失败回滚不占键，同键同参重放首次冻结结果';
COMMENT ON COLUMN joint_request_log.request_id IS '幂等请求号，全局唯一';
COMMENT ON COLUMN joint_request_log.fingerprint IS '结构化幂等指纹：长度前缀编码全部参数（含版本、集合与精确时刻）的 SHA-256';
COMMENT ON COLUMN joint_request_log.operation IS '操作类型：JOINT_CREATE/JOINT_ASSIGN/JOINT_RESULT/JOINT_DETERMINE';
COMMENT ON COLUMN joint_request_log.response IS '首次成功响应快照（JSON），同键同参重放时原样返回';
COMMENT ON COLUMN joint_request_log.created_at IS '登记时间（UTC）';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE late_event IS '迟到事件：批次终态后迟到的检测/复检结果只记录不改状态';
COMMENT ON COLUMN late_event.batch_id IS '所属批次 ID';
COMMENT ON COLUMN late_event.event_type IS '事件类型：LATE_TEST 迟到快检/LATE_RECHECK 迟到复检/LATE_JOINT_RESULT 综合判定后补充的联检结果';
COMMENT ON COLUMN late_event.payload IS '迟到结果摘要';
COMMENT ON COLUMN late_event.created_at IS '事件记录时间（UTC）';
