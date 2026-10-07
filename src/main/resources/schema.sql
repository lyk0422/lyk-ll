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

-- 追缴明细独立编号序列
CREATE SEQUENCE IF NOT EXISTS seq_clawback_no START WITH 1;

-- 结案后分批冲回：余额登记按 批次+报关单 一行，批准时在同一事务内条件累加占用
CREATE TABLE t_chargeback_balance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    declaration_no VARCHAR(64) NOT NULL,
    frozen_cents BIGINT NOT NULL,
    occupied_cents BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_chargeback_balance_decl UNIQUE (batch_id, declaration_no)
);
COMMENT ON TABLE t_chargeback_balance IS '结案后冲回余额：每张已结案批次内报关单一行，占用额为已批准冲回累计（单位：整数分）';
COMMENT ON COLUMN t_chargeback_balance.frozen_cents IS '冻结退税额（结案批次内该报关单校验明细计税依据×100 四舍五入到整数分，单位：分）';
COMMENT ON COLUMN t_chargeback_balance.occupied_cents IS '已批准冲回累计占用额（整数分）；驳回与待复核不占额，占用恰等于冻结即余额归零';

CREATE TABLE t_chargeback_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    check_detail_id BIGINT NOT NULL,
    declaration_no VARCHAR(64) NOT NULL,
    correction_no VARCHAR(64) NOT NULL,
    reason CLOB NOT NULL,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    submitter VARCHAR(64) NOT NULL,
    reviewer VARCHAR(64),
    version_no INT NOT NULL DEFAULT 1,
    submitted_at TIMESTAMP NOT NULL,
    reviewed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_chargeback_batch_correction UNIQUE (batch_id, correction_no)
);
COMMENT ON TABLE t_chargeback_order IS '冲回单：只引用已结案批次内一张报关单及其冻结退税额，不改原文库版本、核销流水或结案总额；状态 PENDING/APPROVED/REJECTED';
COMMENT ON COLUMN t_chargeback_order.check_detail_id IS '引用结案批次内该报关单的关联校验明细（冻结退税额来源），不修改原明细';
COMMENT ON COLUMN t_chargeback_order.correction_no IS '唯一外部更正编号（同批次内唯一）';
COMMENT ON COLUMN t_chargeback_order.reason IS '冲回原因（合法文本，可含任意分隔符）';
COMMENT ON COLUMN t_chargeback_order.amount_cents IS '冲回金额（正数整数分）；累计批准不得超过冻结退税额，恰归零合法';
COMMENT ON COLUMN t_chargeback_order.status IS 'PENDING 待复核 / APPROVED 批准（占用余额并产生追缴明细）/ REJECTED 驳回（不占额）';
COMMENT ON COLUMN t_chargeback_order.version_no IS '乐观版本，从 1 开始，每次成功变更加 1；写操作须携带匹配的 expectedVersion';

CREATE INDEX idx_chargeback_order_decl ON t_chargeback_order (batch_id, declaration_no);

CREATE TABLE t_clawback_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    clawback_no VARCHAR(64) NOT NULL,
    approved_cents BIGINT NOT NULL,
    collected_cents BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    CONSTRAINT uk_clawback_order UNIQUE (order_id),
    CONSTRAINT uk_clawback_no UNIQUE (clawback_no)
);
COMMENT ON TABLE t_clawback_detail IS '追缴明细：冲回单批准时在同一事务内产生的独立明细，一单一条，不回写原批次';
COMMENT ON COLUMN t_clawback_detail.clawback_no IS '追缴明细独立编号';
COMMENT ON COLUMN t_clawback_detail.approved_cents IS '批准追缴金额（整数分），批准后不随后续余额变化重算';
COMMENT ON COLUMN t_clawback_detail.collected_cents IS '累计实际收回（整数分），允许多笔部分到账，不超批准金额';
COMMENT ON COLUMN t_clawback_detail.status IS 'COLLECTING 追缴中 / COMPLETED 已收齐（恰收齐即完成，此后只读）';
COMMENT ON COLUMN t_clawback_detail.version_no IS '乐观版本，从 1 开始，每笔成功回执加 1；回执须携带匹配的 expectedVersion';

CREATE TABLE t_clawback_receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    detail_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    receipt_serial VARCHAR(64) NOT NULL,
    received_cents BIGINT NOT NULL,
    received_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_receipt_serial UNIQUE (receipt_serial)
);
COMMENT ON TABLE t_clawback_receipt IS '追缴回执：独立流水号，可多笔部分到账；同流水异金额拒绝，乱序到账不改变总额';
COMMENT ON COLUMN t_clawback_receipt.receipt_serial IS '追缴回执独立流水号（全局唯一）';
COMMENT ON COLUMN t_clawback_receipt.received_cents IS '本笔实际收回金额（正数整数分）';
COMMENT ON COLUMN t_clawback_receipt.received_at IS '回执业务时刻(UTC，精确时刻，来自请求)';

-- 冲回新写操作的 requestId 幂等：占坑、业务变更与结果回写同事务提交，业务失败随事务回滚即释放键
CREATE TABLE t_chargeback_request (
    request_id VARCHAR(64) PRIMARY KEY,
    op_type VARCHAR(40) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    response_status INT,
    response_body CLOB,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE t_chargeback_request IS '冲回写操作 requestId 幂等记录：业务、版本与幂等结果同事务；失败回滚不占键，同键同参重放首次结果，异参 409';

-- 真实银行流水全局互斥登记：单笔回执与汇总母流水共享同一命名空间，同一流水只能用于其中一种入口
CREATE TABLE t_bank_serial (
    serial VARCHAR(64) PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE t_bank_serial IS '真实银行流水全局互斥登记：主键保证同一流水只登记一次；SINGLE 单笔回执 / MASTER 汇总母流水，两种入口互斥';
COMMENT ON COLUMN t_bank_serial.kind IS '流水用途：SINGLE 旧单笔追缴回执 / MASTER 汇总分配母流水';

-- 汇总分配母凭证独立编号序列（分配行内部标识，不是银行流水）
CREATE SEQUENCE IF NOT EXISTS seq_allocation_no START WITH 1;

-- 汇总分配母凭证：一笔真实到账只记一次，引用原结案批次，分配行分摊到多张已批准冲回单
CREATE TABLE t_clawback_master_receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    master_serial VARCHAR(64) NOT NULL,
    total_cents BIGINT NOT NULL,
    received_at TIMESTAMP NOT NULL,
    content_fingerprint VARCHAR(64) NOT NULL,
    response_body CLOB,
    version_no INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_master_serial UNIQUE (master_serial)
);
COMMENT ON TABLE t_clawback_master_receipt IS '汇总分配母凭证：一笔真实银行到账只记一次（不拆成多笔伪流水），同流水同内容重放首份分配，异内容 409';
COMMENT ON COLUMN t_clawback_master_receipt.master_serial IS '唯一真实银行流水号（全局唯一，与单笔回执流水互斥）';
COMMENT ON COLUMN t_clawback_master_receipt.total_cents IS '到账总额（正数整数分），精确等于各分配行合计';
COMMENT ON COLUMN t_clawback_master_receipt.received_at IS '到账业务时刻(UTC，精确时刻，来自请求)';
COMMENT ON COLUMN t_clawback_master_receipt.content_fingerprint IS '分配内容指纹（批次+流水+总额+时刻+分配对象与金额），用于同流水异内容 409 判定';
COMMENT ON COLUMN t_clawback_master_receipt.response_body IS '首份分配结果快照（JSON），同流水同内容重放时原样返回';
COMMENT ON COLUMN t_clawback_master_receipt.version_no IS '母凭证版本，恒为 1（母凭证只记一次，不可修改）';

CREATE TABLE t_clawback_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    master_id BIGINT NOT NULL,
    detail_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    allocation_no VARCHAR(64) NOT NULL,
    amount_cents BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_allocation_no UNIQUE (allocation_no)
);
COMMENT ON TABLE t_clawback_allocation IS '汇总分配行：母凭证分摊到各追缴明细的金额；内部标识仅作勾稽，不冒充银行流水';
COMMENT ON COLUMN t_clawback_allocation.allocation_no IS '分配行独立内部标识（ALC- 开头序列，非银行流水号）';
COMMENT ON COLUMN t_clawback_allocation.amount_cents IS '本分摊金额（正数整数分），不超过该单未收回金额';

CREATE INDEX idx_allocation_master ON t_clawback_allocation (master_id);
CREATE INDEX idx_allocation_detail ON t_clawback_allocation (detail_id);
