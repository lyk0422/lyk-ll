-- 支付日切批次与清算对账服务建表脚本（H2 MySQL 兼容模式 / MySQL 通用）
-- 时间字段均存 UTC；金额字段单位为分（BIGINT）；费率为基点（万分之一）。

CREATE TABLE IF NOT EXISTS merchant (
    merchant_no   VARCHAR(32)  NOT NULL COMMENT '商户号',
    merchant_name VARCHAR(128) NOT NULL COMMENT '商户名称（合成数据）',
    fee_rate_bp   INT          NOT NULL COMMENT '手续费率，单位基点（1bp=0.01%）',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (merchant_no)
) COMMENT='商户表（合成数据）';

CREATE TABLE IF NOT EXISTS pay_batch (
    id         BIGINT      AUTO_INCREMENT PRIMARY KEY,
    batch_date DATE        NOT NULL COMMENT '批次日期（UTC 日切归属日，16:00 起归次日）',
    status     VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '批次状态：OPEN开放/CLOSED已封账（终态）',
    closed_at  TIMESTAMP   NULL COMMENT '封账时间（UTC），未封账为 NULL',
    CONSTRAINT uk_pay_batch_date UNIQUE (batch_date)
) COMMENT='日切批次表';

CREATE TABLE IF NOT EXISTS pay_transaction (
    id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
    request_id  VARCHAR(64) NOT NULL COMMENT '入账请求幂等键',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹：requestId+流水号+金额+商户号 哈希',
    serial_no   VARCHAR(64) NOT NULL COMMENT '渠道流水号，全局唯一，只允许入账一次',
    merchant_no VARCHAR(32) NOT NULL COMMENT '商户号',
    amount      BIGINT      NOT NULL COMMENT '交易金额，单位分',
    fee         BIGINT      NOT NULL COMMENT '手续费，单位分',
    txn_time    TIMESTAMP   NOT NULL COMMENT '交易时间（UTC）',
    batch_id    BIGINT      NULL COMMENT '归属批次ID；挂账（SUSPENDED）时为 NULL',
    status      VARCHAR(16) NOT NULL COMMENT '状态：POSTED已入账/SUSPENDED挂账',
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    CONSTRAINT uk_pay_txn_request UNIQUE (request_id),
    CONSTRAINT uk_pay_txn_serial UNIQUE (serial_no)
) COMMENT='支付交易表';

CREATE TABLE IF NOT EXISTS settlement_bill (
    id           BIGINT   AUTO_INCREMENT PRIMARY KEY,
    batch_id     BIGINT   NOT NULL COMMENT '批次ID',
    merchant_no  VARCHAR(32) NOT NULL COMMENT '商户号',
    txn_count    INT      NOT NULL COMMENT '交易笔数',
    total_amount BIGINT   NOT NULL COMMENT '交易总额，单位分',
    total_fee    BIGINT   NOT NULL COMMENT '手续费总额，单位分',
    net_amount   BIGINT   NOT NULL COMMENT '清算净额（交易额-手续费），单位分',
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间（UTC）',
    CONSTRAINT uk_bill_batch_merchant UNIQUE (batch_id, merchant_no)
) COMMENT='清算单表（每批次每商户一单）';

CREATE TABLE IF NOT EXISTS recon_difference (
    id             BIGINT   AUTO_INCREMENT PRIMARY KEY,
    batch_id       BIGINT   NOT NULL COMMENT '对账批次ID',
    serial_no      VARCHAR(64) NOT NULL COMMENT '渠道流水号',
    diff_type      VARCHAR(24) NOT NULL COMMENT '差异类型：LOCAL_EXCESS本地多账/CHANNEL_EXCESS渠道多账/AMOUNT_MISMATCH金额差错',
    local_amount   BIGINT   NULL COMMENT '本地金额，单位分；渠道多账时为 NULL',
    channel_amount BIGINT   NULL COMMENT '渠道金额，单位分；本地多账时为 NULL',
    status         VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '状态：OPEN待处理/RESOLVED已处理',
    resolution     VARCHAR(16) NULL COMMENT '处理方式：WRITE_OFF核销/REPOST补记/REFUND退款；未处理为 NULL',
    resolved_by    VARCHAR(64) NULL COMMENT '处理人；未处理为 NULL',
    resolved_at    TIMESTAMP NULL COMMENT '处理时间（UTC）；未处理为 NULL',
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    CONSTRAINT uk_diff_batch_serial UNIQUE (batch_id, serial_no)
) COMMENT='对账差异表';

CREATE TABLE IF NOT EXISTS recon_resolution_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    difference_id BIGINT NOT NULL COMMENT '差异记录ID',
    resolution    VARCHAR(16) NOT NULL COMMENT '处理方式：WRITE_OFF核销/REPOST补记/REFUND退款',
    operator      VARCHAR(64) NOT NULL COMMENT '处理人',
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '处理留痕时间（UTC）'
) COMMENT='差异处理留痕表';
