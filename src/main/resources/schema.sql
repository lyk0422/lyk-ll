-- 碳排放配额清缴与抵消核销服务建表脚本（H2 MODE=MySQL 兼容）
-- 所有时间字段均为 UTC 时刻（TIMESTAMP 存储，应用层以 java.time.Instant 读写）。

CREATE TABLE IF NOT EXISTS enterprise (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '企业主键',
    name VARCHAR(128) NOT NULL COMMENT '企业名称（合成数据）',
    created_at TIMESTAMP NOT NULL COMMENT '创建时刻（UTC）'
) COMMENT='履约企业';

CREATE TABLE IF NOT EXISTS allowance_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '配额批次主键，同时作为先进先出序号',
    enterprise_id BIGINT NOT NULL COMMENT '持有企业',
    compliance_year INT NOT NULL COMMENT '配额对应履约年度',
    quantity_total BIGINT NOT NULL COMMENT '签发总量（吨CO2e）',
    quantity_remaining BIGINT NOT NULL COMMENT '剩余可用量（吨CO2e）',
    expiry_date DATE NOT NULL COMMENT '到期日；扣减时到期日近者优先',
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT '状态：AVAILABLE 可用；EXHAUSTED 已用尽',
    created_at TIMESTAMP NOT NULL COMMENT '签发时刻（UTC）',
    CONSTRAINT fk_allowance_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise (id)
) COMMENT='碳排放配额批次';

CREATE TABLE IF NOT EXISTS offset_credit_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '抵消信用批次主键，同时作为先进先出序号',
    enterprise_id BIGINT NOT NULL COMMENT '持有企业',
    compliance_year INT NOT NULL COMMENT '信用对应履约年度',
    quantity_total BIGINT NOT NULL COMMENT '签发总量（吨CO2e）',
    quantity_remaining BIGINT NOT NULL COMMENT '剩余可用量（吨CO2e）',
    expiry_date DATE NOT NULL COMMENT '到期日；扣减时到期日近者优先',
    created_at TIMESTAMP NOT NULL COMMENT '签发时刻（UTC）',
    CONSTRAINT fk_offset_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise (id)
) COMMENT='核证抵消信用批次';

CREATE TABLE IF NOT EXISTS compliance_account (
    enterprise_id BIGINT NOT NULL COMMENT '企业',
    compliance_year INT NOT NULL COMMENT '履约年度',
    emissions BIGINT NOT NULL COMMENT '核定年度排放量（吨CO2e）',
    surrendered_allowance BIGINT NOT NULL DEFAULT 0 COMMENT '已清缴配额量（吨CO2e）',
    surrendered_offset BIGINT NOT NULL DEFAULT 0 COMMENT '已核销抵消信用量（吨CO2e）',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 履约中；SURRENDERED 已足额清缴；NON_COMPLIANT 未履约（截止后标记，终态）',
    deadline_at TIMESTAMP NOT NULL COMMENT '履约截止时刻（UTC）；恰在截止时刻的申请仍受理',
    PRIMARY KEY (enterprise_id, compliance_year),
    CONSTRAINT fk_account_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise (id)
) COMMENT='企业年度履约账户';

CREATE TABLE IF NOT EXISTS surrender_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '清缴申请主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等键：客户端申请编号',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId+企业+年度+组合明细的哈希；同键异参返回409',
    enterprise_id BIGINT NOT NULL COMMENT '申请企业',
    compliance_year INT NOT NULL COMMENT '履约年度',
    allowance_amount BIGINT NOT NULL COMMENT '本次申请清缴配额量（吨CO2e）',
    offset_amount BIGINT NOT NULL COMMENT '本次申请核销抵消信用量（吨CO2e）',
    certificate_no VARCHAR(64) NOT NULL COMMENT '清缴凭证号，全局唯一，重复查询结果稳定',
    created_at TIMESTAMP NOT NULL COMMENT '受理时刻（UTC）',
    CONSTRAINT uk_surrender_request_id UNIQUE (request_id),
    CONSTRAINT uk_surrender_certificate_no UNIQUE (certificate_no)
) COMMENT='清缴申请（幂等）';

CREATE TABLE IF NOT EXISTS surrender_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '注销明细主键',
    request_id VARCHAR(64) NOT NULL COMMENT '所属清缴申请',
    source_type VARCHAR(16) NOT NULL COMMENT '来源类型：ALLOWANCE 配额；OFFSET 抵消信用',
    batch_id BIGINT NOT NULL COMMENT '被扣减批次主键',
    quantity BIGINT NOT NULL COMMENT '本次扣减量（吨CO2e）',
    CONSTRAINT fk_item_request FOREIGN KEY (request_id) REFERENCES surrender_request (request_id)
) COMMENT='清缴注销明细（配额/信用进入已注销终态的记录）';
