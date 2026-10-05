-- 企业年金权益归属与分期支付服务建表脚本（H2 MySQL 兼容模式，金额单位统一为“分”）

CREATE TABLE IF NOT EXISTS member (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_no    VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    hire_date    DATE         NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_member_no ON member (member_no);
COMMENT ON COLUMN member.member_no IS '成员业务编号，唯一';
COMMENT ON COLUMN member.hire_date IS '入职日期，按 UTC 日历日记录';
COMMENT ON COLUMN member.status IS '成员状态：ACTIVE 在职 / RESIGNED 离职 / RETIRED 退休';

CREATE TABLE IF NOT EXISTS contribution (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id        BIGINT      NOT NULL,
    contribution_type VARCHAR(16) NOT NULL,
    amount_cents     BIGINT      NOT NULL,
    contribution_date DATE       NOT NULL,
    period           VARCHAR(16) NOT NULL,
    snapshot_plan_id BIGINT,
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_contribution_member ON contribution (member_id);
COMMENT ON COLUMN contribution.contribution_type IS '缴费类型：PERSONAL 个人缴费 / ENTERPRISE 企业缴费';
COMMENT ON COLUMN contribution.amount_cents IS '缴费金额，单位：分';
COMMENT ON COLUMN contribution.contribution_date IS '缴费归属日期，按 UTC 日历日记录';
COMMENT ON COLUMN contribution.period IS '缴费所属期，如 2026-01';
COMMENT ON COLUMN contribution.snapshot_plan_id IS '冻结该缴费的支付计划 ID；NULL 表示尚未被任何计划快照冻结';

CREATE TABLE IF NOT EXISTS payout_plan (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_no                  VARCHAR(48) NOT NULL,
    request_id               VARCHAR(64) NOT NULL,
    member_id                BIGINT      NOT NULL,
    reason                   VARCHAR(16) NOT NULL,
    installment_type         VARCHAR(16) NOT NULL,
    fingerprint              VARCHAR(64) NOT NULL,
    request_date             DATE        NOT NULL,
    hire_years               INT         NOT NULL,
    vested_ratio_bp          INT         NOT NULL,
    personal_amount_cents    BIGINT      NOT NULL,
    enterprise_amount_cents  BIGINT      NOT NULL,
    vested_enterprise_cents  BIGINT      NOT NULL,
    vested_total_cents       BIGINT      NOT NULL,
    unvested_return_cents    BIGINT      NOT NULL,
    installments_count       INT         NOT NULL,
    status                   VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
    active_member_id         BIGINT,
    enterprise_returned      BOOLEAN     NOT NULL DEFAULT FALSE,
    original_plan_no         VARCHAR(48),
    supplementary            BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at               TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_plan_no ON payout_plan (plan_no);
CREATE UNIQUE INDEX IF NOT EXISTS ux_plan_request_id ON payout_plan (request_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_plan_active_member ON payout_plan (active_member_id);
COMMENT ON COLUMN payout_plan.plan_no IS '支付计划号，全局唯一；补发计划使用新号并引用原计划号';
COMMENT ON COLUMN payout_plan.request_id IS '支付申请幂等键 requestId，全局唯一';
COMMENT ON COLUMN payout_plan.reason IS '支付事由：RESIGN 成员离职 / RETIRE 成员退休';
COMMENT ON COLUMN payout_plan.installment_type IS '分期方式：LUMP_SUM 一次性 / MONTHLY_12 按月12期 / MONTHLY_24 按月24期';
COMMENT ON COLUMN payout_plan.fingerprint IS '申请指纹：SHA-256(requestId 与全部业务字段)，用于同键异参冲突判定';
COMMENT ON COLUMN payout_plan.request_date IS '支付申请日，按 UTC 日界，用于司龄整年计算';
COMMENT ON COLUMN payout_plan.hire_years IS '申请日相对入职日的完整司龄年数，不满一年不计';
COMMENT ON COLUMN payout_plan.vested_ratio_bp IS '企业缴费归属比例，基点表示，5000=50%、8000=80%、10000=100%';
COMMENT ON COLUMN payout_plan.personal_amount_cents IS '快照冻结的个人缴费总额，单位：分，始终 100% 归属';
COMMENT ON COLUMN payout_plan.enterprise_amount_cents IS '快照冻结的企业缴费总额，单位：分';
COMMENT ON COLUMN payout_plan.vested_enterprise_cents IS '按司龄档位归属的企业缴费金额，单位：分，不足 1 分舍去';
COMMENT ON COLUMN payout_plan.vested_total_cents IS '归属权益快照总额=个人+归属企业，单位：分，计划分期总额必须等于该值';
COMMENT ON COLUMN payout_plan.unvested_return_cents IS '结算时划回企业账户的未归属企业缴费金额，单位：分';
COMMENT ON COLUMN payout_plan.status IS '计划状态：ACCEPTED 已受理 / IN_PROGRESS 支付中 / SETTLED 已结清（终态）';
COMMENT ON COLUMN payout_plan.active_member_id IS '进行中计划占用的成员 ID；结清后置 NULL，配合唯一索引保证同一成员并发唯一受理';
COMMENT ON COLUMN payout_plan.enterprise_returned IS '未归属企业缴费是否已划回企业账户；TRUE 后不得重复划转';
COMMENT ON COLUMN payout_plan.original_plan_no IS '补发计划引用的原计划号；NULL 表示原始支付申请';
COMMENT ON COLUMN payout_plan.supplementary IS '是否为结清后补缴历史缴费触发的补发计划';
COMMENT ON COLUMN payout_plan.settled_at IS '计划结清时间（UTC）；未结清为 NULL';

CREATE TABLE IF NOT EXISTS payout_installment (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id     BIGINT      NOT NULL,
    seq_no      INT         NOT NULL,
    due_date    DATE        NOT NULL,
    amount_cents BIGINT     NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts    INT         NOT NULL DEFAULT 0,
    paid_at     TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_installment_seq ON payout_installment (plan_id, seq_no);
COMMENT ON COLUMN payout_installment.seq_no IS '期次序号，从 1 开始';
COMMENT ON COLUMN payout_installment.due_date IS '该期到期日，按月初 UTC 日历日';
COMMENT ON COLUMN payout_installment.amount_cents IS '该期应付金额，单位：分，尾差归入最后一期';
COMMENT ON COLUMN payout_installment.status IS '期次状态：PENDING 待付 / PAID 已出款 / FAILED 出款失败可重试';
COMMENT ON COLUMN payout_installment.attempts IS '出款尝试次数，失败重试累加';
COMMENT ON COLUMN payout_installment.paid_at IS '实际出款时间（UTC），未出款为 NULL';

CREATE TABLE IF NOT EXISTS payout_instruction (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id     VARCHAR(64) NOT NULL,
    plan_id        BIGINT      NOT NULL,
    installment_id BIGINT      NOT NULL,
    seq_no         INT         NOT NULL,
    amount_cents   BIGINT      NOT NULL,
    fingerprint    VARCHAR(64) NOT NULL,
    success        BOOLEAN     NOT NULL,
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_instruction_request_id ON payout_instruction (request_id);
COMMENT ON COLUMN payout_instruction.request_id IS '每期支付指令幂等键 requestId，全局唯一';
COMMENT ON COLUMN payout_instruction.fingerprint IS '指令指纹：SHA-256(requestId 与期次/金额等全部业务字段)';
COMMENT ON COLUMN payout_instruction.success IS '指令首次执行结果：TRUE 出款成功 / FALSE 出款失败';

CREATE TABLE IF NOT EXISTS account_ledger (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id        BIGINT      NOT NULL,
    installment_id BIGINT,
    account        VARCHAR(24) NOT NULL,
    movement_type  VARCHAR(24) NOT NULL,
    amount_cents   BIGINT      NOT NULL,
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ledger_installment_payout
    ON account_ledger (installment_id, movement_type);
COMMENT ON COLUMN account_ledger.installment_id IS '成员出款对应期次 ID（非空，唯一索引保证每期只出款一次）；企业划回为 NULL，其唯一性由 payout_plan.enterprise_returned 条件更新闸门保证';
COMMENT ON COLUMN account_ledger.account IS '记账账户：MEMBER 成员收款账户 / ENTERPRISE 企业账户';
COMMENT ON COLUMN account_ledger.movement_type IS '账务类型：MEMBER_PAYOUT 个人支付出款 / ENTERPRISE_RETURN 未归属企业缴费划回';
COMMENT ON COLUMN account_ledger.amount_cents IS '记账金额，单位：分';

CREATE TABLE IF NOT EXISTS inheritance_claim (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_no                  VARCHAR(48)  NOT NULL,
    request_id                VARCHAR(64)  NOT NULL,
    member_id                 BIGINT       NOT NULL,
    heir_name                 VARCHAR(128) NOT NULL,
    death_date                DATE         NOT NULL,
    apply_date                DATE         NOT NULL,
    fingerprint               VARCHAR(64)  NOT NULL,
    account_balance_cents     BIGINT       NOT NULL,
    unpaid_installment_cents  BIGINT       NOT NULL,
    claim_amount_cents        BIGINT       NOT NULL,
    terminated_plan_id        BIGINT,
    status                    VARCHAR(16)  NOT NULL DEFAULT 'ACCEPTED',
    created_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at                   TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_claim_no ON inheritance_claim (claim_no);
CREATE UNIQUE INDEX IF NOT EXISTS ux_claim_request_id ON inheritance_claim (request_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_claim_member ON inheritance_claim (member_id);
COMMENT ON COLUMN inheritance_claim.claim_no IS '继承申领号，全局唯一';
COMMENT ON COLUMN inheritance_claim.request_id IS '继承申领幂等键 requestId，全局唯一';
COMMENT ON COLUMN inheritance_claim.member_id IS '身故成员 ID；唯一索引保证同一成员并发唯一受理且仅受理一次';
COMMENT ON COLUMN inheritance_claim.heir_name IS '继承人姓名（合成数据）';
COMMENT ON COLUMN inheritance_claim.death_date IS '成员身故日，按 UTC 日历日；申领窗口为身故日起 180 个自然日（含第 180 日）';
COMMENT ON COLUMN inheritance_claim.apply_date IS '申领受理日，按 UTC 日界，取自系统可控时钟';
COMMENT ON COLUMN inheritance_claim.fingerprint IS '申领指纹：SHA-256(requestId 与全部业务字段)，用于同键异参冲突判定';
COMMENT ON COLUMN inheritance_claim.account_balance_cents IS '继承金额构成一：账户余额（未冻结缴费合计），单位：分';
COMMENT ON COLUMN inheritance_claim.unpaid_installment_cents IS '继承金额构成二：被终止计划的未支付分期本金之和，单位：分；无进行中计划为 0';
COMMENT ON COLUMN inheritance_claim.claim_amount_cents IS '继承支付额=账户余额+未支付分期本金，单位：分，必须与两项合计勾稽一致';
COMMENT ON COLUMN inheritance_claim.terminated_plan_id IS '因继承受理被终止的支付计划 ID；NULL 表示受理时无进行中计划';
COMMENT ON COLUMN inheritance_claim.status IS '申领状态：ACCEPTED 已受理 / PAID 继承支付完成（终态）';
COMMENT ON COLUMN inheritance_claim.paid_at IS '继承支付完成时间（UTC）；未完成为 NULL';

CREATE TABLE IF NOT EXISTS inheritance_ledger (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id       BIGINT      NOT NULL,
    entry_type     VARCHAR(24) NOT NULL,
    ref_id         BIGINT      NOT NULL DEFAULT 0,
    amount_cents   BIGINT      NOT NULL,
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_inh_ledger_entry
    ON inheritance_ledger (claim_id, entry_type, ref_id);
COMMENT ON COLUMN inheritance_ledger.claim_id IS '所属继承申领 ID';
COMMENT ON COLUMN inheritance_ledger.entry_type IS '台账类型：ACCOUNT_BALANCE 账户余额构成 / UNPAID_INSTALLMENT 未付分期本金构成 / INHERITANCE_PAYOUT 继承支付出款';
COMMENT ON COLUMN inheritance_ledger.ref_id IS '构成明细引用的期次 ID；0 表示非期次类条目（余额构成与继承出款）';
COMMENT ON COLUMN inheritance_ledger.amount_cents IS '台账金额，单位：分；构成条目合计必须等于继承支付出款条目';

CREATE TABLE IF NOT EXISTS late_contribution_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id       BIGINT       NOT NULL,
    contribution_id BIGINT       NOT NULL,
    claim_id        BIGINT       NOT NULL,
    amount_cents    BIGINT       NOT NULL,
    note            VARCHAR(256) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_late_contribution ON late_contribution_event (contribution_id);
COMMENT ON COLUMN late_contribution_event.member_id IS '迟到缴费所属成员 ID';
COMMENT ON COLUMN late_contribution_event.contribution_id IS '迟到缴费记录 ID，唯一索引保证同一缴费只记一次迟到事件';
COMMENT ON COLUMN late_contribution_event.claim_id IS '已完成继承支付的申领 ID';
COMMENT ON COLUMN late_contribution_event.amount_cents IS '迟到缴费金额，单位：分';
COMMENT ON COLUMN late_contribution_event.note IS '迟到事件说明，提示人工复核';
