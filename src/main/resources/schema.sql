-- 化妆品备案与功效宣称依据审查服务建表脚本（H2 MySQL 兼容模式，合成数据）。
-- 所有时间戳列均按 UTC 存储；DATE 列为 UTC 日界。

CREATE TABLE IF NOT EXISTS product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '产品主键',
    name VARCHAR(128) NOT NULL COMMENT '产品名称（合成数据）',
    applicant VARCHAR(128) NOT NULL COMMENT '备案人名称（合成数据）',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '产品状态：ACTIVE正常；CANCELLED已注销（终态，注销后新宣称审查一律拒绝）',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）'
) COMMENT='产品主档（合成数据）';

CREATE TABLE IF NOT EXISTS efficacy_catalog (
    code VARCHAR(32) PRIMARY KEY COMMENT '功效编码',
    name VARCHAR(64) NOT NULL COMMENT '功效名称',
    special BOOLEAN NOT NULL COMMENT '是否特殊功效：特殊功效须上传对应功效评价试验报告',
    supported BOOLEAN NOT NULL COMMENT '是否支持宣称：不支持的功效宣称一律拒绝'
) COMMENT='功效宣称目录（合成数据）';

CREATE TABLE IF NOT EXISTS restricted_ingredient (
    code VARCHAR(32) PRIMARY KEY COMMENT '限用成分编码',
    name VARCHAR(64) NOT NULL COMMENT '限用成分名称',
    max_concentration DECIMAL(10,4) NOT NULL COMMENT '浓度限值（质量分数%）：不得超过，恰等限值合法'
) COMMENT='限用成分目录（合成数据）';

CREATE TABLE IF NOT EXISTS registration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '备案主键',
    registration_no VARCHAR(64) NOT NULL COMMENT '备案编号：实质变更生成新编号，简易变更引用原编号',
    product_id BIGINT NOT NULL COMMENT '所属产品ID',
    version INT NOT NULL COMMENT '版本号，同一产品自1递增',
    change_type VARCHAR(16) NOT NULL COMMENT '备案类型：INITIAL首次备案；SUBSTANTIAL实质变更（配方/功效宣称，重新审查）；SIMPLE简易变更（包装文字类）',
    previous_id BIGINT NULL COMMENT '上一版本备案ID，构成变更链；首次备案为NULL',
    status VARCHAR(24) NOT NULL COMMENT '状态：PUBLICITY公示中；OBJECTION_REVIEW异议审查；EFFECTIVE已生效；REJECTED审查不通过；SUPERSEDED已被新版本替代；CANCELLED已注销（终态）',
    packaging_text VARCHAR(512) NOT NULL COMMENT '包装文字内容',
    publicity_start_date DATE NULL COMMENT '公示起始日（UTC日界，即形式审查通过日）',
    publicity_deadline TIMESTAMP NULL COMMENT '公示期满时刻（UTC）：起始日第10日24:00，恰到期满自动生效',
    effective_at TIMESTAMP NULL COMMENT '生效时间（UTC），未生效为NULL',
    cancelled_at TIMESTAMP NULL COMMENT '注销时间（UTC），未注销为NULL',
    reject_reason VARCHAR(512) NULL COMMENT '审查不通过原因汇总，通过为NULL',
    request_id VARCHAR(64) NOT NULL COMMENT '提交/变更幂等键',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT fk_registration_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT uk_registration_product_version UNIQUE (product_id, version)
) COMMENT='化妆品备案记录（含变更版本链）';

CREATE TABLE IF NOT EXISTS registration_claim (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    registration_id BIGINT NOT NULL COMMENT '备案ID',
    efficacy_code VARCHAR(32) NOT NULL COMMENT '功效宣称编码',
    CONSTRAINT fk_claim_registration FOREIGN KEY (registration_id) REFERENCES registration (id)
) COMMENT='备案功效宣称明细';

CREATE TABLE IF NOT EXISTS registration_ingredient (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    registration_id BIGINT NOT NULL COMMENT '备案ID',
    ingredient_code VARCHAR(32) NOT NULL COMMENT '成分编码',
    concentration DECIMAL(10,4) NOT NULL COMMENT '浓度（质量分数%）',
    CONSTRAINT fk_ingredient_registration FOREIGN KEY (registration_id) REFERENCES registration (id)
) COMMENT='备案配方成分明细';

CREATE TABLE IF NOT EXISTS test_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    registration_id BIGINT NOT NULL COMMENT '备案ID',
    efficacy_code VARCHAR(32) NOT NULL COMMENT '对应功效编码',
    conclusion VARCHAR(16) NOT NULL COMMENT '报告结论：SUPPORT支持宣称；NOT_SUPPORT不支持宣称',
    issued_date DATE NOT NULL COMMENT '报告出具日期（UTC日界）：须为备案提交日前3年内，恰满3年当日有效',
    lab VARCHAR(128) NOT NULL COMMENT '出具机构（合成数据）',
    CONSTRAINT fk_report_registration FOREIGN KEY (registration_id) REFERENCES registration (id)
) COMMENT='功效评价试验报告（合成数据）';

CREATE TABLE IF NOT EXISTS objection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    registration_id BIGINT NOT NULL COMMENT '备案ID',
    content VARCHAR(512) NOT NULL COMMENT '异议内容',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING待审查；DISMISSED异议不成立；UPHELD异议成立',
    created_at TIMESTAMP NOT NULL COMMENT '提出时间（UTC），须早于公示期满时刻',
    CONSTRAINT fk_objection_registration FOREIGN KEY (registration_id) REFERENCES registration (id)
) COMMENT='公示异议记录';

CREATE TABLE IF NOT EXISTS review_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    registration_id BIGINT NOT NULL COMMENT '备案ID',
    item VARCHAR(64) NOT NULL COMMENT '审查项名称',
    result VARCHAR(8) NOT NULL COMMENT '审查结果：PASS通过；FAIL不通过',
    detail VARCHAR(512) NOT NULL COMMENT '审查明细说明（含期限口径、限值口径，可复算）',
    created_at TIMESTAMP NOT NULL COMMENT '审查时间（UTC）',
    CONSTRAINT fk_review_registration FOREIGN KEY (registration_id) REFERENCES registration (id)
) COMMENT='形式审查明细，可复算';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等键（提交与变更共用）',
    endpoint VARCHAR(64) NOT NULL COMMENT '首次处理该键的接口',
    fingerprint VARCHAR(128) NOT NULL COMMENT '指纹：requestId与全部业务字段的SHA-256；同键同参返回首次结果，同键异参返回409',
    response_status INT NULL COMMENT '首次处理结果的HTTP状态码',
    response_body CLOB NULL COMMENT '首次处理结果的响应体（JSON）',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='幂等处理记录';
