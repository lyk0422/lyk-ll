-- 携号转网本地流转服务建表脚本（H2，MODE=MySQL；时间均为 UTC，精度毫秒）

CREATE TABLE IF NOT EXISTS subscriber_profile (
    msisdn VARCHAR(32) NOT NULL PRIMARY KEY,
    has_arrears TINYINT(1) NOT NULL DEFAULT 0,
    in_contract TINYINT(1) NOT NULL DEFAULT 0,
    last_port_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE subscriber_profile IS '用户资格档案（合成数据）：欠费、在网约定期限与上次携转时间';
COMMENT ON COLUMN subscriber_profile.msisdn IS '手机号码（合成数据）';
COMMENT ON COLUMN subscriber_profile.has_arrears IS '是否存在欠费：0 无欠费，1 有欠费';
COMMENT ON COLUMN subscriber_profile.in_contract IS '是否处于在网约定期限内：0 否，1 是';
COMMENT ON COLUMN subscriber_profile.last_port_at IS '上次携转完成时刻（UTC，毫秒）；NULL 表示从未携转';
COMMENT ON COLUMN subscriber_profile.updated_at IS '档案最近更新时间（UTC）';

CREATE TABLE IF NOT EXISTS portability_flow (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    msisdn VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    auth_code VARCHAR(16) NULL,
    auth_code_issued_at TIMESTAMP NULL,
    auth_code_expires_at TIMESTAMP NULL,
    active_key VARCHAR(32) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_portability_flow_request UNIQUE (request_id),
    CONSTRAINT uk_portability_flow_active UNIQUE (active_key)
);
COMMENT ON TABLE portability_flow IS '携号转网流程主表；同一号码任一时刻仅允许一个在途流程';
COMMENT ON COLUMN portability_flow.request_id IS '申请幂等请求标识';
COMMENT ON COLUMN portability_flow.msisdn IS '携转手机号码';
COMMENT ON COLUMN portability_flow.status IS '流程状态：APPLIED 已申请 / CODE_ISSUED 已签码 / DONOR_CONFIRMED 携出方已确认 / ACTIVATED 已生效（终态）/ CANCELLED 已撤销（终态）';
COMMENT ON COLUMN portability_flow.auth_code IS '授权码（合成数据）；流程生效后立即作废';
COMMENT ON COLUMN portability_flow.auth_code_issued_at IS '授权码签发时刻（UTC，毫秒）';
COMMENT ON COLUMN portability_flow.auth_code_expires_at IS '授权码失效时刻（UTC，毫秒），签发后 24 小时，恰满即失效';
COMMENT ON COLUMN portability_flow.active_key IS '在途唯一键：在途时等于 msisdn，进入终态后置 NULL，保证同号唯一在途';
COMMENT ON COLUMN portability_flow.version IS '乐观锁版本号，每次状态流转加 1';
COMMENT ON COLUMN portability_flow.created_at IS '流程创建时刻（UTC）';
COMMENT ON COLUMN portability_flow.updated_at IS '流程最近变更时刻（UTC）';

CREATE TABLE IF NOT EXISTS portability_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    flow_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    detail VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE portability_event IS '流程事件表，按 id 升序即事件发生顺序';
COMMENT ON COLUMN portability_event.flow_id IS '所属流程标识';
COMMENT ON COLUMN portability_event.event_type IS '事件类型：APPLIED / AUTH_CODE_ISSUED / DONOR_CONFIRMED / BROADCAST_DEFERRED / ACTIVATED / CANCELLED';
COMMENT ON COLUMN portability_event.detail IS '事件补充说明（如窗口外原因、资格拒绝原因）';
COMMENT ON COLUMN portability_event.created_at IS '事件发生时刻（UTC）';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY,
    endpoint VARCHAR(80) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    http_status INT NOT NULL,
    response_body CLOB NULL,
    flow_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE idempotency_record IS '幂等记录表：requestId 唯一，指纹覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等请求标识（全局唯一）';
COMMENT ON COLUMN idempotency_record.endpoint IS '首次处理该请求的接口';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN idempotency_record.http_status IS '首次处理的 HTTP 状态码，重放原样返回';
COMMENT ON COLUMN idempotency_record.response_body IS '首次处理的响应报文（JSON），重放原样返回';
COMMENT ON COLUMN idempotency_record.flow_id IS '关联流程标识；资格拒绝等无流程场景为 NULL';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时刻（UTC）';
