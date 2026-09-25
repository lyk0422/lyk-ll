-- 集中供热二网水力平衡服务建表脚本（H2 MySQL 兼容模式，数据仅保留在 JVM 生命周期内）
-- 所有时间列一律存储 UTC 时刻；开度单位为百分比（0~100）；温度单位为摄氏度。

CREATE TABLE IF NOT EXISTS heat_station (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code VARCHAR(64)  NOT NULL COMMENT '换热站编码',
    station_name VARCHAR(128) NOT NULL COMMENT '换热站名称',
    created_at   TIMESTAMP    NOT NULL COMMENT '创建时刻(UTC)',
    CONSTRAINT uk_station_code UNIQUE (station_code)
);

CREATE TABLE IF NOT EXISTS heating_unit (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT      NOT NULL COMMENT '所属换热站ID',
    unit_code  VARCHAR(64) NOT NULL COMMENT '单元编码',
    unit_name  VARCHAR(128) NOT NULL COMMENT '单元名称',
    created_at TIMESTAMP   NOT NULL COMMENT '创建时刻(UTC)',
    CONSTRAINT uk_unit_station_code UNIQUE (station_id, unit_code)
);

CREATE TABLE IF NOT EXISTS valve (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    unit_id    BIGINT       NOT NULL COMMENT '所属单元ID',
    valve_code VARCHAR(64)  NOT NULL COMMENT '阀门编码',
    opening    DECIMAL(6,3) NOT NULL COMMENT '当前开度百分比,0~100,单位:%',
    updated_at TIMESTAMP    NOT NULL COMMENT '开度最近变更时刻(UTC)',
    CONSTRAINT uk_valve_unit UNIQUE (unit_id),
    CONSTRAINT uk_valve_code UNIQUE (valve_code)
);

-- 回水/供水温度采集；正常上报写入本表，归档后迟到数据转记 late_temperature_event
CREATE TABLE IF NOT EXISTS temperature_reading (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    unit_id     BIGINT       NOT NULL COMMENT '所属单元ID',
    station_id  BIGINT       NOT NULL COMMENT '所属换热站ID',
    supply_temp DECIMAL(6,3) NOT NULL COMMENT '供水温度,单位:℃',
    return_temp DECIMAL(6,3) NOT NULL COMMENT '回水温度,单位:℃',
    observed_at TIMESTAMP    NOT NULL COMMENT '温度测量时刻(UTC)',
    received_at TIMESTAMP    NOT NULL COMMENT '服务端接收时刻(UTC)',
    created_at  TIMESTAMP    NOT NULL COMMENT '落库时刻(UTC)'
);
CREATE INDEX IF NOT EXISTS idx_reading_unit_time ON temperature_reading (unit_id, observed_at);

-- 调节任务：同一换热站同时刻只允许一个执行中任务（active_station_id 唯一约束，归档后置空）
CREATE TABLE IF NOT EXISTS adjustment_task (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id        BIGINT      NOT NULL COMMENT '换热站ID',
    request_id        VARCHAR(80) NOT NULL COMMENT '首条调节令幂等键',
    status            VARCHAR(16) NOT NULL COMMENT '任务状态:EXECUTING执行中/ARCHIVED已归档终态',
    active_station_id BIGINT      NULL COMMENT '执行中时等于station_id,归档后置空,用于并发唯一约束',
    created_at        TIMESTAMP   NOT NULL COMMENT '创建时刻(UTC)',
    archived_at       TIMESTAMP   NULL COMMENT '归档时刻(UTC),NULL表示未归档',
    CONSTRAINT uk_task_request UNIQUE (request_id),
    CONSTRAINT uk_task_active_station UNIQUE (active_station_id)
);

-- 阀门开度版本链：每次步进一条，与 valve.opening 更新在同一事务内写入
CREATE TABLE IF NOT EXISTS valve_opening_version (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    valve_id      BIGINT       NOT NULL COMMENT '阀门ID',
    version_no    INT          NOT NULL COMMENT '同一阀门内递增版本号,从1开始',
    from_opening  DECIMAL(6,3) NOT NULL COMMENT '步进前开度,单位:%',
    to_opening    DECIMAL(6,3) NOT NULL COMMENT '步进后开度,单位:%',
    delta         DECIMAL(6,3) NOT NULL COMMENT '本次步进幅度,0~10,单位:%',
    action        VARCHAR(8)   NOT NULL COMMENT '调节方向:OPEN开大/CLOSE关小',
    task_id       BIGINT       NOT NULL COMMENT '所属调节任务ID',
    request_id    VARCHAR(80)  NOT NULL COMMENT '触发该版本的调节令幂等键',
    changed_at    TIMESTAMP    NOT NULL COMMENT '变更时刻(UTC)',
    CONSTRAINT uk_version_valve_no UNIQUE (valve_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_version_task ON valve_opening_version (task_id);

-- 单元调节动作台账：用于 24 小时(UTC)内调节次数统计
CREATE TABLE IF NOT EXISTS adjustment_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id     BIGINT       NOT NULL COMMENT '所属调节任务ID',
    unit_id     BIGINT       NOT NULL COMMENT '被调节单元ID',
    valve_id    BIGINT       NOT NULL COMMENT '被调节阀门ID',
    request_id  VARCHAR(80)  NOT NULL COMMENT '调节令幂等键',
    step_index  INT          NOT NULL COMMENT '同一调节令内的步进序号,从1开始',
    action      VARCHAR(8)   NOT NULL COMMENT '调节方向:OPEN/CLOSE',
    from_opening DECIMAL(6,3) NOT NULL COMMENT '步进前开度,单位:%',
    to_opening   DECIMAL(6,3) NOT NULL COMMENT '步进后开度,单位:%',
    delta       DECIMAL(6,3) NOT NULL COMMENT '步进幅度,单位:%',
    created_at  TIMESTAMP    NOT NULL COMMENT '调节时刻(UTC)'
);
CREATE INDEX IF NOT EXISTS idx_adjust_unit_time ON adjustment_record (unit_id, created_at);

-- 幂等请求记录：指纹覆盖 requestId 与全部业务字段，保存首次响应
-- status: PENDING已受理执行中 / COMPLETED已完成（同键并发时后到者等待前者）
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id     VARCHAR(80)  NOT NULL COMMENT '幂等键',
    request_type   VARCHAR(16)  NOT NULL COMMENT '请求类型:ADJUST/EVALUATE/ARCHIVE/TEMPERATURE',
    status         VARCHAR(12)  NOT NULL COMMENT '受理状态:PENDING执行中/COMPLETED已完成',
    fingerprint    VARCHAR(96)  NOT NULL COMMENT 'requestId+全部业务字段的SHA-256指纹',
    station_id     BIGINT       NOT NULL COMMENT '换热站ID',
    http_status    INT          NULL COMMENT '首次响应HTTP状态码,执行中为空',
    response_body  CLOB         NULL COMMENT '首次响应体JSON,执行中为空',
    replay_count   INT          NOT NULL DEFAULT 0 COMMENT '同键同参重放次数',
    created_at     TIMESTAMP    NOT NULL COMMENT '首次受理时刻(UTC)',
    completed_at   TIMESTAMP    NULL COMMENT '首次完成时刻(UTC),NULL表示执行中',
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);

-- 评估记录：失衡判定与效果评估均落表，支持前后对比复算
CREATE TABLE IF NOT EXISTS evaluation_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         BIGINT       NOT NULL COMMENT '所属调节任务ID',
    station_id      BIGINT       NOT NULL COMMENT '换热站ID',
    request_id      VARCHAR(80)  NOT NULL COMMENT '评估幂等键',
    phase           VARCHAR(16)  NOT NULL COMMENT '评估阶段:JUDGMENT失衡判定/RE_EVAL效果评估',
    max_return_temp DECIMAL(6,3) NOT NULL COMMENT '最高回水温度,单位:℃',
    min_return_temp DECIMAL(6,3) NOT NULL COMMENT '最低回水温度,单位:℃',
    temp_diff       DECIMAL(6,3) NOT NULL COMMENT '回水温度极差=最高-最低,单位:℃',
    balanced        BOOLEAN      NOT NULL COMMENT '温差<=2℃为平衡(恰2℃合格)',
    detail_json     CLOB         NOT NULL COMMENT '各单元温差与欠流/过流分类明细JSON',
    evaluated_at    TIMESTAMP    NOT NULL COMMENT '评估时刻(UTC)'
);
CREATE INDEX IF NOT EXISTS idx_eval_task ON evaluation_record (task_id);

-- 人工复核台账：单元 24 小时(UTC)内调节超出 3 次时转入
CREATE TABLE IF NOT EXISTS manual_review (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id       BIGINT      NOT NULL COMMENT '换热站ID',
    unit_id          BIGINT      NOT NULL COMMENT '转入复核的单元ID',
    request_id       VARCHAR(80) NOT NULL COMMENT '触发复核的请求幂等键',
    adjustment_count INT         NOT NULL COMMENT '窗口内已发生调节次数',
    window_start     TIMESTAMP   NOT NULL COMMENT '24小时窗口起点(UTC)',
    window_end       TIMESTAMP   NOT NULL COMMENT '24小时窗口终点(UTC)',
    reason           VARCHAR(255) NOT NULL COMMENT '复核原因',
    created_at       TIMESTAMP   NOT NULL COMMENT '转入时刻(UTC)'
);

-- 归档后迟到温度事件：只记录，不参与任何评估
CREATE TABLE IF NOT EXISTS late_temperature_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id  BIGINT      NOT NULL COMMENT '换热站ID',
    unit_id     BIGINT       NULL COMMENT '单元ID,无法定位时为空',
    request_id  VARCHAR(80) NOT NULL COMMENT '迟到上报幂等键',
    detail_json CLOB        NOT NULL COMMENT '迟到温度报文JSON',
    observed_at TIMESTAMP   NOT NULL COMMENT '温度测量时刻(UTC)',
    received_at TIMESTAMP   NOT NULL COMMENT '服务端接收时刻(UTC)',
    created_at  TIMESTAMP   NOT NULL COMMENT '落库时刻(UTC)'
);
