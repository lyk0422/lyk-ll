-- 换电站电池资产流转台账建表脚本（H2 MySQL 兼容模式，自动执行）
-- 时间字段统一为 UTC 时间戳（epoch 毫秒），空值含义见各列注释。

CREATE TABLE IF NOT EXISTS battery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    code VARCHAR(64) NOT NULL COMMENT '电池唯一编码，全局唯一',
    status VARCHAR(32) NOT NULL COMMENT '状态：PENDING_INSPECTION 在库待检 / CHARGING 充电待修 / LISTED 上架可换 / IN_USE 换出在用 / IN_TRANSIT 调拨在途 / TRANSFER_EXCEPTION 调拨异常',
    station_id VARCHAR(64) COMMENT '当前所在站点，空值表示在车辆侧或调拨在途',
    vehicle_id VARCHAR(64) COMMENT '当前绑定车辆，空值表示未绑定',
    soc INT COMMENT '最近一次检测 SOC（百分比 0-100），空值表示未检测',
    health INT COMMENT '最近一次检测健康度（百分比 0-100），空值表示未检测',
    inspection_batch BIGINT COMMENT '最近一次检测批次号，空值表示未检测',
    created_at BIGINT NOT NULL COMMENT '建档时间（UTC epoch 毫秒）',
    updated_at BIGINT NOT NULL COMMENT '最近更新时间（UTC epoch 毫秒）',
    CONSTRAINT uk_battery_code UNIQUE (code)
) COMMENT='电池资产台账';

CREATE TABLE IF NOT EXISTS battery_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    battery_code VARCHAR(64) NOT NULL COMMENT '电池编码',
    seq BIGINT NOT NULL COMMENT '该电池事件序号，从 1 开始单调递增',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：REGISTER 建档 / INBOUND 入站 / INSPECTION 检测 / LISTED 上架 / SWAP_OUT 换出 / TRANSFER_OUT 调拨发出 / TRANSFER_SIGN 到站签收 / TRANSFER_EXCEPTION 调拨异常 / WRITE_OFF 异常核销 / ALARM 告警',
    payload VARCHAR(2048) NOT NULL COMMENT '事件业务内容（JSON）',
    prev_hash VARCHAR(64) NOT NULL COMMENT '上一事件哈希，创世事件为 64 个 0',
    hash VARCHAR(64) NOT NULL COMMENT '本事件哈希（SHA-256，十六进制）',
    created_at BIGINT NOT NULL COMMENT '事件时间（UTC epoch 毫秒）',
    CONSTRAINT uk_battery_event_seq UNIQUE (battery_code, seq)
) COMMENT='电池流转事件链';

CREATE TABLE IF NOT EXISTS transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键，调拨单号',
    battery_code VARCHAR(64) NOT NULL COMMENT '调拨电池编码',
    from_station VARCHAR(64) NOT NULL COMMENT '调出站点',
    to_station VARCHAR(64) NOT NULL COMMENT '调入站点',
    status VARCHAR(32) NOT NULL COMMENT '状态：IN_TRANSIT 在途 / SIGNED 已签收 / EXCEPTION 调拨异常 / WRITTEN_OFF 已核销',
    initiated_at BIGINT NOT NULL COMMENT '发起时间（UTC epoch 毫秒）',
    signed_at BIGINT COMMENT '签收时间（UTC epoch 毫秒），未签收为空',
    exception_at BIGINT COMMENT '转异常时间（UTC epoch 毫秒），未异常为空',
    written_off_at BIGINT COMMENT '人工核销时间（UTC epoch 毫秒），未核销为空'
) COMMENT='站间调拨单';

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INBOUND / INSPECTION / SWAP_OUT / TRANSFER',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹（requestId 与全部业务字段的 SHA-256）',
    response VARCHAR(4096) NOT NULL COMMENT '首次处理结果快照（JSON）',
    created_at BIGINT NOT NULL COMMENT '记录时间（UTC epoch 毫秒）',
    CONSTRAINT uk_idempotency_request UNIQUE (request_id)
) COMMENT='幂等请求记录';

CREATE TABLE IF NOT EXISTS alarm (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    battery_code VARCHAR(64) NOT NULL COMMENT '告警电池编码',
    reason VARCHAR(512) NOT NULL COMMENT '告警原因',
    created_at BIGINT NOT NULL COMMENT '告警时间（UTC epoch 毫秒）'
) COMMENT='链不一致等异常告警';
