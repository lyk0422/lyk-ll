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

-- 结案批次分批冲回：金额一律为正数整数分（冻结退税额由计税依据元×100 严格换算，存在分以下尾数的报关单禁止冲回）
CREATE TABLE t_reversal_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    declaration_no VARCHAR(64) NOT NULL,
    frozen_cents BIGINT NOT NULL,
    occupied_cents BIGINT NOT NULL DEFAULT 0,
    received_cents BIGINT NOT NULL DEFAULT 0,
    version_no INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_reversal_ledger_batch_decl UNIQUE (batch_id, declaration_no)
);
COMMENT ON TABLE t_reversal_ledger IS '冲回可冲余额台账（逐报关单一行）：结案冲回只占用本行额度，不改结案总额与核销流水';
COMMENT ON COLUMN t_reversal_ledger.frozen_cents IS '冻结退税额（整数分），=结案明细 tax_basis(元)×100';
COMMENT ON COLUMN t_reversal_ledger.occupied_cents IS '已批准冲回累计占用额（整数分），批准时条件更新防止并发超冲';
COMMENT ON COLUMN t_reversal_ledger.received_cents IS '追缴回执累计实际到账额（整数分），乱序到账不改变总额';
COMMENT ON COLUMN t_reversal_ledger.version_no IS '台账行版本号，从 1 开始，每次占用/到账成功 +1';

CREATE TABLE t_reversal_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    declaration_no VARCHAR(64) NOT NULL,
    correction_no VARCHAR(64) NOT NULL,
    reason CLOB NOT NULL,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    submitter VARCHAR(64) NOT NULL,
    reviewer VARCHAR(64),
    received_cents BIGINT NOT NULL DEFAULT 0,
    version_no INT NOT NULL DEFAULT 1,
    submitted_at TIMESTAMP NOT NULL,
    reviewed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_reversal_batch_correction UNIQUE (batch_id, correction_no)
);
COMMENT ON TABLE t_reversal_order IS '分批冲回单：PENDING 待复核 / APPROVED 已批准追缴中 / REJECTED 已驳回不占额 / COMPLETED 追缴完成只读';
COMMENT ON COLUMN t_reversal_order.correction_no IS '外部更正编号，同批次同编号最多一单';
COMMENT ON COLUMN t_reversal_order.reason IS '冲回原因（合法文本可含任意分隔符）';
COMMENT ON COLUMN t_reversal_order.amount_cents IS '申请冲回金额（正数整数分），累计批准不得超过冻结退税额，恰归零合法';
COMMENT ON COLUMN t_reversal_order.status IS 'PENDING/APPROVED/REJECTED/COMPLETED';
COMMENT ON COLUMN t_reversal_order.received_cents IS '已登记追缴回执累计到账（整数分），不超批准金额';
COMMENT ON COLUMN t_reversal_order.version_no IS '乐观版本，从 1 开始，每次成功变更 +1；写操作须携带 expectedVersion';

CREATE INDEX idx_reversal_order_batch_decl ON t_reversal_order (batch_id, declaration_no);

CREATE TABLE t_clawback_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reversal_id BIGINT NOT NULL,
    clawback_serial VARCHAR(64) NOT NULL,
    amount_cents BIGINT NOT NULL,
    approved_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_clawback_reversal UNIQUE (reversal_id),
    CONSTRAINT uk_clawback_serial UNIQUE (clawback_serial)
);
COMMENT ON TABLE t_clawback_detail IS '批准时同事务产生的独立追缴明细（独立追缴流水号），与原核销流水互不影响';
COMMENT ON COLUMN t_clawback_detail.clawback_serial IS '追缴明细独立流水号，CL+定长主键';
COMMENT ON COLUMN t_clawback_detail.amount_cents IS '批准追缴金额（整数分），回执累计不得超过该值';

CREATE TABLE t_clawback_receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reversal_id BIGINT NOT NULL,
    receipt_serial VARCHAR(64) NOT NULL,
    amount_cents BIGINT NOT NULL,
    received_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_clawback_receipt_serial UNIQUE (receipt_serial)
);
COMMENT ON TABLE t_clawback_receipt IS '追缴到账回执：独立流水号全局唯一，允许多笔部分到账；同流水异金额拒绝、同流水同金额去重';
COMMENT ON COLUMN t_clawback_receipt.receipt_serial IS '追缴回执独立流水号';
COMMENT ON COLUMN t_clawback_receipt.amount_cents IS '本笔实际收回金额（正数整数分）';

CREATE INDEX idx_clawback_receipt_reversal ON t_clawback_receipt (reversal_id);
