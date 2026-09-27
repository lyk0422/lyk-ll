-- 跑道临时关闭巡检与开放决策服务建表脚本（H2 内存库，MODE=MySQL）
-- 所有时间字段均为 UTC 瞬时值（TIMESTAMP 存储，应用层以 Instant 处理）。

CREATE TABLE IF NOT EXISTS runway_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    runway_id VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reported_at TIMESTAMP NOT NULL,
    closed_at TIMESTAMP NOT NULL,
    inspection_deadline TIMESTAMP NOT NULL,
    escalated BOOLEAN NOT NULL DEFAULT FALSE,
    assessment_type VARCHAR(32),
    active_flag TINYINT,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 同一跑道同时刻只允许一个进行中事件：active_flag=1 表示进行中，归档/恢复后置 NULL（唯一索引允许多个 NULL）
CREATE UNIQUE INDEX IF NOT EXISTS uk_runway_event_active ON runway_event (runway_id, active_flag);

COMMENT ON TABLE runway_event IS '跑道关闭事件：接报→临时关闭→巡检评估→恢复/持续关闭→归档';
COMMENT ON COLUMN runway_event.id IS '事件主键';
COMMENT ON COLUMN runway_event.runway_id IS '跑道编号（合成数据）';
COMMENT ON COLUMN runway_event.event_type IS '事件类型：FOD=外来物，PAVEMENT_DAMAGE=道面破损';
COMMENT ON COLUMN runway_event.status IS '状态：CLOSED临时关闭/INSPECTING巡检中/ASSESSED已评估/REOPENED已恢复/ARCHIVED已归档(终态)';
COMMENT ON COLUMN runway_event.reported_at IS '接报时间（UTC）';
COMMENT ON COLUMN runway_event.closed_at IS '临时关闭生效时间（UTC），接报即关闭';
COMMENT ON COLUMN runway_event.inspection_deadline IS '巡检开始截止时刻（closed_at+15分钟），恰满即迟延';
COMMENT ON COLUMN runway_event.escalated IS '是否已升级：巡检迟延（含恰满15分钟）时置真';
COMMENT ON COLUMN runway_event.assessment_type IS '最近一次评估类型：CLEAR_TO_REOPEN清除即可恢复/REPAIR_REQUIRED修复后恢复，未评估为NULL';
COMMENT ON COLUMN runway_event.active_flag IS '进行中标记：1=进行中，NULL=已恢复或已归档（配合唯一索引保证唯一进行中事件）';
COMMENT ON COLUMN runway_event.request_id IS '接报幂等请求标识';
COMMENT ON COLUMN runway_event.created_at IS '记录创建时间（UTC）';
COMMENT ON COLUMN runway_event.updated_at IS '记录最近更新时间（UTC）';

CREATE TABLE IF NOT EXISTS inspection_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    started_at TIMESTAMP NOT NULL,
    inspector_count INT NOT NULL,
    late BOOLEAN NOT NULL,
    recorded_after_archive BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE inspection_record IS '巡检记录：每次生效巡检或归档后迟到登记各一行';
COMMENT ON COLUMN inspection_record.id IS '巡检记录主键';
COMMENT ON COLUMN inspection_record.event_id IS '所属跑道事件ID';
COMMENT ON COLUMN inspection_record.started_at IS '巡检开始时间（UTC）';
COMMENT ON COLUMN inspection_record.inspector_count IS '巡检人数：低能见度期间须>=2，否则记录不生效（不入库）';
COMMENT ON COLUMN inspection_record.late IS '是否迟延：开始时间不早于关闭后15分钟（恰满15分钟算迟延）';
COMMENT ON COLUMN inspection_record.recorded_after_archive IS '是否归档后迟到登记：真=仅登记迟到事件，不改变事件状态';
COMMENT ON COLUMN inspection_record.created_at IS '记录创建时间（UTC）';

CREATE TABLE IF NOT EXISTS assessment_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    friction_coefficient DECIMAL(4,2) NOT NULL,
    assessment_type VARCHAR(32) NOT NULL,
    assessed_at TIMESTAMP NOT NULL,
    passed BOOLEAN NOT NULL,
    retest BOOLEAN NOT NULL DEFAULT FALSE,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE assessment_record IS '巡检评估记录：摩擦系数轨迹按 assessed_at 排序可复算';
COMMENT ON COLUMN assessment_record.id IS '评估记录主键';
COMMENT ON COLUMN assessment_record.event_id IS '所属跑道事件ID';
COMMENT ON COLUMN assessment_record.friction_coefficient IS '道面摩擦系数（无量纲，两位小数）：低于0.35须持续关闭，恰0.35合格';
COMMENT ON COLUMN assessment_record.assessment_type IS '评估类型：CLEAR_TO_REOPEN清除即可恢复/REPAIR_REQUIRED修复后恢复';
COMMENT ON COLUMN assessment_record.assessed_at IS '评估时间（UTC）';
COMMENT ON COLUMN assessment_record.passed IS '本次系数是否合格（>=0.35）';
COMMENT ON COLUMN assessment_record.retest IS '是否复测：此前存在修复类评估后的再次测量为真';
COMMENT ON COLUMN assessment_record.request_id IS '评估幂等请求标识';
COMMENT ON COLUMN assessment_record.created_at IS '记录创建时间（UTC）';

CREATE TABLE IF NOT EXISTS notam_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    runway_id VARCHAR(32) NOT NULL,
    action VARCHAR(16) NOT NULL,
    notice_no VARCHAR(64) NOT NULL,
    published_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE notam_record IS '航行通告记录（合成）：关闭与恢复通告，恢复通告与开放在同一事务内完成';
COMMENT ON COLUMN notam_record.id IS '通告记录主键';
COMMENT ON COLUMN notam_record.event_id IS '所属跑道事件ID';
COMMENT ON COLUMN notam_record.runway_id IS '跑道编号';
COMMENT ON COLUMN notam_record.action IS '通告动作：CLOSE=临时关闭，REOPEN=恢复开放';
COMMENT ON COLUMN notam_record.notice_no IS '通告编号（合成）';
COMMENT ON COLUMN notam_record.published_at IS '通告发布时间（UTC）';

CREATE TABLE IF NOT EXISTS weather_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    runway_id VARCHAR(32) NOT NULL,
    rvr_meters INT NOT NULL,
    recorded_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE weather_reading IS '气象读数（合成）：按跑道记录RVR，用于低能见度双人巡检判定';
COMMENT ON COLUMN weather_reading.id IS '气象读数主键';
COMMENT ON COLUMN weather_reading.runway_id IS '跑道编号';
COMMENT ON COLUMN weather_reading.rvr_meters IS '跑道视程RVR（单位：米），低于阈值判定低能见度';
COMMENT ON COLUMN weather_reading.recorded_at IS '读数时间（UTC）';

CREATE TABLE IF NOT EXISTS idempotency_record (
    scope VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (scope, request_id)
);

COMMENT ON TABLE idempotency_record IS '幂等记录：指纹覆盖requestId与全部业务字段，同键同参返回首次结果，同键异参返回409';
COMMENT ON COLUMN idempotency_record.scope IS '幂等作用域：report/assessment/reopen';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等请求标识';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求指纹：requestId与全部业务字段的SHA-256';
COMMENT ON COLUMN idempotency_record.response_body IS '首次成功响应报文（JSON），用于同键同参重放';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时间（UTC）';

-- 航班日历：仅追加版本，版本从1开始，每次成功发布变更加1
CREATE TABLE IF NOT EXISTS flight_calendar (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version INT NOT NULL,
    published_at TIMESTAMP NOT NULL,
    request_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_flight_calendar_version ON flight_calendar (version);

COMMENT ON TABLE flight_calendar IS '航班日历版本：仅追加，例行计划发布时冻结所依据版本，日历变更后未开始计划须重审';
COMMENT ON COLUMN flight_calendar.id IS '日历版本行主键';
COMMENT ON COLUMN flight_calendar.version IS '日历版本号，从1开始单调递增，成功发布一次加1';
COMMENT ON COLUMN flight_calendar.published_at IS '该版本生效时刻（UTC，精确时刻由请求给出）';
COMMENT ON COLUMN flight_calendar.request_id IS '发布幂等请求标识；初始种子版本为NULL';
COMMENT ON COLUMN flight_calendar.created_at IS '记录创建时间（UTC）';

-- 合成航班占用窗：每个日历版本携带一组半开占用区间 [window_start, window_end)
CREATE TABLE IF NOT EXISTS flight_occupancy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version INT NOT NULL,
    runway_id VARCHAR(32) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_flight_occupancy_version_runway ON flight_occupancy (version, runway_id);

COMMENT ON TABLE flight_occupancy IS '合成航班占用窗（半开，UTC）：与例行巡检区间正长度相交即冲突，端点相接合法';
COMMENT ON COLUMN flight_occupancy.id IS '占用窗主键';
COMMENT ON COLUMN flight_occupancy.version IS '所属航班日历版本号';
COMMENT ON COLUMN flight_occupancy.runway_id IS '跑道编号（合成数据）';
COMMENT ON COLUMN flight_occupancy.window_start IS '占用开始时刻（UTC，含）';
COMMENT ON COLUMN flight_occupancy.window_end IS '占用结束时刻（UTC，不含）';

-- 例行巡检计划：发布冻结日历版本与冲突证据；同跑道同窗仅一个获准（未终态）计划
CREATE TABLE IF NOT EXISTS routine_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    runway_id VARCHAR(32) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    duration_minutes INT NOT NULL,
    calendar_version INT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    evidence CLOB,
    slot_flag TINYINT,
    emergency_event_id BIGINT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    preempted_at TIMESTAMP,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 同跑道同窗只能一个获准计划：slot_flag=1 表示未终态占用，终态置 NULL（唯一索引允许多个 NULL）
CREATE UNIQUE INDEX IF NOT EXISTS uk_routine_plan_slot
    ON routine_plan (runway_id, window_start, window_end, slot_flag);
CREATE INDEX IF NOT EXISTS idx_routine_plan_runway_status ON routine_plan (runway_id, status);

COMMENT ON TABLE routine_plan IS '例行跑道巡检计划：发布→开始→正常完成，或被紧急关闭取消（未开始）/中止（已开始，保留检查项）';
COMMENT ON COLUMN routine_plan.runway_id IS '跑道编号（合成数据）';
COMMENT ON COLUMN routine_plan.window_start IS '巡检UTC半开窗口起点（含）';
COMMENT ON COLUMN routine_plan.window_end IS '巡检UTC半开窗口终点（不含），巡检时长须完整落在窗口内';
COMMENT ON COLUMN routine_plan.duration_minutes IS '预计巡检时长（分钟，正整数）：巡检区间[window_start, window_start+时长)与航班占用窗正长度相交即拒绝';
COMMENT ON COLUMN routine_plan.calendar_version IS '发布时冻结的航班日历版本；开始时须重新确认，日历变更后未开始计划待重审';
COMMENT ON COLUMN routine_plan.status IS '状态：APPROVED获准/NEEDS_REVIEW待重审/STARTED已开始/COMPLETED正常完成/ABORTED紧急中止/CANCELLED紧急取消';
COMMENT ON COLUMN routine_plan.evidence IS '发布冻结的冲突证据JSON：日历版本、巡检区间、参与判定的占用窗集合与判定结果';
COMMENT ON COLUMN routine_plan.slot_flag IS '获准占用标记：1=未终态（获准/待重审/已开始），终态NULL；配合唯一索引保证同跑道同窗唯一获准';
COMMENT ON COLUMN routine_plan.emergency_event_id IS '抢占本计划的紧急关闭事件ID；NULL表示未被抢占';
COMMENT ON COLUMN routine_plan.started_at IS '巡检实际开始时刻（UTC），未开始为NULL';
COMMENT ON COLUMN routine_plan.completed_at IS '正常完成时刻（UTC），未正常完成为NULL';
COMMENT ON COLUMN routine_plan.preempted_at IS '被紧急关闭取消/中止时刻（UTC），未被抢占为NULL';
COMMENT ON COLUMN routine_plan.request_id IS '计划发布幂等请求标识';
COMMENT ON COLUMN routine_plan.created_at IS '记录创建时间（UTC）';
COMMENT ON COLUMN routine_plan.updated_at IS '记录最近更新时间（UTC）';

-- 例行巡检检查项回报：紧急中止前已完成项正常保存，中止/取消后的回报仅登记迟到
CREATE TABLE IF NOT EXISTS routine_check_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    item_text VARCHAR(255) NOT NULL,
    passed BOOLEAN NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    late TINYINT NOT NULL DEFAULT 0,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_routine_check_item_plan ON routine_check_item (plan_id, id);

COMMENT ON TABLE routine_check_item IS '例行巡检检查项：已完成项在紧急中止时保留；中止/取消后新回报仅登记迟到，不得开放跑道';
COMMENT ON COLUMN routine_check_item.id IS '检查项主键';
COMMENT ON COLUMN routine_check_item.plan_id IS '所属例行计划ID';
COMMENT ON COLUMN routine_check_item.item_code IS '检查项编码（合成）';
COMMENT ON COLUMN routine_check_item.item_text IS '检查项文本（合法文本可含任意分隔符）';
COMMENT ON COLUMN routine_check_item.passed IS '该项检查是否通过';
COMMENT ON COLUMN routine_check_item.completed_at IS '检查项完成时刻（UTC，精确时刻由回报给出）';
COMMENT ON COLUMN routine_check_item.late IS '是否迟到回报：1=计划被紧急取消/中止后才回报，仅登记不开放跑道；0=进行中正常回报';
COMMENT ON COLUMN routine_check_item.request_id IS '检查回报幂等请求标识';
COMMENT ON COLUMN routine_check_item.created_at IS '记录创建时间（UTC）';

-- 例行巡检完成回报：正常完成只关闭计划；被抢占后的完成回报仅登记迟到
CREATE TABLE IF NOT EXISTS routine_completion_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    late TINYINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_routine_completion_plan ON routine_completion_report (plan_id, id);

COMMENT ON TABLE routine_completion_report IS '例行巡检完成回报：正常完成仅关闭计划，不取代恢复开放与通告流程；被抢占后回报仅登记迟到';
COMMENT ON COLUMN routine_completion_report.id IS '完成回报主键';
COMMENT ON COLUMN routine_completion_report.plan_id IS '所属例行计划ID';
COMMENT ON COLUMN routine_completion_report.completed_at IS '回报的巡检完成时刻（UTC，精确时刻）';
COMMENT ON COLUMN routine_completion_report.late IS '是否迟到完成：1=计划已被紧急取消/中止后回报，仅登记不开放跑道；0=进行中正常完成';
COMMENT ON COLUMN routine_completion_report.request_id IS '完成回报幂等请求标识';
COMMENT ON COLUMN routine_completion_report.created_at IS '记录创建时间（UTC）';

-- 初始航班日历版本1（空占用窗集合）；MERGE 保证测试清理后重建仍幂等
MERGE INTO flight_calendar (version, published_at, request_id, created_at) KEY (version)
VALUES (1, TIMESTAMP '2026-01-01 00:00:00', NULL, TIMESTAMP '2026-01-01 00:00:00');

-- 跑道串行化锁：例行开始与紧急接报在同跑道上先取同一行锁，得到唯一占用状态；跨跑道持不同行互不阻塞
CREATE TABLE IF NOT EXISTS runway_lock (
    runway_id VARCHAR(32) NOT NULL PRIMARY KEY
);

COMMENT ON TABLE runway_lock IS '跑道咨询锁：同跑道的例行开始与紧急接报事务先对该行 FOR UPDATE 串行化，跨跑道互不阻塞';
