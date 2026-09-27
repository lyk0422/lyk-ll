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
COMMENT ON COLUMN idempotency_record.scope IS '幂等作用域：report/assessment/reopen/calendar/plan-publish/plan-start/plan-check/plan-complete/plan-rereview';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等请求标识';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求指纹：requestId与全部业务字段的结构化长度前缀编码SHA-256';
COMMENT ON COLUMN idempotency_record.response_body IS '首次成功响应报文（JSON），用于同键同参重放';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时间（UTC）';

-- 航班日历版本指针：全局单行（id 恒为1），版本从1开始，每次成功发布加1；无行表示日历尚未初始化
CREATE TABLE IF NOT EXISTS flight_calendar (
    id TINYINT PRIMARY KEY,
    version INT NOT NULL,
    published_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_flight_calendar_singleton CHECK (id = 1)
);

COMMENT ON TABLE flight_calendar IS '合成航班日历当前版本指针（单行）：例行计划发布/重审同样冻结所依据的日历版本';
COMMENT ON COLUMN flight_calendar.id IS '固定为1的单行主键';
COMMENT ON COLUMN flight_calendar.version IS '日历版本号：从1开始，每次成功发布加1';
COMMENT ON COLUMN flight_calendar.published_at IS '当前版本发布请求携带的精确时刻（UTC）';
COMMENT ON COLUMN flight_calendar.updated_at IS '记录更新时间（UTC）';

-- 每个日历版本对应一整组航班占用窗快照（半开区间 [start,end)），发布即冻结不可变
CREATE TABLE IF NOT EXISTS flight_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    calendar_version INT NOT NULL,
    runway_id VARCHAR(32) NOT NULL,
    flight_no VARCHAR(64) NOT NULL,
    occupy_start TIMESTAMP NOT NULL,
    occupy_end TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_flight_window_version_runway ON flight_window (calendar_version, runway_id);

COMMENT ON TABLE flight_window IS '合成航班占用窗快照：按日历版本冻结，UTC半开区间，正长度相交拒绝、端点相接合法';
COMMENT ON COLUMN flight_window.id IS '占用窗主键';
COMMENT ON COLUMN flight_window.calendar_version IS '所属日历版本（快照不可变）';
COMMENT ON COLUMN flight_window.runway_id IS '跑道编号（合成数据）';
COMMENT ON COLUMN flight_window.flight_no IS '合成航班号';
COMMENT ON COLUMN flight_window.occupy_start IS '占用开始时刻（UTC，含）';
COMMENT ON COLUMN flight_window.occupy_end IS '占用结束时刻（UTC，不含），须严格晚于开始时刻';
COMMENT ON COLUMN flight_window.created_at IS '记录创建时间（UTC）';

-- 例行跑道巡检计划：发布冻结→（日历变更置待重审）→开始→完成；紧急关闭取消未开始计划、中止已开始计划
CREATE TABLE IF NOT EXISTS inspection_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    runway_id VARCHAR(32) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    planned_start_at TIMESTAMP NOT NULL,
    duration_minutes INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL,
    frozen_calendar_version INT,
    conflict_evidence CLOB,
    live_flag TINYINT,
    aborted_by_event_id BIGINT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 同跑道同窗仅允许一个获准（FROZEN）或进行中（RUNNING）计划；终态/待重审置NULL（唯一索引允许多个NULL）
CREATE UNIQUE INDEX IF NOT EXISTS uk_inspection_plan_window
    ON inspection_plan (runway_id, window_start, window_end, live_flag);

COMMENT ON TABLE inspection_plan IS '例行巡检计划：FROZEN已冻结/REVIEW_PENDING日历变更待重审/RUNNING巡检中/COMPLETED正常完成/CANCELLED紧急取消/ABORTED紧急中止（后三者终态）';
COMMENT ON COLUMN inspection_plan.id IS '计划主键';
COMMENT ON COLUMN inspection_plan.runway_id IS '跑道编号（合成数据）';
COMMENT ON COLUMN inspection_plan.window_start IS 'UTC半开窗口起点（含）';
COMMENT ON COLUMN inspection_plan.window_end IS 'UTC半开窗口终点（不含），巡检区间端点相接该时刻合法';
COMMENT ON COLUMN inspection_plan.planned_start_at IS '申请时计划巡检开始时刻（UTC），与预计时长共同做发布前冲突校验';
COMMENT ON COLUMN inspection_plan.duration_minutes IS '预计巡检分钟数（正整数）：巡检区间须完整落在窗口内';
COMMENT ON COLUMN inspection_plan.status IS '状态：FROZEN/REVIEW_PENDING/RUNNING/COMPLETED/CANCELLED/ABORTED';
COMMENT ON COLUMN inspection_plan.version IS '计划聚合版本：从1开始，每次成功状态变更加1；迟到登记不变更状态故不加1';
COMMENT ON COLUMN inspection_plan.frozen_calendar_version IS '发布/重审冻结所依据的航班日历版本；开始时须等于当前日历版本';
COMMENT ON COLUMN inspection_plan.conflict_evidence IS '结构化冲突证据（JSON）：记录校验时的日历版本、窗口、巡检区间与全部受检航班窗';
COMMENT ON COLUMN inspection_plan.live_flag IS '获准占位：1=FROZEN/RUNNING，NULL=待重审/取消/中止/完成（配合唯一索引保证同跑道同窗唯一获准计划）';
COMMENT ON COLUMN inspection_plan.aborted_by_event_id IS '中止该计划的紧急关闭事件ID；仅ABORTED非NULL';
COMMENT ON COLUMN inspection_plan.created_at IS '记录创建时间（UTC）';
COMMENT ON COLUMN inspection_plan.updated_at IS '记录最近更新时间（UTC）';

-- 巡检检查项回报：进行中逐项登记；紧急中止后已保存检查项原样保留
CREATE TABLE IF NOT EXISTS plan_check_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    result VARCHAR(16) NOT NULL,
    remark VARCHAR(255),
    observed_at TIMESTAMP NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE plan_check_item IS '例行巡检检查项回报：按观测时刻保留，计划中止时已完成项不删除、不复活';
COMMENT ON COLUMN plan_check_item.id IS '检查项主键';
COMMENT ON COLUMN plan_check_item.plan_id IS '所属例行计划ID';
COMMENT ON COLUMN plan_check_item.item_code IS '检查项编码（合成，如 LIGHTING/FOD-SWEEP）';
COMMENT ON COLUMN plan_check_item.result IS '检查结果：PASS合格/FAIL不合格';
COMMENT ON COLUMN plan_check_item.remark IS '备注（可空）：合法文本可含任意分隔符，按结构化字段原样保存';
COMMENT ON COLUMN plan_check_item.observed_at IS '现场观测精确时刻（UTC）';
COMMENT ON COLUMN plan_check_item.request_id IS '检查回报幂等请求标识';
COMMENT ON COLUMN plan_check_item.created_at IS '记录创建时间（UTC）';

-- 例行完成回报：正常完成关闭计划；紧急中止后的迟到完成仅登记，不开放跑道
CREATE TABLE IF NOT EXISTS plan_completion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    late BOOLEAN NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE plan_completion IS '例行完成回报：RUNNING上为正常完成，ABORTED后仅登记迟到且不改变计划状态、不触发开放';
COMMENT ON COLUMN plan_completion.id IS '完成回报主键';
COMMENT ON COLUMN plan_completion.plan_id IS '所属例行计划ID';
COMMENT ON COLUMN plan_completion.completed_at IS '回报的完成精确时刻（UTC）';
COMMENT ON COLUMN plan_completion.late IS '是否迟到登记：TRUE=紧急中止后收到的完成回报，仅登记，不开放跑道';
COMMENT ON COLUMN plan_completion.request_id IS '完成回报幂等请求标识';
COMMENT ON COLUMN plan_completion.created_at IS '记录创建时间（UTC）';

-- 跑道协调锁：按跑道串行化“例行开始/完成”与“紧急接报”，跨跑道使用不同行互不阻塞
CREATE TABLE IF NOT EXISTS runway_coordination_lock (
    runway_id VARCHAR(32) PRIMARY KEY
);

COMMENT ON TABLE runway_coordination_lock IS '跑道级排它锁：SELECT ... FOR UPDATE串行化同跑道的例行开始与紧急接报，跨跑道互不阻塞';
COMMENT ON COLUMN runway_coordination_lock.runway_id IS '跑道编号（合成数据）';

-- 跑道例行巡检占用：同一跑道至多一个进行中例行巡检（主键保证），紧急中止/正常完成时删除
CREATE TABLE IF NOT EXISTS runway_inspection_occupancy (
    runway_id VARCHAR(32) PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    occupied_since TIMESTAMP NOT NULL
);

COMMENT ON TABLE runway_inspection_occupancy IS '跑道例行巡检占用：主键保证同跑道唯一进行中例行巡检，与紧急事件互斥（由协调锁裁决竞争）';
COMMENT ON COLUMN runway_inspection_occupancy.runway_id IS '跑道编号（合成数据）';
COMMENT ON COLUMN runway_inspection_occupancy.plan_id IS '占用跑道的例行计划ID';
COMMENT ON COLUMN runway_inspection_occupancy.occupied_since IS '占用开始时刻（UTC，即巡检开始时刻）';
