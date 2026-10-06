-- 垃圾分类收运台账建表脚本（H2 MySQL 兼容模式，数据仅 JVM 生命周期内保留）

CREATE TABLE IF NOT EXISTS vehicle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plate_no VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_vehicle_plate UNIQUE (plate_no)
);
COMMENT ON TABLE vehicle IS '收运车辆档案';
COMMENT ON COLUMN vehicle.plate_no IS '车牌号，全局唯一';
COMMENT ON COLUMN vehicle.created_at IS '档案创建时间，Asia/Shanghai 本地时间';

CREATE TABLE IF NOT EXISTS tare_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id BIGINT NOT NULL REFERENCES vehicle (id),
    tare_kg DECIMAL(12, 3) NOT NULL,
    weighed_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE tare_record IS '车辆空车皮重称重记录，进场取最近一次且未超过 7 日的记录';
COMMENT ON COLUMN tare_record.vehicle_id IS '车辆主键';
COMMENT ON COLUMN tare_record.tare_kg IS '皮重，单位千克，精确到 0.001';
COMMENT ON COLUMN tare_record.weighed_at IS '空车称重时间，Asia/Shanghai 本地时间';

CREATE TABLE IF NOT EXISTS facility (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_facility_code UNIQUE (code)
);
COMMENT ON TABLE facility IS '合成处理设施目录，联单去向必须命中目录内有效设施';
COMMENT ON COLUMN facility.code IS '设施编码，全局唯一';
COMMENT ON COLUMN facility.name IS '设施名称';
COMMENT ON COLUMN facility.active IS '是否有效，FALSE 时不可作为联单去向';

CREATE TABLE IF NOT EXISTS manifest (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    vehicle_id BIGINT NOT NULL REFERENCES vehicle (id),
    community VARCHAR(128) NOT NULL,
    category VARCHAR(32) NOT NULL,
    facility_id BIGINT NOT NULL REFERENCES facility (id),
    gross_kg DECIMAL(12, 3) NOT NULL,
    tare_kg DECIMAL(12, 3) NOT NULL,
    net_kg DECIMAL(12, 3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    active_vehicle_key BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_manifest_request UNIQUE (request_id),
    CONSTRAINT uk_manifest_active_vehicle UNIQUE (active_vehicle_key)
);
COMMENT ON TABLE manifest IS '收运联单，一车同时刻仅允许一条执行中联单';
COMMENT ON COLUMN manifest.request_id IS '幂等键，写请求唯一标识';
COMMENT ON COLUMN manifest.fingerprint IS '幂等指纹，SHA-256 覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN manifest.community IS '收运小区名称';
COMMENT ON COLUMN manifest.category IS '垃圾品类，如厨余垃圾/可回收物/有害垃圾/其他垃圾';
COMMENT ON COLUMN manifest.gross_kg IS '进场毛重，单位千克';
COMMENT ON COLUMN manifest.tare_kg IS '采用的皮重，单位千克，来自最近一次空车称重或同事务复称';
COMMENT ON COLUMN manifest.net_kg IS '净重=毛重-皮重，单位千克，恰为 0 或负值拒绝登记';
COMMENT ON COLUMN manifest.status IS '联单状态：IN_PROGRESS 执行中 / CLOSED 回执闭环 / EXCEPTION 异常核查';
COMMENT ON COLUMN manifest.active_vehicle_key IS '执行中占位键，等于车辆主键；联单闭环后置 NULL，唯一约束保证一车一执行中联单';
COMMENT ON COLUMN manifest.created_at IS '联单登记时间，Asia/Shanghai 本地时间';

CREATE TABLE IF NOT EXISTS receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    manifest_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    received_net_kg DECIMAL(12, 3) NOT NULL,
    deviation_ratio DECIMAL(10, 6) NOT NULL,
    received_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_receipt_manifest UNIQUE (manifest_id),
    CONSTRAINT uk_receipt_request UNIQUE (request_id)
);
COMMENT ON TABLE receipt IS '处理设施回执，一联单仅一条回执，回执后联单闭环或转异常';
COMMENT ON COLUMN receipt.manifest_id IS '关联联单主键，唯一';
COMMENT ON COLUMN receipt.request_id IS '幂等键，写请求唯一标识';
COMMENT ON COLUMN receipt.fingerprint IS '幂等指纹，SHA-256 覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN receipt.received_net_kg IS '设施签收净重，单位千克';
COMMENT ON COLUMN receipt.deviation_ratio IS '与进场净重偏差绝对值比例，大于 0.02 转异常核查，恰等于 0.02 视为一致';
COMMENT ON COLUMN receipt.received_at IS '回执时间，Asia/Shanghai 本地时间';

CREATE TABLE IF NOT EXISTS exception_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    manifest_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    conclusion_type VARCHAR(32) NULL,
    conclusion_note VARCHAR(512) NULL,
    conclusion_request_id VARCHAR(64) NULL,
    conclusion_fingerprint VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP NULL,
    CONSTRAINT uk_exception_manifest UNIQUE (manifest_id),
    CONSTRAINT uk_exception_conclusion_request UNIQUE (conclusion_request_id)
);
COMMENT ON TABLE exception_case IS '偏差异常核查单，回执偏差超过 2% 时生成，核查结论留痕';
COMMENT ON COLUMN exception_case.manifest_id IS '关联联单主键，唯一';
COMMENT ON COLUMN exception_case.status IS '核查状态：OPEN 待核查 / RESOLVED 已结论';
COMMENT ON COLUMN exception_case.conclusion_type IS '核查结论分类：DEVICE_ERROR 设备误差 / LOADING_DIFF 装载差异，未结论为 NULL';
COMMENT ON COLUMN exception_case.conclusion_note IS '核查结论说明，未结论为 NULL';
COMMENT ON COLUMN exception_case.conclusion_request_id IS '结论写请求幂等键，未结论为 NULL';
COMMENT ON COLUMN exception_case.conclusion_fingerprint IS '结论幂等指纹，未结论为 NULL';
COMMENT ON COLUMN exception_case.created_at IS '异常生成时间，Asia/Shanghai 本地时间';
COMMENT ON COLUMN exception_case.resolved_at IS '结论时间，未结论为 NULL';
