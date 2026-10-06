-- 行道树风险修剪审批流程 schema（H2 MODE=MySQL）
-- 时间字段语义：TIMESTAMP 为 Asia/Shanghai 本地时间；DATE 为本地日历日，不含时区偏移。

CREATE TABLE IF NOT EXISTS road_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_segment_name UNIQUE (name)
);
COMMENT ON TABLE road_segment IS '路段档案';
COMMENT ON COLUMN road_segment.id IS '主键';
COMMENT ON COLUMN road_segment.name IS '路段名称，全局唯一';
COMMENT ON COLUMN road_segment.created_at IS '建档时间（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS tree (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    segment_id BIGINT NOT NULL REFERENCES road_segment(id),
    tree_code VARCHAR(64) NOT NULL,
    species VARCHAR(64) NOT NULL,
    tree_age INT NOT NULL,
    health_score INT NOT NULL,
    lean_degree DECIMAL(5,2) NOT NULL,
    cavity BOOLEAN NOT NULL,
    risk_score INT,
    risk_level INT,
    evaluated_at TIMESTAMP,
    CONSTRAINT uk_tree_code UNIQUE (tree_code)
);
COMMENT ON TABLE tree IS '行道树档案，按路段归集';
COMMENT ON COLUMN tree.id IS '主键';
COMMENT ON COLUMN tree.segment_id IS '所属路段 id';
COMMENT ON COLUMN tree.tree_code IS '树木编号，全局唯一';
COMMENT ON COLUMN tree.species IS '树种';
COMMENT ON COLUMN tree.tree_age IS '树龄（年）';
COMMENT ON COLUMN tree.health_score IS '健康度评分 0-100，越低越差';
COMMENT ON COLUMN tree.lean_degree IS '倾斜度（度）';
COMMENT ON COLUMN tree.cavity IS '是否存在空洞';
COMMENT ON COLUMN tree.risk_score IS '合成评估模型风险分，NULL 表示未评估';
COMMENT ON COLUMN tree.risk_level IS '风险等级 1-4（一级最高），NULL 表示未评估';
COMMENT ON COLUMN tree.evaluated_at IS '最近一次评估时间（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS pruning_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tree_id BIGINT NOT NULL REFERENCES tree(id),
    segment_id BIGINT NOT NULL REFERENCES road_segment(id),
    risk_level INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_date DATE NOT NULL,
    deadline_date DATE NOT NULL,
    closed_at TIMESTAMP,
    escalated BOOLEAN NOT NULL DEFAULT FALSE,
    plan_version INT NOT NULL DEFAULT 1
);
COMMENT ON TABLE pruning_task IS '修剪任务/计划';
COMMENT ON COLUMN pruning_task.id IS '主键';
COMMENT ON COLUMN pruning_task.tree_id IS '目标树木 id';
COMMENT ON COLUMN pruning_task.segment_id IS '所属路段 id（冗余便于并发控制）';
COMMENT ON COLUMN pruning_task.risk_level IS '任务生成/重排时的风险等级 1-3';
COMMENT ON COLUMN pruning_task.status IS '状态：PENDING 待办 / IN_PROGRESS 在途 / CLOSED 已闭环 / CANCELLED 已取消';
COMMENT ON COLUMN pruning_task.created_date IS '任务创建日（本地日历日）';
COMMENT ON COLUMN pruning_task.deadline_date IS '闭环截止日（含当日，一级风险为创建日+7 天）';
COMMENT ON COLUMN pruning_task.closed_at IS '闭环时间，NULL 表示未闭环';
COMMENT ON COLUMN pruning_task.escalated IS '是否已逾期升级督办';
COMMENT ON COLUMN pruning_task.plan_version IS '计划版本，重排时 +1';

CREATE TABLE IF NOT EXISTS work_permit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    permit_no VARCHAR(64) NOT NULL,
    task_id BIGINT NOT NULL REFERENCES pruning_task(id),
    segment_id BIGINT NOT NULL REFERENCES road_segment(id),
    work_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    deadline_date DATE NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT uk_permit_no UNIQUE (permit_no)
);
COMMENT ON TABLE work_permit IS '修剪作业许可';
COMMENT ON COLUMN work_permit.id IS '主键';
COMMENT ON COLUMN work_permit.permit_no IS '许可编号，全局唯一';
COMMENT ON COLUMN work_permit.task_id IS '关联修剪任务 id';
COMMENT ON COLUMN work_permit.segment_id IS '作业路段 id';
COMMENT ON COLUMN work_permit.work_type IS '作业类型：AERIAL 高空 / GROUND 地面';
COMMENT ON COLUMN work_permit.status IS '状态：EXECUTING 执行中 / COMPLETED 已完成';
COMMENT ON COLUMN work_permit.issued_at IS '签发时间（Asia/Shanghai）';
COMMENT ON COLUMN work_permit.deadline_date IS '既定时限（签发时取自任务截止日，计划重排不改变在途许可）';
COMMENT ON COLUMN work_permit.completed_at IS '完成时间，NULL 表示未完成';

CREATE TABLE IF NOT EXISTS segment_active_permit (
    segment_id BIGINT PRIMARY KEY REFERENCES road_segment(id),
    permit_id BIGINT NOT NULL REFERENCES work_permit(id)
);
COMMENT ON TABLE segment_active_permit IS '路段执行中许可占用表，主键保证同一路段同时刻仅一张执行中许可';
COMMENT ON COLUMN segment_active_permit.segment_id IS '路段 id（主键即并发唯一约束）';
COMMENT ON COLUMN segment_active_permit.permit_id IS '占用中的许可 id';

CREATE TABLE IF NOT EXISTS operation_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    permit_id BIGINT NOT NULL REFERENCES work_permit(id),
    before_photo_id VARCHAR(128) NOT NULL,
    after_photo_id VARCHAR(128) NOT NULL,
    disposal_record VARCHAR(256) NOT NULL,
    reported_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_report_permit UNIQUE (permit_id)
);
COMMENT ON TABLE operation_report IS '作业回传记录，一张许可仅允许一条回传';
COMMENT ON COLUMN operation_report.id IS '主键';
COMMENT ON COLUMN operation_report.permit_id IS '关联许可 id，唯一';
COMMENT ON COLUMN operation_report.before_photo_id IS '作业前照片标识';
COMMENT ON COLUMN operation_report.after_photo_id IS '作业后照片标识';
COMMENT ON COLUMN operation_report.disposal_record IS '弃枝清运记录';
COMMENT ON COLUMN operation_report.reported_at IS '回传时间（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS gale_warning (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warning_no VARCHAR(64) NOT NULL,
    warning_level VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    published_at TIMESTAMP NOT NULL,
    lifted_at TIMESTAMP,
    CONSTRAINT uk_warning_no UNIQUE (warning_no)
);
COMMENT ON TABLE gale_warning IS '大风预警（合成级别）';
COMMENT ON COLUMN gale_warning.id IS '主键';
COMMENT ON COLUMN gale_warning.warning_no IS '预警编号，全局唯一';
COMMENT ON COLUMN gale_warning.warning_level IS '合成预警级别：BLUE/YELLOW/ORANGE/RED';
COMMENT ON COLUMN gale_warning.status IS '状态：ACTIVE 生效中 / LIFTED 已解除';
COMMENT ON COLUMN gale_warning.published_at IS '发布时间（Asia/Shanghai）';
COMMENT ON COLUMN gale_warning.lifted_at IS '解除时间，NULL 表示未解除';

CREATE TABLE IF NOT EXISTS segment_freeze (
    segment_id BIGINT PRIMARY KEY REFERENCES road_segment(id),
    warning_id BIGINT NOT NULL REFERENCES gale_warning(id),
    frozen_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL
);
COMMENT ON TABLE segment_freeze IS '高风险路段高空作业冻结记录，与预警同事务生效';
COMMENT ON COLUMN segment_freeze.segment_id IS '被冻结路段 id（主键，一路段至多一条冻结记录）';
COMMENT ON COLUMN segment_freeze.warning_id IS '触发冻结的预警 id';
COMMENT ON COLUMN segment_freeze.frozen_at IS '冻结时间（Asia/Shanghai）';
COMMENT ON COLUMN segment_freeze.status IS '状态：FROZEN 冻结中 / RELEASED 已解除';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    http_status INT NOT NULL,
    response_body CLOB,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE idempotency_record IS '写操作幂等记录';
COMMENT ON COLUMN idempotency_record.request_id IS '请求标识（主键）';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN idempotency_record.http_status IS '首次执行返回的 HTTP 状态码';
COMMENT ON COLUMN idempotency_record.response_body IS '首次执行响应 JSON，NULL 表示处理中';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时间（Asia/Shanghai）';
