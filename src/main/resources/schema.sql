-- micro-ROS 边缘离线转发器建表脚本（H2 MySQL 兼容模式 / MySQL 通用）
-- 时间字段均为 Asia/Shanghai 本地时间戳（TIMESTAMP），由应用 Clock 写入。

-- 待发 / 在途 / 已确认消息表
-- status: PENDING(待补发) / IN_FLIGHT(已发送待确认) / ACKED(已确认，不再转发)
CREATE TABLE IF NOT EXISTS forwarder_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    device_id VARCHAR(128) NOT NULL COMMENT '设备 ID',
    topic VARCHAR(255) NOT NULL COMMENT 'ROS 2 Topic',
    device_seq BIGINT NOT NULL COMMENT '设备本地单调序号',
    priority INT NOT NULL COMMENT '业务优先级，数值越大越优先补发',
    dedup_key VARCHAR(255) NOT NULL COMMENT '全局唯一去重键，同一键只保留一条待发记录',
    payload CLOB COMMENT '消息体（合成数据）',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING/IN_FLIGHT/ACKED',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已尝试转发次数',
    in_flight_since TIMESTAMP NULL COMMENT '最近一次置为 IN_FLIGHT 的时间，NULL 表示非在途',
    created_at TIMESTAMP NOT NULL COMMENT '入库时间',
    updated_at TIMESTAMP NOT NULL COMMENT '最近更新时间',
    CONSTRAINT uk_forwarder_dedup UNIQUE (dedup_key)
) COMMENT='离线转发消息表';

CREATE INDEX IF NOT EXISTS idx_forwarder_drain
    ON forwarder_message (status, priority, device_seq);
CREATE INDEX IF NOT EXISTS idx_forwarder_topic
    ON forwarder_message (topic, status, created_at);

-- 审计表：策略丢弃/覆盖、容量驱逐、拒绝、转发、确认、恢复等事件
CREATE TABLE IF NOT EXISTS forwarder_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：DROPPED_BY_POLICY/OVERWRITTEN/EVICTED/REJECTED_CAPACITY/SENT/ACKED/RECOVERED/REQUEUED',
    device_id VARCHAR(128) NULL COMMENT '相关设备 ID，可为空',
    topic VARCHAR(255) NULL COMMENT '相关 Topic，可为空',
    device_seq BIGINT NULL COMMENT '相关设备序号，可为空',
    dedup_key VARCHAR(255) NULL COMMENT '相关去重键，可为空',
    detail VARCHAR(1024) NOT NULL COMMENT '事件原因说明',
    created_at TIMESTAMP NOT NULL COMMENT '事件时间'
) COMMENT='转发器审计表';

CREATE INDEX IF NOT EXISTS idx_forwarder_audit_type
    ON forwarder_audit (event_type, created_at);
