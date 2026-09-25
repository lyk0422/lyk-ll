-- 保税货物区间转关与核销服务建表脚本（H2 MySQL 兼容模式）
-- 时间字段统一为 UTC 时间戳（TIMESTAMP），数量单位为最小整数件数，单价单位为元。

CREATE TABLE IF NOT EXISTS enterprise (
    id VARCHAR(64) PRIMARY KEY COMMENT '企业编号（合成数据）',
    name VARCHAR(128) NOT NULL COMMENT '企业名称',
    frozen TINYINT NOT NULL DEFAULT 0 COMMENT '是否因超时未核销被冻结：0 正常，1 冻结（冻结期间新申请被拒绝）'
);

CREATE TABLE IF NOT EXISTS warehouse (
    id VARCHAR(64) PRIMARY KEY COMMENT '保税仓编号（合成数据）',
    name VARCHAR(128) NOT NULL COMMENT '保税仓名称'
);

CREATE TABLE IF NOT EXISTS inventory (
    warehouse_id VARCHAR(64) NOT NULL COMMENT '保税仓编号',
    hs_code VARCHAR(32) NOT NULL COMMENT '商品税号',
    total_qty BIGINT NOT NULL COMMENT '账面总数量（件）',
    frozen_qty BIGINT NOT NULL DEFAULT 0 COMMENT '核注冻结数量（件），可用数量 = total_qty - frozen_qty',
    PRIMARY KEY (warehouse_id, hs_code)
);

CREATE TABLE IF NOT EXISTS transfer_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '转关单主键',
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求号，全局唯一',
    enterprise_id VARCHAR(64) NOT NULL COMMENT '申请企业编号',
    from_warehouse VARCHAR(64) NOT NULL COMMENT '起运仓编号',
    to_warehouse VARCHAR(64) NOT NULL COMMENT '目的仓编号',
    status VARCHAR(32) NOT NULL COMMENT '状态：APPLIED 已申请/ENDORSED 已核注/IN_TRANSIT 在途/CLOSED 已结案/CANCELLED 已撤销/ABNORMAL 异常',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段规范化后的摘要',
    endorse_time TIMESTAMP NULL COMMENT '核注时间（UTC），未核注为 NULL',
    deadline TIMESTAMP NULL COMMENT '核销截止时间（UTC）= 核注时间 + 48 小时，未核注为 NULL',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
    updated_at TIMESTAMP NOT NULL COMMENT '最近变更时间（UTC）',
    CONSTRAINT uk_transfer_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS transfer_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '商品项主键',
    order_id BIGINT NOT NULL COMMENT '所属转关单主键',
    hs_code VARCHAR(32) NOT NULL COMMENT '商品税号',
    quantity BIGINT NOT NULL COMMENT '申请/核注数量（件）',
    unit_price DECIMAL(19,4) NOT NULL COMMENT '单价（元）',
    verified_qty BIGINT NOT NULL DEFAULT 0 COMMENT '累计已核销数量（件），不得超过 quantity'
);

CREATE TABLE IF NOT EXISTS status_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '留痕主键',
    order_id BIGINT NOT NULL COMMENT '转关单主键',
    from_status VARCHAR(32) NULL COMMENT '变更前状态，初始创建为 NULL',
    to_status VARCHAR(32) NOT NULL COMMENT '变更后状态',
    changed_at TIMESTAMP NOT NULL COMMENT '变更时间（UTC）',
    note VARCHAR(256) NULL COMMENT '变更说明，无说明为 NULL'
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) PRIMARY KEY COMMENT '幂等请求号',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求指纹，同键异参时用于判定 409',
    response_body CLOB NOT NULL COMMENT '首次请求成功时的响应报文（JSON）',
    created_at TIMESTAMP NOT NULL COMMENT '首次记录时间（UTC）'
);
