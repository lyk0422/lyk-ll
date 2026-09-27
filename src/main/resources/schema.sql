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

CREATE TABLE IF NOT EXISTS flight_calendar (
    runway_id VARCHAR(32) PRIMARY KEY,
    version INT NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE flight_calendar IS '航班日历（合成）：按跑道维护日历版本，日历变更使尚未开始的例行计划待重审';
COMMENT ON COLUMN flight_calendar.runway_id IS '跑道编号（合成数据）';
COMMENT ON COLUMN flight_calendar.version IS '日历版本：从1开始，每次成功变更加1';
COMMENT ON COLUMN flight_calendar.updated_at IS '最近变更时间（UTC）';

CREATE TABLE IF NOT EXISTS inspection_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    runway_id VARCHAR(32) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    inspection_minutes INT NOT NULL,
    flight_windows CLOB NOT NULL,
    calendar_version INT NOT NULL,
    conflict_evidence CLOB NOT NULL,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL,
    active_flag TINYINT,
    request_id VARCHAR(64) NOT NULL,
    started_at TIMESTAMP,
    completed_items CLOB,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 同一跑道同一窗口只允许一个非终态计划：active_flag=1 表示获准/待重审/进行中，终态置 NULL（唯一索引允许多个 NULL）
CREATE UNIQUE INDEX IF NOT EXISTS uk_inspection_plan_active ON inspection_plan (runway_id, window_start, window_end, active_flag);

COMMENT ON TABLE inspection_plan IS '例行跑道巡检计划：申请发布→开始→检查回报→完成；紧急关闭抢占时取消或中止';
COMMENT ON COLUMN inspection_plan.id IS '计划主键';
COMMENT ON COLUMN inspection_plan.runway_id IS '跑道编号（合成数据）';
COMMENT ON COLUMN inspection_plan.window_start IS '例行窗口起点（UTC，半开窗口，含起点）';
COMMENT ON COLUMN inspection_plan.window_end IS '例行窗口终点（UTC，半开窗口，不含终点）';
COMMENT ON COLUMN inspection_plan.inspection_minutes IS '预计巡检时长（分钟）：自窗口起点起须完整落在窗口内';
COMMENT ON COLUMN inspection_plan.flight_windows IS '合成航班占用窗（JSON数组，元素含start/end，UTC半开区间）';
COMMENT ON COLUMN inspection_plan.calendar_version IS '发布或重审时冻结的航班日历版本';
COMMENT ON COLUMN inspection_plan.conflict_evidence IS '发布时冻结的冲突校验证据（JSON）：巡检占用区间、航班占用窗与校验结论';
COMMENT ON COLUMN inspection_plan.status IS '状态：APPROVED获准/PENDING_REVIEW待重审/IN_PROGRESS进行中/COMPLETED已完成/CANCELLED已取消(未开始被抢占)/ABORTED已中止(已开始被抢占)';
COMMENT ON COLUMN inspection_plan.version IS '乐观锁版本：从1开始，每次成功变更加1';
COMMENT ON COLUMN inspection_plan.active_flag IS '非终态标记：1=获准/待重审/进行中，NULL=完成/取消/中止（配合唯一索引保证同跑道同窗唯一）';
COMMENT ON COLUMN inspection_plan.request_id IS '发布幂等请求标识';
COMMENT ON COLUMN inspection_plan.started_at IS '实际开始时间（UTC），未开始为NULL';
COMMENT ON COLUMN inspection_plan.completed_items IS '已完成检查项（JSON数组），中止时保留不丢失，未开始为NULL';
COMMENT ON COLUMN inspection_plan.created_at IS '记录创建时间（UTC）';
COMMENT ON COLUMN inspection_plan.updated_at IS '记录最近更新时间（UTC）';

CREATE TABLE IF NOT EXISTS plan_check_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    reported_at TIMESTAMP NOT NULL,
    completed_items CLOB NOT NULL,
    complete BOOLEAN NOT NULL,
    late BOOLEAN NOT NULL DEFAULT FALSE,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE plan_check_report IS '例行巡检检查回报：每次生效回报或被抢占后迟到登记各一行';
COMMENT ON COLUMN plan_check_report.id IS '检查回报主键';
COMMENT ON COLUMN plan_check_report.plan_id IS '所属例行计划ID';
COMMENT ON COLUMN plan_check_report.reported_at IS '回报时间（UTC）';
COMMENT ON COLUMN plan_check_report.completed_items IS '本次回报已完成检查项（JSON数组）';
COMMENT ON COLUMN plan_check_report.complete IS '是否完结回报：真=巡检正常完成，计划关闭（不触发跑道开放）';
COMMENT ON COLUMN plan_check_report.late IS '是否迟到登记：真=计划已被抢占（取消/中止），仅登记不开放跑道';
COMMENT ON COLUMN plan_check_report.request_id IS '回报幂等请求标识';
COMMENT ON COLUMN plan_check_report.created_at IS '记录创建时间（UTC）';
