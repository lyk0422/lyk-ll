-- 城市内涝泵站联动预降服务：H2（MODE=MySQL）建表脚本
-- 所有水位单位均为米，开度为百分比，时刻为 Asia/Shanghai 本地时间。

-- 合成气象预警级别表：橙色及以上触发预降
CREATE TABLE IF NOT EXISTS alert_level (
    code VARCHAR(16) PRIMARY KEY COMMENT '预警级别代码 BLUE/YELLOW/ORANGE/RED',
    name VARCHAR(32) NOT NULL COMMENT '预警级别中文名',
    rank_no INT NOT NULL COMMENT '级别排序，数值越大越严重',
    trigger_pumpdown TINYINT NOT NULL COMMENT '是否触发预降：1是 0否'
);

-- 流域分区表（合成数据）
CREATE TABLE IF NOT EXISTS basin_zone (
    code VARCHAR(32) PRIMARY KEY COMMENT '流域分区代码',
    name VARCHAR(64) NOT NULL COMMENT '流域分区名称'
);

-- 泵站表（合成数据）
CREATE TABLE IF NOT EXISTS pump_station (
    code VARCHAR(32) PRIMARY KEY COMMENT '泵站代码',
    name VARCHAR(64) NOT NULL COMMENT '泵站名称',
    zone_code VARCHAR(32) NOT NULL COMMENT '所属流域分区代码',
    target_level DECIMAL(8,3) NOT NULL COMMENT '预降目标水位（米），恰达此值停泵',
    safe_low_level DECIMAL(8,3) NOT NULL COMMENT '安全下限水位（米），严格低于立即停泵，恰等合法',
    gate_start_opening DECIMAL(5,2) NOT NULL COMMENT '启泵联动闸门开度（百分比）'
);

-- 幂等请求台账：指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求ID',
    biz_type VARCHAR(24) NOT NULL COMMENT '业务类型 WARNING/PUMP_TASK/ARCHIVE',
    fingerprint VARCHAR(64) NOT NULL COMMENT 'requestId+全部业务字段的SHA-256指纹',
    status_code INT NULL COMMENT '首次受理返回的HTTP状态码；占位插入时为NULL，受理后回写',
    response_body CLOB COMMENT '首次受理返回体JSON',
    created_at TIMESTAMP NOT NULL COMMENT '首次受理时刻'
);

-- 预降事件：预警决策的产物
CREATE TABLE IF NOT EXISTS pumpdown_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '预降事件ID',
    request_id VARCHAR(64) NOT NULL UNIQUE COMMENT '触发预警的requestId',
    zone_code VARCHAR(32) NOT NULL COMMENT '流域分区代码',
    alert_level VARCHAR(16) NOT NULL COMMENT '触发时预警级别代码',
    status VARCHAR(16) NOT NULL COMMENT '状态 ACTIVE预降中/ARCHIVED已归档终态',
    target_level DECIMAL(8,3) NOT NULL COMMENT '分区预降目标水位（米）',
    started_at TIMESTAMP NOT NULL COMMENT '预降开始时刻',
    archived_at TIMESTAMP NULL COMMENT '归档时刻，NULL表示未归档'
);

-- 泵站启停序列：同一分区、同一时刻只允许一套序列（多站同时刻任务归入同套序列）
CREATE TABLE IF NOT EXISTS pump_sequence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '启停序列ID',
    pumpdown_id BIGINT NOT NULL COMMENT '所属预降事件ID',
    zone_code VARCHAR(32) NOT NULL COMMENT '流域分区代码',
    task_time TIMESTAMP NOT NULL COMMENT '序列联动时刻',
    created_at TIMESTAMP NOT NULL COMMENT '序列创建时刻',
    CONSTRAINT uk_zone_time_sequence UNIQUE (pumpdown_id, zone_code, task_time)
);

-- 单站启停任务：同一泵站同时刻只允许一个任务
CREATE TABLE IF NOT EXISTS pump_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '启停任务ID',
    sequence_id BIGINT NOT NULL COMMENT '所属启停序列ID',
    pumpdown_id BIGINT NOT NULL COMMENT '所属预降事件ID',
    station_code VARCHAR(32) NOT NULL COMMENT '泵站代码',
    action VARCHAR(8) NOT NULL COMMENT '动作 START启泵/STOP停泵',
    task_time TIMESTAMP NOT NULL COMMENT '启停时刻',
    reason VARCHAR(128) NOT NULL COMMENT '启停原因',
    CONSTRAINT uk_station_time UNIQUE (station_code, task_time)
);

-- 闸门开度台账：与启泵任务同事务提交
CREATE TABLE IF NOT EXISTS gate_opening_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '闸门开度记录ID',
    pump_task_id BIGINT NOT NULL COMMENT '关联的启泵任务ID',
    station_code VARCHAR(32) NOT NULL COMMENT '泵站代码',
    opening DECIMAL(5,2) NOT NULL COMMENT '闸门开度（百分比，0-100）',
    updated_at TIMESTAMP NOT NULL COMMENT '开度到位时刻，与启泵同时刻',
    CONSTRAINT chk_gate_opening CHECK (opening >= 0 AND opening <= 100)
);

-- 液位数据：按站点+采集时刻去重
CREATE TABLE IF NOT EXISTS level_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '液位数据ID',
    station_code VARCHAR(32) NOT NULL COMMENT '泵站代码',
    pumpdown_id BIGINT NULL COMMENT '所属预降事件ID；归档后迟到数据不入此表',
    reading_time TIMESTAMP NOT NULL COMMENT '液位采集时刻',
    level DECIMAL(8,3) NOT NULL COMMENT '河道水位（米）',
    received_at TIMESTAMP NOT NULL COMMENT '服务接收时刻',
    CONSTRAINT uk_station_reading UNIQUE (station_code, reading_time)
);

-- 失联派单台账：恰满30分钟无数据判失联
CREATE TABLE IF NOT EXISTS miss_contact_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '派单ID',
    station_code VARCHAR(32) NOT NULL COMMENT '泵站代码',
    pumpdown_id BIGINT NOT NULL COMMENT '所属预降事件ID',
    last_reading_time TIMESTAMP NOT NULL COMMENT '判定依据的最后一条液位时刻（无数据则为预降开始时刻）',
    triggered_at TIMESTAMP NOT NULL COMMENT '失联判定时刻=末次数据时刻恰满30分钟',
    status VARCHAR(16) NOT NULL COMMENT '工单状态 OPEN派单中/CLEARED已销单',
    CONSTRAINT uk_station_pumpdown UNIQUE (station_code, pumpdown_id)
);

-- 归档后迟到液位事件：只记录，不参与曲线
CREATE TABLE IF NOT EXISTS late_reading_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '迟到事件ID',
    station_code VARCHAR(32) NOT NULL COMMENT '泵站代码',
    reading_time TIMESTAMP NOT NULL COMMENT '液位采集时刻',
    level DECIMAL(8,3) NOT NULL COMMENT '河道水位（米）',
    received_at TIMESTAMP NOT NULL COMMENT '服务接收时刻（晚于归档时刻）',
    CONSTRAINT uk_late_station_reading UNIQUE (station_code, reading_time)
);

-- 复盘归档：终态，一个预降事件仅一次
CREATE TABLE IF NOT EXISTS review_archive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '归档记录ID',
    pumpdown_id BIGINT NOT NULL UNIQUE COMMENT '被归档预降事件ID',
    request_id VARCHAR(64) NOT NULL UNIQUE COMMENT '归档请求ID',
    summary CLOB NOT NULL COMMENT '复盘摘要（曲线、启停序列、失联台账复算结果）',
    archived_at TIMESTAMP NOT NULL COMMENT '归档时刻'
);
