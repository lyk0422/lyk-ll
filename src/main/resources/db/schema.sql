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

-- 启停序列生命周期：强排切换原子启用新序列、终结旧序列
ALTER TABLE pump_sequence ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE pump_sequence ADD COLUMN IF NOT EXISTS terminated_at TIMESTAMP NULL;

-- 分区潮汐控制配置：未配置的分区保持原行为
CREATE TABLE IF NOT EXISTS tide_zone_config (
    zone_code VARCHAR(32) PRIMARY KEY COMMENT '流域分区代码',
    backwater_threshold DECIMAL(8,3) NOT NULL COMMENT '潮位顶托门槛（米），非负；恰达进入强排',
    current_mode VARCHAR(16) NOT NULL COMMENT '当前工况 NORMAL常规/FORCED强排',
    version INT NOT NULL COMMENT '工况版本，从1开始，每次成功变更加1',
    updated_at TIMESTAMP NOT NULL COMMENT '最近变更时刻（Asia/Shanghai）',
    CONSTRAINT chk_threshold_nonneg CHECK (backwater_threshold >= 0)
);

-- 潮位数据：按分区+UTC观测时刻唯一，潮位非负
CREATE TABLE IF NOT EXISTS tide_level_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '潮位数据ID',
    zone_code VARCHAR(32) NOT NULL COMMENT '流域分区代码',
    observed_at TIMESTAMP NOT NULL COMMENT '潮位观测时刻（UTC）',
    tide_level DECIMAL(8,3) NOT NULL COMMENT '潮位（米），非负',
    received_at TIMESTAMP NOT NULL COMMENT '服务接收时刻（Asia/Shanghai）',
    CONSTRAINT uk_zone_tide_time UNIQUE (zone_code, observed_at),
    CONSTRAINT chk_tide_level_nonneg CHECK (tide_level >= 0)
);

-- 迟到/归档后潮位证据：只留证据，不让当前工况倒退、不重开已归档事件
CREATE TABLE IF NOT EXISTS late_tide_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '迟到潮位事件ID',
    zone_code VARCHAR(32) NOT NULL COMMENT '流域分区代码',
    observed_at TIMESTAMP NOT NULL COMMENT '潮位观测时刻（UTC）',
    tide_level DECIMAL(8,3) NOT NULL COMMENT '潮位（米），非负',
    received_at TIMESTAMP NOT NULL COMMENT '服务接收时刻（Asia/Shanghai）',
    reason VARCHAR(24) NOT NULL COMMENT '迟到原因 OUT_OF_ORDER乱序/ARCHIVED已归档',
    CONSTRAINT uk_late_zone_tide_time UNIQUE (zone_code, observed_at)
);

-- 强排切换候选序列：固定触发潮位版本与全部相关站闸门目标，确认前不产生任何启停
CREATE TABLE IF NOT EXISTS tide_switch_candidate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '候选序列ID',
    request_id VARCHAR(64) NOT NULL UNIQUE COMMENT '生成候选的潮位上送requestId',
    zone_code VARCHAR(32) NOT NULL COMMENT '流域分区代码',
    target_mode VARCHAR(16) NOT NULL COMMENT '目标工况 FORCED进入强排/NORMAL退出强排',
    tide_reading_id BIGINT NOT NULL COMMENT '固定的触发潮位数据ID',
    tide_level DECIMAL(8,3) NOT NULL COMMENT '固定的触发潮位（米）',
    tide_observed_at TIMESTAMP NOT NULL COMMENT '固定的触发潮位观测时刻（UTC）',
    threshold_snapshot DECIMAL(8,3) NOT NULL COMMENT '生成时顶托门槛快照（米）',
    base_version INT NOT NULL COMMENT '生成时工况版本',
    status VARCHAR(16) NOT NULL COMMENT '候选状态 PENDING待确认/ACTIVATED已启用/SUPERSEDED已被取代',
    created_at TIMESTAMP NOT NULL COMMENT '候选生成时刻（Asia/Shanghai）',
    activated_at TIMESTAMP NULL COMMENT '启用时刻，NULL表示未启用'
);

-- 候选序列的站闸门目标：确认时回执须与此快照逐一吻合
CREATE TABLE IF NOT EXISTS tide_switch_candidate_station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '候选站目标ID',
    candidate_id BIGINT NOT NULL COMMENT '所属候选序列ID',
    station_code VARCHAR(32) NOT NULL COMMENT '泵站代码',
    target_opening DECIMAL(5,2) NOT NULL COMMENT '固定闸门目标开度（百分比，0-100）',
    CONSTRAINT uk_candidate_station UNIQUE (candidate_id, station_code),
    CONSTRAINT chk_target_opening CHECK (target_opening >= 0 AND target_opening <= 100)
);

-- 工况轨迹：每次成功切换追加一条，版本单调递增
CREATE TABLE IF NOT EXISTS tide_mode_trail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '轨迹ID',
    zone_code VARCHAR(32) NOT NULL COMMENT '流域分区代码',
    from_mode VARCHAR(16) NOT NULL COMMENT '切换前工况 NORMAL/FORCED',
    to_mode VARCHAR(16) NOT NULL COMMENT '切换后工况 NORMAL/FORCED',
    version INT NOT NULL COMMENT '切换后的工况版本',
    candidate_id BIGINT NOT NULL COMMENT '被启用的候选序列ID',
    request_id VARCHAR(64) NOT NULL COMMENT '候选确认requestId',
    switched_at TIMESTAMP NOT NULL COMMENT '切换生效时刻（Asia/Shanghai）'
);
