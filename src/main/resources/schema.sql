-- 携号转网本地演示库表结构（H2 MySQL 兼容模式，全部为合成数据）
-- 时间字段统一为 UTC 口径的 TIMESTAMP；空值含义见各列注释。

-- 用户（号码）当前状态表：资格校验的数据来源，仅存放合成数据
CREATE TABLE IF NOT EXISTS subscriber (
    msisdn            VARCHAR(20)  NOT NULL COMMENT '手机号码（合成数据）',
    current_operator  VARCHAR(32)  NOT NULL COMMENT '当前运营商（携出方）',
    arrears_amount    DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '欠费金额（元），0 表示无欠费',
    contract_end_date DATE         NULL COMMENT '在网约定期限截止日（UTC 日期）；NULL 表示无在网约定',
    last_port_time    TIMESTAMP    NULL COMMENT '上次携转完成时刻（UTC）；NULL 表示从未携转',
    PRIMARY KEY (msisdn)
) COMMENT='号码用户状态表（合成数据）';

-- 携号转网流程主表；request_id 为幂等键，fingerprint 覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS port_request (
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '流程主键',
    request_id           VARCHAR(64)  NOT NULL COMMENT '申请幂等键（客户端 requestId）',
    fingerprint          VARCHAR(128) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的哈希',
    msisdn               VARCHAR(20)  NOT NULL COMMENT '携转号码',
    donor_operator       VARCHAR(32)  NOT NULL COMMENT '携出方运营商',
    recipient_operator   VARCHAR(32)  NOT NULL COMMENT '携入方运营商',
    status               VARCHAR(16)  NOT NULL COMMENT '流程状态：APPLIED/CODE_ISSUED/CONFIRMED/EFFECTIVE/CANCELLED',
    auth_code            VARCHAR(16)  NULL COMMENT '授权码；流程生效后立即作废置 NULL',
    auth_code_issued_at  TIMESTAMP    NULL COMMENT '授权码签发时刻（UTC）；NULL 表示未签发',
    auth_code_expires_at TIMESTAMP    NULL COMMENT '授权码失效时刻（UTC），签发后 24 小时，恰满即失效',
    effective_at         TIMESTAMP    NULL COMMENT '生效时刻（UTC）；NULL 表示未生效',
    cancelled_at         TIMESTAMP    NULL COMMENT '撤销时刻（UTC）；NULL 表示未撤销',
    cancel_reason        VARCHAR(255) NULL COMMENT '撤销原因；NULL 表示未撤销',
    created_at           TIMESTAMP    NOT NULL COMMENT '创建时刻（UTC）',
    updated_at           TIMESTAMP    NOT NULL COMMENT '最近更新时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_port_request_request_id (request_id)
) COMMENT='携号转网流程表';

-- 在途流程唯一性守卫表：同一号码任一时刻只允许一条在途记录
CREATE TABLE IF NOT EXISTS port_inflight (
    msisdn          VARCHAR(20) NOT NULL COMMENT '携转号码',
    port_request_id BIGINT      NOT NULL COMMENT '在途流程主键',
    created_at      TIMESTAMP   NOT NULL COMMENT '进入在途时刻（UTC）',
    PRIMARY KEY (msisdn)
) COMMENT='在途流程守卫表（号码级唯一）';

-- 生效记录表：同一流程只产生一条；request_id 为广播幂等键
CREATE TABLE IF NOT EXISTS effectiveness_record (
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '生效记录主键',
    port_request_id BIGINT      NOT NULL COMMENT '流程主键',
    request_id      VARCHAR(64) NOT NULL COMMENT '广播幂等键（客户端 requestId）',
    fingerprint     VARCHAR(128) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的哈希',
    msisdn          VARCHAR(20) NOT NULL COMMENT '携转号码',
    effective_at    TIMESTAMP   NOT NULL COMMENT '生效时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_effectiveness_request (port_request_id),
    UNIQUE KEY uk_effectiveness_request_id (request_id)
) COMMENT='携转生效记录表';

-- 流程事件表：按主键顺序即事件发生顺序
CREATE TABLE IF NOT EXISTS port_event (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '事件主键（有序）',
    port_request_id BIGINT       NOT NULL COMMENT '流程主键',
    event_type      VARCHAR(32)  NOT NULL COMMENT '事件类型：APPLIED/AUTH_CODE_ISSUED/CONFIRMED/EFFECTIVE/CANCELLED',
    detail          VARCHAR(500) NULL COMMENT '事件说明；NULL 表示无补充说明',
    created_at      TIMESTAMP    NOT NULL COMMENT '事件时刻（UTC）',
    PRIMARY KEY (id),
    KEY idx_port_event_request (port_request_id)
) COMMENT='携转流程事件表';
