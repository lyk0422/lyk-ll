-- 城镇燃气门站加臭与压力联动服务：H2(MODE=MySQL) 自动建表脚本

CREATE TABLE IF NOT EXISTS station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    pressure_low_limit_mpa DECIMAL(10,4) NOT NULL,
    pressure_high_limit_mpa DECIMAL(10,4) NOT NULL,
    pressure_target_mpa DECIMAL(10,4) NOT NULL,
    target_concentration_mg_m3 DECIMAL(10,3) NOT NULL,
    concentration_tolerance_pct DECIMAL(6,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN station.station_code IS '门站唯一编码';
COMMENT ON COLUMN station.pressure_low_limit_mpa IS '管网压力下限MPa，恰等限值判定合格';
COMMENT ON COLUMN station.pressure_high_limit_mpa IS '管网压力上限MPa，恰等限值判定合格';
COMMENT ON COLUMN station.pressure_target_mpa IS '调压回归目标压力MPa';
COMMENT ON COLUMN station.target_concentration_mg_m3 IS '末端加臭目标浓度mg/m3，默认20';
COMMENT ON COLUMN station.concentration_tolerance_pct IS '浓度允许偏差百分比，默认20%';

CREATE TABLE IF NOT EXISTS flow_meter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL,
    meter_code VARCHAR(64) NOT NULL,
    meter_name VARCHAR(128) NOT NULL
);
COMMENT ON COLUMN flow_meter.meter_code IS '合成流量计编码';

CREATE TABLE IF NOT EXISTS odorant_device (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL,
    device_code VARCHAR(64) NOT NULL,
    device_name VARCHAR(128) NOT NULL
);
COMMENT ON COLUMN odorant_device.device_code IS '合成加臭装置编码';

CREATE TABLE IF NOT EXISTS valve_state (
    station_id BIGINT PRIMARY KEY,
    injection_locked BOOLEAN NOT NULL DEFAULT FALSE,
    alarm_active BOOLEAN NOT NULL DEFAULT FALSE,
    breach_count INT NOT NULL DEFAULT 0,
    current_injection_rate_mg_m3 DECIMAL(10,3) NOT NULL DEFAULT 20,
    current_pressure_mpa DECIMAL(10,4),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN valve_state.injection_locked IS '加注阀锁定状态：TRUE锁定须双人复开';
COMMENT ON COLUMN valve_state.alarm_active IS '浓度连续越限告警状态';
COMMENT ON COLUMN valve_state.breach_count IS '当前连续越限检测次数，合格即归零，达2锁阀';
COMMENT ON COLUMN valve_state.current_injection_rate_mg_m3 IS '当前加注速率折算末端浓度mg/m3';
COMMENT ON COLUMN valve_state.current_pressure_mpa IS '最近一次联动后管网压力MPa，NULL未调节';

CREATE TABLE IF NOT EXISTS odorant_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    detected_at TIMESTAMP NOT NULL,
    gas_flow_m3 DECIMAL(14,3) NOT NULL,
    concentration_mg_m3 DECIMAL(10,3) NOT NULL,
    qualified BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN odorant_reading.detected_at IS '检测时刻（业务时间，Asia/Shanghai）';
COMMENT ON COLUMN odorant_reading.gas_flow_m3 IS '检测时段标况累计流量m3';
COMMENT ON COLUMN odorant_reading.concentration_mg_m3 IS '末端加臭剂实测浓度mg/m3';
COMMENT ON COLUMN odorant_reading.qualified IS '浓度是否在20mg/m3上下20%闭区间';

CREATE TABLE IF NOT EXISTS pressure_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    detected_at TIMESTAMP NOT NULL,
    pressure_mpa DECIMAL(10,4) NOT NULL,
    grade VARCHAR(16) NOT NULL,
    task_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN pressure_reading.pressure_mpa IS '实测管网压力MPa';
COMMENT ON COLUMN pressure_reading.grade IS '压力分级：LOW低于下限/HIGH超上限/NORMAL合格（含恰等限值）';
COMMENT ON COLUMN pressure_reading.task_id IS '触发的联动任务ID，合格无联动时为NULL';

CREATE TABLE IF NOT EXISTS lock_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    first_operator VARCHAR(64),
    second_operator VARCHAR(64),
    trigger_request_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP
);
COMMENT ON COLUMN lock_order.action IS 'LOCK锁阀/REOPEN复开，均须两名不同操作人';
COMMENT ON COLUMN lock_order.status IS 'PENDING待确认/CONFIRMED已生效';
COMMENT ON COLUMN lock_order.first_operator IS '第一名确认人工号，空表示系统触发待受理';
COMMENT ON COLUMN lock_order.second_operator IS '第二名确认人工号，须与第一人不同，单人操作不生效';
COMMENT ON COLUMN lock_order.trigger_request_id IS '第2次连续越限检测的requestId（锁阀单来源）';

CREATE TABLE IF NOT EXISTS linkage_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    detected_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    pressure_before_mpa DECIMAL(10,4),
    pressure_after_mpa DECIMAL(10,4),
    injection_rate_before_mg_m3 DECIMAL(10,3),
    injection_rate_after_mg_m3 DECIMAL(10,3),
    gas_flow_m3 DECIMAL(14,3),
    injection_dose_mg DECIMAL(16,3),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT uk_linkage_station_time UNIQUE (station_id, detected_at)
);
COMMENT ON COLUMN linkage_task.detected_at IS '检测时刻；(门站,时刻)唯一约束保证并发下达唯一受理';
COMMENT ON COLUMN linkage_task.status IS 'ACCEPTED已受理/COMPLETED已完成/NO_ACTION合格无需调节';
COMMENT ON COLUMN linkage_task.gas_flow_m3 IS '联动时刻时段流量m3，按流量比例核算加注量';
COMMENT ON COLUMN linkage_task.injection_dose_mg IS '本次联动加注量mg=流量m3×目标浓度mg/m3';

CREATE TABLE IF NOT EXISTS adjustment_issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    station_id BIGINT NOT NULL,
    issue_type VARCHAR(16) NOT NULL,
    before_value DECIMAL(12,4),
    after_value DECIMAL(12,4),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN adjustment_issue.issue_type IS 'PRESSURE调压/INJECTION加注，两类指令须同一事务下发';

CREATE TABLE IF NOT EXISTS daily_close (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL,
    biz_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    total_flow_m3 DECIMAL(16,3) NOT NULL,
    expected_odorant_mg DECIMAL(18,3) NOT NULL,
    reported_odorant_mg DECIMAL(18,3),
    deviation_pct DECIMAL(8,4),
    seal_request_id VARCHAR(64),
    sealed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_daily_close UNIQUE (station_id, biz_date)
);
COMMENT ON COLUMN daily_close.biz_date IS '业务日期（Asia/Shanghai）';
COMMENT ON COLUMN daily_close.status IS 'SEALED封存终态/MANUAL偏差超3%转人工';
COMMENT ON COLUMN daily_close.total_flow_m3 IS '当日非迟到检测流量合计m3';
COMMENT ON COLUMN daily_close.expected_odorant_mg IS '理论消耗mg=累计流量×20mg/m3';
COMMENT ON COLUMN daily_close.reported_odorant_mg IS '实际上报消耗mg';
COMMENT ON COLUMN daily_close.deviation_pct IS '勾稽偏差百分比，恰3%合格，超过转人工';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    detected_at TIMESTAMP NOT NULL,
    payload_json CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN late_event.source_type IS 'ODORANT浓度检测/PRESSURE压力检测';
COMMENT ON COLUMN late_event.payload_json IS '迟到原始报文，封存后不参与任何联动与统计';

CREATE TABLE IF NOT EXISTS idempotent_record (
    request_id VARCHAR(64) PRIMARY KEY,
    station_id BIGINT,
    api_type VARCHAR(32) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    http_status INT NOT NULL,
    result_json CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN idempotent_record.fingerprint IS 'SHA-256覆盖requestId与全部业务字段';
COMMENT ON COLUMN idempotent_record.http_status IS '首次受理时返回的HTTP状态码';
