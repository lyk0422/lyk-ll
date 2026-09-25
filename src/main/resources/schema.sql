-- 支付清算服务建表脚本（H2 MODE=MySQL，单位：金额均为分，时间除 batch_date 外均为 UTC 时间戳）

CREATE TABLE IF NOT EXISTS merchant (
    merchant_no  VARCHAR(64)  NOT NULL PRIMARY KEY,
    merchant_name VARCHAR(128) NOT NULL,
    fee_rate     DECIMAL(8,6) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMP    NOT NULL
);
COMMENT ON COLUMN merchant.merchant_no IS '商户号（合成数据主键）';
COMMENT ON COLUMN merchant.fee_rate IS '手续费率，如 0.006000 表示千分之六';
COMMENT ON COLUMN merchant.status IS '商户状态：ACTIVE 可用 / DISABLED 停用';

CREATE TABLE IF NOT EXISTS settlement_batch (
    batch_date     DATE        NOT NULL PRIMARY KEY,
    status         VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    closed_at      TIMESTAMP,
    merchant_count INT         NOT NULL DEFAULT 0,
    total_amount   BIGINT      NOT NULL DEFAULT 0,
    total_fee      BIGINT      NOT NULL DEFAULT 0,
    settled_amount BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMP   NOT NULL
);
COMMENT ON COLUMN settlement_batch.batch_date IS '批次归属日（UTC 日切，日期本身无时区）';
COMMENT ON COLUMN settlement_batch.status IS '批次状态：OPEN 开放入账 / CLOSED 已封账（终态）';
COMMENT ON COLUMN settlement_batch.closed_at IS '封账完成时刻(UTC)，未封账为 NULL';
COMMENT ON COLUMN settlement_batch.total_amount IS '封账时批次交易总额（分），未封账为 0';

CREATE TABLE IF NOT EXISTS payment_txn (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(64) NOT NULL,
    fingerprint      VARCHAR(128) NOT NULL,
    channel_flow_no  VARCHAR(64) NOT NULL,
    merchant_no      VARCHAR(64) NOT NULL,
    amount           BIGINT      NOT NULL,
    fee              BIGINT      NOT NULL,
    batch_date       DATE        NOT NULL,
    origin_batch_date DATE,
    status           VARCHAR(16) NOT NULL,
    occurred_at      TIMESTAMP   NOT NULL,
    created_at       TIMESTAMP   NOT NULL,
    moved_at         TIMESTAMP,
    CONSTRAINT uq_txn_request UNIQUE (request_id),
    CONSTRAINT uq_txn_batch_flow UNIQUE (batch_date, channel_flow_no)
);
COMMENT ON COLUMN payment_txn.request_id IS '入账幂等键，同 requestId 指纹一致返回首次结果';
COMMENT ON COLUMN payment_txn.fingerprint IS '幂等指纹：requestId+流水号+金额+商户号的 SHA-256';
COMMENT ON COLUMN payment_txn.channel_flow_no IS '渠道流水号，批次内唯一，重复流水拒绝入账';
COMMENT ON COLUMN payment_txn.amount IS '交易金额（分），必须为正数';
COMMENT ON COLUMN payment_txn.fee IS '入账时按商户费率计算的手续费（分）';
COMMENT ON COLUMN payment_txn.batch_date IS '当前归属批次；挂账时为原批次，调账后为目标批次';
COMMENT ON COLUMN payment_txn.origin_batch_date IS '挂账调账前的原批次；非调账交易为 NULL';
COMMENT ON COLUMN payment_txn.status IS 'POSTED 已入账 / SUSPENDED 封账后挂账 / MOVED 已调账入新批';
COMMENT ON COLUMN payment_txn.occurred_at IS '交易发生时刻(UTC)，用于 16:00 日切归属判定';

CREATE TABLE IF NOT EXISTS settlement_order (
    id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_date     DATE        NOT NULL,
    merchant_no    VARCHAR(64) NOT NULL,
    txn_count      INT         NOT NULL,
    total_amount   BIGINT      NOT NULL,
    total_fee      BIGINT      NOT NULL,
    settled_amount BIGINT      NOT NULL,
    created_at     TIMESTAMP   NOT NULL,
    CONSTRAINT uq_order_batch_merchant UNIQUE (batch_date, merchant_no)
);
COMMENT ON COLUMN settlement_order.settled_amount IS '商户清算金额=交易额合计-手续费合计（分）';

CREATE TABLE IF NOT EXISTS adjustment (
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    adjust_no       VARCHAR(64) NOT NULL,
    txn_id          BIGINT      NOT NULL,
    from_batch_date DATE        NOT NULL,
    to_batch_date   DATE        NOT NULL,
    amount          BIGINT      NOT NULL,
    status          VARCHAR(16) NOT NULL,
    created_at      TIMESTAMP   NOT NULL,
    CONSTRAINT uq_adjust_no UNIQUE (adjust_no)
);
COMMENT ON COLUMN adjustment.adjust_no IS '调账指令号（幂等键）';
COMMENT ON COLUMN adjustment.status IS 'DONE 调账完成（与原交易同事务提交）';

CREATE TABLE IF NOT EXISTS recon_file (
    file_no      VARCHAR(64) NOT NULL PRIMARY KEY,
    batch_date   DATE        NOT NULL,
    channel_code VARCHAR(32) NOT NULL,
    created_at   TIMESTAMP   NOT NULL
);
COMMENT ON COLUMN recon_file.file_no IS '渠道对账文件编号（合成数据，唯一）';

CREATE TABLE IF NOT EXISTS recon_diff (
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_date      DATE        NOT NULL,
    channel_flow_no VARCHAR(64) NOT NULL,
    merchant_no     VARCHAR(64),
    diff_type       VARCHAR(32) NOT NULL,
    local_amount    BIGINT,
    channel_amount  BIGINT,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    resolution      VARCHAR(32),
    resolved_at     TIMESTAMP,
    resolved_by     VARCHAR(64),
    remark          VARCHAR(255),
    created_at      TIMESTAMP   NOT NULL,
    CONSTRAINT uq_diff_unique UNIQUE (batch_date, channel_flow_no, diff_type)
);
COMMENT ON COLUMN recon_diff.diff_type IS '差异类型：LOCAL_EXTRA 本地多账 / CHANNEL_EXTRA 渠道多账 / AMOUNT_MISMATCH 金额差错';
COMMENT ON COLUMN recon_diff.local_amount IS '本地金额（分）；渠道多账时为 NULL';
COMMENT ON COLUMN recon_diff.channel_amount IS '渠道金额（分）；本地多账时为 NULL';
COMMENT ON COLUMN recon_diff.status IS 'PENDING 待处理 / RESOLVED 已核销（终态，禁止重复核销）';
COMMENT ON COLUMN recon_diff.resolution IS '处理结果：WRITE_OFF 核销 / SUPPLEMENT 补记 / REFUND 退款';
