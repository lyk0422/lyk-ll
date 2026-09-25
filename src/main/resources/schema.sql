-- 出口退税申报与换汇成本校验：H2（MODE=MySQL）建表脚本，仅合成数据，JVM 生命周期内有效。

CREATE TABLE IF NOT EXISTS rate_library (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    hs_code         VARCHAR(32)  NOT NULL,
    effective_date  DATE         NOT NULL,
    rate            DECIMAL(10,6) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rate_lib_version UNIQUE (hs_code, effective_date)
);
COMMENT ON TABLE rate_library IS '退税率文库版本表，按申报日取不晚于该日的最新版本';
COMMENT ON COLUMN rate_library.hs_code IS '商品编码';
COMMENT ON COLUMN rate_library.effective_date IS '文库版本生效日（UTC 日界）';
COMMENT ON COLUMN rate_library.rate IS '退税率（小数，如 0.130000）';

-- 采集单证：一张报关单与一张进项发票按商品编码配成一条
CREATE TABLE IF NOT EXISTS rebate_document (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    declaration_no  VARCHAR(64) NOT NULL,
    invoice_no      VARCHAR(64) NOT NULL,
    hs_code         VARCHAR(32) NOT NULL,
    fob_rmb         DECIMAL(18,4) NOT NULL,
    fob_usd         DECIMAL(18,4) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    batch_id        BIGINT NULL,
    collected_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at     TIMESTAMP NULL,
    CONSTRAINT uk_doc_pair UNIQUE (declaration_no, invoice_no)
);
COMMENT ON TABLE rebate_document IS '报关单-发票配对单证表，按报关单号+发票号去重';
COMMENT ON COLUMN rebate_document.declaration_no IS '报关单号';
COMMENT ON COLUMN rebate_document.invoice_no IS '进项发票号';
COMMENT ON COLUMN rebate_document.hs_code IS '商品编码（报关单与发票关联键）';
COMMENT ON COLUMN rebate_document.fob_rmb IS 'FOB 价（人民币元，计税依据基数）';
COMMENT ON COLUMN rebate_document.fob_usd IS 'FOB 美元价（美元，换汇成本分母）';
COMMENT ON COLUMN rebate_document.status IS '状态：COLLECTED 已采集/RELEASED 已释放/LATE 结案后迟到';
COMMENT ON COLUMN rebate_document.batch_id IS '所属有效申报批次；撤批后置空表示单证已释放';
COMMENT ON COLUMN rebate_document.collected_at IS '采集时间（UTC）';
COMMENT ON COLUMN rebate_document.released_at IS '撤批释放时间（UTC）';

CREATE TABLE IF NOT EXISTS rebate_batch (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no        VARCHAR(64) NOT NULL,
    declare_date    DATE NOT NULL,
    status          VARCHAR(24) NOT NULL,
    tax_total       DECIMAL(18,4) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    declared_at     TIMESTAMP NULL,
    approved_at     TIMESTAMP NULL,
    refunded_at     TIMESTAMP NULL,
    voided_at       TIMESTAMP NULL,
    closed_at       TIMESTAMP NULL,
    CONSTRAINT uk_batch_no UNIQUE (batch_no)
);
COMMENT ON TABLE rebate_batch IS '退税申报批次主表';
COMMENT ON COLUMN rebate_batch.batch_no IS '申报批次号';
COMMENT ON COLUMN rebate_batch.declare_date IS '申报日（UTC 日界），决定退税率文库版本';
COMMENT ON COLUMN rebate_batch.status IS '状态：DRAFT 待申报/DECLARED 已正式申报(冻结)/APPROVED 审核通过/REFUNDED 已退库/CLOSED 已结案(终态)/VOID 已作废';
COMMENT ON COLUMN rebate_batch.tax_total IS '申报税额合计（元）';
COMMENT ON COLUMN rebate_batch.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN rebate_batch.declared_at IS '正式申报时间（UTC）';
COMMENT ON COLUMN rebate_batch.approved_at IS '审核通过时间（UTC）';
COMMENT ON COLUMN rebate_batch.refunded_at IS '退库完成时间（UTC）';
COMMENT ON COLUMN rebate_batch.voided_at IS '撤批作废时间（UTC）';
COMMENT ON COLUMN rebate_batch.closed_at IS '结案时间（UTC，终态）';

CREATE TABLE IF NOT EXISTS validation_detail (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id            BIGINT NOT NULL,
    doc_id              BIGINT NOT NULL,
    hs_code             VARCHAR(32) NOT NULL,
    rate                DECIMAL(10,6) NOT NULL,
    rate_effective_date DATE NOT NULL,
    tax_base            DECIMAL(18,4) NOT NULL,
    cost                DECIMAL(18,10) NOT NULL,
    result              VARCHAR(16) NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_detail_doc UNIQUE (batch_id, doc_id)
);
COMMENT ON TABLE validation_detail IS '关联校验明细，可按批次复算';
COMMENT ON COLUMN validation_detail.rate IS '实际采用退税率';
COMMENT ON COLUMN validation_detail.rate_effective_date IS '采用文库版本生效日（UTC）';
COMMENT ON COLUMN validation_detail.tax_base IS '计税依据=FOB价×退税率（元）';
COMMENT ON COLUMN validation_detail.cost IS '换汇成本=计税依据÷FOB美元价';
COMMENT ON COLUMN validation_detail.result IS '校验结果：PASS 通过/DOUBT 疑点';

CREATE TABLE IF NOT EXISTS doubt (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id            BIGINT NOT NULL,
    doc_id              BIGINT NOT NULL,
    status              VARCHAR(16) NOT NULL,
    explanation         VARCHAR(1000) NULL,
    explain_request_id  VARCHAR(64) NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    explained_at        TIMESTAMP NULL,
    reviewed_at         TIMESTAMP NULL
);
COMMENT ON TABLE doubt IS '换汇成本越界疑点，须说明+复核确认后方可解除';
COMMENT ON COLUMN doubt.status IS '疑点状态：OPEN 待说明/EXPLAINED 已说明待复核/CLEARED 已复核解除';
COMMENT ON COLUMN doubt.explanation IS '补充说明';
COMMENT ON COLUMN doubt.explain_request_id IS '说明提交的幂等 requestId';
COMMENT ON COLUMN doubt.explained_at IS '说明提交时间（UTC）';
COMMENT ON COLUMN doubt.reviewed_at IS '复核确认时间（UTC）';

-- 报关单有效批次占用登记：同一报关单号在批次非作废期间唯一占用；撤批时删除
CREATE TABLE IF NOT EXISTS declaration_registration (
    declaration_no  VARCHAR(64) PRIMARY KEY,
    batch_id        BIGINT NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE declaration_registration IS '报关单号有效批次占用登记，保证同一报关单号只参与一个有效申报批次';
COMMENT ON COLUMN declaration_registration.declaration_no IS '报关单号（占用唯一键）';
COMMENT ON COLUMN declaration_registration.batch_id IS '占用该报关单号的批次 ID';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id      VARCHAR(64) PRIMARY KEY,
    biz_type        VARCHAR(32) NOT NULL,
    fingerprint     VARCHAR(64) NOT NULL,
    status_code     INT NOT NULL,
    response_json   CLOB NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE idempotent_request IS '幂等请求登记表：同键同参重放，同键异参 409';
COMMENT ON COLUMN idempotent_request.request_id IS '业务幂等键 requestId';
COMMENT ON COLUMN idempotent_request.biz_type IS '业务类型：COLLECT/SUBMIT_EXPLANATION/DECLARE/CREATE_BATCH/REFUND 等';
COMMENT ON COLUMN idempotent_request.fingerprint IS 'requestId+全部业务字段的 SHA-256 指纹';
COMMENT ON COLUMN idempotent_request.status_code IS '首次响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '首次响应体 JSON';

CREATE TABLE IF NOT EXISTS refund_registration (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id            BIGINT NOT NULL,
    treasury_serial_no  VARCHAR(64) NOT NULL,
    declared_tax        DECIMAL(18,4) NOT NULL,
    received_amount     DECIMAL(18,4) NOT NULL,
    diff_ratio          DECIMAL(18,10) NOT NULL,
    status              VARCHAR(24) NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_refund_serial UNIQUE (batch_id, treasury_serial_no)
);
COMMENT ON TABLE refund_registration IS '退库核销登记，批次+国库流水号唯一，差额>0.5% 转人工（恰 0.5% 一致）';
COMMENT ON COLUMN refund_registration.treasury_serial_no IS '国库流水号';
COMMENT ON COLUMN refund_registration.declared_tax IS '申报税额（元）';
COMMENT ON COLUMN refund_registration.received_amount IS '实际到账金额（元）';
COMMENT ON COLUMN refund_registration.diff_ratio IS '到账差额比例=|到账-申报|/申报';
COMMENT ON COLUMN refund_registration.status IS '核销状态：CONSISTENT 一致/DISCREPANCY 差异转人工';

CREATE TABLE IF NOT EXISTS late_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id        BIGINT NOT NULL,
    declaration_no  VARCHAR(64) NOT NULL,
    invoice_no      VARCHAR(64) NOT NULL,
    payload         CLOB NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE late_event IS '结案后迟到单证仅登记事件，不改动终态批次';
COMMENT ON COLUMN late_event.declaration_no IS '迟到报关单号';
COMMENT ON COLUMN late_event.invoice_no IS '迟到发票号';
COMMENT ON COLUMN late_event.payload IS '迟到单证原始报文';
