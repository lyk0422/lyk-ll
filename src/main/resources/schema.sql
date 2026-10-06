-- 档案库房虫情分区处置 建表脚本（H2 MODE=MySQL 兼容）
-- 时间列均为 TIMESTAMP（无时区，按 Asia/Shanghai 写入），空值含义见各列注释。

CREATE TABLE IF NOT EXISTS archive_zone (
    code VARCHAR(32) PRIMARY KEY,
    name VARCHAR(64) NOT NULL
);
COMMENT ON COLUMN archive_zone.code IS '库区编码';
COMMENT ON COLUMN archive_zone.name IS '库区名称';

CREATE TABLE IF NOT EXISTS zone_adjacency (
    zone_code VARCHAR(32) NOT NULL,
    adjacent_code VARCHAR(32) NOT NULL,
    PRIMARY KEY (zone_code, adjacent_code)
);
COMMENT ON COLUMN zone_adjacency.zone_code IS '库区编码';
COMMENT ON COLUMN zone_adjacency.adjacent_code IS '相邻库区编码（双向各存一行）';

CREATE TABLE IF NOT EXISTS pest_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    zone_code VARCHAR(32) NOT NULL,
    description VARCHAR(512) NULL,
    reporter VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reported_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP NULL,
    evaluation_consumed BOOLEAN NOT NULL DEFAULT FALSE
);
COMMENT ON COLUMN pest_report.request_id IS '幂等请求号';
COMMENT ON COLUMN pest_report.status IS '状态：REPORTED=已上报，CONFIRMED=已确认';
COMMENT ON COLUMN pest_report.reported_at IS '上报时间（Asia/Shanghai）';
COMMENT ON COLUMN pest_report.confirmed_at IS '确认时间；NULL 表示未确认';
COMMENT ON COLUMN pest_report.evaluation_consumed IS '是否已被预防性消杀评估消耗（消耗后不再参与触发）';

CREATE TABLE IF NOT EXISTS treatment_chain (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code VARCHAR(32) NOT NULL,
    report_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    monitoring_start TIMESTAMP NULL,
    lift_requested_at TIMESTAMP NULL,
    confirm_disinfection BOOLEAN NOT NULL DEFAULT FALSE,
    confirm_warehouse BOOLEAN NOT NULL DEFAULT FALSE,
    lifted_at TIMESTAMP NULL
);
COMMENT ON COLUMN treatment_chain.status IS '状态：TREATING=处置中，MONITORING=监测期，LIFT_REQUESTED=解除申请中，LIFTED=已解除';
COMMENT ON COLUMN treatment_chain.monitoring_start IS '监测期起点（最近一次投放或新增虫迹时间）；NULL 表示尚未投放';
COMMENT ON COLUMN treatment_chain.lift_requested_at IS '解除申请时间；NULL 表示未申请';
COMMENT ON COLUMN treatment_chain.confirm_disinfection IS '消杀方是否已确认解除';
COMMENT ON COLUMN treatment_chain.confirm_warehouse IS '库房方是否已确认解除';
COMMENT ON COLUMN treatment_chain.lifted_at IS '解除完成时间；NULL 表示未解除';

-- 进行中处置链唯一约束表：主键保证同库区同时刻仅一条进行中处置链
CREATE TABLE IF NOT EXISTS active_chain (
    zone_code VARCHAR(32) PRIMARY KEY,
    chain_id BIGINT NOT NULL
);
COMMENT ON COLUMN active_chain.zone_code IS '处于处置状态的库区编码';
COMMENT ON COLUMN active_chain.chain_id IS '进行中的处置链 ID';

CREATE TABLE IF NOT EXISTS pesticide (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    quantity DECIMAL(12,2) NOT NULL,
    unit VARCHAR(16) NOT NULL,
    safety_line DECIMAL(12,2) NOT NULL,
    expire_date DATE NOT NULL
);
COMMENT ON COLUMN pesticide.name IS '药剂名称';
COMMENT ON COLUMN pesticide.batch_no IS '药剂批次号';
COMMENT ON COLUMN pesticide.quantity IS '库存数量（单位见 unit）';
COMMENT ON COLUMN pesticide.unit IS '计量单位';
COMMENT ON COLUMN pesticide.safety_line IS '安全库存线（低于该值自动生成采购建议）';
COMMENT ON COLUMN pesticide.expire_date IS '过期日期（当日及以后视为过期，拒绝投放）';

CREATE TABLE IF NOT EXISTS dispense_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    chain_id BIGINT NOT NULL,
    pesticide_id BIGINT NOT NULL,
    dosage DECIMAL(12,2) NOT NULL,
    points VARCHAR(256) NOT NULL,
    registrar1 VARCHAR(64) NOT NULL,
    registrar2 VARCHAR(64) NOT NULL,
    dispensed_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN dispense_record.request_id IS '幂等请求号';
COMMENT ON COLUMN dispense_record.dosage IS '投放用量（单位同药剂 unit）';
COMMENT ON COLUMN dispense_record.points IS '投放点位描述';
COMMENT ON COLUMN dispense_record.registrar1 IS '登记人 1（双人登记）';
COMMENT ON COLUMN dispense_record.registrar2 IS '登记人 2（须与登记人 1 不同）';
COMMENT ON COLUMN dispense_record.dispensed_at IS '投放时间（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS purchase_suggestion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pesticide_id BIGINT NOT NULL,
    suggested_quantity DECIMAL(12,2) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    related_dispense_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN purchase_suggestion.suggested_quantity IS '建议采购数量（补足至安全线两倍）';
COMMENT ON COLUMN purchase_suggestion.reason IS '生成原因';
COMMENT ON COLUMN purchase_suggestion.related_dispense_id IS '勾稽的最近投放记录 ID；NULL 表示无关联投放';

CREATE TABLE IF NOT EXISTS pest_trace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chain_id BIGINT NOT NULL,
    found_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN pest_trace.found_at IS '新增虫迹发现时间（发现后监测期自该时间重新起算）';

CREATE TABLE IF NOT EXISTS outbound_registration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    zone_code VARCHAR(32) NOT NULL,
    item VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN outbound_registration.item IS '出库档案条目描述';

CREATE TABLE IF NOT EXISTS preventive_evaluation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code VARCHAR(32) NOT NULL,
    adjacent_zone_code VARCHAR(32) NOT NULL,
    trigger_report_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN preventive_evaluation.zone_code IS '本次虫情库区编码';
COMMENT ON COLUMN preventive_evaluation.adjacent_zone_code IS '90 日内前一次虫情的相邻库区编码';
COMMENT ON COLUMN preventive_evaluation.trigger_report_id IS '触发评估的虫情上报 ID（恰第 2 次）';

CREATE TABLE IF NOT EXISTS idempotency_key (
    request_id VARCHAR(64) PRIMARY KEY,
    fingerprint VARCHAR(128) NOT NULL,
    result_json CLOB NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN idempotency_key.fingerprint IS '请求指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN idempotency_key.result_json IS '首次成功响应快照（JSON）；NULL 表示写入中';
