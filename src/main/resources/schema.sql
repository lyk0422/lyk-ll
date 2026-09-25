-- 治超执法服务建表脚本（H2 MySQL 兼容模式；时间均为 UTC，重量单位千克，金额单位元）
CREATE TABLE IF NOT EXISTS axle_limit (
    axle_type VARCHAR(32) NOT NULL COMMENT '轴型代码，如 AXLE_2/AXLE_4/AXLE_6',
    limit_weight_kg INT NOT NULL COMMENT '该轴型车货总重限值，单位千克',
    PRIMARY KEY (axle_type)
);

CREATE TABLE IF NOT EXISTS weigh_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求标识',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的哈希',
    plate_no VARCHAR(16) NOT NULL COMMENT '车牌号',
    station_id VARCHAR(32) NOT NULL COMMENT '检测站编号',
    weighed_at TIMESTAMP NOT NULL COMMENT '过磅时刻，UTC',
    axle_type VARCHAR(32) NOT NULL COMMENT '轴型代码',
    weigh_type VARCHAR(8) NOT NULL COMMENT '称重方式：DYNAMIC 动态 / STATIC 静态复检',
    total_weight_kg INT NOT NULL COMMENT '实测总重，单位千克',
    limit_weight_kg INT NOT NULL COMMENT '判定采用限值，单位千克',
    over_rate DECIMAL(6,2) NOT NULL COMMENT '超限率=(实测-限值)/限值，保留两位小数，可为负',
    over_limit BOOLEAN NOT NULL COMMENT '是否超限：超限率>0 为 true，恰为 0 或更小为 false',
    authoritative BOOLEAN NOT NULL COMMENT '是否判定依据：静态复检优先，被取代的动态记录为 false',
    late_evidence BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否结案后迟到数据：仅入证据备查不改判',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时间，UTC',
    CONSTRAINT uk_weigh_request UNIQUE (request_id),
    CONSTRAINT uk_weigh_dedup UNIQUE (plate_no, station_id, weighed_at, weigh_type)
);

CREATE TABLE IF NOT EXISTS penalty_decision (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求标识',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹',
    case_id BIGINT NOT NULL COMMENT '关联案件编号',
    weigh_record_id BIGINT NOT NULL COMMENT '判定依据检测记录编号',
    plate_no VARCHAR(16) NOT NULL COMMENT '车牌号',
    over_rate DECIMAL(6,2) NOT NULL COMMENT '处罚采用超限率，两位小数',
    measure VARCHAR(16) NOT NULL COMMENT '处置措施：UNLOAD 卸载放行 / FINE_DEMERIT 罚款并记分',
    fine_amount DECIMAL(10,2) NOT NULL COMMENT '罚款金额，单位元，未罚款为 0',
    demerit_points INT NOT NULL COMMENT '记分数，未记分为 0',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效 / REVOKED 已撤销',
    served_at TIMESTAMP NOT NULL COMMENT '处罚送达时间，UTC，复检期限起算点',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时间，UTC',
    CONSTRAINT uk_penalty_request UNIQUE (request_id),
    CONSTRAINT uk_penalty_case UNIQUE (case_id)
);

CREATE TABLE IF NOT EXISTS case_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    penalty_id BIGINT NULL COMMENT '关联处罚决定编号，创建案件时暂空并在同事务回填',
    plate_no VARCHAR(16) NOT NULL COMMENT '车牌号',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN 在办 / CLOSED 结案（终态）',
    closed_at TIMESTAMP NULL COMMENT '结案时间，UTC，未结案为 NULL',
    created_at TIMESTAMP NOT NULL COMMENT '案件创建时间，UTC',
    CONSTRAINT uk_case_penalty UNIQUE (penalty_id)
);
CREATE INDEX IF NOT EXISTS idx_case_plate ON case_record (plate_no, status);

CREATE TABLE IF NOT EXISTS recheck_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求标识',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹',
    penalty_id BIGINT NOT NULL COMMENT '被申请复检的处罚决定编号',
    case_id BIGINT NOT NULL COMMENT '关联案件编号，唯一约束保证一案仅受理一次复检',
    static_weight_kg INT NOT NULL COMMENT '静态复检实测总重，单位千克',
    applied_at TIMESTAMP NOT NULL COMMENT '复检申请时间，UTC',
    outcome VARCHAR(16) NOT NULL COMMENT '复检结论：OVERTURNED 推翻原处罚 / UPHELD 维持',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时间，UTC',
    CONSTRAINT uk_recheck_request UNIQUE (request_id),
    CONSTRAINT uk_recheck_case UNIQUE (case_id)
);

CREATE TABLE IF NOT EXISTS revocation_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    penalty_id BIGINT NOT NULL COMMENT '被撤销的处罚决定编号',
    recheck_id BIGINT NOT NULL COMMENT '触发撤销的复检申请编号',
    refunded_fine DECIMAL(10,2) NOT NULL COMMENT '退还罚款金额，单位元',
    refunded_points INT NOT NULL COMMENT '退还记分数',
    created_at TIMESTAMP NOT NULL COMMENT '文书生成时间，UTC',
    CONSTRAINT uk_revocation_penalty UNIQUE (penalty_id)
);

CREATE TABLE IF NOT EXISTS late_weigh_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL COMMENT '已结案案件编号',
    weigh_record_id BIGINT NOT NULL COMMENT '迟到的检测记录编号',
    plate_no VARCHAR(16) NOT NULL COMMENT '车牌号',
    station_id VARCHAR(32) NOT NULL COMMENT '检测站编号',
    weighed_at TIMESTAMP NOT NULL COMMENT '过磅时刻，UTC',
    created_at TIMESTAMP NOT NULL COMMENT '事件登记时间，UTC',
    CONSTRAINT uk_late_weigh UNIQUE (weigh_record_id)
);
