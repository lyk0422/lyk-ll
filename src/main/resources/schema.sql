-- 出口退税申报服务建表脚本（H2 MySQL 兼容模式，合成数据，仅 JVM 生命周期内有效）

CREATE TABLE t_rate_library (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_code VARCHAR(64) NOT NULL,
    version_no VARCHAR(32) NOT NULL,
    effective_from DATE NOT NULL,
    rebate_rate DECIMAL(18, 6) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_rate_library_product_version UNIQUE (product_code, version_no)
);
COMMENT ON TABLE t_rate_library IS '退税率文库版本（合成数据），按商品编码与 UTC 生效日管理版本';
COMMENT ON COLUMN t_rate_library.product_code IS '商品编码，报关单与进项发票据此关联';
COMMENT ON COLUMN t_rate_library.version_no IS '文库版本号';
COMMENT ON COLUMN t_rate_library.effective_from IS 'UTC 生效日（含当日），申报日取 effective_from <= 申报日 的最新版本';
COMMENT ON COLUMN t_rate_library.rebate_rate IS '退税率（合成数值，计税依据=FOB 价×该值）';

CREATE TABLE t_customs_declaration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    declaration_no VARCHAR(64) NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    fob_amount DECIMAL(18, 4) NOT NULL,
    fob_usd DECIMAL(18, 4) NOT NULL,
    collected_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_customs_declaration_no UNIQUE (declaration_no)
);
COMMENT ON TABLE t_customs_declaration IS '报关单采集表（合成数据）';
COMMENT ON COLUMN t_customs_declaration.fob_amount IS 'FOB 价（合成金额），计税依据输入';
COMMENT ON COLUMN t_customs_declaration.fob_usd IS 'FOB 美元价，换汇成本分母';

CREATE TABLE t_input_invoice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_no VARCHAR(64) NOT NULL,
    declaration_no VARCHAR(64) NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    amount DECIMAL(18, 4) NOT NULL,
    collected_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_invoice_declaration_invoice UNIQUE (declaration_no, invoice_no)
);
COMMENT ON TABLE t_input_invoice IS '进项发票采集表（合成数据），按 报关单号+发票号 去重';
COMMENT ON COLUMN t_input_invoice.declaration_no IS '采集时关联的报关单号';

CREATE TABLE t_declaration_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    declare_date DATE NOT NULL,
    total_declared_tax DECIMAL(18, 4) NOT NULL DEFAULT 0,
    frozen_at TIMESTAMP NULL,
    voided_at TIMESTAMP NULL,
    closed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_batch_no UNIQUE (batch_no)
);
COMMENT ON TABLE t_declaration_batch IS '退税申报批次（状态机：CHECKED/DOUBTFUL/DECLARED/APPROVED/TREASURED/DIFF_MANUAL/CLOSED/VOID）';
COMMENT ON COLUMN t_declaration_batch.status IS '批次状态；CLOSED 终态，VOID 为撤批整体作废';
COMMENT ON COLUMN t_declaration_batch.declare_date IS '申报日（UTC 日界），退税率文库版本取值依据';
COMMENT ON COLUMN t_declaration_batch.total_declared_tax IS '申报税额合计=各明细计税依据合计';
COMMENT ON COLUMN t_declaration_batch.frozen_at IS '正式申报冻结时刻(UTC)，NULL 未冻结';
COMMENT ON COLUMN t_declaration_batch.voided_at IS '撤批作废时刻(UTC)，NULL 未作废';
COMMENT ON COLUMN t_declaration_batch.closed_at IS '结案时刻(UTC)，NULL 未结案';

CREATE TABLE t_batch_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    doc_type VARCHAR(16) NOT NULL,
    declaration_no VARCHAR(64) NOT NULL,
    invoice_no VARCHAR(64),
    product_code VARCHAR(64) NOT NULL,
    link_status VARCHAR(16) NOT NULL,
    linked_at TIMESTAMP NOT NULL,
    released_at TIMESTAMP NULL
);
COMMENT ON TABLE t_batch_document IS '批次-单证关联明细；撤批时全部置 RELEASED';
COMMENT ON COLUMN t_batch_document.doc_type IS '单证类型：CUSTOMS 报关单 / INVOICE 发票';
COMMENT ON COLUMN t_batch_document.link_status IS '关联状态：LINKED 占用中 / RELEASED 已释放';

CREATE INDEX idx_batch_document_batch ON t_batch_document (batch_id);

-- 有效批次占用登记：报关单号 / 发票号在非作废批次中唯一；撤批时删除登记行释放
CREATE TABLE t_declaration_registry (
    declaration_no VARCHAR(64) PRIMARY KEY,
    batch_id BIGINT NOT NULL
);
COMMENT ON TABLE t_declaration_registry IS '报关单有效批次占用登记，主键保证同一报关单号只参与一个有效批次';

CREATE TABLE t_invoice_registry (
    invoice_no VARCHAR(64) PRIMARY KEY,
    batch_id BIGINT NOT NULL
);
COMMENT ON TABLE t_invoice_registry IS '进项发票有效批次占用登记，主键保证同一发票只被一个有效批次使用';

CREATE TABLE t_check_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    declaration_no VARCHAR(64) NOT NULL,
    invoice_no VARCHAR(64),
    product_code VARCHAR(64) NOT NULL,
    library_version_id BIGINT,
    version_no VARCHAR(32),
    fob_amount DECIMAL(18, 4) NOT NULL,
    fob_usd DECIMAL(18, 4) NOT NULL,
    rebate_rate DECIMAL(18, 6),
    tax_basis DECIMAL(18, 4),
    exchange_cost DECIMAL(18, 6),
    result VARCHAR(16) NOT NULL,
    doubt_type VARCHAR(48)
);
COMMENT ON TABLE t_check_detail IS '关联校验明细，可按相同输入复算';
COMMENT ON COLUMN t_check_detail.tax_basis IS '计税依据=FOB 价×退税率';
COMMENT ON COLUMN t_check_detail.exchange_cost IS '换汇成本=计税依据÷FOB 美元价，合法区间[5.0,8.0]（含端点）';
COMMENT ON COLUMN t_check_detail.result IS 'PASS 通过 / DOUBT 转疑点';
COMMENT ON COLUMN t_check_detail.doubt_type IS '疑点类型：EXCHANGE_COST_OUT_OF_RANGE/MATCH_MISSING/RATE_NOT_FOUND';

CREATE INDEX idx_check_detail_batch ON t_check_detail (batch_id);

CREATE TABLE t_doubt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    check_detail_id BIGINT NOT NULL,
    doubt_type VARCHAR(48) NOT NULL,
    status VARCHAR(24) NOT NULL,
    explanation CLOB,
    explained_at TIMESTAMP NULL,
    reviewed_at TIMESTAMP NULL,
    CONSTRAINT uk_doubt_detail_type UNIQUE (check_detail_id, doubt_type)
);
COMMENT ON TABLE t_doubt IS '疑点记录：OPEN 待说明 / EXPLAINED 已说明待复核 / RESOLVED 复核确认解除';
COMMENT ON COLUMN t_doubt.explanation IS '企业补充说明，NULL 表示尚未提交';

CREATE INDEX idx_doubt_batch ON t_doubt (batch_id);

CREATE TABLE t_treasury_writeoff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    treasury_serial VARCHAR(64) NOT NULL,
    declared_tax DECIMAL(18, 4) NOT NULL,
    received_amount DECIMAL(18, 4) NOT NULL,
    diff_amount DECIMAL(18, 4) NOT NULL,
    diff_ratio DECIMAL(18, 8) NOT NULL,
    consistent CHAR(1) NOT NULL,
    manual_flag CHAR(1) NOT NULL DEFAULT '0',
    registered_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_writeoff_batch_serial UNIQUE (batch_id, treasury_serial)
);
COMMENT ON TABLE t_treasury_writeoff IS '退库核销登记，按 批次+国库流水号 唯一';
COMMENT ON COLUMN t_treasury_writeoff.diff_ratio IS '差额比例=|到账-申报税额|÷申报税额；恰 0.005 视为一致';
COMMENT ON COLUMN t_treasury_writeoff.consistent IS '1 一致 / 0 超差';
COMMENT ON COLUMN t_treasury_writeoff.manual_flag IS '1 已登记差异并转人工';

CREATE TABLE t_late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    declaration_no VARCHAR(64),
    invoice_no VARCHAR(64),
    note CLOB,
    recorded_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE t_late_event IS '结案后迟到单证事件，仅登记不关联';

CREATE TABLE t_idempotent_record (
    request_id VARCHAR(64) PRIMARY KEY,
    op_type VARCHAR(40) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    response_status INT,
    response_body CLOB,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL
);
COMMENT ON TABLE t_idempotent_record IS 'requestId 幂等记录：指纹覆盖 requestId 与全部业务字段';

-- 结案批次分批冲回：逐单可冲余额台账（单位：分），批准冲回时在同一事务内条件占用
CREATE TABLE t_reversal_quota (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    declaration_no VARCHAR(64) NOT NULL,
    frozen_tax_cents BIGINT NOT NULL,
    occupied_cents BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_reversal_quota_decl UNIQUE (batch_id, declaration_no)
);
COMMENT ON TABLE t_reversal_quota IS '逐单可冲余额台账：结案批次内每张报关单一行，冲回批准时条件占用，不改动结案总额';
COMMENT ON COLUMN t_reversal_quota.frozen_tax_cents IS '冻结退税额（整数分），取自结案时该校验明细的计税依据（元×100，四舍五入）';
COMMENT ON COLUMN t_reversal_quota.occupied_cents IS '已批准冲回累计占用（整数分）；待复核与驳回不占额，恒不超过冻结额';

CREATE TABLE t_reversal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    declaration_no VARCHAR(64) NOT NULL,
    correction_no VARCHAR(64) NOT NULL,
    reason CLOB NOT NULL,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    submitted_by VARCHAR(64) NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    reviewed_by VARCHAR(64),
    reviewed_at TIMESTAMP NULL,
    reject_reason CLOB,
    approved_cents BIGINT NOT NULL DEFAULT 0,
    collected_cents BIGINT NOT NULL DEFAULT 0,
    completed_at TIMESTAMP NULL,
    version_no INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_reversal_batch_correction UNIQUE (batch_id, correction_no)
);
COMMENT ON TABLE t_reversal IS '分批冲回单：同批次同更正编号最多一单；状态 PENDING/APPROVED/REJECTED/COMPLETED';
COMMENT ON COLUMN t_reversal.correction_no IS '唯一外部更正编号，同一结案批次内唯一';
COMMENT ON COLUMN t_reversal.reason IS '冲回原因（合法文本，可为任意字符）';
COMMENT ON COLUMN t_reversal.amount_cents IS '申请冲回金额（正整数分）';
COMMENT ON COLUMN t_reversal.status IS 'PENDING 待复核 / APPROVED 已批准追缴中 / REJECTED 已驳回 / COMPLETED 追缴收齐（只读终态）';
COMMENT ON COLUMN t_reversal.approved_cents IS '批准金额（整数分），批准时等于申请额，追缴累计上限';
COMMENT ON COLUMN t_reversal.collected_cents IS '已登记追缴实际到账累计（整数分），恒不超过批准额';
COMMENT ON COLUMN t_reversal.version_no IS '乐观版本号，从 1 开始，每次成功变更加 1；写操作须携带 expectedVersion';

CREATE INDEX idx_reversal_batch ON t_reversal (batch_id);
CREATE INDEX idx_reversal_decl ON t_reversal (batch_id, declaration_no);

CREATE TABLE t_reversal_receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reversal_id BIGINT NOT NULL,
    receipt_serial VARCHAR(64) NOT NULL,
    amount_cents BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    registered_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_receipt_reversal_serial UNIQUE (reversal_id, receipt_serial)
);
COMMENT ON TABLE t_reversal_receipt IS '追缴回执明细：独立流水号，同冲回单内唯一；允许多笔部分到账，乱序不改变总额';
COMMENT ON COLUMN t_reversal_receipt.receipt_serial IS '追缴回执独立流水号；同流水异金额拒绝';
COMMENT ON COLUMN t_reversal_receipt.amount_cents IS '本笔实际收回金额（正整数分）';
COMMENT ON COLUMN t_reversal_receipt.occurred_at IS '回执载明的精确到账时刻(UTC)，与登记时刻区分';
