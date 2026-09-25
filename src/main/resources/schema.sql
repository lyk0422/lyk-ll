-- 企业年金权益归属与分期支付服务建表脚本（H2 MySQL 兼容模式）
-- 金额一律以“分”存储（BIGINT），时间以 UTC 存储。

CREATE TABLE IF NOT EXISTS pension_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    hire_date DATE NOT NULL,
    employment_status VARCHAR(16) NOT NULL,
    created_at_utc TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_member_no UNIQUE (member_no)
);
COMMENT ON TABLE pension_member IS '年金成员表，employment_status: ACTIVE 在职 / RESIGNED 离职 / RETIRED 退休';
COMMENT ON COLUMN pension_member.hire_date IS '入职日期（UTC 日界），司龄计算起点';
COMMENT ON COLUMN pension_member.employment_status IS '在职状态：ACTIVE 在职、RESIGNED 已离职、RETIRED 已退休';

CREATE TABLE IF NOT EXISTS contribution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_no VARCHAR(64) NOT NULL,
    contribution_date DATE NOT NULL,
    source VARCHAR(16) NOT NULL,
    amount_cents BIGINT NOT NULL,
    used_by_plan_no VARCHAR(64),
    created_at_utc TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_contribution_source CHECK (source IN ('EMPLOYER', 'EMPLOYEE')),
    CONSTRAINT ck_contribution_amount CHECK (amount_cents >= 0)
);
COMMENT ON TABLE contribution IS '年金缴费流水，均为合成数据';
COMMENT ON COLUMN contribution.source IS '缴费来源：EMPLOYER 企业缴费 / EMPLOYEE 个人缴费';
COMMENT ON COLUMN contribution.amount_cents IS '缴费金额，单位：分';
COMMENT ON COLUMN contribution.used_by_plan_no IS '已归属进哪个支付计划的快照；NULL 表示尚未被计划受理';

CREATE TABLE IF NOT EXISTS payout_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_no VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    member_no VARCHAR(64) NOT NULL,
    reason VARCHAR(16) NOT NULL,
    installment_type VARCHAR(16) NOT NULL,
    installment_count INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    employee_amount_cents BIGINT NOT NULL,
    employer_vested_cents BIGINT NOT NULL,
    employer_forfeit_cents BIGINT NOT NULL,
    total_amount_cents BIGINT NOT NULL,
    tenure_years INT NOT NULL,
    vesting_rate_bp INT NOT NULL,
    application_date DATE NOT NULL,
    original_plan_no VARCHAR(64),
    settled_at_utc TIMESTAMP,
    created_at_utc TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active_member_key VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN member_no ELSE NULL END),
    CONSTRAINT ux_plan_no UNIQUE (plan_no),
    CONSTRAINT ux_plan_request UNIQUE (request_id),
    CONSTRAINT ux_plan_active_member UNIQUE (active_member_key)
);
COMMENT ON TABLE payout_plan IS '支付计划主表；ACTIVE 进行中 / SETTLED 已结清（终态）';
COMMENT ON COLUMN payout_plan.reason IS '支付事由：RESIGNED 离职 / RETIRED 退休';
COMMENT ON COLUMN payout_plan.installment_type IS '分期方式：LUMP_SUM 一次性 / MONTHLY_12 按月12期 / MONTHLY_24 按月24期';
COMMENT ON COLUMN payout_plan.employee_amount_cents IS '个人缴费归属快照金额，单位：分，始终100%归属';
COMMENT ON COLUMN payout_plan.employer_vested_cents IS '企业缴费按司龄阶梯归属的快照金额，单位：分';
COMMENT ON COLUMN payout_plan.employer_forfeit_cents IS '未归属企业缴费金额，单位：分，结清时划回企业账户';
COMMENT ON COLUMN payout_plan.total_amount_cents IS '向成员支付的计划总额，单位：分，必须等于归属权益快照金额';
COMMENT ON COLUMN payout_plan.tenure_years IS '受理时司龄：入职日至申请日（UTC）的整年数';
COMMENT ON COLUMN payout_plan.vesting_rate_bp IS '企业缴费归属比例，基点（5000=50%、8000=80%、10000=100%）';
COMMENT ON COLUMN payout_plan.application_date IS '支付申请受理日（UTC 日界），司龄与快照冻结时点';
COMMENT ON COLUMN payout_plan.original_plan_no IS '补发计划引用的原计划号；原计划始终不被改动';
COMMENT ON COLUMN payout_plan.active_member_key IS '生成列：仅进行中计划持有成员号，保证同成员唯一进行中计划';

CREATE TABLE IF NOT EXISTS plan_contribution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_no VARCHAR(64) NOT NULL,
    contribution_id BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL,
    amount_cents BIGINT NOT NULL,
    vested_cents BIGINT NOT NULL,
    returned_cents BIGINT NOT NULL,
    CONSTRAINT ux_plan_contribution UNIQUE (plan_no, contribution_id)
);
COMMENT ON TABLE plan_contribution IS '计划受理时冻结的缴费快照明细';
COMMENT ON COLUMN plan_contribution.vested_cents IS '该笔缴费归属给成员的金额，单位：分';
COMMENT ON COLUMN plan_contribution.returned_cents IS '该笔缴费未归属、结清时划回企业的金额，单位：分';

CREATE TABLE IF NOT EXISTS plan_installment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_no VARCHAR(64) NOT NULL,
    seq_no INT NOT NULL,
    due_date DATE NOT NULL,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    paid_at_utc TIMESTAMP,
    paid_request_id VARCHAR(64),
    CONSTRAINT ux_installment_seq UNIQUE (plan_no, seq_no)
);
COMMENT ON TABLE plan_installment IS '计划分期期次表';
COMMENT ON COLUMN plan_installment.due_date IS '到期日：各期按月初（UTC）到期';
COMMENT ON COLUMN plan_installment.amount_cents IS '该期出款金额，单位：分，尾差并入最后一期';
COMMENT ON COLUMN plan_installment.status IS 'PENDING 待支付 / PAID 已出款；每期最多出款一次';

CREATE TABLE IF NOT EXISTS payment_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    plan_no VARCHAR(64) NOT NULL,
    seq_no INT NOT NULL,
    amount_cents BIGINT NOT NULL,
    result VARCHAR(16) NOT NULL,
    detail VARCHAR(255),
    created_at_utc TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_attempt_request UNIQUE (request_id)
);
COMMENT ON TABLE payment_attempt IS '每期支付指令的出款尝试；失败可重试，但仅一条 SUCCESS';
COMMENT ON COLUMN payment_attempt.result IS 'SUCCESS 出款成功 / FAILED 托管行模拟失败';

CREATE TABLE IF NOT EXISTS account_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_no VARCHAR(64) NOT NULL,
    entry_type VARCHAR(24) NOT NULL,
    installment_seq INT,
    amount_cents BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at_utc TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE account_entry IS '账户划转台账：MEMBER_PAYOUT 成员出款 / COMPANY_RETURN 未归属划回企业';
COMMENT ON COLUMN account_entry.amount_cents IS '划转金额，单位：分';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY,
    operation VARCHAR(24) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    response_json CLOB NOT NULL,
    created_at_utc TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE idempotency_record IS '幂等记录：指纹覆盖 requestId 与全部业务字段，同键异参冲突';
