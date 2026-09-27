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

-- ============ 潮汐顶托工况切换与强排联锁（合成，仅JVM内H2） ============

-- 分区潮汐控制配置：只有显式配置的分区才启用潮汐顶托工况；未配置分区保持原预降行为
CREATE TABLE IF NOT EXISTS tide_control_config (
    zone_code VARCHAR(32) PRIMARY KEY COMMENT '流域分区代码',
    threshold_level DECIMAL(8,3) NOT NULL COMMENT '顶托门槛潮位（米）；潮位大于等于此值进入强排，恰等进入',
    backoff DECIMAL(5,3) NOT NULL COMMENT '强排退出回落差（米）；潮位严格低于门槛-回落差才退出，恰在回落线保持强排',
    regime_version INT NOT NULL COMMENT '工况配置版本，从1开始，每次成功修改加1',
    updated_at TIMESTAMP NOT NULL COMMENT '配置最近修改时刻'
);

-- 潮位数据：按分区+UTC时刻唯一；自增ID即固定潮位版本
CREATE TABLE IF NOT EXISTS tide_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '潮位版本ID（自增，候选序列固定引用此版本）',
    zone_code VARCHAR(32) NOT NULL COMMENT '流域分区代码',
    tide_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '潮位采集UTC时刻（带时区），按此时刻唯一',
    tide_level DECIMAL(8,3) NOT NULL COMMENT '潮位（米，非负）',
    received_at TIMESTAMP NOT NULL COMMENT '服务接收时刻（Asia/Shanghai）',
    CONSTRAINT chk_tide_level_nonneg CHECK (tide_level >= 0),
    CONSTRAINT uk_zone_tide_time UNIQUE (zone_code, tide_time)
);

-- 迟到的较旧潮位证据：只记录不让当前工况倒退，不参与工况判定
CREATE TABLE IF NOT EXISTS late_tide_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '迟到潮位证据ID',
    zone_code VARCHAR(32) NOT NULL COMMENT '流域分区代码',
    tide_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '迟到潮位的UTC采集时刻',
    tide_level DECIMAL(8,3) NOT NULL COMMENT '潮位（米，非负）',
    received_at TIMESTAMP NOT NULL COMMENT '服务接收时刻（晚于当前最新潮位）',
    CONSTRAINT uk_late_zone_tide_time UNIQUE (zone_code, tide_time)
);

-- 强排候选/生效序列：候选生成时即固定潮位版本与各站闸门目标；确认时原子启用
-- 状态机：CANDIDATE 候选 -> ACTIVE 进入强排生效 -> SUPERSEDED 被新序列终结（终态，不可重开）；
--         退出强排的候选确认后直接置 COMPLETED（终态，分区恢复常规，无在强排序列）
CREATE TABLE IF NOT EXISTS strong_drain_sequence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '强排序列ID',
    zone_code VARCHAR(32) NOT NULL COMMENT '流域分区代码',
    tide_version BIGINT NOT NULL COMMENT '候选所固定的潮位版本（tide_reading.id）',
    tide_level DECIMAL(8,3) NOT NULL COMMENT '候选依据的潮位（米）',
    direction VARCHAR(8) NOT NULL COMMENT '切换方向 ENTER进入强排/EXIT退出强排',
    regime_version INT NOT NULL COMMENT '生成候选时的分区工况配置版本',
    expected_active_sequence_id BIGINT NULL COMMENT '生成候选时在强排序列ID（无则NULL）；确认时校验未被他人切换',
    state VARCHAR(16) NOT NULL COMMENT '状态 CANDIDATE候选/ACTIVE进入生效/SUPERSEDED已被新序列终结/COMPLETED退出完成终态',
    task_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '序列联动UTC时刻（固定为潮位时刻）',
    created_at TIMESTAMP NOT NULL COMMENT '候选生成时刻',
    CONSTRAINT chk_sds_direction CHECK (direction IN ('ENTER', 'EXIT')),
    CONSTRAINT chk_sds_state CHECK (state IN ('CANDIDATE', 'ACTIVE', 'SUPERSEDED', 'COMPLETED'))
);

-- 序列各站闸门目标：候选生成时一次性固化，确认后不再改变
CREATE TABLE IF NOT EXISTS strong_drain_target (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '闸门目标ID',
    sequence_id BIGINT NOT NULL COMMENT '所属强排序列ID',
    station_code VARCHAR(32) NOT NULL COMMENT '泵站代码',
    gate_target DECIMAL(5,2) NOT NULL COMMENT '闸门目标开度（百分比，0-100），取自泵站档案快照',
    CONSTRAINT chk_sdt_gate CHECK (gate_target >= 0 AND gate_target <= 100),
    CONSTRAINT uk_sdt_sequence_station UNIQUE (sequence_id, station_code)
);

-- 序列各站启停任务：与序列同事务写出，确认前不存在、确认后全部存在（禁止一半站先启机）
CREATE TABLE IF NOT EXISTS strong_drain_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '强排启停任务ID',
    sequence_id BIGINT NOT NULL COMMENT '所属强排序列ID',
    station_code VARCHAR(32) NOT NULL COMMENT '泵站代码',
    action VARCHAR(8) NOT NULL COMMENT '动作 START强排启泵/STOP终结停泵',
    task_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '启停UTC时刻',
    reason VARCHAR(128) NOT NULL COMMENT '启停原因',
    task_state VARCHAR(16) NOT NULL COMMENT '任务状态 PENDING待到位回执/ACKED回执齐全已确认',
    CONSTRAINT chk_sdtask_action CHECK (action IN ('START', 'STOP')),
    CONSTRAINT chk_sdtask_state CHECK (task_state IN ('PENDING', 'ACKED')),
    CONSTRAINT uk_sdtask_station_time UNIQUE (station_code, task_time)
);

-- 闸门合成到位回执：按序列+站点唯一；旧序列回执只归旧序列，不能改动新序列任务状态
CREATE TABLE IF NOT EXISTS gate_receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '闸门到位回执ID',
    sequence_id BIGINT NOT NULL COMMENT '回执归属的强排序列ID',
    station_code VARCHAR(32) NOT NULL COMMENT '泵站代码',
    opening DECIMAL(5,2) NOT NULL COMMENT '实际到位闸门开度（百分比，0-100），须与候选目标一致才算到位',
    received_at TIMESTAMP NOT NULL COMMENT '回执接收时刻',
    CONSTRAINT chk_gr_opening CHECK (opening >= 0 AND opening <= 100),
    CONSTRAINT uk_gr_sequence_station UNIQUE (sequence_id, station_code)
);

-- 工况轨迹：每次候选生成、确认切换、拒绝均留证据，只追加不改写
CREATE TABLE IF NOT EXISTS regime_trace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '轨迹记录ID',
    zone_code VARCHAR(32) NOT NULL COMMENT '流域分区代码',
    tide_version BIGINT NULL COMMENT '关联潮位版本（配置变更等可空）',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型 CANDIDATE_CREATED候选生成/SWITCH_ON确认进入/SWITCH_OFF确认退出/SWITCH_REJECTED确认拒绝/CONFIG_CHANGED配置变更',
    from_mode VARCHAR(16) NOT NULL COMMENT '变更前工况 NORMAL常规/STRONG强排',
    to_mode VARCHAR(16) NOT NULL COMMENT '变更后工况 NORMAL常规/STRONG强排',
    sequence_id BIGINT NULL COMMENT '关联强排序列ID（可空）',
    detail VARCHAR(256) NOT NULL COMMENT '事件说明（拒绝原因等）',
    event_time TIMESTAMP NOT NULL COMMENT '事件记录时刻'
);
