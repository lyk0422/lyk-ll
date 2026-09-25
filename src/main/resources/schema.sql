-- 携号转网流转服务建表脚本（H2 MySQL 兼容模式，语法同时兼容 MySQL）
CREATE TABLE IF NOT EXISTS portability_flow (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '流程主键',
    request_id VARCHAR(64) NOT NULL COMMENT '申请幂等请求号',
    msisdn VARCHAR(32) NOT NULL COMMENT '携转号码（合成数据）',
    donor_operator VARCHAR(64) NOT NULL COMMENT '携出方运营商',
    recipient_operator VARCHAR(64) NOT NULL COMMENT '携入方运营商',
    state VARCHAR(32) NOT NULL COMMENT '流程状态：AUTH_ISSUED 已签码待确认 / CONFIRMED 待生效 / EFFECTIVE 已生效（终态） / CANCELLED 已撤销（终态）',
    auth_code VARCHAR(64) NULL COMMENT '授权码，自签发时刻起 24 小时有效（UTC），恰满即失效',
    auth_issued_at TIMESTAMP NULL COMMENT '授权码签发时刻（UTC）',
    auth_expires_at TIMESTAMP NULL COMMENT '授权码失效时刻（UTC），为空表示未签发',
    active_msisdn VARCHAR(32) NULL COMMENT '在途号码占位：在途期间等于 msisdn，进入终态置 NULL；唯一约束保证同号单在途',
    created_at TIMESTAMP NOT NULL COMMENT '流程创建时刻（UTC）',
    updated_at TIMESTAMP NOT NULL COMMENT '流程最近更新时刻（UTC）',
    CONSTRAINT uk_portability_active_msisdn UNIQUE (active_msisdn)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    endpoint VARCHAR(32) NOT NULL COMMENT '幂等作用域：APPLY 申请 / BROADCAST 生效广播',
    request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等请求号',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求指纹：requestId 与全部业务字段的哈希，同键异参判 409',
    response_status INT NOT NULL COMMENT '首次响应的 HTTP 状态码',
    response_body CLOB NOT NULL COMMENT '首次响应报文快照，重放原样返回',
    flow_id BIGINT NULL COMMENT '关联流程主键',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻（UTC）',
    PRIMARY KEY (endpoint, request_id)
);

CREATE TABLE IF NOT EXISTS flow_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件主键，按序递增即流程事件顺序',
    flow_id BIGINT NOT NULL COMMENT '所属流程主键',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：FLOW_APPLIED/AUTH_CODE_ISSUED/DONOR_CONFIRMED/BROADCAST_EFFECTIVE/FLOW_CANCELLED',
    detail VARCHAR(512) NULL COMMENT '事件补充说明，可为空',
    created_at TIMESTAMP NOT NULL COMMENT '事件发生时刻（UTC）'
);

CREATE TABLE IF NOT EXISTS effective_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '生效记录主键',
    flow_id BIGINT NOT NULL COMMENT '所属流程主键，唯一约束保证同一流程仅一条生效记录',
    msisdn VARCHAR(32) NOT NULL COMMENT '生效号码（合成数据）',
    broadcast_request_id VARCHAR(64) NOT NULL COMMENT '触发本次生效的广播幂等请求号',
    effective_at TIMESTAMP NOT NULL COMMENT '生效时刻（UTC，位于每日 02:00-04:00 窗口内）',
    CONSTRAINT uk_effective_flow UNIQUE (flow_id)
);
