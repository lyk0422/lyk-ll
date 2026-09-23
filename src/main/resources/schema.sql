-- micro-ROS 离线转发器持久化结构（H2 MySQL 兼容模式，文件库支持跨进程恢复）
-- 重启安全：全部使用 IF NOT EXISTS。

CREATE TABLE IF NOT EXISTS device_message (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_key         VARCHAR(64)  NOT NULL UNIQUE COMMENT '消息内部标识 UUID',
    device_id           VARCHAR(64)  NOT NULL COMMENT '设备 ID',
    topic               VARCHAR(128) NOT NULL COMMENT 'micro-ROS 话题名',
    seq                 BIGINT       NOT NULL COMMENT '设备本地单调序号（设备内单调递增，无单位）',
    priority            INT          NOT NULL COMMENT '业务优先级，数值越大越优先补发',
    dedup_key           VARCHAR(128) NOT NULL UNIQUE COMMENT '全局唯一去重键',
    payload             CLOB         NULL COMMENT '消息业务载荷（合成数据）',
    status              VARCHAR(16)  NOT NULL COMMENT 'PENDING 待发；IN_FLIGHT 在途；ACKNOWLEDGED 已确认',
    received_at         BIGINT       NOT NULL COMMENT '接入时间，epoch 毫秒（UTC）',
    inflight_deadline   BIGINT       NULL COMMENT '在途 ACK 截止时间，epoch 毫秒；NULL 表示非在途',
    dispatched_count    INT          NOT NULL DEFAULT 0 COMMENT '累计补发次数，0 表示从未发送',
    last_dispatched_at  BIGINT       NULL COMMENT '最近一次补发时间，epoch 毫秒',
    acked_at            BIGINT       NULL COMMENT 'ACK 确认时间，epoch 毫秒；NULL 未确认'
);
CREATE INDEX IF NOT EXISTS idx_message_status ON device_message (status);
CREATE INDEX IF NOT EXISTS idx_message_pending_order
    ON device_message (status, priority DESC, device_id, seq, id);
CREATE INDEX IF NOT EXISTS idx_message_topic_status ON device_message (topic, status);

CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type  VARCHAR(32)  NOT NULL COMMENT '事件类型：DROP_POLICY/OVERWRITE_LATEST/EVICT_BOUNDED/EVICT_CAPACITY/REJECT_CAPACITY/TIMEOUT_RETRY/RESET_INFLIGHT/DISPATCH/ACK',
    dedup_key   VARCHAR(128) NULL COMMENT '关联消息去重键；拒绝/丢弃事件取新消息键',
    device_id   VARCHAR(64)  NULL COMMENT '关联设备 ID',
    topic       VARCHAR(128) NULL COMMENT '关联话题',
    seq         BIGINT       NULL COMMENT '关联设备本地序号',
    priority    INT          NULL COMMENT '关联优先级',
    detail      VARCHAR(1024) NULL COMMENT '原因说明，如容量不足、被覆盖/驱逐记录的 dedupKey',
    created_at  BIGINT       NOT NULL COMMENT '事件时间，epoch 毫秒（UTC）'
);
CREATE INDEX IF NOT EXISTS idx_audit_type ON audit_log (event_type);
CREATE INDEX IF NOT EXISTS idx_audit_topic ON audit_log (topic);
