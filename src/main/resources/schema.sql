-- micro-ROS 离线消息转发器嵌入式 H2（MODE=MySQL）建表脚本
-- 时间字段均为 Unix 毫秒时间戳（Asia/Shanghai 业务时区），NULL 表示该阶段尚未发生。

CREATE TABLE IF NOT EXISTS topic_policy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    strategy VARCHAR(32) NOT NULL,
    bound INT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uq_topic_policy_topic UNIQUE (topic)
);

CREATE TABLE IF NOT EXISTS device_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    seq BIGINT NOT NULL,
    priority INT NOT NULL,
    payload CLOB,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    enqueued_at BIGINT NOT NULL,
    inflight_at BIGINT,
    forwarded_at BIGINT,
    acked_at BIGINT,
    CONSTRAINT uq_device_message_message_id UNIQUE (message_id)
);

CREATE INDEX IF NOT EXISTS idx_dm_status_prio_seq ON device_message (status, priority, seq);
CREATE INDEX IF NOT EXISTS idx_dm_topic_status ON device_message (topic, status);
CREATE INDEX IF NOT EXISTS idx_dm_device_seq ON device_message (device_id, seq);
CREATE INDEX IF NOT EXISTS idx_dm_inflight ON device_message (status, inflight_at);

CREATE TABLE IF NOT EXISTS audit_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(32) NOT NULL,
    message_id VARCHAR(64),
    device_id VARCHAR(64),
    topic VARCHAR(255),
    seq BIGINT,
    priority INT,
    reason VARCHAR(512),
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_topic ON audit_record (topic);
CREATE INDEX IF NOT EXISTS idx_audit_type ON audit_record (event_type);

COMMENT ON TABLE topic_policy IS 'Topic 离线保留策略配置表';
COMMENT ON COLUMN topic_policy.topic IS '消息主题，全局唯一';
COMMENT ON COLUMN topic_policy.strategy IS '策略：DROP=离线丢弃，LATEST=仅留最新，FIFO=完整顺序保留，BOUNDED=限量保留';
COMMENT ON COLUMN topic_policy.bound IS 'BOUNDED 策略的保留条数 n，其余策略为 NULL';
COMMENT ON COLUMN topic_policy.created_at IS '创建时间，Unix 毫秒时间戳（Asia/Shanghai）';
COMMENT ON COLUMN topic_policy.updated_at IS '最近更新时间，Unix 毫秒时间戳（Asia/Shanghai）';

COMMENT ON TABLE device_message IS '设备消息表，覆盖待发、in-flight、已确认全生命周期';
COMMENT ON COLUMN device_message.message_id IS '全局唯一去重键 dedupKey';
COMMENT ON COLUMN device_message.device_id IS '边缘设备标识';
COMMENT ON COLUMN device_message.topic IS '消息主题';
COMMENT ON COLUMN device_message.seq IS '设备本地单调递增序号（同一设备内单调）';
COMMENT ON COLUMN device_message.priority IS '业务优先级，数值越大优先级越高';
COMMENT ON COLUMN device_message.payload IS '消息业务负载（合成文本，可为 NULL）';
COMMENT ON COLUMN device_message.status IS '状态：PENDING=待发，INFLIGHT=已投递待 ACK，FORWARDED=已明确确认';
COMMENT ON COLUMN device_message.attempts IS '已尝试投递次数，初始为 0';
COMMENT ON COLUMN device_message.enqueued_at IS '入队（写入本地）时间，Unix 毫秒时间戳';
COMMENT ON COLUMN device_message.inflight_at IS '最近一次进入 in-flight 的时间，NULL=当前不在投递中';
COMMENT ON COLUMN device_message.forwarded_at IS '最近一次成功投递给上游适配器的时间，NULL=尚未投递成功';
COMMENT ON COLUMN device_message.acked_at IS '收到明确 ACK 的时间，NULL=未确认';

COMMENT ON TABLE audit_record IS '丢弃、覆盖、驱逐、拒绝等审计记录表';
COMMENT ON COLUMN audit_record.event_type IS '事件类型：DROP_POLICY/OVERWRITE_LATEST/EVICT_BOUNDED/EVICT_CAPACITY/REJECT_CAPACITY';
COMMENT ON COLUMN audit_record.message_id IS '被处置消息的 dedupKey，可能为 NULL（如容量拒绝时新消息未持久化则记录请求键）';
COMMENT ON COLUMN audit_record.reason IS '处置或拒绝原因的文字说明';
COMMENT ON COLUMN audit_record.created_at IS '审计事件时间，Unix 毫秒时间戳（Asia/Shanghai）';
