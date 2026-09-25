-- 水库闸门调度令服务建表脚本（H2 MySQL 兼容模式，标准 SQL 语法亦可在 MySQL 8 执行）

CREATE TABLE IF NOT EXISTS reservoir (
    id          BIGINT PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL COMMENT '水库编号',
    name        VARCHAR(64)  NOT NULL COMMENT '水库名称（合成数据）',
    created_at  BIGINT       NOT NULL COMMENT '创建时刻，UTC 毫秒时间戳'
);

CREATE TABLE IF NOT EXISTS gate (
    id            BIGINT PRIMARY KEY,
    code          VARCHAR(32)  NOT NULL COMMENT '闸门编号',
    name          VARCHAR(64)  NOT NULL COMMENT '闸门名称（合成数据）',
    reservoir_id  BIGINT       NOT NULL COMMENT '所属水库编号',
    max_opening   DECIMAL(6,3) NOT NULL COMMENT '最大开度百分比，单位%（0-100）'
);

CREATE TABLE IF NOT EXISTS water_reading (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservoir_id  BIGINT       NOT NULL COMMENT '所属水库编号',
    level_m       DECIMAL(8,3) NOT NULL COMMENT '库水位，单位米',
    inflow_m3s    DECIMAL(10,3) NOT NULL COMMENT '入库流量，单位立方米每秒',
    read_at       BIGINT       NOT NULL COMMENT '读数时刻，UTC 毫秒时间戳（合成数据）'
);

-- 调度令主表
CREATE TABLE IF NOT EXISTS dispatch_order (
    request_id        VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '幂等键，调度令唯一编号',
    fingerprint       VARCHAR(64)  NOT NULL COMMENT '业务指纹：requestId 与全部业务字段的 SHA-256',
    status            VARCHAR(32)  NOT NULL COMMENT '状态：ISSUED/ACKNOWLEDGED/COMPLETED/TIMEOUT_ESCALATED/CANCELLED/ABORTED',
    deadline          BIGINT       NOT NULL COMMENT '执行时限，UTC 毫秒时间戳',
    issued_at         BIGINT       NOT NULL COMMENT '下达时刻，UTC 毫秒时间戳',
    acknowledged_at   BIGINT       NULL COMMENT '签收时刻，UTC 毫秒时间戳；未签收为空',
    finished_at       BIGINT       NULL COMMENT '终态时刻，UTC 毫秒时间戳；未终态为空',
    escalated         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已超时升级：0否1是',
    taken_over        TINYINT      NOT NULL DEFAULT 0 COMMENT '超时升级后是否已人工接管：0否1是',
    abort_note        VARCHAR(500) NULL COMMENT '中止说明；中止时已执行闸门实际开度留痕于 gate_step',
    payload_json      CLOB         NOT NULL COMMENT '下达业务字段快照（闸门序列与目标开度），JSON',
    created_at        BIGINT       NOT NULL COMMENT '记录创建时刻，UTC 毫秒时间戳'
);

-- 逐门执行项
CREATE TABLE IF NOT EXISTS gate_step (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64)  NOT NULL COMMENT '所属调度令',
    seq             INT          NOT NULL COMMENT '令中执行顺序，从1开始，按序依次执行',
    gate_code       VARCHAR(32)  NOT NULL COMMENT '闸门编号',
    target_opening  DECIMAL(6,3) NOT NULL COMMENT '目标开度，单位%（0-100）',
    actual_opening  DECIMAL(6,3) NULL COMMENT '回执实际开度，单位%；未回执为空',
    deviation       DECIMAL(10,6) NULL COMMENT '相对偏差 |实际-目标|/目标；目标为0时按绝对差判定，该列存绝对差',
    in_position     TINYINT      NULL COMMENT '是否到位（偏差不超±2%含边界）：0否1是；未回执为空',
    completed_at    BIGINT       NULL COMMENT '实际完成时刻，UTC 毫秒时间戳；未回执为空',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '执行项状态：PENDING/RECEIVED',
    receipt_fingerprint VARCHAR(64) NULL COMMENT '回执业务指纹：requestId+闸门+实际开度+完成时刻 SHA-256',
    CONSTRAINT uq_gate_step_order_seq UNIQUE (request_id, seq)
);

-- 同一闸门同一时刻只允许一张未完成调度令：在令存续期间持锁，终态释放
CREATE TABLE IF NOT EXISTS active_gate_lock (
    gate_code   VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '被占用闸门编号',
    request_id  VARCHAR(64) NOT NULL COMMENT '占用该闸门的在执行调度令',
    locked_at   BIGINT      NOT NULL COMMENT '加锁时刻，UTC 毫秒时间戳'
);

-- 通知留痕（超时升级通知上级 / 偏差自动告警的本地模拟）
CREATE TABLE IF NOT EXISTS dispatch_notification (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id  VARCHAR(64)  NOT NULL COMMENT '关联调度令',
    type        VARCHAR(32)  NOT NULL COMMENT '通知类型：ESCALATION/DEVIATION_ALARM',
    message     VARCHAR(500) NOT NULL COMMENT '通知内容',
    created_at  BIGINT       NOT NULL COMMENT '通知时刻，UTC 毫秒时间戳'
);

CREATE INDEX IF NOT EXISTS idx_gate_step_request ON gate_step (request_id, seq);
CREATE INDEX IF NOT EXISTS idx_notification_request ON dispatch_notification (request_id, id);
CREATE INDEX IF NOT EXISTS idx_order_issued ON dispatch_order (issued_at, request_id);
