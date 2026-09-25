-- 城镇燃气门站加臭与压力联动服务：H2（MODE=MySQL）建表脚本
-- 仅用于本地运行与单元测试，数据保留在 JVM 生命周期内

CREATE TABLE IF NOT EXISTS gate_station (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    station_code    VARCHAR(64)  NOT NULL COMMENT '门站编码',
    station_name    VARCHAR(128) NOT NULL COMMENT '门站名称',
    odor_target     DECIMAL(12,4) NOT NULL COMMENT '末端加臭目标浓度，单位 mg/m³，本服务为 20',
    odor_tolerance  DECIMAL(8,4)  NOT NULL COMMENT '浓度允许相对偏差，无量纲，本服务为 0.20',
    pressure_low    DECIMAL(12,4) NOT NULL COMMENT '管网压力下限，单位 kPa，恰等限值合格',
    pressure_high   DECIMAL(12,4) NOT NULL COMMENT '管网压力上限，单位 kPa，恰等限值合格',
    odor_consumed   DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '加臭剂累计实耗，单位 g（克）',
    valve_state     VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '加注阀状态：OPEN 正常加注 / LOCKED 告警锁定',
    alarm_active    BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否存在未解除的浓度越限告警；复开时同事务解除',
    violation_count INT          NOT NULL DEFAULT 0 COMMENT '截至最近一次检测的连续越限计数；合格或复开清零',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，时区 Asia/Shanghai',
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间，时区 Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE (station_code)
);

-- 幂等请求登记表：指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_request (
    id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id       VARCHAR(96) NOT NULL COMMENT '业务幂等键 requestId',
    request_type     VARCHAR(32) NOT NULL COMMENT '请求类型：DETECTION/LINKAGE/SETTLEMENT',
    fingerprint      VARCHAR(96) NOT NULL COMMENT 'requestId+全业务字段的 SHA-256 指纹',
    response_body    VARCHAR(4096) COMMENT '首次受理时留存的响应快照（JSON）',
    idempotent_hit   BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '是否为同键重放命中',
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次受理时间，时区 Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE (request_id)
);

-- 浓度检测序列：用于连续越限计数与浓度曲线复算
CREATE TABLE IF NOT EXISTS concentration_detection (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    station_code     VARCHAR(64)  NOT NULL COMMENT '门站编码',
    request_id       VARCHAR(96)  NOT NULL COMMENT '检测请求 requestId',
    detected_at      TIMESTAMP    NOT NULL COMMENT '检测时刻，时区 Asia/Shanghai',
    concentration_mg DECIMAL(12,4) NOT NULL COMMENT '末端检测浓度，单位 mg/m³',
    in_range         BOOLEAN      NOT NULL COMMENT '是否落在目标浓度 ±20% 区间（含边界）',
    violation_seq    INT          NOT NULL COMMENT '本条之前（含本条判定基数）的连续越限计数',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间，时区 Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE (request_id)
);

-- 流量读数
CREATE TABLE IF NOT EXISTS flow_reading (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    station_code     VARCHAR(64)  NOT NULL COMMENT '门站编码',
    request_id       VARCHAR(96)  NOT NULL COMMENT '检测请求 requestId',
    detected_at      TIMESTAMP    NOT NULL COMMENT '检测时刻，时区 Asia/Shanghai',
    flow_m3          DECIMAL(18,6) NOT NULL COMMENT '检测时段累计工况流量，单位 m³',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间，时区 Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE (request_id)
);

-- 加臭剂投加记录：理论/实投/累计，支撑消耗勾稽复算
CREATE TABLE IF NOT EXISTS odorant_injection (
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    station_code           VARCHAR(64)  NOT NULL COMMENT '门站编码',
    source_request_id      VARCHAR(96)  NOT NULL COMMENT '触发来源 requestId（检测或联动）',
    occurred_at            TIMESTAMP    NOT NULL COMMENT '投加时刻，时区 Asia/Shanghai',
    flow_m3                DECIMAL(18,6) NOT NULL COMMENT '对应时段流量，单位 m³',
    dose_g                 DECIMAL(18,6) NOT NULL COMMENT '单位流量投加比例，单位 g/m³',
    theoretical_consume_g  DECIMAL(18,6) NOT NULL COMMENT '按比例计算的理论消耗，单位 g',
    actual_consume_g       DECIMAL(18,6) NOT NULL COMMENT '实际下发消耗，单位 g；锁阀期间为 0',
    valve_locked           BOOLEAN      NOT NULL COMMENT '下发时阀门是否锁定',
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间，时区 Asia/Shanghai',
    PRIMARY KEY (id)
);

-- 阀门双人确认指令（锁阀 / 复开）
CREATE TABLE IF NOT EXISTS valve_command (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    station_code     VARCHAR(64)  NOT NULL COMMENT '门站编码',
    request_id       VARCHAR(96)  NOT NULL COMMENT '阀门操作 requestId',
    action           VARCHAR(16)  NOT NULL COMMENT '动作：LOCK 锁阀 / REOPEN 复开',
    operator1        VARCHAR(64)  NOT NULL COMMENT '确认人一',
    operator2        VARCHAR(64) COMMENT '确认人二；单人操作时为空且不生效',
    effective        BOOLEAN      NOT NULL COMMENT '是否双人确认并生效',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间，时区 Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE (request_id)
);

-- 压力检测序列：支撑压力链复算
CREATE TABLE IF NOT EXISTS pressure_detection (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    station_code     VARCHAR(64)  NOT NULL COMMENT '门站编码',
    request_id       VARCHAR(96)  NOT NULL COMMENT '联动请求 requestId',
    detected_at      TIMESTAMP    NOT NULL COMMENT '检测时刻，时区 Asia/Shanghai',
    pressure_kpa     DECIMAL(12,4) NOT NULL COMMENT '管网实测压力，单位 kPa',
    pressure_action  VARCHAR(16)  NOT NULL COMMENT '判定动作：HOLD/RAISE/LOWER（恰等限值为 HOLD）',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间，时区 Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE (request_id)
);

-- 联动任务：同站同时刻执行中唯一
CREATE TABLE IF NOT EXISTS linkage_task (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    station_code     VARCHAR(64)  NOT NULL COMMENT '门站编码',
    task_type        VARCHAR(32)  NOT NULL COMMENT '任务类型：PRESSURE_REGULATION',
    status           VARCHAR(16)  NOT NULL COMMENT '任务状态：EXECUTING 执行中 / COMPLETED 已完成',
    start_at         TIMESTAMP    NOT NULL COMMENT '联动时刻（起），时区 Asia/Shanghai',
    finish_at        TIMESTAMP    COMMENT '完成时刻，时区 Asia/Shanghai；执行中为空',
    request_id       VARCHAR(96)  NOT NULL COMMENT '联动请求 requestId',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，时区 Asia/Shanghai',
    PRIMARY KEY (id)
);

-- 同站同时刻联动受理槽：并发下达时由主键唯一受理一条，受理事实永久保留
CREATE TABLE IF NOT EXISTS linkage_slot (
    station_code     VARCHAR(64)  NOT NULL COMMENT '门站编码',
    start_at         TIMESTAMP    NOT NULL COMMENT '联动时刻，时区 Asia/Shanghai',
    request_id       VARCHAR(96)  NOT NULL COMMENT '受理成功的联动请求 requestId',
    task_id          BIGINT       NOT NULL COMMENT '对应 linkage_task 主键',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '受理时间，时区 Asia/Shanghai',
    PRIMARY KEY (station_code, start_at)
);

-- 日结单
CREATE TABLE IF NOT EXISTS daily_settlement (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    station_code          VARCHAR(64)  NOT NULL COMMENT '门站编码',
    settle_date           DATE         NOT NULL COMMENT '结算日（Asia/Shanghai 日历日）',
    request_id            VARCHAR(96)  NOT NULL COMMENT '日结 requestId',
    total_flow_m3         DECIMAL(18,6) NOT NULL COMMENT '结算日累计流量，单位 m³',
    theoretical_consume_g DECIMAL(18,6) NOT NULL COMMENT '按投加比例复算的理论消耗，单位 g',
    actual_consume_g      DECIMAL(18,6) NOT NULL COMMENT '投加记录实耗汇总，单位 g',
    deviation_ratio       DECIMAL(12,6) NOT NULL COMMENT '相对偏差 |实耗-理论|/理论，无量纲；恰 0.03 合格',
    status                VARCHAR(16)  NOT NULL COMMENT '状态：SEALED 封存终态 / MANUAL 转人工',
    sealed_at             TIMESTAMP    COMMENT '封存时间，时区 Asia/Shanghai；未封存为空',
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，时区 Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE (station_code, settle_date)
);

-- 封存后迟到检测事件
CREATE TABLE IF NOT EXISTS late_detection_event (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    station_code     VARCHAR(64)  NOT NULL COMMENT '门站编码',
    settle_date      DATE         NOT NULL COMMENT '已封存的结算日',
    request_id       VARCHAR(96)  NOT NULL COMMENT '迟到检测携带的 requestId',
    detected_at      TIMESTAMP    NOT NULL COMMENT '迟到检测的业务时刻，时区 Asia/Shanghai',
    payload          VARCHAR(1024) COMMENT '迟到检测载荷摘要',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间，时区 Asia/Shanghai',
    PRIMARY KEY (id)
);

-- 站内通用事件流（告警、锁阀、复开解除、转人工等）
CREATE TABLE IF NOT EXISTS station_event (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    station_code     VARCHAR(64)  NOT NULL COMMENT '门站编码',
    event_kind       VARCHAR(32)  NOT NULL COMMENT '事件类型：ODOR_ALARM/VALVE_LOCKED/VALVE_REOPENED/LATE_DETECTION/MANUAL_REVIEW',
    event_at         TIMESTAMP    NOT NULL COMMENT '事件发生时刻，时区 Asia/Shanghai',
    detail           VARCHAR(1024) COMMENT '事件详情摘要',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间，时区 Asia/Shanghai',
    PRIMARY KEY (id)
);
