-- 本地碳市场履约服务建表脚本（H2 MODE=MySQL，单位均为吨 CO2e，时刻均为 UTC）

CREATE TABLE IF NOT EXISTS enterprise (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    code    VARCHAR(64)  NOT NULL COMMENT '企业编码（合成数据）',
    name    VARCHAR(128) NOT NULL COMMENT '企业名称（合成数据）',
    CONSTRAINT uk_enterprise_code UNIQUE (code)
);

-- 企业履约年度核定信息：核定排放量、履约截止时刻与履约状态
CREATE TABLE IF NOT EXISTS emission_assessment (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    enterprise_id    BIGINT        NOT NULL COMMENT '企业ID',
    compliance_year  INT           NOT NULL COMMENT '履约年度',
    verified_emission DECIMAL(18,3) NOT NULL COMMENT '核定年度排放量，单位吨CO2e',
    deadline_utc     TIMESTAMP     NOT NULL COMMENT '履约截止时刻（UTC），恰在该时刻仍受理',
    status           VARCHAR(24)   NOT NULL COMMENT '状态：PENDING 未完成/SURRENDERED 已足额清缴/NOT_COMPLIED 未履约',
    CONSTRAINT uk_assessment_ent_year UNIQUE (enterprise_id, compliance_year)
);

-- 配额批次：同一企业可持有多批，按先进先出+到期日近者优先扣减
CREATE TABLE IF NOT EXISTS allowance_batch (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    enterprise_id    BIGINT        NOT NULL COMMENT '归属企业ID',
    batch_no         VARCHAR(64)   NOT NULL COMMENT '配额批次号',
    total_amount     DECIMAL(18,3) NOT NULL COMMENT '批次发放总量，单位吨CO2e',
    remaining_amount DECIMAL(18,3) NOT NULL COMMENT '当前剩余可用量，单位吨CO2e',
    issued_date      DATE          NOT NULL COMMENT '获得日期，先进先出排序依据',
    expiry_date      DATE          NOT NULL COMMENT '到期日，到期日近者优先扣减',
    status           VARCHAR(16)   NOT NULL COMMENT '状态：ACTIVE 可用/RETIRED 已注销终态',
    CONSTRAINT uk_allowance_batch_no UNIQUE (batch_no)
);
CREATE INDEX IF NOT EXISTS idx_allowance_ent_order
    ON allowance_batch (enterprise_id, status, issued_date, expiry_date, id);

-- 核证抵消信用批次：扣减规则与配额一致，总量受排放量5%上限约束
CREATE TABLE IF NOT EXISTS offset_credit (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    enterprise_id    BIGINT        NOT NULL COMMENT '归属企业ID',
    credit_no        VARCHAR(64)   NOT NULL COMMENT '抵消信用批次号',
    total_amount     DECIMAL(18,3) NOT NULL COMMENT '批次核发总量，单位吨CO2e',
    remaining_amount DECIMAL(18,3) NOT NULL COMMENT '当前剩余可用量，单位吨CO2e',
    issued_date      DATE          NOT NULL COMMENT '核证获得日期，先进先出排序依据',
    expiry_date      DATE          NOT NULL COMMENT '到期日，到期日近者优先扣减',
    status           VARCHAR(16)   NOT NULL COMMENT '状态：ACTIVE 可用/RETIRED 已核销终态',
    CONSTRAINT uk_offset_credit_no UNIQUE (credit_no)
);
CREATE INDEX IF NOT EXISTS idx_offset_ent_order
    ON offset_credit (enterprise_id, status, issued_date, expiry_date, id);

-- 企业+履约年度串行锁：同一履约年度的清缴申请按提交顺序串行生效
CREATE TABLE IF NOT EXISTS compliance_lock (
    enterprise_id   BIGINT NOT NULL COMMENT '企业ID',
    compliance_year INT    NOT NULL COMMENT '履约年度',
    PRIMARY KEY (enterprise_id, compliance_year)
);

-- 清缴申请（requestId 幂等）
CREATE TABLE IF NOT EXISTS surrender_request (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(64)   NOT NULL COMMENT '外部申请幂等键',
    enterprise_id    BIGINT        NOT NULL COMMENT '申请企业ID',
    compliance_year  INT           NOT NULL COMMENT '履约年度',
    allowance_amount DECIMAL(18,3) NOT NULL COMMENT '申请以配额清缴数量，单位吨CO2e',
    offset_amount    DECIMAL(18,3) NOT NULL COMMENT '申请以抵消信用抵减数量，单位吨CO2e',
    fingerprint      CHAR(64)      NOT NULL COMMENT '申请指纹SHA-256：requestId+企业+年度+组合明细',
    status           VARCHAR(16)   NOT NULL COMMENT '状态：RETIRED 已注销/REJECTED 已拒绝',
    reject_reason    VARCHAR(255)  NULL COMMENT '拒绝原因，成功时为空',
    arrival_utc      TIMESTAMP     NOT NULL COMMENT '请求到达时刻（UTC）',
    processed_utc    TIMESTAMP     NOT NULL COMMENT '处理完成时刻（UTC）',
    CONSTRAINT uk_surrender_request_id UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_surrender_ent_year
    ON surrender_request (enterprise_id, compliance_year);

-- 注销清单（配额/抵消逐批扣减明细）
CREATE TABLE IF NOT EXISTS retirement_record (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    surrender_request_id BIGINT       NOT NULL COMMENT '清缴申请行ID',
    request_id          VARCHAR(64)  NOT NULL COMMENT '外部申请幂等键',
    account_type        VARCHAR(16)  NOT NULL COMMENT '账户类型：ALLOWANCE 配额/OFFSET 抵消信用',
    batch_id            BIGINT       NOT NULL COMMENT '被扣减批次ID',
    batch_no            VARCHAR(64)  NOT NULL COMMENT '被扣减批次号/信用编号',
    amount              DECIMAL(18,3) NOT NULL COMMENT '本笔自该批次扣减数量，单位吨CO2e',
    seq_no              INT          NOT NULL COMMENT '同笔申请内扣减顺序号',
    CONSTRAINT fk_retirement_request FOREIGN KEY (surrender_request_id)
        REFERENCES surrender_request (id)
);
CREATE INDEX IF NOT EXISTS idx_retirement_request ON retirement_record (surrender_request_id);

-- 清缴凭证：注销完成后生成，凭证号唯一且重复查询稳定
CREATE TABLE IF NOT EXISTS surrender_certificate (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    certificate_no    VARCHAR(64)   NOT NULL COMMENT '清缴凭证号，全局唯一',
    request_id        VARCHAR(64)   NOT NULL COMMENT '对应清缴申请幂等键',
    enterprise_id     BIGINT        NOT NULL COMMENT '企业ID',
    compliance_year   INT           NOT NULL COMMENT '履约年度',
    total_retired     DECIMAL(18,3) NOT NULL COMMENT '注销总量（配额+抵消），单位吨CO2e',
    allowance_retired DECIMAL(18,3) NOT NULL COMMENT '配额注销量，单位吨CO2e',
    offset_retired    DECIMAL(18,3) NOT NULL COMMENT '抵消核销量，单位吨CO2e',
    issued_utc        TIMESTAMP     NOT NULL COMMENT '凭证签发时刻（UTC）',
    CONSTRAINT uk_certificate_no UNIQUE (certificate_no),
    CONSTRAINT uk_certificate_request UNIQUE (request_id)
);
