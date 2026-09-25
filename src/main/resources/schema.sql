-- 航班载重平衡舱单：H2（MODE=MySQL）建表脚本，仅用于本地/测试内存库。
-- 重量单位 kg，力臂单位 m（自基准点起），力矩单位 kg·m；所有重量/力矩非空字段默认 0 表示无该项。

CREATE TABLE IF NOT EXISTS aircraft_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aircraft_type VARCHAR(32) NOT NULL,
    max_zero_fuel_weight_kg DECIMAL(12,2) NOT NULL,
    operating_empty_weight_kg DECIMAL(12,2) NOT NULL,
    operating_empty_moment DECIMAL(18,2) NOT NULL,
    default_fuel_arm DECIMAL(10,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_aircraft_type UNIQUE (aircraft_type)
);
COMMENT ON TABLE aircraft_config IS '机型合成性能数据：最大无油重量与使用空重（kg，UTC 无关的静态数据）';
COMMENT ON COLUMN aircraft_config.max_zero_fuel_weight_kg IS '最大无油重量 MZFW，单位 kg';
COMMENT ON COLUMN aircraft_config.operating_empty_weight_kg IS '使用空重 OEW，单位 kg';
COMMENT ON COLUMN aircraft_config.operating_empty_moment IS '使用空重力矩，单位 kg·m';
COMMENT ON COLUMN aircraft_config.default_fuel_arm IS '默认燃油合力力臂，单位 m；加油变更未传力臂时取此值';

CREATE TABLE IF NOT EXISTS envelope_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_id BIGINT NOT NULL,
    seq_no INT NOT NULL,
    weight_kg DECIMAL(12,2) NOT NULL,
    forward_arm DECIMAL(10,4) NOT NULL,
    aft_arm DECIMAL(10,4) NOT NULL,
    CONSTRAINT fk_envelope_config FOREIGN KEY (config_id) REFERENCES aircraft_config(id)
);
COMMENT ON TABLE envelope_point IS '合成重心包线折线点：按重量插值的前/后限力臂，恰在边界合法';
COMMENT ON COLUMN envelope_point.weight_kg IS '该折点对应重量，单位 kg';
COMMENT ON COLUMN envelope_point.forward_arm IS '该重量下前限重心力臂，单位 m，含边界';
COMMENT ON COLUMN envelope_point.aft_arm IS '该重量下后限重心力臂，单位 m，含边界';

CREATE TABLE IF NOT EXISTS manifest (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    flight_key VARCHAR(80) NOT NULL,
    flight_no VARCHAR(16) NOT NULL,
    flight_date DATE NOT NULL,
    aircraft_type VARCHAR(32) NOT NULL,
    aircraft_registration VARCHAR(16) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    parent_id BIGINT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    fuel_kg DECIMAL(12,2) NOT NULL DEFAULT 0,
    fuel_arm DECIMAL(10,4) NOT NULL DEFAULT 0,
    fuel_moment DECIMAL(18,2) NOT NULL DEFAULT 0,
    passenger_count INT NOT NULL DEFAULT 0,
    payload_kg DECIMAL(12,2) NOT NULL DEFAULT 0,
    payload_moment DECIMAL(18,2) NOT NULL DEFAULT 0,
    zero_fuel_weight_kg DECIMAL(12,2) NOT NULL DEFAULT 0,
    zfw_moment DECIMAL(18,2) NOT NULL DEFAULT 0,
    zfw_cg_arm DECIMAL(12,6) NOT NULL DEFAULT 0,
    takeoff_weight_kg DECIMAL(12,2) NOT NULL DEFAULT 0,
    tow_moment DECIMAL(18,2) NOT NULL DEFAULT 0,
    tow_cg_arm DECIMAL(12,6) NOT NULL DEFAULT 0,
    lmc_delta_pax INT NOT NULL DEFAULT 0,
    lmc_delta_kg DECIMAL(12,2) NOT NULL DEFAULT 0,
    frozen BOOLEAN NOT NULL DEFAULT FALSE,
    closed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_manifest_version UNIQUE (flight_key, version_no),
    CONSTRAINT uk_manifest_request UNIQUE (request_id)
);
COMMENT ON TABLE manifest IS '舱单版本：预配/值机/装载确认/复核/放行各状态，超限重打生成新版本';
COMMENT ON COLUMN manifest.flight_key IS '航班业务键，航班号+航班日期，同一航班同时刻仅一个有效版本';
COMMENT ON COLUMN manifest.version_no IS '版本号，从 1 递增；LMC 超限重打时 +1';
COMMENT ON COLUMN manifest.status IS '状态：PREPARED/CHECK_IN/LOADED/REVIEWED/CLOSED/SUPERSEDED';
COMMENT ON COLUMN manifest.parent_id IS '上一舱单版本 id，构成 LMC/重打版本链，首版为空';
COMMENT ON COLUMN manifest.fingerprint IS '幂等指纹：覆盖 requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN manifest.lmc_delta_pax IS '本版本自基线累计 LMC 人数增量（含正负），限额 ±5，恰达含';
COMMENT ON COLUMN manifest.lmc_delta_kg IS '本版本自基线累计 LMC 重量增量，单位 kg，限额 ±500，恰达含';
COMMENT ON COLUMN manifest.frozen IS '双岗复核通过后冻结：TRUE 表示拒绝任何业载/油量修改';
COMMENT ON COLUMN manifest.closed_at IS '放行关闭时间，Asia/Shanghai；空表示未关闭，关闭为终态';

CREATE TABLE IF NOT EXISTS payload_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    manifest_id BIGINT NOT NULL,
    category VARCHAR(16) NOT NULL,
    zone_code VARCHAR(16) NOT NULL,
    piece_count INT NOT NULL DEFAULT 0,
    weight_kg DECIMAL(12,2) NOT NULL DEFAULT 0,
    arm DECIMAL(10,4) NOT NULL DEFAULT 0,
    moment DECIMAL(18,2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_payload_manifest FOREIGN KEY (manifest_id) REFERENCES manifest(id)
);
COMMENT ON TABLE payload_item IS '业载明细：旅客/行李/货物/邮件分区装载，可逐条复算力矩';
COMMENT ON COLUMN payload_item.category IS '业载类别：PAX/BAGGAGE/CARGO/MAIL';
COMMENT ON COLUMN payload_item.zone_code IS '装载区/客舱分区代码';
COMMENT ON COLUMN payload_item.piece_count IS '件数或人数；PAX 行为人数';
COMMENT ON COLUMN payload_item.weight_kg IS '该行重量，单位 kg（含 PAX 折算重量）';

CREATE TABLE IF NOT EXISTS manifest_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    manifest_id BIGINT NOT NULL,
    review_role VARCHAR(20) NOT NULL,
    reviewer VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_review_request UNIQUE (request_id),
    CONSTRAINT uk_review_role UNIQUE (manifest_id, review_role),
    CONSTRAINT fk_review_manifest FOREIGN KEY (manifest_id) REFERENCES manifest(id)
);
COMMENT ON TABLE manifest_review IS '双岗复核记录：同一舱单每个岗位仅一条，两岗齐签方生效';
COMMENT ON COLUMN manifest_review.review_role IS '复核岗位：WBO 载重平衡岗 / DSP 签派放行岗，须不同岗位不同人';

CREATE TABLE IF NOT EXISTS flight_active_manifest (
    flight_key VARCHAR(80) PRIMARY KEY,
    manifest_id BIGINT NOT NULL,
    version_no INT NOT NULL
);
COMMENT ON TABLE flight_active_manifest IS '航班当前有效舱单指针：单行保证同时刻仅一个有效版本';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(64) PRIMARY KEY,
    biz_type VARCHAR(24) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    manifest_id BIGINT NULL,
    outcome VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE idempotent_request IS 'requestId 幂等记录：同键同参返回首次结果，同键异参冲突 409';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    flight_key VARCHAR(80) NOT NULL,
    manifest_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    detail CLOB NOT NULL,
    alarmed BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE late_event IS '关闭后迟到的业载变化：不改舱单，仅记事件并告警';
COMMENT ON COLUMN late_event.alarmed IS '是否已产生告警；迟到事件固定 TRUE';
