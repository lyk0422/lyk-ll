-- 换电资产台账建表脚本（H2 MySQL 兼容模式；时间均为 UTC）。

-- 电池资产主表
CREATE TABLE IF NOT EXISTS battery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE COMMENT '电池唯一编码',
    status VARCHAR(32) NOT NULL COMMENT '资产状态：REGISTERED/AT_STATION/AVAILABLE/CHARGING/IN_USE/IN_TRANSIT/TRANSFER_EXCEPTION/WRITTEN_OFF',
    current_station VARCHAR(64) NULL COMMENT '当前所在站点编码；空值表示不在站（在用/在途）',
    bound_vehicle VARCHAR(64) NULL COMMENT '绑定车辆编码；空值表示未绑定车辆',
    head_event_hash VARCHAR(64) NULL COMMENT '事件链最新哈希；空值表示尚无事件',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at TIMESTAMP NOT NULL COMMENT '建档时间（UTC）',
    updated_at TIMESTAMP NOT NULL COMMENT '最近更新时间（UTC）'
);

-- 电池流转事件（哈希链）
CREATE TABLE IF NOT EXISTS ledger_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    battery_code VARCHAR(64) NOT NULL COMMENT '电池唯一编码',
    seq BIGINT NOT NULL COMMENT '链内序号，从 1 递增',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型 EventType',
    prev_hash VARCHAR(64) NOT NULL COMMENT '上一事件哈希；首事件为 64 个 0',
    event_hash VARCHAR(64) NOT NULL UNIQUE COMMENT '本事件内容计算出的 SHA-256 哈希',
    payload VARCHAR(4000) NOT NULL COMMENT '事件业务字段规范化 JSON',
    request_id VARCHAR(64) NULL COMMENT '产生该事件的幂等请求标识',
    occurred_at TIMESTAMP NOT NULL COMMENT '事件发生时间（UTC）',
    CONSTRAINT uk_event_chain UNIQUE (battery_code, seq)
);
CREATE INDEX IF NOT EXISTS idx_event_battery ON ledger_event (battery_code, seq);

-- 站间调拨单
CREATE TABLE IF NOT EXISTS transfer_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE COMMENT '调拨单号',
    battery_code VARCHAR(64) NOT NULL COMMENT '调拨电池编码',
    from_station VARCHAR(64) NOT NULL COMMENT '调出站点编码（发起方）',
    to_station VARCHAR(64) NOT NULL COMMENT '调入站点编码（签收方）',
    status VARCHAR(32) NOT NULL COMMENT '调拨状态：DISPATCHED/RECEIVED/EXCEPTION/WRITTEN_OFF',
    dispatched_at TIMESTAMP NOT NULL COMMENT '调出时间（UTC），在途计时起点',
    received_at TIMESTAMP NULL COMMENT '签收时间（UTC）；空值表示尚未签收',
    exception_at TIMESTAMP NULL COMMENT '超时转异常时间（UTC）；空值表示正常',
    written_off_at TIMESTAMP NULL COMMENT '人工核销时间（UTC）；空值表示未核销，且终态仅允许一次',
    writeoff_reason VARCHAR(512) NULL COMMENT '核销原因；空值表示未核销',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号'
);
CREATE INDEX IF NOT EXISTS idx_transfer_battery ON transfer_order (battery_code, status);

-- 幂等请求记录
CREATE TABLE IF NOT EXISTS idempotent_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '请求唯一标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型',
    fingerprint VARCHAR(64) NOT NULL COMMENT '覆盖 requestId 与全部业务字段的 SHA-256 指纹',
    result_status INT NOT NULL COMMENT '首次响应 HTTP 状态码',
    result_body VARCHAR(4000) NOT NULL COMMENT '首次响应体 JSON',
    created_at TIMESTAMP NOT NULL COMMENT '首次请求时间（UTC）'
);

-- 链不一致告警记录
CREATE TABLE IF NOT EXISTS chain_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    battery_code VARCHAR(64) NOT NULL COMMENT '被拒绝入站的电池编码',
    station_code VARCHAR(64) NOT NULL COMMENT '尝试入站的站点编码',
    reason VARCHAR(512) NOT NULL COMMENT '告警原因（链校验失败详情）',
    request_id VARCHAR(64) NULL COMMENT '触发告警的请求标识',
    created_at TIMESTAMP NOT NULL COMMENT '告警时间（UTC）'
);
