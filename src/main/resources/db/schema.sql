-- 隧道火灾通风联动：H2 内存库（MODE=MySQL）建表脚本。
-- 所有语句使用 IF NOT EXISTS，可在空库上重复执行。

-- 防火分区
CREATE TABLE IF NOT EXISTS fire_zone (
    zone_code   VARCHAR(32)  NOT NULL,
    zone_name   VARCHAR(128) NOT NULL,
    remark      VARCHAR(255),
    PRIMARY KEY (zone_code)
);

-- 设备：风机(FAN)/风阀(DAMPER)，均为合成数据
CREATE TABLE IF NOT EXISTS device (
    device_code VARCHAR(32)  NOT NULL,
    device_type VARCHAR(16)  NOT NULL,
    home_zone   VARCHAR(32)  NOT NULL,
    shared_flag TINYINT      NOT NULL DEFAULT 0,
    device_name VARCHAR(128) NOT NULL,
    CONSTRAINT pk_device PRIMARY KEY (device_code),
    CONSTRAINT ck_device_type CHECK (device_type IN ('FAN', 'DAMPER')),
    CONSTRAINT ck_device_shared CHECK (shared_flag IN (0, 1))
);

-- 分区预案设备序列：step_order 为先阀后机的预案顺序；
-- purge_target/reset_target 为该分区在排烟/复位阶段对设备的目标状态。
CREATE TABLE IF NOT EXISTS zone_device (
    zone_code     VARCHAR(32) NOT NULL,
    device_code   VARCHAR(32) NOT NULL,
    step_order     INT         NOT NULL,
    purge_target  VARCHAR(16) NOT NULL,
    reset_target  VARCHAR(16) NOT NULL,
    CONSTRAINT pk_zone_device PRIMARY KEY (zone_code, device_code)
);

-- 分区相邻关系（无向，按两行存储）
CREATE TABLE IF NOT EXISTS zone_adjacency (
    zone_code        VARCHAR(32) NOT NULL,
    adjacent_zone    VARCHAR(32) NOT NULL,
    CONSTRAINT pk_zone_adjacency PRIMARY KEY (zone_code, adjacent_zone)
);

-- 火灾模式激活单
CREATE TABLE IF NOT EXISTS fire_mode (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id           VARCHAR(64)  NOT NULL,
    zone_code            VARCHAR(32)  NOT NULL,
    smoke_flow_direction VARCHAR(32)  NOT NULL,
    target_condition     VARCHAR(64)  NOT NULL,
    fingerprint          CHAR(64)     NOT NULL,
    status               VARCHAR(24)  NOT NULL,
    conflict_detail      VARCHAR(512),
    created_at           DATETIME(3)  NOT NULL,
    reset_started_at     DATETIME(3),
    reset_completed_at   DATETIME(3),
    archived_at          DATETIME(3),
    CONSTRAINT uq_fire_mode_request UNIQUE (request_id),
    CONSTRAINT ck_fire_mode_status CHECK (status IN
        ('ACTIVE', 'RESETTING', 'RESET_COMPLETE', 'ARCHIVED'))
);

-- 分区激活锁：同一分区同一时刻只允许一个未完成复位的火灾模式。
-- 激活成功时插入，复位完成时删除；并发激活依靠主键唯一约束保证唯一成功。
CREATE TABLE IF NOT EXISTS active_zone_lock (
    zone_code VARCHAR(32) NOT NULL,
    mode_id   BIGINT      NOT NULL,
    locked_at DATETIME(3) NOT NULL,
    CONSTRAINT pk_active_zone_lock PRIMARY KEY (zone_code)
);

-- 幂等记录：指纹覆盖 requestId+kind+全部业务字段
CREATE TABLE IF NOT EXISTS idem_request (
    request_id   VARCHAR(64)  NOT NULL,
    request_kind VARCHAR(16)  NOT NULL,
    fingerprint  CHAR(64)     NOT NULL,
    http_status  INT          NOT NULL,
    response_json CLOB        NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    CONSTRAINT pk_idem_request PRIMARY KEY (request_id, request_kind),
    CONSTRAINT ck_idem_kind CHECK (request_kind IN
        ('ACTIVATE', 'RESET', 'ARCHIVE', 'FEEDBACK'))
);

-- 设备指令：attempt 为发送次数（首发 1，超时重发后 2）
CREATE TABLE IF NOT EXISTS device_command (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_uid     VARCHAR(48) NOT NULL,
    mode_id         BIGINT      NOT NULL,
    zone_code       VARCHAR(32) NOT NULL,
    device_code     VARCHAR(32) NOT NULL,
    device_type     VARCHAR(16) NOT NULL,
    target_state    VARCHAR(16) NOT NULL,
    phase           VARCHAR(16) NOT NULL,
    step_order      INT         NOT NULL,
    attempt         INT         NOT NULL DEFAULT 1,
    status          VARCHAR(24) NOT NULL,
    blocked_reason  VARCHAR(255),
    issued_at       DATETIME(3),
    ack_at          DATETIME(3),
    failed_at       DATETIME(3),
    created_at      DATETIME(3) NOT NULL,
    CONSTRAINT uq_command_uid UNIQUE (command_uid),
    CONSTRAINT ck_command_device_type CHECK (device_type IN ('FAN', 'DAMPER')),
    CONSTRAINT ck_command_phase CHECK (phase IN ('PURGE', 'RESET')),
    CONSTRAINT ck_command_status CHECK (status IN
        ('PENDING', 'ISSUED', 'IN_POSITION', 'FAILED', 'SKIPPED'))
);

-- 设备到位反馈
CREATE TABLE IF NOT EXISTS device_feedback (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_uid    VARCHAR(48),
    mode_id        BIGINT,
    device_code    VARCHAR(32) NOT NULL,
    in_position    TINYINT     NOT NULL,
    observed_state VARCHAR(16),
    late_flag      TINYINT     NOT NULL DEFAULT 0,
    detail         VARCHAR(255),
    received_at    DATETIME(3) NOT NULL,
    CONSTRAINT ck_feedback_inpos CHECK (in_position IN (0, 1)),
    CONSTRAINT ck_feedback_late CHECK (late_flag IN (0, 1))
);

-- 全量有序事件流：指令、反馈、状态迁移、告警升级均可按 id 顺序追溯
CREATE TABLE IF NOT EXISTS event_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    mode_id     BIGINT,
    zone_code   VARCHAR(32),
    request_id  VARCHAR(64),
    event_type  VARCHAR(40) NOT NULL,
    detail      VARCHAR(1000),
    event_time  DATETIME(3) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_command_mode ON device_command (mode_id, phase, step_order);
CREATE INDEX IF NOT EXISTS idx_command_status ON device_command (status, issued_at);
CREATE INDEX IF NOT EXISTS idx_feedback_command ON device_feedback (command_uid);
CREATE INDEX IF NOT EXISTS idx_event_mode ON event_log (mode_id, id);
