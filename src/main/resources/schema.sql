-- 轨交票务清分服务建表脚本（H2 MySQL 兼容模式）
-- 金额单位统一为“分”，里程单位为“米”，时间均为 Asia/Shanghai 本地时间。

CREATE TABLE IF NOT EXISTS metro_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_code VARCHAR(16) NOT NULL,
    from_station VARCHAR(32) NOT NULL,
    to_station VARCHAR(32) NOT NULL,
    distance_m INT NOT NULL
);
COMMENT ON TABLE metro_segment IS '合成里程表：线路相邻站区间里程';
COMMENT ON COLUMN metro_segment.line_code IS '线路编码，如 L1';
COMMENT ON COLUMN metro_segment.from_station IS '区间起点站编码';
COMMENT ON COLUMN metro_segment.to_station IS '区间终点站编码';
COMMENT ON COLUMN metro_segment.distance_m IS '区间里程，单位米';

CREATE TABLE IF NOT EXISTS metro_transaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    txn_no VARCHAR(64) NOT NULL,
    card_id VARCHAR(64) NOT NULL,
    entry_station VARCHAR(32) NOT NULL,
    entry_time DATETIME NOT NULL,
    exit_station VARCHAR(32) NULL,
    exit_time DATETIME NULL,
    status VARCHAR(16) NOT NULL,
    distance_m BIGINT NULL,
    chain_base_m BIGINT NOT NULL DEFAULT 0,
    fare_cents BIGINT NULL,
    penalty_cents BIGINT NOT NULL DEFAULT 0,
    transfer_flag TINYINT NOT NULL DEFAULT 0,
    estimated_flag TINYINT NOT NULL DEFAULT 0,
    batch_id BIGINT NULL,
    biz_date DATE NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_txn_no UNIQUE (txn_no)
);
COMMENT ON TABLE metro_transaction IS '交易流水：进闸、出闸、暂记与结算状态';
COMMENT ON COLUMN metro_transaction.txn_no IS '交易流水号，全局唯一，重复拒绝';
COMMENT ON COLUMN metro_transaction.card_id IS '票卡编号';
COMMENT ON COLUMN metro_transaction.entry_station IS '进闸站编码';
COMMENT ON COLUMN metro_transaction.entry_time IS '进闸时间（Asia/Shanghai）';
COMMENT ON COLUMN metro_transaction.exit_station IS '出闸站编码，NULL 表示尚未出闸';
COMMENT ON COLUMN metro_transaction.exit_time IS '出闸时间（Asia/Shanghai）';
COMMENT ON COLUMN metro_transaction.status IS '状态：ENTERED 已进闸未出闸 / ESTIMATED 出闸缺失暂记 / COMPLETED 已出闸结算';
COMMENT ON COLUMN metro_transaction.distance_m IS '计费里程（米），按进出站最短路径';
COMMENT ON COLUMN metro_transaction.chain_base_m IS '换乘链在本程之前已累计里程（米），用于只计一次起步价';
COMMENT ON COLUMN metro_transaction.fare_cents IS '实收金额（分）；暂记状态下为暂记金额';
COMMENT ON COLUMN metro_transaction.penalty_cents IS '超时乘车补收金额（分）';
COMMENT ON COLUMN metro_transaction.transfer_flag IS '是否换乘接续：1 是 0 否';
COMMENT ON COLUMN metro_transaction.estimated_flag IS '是否暂记：1 是 0 否';
COMMENT ON COLUMN metro_transaction.batch_id IS '入账清分批次 ID，NULL 表示未入账';
COMMENT ON COLUMN metro_transaction.biz_date IS '入账业务日期';
COMMENT ON COLUMN metro_transaction.request_id IS '进闸请求幂等键';
COMMENT ON COLUMN metro_transaction.fingerprint IS '进闸请求指纹（requestId+全部业务字段）';

CREATE TABLE IF NOT EXISTS idempotency_record (
    scope VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    response_body CLOB NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (scope, request_id)
);
COMMENT ON TABLE idempotency_record IS '幂等记录：同键同参返回首次结果，同键异参返回 409';
COMMENT ON COLUMN idempotency_record.scope IS '操作类型：ENTRY/EXIT/ESTIMATE/SEAL/REFUND_APPLY/REFUND_CONFIRM/ADJ_INCLUDE/ADJ_SEAL/CARD_CLOSE';
COMMENT ON COLUMN idempotency_record.request_id IS '请求幂等键';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求指纹，覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN idempotency_record.response_body IS '首次成功响应快照（JSON）';

CREATE TABLE IF NOT EXISTS clearing_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    biz_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    total_cents BIGINT NOT NULL DEFAULT 0,
    txn_count INT NOT NULL DEFAULT 0,
    seal_request_id VARCHAR(64) NULL,
    created_at DATETIME NOT NULL,
    sealed_at DATETIME NULL,
    CONSTRAINT uk_biz_date UNIQUE (biz_date)
);
COMMENT ON TABLE clearing_batch IS '清分批次：按业务日期日结，封账为终态';
COMMENT ON COLUMN clearing_batch.biz_date IS '业务日期';
COMMENT ON COLUMN clearing_batch.status IS '状态：OPEN 未封账 / SEALED 已封账（终态）';
COMMENT ON COLUMN clearing_batch.total_cents IS '批次合计（分），封账时快照，封账后不再变化';
COMMENT ON COLUMN clearing_batch.txn_count IS '批次内交易笔数';
COMMENT ON COLUMN clearing_batch.seal_request_id IS '封账请求幂等键';

CREATE TABLE IF NOT EXISTS clearing_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    txn_no VARCHAR(64) NOT NULL,
    line_code VARCHAR(16) NOT NULL,
    distance_m BIGINT NOT NULL,
    ratio DECIMAL(10,4) NOT NULL,
    amount_cents BIGINT NOT NULL,
    entry_type VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL
);
COMMENT ON TABLE clearing_detail IS '清分明细：按线路分摊，可逐笔复算';
COMMENT ON COLUMN clearing_detail.batch_id IS '所属清分批次 ID';
COMMENT ON COLUMN clearing_detail.txn_no IS '交易流水号';
COMMENT ON COLUMN clearing_detail.line_code IS '承运线路编码';
COMMENT ON COLUMN clearing_detail.distance_m IS '该线路承运里程（米）';
COMMENT ON COLUMN clearing_detail.ratio IS '分摊比例，保留四位小数';
COMMENT ON COLUMN clearing_detail.amount_cents IS '分摊金额（分），冲销记录为负数';
COMMENT ON COLUMN clearing_detail.entry_type IS '类型：NORMAL 正常结算 / ESTIMATE 暂记 / REVERSAL 暂记冲销 / SETTLE 补登实结';

CREATE TABLE IF NOT EXISTS clearing_discrepancy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    biz_date DATE NOT NULL,
    txn_no VARCHAR(64) NOT NULL,
    amount_cents BIGINT NOT NULL,
    reason VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL
);
COMMENT ON TABLE clearing_discrepancy IS '对账差异登记：封账后迟到交易，不改已封账合计';
COMMENT ON COLUMN clearing_discrepancy.biz_date IS '交易原应入账的业务日期';
COMMENT ON COLUMN clearing_discrepancy.txn_no IS '交易流水号';
COMMENT ON COLUMN clearing_discrepancy.amount_cents IS '差异金额（分）';
COMMENT ON COLUMN clearing_discrepancy.reason IS '差异原因';

-- 已封账行程退款冲正：退款单、调账单、负向调整明细、调账批次与票卡关闭登记
-- 全部金额单位为“分”；调账明细金额恒为负，原流水与原封账合计永不改写。

CREATE TABLE IF NOT EXISTS refund_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_no VARCHAR(64) NOT NULL,
    txn_no VARCHAR(64) NOT NULL,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    confirmer_one VARCHAR(64) NULL,
    confirmer_one_time DATETIME NULL,
    confirmer_two VARCHAR(64) NULL,
    confirmer_two_time DATETIME NULL,
    adjustment_no VARCHAR(64) NULL,
    version INT NOT NULL DEFAULT 1,
    apply_request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    confirmed_at DATETIME NULL,
    CONSTRAINT uk_refund_no UNIQUE (refund_no)
);
CREATE INDEX IF NOT EXISTS idx_refund_txn ON refund_order (txn_no);
COMMENT ON TABLE refund_order IS '退款单：仅引用已封账且按实结算行程，两名不同复核人确认后生效';
COMMENT ON COLUMN refund_order.refund_no IS '退款单号，全局唯一';
COMMENT ON COLUMN refund_order.txn_no IS '原交易流水号';
COMMENT ON COLUMN refund_order.amount_cents IS '本次退款金额（分），恒为正，累计退款不得超过原实收';
COMMENT ON COLUMN refund_order.status IS '状态：PENDING 待双人确认 / CONFIRMED 已确认生效';
COMMENT ON COLUMN refund_order.confirmer_one IS '第一复核人工号，单人确认不生效';
COMMENT ON COLUMN refund_order.confirmer_one_time IS '第一复核人确认时刻（Asia/Shanghai）';
COMMENT ON COLUMN refund_order.confirmer_two IS '第二复核人工号，必须与第一复核人不同';
COMMENT ON COLUMN refund_order.confirmer_two_time IS '第二复核人确认时刻（Asia/Shanghai）';
COMMENT ON COLUMN refund_order.adjustment_no IS '确认生效后登记的调账单号，NULL 表示尚未生效';
COMMENT ON COLUMN refund_order.version IS '乐观版本：从 1 开始，每次成功变更加 1';
COMMENT ON COLUMN refund_order.apply_request_id IS '退款申请幂等键';
COMMENT ON COLUMN refund_order.fingerprint IS '申请请求结构化指纹（含全部业务字段）';
COMMENT ON COLUMN refund_order.confirmed_at IS '退款最终确认时刻（Asia/Shanghai），NULL 表示未生效';

CREATE TABLE IF NOT EXISTS adjustment_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    adjustment_no VARCHAR(64) NOT NULL,
    refund_no VARCHAR(64) NOT NULL,
    txn_no VARCHAR(64) NOT NULL,
    amount_cents BIGINT NOT NULL,
    batch_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    included_at DATETIME NULL,
    CONSTRAINT uk_adjustment_no UNIQUE (adjustment_no),
    CONSTRAINT uk_adjustment_refund UNIQUE (refund_no)
);
CREATE INDEX IF NOT EXISTS idx_adjustment_batch ON adjustment_order (batch_id);
COMMENT ON TABLE adjustment_order IS '调账单：退款确认时登记，只能纳入一个调账批次';
COMMENT ON COLUMN adjustment_order.adjustment_no IS '调账单号，全局唯一';
COMMENT ON COLUMN adjustment_order.refund_no IS '来源退款单号，一退款单一调账单';
COMMENT ON COLUMN adjustment_order.txn_no IS '原交易流水号';
COMMENT ON COLUMN adjustment_order.amount_cents IS '退款本金（分，正数）；负向金额见 adjustment_detail';
COMMENT ON COLUMN adjustment_order.batch_id IS '已纳入的调账批次 ID，NULL 表示尚未纳入；封存后不可改投';
COMMENT ON COLUMN adjustment_order.status IS '状态：REGISTERED 已登记 / INCLUDED 已纳入批次';
COMMENT ON COLUMN adjustment_order.version IS '乐观版本：从 1 开始，纳入批次成功后加 1';

CREATE TABLE IF NOT EXISTS adjustment_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    adjustment_no VARCHAR(64) NOT NULL,
    txn_no VARCHAR(64) NOT NULL,
    original_detail_id BIGINT NOT NULL,
    line_code VARCHAR(16) NOT NULL,
    amount_cents BIGINT NOT NULL,
    created_at DATETIME NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_adjustment_detail_txn ON adjustment_detail (txn_no, line_code);
COMMENT ON TABLE adjustment_detail IS '负向调整明细：按原线路实收份额分摊，独立于原清分明细，金额恒为负';
COMMENT ON COLUMN adjustment_detail.adjustment_no IS '所属调账单号';
COMMENT ON COLUMN adjustment_detail.txn_no IS '原交易流水号';
COMMENT ON COLUMN adjustment_detail.original_detail_id IS '对应原清分明细 ID（NORMAL/SETTLE 行），保留证据链';
COMMENT ON COLUMN adjustment_detail.line_code IS '原承运线路编码';
COMMENT ON COLUMN adjustment_detail.amount_cents IS '该线路冲正金额（分），恒为负，各线路累计不超过原份额';

CREATE TABLE IF NOT EXISTS adjustment_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL,
    open_slot VARCHAR(16) NULL,
    status VARCHAR(16) NOT NULL,
    total_cents BIGINT NOT NULL DEFAULT 0,
    adjustment_count INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    seal_request_id VARCHAR(64) NULL,
    created_at DATETIME NOT NULL,
    sealed_at DATETIME NULL,
    CONSTRAINT uk_adjustment_batch_no UNIQUE (batch_no),
    CONSTRAINT uk_adjustment_open_slot UNIQUE (open_slot)
);
COMMENT ON TABLE adjustment_batch IS '调账批次：任一时刻至多一个 OPEN；封存后只读，合计快照不再变化';
COMMENT ON COLUMN adjustment_batch.batch_no IS '调账批次号，全局唯一';
COMMENT ON COLUMN adjustment_batch.open_slot IS '开放占位：OPEN 批次恒为 ''OPEN''，封存后置 NULL，唯一约束保证至多一个开放批次';
COMMENT ON COLUMN adjustment_batch.status IS '状态：OPEN 开放可纳入 / SEALED 已封存只读';
COMMENT ON COLUMN adjustment_batch.total_cents IS '封存时快照的调账合计（分，负数），封存后不再变化';
COMMENT ON COLUMN adjustment_batch.adjustment_count IS '已纳入调账单笔数';
COMMENT ON COLUMN adjustment_batch.version IS '乐观版本：从 1 开始，封存成功后加 1';
COMMENT ON COLUMN adjustment_batch.seal_request_id IS '封存请求幂等键';

CREATE TABLE IF NOT EXISTS metro_card_close (
    card_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    closed_at DATETIME NOT NULL,
    PRIMARY KEY (card_id)
);
COMMENT ON TABLE metro_card_close IS '票卡关闭登记：关闭不影响已成功退款结果的历史重放';
COMMENT ON COLUMN metro_card_close.card_id IS '票卡编号';
COMMENT ON COLUMN metro_card_close.request_id IS '关闭请求幂等键';
COMMENT ON COLUMN metro_card_close.closed_at IS '票卡关闭时刻（Asia/Shanghai）';
