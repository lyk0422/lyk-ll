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
    warning_level VARCHAR(10) NOT NULL DEFAULT 'ORANGE',
    request_id VARCHAR(64) NOT NULL,
    opened_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP
);
COMMENT ON TABLE warning_chain IS '降雨预警/险情处置链：险情报送与方案在同一链上，终态为 ARCHIVED';
COMMENT ON COLUMN warning_chain.status IS '处置链状态：ACTIVE 生效中；ARCHIVED 已归档（终态）';
COMMENT ON COLUMN warning_chain.warning_level IS '预警级别：BLUE/YELLOW/ORANGE/RED，滚动演算泄流能力不足时自动逐级提升，RED 为最高';
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
    routing_id BIGINT,
    rolling_id BIGINT,
    request_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    openings CLOB NOT NULL,
    total_release DECIMAL(14, 3) NOT NULL,
    downstream_safe_release DECIMAL(14, 3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    active_key BIGINT,
    created_at TIMESTAMP NOT NULL,
    superseded_at TIMESTAMP,
    completed_at TIMESTAMP,
    archived_at TIMESTAMP
);
COMMENT ON TABLE release_plan IS '泄流方案：同一尾矿库同时刻只允许一个执行中方案；滚动演算可追加新版本，旧版本留痕 SUPERSEDED';
COMMENT ON COLUMN release_plan.routing_id IS '依据的静态调洪演算主键；滚动演算自动追加的版本可为空';
COMMENT ON COLUMN release_plan.rolling_id IS '触发本版本的滚动演算主键，人工制定版本为空';
COMMENT ON COLUMN release_plan.version_no IS '方案版本号，同一处置链内自 1 递增，滚动演算泄流不足时追加新版本';
COMMENT ON COLUMN release_plan.openings IS '孔口组合 JSON：名称、开度(米)、泄量(立方米每秒)';
COMMENT ON COLUMN release_plan.total_release IS '方案总泄量，立方米每秒，恰等下游安全泄量合法';
COMMENT ON COLUMN release_plan.status IS 'EFFECTIVE 生效中 / SUPERSEDED 已被新版本取代（留痕） / COMPLETED 已回执完成 / ARCHIVED 已归档终态';
COMMENT ON COLUMN release_plan.active_key IS '执行中唯一约束位：执行中为 pond_id，否则为空，空值可多行';
COMMENT ON COLUMN release_plan.superseded_at IS '被滚动演算新版本取代的时刻，未取代为空';

CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_no ON release_plan (plan_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_request ON release_plan (request_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_active ON release_plan (active_key);

CREATE TABLE IF NOT EXISTS release_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    plan_id BIGINT NOT NULL,
    plan_version_no INT NOT NULL,
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
COMMENT ON TABLE release_order IS '泄流指令链：下达→签收→执行→回执，签收时限恰 15 分钟升级；指令只认创建时的方案版本';
COMMENT ON COLUMN release_order.plan_version_no IS '指令创建时的方案版本号快照，后续新版本不溯及在途指令';
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

CREATE TABLE IF NOT EXISTS inflow_hydrograph_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warning_id BIGINT NOT NULL,
    forecast_time TIMESTAMP NOT NULL,
    inflow_rate DECIMAL(14, 3) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE inflow_hydrograph_point IS '入库洪水过程线点：处置链维度，按预报时刻给出入库流量，滚动演算的源输入';
COMMENT ON COLUMN inflow_hydrograph_point.forecast_time IS '预报时刻（时区 Asia/Shanghai），同一处置链内唯一';
COMMENT ON COLUMN inflow_hydrograph_point.inflow_rate IS '该时刻入库流量，立方米每秒';
COMMENT ON COLUMN inflow_hydrograph_point.request_id IS '录入该点的幂等键';

CREATE UNIQUE INDEX IF NOT EXISTS uk_hydrograph_point ON inflow_hydrograph_point (warning_id, forecast_time);

CREATE TABLE IF NOT EXISTS rolling_routing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rolling_no VARCHAR(64) NOT NULL,
    warning_id BIGINT NOT NULL,
    pond_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    previous_id BIGINT,
    scheduled_time TIMESTAMP NOT NULL,
    current_water_level DECIMAL(12, 4) NOT NULL,
    current_storage DECIMAL(18, 4) NOT NULL,
    max_storage DECIMAL(18, 4) NOT NULL,
    available_capacity DECIMAL(18, 4) NOT NULL,
    inflow_rate DECIMAL(14, 3) NOT NULL,
    period_inflow_volume DECIMAL(18, 4) NOT NULL,
    release_capacity DECIMAL(14, 3) NOT NULL,
    required_release DECIMAL(14, 3) NOT NULL,
    projected_storage DECIMAL(18, 4) NOT NULL,
    release_judgment VARCHAR(20) NOT NULL,
    dry_beach_length DECIMAL(12, 3),
    phreatic_depth DECIMAL(12, 3),
    dry_beach_judgment VARCHAR(20),
    phreatic_judgment VARCHAR(20),
    danger_judgment VARCHAR(20) NOT NULL,
    warning_level_after VARCHAR(10) NOT NULL,
    plan_version_after INT,
    input_snapshot CLOB NOT NULL,
    criterion VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE rolling_routing IS '滚动调洪演算版本：按入库洪水过程线每 30 分钟重算一次，恰满 30 分钟触发；旧版本保留可查，任一版本可按输入快照单独复算';
COMMENT ON COLUMN rolling_routing.version_no IS '演算版本号，同一处置链内自 1 递增';
COMMENT ON COLUMN rolling_routing.previous_id IS '上一版本主键，首版本为空';
COMMENT ON COLUMN rolling_routing.scheduled_time IS '本次演算基准时刻，相邻版本间隔恰满 30 分钟方可触发新版本';
COMMENT ON COLUMN rolling_routing.inflow_rate IS '基准时刻入库流量（过程线取值，点间线性插值、界外取端点），立方米每秒';
COMMENT ON COLUMN rolling_routing.period_inflow_volume IS '本时段（30 分钟）入库洪量，立方米';
COMMENT ON COLUMN rolling_routing.release_capacity IS '当前生效方案泄流能力，立方米每秒，无方案时为 0';
COMMENT ON COLUMN rolling_routing.required_release IS '过程线剩余段峰值入库流量（泄流能力需求），立方米每秒';
COMMENT ON COLUMN rolling_routing.projected_storage IS '下一演算时刻预计蓄水量=当前蓄水+时段入库-时段泄流（下限 0），立方米';
COMMENT ON COLUMN rolling_routing.release_judgment IS '泄流能力判定：SUFFICIENT 足够 / CRITICAL 恰等临界 / INSUFFICIENT 不足（触发提级与方案追加）';
COMMENT ON COLUMN rolling_routing.danger_judgment IS '综合判定：SAFE / CRITICAL / RELEASE_REQUIRED / DANGER，口径同静态演算';
COMMENT ON COLUMN rolling_routing.warning_level_after IS '本次演算后的预警级别（泄流不足自动提升后取值）';
COMMENT ON COLUMN rolling_routing.plan_version_after IS '本次演算后当前方案版本号，无方案为空';
COMMENT ON COLUMN rolling_routing.input_snapshot IS '源输入快照 JSON（含过程线、库容曲线、泄流能力等全部输入），供任一版本单独复算';
COMMENT ON COLUMN rolling_routing.criterion IS '判定口径文字说明（边界口径）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_rolling_no ON rolling_routing (rolling_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rolling_request ON rolling_routing (request_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rolling_version ON rolling_routing (warning_id, version_no);

CREATE TABLE IF NOT EXISTS warning_level_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warning_id BIGINT NOT NULL,
    from_level VARCHAR(10) NOT NULL,
    to_level VARCHAR(10) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    rolling_id BIGINT,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE warning_level_event IS '预警级别变更留痕：滚动演算泄流能力不足时自动提升，与方案版本追加同事务';
COMMENT ON COLUMN warning_level_event.from_level IS '变更前级别；已为最高级 RED 时与 to_level 相同';

CREATE TABLE IF NOT EXISTS order_conflict_suggestion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    suggestion_no VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    old_plan_id BIGINT NOT NULL,
    new_plan_id BIGINT NOT NULL,
    old_version_no INT NOT NULL,
    new_version_no INT NOT NULL,
    suggestion_detail VARCHAR(1024) NOT NULL,
    status VARCHAR(20) NOT NULL,
    decision VARCHAR(30),
    confirmed_by VARCHAR(64),
    confirmed_at TIMESTAMP,
    confirm_note VARCHAR(1024),
    confirm_request_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE order_conflict_suggestion IS '在途指令与新版方案冲突的替代建议：人工确认留痕可查，确认幂等';
COMMENT ON COLUMN order_conflict_suggestion.suggestion_detail IS '替代建议内容（维持原版本执行或按新版本另行下达）';
COMMENT ON COLUMN order_conflict_suggestion.status IS 'PENDING 待确认 / CONFIRMED 已人工确认';
COMMENT ON COLUMN order_conflict_suggestion.decision IS '人工确认决策：ADOPT_NEW_VERSION 采纳新版本 / KEEP_ORIGINAL_VERSION 维持原版本';
COMMENT ON COLUMN order_conflict_suggestion.confirm_request_id IS '人工确认的幂等键';

CREATE UNIQUE INDEX IF NOT EXISTS uk_suggestion_no ON order_conflict_suggestion (suggestion_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_suggestion_order_plan ON order_conflict_suggestion (order_id, new_plan_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_suggestion_confirm_req ON order_conflict_suggestion (confirm_request_id);
