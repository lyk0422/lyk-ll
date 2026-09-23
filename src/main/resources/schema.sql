-- micro-ROS 边缘离线消息转发器：H2 文件库（MODE=MySQL）建表脚本。
-- H2 的 MySQL 兼容模式支持表/列 COMMENT 语法。

CREATE TABLE IF NOT EXISTS topic_config (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic       VARCHAR(255) NOT NULL,
    policy_type VARCHAR(32)  NOT NULL,
    capacity    INTEGER      NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uq_topic_config_topic UNIQUE (topic)
);
COMMENT ON COLUMN topic_config.policy_type IS '离线保留策略：DROP/LATEST/FIFO/BOUNDED';
COMMENT ON COLUMN topic_config.capacity IS 'BOUNDED 策略容量（条），其余策略为 NULL';

CREATE TABLE IF NOT EXISTS device_message (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id   VARCHAR(128) NOT NULL,
    topic       VARCHAR(255) NOT NULL,
    seq         BIGINT       NOT NULL,
    priority    INTEGER      NOT NULL,
    dedup_key   VARCHAR(255) NOT NULL,
    payload     VARCHAR(4000),
    status      VARCHAR(16)  NOT NULL,
    attempts    INTEGER      NOT NULL DEFAULT 0,
    enqueued_at TIMESTAMP    NOT NULL,
    sent_at     TIMESTAMP    NULL,
    CONSTRAINT uq_device_message_dedup UNIQUE (dedup_key)
);
COMMENT ON COLUMN device_message.seq IS '设备本地单调序号（同一设备+Topic 内递增）';
COMMENT ON COLUMN device_message.priority IS '业务优先级，数值越大补发越靠前';
COMMENT ON COLUMN device_message.status IS 'PENDING=待发；IN_FLIGHT=已发送待 ACK';
COMMENT ON COLUMN device_message.attempts IS '已发送次数，超时或重启重试时累加';
COMMENT ON COLUMN device_message.sent_at IS '最近一次发送时间（UTC），NULL 表示从未发送';

CREATE INDEX IF NOT EXISTS idx_device_message_flush
    ON device_message (status, priority DESC, seq ASC);
CREATE INDEX IF NOT EXISTS idx_device_message_topic
    ON device_message (topic, status);
CREATE INDEX IF NOT EXISTS idx_device_message_evict
    ON device_message (priority ASC, enqueued_at ASC);

CREATE TABLE IF NOT EXISTS audit_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type  VARCHAR(32)  NOT NULL,
    device_id   VARCHAR(128) NULL,
    topic       VARCHAR(255) NULL,
    seq         BIGINT       NULL,
    dedup_key   VARCHAR(255) NULL,
    message_id  BIGINT       NULL,
    reason      VARCHAR(1000),
    created_at  TIMESTAMP    NOT NULL
);
COMMENT ON COLUMN audit_record.event_type IS '事件类型：DROP_POLICY/OVERWRITE/EVICT/REJECT/SENT/DELIVERED/ACK_IGNORED/RETRY_RESET/DEDUP_SKIP';
COMMENT ON COLUMN audit_record.message_id IS '相关 device_message 主键，消息已物理删除时仍保留历史 ID';
COMMENT ON COLUMN audit_record.created_at IS '事件时间（UTC）';

CREATE INDEX IF NOT EXISTS idx_audit_type ON audit_record (event_type);
CREATE INDEX IF NOT EXISTS idx_audit_dedup ON audit_record (dedup_key);
