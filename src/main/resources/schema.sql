-- 数据中心割接窗口与回退判据服务建表脚本（H2 MySQL 兼容模式）
-- 所有时间字段均为服务器本地时间（Asia/Shanghai），NULL 表示该时点尚未发生。

-- 业务系统（合成数据）
CREATE TABLE IF NOT EXISTS business_system (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL
);
COMMENT ON TABLE business_system IS '业务系统主数据（合成数据）';
COMMENT ON COLUMN business_system.id IS '主键';
COMMENT ON COLUMN business_system.code IS '业务系统编码，唯一';
COMMENT ON COLUMN business_system.name IS '业务系统名称';

-- 设备（合成数据）
CREATE TABLE IF NOT EXISTS device (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL
);
COMMENT ON TABLE device IS '设备主数据（合成数据）';
COMMENT ON COLUMN device.id IS '主键';
COMMENT ON COLUMN device.code IS '设备编码，唯一';
COMMENT ON COLUMN device.name IS '设备名称';

-- 设备-业务关联（合成数据）
CREATE TABLE IF NOT EXISTS device_business (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_code VARCHAR(64) NOT NULL,
    business_code VARCHAR(64) NOT NULL,
    CONSTRAINT uk_device_business UNIQUE (device_code, business_code)
);
COMMENT ON TABLE device_business IS '设备与业务系统关联关系（合成数据）';
COMMENT ON COLUMN device_business.id IS '主键';
COMMENT ON COLUMN device_business.device_code IS '设备编码';
COMMENT ON COLUMN device_business.business_code IS '业务系统编码';

-- 窗口日历（合成数据）
CREATE TABLE IF NOT EXISTS window_calendar (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    window_start DATETIME NOT NULL,
    window_end DATETIME NOT NULL,
    description VARCHAR(255) NULL
);
COMMENT ON TABLE window_calendar IS '割接窗口日历（合成数据），半开区间 [window_start, window_end)';
COMMENT ON COLUMN window_calendar.id IS '主键';
COMMENT ON COLUMN window_calendar.window_start IS '窗口开始时间（含）';
COMMENT ON COLUMN window_calendar.window_end IS '窗口结束时间（不含）';
COMMENT ON COLUMN window_calendar.description IS '窗口说明';

-- 割接申请
CREATE TABLE IF NOT EXISTS cutover_application (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    device_code VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    window_start DATETIME NOT NULL,
    window_end DATETIME NOT NULL,
    status VARCHAR(32) NOT NULL,
    reject_reason VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
COMMENT ON TABLE cutover_application IS '割接申请单，状态机：CREATED/ANALYZED/APPROVED/EXECUTING/VERIFIED/SEALED/REJECTED/ROLLED_BACK/WINDOW_OVERDUE';
COMMENT ON COLUMN cutover_application.id IS '主键';
COMMENT ON COLUMN cutover_application.request_id IS '幂等请求标识，全局唯一';
COMMENT ON COLUMN cutover_application.device_code IS '目标设备编码';
COMMENT ON COLUMN cutover_application.title IS '割接标题';
COMMENT ON COLUMN cutover_application.priority IS '变更优先级：HIGH/NORMAL/LOW';
COMMENT ON COLUMN cutover_application.window_start IS '割接窗口开始时间（含）';
COMMENT ON COLUMN cutover_application.window_end IS '割接窗口结束时间（不含），最长 4 小时（恰 4 小时合法）';
COMMENT ON COLUMN cutover_application.status IS '申请状态';
COMMENT ON COLUMN cutover_application.reject_reason IS '拒绝原因，NULL 表示未拒绝';
COMMENT ON COLUMN cutover_application.created_at IS '创建时间';
COMMENT ON COLUMN cutover_application.updated_at IS '最近更新时间';
CREATE INDEX IF NOT EXISTS idx_cutover_device_window ON cutover_application (device_code, window_start, window_end);
CREATE INDEX IF NOT EXISTS idx_cutover_status ON cutover_application (status);

-- 割接步骤
CREATE TABLE IF NOT EXISTS cutover_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cutover_id BIGINT NOT NULL,
    seq INT NOT NULL,
    name VARCHAR(128) NOT NULL,
    step_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at DATETIME NULL,
    completed_at DATETIME NULL,
    CONSTRAINT uk_cutover_step_seq UNIQUE (cutover_id, seq)
);
COMMENT ON TABLE cutover_step IS '割接步骤，按 seq 顺序执行，逐条回报';
COMMENT ON COLUMN cutover_step.id IS '主键';
COMMENT ON COLUMN cutover_step.cutover_id IS '所属割接申请主键';
COMMENT ON COLUMN cutover_step.seq IS '步骤序号，从 1 开始连续递增';
COMMENT ON COLUMN cutover_step.name IS '步骤名称';
COMMENT ON COLUMN cutover_step.step_type IS '步骤类型：NORMAL 普通步骤 / VERIFY 关键验证点';
COMMENT ON COLUMN cutover_step.status IS '步骤状态：PENDING/RUNNING/DONE/FAILED/FROZEN';
COMMENT ON COLUMN cutover_step.started_at IS '步骤开始时间，NULL 表示未开始';
COMMENT ON COLUMN cutover_step.completed_at IS '步骤完成时间，NULL 表示未完成';

-- 回退记录（回退判据命中留痕，可复算）
CREATE TABLE IF NOT EXISTS rollback_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cutover_id BIGINT NOT NULL UNIQUE,
    trigger_reason VARCHAR(32) NOT NULL,
    detail VARCHAR(1024) NULL,
    step_id BIGINT NULL,
    snapshot_json CLOB NULL,
    rolled_back_at DATETIME NOT NULL
);
COMMENT ON TABLE rollback_record IS '回退记录，每个割接最多一条；snapshot_json 为回退时点步骤快照，可复算判据';
COMMENT ON COLUMN rollback_record.id IS '主键';
COMMENT ON COLUMN rollback_record.cutover_id IS '所属割接申请主键，唯一';
COMMENT ON COLUMN rollback_record.trigger_reason IS '回退触发判据：VERIFY_TIMEOUT/HARD_CHECK_FAILED/STEP_FAILED/WINDOW_OVERDUE/FORCED';
COMMENT ON COLUMN rollback_record.detail IS '判据命中明细说明';
COMMENT ON COLUMN rollback_record.step_id IS '触发回退的步骤主键，NULL 表示非步骤触发';
COMMENT ON COLUMN rollback_record.snapshot_json IS '回退时点步骤状态快照（JSON），用于复算';
COMMENT ON COLUMN rollback_record.rolled_back_at IS '回退指令下达时间';

-- 迟到步骤回报事件（终态后补报只记录事件，不改变状态）
CREATE TABLE IF NOT EXISTS step_report_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cutover_id BIGINT NOT NULL,
    step_seq INT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    result VARCHAR(16) NOT NULL,
    note VARCHAR(512) NULL,
    reported_at DATETIME NOT NULL
);
COMMENT ON TABLE step_report_event IS '割接进入终态后补报的步骤事件，仅留痕不生效';
COMMENT ON COLUMN step_report_event.id IS '主键';
COMMENT ON COLUMN step_report_event.cutover_id IS '所属割接申请主键';
COMMENT ON COLUMN step_report_event.step_seq IS '补报的步骤序号';
COMMENT ON COLUMN step_report_event.request_id IS '补报请求标识';
COMMENT ON COLUMN step_report_event.result IS '补报结果：SUCCESS/FAIL';
COMMENT ON COLUMN step_report_event.note IS '补报备注';
COMMENT ON COLUMN step_report_event.reported_at IS '补报接收时间';

-- 幂等记录
CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NULL,
    response_json CLOB NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_idempotency UNIQUE (request_id, scope)
);
COMMENT ON TABLE idempotency_record IS '幂等记录：同键同参返回首次结果，同键异参返回 409';
COMMENT ON COLUMN idempotency_record.id IS '主键';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等请求标识';
COMMENT ON COLUMN idempotency_record.scope IS '幂等作用域：APPLY/STEP_REPORT';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求指纹（SHA-256），覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN idempotency_record.resource_id IS '首次请求产生的资源标识（申请主键或步骤主键）';
COMMENT ON COLUMN idempotency_record.response_json IS '首次请求响应快照（JSON），重放时原样返回';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时间';
