-- 保税货物区间转关与核销服务建表脚本（H2 MySQL 兼容模式）
-- 所有时间字段均为 UTC 瞬时时间；数量单位为商品最小计量单位；金额币种为合成数据演示用，不做币种约束。

-- 转关单主表
-- status 取值：APPLIED(已申请) / IN_TRANSIT(在途) / FROZEN(企业异常冻结) / EXCEPTION(超时异常) / CLOSED(结案) / CANCELLED(已撤销)
-- endorsed_at / verify_deadline 仅核注后非空；verify_deadline = endorsed_at + 48 小时（UTC）
CREATE TABLE IF NOT EXISTS transfer_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段规范化后的 SHA-256',
    enterprise_id VARCHAR(64) NOT NULL COMMENT '企业编号（合成数据）',
    from_warehouse VARCHAR(64) NOT NULL COMMENT '起运仓编码',
    to_warehouse VARCHAR(64) NOT NULL COMMENT '目的仓编码',
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL DEFAULT 0 COMMENT '状态变更序号，每次变更递增，与历史留痕 seq 对应',
    endorsed_at TIMESTAMP NULL COMMENT '核注时间（UTC），未核注为空',
    verify_deadline TIMESTAMP NULL COMMENT '核销截止（UTC）= 核注后 48 小时，未核注为空',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_transfer_order_request UNIQUE (request_id)
);

-- 转关单商品项明细
-- quantity 为申请并整体核注的数量；verified_quantity 为累计已核销数量，不得超过 quantity
CREATE TABLE IF NOT EXISTS transfer_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    tariff_code VARCHAR(32) NOT NULL COMMENT '商品税号',
    quantity BIGINT NOT NULL COMMENT '核注数量',
    unit_price DECIMAL(19,4) NOT NULL COMMENT '单价',
    verified_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '累计已核销数量'
);

-- 仓库库存表（合成数据），quantity 为可用库存，核注时冻结扣减、撤销时恢复
CREATE TABLE IF NOT EXISTS warehouse_stock (
    warehouse_code VARCHAR(64) NOT NULL,
    tariff_code VARCHAR(32) NOT NULL COMMENT '商品税号',
    quantity BIGINT NOT NULL COMMENT '可用库存数量',
    PRIMARY KEY (warehouse_code, tariff_code)
);

-- 核销幂等记录：request_id 唯一，response_body 保存首次核销结果快照用于同键重放
CREATE TABLE IF NOT EXISTS transfer_verification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与核销明细规范化后的 SHA-256',
    order_id BIGINT NOT NULL,
    response_body CLOB NOT NULL COMMENT '首次核销响应 JSON 快照',
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_transfer_verification_request UNIQUE (request_id)
);

-- 单据状态变更留痕，按 (order_id, seq) 严格递增可查
CREATE TABLE IF NOT EXISTS transfer_status_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    seq INT NOT NULL COMMENT '变更序号，与 transfer_order.version 对应',
    from_status VARCHAR(16) NULL COMMENT '原状态，单据悉数创建时为空',
    to_status VARCHAR(16) NOT NULL,
    changed_at TIMESTAMP NOT NULL COMMENT '变更时间（UTC）',
    note VARCHAR(255) NULL
);

-- 企业账户表：frozen=true 表示存在未解除的超时异常，冻结期间新申请拒绝、在途单冻结
CREATE TABLE IF NOT EXISTS enterprise_account (
    enterprise_id VARCHAR(64) PRIMARY KEY,
    frozen BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP NOT NULL
);
