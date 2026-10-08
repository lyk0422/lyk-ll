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
    version INT NOT NULL DEFAULT 1 COMMENT '设施版本，1 起；每次成功改版加 1',
    CONSTRAINT uk_facility_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS facility_category (
    facility_id BIGINT NOT NULL COMMENT '关联设施 id',
    category VARCHAR(32) NOT NULL COMMENT '可接收垃圾品类：RECYCLABLE/KITCHEN/HAZARDOUS/OTHER；设施无任何配置行表示不限制品类',
    CONSTRAINT uk_facility_category UNIQUE (facility_id, category),
    CONSTRAINT fk_category_facility FOREIGN KEY (facility_id) REFERENCES facility (id)
);

CREATE TABLE IF NOT EXISTS facility_suspension (
    facility_id BIGINT NOT NULL PRIMARY KEY COMMENT '关联设施 id，每设施至多一条停收配置',
    start_at TIMESTAMP NOT NULL COMMENT '停收区间起点（UTC，含该时刻）',
    end_at TIMESTAMP NOT NULL COMMENT '停收区间终点（UTC，恰该时刻恢复，不含）',
    CONSTRAINT fk_suspension_facility FOREIGN KEY (facility_id) REFERENCES facility (id)
);

CREATE TABLE IF NOT EXISTS facility_revision (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号，全局唯一',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部改版字段（含区间精确时刻）的 SHA-256',
    facility_id BIGINT NOT NULL COMMENT '关联设施 id',
    version INT NOT NULL COMMENT '本次改版后的设施版本',
    applied_at TIMESTAMP NOT NULL COMMENT '改版生效时间（UTC）',
    CONSTRAINT uk_facility_revision_request UNIQUE (request_id),
    CONSTRAINT fk_revision_facility FOREIGN KEY (facility_id) REFERENCES facility (id)
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
    facility_id BIGINT NOT NULL COMMENT '当前去向处理设施 id，必须在目录内',
    destination_version INT NOT NULL DEFAULT 1 COMMENT '当前去向版本，1 起；每次成功改投加 1',
    status VARCHAR(16) NOT NULL COMMENT '联单状态：IN_TRANSIT 执行中 / RECEIVED 已回执 / EXCEPTION 异常待核查 / RESOLVED 核查闭环',
    open_flag TINYINT NULL COMMENT '执行中标记：IN_TRANSIT 时为 1，其余为 NULL，用于同车同时刻唯一执行中联单',
    created_at TIMESTAMP NOT NULL COMMENT '登记时间（UTC）',
    CONSTRAINT uk_manifest_request UNIQUE (request_id),
    CONSTRAINT uk_manifest_open UNIQUE (vehicle_id, open_flag),
    CONSTRAINT fk_manifest_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle (id),
    CONSTRAINT fk_manifest_facility FOREIGN KEY (facility_id) REFERENCES facility (id)
);

CREATE TABLE IF NOT EXISTS manifest_destination (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    manifest_id BIGINT NOT NULL COMMENT '关联联单 id',
    version INT NOT NULL COMMENT '去向版本，1 起；版本 1 为进场登记时的初始去向',
    facility_id BIGINT NOT NULL COMMENT '该版本去向设施 id',
    request_id VARCHAR(64) NOT NULL COMMENT '产生该版本的请求号：版本 1 为进场登记 requestId，其余为改投 requestId',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部改投字段的 SHA-256；版本 1 与联单指纹一致',
    created_at TIMESTAMP NOT NULL COMMENT '该去向版本生效时间（UTC）',
    CONSTRAINT uk_destination_request UNIQUE (request_id),
    CONSTRAINT uk_destination_version UNIQUE (manifest_id, version),
    CONSTRAINT fk_destination_manifest FOREIGN KEY (manifest_id) REFERENCES manifest (id),
    CONSTRAINT fk_destination_facility FOREIGN KEY (facility_id) REFERENCES facility (id)
);

CREATE TABLE IF NOT EXISTS receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号，全局唯一',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    manifest_id BIGINT NOT NULL COMMENT '关联联单 id，一张联单仅一条入账回执',
    net_kg DECIMAL(12,3) NOT NULL COMMENT '处理设施回执净重，单位 kg',
    deviation_pct DECIMAL(9,6) NOT NULL COMMENT '与进场净重偏差百分比；>2 转异常，恰 2 视为一致',
    result VARCHAR(16) NOT NULL COMMENT '回执结论：MATCH 一致 / EXCEPTION 转异常核查',
    facility_code VARCHAR(32) NULL COMMENT '回执声明的设施编码；未改投联单的旧式调用为 NULL',
    destination_version INT NULL COMMENT '回执声明的去向版本；未改投联单的旧式调用为 NULL',
    received_at TIMESTAMP NOT NULL COMMENT '回执时间（UTC）',
    CONSTRAINT uk_receipt_request UNIQUE (request_id),
    CONSTRAINT uk_receipt_manifest UNIQUE (manifest_id),
    CONSTRAINT fk_receipt_manifest FOREIGN KEY (manifest_id) REFERENCES manifest (id)
);

CREATE TABLE IF NOT EXISTS late_receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号，全局唯一',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    manifest_id BIGINT NOT NULL COMMENT '关联联单 id',
    facility_code VARCHAR(32) NOT NULL COMMENT '迟到回执声明的设施编码（与当前去向不匹配或版本过旧）',
    destination_version INT NOT NULL COMMENT '迟到回执声明的去向版本（非当前版本）',
    net_kg DECIMAL(12,3) NOT NULL COMMENT '迟到回执净重，单位 kg；仅存证据不入账',
    received_at TIMESTAMP NOT NULL COMMENT '迟到回执留证时间（UTC）',
    CONSTRAINT uk_late_receipt_request UNIQUE (request_id),
    CONSTRAINT fk_late_receipt_manifest FOREIGN KEY (manifest_id) REFERENCES manifest (id)
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
