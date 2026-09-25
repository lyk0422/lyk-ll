-- 疫苗批签发资料审查与公示服务建表脚本（H2 MODE=MySQL 兼容，时间均为 UTC）
CREATE TABLE IF NOT EXISTS vaccine_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，合成数据，全局唯一',
    enterprise VARCHAR(128) NOT NULL COMMENT '生产企业名称',
    product_name VARCHAR(128) NOT NULL COMMENT '品种名称',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_vaccine_batch_no UNIQUE (batch_no)
) COMMENT='疫苗批次表';

CREATE TABLE IF NOT EXISTS review_process (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '关联疫苗批次ID',
    request_id VARCHAR(64) NOT NULL COMMENT '申报幂等请求ID',
    fingerprint VARCHAR(128) NOT NULL COMMENT '申报请求指纹（requestId+全部业务字段SHA-256）',
    status VARCHAR(32) NOT NULL COMMENT '流程状态：PENDING_CORRECTION待补正/UNDER_REVIEW待评定/EVALUATED_FAIL评定不合格/IN_PUBLICITY公示中/OBJECTION_REVIEW异议复核/ISSUED已签发/ARCHIVED签发归档/WITHDRAWN逾期撤回',
    missing_items CLOB COMMENT '一次性告知的缺项清单（JSON），无缺项为NULL',
    correction_deadline DATE COMMENT '补正截止日（UTC自然日，第5个工作日当日有效），无需补正为NULL',
    evaluation_result VARCHAR(8) COMMENT '综合评定结论：PASS合格/FAIL不合格，未评定为NULL',
    fail_reason VARCHAR(512) COMMENT '评定不合格原因，合格为NULL',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
    updated_at TIMESTAMP NOT NULL COMMENT '更新时间（UTC）',
    CONSTRAINT uk_review_process_batch UNIQUE (batch_id),
    CONSTRAINT uk_review_process_request UNIQUE (request_id)
) COMMENT='批签发审查流程表，同一批号仅允许一个流程';

CREATE TABLE IF NOT EXISTS document_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    process_id BIGINT NOT NULL COMMENT '关联审查流程ID',
    item_code VARCHAR(64) NOT NULL COMMENT '资料目录项编码',
    item_name VARCHAR(128) NOT NULL COMMENT '资料目录项名称',
    required_flag TINYINT NOT NULL COMMENT '是否必交：1必交/0选交',
    submitted_flag TINYINT NOT NULL COMMENT '是否已提交：1已交/0未交',
    submitted_at TIMESTAMP COMMENT '提交时间（UTC），未提交为NULL',
    CONSTRAINT uk_document_item UNIQUE (process_id, item_code)
) COMMENT='资料核对表，可按目录复算缺项';

CREATE TABLE IF NOT EXISTS inspection_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    process_id BIGINT NOT NULL COMMENT '关联审查流程ID',
    item_code VARCHAR(64) NOT NULL COMMENT '检验项目编码',
    item_name VARCHAR(128) NOT NULL COMMENT '检验项目名称',
    critical_flag TINYINT NOT NULL COMMENT '是否关键项目：1关键/0一般',
    limit_value DECIMAL(18,6) NOT NULL COMMENT '限值，测定值小于等于限值判合格（恰等限值判合格）',
    measured_value DECIMAL(18,6) NOT NULL COMMENT '测定值',
    qualified_flag TINYINT NOT NULL COMMENT '是否合格：1合格/0不合格，由限值与测定值复算',
    CONSTRAINT uk_inspection_item UNIQUE (process_id, item_code)
) COMMENT='检验链项目表，合格判定可复算';

CREATE TABLE IF NOT EXISTS publicity_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    process_id BIGINT NOT NULL COMMENT '关联审查流程ID',
    start_at TIMESTAMP NOT NULL COMMENT '公示开始时间（UTC）',
    end_at TIMESTAMP NOT NULL COMMENT '公示期满时间（UTC，开始后7个自然日，恰第7日期满生效）',
    status VARCHAR(32) NOT NULL COMMENT '公示状态：IN_PROGRESS公示中/OBJECTION异议复核/EFFECTIVE期满生效',
    objection_request_id VARCHAR(64) COMMENT '异议受理幂等请求ID，无异议为NULL',
    objection_reason VARCHAR(512) COMMENT '异议理由，无异议为NULL',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_publicity_process UNIQUE (process_id)
) COMMENT='公示记录表，期满状态可按UTC日界复算';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等请求ID',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求指纹（requestId+全部业务字段SHA-256）',
    http_status INT NOT NULL COMMENT '首次响应HTTP状态码',
    response_body CLOB NOT NULL COMMENT '首次响应报文，同键同参重放时原样返回',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）'
) COMMENT='幂等记录表，同键异参返回409且不改变审查状态';

CREATE TABLE IF NOT EXISTS enterprise_suspension (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    enterprise VARCHAR(128) NOT NULL COMMENT '生产企业名称',
    product_name VARCHAR(128) NOT NULL COMMENT '品种名称',
    reason VARCHAR(512) NOT NULL COMMENT '暂停原因（同企业同品种连续3批不合格）',
    status VARCHAR(32) NOT NULL COMMENT '状态：SUSPENDED暂停受理并转核查',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）'
) COMMENT='企业暂停受理记录表';

CREATE TABLE IF NOT EXISTS late_document_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '关联疫苗批次ID',
    request_id VARCHAR(64) NOT NULL COMMENT '迟到资料请求ID',
    detail VARCHAR(512) NOT NULL COMMENT '迟到资料说明',
    created_at TIMESTAMP NOT NULL COMMENT '记录时间（UTC）'
) COMMENT='迟到资料事件表，归档后迟到资料只记事件不改变状态';
