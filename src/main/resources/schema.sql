-- 水族馆水质监测与联动处置 schema（H2 MySQL 兼容模式 / MySQL 通用）
-- 时间字段统一为 TIMESTAMP，语义为 Asia/Shanghai 本地时间戳；空值含义见各列注释。

CREATE TABLE IF NOT EXISTS zone_pool (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '展示池主键',
    code VARCHAR(32) NOT NULL COMMENT '展示池分区编码，全局唯一',
    name VARCHAR(64) NOT NULL COMMENT '展示池名称',
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '池状态：NORMAL正常/ALARM告警/EMERGENCY紧急/DISPOSING处置中',
    consecutive_violations INT NOT NULL DEFAULT 0 COMMENT '当前连续越限采样点数，正常采样后清零',
    sensor_state VARCHAR(16) NOT NULL DEFAULT 'ONLINE' COMMENT '传感器状态：ONLINE在线/MISSING失联数据缺失/PENDING_CONFIRM复连待人工确认',
    last_heartbeat_at TIMESTAMP NULL COMMENT '最近一次传感器心跳时间，NULL表示从未收到心跳',
    missing_since TIMESTAMP NULL COMMENT '标记数据缺失的起始时间，NULL表示未缺失',
    updated_at TIMESTAMP NOT NULL COMMENT '最近状态变更时间',
    CONSTRAINT uk_zone_pool_code UNIQUE (code)
) COMMENT '展示池分区表';

CREATE TABLE IF NOT EXISTS water_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '采样主键',
    zone_id BIGINT NOT NULL COMMENT '所属展示池ID',
    dissolved_oxygen DECIMAL(8,3) NOT NULL COMMENT '溶解氧，单位 mg/L',
    ammonia_nitrogen DECIMAL(8,3) NOT NULL COMMENT '氨氮，单位 mg/L',
    temperature DECIMAL(5,2) NOT NULL COMMENT '水温，单位 摄氏度',
    out_of_limit TINYINT NOT NULL DEFAULT 0 COMMENT '是否越限：0否（恰等限值记0）/1是',
    evaluated TINYINT NOT NULL DEFAULT 1 COMMENT '是否已参与告警评估：1是/0否（传感器缺失期间补录待补算）',
    sampled_at TIMESTAMP NOT NULL COMMENT '采样时间',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    CONSTRAINT fk_sample_zone FOREIGN KEY (zone_id) REFERENCES zone_pool (id)
) COMMENT '水质采样记录表';

CREATE TABLE IF NOT EXISTS alarm (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '告警主键',
    zone_id BIGINT NOT NULL COMMENT '所属展示池ID',
    params VARCHAR(64) NOT NULL COMMENT '越限参数列表，逗号分隔：DO溶解氧/NH3N氨氮/TEMP温度',
    detail VARCHAR(255) NOT NULL COMMENT '越限明细，含实测值与限值',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '告警状态：OPEN未升级/ESCALATED已升级紧急/RESOLVED已解除',
    sample_id BIGINT NOT NULL COMMENT '触发告警的采样ID',
    created_at TIMESTAMP NOT NULL COMMENT '告警生成时间',
    resolved_at TIMESTAMP NULL COMMENT '告警解除时间，NULL表示未解除',
    CONSTRAINT fk_alarm_zone FOREIGN KEY (zone_id) REFERENCES zone_pool (id)
) COMMENT '水质告警表';

CREATE TABLE IF NOT EXISTS disposal_command (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '处置指令主键',
    zone_id BIGINT NOT NULL COMMENT '所属展示池ID',
    alarm_id BIGINT NOT NULL COMMENT '关联的紧急告警ID',
    command_type VARCHAR(24) NOT NULL COMMENT '指令类型：AERATION增氧/WATER_CHANGE换水/CLOSURE_ISOLATION停展隔离',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '指令状态：PENDING待回执/CONFIRMED已确认/ESCALATED已超时升级/COMPLETED已完成',
    created_at TIMESTAMP NOT NULL COMMENT '指令下发时间',
    confirm_deadline TIMESTAMP NOT NULL COMMENT '回执确认截止时间，恰满5分钟未确认即升级',
    confirmed_at TIMESTAMP NULL COMMENT '回执确认时间，NULL表示未确认',
    escalated_at TIMESTAMP NULL COMMENT '超时升级时间，NULL表示未升级',
    CONSTRAINT fk_cmd_zone FOREIGN KEY (zone_id) REFERENCES zone_pool (id)
) COMMENT '联动处置指令表';

CREATE TABLE IF NOT EXISTS exhibition_registration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '登记主键',
    zone_id BIGINT NOT NULL COMMENT '所属展示池ID',
    group_name VARCHAR(64) NOT NULL COMMENT '参观团体名称',
    registered_at TIMESTAMP NOT NULL COMMENT '登记时间',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    CONSTRAINT fk_exh_zone FOREIGN KEY (zone_id) REFERENCES zone_pool (id)
) COMMENT '展示登记表';

CREATE TABLE IF NOT EXISTS retest_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '复测主键',
    zone_id BIGINT NOT NULL COMMENT '所属展示池ID',
    dissolved_oxygen DECIMAL(8,3) NOT NULL COMMENT '复测溶解氧，单位 mg/L',
    ammonia_nitrogen DECIMAL(8,3) NOT NULL COMMENT '复测氨氮，单位 mg/L',
    temperature DECIMAL(5,2) NOT NULL COMMENT '复测水温，单位 摄氏度',
    qualified TINYINT NOT NULL COMMENT '是否合格：1合格（全部不越限，恰等限值视为合格）/0不合格',
    tested_at TIMESTAMP NOT NULL COMMENT '复测时间',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    CONSTRAINT fk_retest_zone FOREIGN KEY (zone_id) REFERENCES zone_pool (id)
) COMMENT '恢复复测记录表';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求ID，全局唯一',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹，覆盖requestId与全部业务字段的SHA-256',
    endpoint VARCHAR(64) NOT NULL COMMENT '业务入口标识',
    response_body CLOB NOT NULL COMMENT '首次执行的响应报文（JSON）',
    created_at TIMESTAMP NOT NULL COMMENT '首次执行时间'
) COMMENT '写操作幂等记录表';

CREATE TABLE IF NOT EXISTS zone_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件主键',
    zone_id BIGINT NOT NULL COMMENT '所属展示池ID',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：STATUS_CHANGE状态变更/ALARM_RAISED告警/COMMAND_ISSUED指令下发/COMMAND_CONFIRMED回执/COMMAND_ESCALATED超时升级/SENSOR_MISSING失联/SENSOR_RECONNECTED复连/SENSOR_CONFIRMED人工确认/RECOVERY恢复',
    from_status VARCHAR(16) NULL COMMENT '变更前池状态，NULL表示不适用',
    to_status VARCHAR(16) NULL COMMENT '变更后池状态，NULL表示不适用',
    detail VARCHAR(255) NOT NULL DEFAULT '' COMMENT '事件明细',
    occurred_at TIMESTAMP NOT NULL COMMENT '事件发生时间',
    CONSTRAINT fk_event_zone FOREIGN KEY (zone_id) REFERENCES zone_pool (id)
) COMMENT '展示池事件流水表，用于日评估复算';
