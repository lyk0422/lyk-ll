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
COMMENT ON COLUMN idempotency_record.scope IS '操作类型：ENTRY/EXIT/ESTIMATE/SEAL/REFUND_APPLY/REFUND_CONFIRM/CARD_CLOSE/ADJ_BATCH_OPEN/ADJ_INCLUDE/ADJ_BATCH_SEAL';
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

-- 封账后冲正（退款）域：原流水、原清分明细与封账合计永不改写，
-- 退款以独立负向调整明细登记，仅占用行程可退余额。
CREATE TABLE IF NOT EXISTS metro_card (
    card_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (card_id)
);
COMMENT ON TABLE metro_card IS '票卡主数据：ACTIVE 正常 / CLOSED 已关闭；关卡不影响已封账行程退款及其历史重放';
COMMENT ON COLUMN metro_card.card_id IS '票卡编号';
COMMENT ON COLUMN metro_card.status IS '票卡状态：ACTIVE 正常 / CLOSED 已关闭';
COMMENT ON COLUMN metro_card.version IS '乐观锁版本，从 1 开始，每次成功变更加 1';

CREATE TABLE IF NOT EXISTS refund_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_no VARCHAR(64) NOT NULL,
    txn_no VARCHAR(64) NOT NULL,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    first_reviewer VARCHAR(64) NULL,
    first_review_time DATETIME NULL,
    final_reviewer VARCHAR(64) NULL,
    confirmed_time DATETIME NULL,
    apply_request_id VARCHAR(64) NOT NULL,
    apply_time DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_refund_no UNIQUE (refund_no)
);
COMMENT ON TABLE refund_order IS '退款单：仅引用已封账且已按实结算的行程；金额单位分，单次为正';
COMMENT ON COLUMN refund_order.refund_no IS '退款单号，全局唯一';
COMMENT ON COLUMN refund_order.txn_no IS '被退款的原交易流水号（COMPLETED 且原批次已封账）';
COMMENT ON COLUMN refund_order.amount_cents IS '本次退款金额（分），正整数；同行程累计退款不得超过原实收';
COMMENT ON COLUMN refund_order.status IS '状态：PENDING 待复核（0 人）/ IN_REVIEW 已一人复核 / CONFIRMED 双人确认完成';
COMMENT ON COLUMN refund_order.version IS '乐观锁版本，从 1 开始，每次成功变更加 1：首人复核后 2，最终确认后 3';
COMMENT ON COLUMN refund_order.first_reviewer IS '第一名复核人工号，NULL 表示尚无人复核';
COMMENT ON COLUMN refund_order.final_reviewer IS '最终（第二名，与第一人不同）复核人工号，NULL 表示未最终确认';
COMMENT ON COLUMN refund_order.confirmed_time IS '最终确认时刻（Asia/Shanghai），NULL 表示未确认';

CREATE TABLE IF NOT EXISTS refund_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_no VARCHAR(64) NOT NULL,
    reviewer VARCHAR(64) NOT NULL,
    review_time DATETIME NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_refund_reviewer UNIQUE (refund_no, reviewer)
);
COMMENT ON TABLE refund_review IS '退款复核登记：同一退款单同一复核人只能登记一次，最终确认须两名不同复核人';
COMMENT ON COLUMN refund_review.refund_no IS '退款单号';
COMMENT ON COLUMN refund_review.reviewer IS '复核人工号；两名复核人必须不同';
COMMENT ON COLUMN refund_review.review_time IS '复核确认精确时刻（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS refund_line_share (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    txn_no VARCHAR(64) NOT NULL,
    line_code VARCHAR(16) NOT NULL,
    original_share_cents BIGINT NOT NULL,
    refunded_cents BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_txn_line_share UNIQUE (txn_no, line_code)
);
COMMENT ON TABLE refund_line_share IS '行程逐线路可退余额：原实收份额快照与累计已退占用，refunded 永不超过 original';
COMMENT ON COLUMN refund_line_share.original_share_cents IS '该线路在原清分明细中的实收份额（分，正数），取自封账批次 NORMAL/SETTLE 明细';
COMMENT ON COLUMN refund_line_share.refunded_cents IS '该线路累计已退款占用（分），并发退款下不得超过 original_share_cents';

CREATE TABLE IF NOT EXISTS refund_adjustment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    adjustment_no VARCHAR(96) NOT NULL,
    refund_no VARCHAR(64) NOT NULL,
    txn_no VARCHAR(64) NOT NULL,
    amount_cents BIGINT NOT NULL,
    batch_id BIGINT NULL,
    confirm_request_id VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_adjustment_no UNIQUE (adjustment_no),
    CONSTRAINT uk_adjustment_refund UNIQUE (refund_no)
);
COMMENT ON TABLE refund_adjustment IS '退款调整单：双人确认成功后生成，独立于原清分；一张退款单至多一张调整单';
COMMENT ON COLUMN refund_adjustment.amount_cents IS '调整单金额（分，正数），其明细均为负向';
COMMENT ON COLUMN refund_adjustment.batch_id IS '纳入的调账批次 ID，NULL 表示尚未纳入；一张调整单只能纳入一个批次';

CREATE TABLE IF NOT EXISTS refund_adjustment_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    adjustment_id BIGINT NOT NULL,
    line_code VARCHAR(16) NOT NULL,
    amount_cents BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_adjustment_line UNIQUE (adjustment_id, line_code)
);
COMMENT ON TABLE refund_adjustment_detail IS '退款负向调整明细：按原线路实收份额分摊，金额为负，合计等于退款金额的相反数';
COMMENT ON COLUMN refund_adjustment_detail.amount_cents IS '该线路负向调整金额（分，负数）';

CREATE TABLE IF NOT EXISTS adjustment_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    total_cents BIGINT NOT NULL DEFAULT 0,
    adjustment_count INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    open_request_id VARCHAR(64) NOT NULL,
    seal_request_id VARCHAR(64) NULL,
    created_at DATETIME NOT NULL,
    sealed_at DATETIME NULL,
    CONSTRAINT uk_adjustment_batch_no UNIQUE (batch_no)
);
COMMENT ON TABLE adjustment_batch IS '调账批次：纳入负向调整单，封存后只读；与原日清分批次相互独立';
COMMENT ON COLUMN adjustment_batch.status IS '状态：OPEN 开放可纳入 / SEALED 已封存（终态，只读）';
COMMENT ON COLUMN adjustment_batch.total_cents IS '批次内负向调整明细合计（分，负数或 0），封存时快照';
COMMENT ON COLUMN adjustment_batch.adjustment_count IS '已纳入调整单数量；重复纳入不重复计额';
COMMENT ON COLUMN adjustment_batch.version IS '乐观锁版本，从 1 开始，每次成功变更加 1';

CREATE TABLE IF NOT EXISTS adjustment_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    adjustment_id BIGINT NOT NULL,
    txn_no VARCHAR(64) NOT NULL,
    line_code VARCHAR(16) NOT NULL,
    amount_cents BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_batch_adjustment_line UNIQUE (batch_id, adjustment_id, line_code)
);
COMMENT ON TABLE adjustment_detail IS '调账批次明细：调整单纳入时复制其负向明细，封存后只读';
COMMENT ON COLUMN adjustment_detail.amount_cents IS '负向调整金额（分，负数）';

