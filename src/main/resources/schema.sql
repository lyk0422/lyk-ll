-- 药物警戒个例管理服务建表脚本（H2 MySQL 兼容模式，幂等可重复执行）
-- 时间字段均为 UTC 瞬时值（TIMESTAMP，无时区），日期字段为 UTC 日界日期。

CREATE TABLE IF NOT EXISTS pv_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(32) NOT NULL,
    patient_id VARCHAR(64) NOT NULL,
    drug_name VARCHAR(128) NOT NULL,
    adverse_event VARCHAR(256) NOT NULL,
    seriousness VARCHAR(32) NOT NULL,
    awareness_date DATE NOT NULL,
    due_at TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL,
    initial_submitted_at TIMESTAMP NULL,
    last_follow_up_at TIMESTAMP NULL,
    closed_at TIMESTAMP NULL,
    linked_case_no VARCHAR(32) NULL,
    active_marker VARCHAR(1) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 同一患者+同一药品+同一不良反应事件仅允许一个活动个例：
-- 活动个例 active_marker='A'，关闭后置 NULL（H2 唯一索引中 NULL 互不相等，可存在多个历史关闭个例）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_pv_case_active
    ON pv_case (patient_id, drug_name, adverse_event, active_marker);

CREATE TABLE IF NOT EXISTS pv_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    report_type VARCHAR(16) NOT NULL,
    narrative VARCHAR(2048) NOT NULL,
    is_late BOOLEAN NOT NULL DEFAULT FALSE,
    late_reason VARCHAR(512) NULL,
    submitted_at TIMESTAMP NOT NULL,
    case_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- 幂等键：requestId 全局唯一，重放时比对指纹。
CREATE UNIQUE INDEX IF NOT EXISTS uk_pv_report_request ON pv_report (request_id);
CREATE INDEX IF NOT EXISTS idx_pv_report_case ON pv_report (case_id);

CREATE TABLE IF NOT EXISTS pv_submission_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pv_batch_no ON pv_submission_batch (batch_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pv_batch_request ON pv_submission_batch (request_id);

CREATE TABLE IF NOT EXISTS pv_submission_batch_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL,
    case_no VARCHAR(32) NOT NULL,
    case_status VARCHAR(32) NOT NULL,
    case_version INT NOT NULL,
    initial_submitted_at TIMESTAMP NULL,
    last_follow_up_at TIMESTAMP NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pv_batch_item ON pv_submission_batch_item (batch_id, case_id);

COMMENT ON TABLE pv_case IS '药物警戒个例：按获知→初报→随访→关闭流转';
COMMENT ON COLUMN pv_case.case_no IS '个例编号，业务唯一，格式 PV-yyyyMMdd-序号';
COMMENT ON COLUMN pv_case.patient_id IS '患者标识（合成数据）';
COMMENT ON COLUMN pv_case.drug_name IS '怀疑药品名称（合成数据）';
COMMENT ON COLUMN pv_case.adverse_event IS '不良反应事件描述';
COMMENT ON COLUMN pv_case.seriousness IS '严重性分类：FATAL_OR_LIFE_THREATENING 致死或危及生命（7 日时限）；OTHER_SERIOUS 其他严重（15 日时限）';
COMMENT ON COLUMN pv_case.awareness_date IS '获知日 T0（UTC 日界日期）';
COMMENT ON COLUMN pv_case.due_at IS '初报期限截止时刻（UTC，含边界，期限最后一日 23:59:59.999999999）';
COMMENT ON COLUMN pv_case.status IS '个例状态：INITIAL_SUBMITTED 已初报；FOLLOW_UP_SUBMITTED 已有随访；CLOSED 已关闭（终态）';
COMMENT ON COLUMN pv_case.initial_submitted_at IS '初报提交时刻（UTC）';
COMMENT ON COLUMN pv_case.last_follow_up_at IS '最近随访提交时刻（UTC），无随访时为 NULL';
COMMENT ON COLUMN pv_case.closed_at IS '关闭时刻（UTC），未关闭为 NULL';
COMMENT ON COLUMN pv_case.linked_case_no IS '关闭后新建关联个例时引用的原个例编号，无关联为 NULL';
COMMENT ON COLUMN pv_case.active_marker IS '活动标记：A=活动个例；NULL=已关闭（配合唯一索引保证同三元组仅一个活动个例）';
COMMENT ON COLUMN pv_case.version IS '乐观锁版本号，每次状态/内容变更递增';

COMMENT ON TABLE pv_report IS '个例报告提交记录（初报与随访），按 requestId 幂等';
COMMENT ON COLUMN pv_report.request_id IS '幂等键，全局唯一';
COMMENT ON COLUMN pv_report.fingerprint IS '幂等指纹：requestId 与全部业务字段的 SHA-256，同键异参返回 409';
COMMENT ON COLUMN pv_report.report_type IS '报告类型：INITIAL 初报；FOLLOW_UP 随访';
COMMENT ON COLUMN pv_report.is_late IS '是否迟报（提交时刻晚于 due_at）';
COMMENT ON COLUMN pv_report.late_reason IS '迟报原因，迟报时强制填写，及时为 NULL';
COMMENT ON COLUMN pv_report.case_version IS '本次提交应用后的个例版本号';

COMMENT ON TABLE pv_submission_batch IS '监管递交批次：批次内个例集合为提交时刻快照，固定不变';
COMMENT ON COLUMN pv_submission_batch.batch_no IS '递交批次号，业务唯一，格式 PB-序号';
COMMENT ON COLUMN pv_submission_batch.request_id IS '幂等键，重复提交同一 requestId 返回首次结果';

COMMENT ON TABLE pv_submission_batch_item IS '递交批次个例快照项，批次创建后不再变更';
COMMENT ON COLUMN pv_submission_batch_item.case_status IS '提交时刻个例状态快照';
COMMENT ON COLUMN pv_submission_batch_item.case_version IS '提交时刻个例版本快照';
