-- 融资融券追保与强平阶梯服务：H2（MODE=MySQL）建表脚本
-- 所有金额单位为元，DECIMAL(18,2) 精确到分；时间统一存储 UTC 时刻，展示时区 Asia/Shanghai (UTC+8)。

CREATE TABLE IF NOT EXISTS account (
    account_no    VARCHAR(32)  NOT NULL PRIMARY KEY,
    client_name   VARCHAR(128) NOT NULL,
    base_currency CHAR(3)      NOT NULL,
    created_at    TIMESTAMP    NOT NULL
);
COMMENT ON TABLE account IS '信用账户主表；account_no 为账户业务主键，base_currency 为账户记账币种';

CREATE TABLE IF NOT EXISTS holding (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_no    VARCHAR(32)  NOT NULL,
    symbol        VARCHAR(32)  NOT NULL,
    quantity      BIGINT       NOT NULL,
    last_price    DECIMAL(18,4) NOT NULL,
    market_value  DECIMAL(18,2) NOT NULL,
    snapshot_time TIMESTAMP    NOT NULL
);
COMMENT ON TABLE holding IS '账户持仓与行情合成快照；quantity 单位股，market_value 单位元，为总资产构成项';

CREATE TABLE IF NOT EXISTS liability (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_no         VARCHAR(32)  NOT NULL,
    liability_no       VARCHAR(40)  NOT NULL,
    currency           CHAR(3)      NOT NULL,
    outstanding_amount DECIMAL(18,2) NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    created_at         TIMESTAMP    NOT NULL,
    repaid_at          TIMESTAMP
);
COMMENT ON TABLE liability IS '逐笔负债（融资欠款）；outstanding_amount 未偿金额，单位元；status=OUTSTANDING/REPAID；强平安负债从大到小排序';
CREATE UNIQUE INDEX IF NOT EXISTS ux_liability_account_no ON liability (account_no, liability_no);

CREATE TABLE IF NOT EXISTS account_snapshot (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_no       VARCHAR(32)  NOT NULL,
    total_assets     DECIMAL(18,2) NOT NULL,
    total_liabilities DECIMAL(18,2) NOT NULL,
    confirmed        BOOLEAN      NOT NULL,
    market_time      TIMESTAMP    NOT NULL,
    remark           VARCHAR(255),
    created_at       TIMESTAMP    NOT NULL
);
COMMENT ON TABLE account_snapshot IS '账户资产负债已确认快照；total_assets 总资产、total_liabilities 总负债，单位元；confirmed=1 才参与比例判定';
CREATE INDEX IF NOT EXISTS ix_snapshot_account ON account_snapshot (account_no, id);

CREATE TABLE IF NOT EXISTS margin_notice (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_no    VARCHAR(32)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    trigger_ratio DECIMAL(9,4) NOT NULL,
    deadline      TIMESTAMP    NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    resolved_at   TIMESTAMP,
    open_key      VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status = 'OPEN' THEN account_no ELSE NULL END)
);
COMMENT ON TABLE margin_notice IS '追保通知；status=OPEN/RESOLVED/EXPIRED；deadline 为次一交易日15:00(UTC+8)；open_key 唯一索引保证同一账户未结清周期仅一条 OPEN';
CREATE UNIQUE INDEX IF NOT EXISTS ux_notice_open ON margin_notice (open_key);

CREATE TABLE IF NOT EXISTS margin_deposit (
    request_id   VARCHAR(64)  NOT NULL PRIMARY KEY,
    account_no   VARCHAR(32)  NOT NULL,
    amount       DECIMAL(18,2) NOT NULL,
    currency     CHAR(3)      NOT NULL,
    result       VARCHAR(24)  NOT NULL,
    replayed     BOOLEAN      NOT NULL,
    ratio_after  DECIMAL(9,4),
    notice_resolved BOOLEAN   NOT NULL DEFAULT FALSE,
    job_cancelled   BOOLEAN   NOT NULL DEFAULT FALSE,
    deposited_at TIMESTAMP    NOT NULL
);
COMMENT ON TABLE margin_deposit IS '追加保证金入账记录；request_id 为幂等键，指纹覆盖 request_id+account_no+amount+currency；replayed=1 表示重放首次结果';

CREATE TABLE IF NOT EXISTS liquidation_job (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_no  VARCHAR(32)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    reason      VARCHAR(20)  NOT NULL,
    final_ratio DECIMAL(9,4),
    created_at  TIMESTAMP    NOT NULL,
    executed_at TIMESTAMP,
    pending_key VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN account_no ELSE NULL END)
);
COMMENT ON TABLE liquidation_job IS '强平作业；status=PENDING(待发)/CANCELLED(入账达标撤销)/EXECUTED(已执行终态)；reason=BELOW_110/OVERDUE';
CREATE UNIQUE INDEX IF NOT EXISTS ux_job_pending ON liquidation_job (pending_key);

CREATE TABLE IF NOT EXISTS liquidation_item (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id           BIGINT       NOT NULL,
    account_no       VARCHAR(32)  NOT NULL,
    liability_no     VARCHAR(40)  NOT NULL,
    seq_no           INT          NOT NULL,
    repaid_amount    DECIMAL(18,2) NOT NULL,
    assets_reduced   DECIMAL(18,2) NOT NULL,
    ratio_after_pct  DECIMAL(9,4) NOT NULL,
    created_at       TIMESTAMP    NOT NULL
);
COMMENT ON TABLE liquidation_item IS '强平指令明细，生成即终态；seq_no 为按负债从大到小的平仓顺序；repaid_amount 该笔负债偿还金额，assets_reduced 担保物处置减记金额，单位元';

CREATE TABLE IF NOT EXISTS trade_holiday (
    holiday_date DATE NOT NULL PRIMARY KEY
);
COMMENT ON TABLE trade_holiday IS '交易日历休市日（合成，默认空）；周末默认休市，次一交易日顺延';
