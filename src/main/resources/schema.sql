-- 垃圾分类收运台账建表脚本（H2 MySQL 兼容模式）
-- 时间列统一为 UTC 时间戳；重量单位均为千克（kg）；NULL 表示该状态字段不适用。

CREATE TABLE IF NOT EXISTS vehicle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plate_no VARCHAR(32) NOT NULL COMMENT '车牌号，全局唯一',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '档案创建时间（UTC）',
    CONSTRAINT uk_vehicle_plate UNIQUE (plate_no)
);

CREATE TABLE IF NOT EXISTS tare_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id BIGINT NOT NULL COMMENT '关联车辆 id',
    tare_kg DECIMAL(12,3) NOT NULL COMMENT '空车皮重，单位 kg',
    weighed_at TIMESTAMP NOT NULL COMMENT '空车称重时间（UTC）',
    CONSTRAINT fk_tare_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle (id)
);

CREATE TABLE IF NOT EXISTS facility (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL COMMENT '处理设施编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '处理设施名称',
    active TINYINT NOT NULL DEFAULT 1 COMMENT '是否在目录内有效：1 有效，0 停用',
    CONSTRAINT uk_facility_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS manifest (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号，全局唯一',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    vehicle_id BIGINT NOT NULL COMMENT '收运车辆 id',
    community VARCHAR(128) NOT NULL COMMENT '收运小区名称',
    category VARCHAR(32) NOT NULL COMMENT '垃圾品类：RECYCLABLE/KITCHEN/HAZARDOUS/OTHER',
    gross_kg DECIMAL(12,3) NOT NULL COMMENT '进场毛重，单位 kg',
    tare_kg DECIMAL(12,3) NOT NULL COMMENT '登记时采用的车辆皮重，单位 kg',
    net_kg DECIMAL(12,3) NOT NULL COMMENT '净重=毛重-皮重，单位 kg，恰为 0 拒绝登记',
    facility_id BIGINT NOT NULL COMMENT '去向处理设施 id，必须在目录内',
    status VARCHAR(16) NOT NULL COMMENT '联单状态：IN_TRANSIT 执行中 / RECEIVED 已回执 / EXCEPTION 异常待核查 / RESOLVED 核查闭环',
    open_flag TINYINT NULL COMMENT '执行中标记：IN_TRANSIT 时为 1，其余为 NULL，用于同车同时刻唯一执行中联单',
    created_at TIMESTAMP NOT NULL COMMENT '登记时间（UTC）',
    CONSTRAINT uk_manifest_request UNIQUE (request_id),
    CONSTRAINT uk_manifest_open UNIQUE (vehicle_id, open_flag),
    CONSTRAINT fk_manifest_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle (id),
    CONSTRAINT fk_manifest_facility FOREIGN KEY (facility_id) REFERENCES facility (id)
);

CREATE TABLE IF NOT EXISTS receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号，全局唯一',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    manifest_id BIGINT NOT NULL COMMENT '关联联单 id，一张联单仅一条回执',
    net_kg DECIMAL(12,3) NOT NULL COMMENT '处理设施回执净重，单位 kg',
    deviation_pct DECIMAL(9,6) NOT NULL COMMENT '与进场净重偏差百分比；>2 转异常，恰 2 视为一致',
    result VARCHAR(16) NOT NULL COMMENT '回执结论：MATCH 一致 / EXCEPTION 转异常核查',
    received_at TIMESTAMP NOT NULL COMMENT '回执时间（UTC）',
    CONSTRAINT uk_receipt_request UNIQUE (request_id),
    CONSTRAINT uk_receipt_manifest UNIQUE (manifest_id),
    CONSTRAINT fk_receipt_manifest FOREIGN KEY (manifest_id) REFERENCES manifest (id)
);

CREATE TABLE IF NOT EXISTS exception_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号，全局唯一',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    manifest_id BIGINT NOT NULL COMMENT '关联联单 id，一张联单仅一条核查结论',
    conclusion VARCHAR(32) NOT NULL COMMENT '核查结论：DEVICE_ERROR 设备误差 / LOADING_DIFF 装载差异',
    note VARCHAR(512) NULL COMMENT '核查备注，NULL 表示未填写',
    reviewed_at TIMESTAMP NOT NULL COMMENT '核查留痕时间（UTC）',
    CONSTRAINT uk_review_request UNIQUE (request_id),
    CONSTRAINT uk_review_manifest UNIQUE (manifest_id),
    CONSTRAINT fk_review_manifest FOREIGN KEY (manifest_id) REFERENCES manifest (id)
);
