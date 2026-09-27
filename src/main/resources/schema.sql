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
COMMENT ON COLUMN idempotency_record.scope IS '幂等作用域：report/assessment/reopen/calendar/plan_publish/plan_start/plan_check/plan_review/emergency/emergency_end';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等请求标识';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求指纹：requestId与全部业务字段的SHA-256';
COMMENT ON COLUMN idempotency_record.response_body IS '首次成功响应报文（JSON），用于同键同参重放';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时间（UTC）';

-- ===================== 例行巡检窗口编排与紧急关闭抢占（新增） =====================

CREATE TABLE IF NOT EXISTS flight_calendar_version (
    version INT PRIMARY KEY,
    published_at TIMESTAMP NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE flight_calendar_version IS '合成航班日历版本：版本号从1开始递增，发布新版本会使尚未开始的例行计划待重审';
COMMENT ON COLUMN flight_calendar_version.version IS '日历版本号，从1开始，每次发布加1';
COMMENT ON COLUMN flight_calendar_version.published_at IS '版本发布时间（UTC）';
COMMENT ON COLUMN flight_calendar_version.request_id IS '发布幂等请求标识';
COMMENT ON COLUMN flight_calendar_version.created_at IS '记录创建时间（UTC）';

CREATE TABLE IF NOT EXISTS flight_occupancy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version INT NOT NULL,
    runway_id VARCHAR(32) NOT NULL,
    flight_no VARCHAR(64) NOT NULL,
    start_at TIMESTAMP NOT NULL,
    end_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_flight_occupancy_version_runway
    ON flight_occupancy (version, runway_id);

COMMENT ON TABLE flight_occupancy IS '合成航班占用窗：半开区间[start_at,end_at)，UTC；与巡检窗正长度相交拒绝，端点相接合法';
COMMENT ON COLUMN flight_occupancy.id IS '占用窗主键';
COMMENT ON COLUMN flight_occupancy.version IS '所属航班日历版本号';
COMMENT ON COLUMN flight_occupancy.runway_id IS '跑道编号（合成数据）';
COMMENT ON COLUMN flight_occupancy.flight_no IS '合成航班号（合法文本，允许包含任意分隔符）';
COMMENT ON COLUMN flight_occupancy.start_at IS '占用开始时刻（UTC，含）';
COMMENT ON COLUMN flight_occupancy.end_at IS '占用结束时刻（UTC，不含）';

CREATE TABLE IF NOT EXISTS routine_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    runway_id VARCHAR(32) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    duration_minutes INT NOT NULL,
    frozen_calendar_version INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_flag TINYINT,
    conflict_evidence CLOB NOT NULL,
    version INT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    preempted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 同跑道同窗只能一个存活（获准/进行中/待重审）计划：终态置 active_flag=NULL（唯一索引允许多个 NULL）
CREATE UNIQUE INDEX IF NOT EXISTS uk_routine_plan_live
    ON routine_plan (runway_id, window_start, window_end, active_flag);

COMMENT ON TABLE routine_plan IS '例行巡检计划：发布冻结日历版本与冲突证据；状态 APPROVED→IN_PROGRESS→COMPLETED，或被紧急关闭 CANCELLED/ABORTED，日历变更后 NEEDS_REVIEW';
COMMENT ON COLUMN routine_plan.id IS '计划主键';
COMMENT ON COLUMN routine_plan.runway_id IS '跑道编号（合成数据）';
COMMENT ON COLUMN routine_plan.window_start IS '巡检窗口开始时刻（UTC，半开区间含端点）';
COMMENT ON COLUMN routine_plan.window_end IS '巡检窗口结束时刻（UTC，半开区间不含端点）';
COMMENT ON COLUMN routine_plan.duration_minutes IS '预计巡检时长（分钟，正整数）：巡检区间须完整落在窗口内';
COMMENT ON COLUMN routine_plan.frozen_calendar_version IS '发布/重审时冻结的航班日历版本号';
COMMENT ON COLUMN routine_plan.status IS '状态：APPROVED获准未开始/IN_PROGRESS巡检中/COMPLETED正常完成/CANCELLED紧急关闭时未开始被取消/ABORTED紧急关闭时已开始被中止/NEEDS_REVIEW日历变更待重审';
COMMENT ON COLUMN routine_plan.active_flag IS '存活标记：1=APPROVED/IN_PROGRESS/NEEDS_REVIEW，NULL=终态（配合唯一索引保证同跑道同窗唯一存活计划）';
COMMENT ON COLUMN routine_plan.conflict_evidence IS '冻结时结构化冲突证据（JSON）：日历版本、巡检区间、全部受检航班窗及相交长度（分钟）';
COMMENT ON COLUMN routine_plan.version IS '乐观版本号，从1开始，每次成功变更加1，expectedVersion 不匹配返回409';
COMMENT ON COLUMN routine_plan.request_id IS '发布幂等请求标识';
COMMENT ON COLUMN routine_plan.started_at IS '实际巡检开始时刻（UTC），未开始为NULL';
COMMENT ON COLUMN routine_plan.completed_at IS '正常完成时刻（UTC），未完成为NULL';
COMMENT ON COLUMN routine_plan.preempted_at IS '紧急关闭抢占时刻（UTC），未被抢占为NULL';
COMMENT ON COLUMN routine_plan.created_at IS '记录创建时间（UTC）';
COMMENT ON COLUMN routine_plan.updated_at IS '记录最近更新时间（UTC）';

CREATE TABLE IF NOT EXISTS routine_check_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    seq_no INT NOT NULL,
    completed_at TIMESTAMP,
    recorded_late BOOLEAN NOT NULL DEFAULT FALSE,
    request_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_routine_check_item UNIQUE (plan_id, item_code)
);

COMMENT ON TABLE routine_check_item IS '例行巡检检查项：发布时按集合登记；计划中止后的新回报仅登记迟到，不改变计划状态、不开放跑道';
COMMENT ON COLUMN routine_check_item.id IS '检查项主键';
COMMENT ON COLUMN routine_check_item.plan_id IS '所属例行计划ID';
COMMENT ON COLUMN routine_check_item.item_code IS '检查项编码（合法文本，允许包含分隔符），计划内唯一';
COMMENT ON COLUMN routine_check_item.seq_no IS '检查项序号：发布集合按编码排序后的稳定顺序';
COMMENT ON COLUMN routine_check_item.completed_at IS '检查项完成时刻（UTC）：中止前为正常完成，中止后为迟到登记，未回报为NULL';
COMMENT ON COLUMN routine_check_item.recorded_late IS '是否计划中止后的迟到回报：真=仅登记，不改变计划状态，不触发开放';
COMMENT ON COLUMN routine_check_item.request_id IS '最近一次回报的幂等请求标识';
COMMENT ON COLUMN routine_check_item.created_at IS '记录创建时间（UTC）';
COMMENT ON COLUMN routine_check_item.updated_at IS '记录最近更新时间（UTC）';

CREATE TABLE IF NOT EXISTS runway_emergency (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    runway_id VARCHAR(32) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL,
    active_flag TINYINT,
    started_at TIMESTAMP NOT NULL,
    ended_at TIMESTAMP,
    version INT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 同一跑道同时只允许一个进行中紧急关闭事件
CREATE UNIQUE INDEX IF NOT EXISTS uk_runway_emergency_active ON runway_emergency (runway_id, active_flag);

COMMENT ON TABLE runway_emergency IS '跑道紧急关闭事件：接报即在同事务抢占该跑道例行计划，结束后可另建新计划，不复活被抢占项';
COMMENT ON COLUMN runway_emergency.id IS '紧急事件主键';
COMMENT ON COLUMN runway_emergency.runway_id IS '跑道编号（合成数据）';
COMMENT ON COLUMN runway_emergency.reason IS '紧急关闭原因（合成文本，允许包含分隔符）';
COMMENT ON COLUMN runway_emergency.status IS '状态：ACTIVE进行中/ENDED已结束';
COMMENT ON COLUMN runway_emergency.active_flag IS '进行中标记：1=ACTIVE，NULL=ENDED（配合唯一索引保证同跑道唯一进行中紧急事件）';
COMMENT ON COLUMN runway_emergency.started_at IS '紧急关闭生效时刻（UTC），接报即关闭';
COMMENT ON COLUMN runway_emergency.ended_at IS '紧急事件结束时刻（UTC），未结束为NULL';
COMMENT ON COLUMN runway_emergency.version IS '乐观版本号，从1开始，结束成功变更加1';
COMMENT ON COLUMN runway_emergency.request_id IS '接报幂等请求标识';
COMMENT ON COLUMN runway_emergency.created_at IS '记录创建时间（UTC）';
COMMENT ON COLUMN runway_emergency.updated_at IS '记录最近更新时间（UTC）';

-- 跑道级互斥：同跑道的计划发布/开始/完成/重审与紧急接报/结束串行化，跨跑道行锁互不阻塞
CREATE TABLE IF NOT EXISTS runway_mutex (
    runway_id VARCHAR(32) NOT NULL PRIMARY KEY
);

COMMENT ON TABLE runway_mutex IS '跑道级事务互斥锁表：事务内先确保行存在再 SELECT ... FOR UPDATE，保证批准/开始与紧急接报得到唯一占用状态';
COMMENT ON COLUMN runway_mutex.runway_id IS '跑道编号（合成数据）';
