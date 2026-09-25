-- 碳排放配额清缴与抵消核销服务建表脚本（H2 MySQL 兼容模式）
-- 金额单位：吨二氧化碳当量（tCO2e）；时间戳默认 UTC 会话时区由应用保证；空值含义见各列注释。

CREATE TABLE IF NOT EXISTS enterprise (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_enterprise_code UNIQUE (code)
);
COMMENT ON TABLE enterprise IS '企业主数据（合成数据）';
COMMENT ON COLUMN enterprise.code IS '企业唯一编码，业务幂等键的一部分';
COMMENT ON COLUMN enterprise.name IS '企业名称';
COMMENT ON COLUMN enterprise.created_at IS '创建时间（应用时钟，UTC）';

CREATE TABLE IF NOT EXISTS emission_assessment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    compliance_year INT NOT NULL,
    emission_amount BIGINT NOT NULL,
    assessed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_emission_ent_year UNIQUE (enterprise_id, compliance_year)
);
COMMENT ON TABLE emission_assessment IS '企业年度核定排放量';
COMMENT ON COLUMN emission_assessment.enterprise_id IS '企业主键，关联 enterprise.id';
COMMENT ON COLUMN emission_assessment.compliance_year IS '履约年度（公历年）';
COMMENT ON COLUMN emission_assessment.emission_amount IS '核定排放量，单位 tCO2e，必须为正数';
COMMENT ON COLUMN emission_assessment.assessed_at IS '核定时间（应用时钟，UTC）';

CREATE TABLE IF NOT EXISTS allowance_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    vintage_year INT NOT NULL,
    total_amount BIGINT NOT NULL,
    remaining_amount BIGINT NOT NULL,
    expiry_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE allowance_batch IS '碳排放配额批次账户';
COMMENT ON COLUMN allowance_batch.vintage_year IS '配额核发年度';
COMMENT ON COLUMN allowance_batch.total_amount IS '批次总量，单位 tCO2e';
COMMENT ON COLUMN allowance_batch.remaining_amount IS '剩余可用量，单位 tCO2e；扣减遵循先进先出+到期日近者优先';
COMMENT ON COLUMN allowance_batch.expiry_date IS '配额到期日（UTC 日期），扣减时到期日近者优先';
COMMENT ON COLUMN allowance_batch.status IS '批次状态：ACTIVE=可用，EXHAUSTED=已用尽；注销数量以 surrender_item 终态记录为准';

CREATE TABLE IF NOT EXISTS offset_credit_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    credit_type VARCHAR(64) NOT NULL,
    total_amount BIGINT NOT NULL,
    remaining_amount BIGINT NOT NULL,
    expiry_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE offset_credit_batch IS '核证抵消信用批次账户';
COMMENT ON COLUMN offset_credit_batch.credit_type IS '抵消信用类型（如 CCER），合成数据';
COMMENT ON COLUMN offset_credit_batch.total_amount IS '批次总量，单位 tCO2e';
COMMENT ON COLUMN offset_credit_batch.remaining_amount IS '剩余可用量，单位 tCO2e；扣减遵循先进先出+到期日近者优先';
COMMENT ON COLUMN offset_credit_batch.expiry_date IS '信用到期日（UTC 日期），扣减时到期日近者优先';
COMMENT ON COLUMN offset_credit_batch.status IS '批次状态：ACTIVE=可用，EXHAUSTED=已用尽';

CREATE TABLE IF NOT EXISTS surrender_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    enterprise_id BIGINT NOT NULL,
    compliance_year INT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    allowance_amount BIGINT NOT NULL,
    offset_amount BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_surrender_request_id UNIQUE (request_id)
);
COMMENT ON TABLE surrender_request IS '清缴申请（按 request_id 幂等）';
COMMENT ON COLUMN surrender_request.request_id IS '客户端幂等键，全局唯一';
COMMENT ON COLUMN surrender_request.fingerprint IS '申请指纹：requestId+企业+履约年度+组合明细的 SHA-256，同键异参返回 409';
COMMENT ON COLUMN surrender_request.allowance_amount IS '本笔申请使用配额数量，单位 tCO2e';
COMMENT ON COLUMN surrender_request.offset_amount IS '本笔申请使用抵消信用数量，单位 tCO2e，累计不得超过核定排放量 5%';
COMMENT ON COLUMN surrender_request.status IS '申请状态：COMPLETED=已注销完成；拒绝的申请不落库（事务回滚）';

CREATE TABLE IF NOT EXISTS surrender_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_pk BIGINT NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    batch_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    item_status VARCHAR(16) NOT NULL DEFAULT 'CANCELLED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE surrender_item IS '清缴注销明细（终态，不可恢复、不可再次使用）';
COMMENT ON COLUMN surrender_item.request_pk IS '所属清缴申请主键，关联 surrender_request.id';
COMMENT ON COLUMN surrender_item.source_type IS '来源类型：ALLOWANCE=配额批次，OFFSET=抵消信用批次';
COMMENT ON COLUMN surrender_item.batch_id IS '来源批次主键';
COMMENT ON COLUMN surrender_item.amount IS '本明细注销数量，单位 tCO2e';
COMMENT ON COLUMN surrender_item.item_status IS '明细状态：CANCELLED=已注销（终态）';

CREATE TABLE IF NOT EXISTS certificate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    certificate_no VARCHAR(40) NOT NULL,
    request_pk BIGINT NOT NULL,
    enterprise_id BIGINT NOT NULL,
    compliance_year INT NOT NULL,
    total_amount BIGINT NOT NULL,
    offset_amount BIGINT NOT NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_certificate_no UNIQUE (certificate_no),
    CONSTRAINT uk_certificate_request UNIQUE (request_pk)
);
COMMENT ON TABLE certificate IS '清缴凭证（凭证号唯一，重复查询结果稳定）';
COMMENT ON COLUMN certificate.certificate_no IS '清缴凭证号，全局唯一，格式 CT<履约年度>-<申请序号>';
COMMENT ON COLUMN certificate.request_pk IS '对应清缴申请主键，关联 surrender_request.id';
COMMENT ON COLUMN certificate.total_amount IS '本凭证注销总量（配额+抵消），单位 tCO2e';
COMMENT ON COLUMN certificate.offset_amount IS '其中抵消信用量，单位 tCO2e';
COMMENT ON COLUMN certificate.issued_at IS '凭证签发时间（应用时钟，UTC）';

CREATE TABLE IF NOT EXISTS compliance_status (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    compliance_year INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_compliance_ent_year UNIQUE (enterprise_id, compliance_year)
);
COMMENT ON TABLE compliance_status IS '企业年度履约状态';
COMMENT ON COLUMN compliance_status.status IS '履约状态：PENDING=履约中，COMPLIANT=已足额履约，NON_COMPLIANT=截止后未足额（未履约）';
COMMENT ON COLUMN compliance_status.updated_at IS '状态最近更新时间（应用时钟，UTC）';
