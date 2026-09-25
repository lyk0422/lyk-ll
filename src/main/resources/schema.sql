-- 再保险分保账单与摊回核销服务建表脚本（H2 MySQL 兼容模式）
-- 金额单位：元，保留 2 位小数；比例保留 6 位小数；时间均为带时区时间戳，业务口径为 UTC 日界。

CREATE TABLE IF NOT EXISTS treaty (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    treaty_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    cession_rate DECIMAL(9, 6) NOT NULL,
    limit_per_policy DECIMAL(19, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_treaty_no UNIQUE (treaty_no)
);
COMMENT ON TABLE treaty IS '比例再保合约';
COMMENT ON COLUMN treaty.treaty_no IS '合约编号，全局唯一';
COMMENT ON COLUMN treaty.name IS '合约名称';
COMMENT ON COLUMN treaty.cession_rate IS '分保比例，取值 (0,1]，如 0.8 表示分出 80%';
COMMENT ON COLUMN treaty.limit_per_policy IS '每张保单每期账单责任限额（元），恰达限额按限额计入，超出部分留在分出公司';
COMMENT ON COLUMN treaty.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS policy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_no VARCHAR(64) NOT NULL,
    treaty_id BIGINT NOT NULL,
    ceded_premium DECIMAL(19, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_policy_no UNIQUE (policy_no),
    CONSTRAINT fk_policy_treaty FOREIGN KEY (treaty_id) REFERENCES treaty (id)
);
COMMENT ON TABLE policy IS '分出保单（合成数据）';
COMMENT ON COLUMN policy.policy_no IS '保单号，全局唯一';
COMMENT ON COLUMN policy.treaty_id IS '所属合约主键';
COMMENT ON COLUMN policy.ceded_premium IS '分出保费（元），账单保费分保 = 分出保费 × 比例';
COMMENT ON COLUMN policy.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS claim (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_no VARCHAR(64) NOT NULL,
    policy_id BIGINT NOT NULL,
    estimate_amount DECIMAL(19, 2) NOT NULL,
    paid_amount DECIMAL(19, 2) NULL,
    status VARCHAR(16) NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_claim_no UNIQUE (claim_no),
    CONSTRAINT fk_claim_policy FOREIGN KEY (policy_id) REFERENCES policy (id)
);
COMMENT ON TABLE claim IS '赔案（合成数据）';
COMMENT ON COLUMN claim.claim_no IS '赔案号，全局唯一';
COMMENT ON COLUMN claim.policy_id IS '所属保单主键';
COMMENT ON COLUMN claim.estimate_amount IS '当前估损金额（元），未决时按此预提摊回';
COMMENT ON COLUMN claim.paid_amount IS '最终实际赔付金额（元），未结案为空';
COMMENT ON COLUMN claim.status IS '状态：OPEN=未决，CLOSED=最终结案（终态）';
COMMENT ON COLUMN claim.closed_at IS '最终结案时间（UTC），未结案为空';
COMMENT ON COLUMN claim.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS claim_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    old_estimate DECIMAL(19, 2) NULL,
    new_estimate DECIMAL(19, 2) NULL,
    note VARCHAR(512) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_event_claim FOREIGN KEY (claim_id) REFERENCES claim (id)
);
COMMENT ON TABLE claim_event IS '赔案事件流水；结案后迟到的估损调整只记事件、不改账务';
COMMENT ON COLUMN claim_event.claim_id IS '所属赔案主键';
COMMENT ON COLUMN claim_event.event_type IS '事件类型，如 LATE_ESTIMATE_ADJUST=结案后迟到估损调整';
COMMENT ON COLUMN claim_event.old_estimate IS '调整前估损（元），可空';
COMMENT ON COLUMN claim_event.new_estimate IS '调整后估损（元），可空';
COMMENT ON COLUMN claim_event.note IS '事件备注';
COMMENT ON COLUMN claim_event.created_at IS '事件时间（UTC）';

CREATE TABLE IF NOT EXISTS recovery_account (
    treaty_id BIGINT PRIMARY KEY,
    balance DECIMAL(19, 2) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_account_treaty FOREIGN KEY (treaty_id) REFERENCES treaty (id),
    CONSTRAINT chk_balance_nonneg CHECK (balance >= 0)
);
COMMENT ON TABLE recovery_account IS '摊回账户（每合约一户），余额任何时刻不得为负';
COMMENT ON COLUMN recovery_account.treaty_id IS '所属合约主键';
COMMENT ON COLUMN recovery_account.balance IS '摊回预提余额（元），预提增加、结案冲销减少，不得为负';
COMMENT ON COLUMN recovery_account.updated_at IS '最近变动时间（UTC）';

CREATE TABLE IF NOT EXISTS account_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    treaty_id BIGINT NOT NULL,
    claim_id BIGINT NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    balance_after DECIMAL(19, 2) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_entry_treaty FOREIGN KEY (treaty_id) REFERENCES treaty (id),
    CONSTRAINT fk_entry_claim FOREIGN KEY (claim_id) REFERENCES claim (id)
);
COMMENT ON TABLE account_entry IS '摊回账户分录流水，预提与结案冲销在同一事务内写入';
COMMENT ON COLUMN account_entry.treaty_id IS '所属合约主键';
COMMENT ON COLUMN account_entry.claim_id IS '关联赔案主键';
COMMENT ON COLUMN account_entry.entry_type IS '分录类型：PROVISION=估损预提，PROVISION_ADJUST=估损调整，REVERSAL=结案冲销差额';
COMMENT ON COLUMN account_entry.amount IS '分录金额（元，带符号，正增负减）';
COMMENT ON COLUMN account_entry.balance_after IS '本分录入账后的账户余额（元）';
COMMENT ON COLUMN account_entry.request_id IS '触发本分录的幂等请求号';
COMMENT ON COLUMN account_entry.created_at IS '入账时间（UTC）';

CREATE TABLE IF NOT EXISTS bill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_no VARCHAR(64) NOT NULL,
    dedupe_key VARCHAR(160) NOT NULL,
    treaty_id BIGINT NOT NULL,
    quarter_label VARCHAR(8) NOT NULL,
    bill_type VARCHAR(16) NOT NULL,
    original_bill_no VARCHAR(32) NULL,
    status VARCHAR(32) NOT NULL,
    total_premium_cession DECIMAL(19, 2) NOT NULL,
    total_claim_recovery DECIMAL(19, 2) NOT NULL,
    total_amount DECIMAL(19, 2) NOT NULL,
    confirmed_by_cedant BOOLEAN NOT NULL DEFAULT FALSE,
    confirmed_by_reinsurer BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(512) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sealed_at TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT uk_bill_no UNIQUE (bill_no),
    CONSTRAINT uk_bill_dedupe UNIQUE (dedupe_key),
    CONSTRAINT fk_bill_treaty FOREIGN KEY (treaty_id) REFERENCES treaty (id)
);
COMMENT ON TABLE bill IS '分保账单，按季度生成（UTC 日界）；双方确认后封存，封存为终态';
COMMENT ON COLUMN bill.bill_no IS '账单号，全局唯一';
COMMENT ON COLUMN bill.dedupe_key IS '业务去重键：常规账单=合约|季度，调整单=ADJ|账单号';
COMMENT ON COLUMN bill.treaty_id IS '所属合约主键';
COMMENT ON COLUMN bill.quarter_label IS '账单季度，格式 yyyyQn，按 UTC 日界划分';
COMMENT ON COLUMN bill.bill_type IS '账单类型：REGULAR=常规账单，ADJUSTMENT=差错调整单';
COMMENT ON COLUMN bill.original_bill_no IS '调整单引用的原账单号，常规账单为空';
COMMENT ON COLUMN bill.status IS '状态：PENDING=待确认，DISPUTED=已异议，SEALED=双方确认已封存（终态）';
COMMENT ON COLUMN bill.total_premium_cession IS '保费分保汇总（元，限额前），等于各明细保费分保之和';
COMMENT ON COLUMN bill.total_claim_recovery IS '赔款摊回汇总（元，限额前），等于各明细赔款摊回之和';
COMMENT ON COLUMN bill.total_amount IS '账单汇总金额（元，限额后），必须等于各明细行合计';
COMMENT ON COLUMN bill.confirmed_by_cedant IS '分出公司是否已确认';
COMMENT ON COLUMN bill.confirmed_by_reinsurer IS '再保人是否已确认';
COMMENT ON COLUMN bill.reason IS '调整单差错原因说明，常规账单为空';
COMMENT ON COLUMN bill.created_at IS '生成时间（UTC）';
COMMENT ON COLUMN bill.sealed_at IS '封存时间（UTC），未封存为空';

CREATE TABLE IF NOT EXISTS bill_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_id BIGINT NOT NULL,
    policy_id BIGINT NOT NULL,
    premium_cession DECIMAL(19, 2) NOT NULL,
    claim_recovery DECIMAL(19, 2) NOT NULL,
    item_total DECIMAL(19, 2) NOT NULL,
    retained_amount DECIMAL(19, 2) NOT NULL,
    limit_applied BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_item_bill FOREIGN KEY (bill_id) REFERENCES bill (id),
    CONSTRAINT fk_item_policy FOREIGN KEY (policy_id) REFERENCES policy (id)
);
COMMENT ON TABLE bill_item IS '账单明细（按保单一行），明细合计必须等于账单汇总金额';
COMMENT ON COLUMN bill_item.bill_id IS '所属账单主键';
COMMENT ON COLUMN bill_item.policy_id IS '所属保单主键';
COMMENT ON COLUMN bill_item.premium_cession IS '保费分保（元，限额前）= 分出保费 × 比例';
COMMENT ON COLUMN bill_item.claim_recovery IS '赔款摊回（元，限额前）= 本季已决赔款 × 比例';
COMMENT ON COLUMN bill_item.item_total IS '本明细计入金额（元，限额后）= min(保费分保+赔款摊回, 限额)';
COMMENT ON COLUMN bill_item.retained_amount IS '超出限额留在分出公司的金额（元），恰达限额为 0';
COMMENT ON COLUMN bill_item.limit_applied IS '是否触发责任限额';

CREATE TABLE IF NOT EXISTS bill_dispute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_dispute_bill FOREIGN KEY (bill_id) REFERENCES bill (id)
);
COMMENT ON TABLE bill_dispute IS '账单异议单，一次异议可含多项明细差异';
COMMENT ON COLUMN bill_dispute.bill_id IS '被异议账单主键';
COMMENT ON COLUMN bill_dispute.request_id IS '发起异议的幂等请求号';
COMMENT ON COLUMN bill_dispute.created_at IS '异议时间（UTC）';

CREATE TABLE IF NOT EXISTS bill_dispute_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispute_id BIGINT NOT NULL,
    bill_item_id BIGINT NOT NULL,
    reason VARCHAR(512) NOT NULL,
    CONSTRAINT fk_ditem_dispute FOREIGN KEY (dispute_id) REFERENCES bill_dispute (id),
    CONSTRAINT fk_ditem_item FOREIGN KEY (bill_item_id) REFERENCES bill_item (id)
);
COMMENT ON TABLE bill_dispute_item IS '账单异议明细，逐项列明差异原因';
COMMENT ON COLUMN bill_dispute_item.dispute_id IS '所属异议单主键';
COMMENT ON COLUMN bill_dispute_item.bill_item_id IS '被异议的账单明细主键';
COMMENT ON COLUMN bill_dispute_item.reason IS '差异原因说明';

CREATE TABLE IF NOT EXISTS idempotency_key (
    request_id VARCHAR(64) PRIMARY KEY,
    endpoint VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    http_status INT NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
COMMENT ON TABLE idempotency_key IS '幂等键登记表：同键同参返回首次结果，同键异参拒绝（409）';
COMMENT ON COLUMN idempotency_key.request_id IS '幂等请求号，全局唯一';
COMMENT ON COLUMN idempotency_key.endpoint IS '首次处理该请求号的接口标识';
COMMENT ON COLUMN idempotency_key.fingerprint IS '请求指纹，覆盖 requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN idempotency_key.http_status IS '首次处理结果的 HTTP 状态码';
COMMENT ON COLUMN idempotency_key.response_body IS '首次处理结果的响应报文快照（JSON）';
COMMENT ON COLUMN idempotency_key.created_at IS '登记时间（UTC）';
