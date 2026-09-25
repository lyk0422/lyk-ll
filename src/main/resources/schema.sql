-- 尾矿库调洪演算与泄流指令服务：H2（MODE=MySQL）建表脚本
-- 所有金额/水量均使用 DECIMAL；时间列统一使用 TIMESTAMP，业务时区为 Asia/Shanghai。

CREATE TABLE IF NOT EXISTS pond (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pond_code VARCHAR(64) NOT NULL,
    pond_name VARCHAR(128) NOT NULL,
    downstream_safe_release DECIMAL(14, 3) NOT NULL,
    dry_beach_limit DECIMAL(12, 3) NOT NULL,
    phreatic_limit DECIMAL(12, 3) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE pond IS '尾矿库基础档案（合成数据）';
COMMENT ON COLUMN pond.pond_code IS '尾矿库唯一编码';
COMMENT ON COLUMN pond.downstream_safe_release IS '下游安全泄量，立方米每秒，方案总泄量恰等于该值合法';
COMMENT ON COLUMN pond.dry_beach_limit IS '干滩长度安全下限，米，恰等判临界、低于判险';
COMMENT ON COLUMN pond.phreatic_limit IS '浸润线埋深安全下限，米，恰等临界、低于判险';

CREATE UNIQUE INDEX IF NOT EXISTS uk_pond_code ON pond (pond_code);

CREATE TABLE IF NOT EXISTS storage_curve_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pond_id BIGINT NOT NULL,
    water_level DECIMAL(12, 4) NOT NULL,
    storage DECIMAL(18, 4) NOT NULL
);
COMMENT ON TABLE storage_curve_point IS '库容曲线点：库水位标高-蓄水量对照（合成数据，线性插值）';
COMMENT ON COLUMN storage_curve_point.water_level IS '库水位标高，米';
COMMENT ON COLUMN storage_curve_point.storage IS '对应蓄水量，立方米';

CREATE UNIQUE INDEX IF NOT EXISTS uk_curve_point ON storage_curve_point (pond_id, water_level);

CREATE TABLE IF NOT EXISTS rainfall_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pond_id BIGINT NOT NULL,
    station_code VARCHAR(64) NOT NULL,
    sample_time TIMESTAMP NOT NULL,
    rainfall_depth DECIMAL(12, 3) NOT NULL,
    duration_minutes INT NOT NULL,
    recorded_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE rainfall_reading IS '降雨读数：按 测点+采样时刻 去重，乱序按时刻归位';
COMMENT ON COLUMN rainfall_reading.station_code IS '雨量测点编码';
COMMENT ON COLUMN rainfall_reading.sample_time IS '采样时刻（去重键，时区 Asia/Shanghai）';
COMMENT ON COLUMN rainfall_reading.rainfall_depth IS '时段降雨量，毫米';
COMMENT ON COLUMN rainfall_reading.duration_minutes IS '降雨历时，分钟';
COMMENT ON COLUMN rainfall_reading.recorded_at IS '系统接收时刻，用于判定迟到';

CREATE UNIQUE INDEX IF NOT EXISTS uk_rainfall_key ON rainfall_reading (pond_id, station_code, sample_time);

CREATE TABLE IF NOT EXISTS water_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pond_id BIGINT NOT NULL,
    station_code VARCHAR(64) NOT NULL,
    sample_time TIMESTAMP NOT NULL,
    water_level DECIMAL(12, 4) NOT NULL,
    dry_beach_length DECIMAL(12, 3),
    phreatic_depth DECIMAL(12, 3),
    recorded_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE water_reading IS '水位/监测读数：按 测点+采样时刻 去重，乱序按时刻归位';
COMMENT ON COLUMN water_reading.station_code IS '监测测点编码';
COMMENT ON COLUMN water_reading.sample_time IS '采样时刻（去重键，时区 Asia/Shanghai）';
COMMENT ON COLUMN water_reading.water_level IS '实测库水位标高，米';
COMMENT ON COLUMN water_reading.dry_beach_length IS '干滩长度，米，空值表示该测点未测';
COMMENT ON COLUMN water_reading.phreatic_depth IS '浸润线埋深，米，空值表示该测点未测';

CREATE UNIQUE INDEX IF NOT EXISTS uk_water_key ON water_reading (pond_id, station_code, sample_time);

CREATE TABLE IF NOT EXISTS warning_chain (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warning_no VARCHAR(64) NOT NULL,
    pond_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    rain_summary VARCHAR(1024),
    status VARCHAR(20) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    opened_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP
);
COMMENT ON TABLE warning_chain IS '降雨预警/险情处置链：险情报送与方案在同一链上，终态为 ARCHIVED';
COMMENT ON COLUMN warning_chain.status IS '处置链状态：ACTIVE 生效中；ARCHIVED 已归档（终态）';
COMMENT ON COLUMN warning_chain.request_id IS '创建预警的幂等键';

CREATE UNIQUE INDEX IF NOT EXISTS uk_warning_no ON warning_chain (warning_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_warning_request ON warning_chain (request_id);

CREATE TABLE IF NOT EXISTS flood_routing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    routing_no VARCHAR(64) NOT NULL,
    pond_id BIGINT NOT NULL,
    warning_id BIGINT,
    request_id VARCHAR(64) NOT NULL,
    current_water_level DECIMAL(12, 4) NOT NULL,
    current_storage DECIMAL(18, 4) NOT NULL,
    max_storage DECIMAL(18, 4) NOT NULL,
    available_capacity DECIMAL(18, 4) NOT NULL,
    design_flood_volume DECIMAL(18, 4) NOT NULL,
    capacity_judgment VARCHAR(20) NOT NULL,
    dry_beach_length DECIMAL(12, 3),
    phreatic_depth DECIMAL(12, 3),
    dry_beach_judgment VARCHAR(20),
    phreatic_judgment VARCHAR(20),
    danger_judgment VARCHAR(20) NOT NULL,
    input_snapshot CLOB NOT NULL,
    criterion VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE flood_routing IS '调洪演算记录：输入快照、判定口径可复算';
COMMENT ON COLUMN flood_routing.available_capacity IS '当前水位反查的可用调洪库容，立方米';
COMMENT ON COLUMN flood_routing.design_flood_volume IS '设计洪水量，立方米';
COMMENT ON COLUMN flood_routing.capacity_judgment IS '库容判定：SAFE 不足安全 / CRITICAL 恰等临界 / RELEASE_REQUIRED 超出须泄流';
COMMENT ON COLUMN flood_routing.danger_judgment IS '综合判定：SAFE 安全 / CRITICAL 临界 / RELEASE_REQUIRED 须泄流 / DANGER 险（双指标任一低于下限）';
COMMENT ON COLUMN flood_routing.input_snapshot IS '演算全部输入字段快照（JSON），供复算';
COMMENT ON COLUMN flood_routing.criterion IS '判定口径文字说明（边界口径）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_routing_no ON flood_routing (routing_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_routing_request ON flood_routing (request_id);

CREATE TABLE IF NOT EXISTS release_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_no VARCHAR(64) NOT NULL,
    pond_id BIGINT NOT NULL,
    warning_id BIGINT NOT NULL,
    routing_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    openings CLOB NOT NULL,
    total_release DECIMAL(14, 3) NOT NULL,
    downstream_safe_release DECIMAL(14, 3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    active_key BIGINT,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    archived_at TIMESTAMP
);
COMMENT ON TABLE release_plan IS '泄流方案：同一尾矿库同时刻只允许一个执行中方案';
COMMENT ON COLUMN release_plan.openings IS '孔口组合 JSON：名称、开度(米)、泄量(立方米每秒)';
COMMENT ON COLUMN release_plan.total_release IS '方案总泄量，立方米每秒，恰等下游安全泄量合法';
COMMENT ON COLUMN release_plan.status IS 'EFFECTIVE 生效中 / COMPLETED 已回执完成 / ARCHIVED 已归档终态';
COMMENT ON COLUMN release_plan.active_key IS '执行中唯一约束位：执行中为 pond_id，否则为空，空值可多行';

CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_no ON release_plan (plan_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_request ON release_plan (request_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_active ON release_plan (active_key);

CREATE TABLE IF NOT EXISTS release_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    plan_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    sign_deadline TIMESTAMP NOT NULL,
    signed_at TIMESTAMP,
    signer VARCHAR(64),
    escalated_at TIMESTAMP,
    escalated_to VARCHAR(64),
    executed_at TIMESTAMP,
    executor VARCHAR(64),
    receipted_at TIMESTAMP,
    actual_openings CLOB,
    actual_total_release DECIMAL(14, 3),
    deviation_ratio DECIMAL(9, 6),
    deviation_reason VARCHAR(1024),
    receipt_request_id VARCHAR(64)
);
COMMENT ON TABLE release_order IS '泄流指令链：下达→签收→执行→回执，签收时限恰 15 分钟升级';
COMMENT ON COLUMN release_order.status IS 'ISSUED 下达 / SIGNED 签收 / EXECUTING 执行 / RECEIPTED 回执 / ESCALATED 已升级值班领导';
COMMENT ON COLUMN release_order.sign_deadline IS '签收时限=下达时刻+15 分钟，恰满未签收即升级';
COMMENT ON COLUMN release_order.deviation_ratio IS '回执偏差比例=|实际-方案|/方案，恰 0.10 视为一致';
COMMENT ON COLUMN release_order.deviation_reason IS '偏差超 10% 时必填的原因说明';

CREATE UNIQUE INDEX IF NOT EXISTS uk_order_no ON release_order (order_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_order_receipt_request ON release_order (receipt_request_id);

CREATE TABLE IF NOT EXISTS order_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    event_time TIMESTAMP NOT NULL,
    detail VARCHAR(1024)
);
COMMENT ON TABLE order_event IS '指令链状态事件流（下达/签收/升级/执行/回执），可复算';

CREATE TABLE IF NOT EXISTS review_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warning_id BIGINT NOT NULL,
    plan_id BIGINT,
    order_id BIGINT,
    note VARCHAR(1024),
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE review_record IS '复盘归档记录：归档为终态，重复归档幂等返回首次结果';

CREATE UNIQUE INDEX IF NOT EXISTS uk_review_warning ON review_record (warning_id);

CREATE TABLE IF NOT EXISTS late_reading_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pond_id BIGINT NOT NULL,
    reading_type VARCHAR(10) NOT NULL,
    station_code VARCHAR(64) NOT NULL,
    sample_time TIMESTAMP NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    payload CLOB NOT NULL
);
COMMENT ON TABLE late_reading_event IS '迟到读数事件：处置链归档后送达的读数只登记事件，不入读数表';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(64) NOT NULL,
    biz_type VARCHAR(30) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    ref_id BIGINT,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (request_id)
);
COMMENT ON TABLE idempotent_request IS '幂等记录：指纹覆盖 requestId 与全部业务字段，同键异参冲突';
COMMENT ON COLUMN idempotent_request.fingerprint IS 'SHA-256 指纹（含 requestId 及全部业务字段的规范化 JSON）';
