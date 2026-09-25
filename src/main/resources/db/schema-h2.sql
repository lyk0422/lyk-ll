-- 本地再保分保服务建表脚本（H2，MODE=MySQL）。仅在启动时由 SchemaInitializer 执行一次。
-- 金额单位：人民币元（DECIMAL(18,2)）；比例：DECIMAL(9,6)；时间均为 UTC。

CREATE TABLE IF NOT EXISTS treaty (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_no      VARCHAR(64)  NOT NULL CONSTRAINT uk_treaty_contract_no UNIQUE,
    cession_rate     DECIMAL(9,6) NOT NULL,
    per_policy_limit DECIMAL(18,2),
    created_at       TIMESTAMP    NOT NULL
);
COMMENT ON TABLE treaty IS '再保比例合约';
COMMENT ON COLUMN treaty.contract_no IS '合约号（业务唯一键）';
COMMENT ON COLUMN treaty.cession_rate IS '分保比例，取值 0~1，如 0.300000 表示 30%';
COMMENT ON COLUMN treaty.per_policy_limit IS '每张保单再保人责任限额（元）；NULL 表示无限额';
COMMENT ON COLUMN treaty.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS policy (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_no      VARCHAR(64)  NOT NULL CONSTRAINT uk_policy_policy_no UNIQUE,
    contract_id    BIGINT       NOT NULL,
    premium        DECIMAL(18,2) NOT NULL,
    effective_date DATE         NOT NULL,
    bill_id        BIGINT,
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT fk_policy_contract FOREIGN KEY (contract_id) REFERENCES treaty (id)
);
COMMENT ON TABLE policy IS '分出保单（合成数据）';
COMMENT ON COLUMN policy.policy_no IS '保单号（业务唯一键）';
COMMENT ON COLUMN policy.contract_id IS '所属合约 ID';
COMMENT ON COLUMN policy.premium IS '分出保费（元）';
COMMENT ON COLUMN policy.effective_date IS '保单生效日期（UTC 日界），决定所属账单季度';
COMMENT ON COLUMN policy.bill_id IS '已计入的账单 ID；NULL 表示尚未出账，防止重复出账';
COMMENT ON COLUMN policy.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS claim (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_no         VARCHAR(64)  NOT NULL CONSTRAINT uk_claim_claim_no UNIQUE,
    policy_id        BIGINT       NOT NULL,
    estimated_amount DECIMAL(18,2) NOT NULL,
    paid_amount      DECIMAL(18,2),
    status           VARCHAR(16)  NOT NULL,
    loss_date        DATE         NOT NULL,
    closed_at        TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL,
    CONSTRAINT fk_claim_policy FOREIGN KEY (policy_id) REFERENCES policy (id)
);
COMMENT ON TABLE claim IS '赔案（合成数据）';
COMMENT ON COLUMN claim.claim_no IS '赔案号（业务唯一键）';
COMMENT ON COLUMN claim.policy_id IS '所属保单 ID';
COMMENT ON COLUMN claim.estimated_amount IS '最新估损金额（元）；结案后迟到调整只记事件不改此值';
COMMENT ON COLUMN claim.paid_amount IS '实际赔付金额（元）；未结案时为 NULL';
COMMENT ON COLUMN claim.status IS '赔案状态：OPEN=未决，FINAL_CLOSED=最终结案（终态）';
COMMENT ON COLUMN claim.loss_date IS '出险日期（UTC 日界）';
COMMENT ON COLUMN claim.closed_at IS '最终结案时间（UTC）；NULL 表示未结案';
COMMENT ON COLUMN claim.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS reinsurance_account (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_id BIGINT       NOT NULL CONSTRAINT uk_account_contract UNIQUE,
    balance     DECIMAL(18,2) NOT NULL DEFAULT 0 CONSTRAINT ck_account_balance CHECK (balance >= 0),
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT fk_account_contract FOREIGN KEY (contract_id) REFERENCES treaty (id)
);
COMMENT ON TABLE reinsurance_account IS '合约级摊回账户（再保人应付摊回准备金）';
COMMENT ON COLUMN reinsurance_account.contract_id IS '所属合约 ID';
COMMENT ON COLUMN reinsurance_account.balance IS '摊回账户余额（元）；任何时刻不得为负，数据库层强制约束';
COMMENT ON COLUMN reinsurance_account.updated_at IS '最近变动时间（UTC）';

CREATE TABLE IF NOT EXISTS claim_recovery (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id          BIGINT       NOT NULL CONSTRAINT uk_recovery_claim UNIQUE,
    policy_id         BIGINT       NOT NULL,
    contract_id       BIGINT       NOT NULL,
    provision_amount  DECIMAL(18,2) NOT NULL DEFAULT 0,
    settled_amount    DECIMAL(18,2) NOT NULL DEFAULT 0,
    capped_excess     DECIMAL(18,2) NOT NULL DEFAULT 0,
    recovery_status   VARCHAR(16)  NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    CONSTRAINT fk_recovery_claim FOREIGN KEY (claim_id) REFERENCES claim (id)
);
COMMENT ON TABLE claim_recovery IS '赔案摊回状态：未决预提与结案实摊';
COMMENT ON COLUMN claim_recovery.claim_id IS '赔案 ID';
COMMENT ON COLUMN claim_recovery.policy_id IS '所属保单 ID（限额按保单累计）';
COMMENT ON COLUMN claim_recovery.contract_id IS '所属合约 ID';
COMMENT ON COLUMN claim_recovery.provision_amount IS '当前未决预提摊回（元）；结案冲销后归零';
COMMENT ON COLUMN claim_recovery.settled_amount IS '最终结案实摊金额（元）';
COMMENT ON COLUMN claim_recovery.capped_excess IS '因保单责任限额而留在分出公司的金额（元）';
COMMENT ON COLUMN claim_recovery.recovery_status IS '摊回状态：PROVISIONED=预提中，SETTLED=已结案实摊';
COMMENT ON COLUMN claim_recovery.updated_at IS '最近更新时间（UTC）';

CREATE TABLE IF NOT EXISTS account_ledger (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_id    BIGINT       NOT NULL,
    policy_id      BIGINT,
    claim_id       BIGINT,
    bill_id        BIGINT,
    movement_type  VARCHAR(24)  NOT NULL,
    gross_amount   DECIMAL(18,2) NOT NULL,
    cession_rate   DECIMAL(9,6) NOT NULL,
    amount         DECIMAL(18,2) NOT NULL,
    balance_after  DECIMAL(18,2) NOT NULL CONSTRAINT ck_ledger_balance CHECK (balance_after >= 0),
    event_date     DATE         NOT NULL,
    request_id     VARCHAR(64),
    created_at     TIMESTAMP    NOT NULL
);
COMMENT ON TABLE account_ledger IS '摊回账户流水（预提/调整/结案冲销逐笔留痕）';
COMMENT ON COLUMN account_ledger.contract_id IS '所属合约 ID';
COMMENT ON COLUMN account_ledger.policy_id IS '关联保单 ID；非赔案动作为空';
COMMENT ON COLUMN account_ledger.claim_id IS '关联赔案 ID；非赔案动作为空';
COMMENT ON COLUMN account_ledger.bill_id IS '已计入账单 ID；NULL 表示尚未出账';
COMMENT ON COLUMN account_ledger.movement_type IS '变动类型：PROVISION=预提，ADJUST=估损调整，SETTLE=结案冲销差额';
COMMENT ON COLUMN account_ledger.gross_amount IS '带符号业务基数（元）：预提为估损，调整为估损差额，结案为(实赔-估损)';
COMMENT ON COLUMN account_ledger.cession_rate IS '该笔适用分保比例';
COMMENT ON COLUMN account_ledger.amount IS '带符号摊回变动额（元）：预提为正、冲销为负；受限额截断';
COMMENT ON COLUMN account_ledger.balance_after IS '该笔变动后的账户余额（元），不得为负';
COMMENT ON COLUMN account_ledger.event_date IS '业务发生日期（UTC 日界），决定出账季度';
COMMENT ON COLUMN account_ledger.request_id IS '触发该流水的幂等键';
COMMENT ON COLUMN account_ledger.created_at IS '落账时间（UTC）';

CREATE TABLE IF NOT EXISTS claim_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id    BIGINT       NOT NULL,
    event_type  VARCHAR(32)  NOT NULL,
    from_amount DECIMAL(18,2),
    to_amount   DECIMAL(18,2),
    note        VARCHAR(500),
    created_at  TIMESTAMP    NOT NULL
);
COMMENT ON TABLE claim_event IS '赔案事件流；结案后的迟到估损调整只在此留痕';
COMMENT ON COLUMN claim_event.claim_id IS '赔案 ID';
COMMENT ON COLUMN claim_event.event_type IS '事件类型：CREATED/ESTIMATE_ADJUSTED/LATE_ESTIMATE_RECORDED/SETTLED';
COMMENT ON COLUMN claim_event.from_amount IS '变动前金额（元），可空';
COMMENT ON COLUMN claim_event.to_amount IS '变动后金额（元），可空';
COMMENT ON COLUMN claim_event.note IS '事件说明';
COMMENT ON COLUMN claim_event.created_at IS '记录时间（UTC）';

CREATE TABLE IF NOT EXISTS bill (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_no              VARCHAR(64)  NOT NULL CONSTRAINT uk_bill_bill_no UNIQUE,
    contract_id          BIGINT       NOT NULL,
    period               VARCHAR(8)   NOT NULL,
    bill_type            VARCHAR(16)  NOT NULL,
    ref_bill_no          VARCHAR(64),
    status               VARCHAR(16)  NOT NULL,
    cedant_confirmed     BOOLEAN      NOT NULL DEFAULT FALSE,
    reinsurer_confirmed  BOOLEAN      NOT NULL DEFAULT FALSE,
    premium_total        DECIMAL(18,2) NOT NULL DEFAULT 0,
    recovery_total       DECIMAL(18,2) NOT NULL DEFAULT 0,
    net_total            DECIMAL(18,2) NOT NULL DEFAULT 0,
    created_at           TIMESTAMP    NOT NULL,
    sealed_at            TIMESTAMP,
    CONSTRAINT fk_bill_contract FOREIGN KEY (contract_id) REFERENCES treaty (id),
    CONSTRAINT uk_bill_quarter UNIQUE (contract_id, period, bill_type)
);
COMMENT ON TABLE bill IS '再保分保账单（季度账单/下一期调整单）';
COMMENT ON COLUMN bill.bill_no IS '账单号（业务唯一键），季度单：RB-合约号-季度；调整单：RA-...';
COMMENT ON COLUMN bill.contract_id IS '所属合约 ID';
COMMENT ON COLUMN bill.period IS '账单季度（UTC 日界），格式 yyyy-Qn，如 2026-Q3';
COMMENT ON COLUMN bill.bill_type IS '账单类型：QUARTERLY=季度账单，ADJUSTMENT=下一期调整单';
COMMENT ON COLUMN bill.ref_bill_no IS '调整单引用的原账单号；季度单为空';
COMMENT ON COLUMN bill.status IS '账单状态：PENDING=待确认，DISPUTED=有异议，SEALED=双方确认已封存（终态）';
COMMENT ON COLUMN bill.cedant_confirmed IS '分出公司是否已确认；账单生成时置真';
COMMENT ON COLUMN bill.reinsurer_confirmed IS '再保人是否已确认；与分出方均确认后封存';
COMMENT ON COLUMN bill.premium_total IS '保费分保合计（元），须等于明细分保保费之和';
COMMENT ON COLUMN bill.recovery_total IS '赔款摊回合计（元），须等于明细摊回之和';
COMMENT ON COLUMN bill.net_total IS '账单净额（元）=保费分保合计-赔款摊回合计';
COMMENT ON COLUMN bill.created_at IS '生成时间（UTC）';
COMMENT ON COLUMN bill.sealed_at IS '封存时间（UTC）；NULL 表示未封存';

CREATE TABLE IF NOT EXISTS bill_item (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_id      BIGINT       NOT NULL,
    line_no      INT          NOT NULL,
    item_type    VARCHAR(16)  NOT NULL,
    ref_no       VARCHAR(64)  NOT NULL,
    ref_bill_no  VARCHAR(64),
    gross_amount DECIMAL(18,2) NOT NULL,
    cession_rate DECIMAL(9,6) NOT NULL,
    amount       DECIMAL(18,2) NOT NULL,
    remark       VARCHAR(500),
    CONSTRAINT fk_item_bill FOREIGN KEY (bill_id) REFERENCES bill (id),
    CONSTRAINT uk_item_line UNIQUE (bill_id, line_no)
);
COMMENT ON TABLE bill_item IS '账单明细，可逐笔复算；全部明细合计必须等于账单汇总';
COMMENT ON COLUMN bill_item.bill_id IS '所属账单 ID';
COMMENT ON COLUMN bill_item.line_no IS '账单内行号，从 1 开始';
COMMENT ON COLUMN bill_item.item_type IS '明细类型：PREMIUM=保费分保，CLAIM_RECOVERY=赔款摊回，ADJUSTMENT=调整项';
COMMENT ON COLUMN bill_item.ref_no IS '业务关联号：保单号/赔案号';
COMMENT ON COLUMN bill_item.ref_bill_no IS '调整项引用的原账单号；非调整项为空';
COMMENT ON COLUMN bill_item.gross_amount IS '计算基数（元）：分出保费或赔案赔款';
COMMENT ON COLUMN bill_item.cession_rate IS '该行适用分保比例';
COMMENT ON COLUMN bill_item.amount IS '分入/摊回金额（元）；受责任限额截断时按限额计入';
COMMENT ON COLUMN bill_item.remark IS '明细说明（如限额截断、结案冲销）';

CREATE TABLE IF NOT EXISTS bill_dispute (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_id         BIGINT       NOT NULL,
    item_ref        VARCHAR(64)  NOT NULL,
    expected_amount DECIMAL(18,2) NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT fk_dispute_bill FOREIGN KEY (bill_id) REFERENCES bill (id)
);
COMMENT ON TABLE bill_dispute IS '账单异议明细；异议必须逐项列明差异原因';
COMMENT ON COLUMN bill_dispute.bill_id IS '所属账单 ID';
COMMENT ON COLUMN bill_dispute.item_ref IS '产生异议的账单明细行号或关联号';
COMMENT ON COLUMN bill_dispute.expected_amount IS '异议方认为正确的金额（元）';
COMMENT ON COLUMN bill_dispute.reason IS '该项差异原因（逐项必填）';
COMMENT ON COLUMN bill_dispute.created_at IS '异议提出时间（UTC）';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id    VARCHAR(64)  NOT NULL PRIMARY KEY,
    operation     VARCHAR(40)  NOT NULL,
    fingerprint   CHAR(64)     NOT NULL,
    http_status   INT          NOT NULL,
    result_ref    VARCHAR(64),
    result_json   CLOB,
    created_at    TIMESTAMP    NOT NULL
);
COMMENT ON TABLE idempotency_record IS 'requestId 幂等记录：指纹覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等键 requestId（全局唯一）';
COMMENT ON COLUMN idempotency_record.operation IS '操作类型（GENERATE_BILL/CONFIRM_BILL/DISPUTE_BILL/CLOSE_CLAIM 等）';
COMMENT ON COLUMN idempotency_record.fingerprint IS 'SHA-256 指纹，覆盖 requestId 与规范化后的全部业务字段';
COMMENT ON COLUMN idempotency_record.http_status IS '首次响应 HTTP 状态码';
COMMENT ON COLUMN idempotency_record.result_ref IS '首次结果业务单号（账单号/赔案号）';
COMMENT ON COLUMN idempotency_record.result_json IS '首次响应体 JSON，重放时原样返回';
COMMENT ON COLUMN idempotency_record.created_at IS '首次请求时间（UTC）';
