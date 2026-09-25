-- 隧道火灾通风联动服务：H2（MODE=MySQL）建表脚本
-- 时间统一以 BIGINT 存储 epoch 毫秒（UTC 时钟，展示层按 Asia/Shanghai 渲染）。

CREATE TABLE IF NOT EXISTS fire_zone (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code    VARCHAR(32)  NOT NULL,
    zone_name    VARCHAR(64)  NOT NULL,
    CONSTRAINT uk_fire_zone_code UNIQUE (zone_code)
);
COMMENT ON TABLE fire_zone IS '隧道防火分区主数据（合成数据）';
COMMENT ON COLUMN fire_zone.zone_code IS '防火分区编码，如 ZONE_A';

CREATE TABLE IF NOT EXISTS device (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_code  VARCHAR(32)  NOT NULL,
    device_name  VARCHAR(64)  NOT NULL,
    device_type  VARCHAR(16)  NOT NULL,
    home_zone    VARCHAR(32)  NOT NULL,
    shared_flag  TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_device_code UNIQUE (device_code)
);
COMMENT ON TABLE device IS '风机/风阀设备台账（合成数据）';
COMMENT ON COLUMN device.device_type IS '设备类型：FAN 风机，DAMPER 风阀';
COMMENT ON COLUMN device.home_zone IS '设备归属分区编码';
COMMENT ON COLUMN device.shared_flag IS '是否为可被相邻分区共用的共享风阀：1 是，0 否';

CREATE TABLE IF NOT EXISTS zone_damper_ref (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code    VARCHAR(32)  NOT NULL,
    damper_code  VARCHAR(32)  NOT NULL,
    bias         VARCHAR(16)  NOT NULL,
    CONSTRAINT uk_zone_damper UNIQUE (zone_code, damper_code)
);
COMMENT ON TABLE zone_damper_ref IS '分区风阀编组及烟流方向偏置';
COMMENT ON COLUMN zone_damper_ref.bias IS '方向偏置：PLUS 与 FORWARD 同向时目标 OPEN，MINUS 反向时目标 OPEN，否则 CLOSE';

CREATE TABLE IF NOT EXISTS idem_request (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64)  NOT NULL,
    request_type    VARCHAR(16)  NOT NULL,
    zone_code       VARCHAR(32),
    fingerprint     VARCHAR(64)  NOT NULL,
    status          VARCHAR(24)  NOT NULL,
    conflict_info   VARCHAR(512),
    result_json     CLOB,
    created_at      BIGINT       NOT NULL,
    CONSTRAINT uk_idem_request_id UNIQUE (request_id)
);
COMMENT ON TABLE idem_request IS '以 requestId 为键的幂等请求记录，指纹覆盖全部业务字段';
COMMENT ON COLUMN idem_request.request_type IS '请求类型：ACTIVATE 激活，RESET 复位，ARCHIVE 归档，FEEDBACK 设备反馈';
COMMENT ON COLUMN idem_request.fingerprint IS 'SHA-256(requestId|type|全部业务字段)，同键异参判冲突';
COMMENT ON COLUMN idem_request.status IS '首次处理结果状态：SUCCESS 成功，CONFLICT 冲突';

CREATE TABLE IF NOT EXISTS mode_session (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code            VARCHAR(32)  NOT NULL,
    activate_request_id  VARCHAR(64)  NOT NULL,
    smoke_direction      VARCHAR(32)  NOT NULL,
    target_condition     VARCHAR(32)  NOT NULL,
    status               VARCHAR(24)  NOT NULL,
    fault_flag           TINYINT      NOT NULL DEFAULT 0,
    activated_at         BIGINT       NOT NULL,
    reset_completed_at   BIGINT,
    archived_at          BIGINT,
    CONSTRAINT uk_mode_activate_request UNIQUE (activate_request_id)
);
COMMENT ON TABLE mode_session IS '一次火灾模式激活的生命周期会话';
COMMENT ON COLUMN mode_session.smoke_direction IS '烟气流向假设：FORWARD 正向，BACKWARD 反向';
COMMENT ON COLUMN mode_session.target_condition IS '目标工况：SMOKE_EXHAUST 排烟，PRESSURIZATION 正压送风';
COMMENT ON COLUMN mode_session.status IS '会话状态：ACTIVE 激活中，RESETTING 复位中，RESET_COMPLETE 复位完成，ARCHIVED 已归档终态';
COMMENT ON COLUMN mode_session.fault_flag IS '本会话是否存在设备故障：1 是（不影响其他分区），0 否';
COMMENT ON COLUMN mode_session.activated_at IS '激活时间 epoch 毫秒';
COMMENT ON COLUMN mode_session.reset_completed_at IS '逆序复位全部到位时间 epoch 毫秒，未完成为空';
COMMENT ON COLUMN mode_session.archived_at IS '归档时间 epoch 毫秒，未归档为空';
CREATE INDEX IF NOT EXISTS idx_mode_session_zone ON mode_session (zone_code);

CREATE TABLE IF NOT EXISTS zone_activation_lock (
    zone_code   VARCHAR(32) NOT NULL PRIMARY KEY,
    session_id  BIGINT      NOT NULL,
    locked_at   BIGINT      NOT NULL
);
COMMENT ON TABLE zone_activation_lock IS '分区激活互斥锁：同一分区同时至多一个激活/复位中的火灾模式，靠主键唯一约束保证并发唯一';

CREATE TABLE IF NOT EXISTS shared_damper_commitment (
    damper_code   VARCHAR(32) NOT NULL PRIMARY KEY,
    target_action VARCHAR(16) NOT NULL,
    session_id    BIGINT      NOT NULL,
    zone_code     VARCHAR(32) NOT NULL,
    committed_at  BIGINT      NOT NULL
);
COMMENT ON TABLE shared_damper_commitment IS '共享风阀目标状态占用：以先激活分区的目标状态为准，异状态插入即冲突';

CREATE TABLE IF NOT EXISTS device_command (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    BIGINT      NOT NULL,
    request_id    VARCHAR(64) NOT NULL,
    zone_code     VARCHAR(32) NOT NULL,
    device_code   VARCHAR(32) NOT NULL,
    device_type   VARCHAR(16) NOT NULL,
    action        VARCHAR(16) NOT NULL,
    phase         VARCHAR(16) NOT NULL,
    seq_no        INT         NOT NULL,
    attempt       INT         NOT NULL DEFAULT 1,
    status        VARCHAR(24) NOT NULL,
    sent_at       BIGINT,
    ack_due_at    BIGINT,
    acked_at      BIGINT,
    fault_at      BIGINT,
    CONSTRAINT uk_command_session_device_phase UNIQUE (session_id, device_code, phase)
);
COMMENT ON TABLE device_command IS '按预案生成的设备指令序列（先阀后机，复位逆序）';
COMMENT ON COLUMN device_command.action IS '指令动作：OPEN 开阀，CLOSE 关阀，START 启风机，STOP 停风机';
COMMENT ON COLUMN device_command.phase IS '阶段：ACTIVATE 激活阶段，RESET 复位阶段';
COMMENT ON COLUMN device_command.seq_no IS '同一会话阶段内的下发顺序，风阀在前、风机在后';
COMMENT ON COLUMN device_command.attempt IS '下发次数：1 首发，2 超时重发，最多 2 次';
COMMENT ON COLUMN device_command.status IS '指令状态：PENDING 待下发，SENT 已下发待到位，ACKED 已到位，FAULT 设备故障';
COMMENT ON COLUMN device_command.sent_at IS '本次下发时间 epoch 毫秒';
COMMENT ON COLUMN device_command.ack_due_at IS '到位反馈截止时间（下发后 60 秒），恰满即判超时';
CREATE INDEX IF NOT EXISTS idx_command_status ON device_command (status);
CREATE INDEX IF NOT EXISTS idx_command_session ON device_command (session_id);

CREATE TABLE IF NOT EXISTS device_feedback (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id    VARCHAR(64),
    session_id    BIGINT,
    command_id    BIGINT,
    zone_code     VARCHAR(32),
    device_code   VARCHAR(32) NOT NULL,
    result        VARCHAR(24) NOT NULL,
    late_flag     TINYINT     NOT NULL DEFAULT 0,
    received_at   BIGINT      NOT NULL
);
COMMENT ON TABLE device_feedback IS '设备到位反馈流水；归档后到达只记迟到事件';
COMMENT ON COLUMN device_feedback.result IS '反馈结果：IN_POSITION 已到位';
COMMENT ON COLUMN device_feedback.late_flag IS '是否为会话归档后的迟到反馈：1 是，0 否';
CREATE INDEX IF NOT EXISTS idx_feedback_device ON device_feedback (device_code, received_at);

CREATE TABLE IF NOT EXISTS event_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type   VARCHAR(32)  NOT NULL,
    session_id   BIGINT,
    zone_code    VARCHAR(32),
    request_id   VARCHAR(64),
    detail       VARCHAR(1024),
    event_time   BIGINT       NOT NULL
);
COMMENT ON TABLE event_log IS '全部指令、反馈与状态迁移的有序事件流水，自增 id 即顺序';
COMMENT ON COLUMN event_log.event_type IS '事件类型：MODE_ACTIVATED/COMMAND_SENT/COMMAND_RESENT/FEEDBACK_RECEIVED/DEVICE_FAULT/ALARM_ESCALATED/MODE_RESETTING/RESET_COMPLETE/MODE_ARCHIVED/LATE_FEEDBACK/CONFLICT';
CREATE INDEX IF NOT EXISTS idx_event_session ON event_log (session_id, id);
CREATE INDEX IF NOT EXISTS idx_event_zone ON event_log (zone_code, id);
