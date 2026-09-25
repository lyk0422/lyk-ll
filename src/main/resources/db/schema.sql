-- 支付日切批次与清算对账：H2 内存库（MODE=MySQL）建表脚本，服务启动时自动执行。
-- 金额单位：元；DECIMAL(15,2)。时间列一律存 UTC。

CREATE TABLE IF NOT EXISTS merchant (
    merchant_no   VARCHAR(32)  NOT NULL PRIMARY KEY,
    merchant_name VARCHAR(128) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE  merchant IS '商户表（合成数据）';
COMMENT ON COLUMN merchant.merchant_no IS '商户号';
COMMENT ON COLUMN merchant.merchant_name IS '商户名称';
COMMENT ON COLUMN merchant.status IS '状态：ACTIVE 正常，DISABLED 停用';
COMMENT ON COLUMN merchant.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS merchant_fee_rate (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    merchant_no VARCHAR(32)  NOT NULL,
    fee_rate    DECIMAL(6,4) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_fee_merchant UNIQUE (merchant_no)
);
COMMENT ON TABLE  merchant_fee_rate IS '商户手续费费率表（合成数据，单笔固定费率）';
COMMENT ON COLUMN merchant_fee_rate.merchant_no IS '商户号';
COMMENT ON COLUMN merchant_fee_rate.fee_rate IS '手续费费率，如 0.0060 表示千分之六';
COMMENT ON COLUMN merchant_fee_rate.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS payment_batch (
    batch_date  CHAR(10)    NOT NULL PRIMARY KEY,
    status      VARCHAR(8)  NOT NULL DEFAULT 'OPEN',
    closed_at   TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE  payment_batch IS '日切批次表，batch_date 为 UTC 归属日（yyyy-MM-dd）';
COMMENT ON COLUMN payment_batch.batch_date IS '批次归属日（UTC，yyyy-MM-dd）；16:00 前交易归当日，16:00 及以后归次日';
COMMENT ON COLUMN payment_batch.status IS '批次状态：OPEN 开放入账，CLOSED 已封账（终态）';
COMMENT ON COLUMN payment_batch.closed_at IS '封账时间（UTC），未封账为空';
COMMENT ON COLUMN payment_batch.created_at IS '批次创建时间（UTC）';

CREATE TABLE IF NOT EXISTS payment_transaction (
    id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id        VARCHAR(64)   NOT NULL,
    fingerprint       VARCHAR(64)   NOT NULL,
    channel_serial_no VARCHAR(64)   NOT NULL,
    merchant_no       VARCHAR(32)   NOT NULL,
    amount            DECIMAL(15,2) NOT NULL,
    fee_rate          DECIMAL(6,4)  NOT NULL,
    fee               DECIMAL(15,2) NOT NULL,
    occur_time        TIMESTAMP     NOT NULL,
    batch_date        CHAR(10)      NOT NULL,
    status            VARCHAR(12)   NOT NULL,
    source            VARCHAR(16)   NOT NULL DEFAULT 'CHANNEL',
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_txn_request UNIQUE (request_id),
    CONSTRAINT uk_txn_channel_serial UNIQUE (channel_serial_no)
);
COMMENT ON TABLE  payment_transaction IS '支付交易表，含已入账 POSTED 与封账后挂账 SUSPENDED';
COMMENT ON COLUMN payment_transaction.request_id IS '入账请求幂等号';
COMMENT ON COLUMN payment_transaction.fingerprint IS '幂等指纹：requestId+流水号+金额+商户号 的 SHA-256';
COMMENT ON COLUMN payment_transaction.channel_serial_no IS '渠道流水号，全局唯一，同一流水只允许入账一次';
COMMENT ON COLUMN payment_transaction.merchant_no IS '商户号';
COMMENT ON COLUMN payment_transaction.amount IS '交易金额（元）';
COMMENT ON COLUMN payment_transaction.fee_rate IS '入账时快照的手续费费率';
COMMENT ON COLUMN payment_transaction.fee IS '手续费金额（元），round(amount*fee_rate,2)';
COMMENT ON COLUMN payment_transaction.occur_time IS '交易发生时刻（UTC）';
COMMENT ON COLUMN payment_transaction.batch_date IS '当前归属批次日（UTC）；挂账调账后更新为目标批次日';
COMMENT ON COLUMN payment_transaction.status IS 'POSTED 已入账；SUSPENDED 封账后挂账，不计入任何清算';
COMMENT ON COLUMN payment_transaction.source IS '来源：CHANNEL 渠道入账；SUPPLEMENT 对账补记';
COMMENT ON COLUMN payment_transaction.created_at IS '记录创建时间（UTC）';

CREATE TABLE IF NOT EXISTS adjustment_order (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    transaction_id   BIGINT       NOT NULL,
    channel_serial_no VARCHAR(64) NOT NULL,
    from_batch_date  CHAR(10)     NOT NULL,
    to_batch_date    CHAR(10)     NOT NULL,
    amount           DECIMAL(15,2) NOT NULL,
    operator         VARCHAR(64),
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_adjust_txn UNIQUE (transaction_id)
);
COMMENT ON TABLE  adjustment_order IS '挂账调账指令留痕；一笔挂账交易只允许调账一次';
COMMENT ON COLUMN adjustment_order.transaction_id IS '被调账的交易ID';
COMMENT ON COLUMN adjustment_order.channel_serial_no IS '渠道流水号';
COMMENT ON COLUMN adjustment_order.from_batch_date IS '调账前归属批次日（已封账）';
COMMENT ON COLUMN adjustment_order.to_batch_date IS '调账目标批次日（调账时必须 OPEN）';
COMMENT ON COLUMN adjustment_order.amount IS '调账金额（元）';
COMMENT ON COLUMN adjustment_order.operator IS '调账操作人';
COMMENT ON COLUMN adjustment_order.created_at IS '调账时间（UTC）';

CREATE TABLE IF NOT EXISTS settlement_order (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_date     CHAR(10)      NOT NULL,
    merchant_no    VARCHAR(32)   NOT NULL,
    txn_count      INT           NOT NULL,
    total_amount   DECIMAL(15,2) NOT NULL,
    total_fee      DECIMAL(15,2) NOT NULL,
    settled_amount DECIMAL(15,2) NOT NULL,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_settlement_batch_merchant UNIQUE (batch_date, merchant_no)
);
COMMENT ON TABLE  settlement_order IS '商户清算单，封账终态后生成，重复封账返回原单不重算';
COMMENT ON COLUMN settlement_order.batch_date IS '清算批次日（UTC）';
COMMENT ON COLUMN settlement_order.merchant_no IS '商户号';
COMMENT ON COLUMN settlement_order.txn_count IS '批次内该商户已入账交易笔数';
COMMENT ON COLUMN settlement_order.total_amount IS '交易总额（元）';
COMMENT ON COLUMN settlement_order.total_fee IS '手续费总额（元）';
COMMENT ON COLUMN settlement_order.settled_amount IS '清算金额（元）= 交易总额 - 手续费总额';
COMMENT ON COLUMN settlement_order.created_at IS '清算单生成时间（UTC）';

CREATE TABLE IF NOT EXISTS recon_diff (
    id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_date        CHAR(10)      NOT NULL,
    channel_serial_no VARCHAR(64)   NOT NULL,
    merchant_no       VARCHAR(32),
    diff_type         VARCHAR(16)   NOT NULL,
    local_amount      DECIMAL(15,2),
    channel_amount    DECIMAL(15,2),
    status            VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    resolve_action    VARCHAR(16),
    resolve_remark    VARCHAR(255),
    resolved_at       TIMESTAMP,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_recon_diff UNIQUE (batch_date, channel_serial_no, diff_type)
);
COMMENT ON TABLE  recon_diff IS '渠道对账差异表';
COMMENT ON COLUMN recon_diff.batch_date IS '对账批次日（UTC）';
COMMENT ON COLUMN recon_diff.channel_serial_no IS '差异渠道流水号';
COMMENT ON COLUMN recon_diff.merchant_no IS '商户号（本地无时取渠道侧）';
COMMENT ON COLUMN recon_diff.diff_type IS '差异类型：LOCAL_EXTRA 本地多账；CHANNEL_EXTRA 渠道多账；AMOUNT_MISMATCH 金额差错';
COMMENT ON COLUMN recon_diff.local_amount IS '本地金额（元），本地无账为空';
COMMENT ON COLUMN recon_diff.channel_amount IS '渠道金额（元），渠道无账为空';
COMMENT ON COLUMN recon_diff.status IS 'OPEN 待处理；RESOLVED 已处理，不可重复核销';
COMMENT ON COLUMN recon_diff.resolve_action IS '处理方式：WRITE_OFF 核销 / SUPPLEMENT 补记 / REFUND 退款';
COMMENT ON COLUMN recon_diff.resolve_remark IS '处理备注';
COMMENT ON COLUMN recon_diff.resolved_at IS '处理时间（UTC），未处理为空';
COMMENT ON COLUMN recon_diff.created_at IS '差异生成时间（UTC）';

CREATE TABLE IF NOT EXISTS recon_run (
    batch_date  CHAR(10)  NOT NULL PRIMARY KEY,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE  recon_run IS '对账执行记录；每批次只允许对账一次，即使无差异也留痕';
COMMENT ON COLUMN recon_run.batch_date IS '对账批次日（UTC）';
COMMENT ON COLUMN recon_run.created_at IS '对账执行时间（UTC）';

CREATE TABLE IF NOT EXISTS recon_diff_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    diff_id     BIGINT       NOT NULL,
    action      VARCHAR(16)  NOT NULL,
    operator    VARCHAR(64),
    remark      VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE  recon_diff_log IS '差异处理留痕表，每次核销/补记/退款追加一条';
COMMENT ON COLUMN recon_diff_log.diff_id IS '差异记录ID';
COMMENT ON COLUMN recon_diff_log.action IS '处理方式：WRITE_OFF / SUPPLEMENT / REFUND';
COMMENT ON COLUMN recon_diff_log.operator IS '处理操作人';
COMMENT ON COLUMN recon_diff_log.remark IS '处理备注';
COMMENT ON COLUMN recon_diff_log.created_at IS '处理时间（UTC）';
