-- 再保分保服务 MySQL 建表脚本（与 schema-h2.sql 结构一致，供对接外部 MySQL 时使用；
-- 本地与自动化测试默认使用 H2 MySQL 兼容模式，不依赖该脚本）。
-- 金额单位：人民币元；比例：DECIMAL(9,6)；时间均为 UTC。

CREATE TABLE IF NOT EXISTS treaty (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    contract_no      VARCHAR(64)  NOT NULL COMMENT '合约号（业务唯一键）',
    cession_rate     DECIMAL(9,6) NOT NULL COMMENT '分保比例，取值 0~1',
    per_policy_limit DECIMAL(18,2) NULL COMMENT '每张保单再保人责任限额（元）；NULL 表示无限额',
    created_at       TIMESTAMP    NOT NULL COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_treaty_contract_no (contract_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='再保比例合约';

CREATE TABLE IF NOT EXISTS policy (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    policy_no      VARCHAR(64)   NOT NULL COMMENT '保单号（业务唯一键）',
    contract_id    BIGINT        NOT NULL COMMENT '所属合约 ID',
    premium        DECIMAL(18,2) NOT NULL COMMENT '分出保费（元）',
    effective_date DATE          NOT NULL COMMENT '保单生效日期（UTC 日界），决定所属账单季度',
    bill_id        BIGINT        NULL COMMENT '已计入的账单 ID；NULL 表示尚未出账',
    created_at     TIMESTAMP     NOT NULL COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_policy_policy_no (policy_no),
    KEY idx_policy_contract (contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分出保单（合成数据）';

CREATE TABLE IF NOT EXISTS claim (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    claim_no         VARCHAR(64)   NOT NULL COMMENT '赔案号（业务唯一键）',
    policy_id        BIGINT        NOT NULL COMMENT '所属保单 ID',
    estimated_amount DECIMAL(18,2) NOT NULL COMMENT '最新估损金额（元）；结案后迟到调整只记事件',
    paid_amount      DECIMAL(18,2) NULL COMMENT '实际赔付金额（元）；未结案时为 NULL',
    status           VARCHAR(16)   NOT NULL COMMENT '赔案状态：OPEN/FINAL_CLOSED',
    loss_date        DATE          NOT NULL COMMENT '出险日期（UTC 日界）',
    closed_at        TIMESTAMP     NULL COMMENT '最终结案时间（UTC）',
    created_at       TIMESTAMP     NOT NULL COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_claim_claim_no (claim_no),
    KEY idx_claim_policy (policy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='赔案（合成数据）';

CREATE TABLE IF NOT EXISTS reinsurance_account (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    contract_id BIGINT        NOT NULL COMMENT '所属合约 ID',
    balance     DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '摊回账户余额（元），不得为负',
    updated_at  TIMESTAMP     NOT NULL COMMENT '最近变动时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_contract (contract_id),
    CONSTRAINT ck_account_balance CHECK (balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合约级摊回账户';

CREATE TABLE IF NOT EXISTS claim_recovery (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    claim_id         BIGINT        NOT NULL COMMENT '赔案 ID',
    policy_id        BIGINT        NOT NULL COMMENT '所属保单 ID（限额按保单累计）',
    contract_id      BIGINT        NOT NULL COMMENT '所属合约 ID',
    provision_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '当前未决预提摊回（元）',
    settled_amount   DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '最终结案实摊金额（元）',
    capped_excess    DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '因责任限额留在分出公司的金额（元）',
    recovery_status  VARCHAR(16)   NOT NULL COMMENT '摊回状态：PROVISIONED/SETTLED',
    updated_at       TIMESTAMP     NOT NULL COMMENT '最近更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_recovery_claim (claim_id),
    KEY idx_recovery_policy (policy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='赔案摊回状态';

CREATE TABLE IF NOT EXISTS account_ledger (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    contract_id   BIGINT        NOT NULL COMMENT '所属合约 ID',
    policy_id     BIGINT        NULL COMMENT '关联保单 ID',
    claim_id      BIGINT        NULL COMMENT '关联赔案 ID',
    bill_id       BIGINT        NULL COMMENT '已计入账单 ID；NULL 表示尚未出账',
    movement_type VARCHAR(24)   NOT NULL COMMENT 'PROVISION/ADJUST/SETTLE',
    gross_amount  DECIMAL(18,2) NOT NULL COMMENT '带符号业务基数（元）：预提为估损，调整为估损差额，结案为(实赔-估损)',
    cession_rate  DECIMAL(9,6)  NOT NULL COMMENT '该笔适用分保比例',
    amount        DECIMAL(18,2) NOT NULL COMMENT '带符号摊回变动额（元）：预提为正、冲销为负',
    balance_after DECIMAL(18,2) NOT NULL COMMENT '变动后余额（元），不得为负',
    event_date    DATE          NOT NULL COMMENT '业务发生日期（UTC 日界）',
    request_id    VARCHAR(64)   NULL COMMENT '触发该流水的幂等键',
    created_at    TIMESTAMP     NOT NULL COMMENT '落账时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_ledger_contract_event (contract_id, event_date),
    KEY idx_ledger_bill (bill_id),
    CONSTRAINT ck_ledger_balance CHECK (balance_after >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='摊回账户流水';

CREATE TABLE IF NOT EXISTS claim_event (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    claim_id    BIGINT        NOT NULL COMMENT '赔案 ID',
    event_type  VARCHAR(32)   NOT NULL COMMENT 'CREATED/ESTIMATE_ADJUSTED/LATE_ESTIMATE_RECORDED/SETTLED',
    from_amount DECIMAL(18,2) NULL COMMENT '变动前金额（元）',
    to_amount   DECIMAL(18,2) NULL COMMENT '变动后金额（元）',
    note        VARCHAR(500)  NULL COMMENT '事件说明',
    created_at  TIMESTAMP     NOT NULL COMMENT '记录时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_event_claim (claim_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='赔案事件流';

CREATE TABLE IF NOT EXISTS bill (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    bill_no             VARCHAR(64)   NOT NULL COMMENT '账单号（业务唯一键）',
    contract_id         BIGINT        NOT NULL COMMENT '所属合约 ID',
    period              VARCHAR(8)    NOT NULL COMMENT '账单季度，格式 yyyy-Qn',
    bill_type           VARCHAR(16)   NOT NULL COMMENT 'QUARTERLY/ADJUSTMENT',
    ref_bill_no         VARCHAR(64)   NULL COMMENT '调整单引用的原账单号',
    status              VARCHAR(16)   NOT NULL COMMENT 'PENDING/DISPUTED/SEALED',
    cedant_confirmed    BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '分出公司是否已确认',
    reinsurer_confirmed BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '再保人是否已确认',
    premium_total       DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '保费分保合计（元）',
    recovery_total      DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '赔款摊回合计（元）',
    net_total           DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '账单净额（元）=保费分保-赔款摊回',
    created_at          TIMESTAMP     NOT NULL COMMENT '生成时间（UTC）',
    sealed_at           TIMESTAMP     NULL COMMENT '封存时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bill_bill_no (bill_no),
    UNIQUE KEY uk_bill_quarter (contract_id, period, bill_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='再保分保账单';

CREATE TABLE IF NOT EXISTS bill_item (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    bill_id      BIGINT        NOT NULL COMMENT '所属账单 ID',
    line_no      INT           NOT NULL COMMENT '行号，从 1 开始',
    item_type    VARCHAR(16)   NOT NULL COMMENT 'PREMIUM/CLAIM_RECOVERY/ADJUSTMENT',
    ref_no       VARCHAR(64)   NOT NULL COMMENT '保单号/赔案号',
    ref_bill_no  VARCHAR(64)   NULL COMMENT '调整项引用的原账单号',
    gross_amount DECIMAL(18,2) NOT NULL COMMENT '计算基数（元）',
    cession_rate DECIMAL(9,6)  NOT NULL COMMENT '该行适用分保比例',
    amount       DECIMAL(18,2) NOT NULL COMMENT '分入/摊回金额（元）',
    remark       VARCHAR(500)  NULL COMMENT '明细说明',
    PRIMARY KEY (id),
    UNIQUE KEY uk_item_line (bill_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单明细';

CREATE TABLE IF NOT EXISTS bill_dispute (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    bill_id         BIGINT        NOT NULL COMMENT '所属账单 ID',
    item_ref        VARCHAR(64)   NOT NULL COMMENT '异议明细行号或关联号',
    expected_amount DECIMAL(18,2) NOT NULL COMMENT '异议方认为正确的金额（元）',
    reason          VARCHAR(500)  NOT NULL COMMENT '差异原因（逐项必填）',
    created_at      TIMESTAMP     NOT NULL COMMENT '异议提出时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_dispute_bill (bill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单异议明细';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id  VARCHAR(64)  NOT NULL COMMENT '幂等键 requestId',
    operation   VARCHAR(40)  NOT NULL COMMENT '操作类型',
    fingerprint CHAR(64)     NOT NULL COMMENT 'SHA-256 指纹',
    http_status INT          NOT NULL COMMENT '首次响应 HTTP 状态码',
    result_ref  VARCHAR(64)  NULL COMMENT '首次结果业务单号',
    result_json TEXT         NULL COMMENT '首次响应体 JSON',
    created_at  TIMESTAMP    NOT NULL COMMENT '首次请求时间（UTC）',
    PRIMARY KEY (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='requestId 幂等记录';
