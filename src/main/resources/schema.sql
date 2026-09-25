-- 融资融券追保与强平阶梯服务建表脚本（H2 MySQL 兼容模式）
-- 金额单位统一为“分”（BIGINT），时间均为 Asia/Shanghai（UTC+8）本地时间。

CREATE TABLE IF NOT EXISTS account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '账户主键',
    account_no VARCHAR(64) NOT NULL COMMENT '账户编号（合成数据）',
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY' COMMENT '账户币种',
    total_assets BIGINT NOT NULL DEFAULT 0 COMMENT '最新已确认总资产（含已入账保证金），单位：分',
    total_liabilities BIGINT NOT NULL DEFAULT 0 COMMENT '最新已确认总负债，单位：分',
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '账户状态：NORMAL 正常 / LIQUIDATING 强平中',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC+8）',
    CONSTRAINT uk_account_no UNIQUE (account_no)
) COMMENT='信用账户';

CREATE TABLE IF NOT EXISTS snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '快照主键',
    account_id BIGINT NOT NULL COMMENT '账户ID',
    total_assets BIGINT NOT NULL COMMENT '快照总资产（不含后续入账保证金），单位：分',
    total_liabilities BIGINT NOT NULL COMMENT '快照总负债，单位：分',
    confirmed TINYINT NOT NULL DEFAULT 1 COMMENT '是否已确认：1 已确认（比例判定仅以已确认快照为准）',
    snapshot_at TIMESTAMP NOT NULL COMMENT '快照确认时间（UTC+8）',
    CONSTRAINT fk_snapshot_account FOREIGN KEY (account_id) REFERENCES account (id)
) COMMENT='账户资产负债快照';

CREATE TABLE IF NOT EXISTS liability (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '负债主键',
    account_id BIGINT NOT NULL COMMENT '账户ID',
    snapshot_id BIGINT NOT NULL COMMENT '来源快照ID',
    outstanding BIGINT NOT NULL COMMENT '未偿还负债余额，单位：分',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 未结清 / CLOSED 已结清',
    CONSTRAINT fk_liability_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_liability_snapshot FOREIGN KEY (snapshot_id) REFERENCES snapshot (id)
) COMMENT='融资负债明细（强平按金额从大到小逐笔平仓）';

CREATE TABLE IF NOT EXISTS margin_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '追保通知主键',
    account_id BIGINT NOT NULL COMMENT '账户ID',
    ratio_bp INT NOT NULL COMMENT '触发时维持担保比例，单位：基点（万分之几）',
    trigger_at TIMESTAMP NOT NULL COMMENT '触发时间（UTC+8）',
    deadline TIMESTAMP NOT NULL COMMENT '补足截止时间：通知后次一交易日 15:00（UTC+8）',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 未结清 / RESOLVED 已补足 / LIQUIDATED 已强平',
    active_flag TINYINT NULL COMMENT '未结清周期标记：OPEN 时为 1，结清后为 NULL，用于唯一约束',
    merge_count INT NOT NULL DEFAULT 0 COMMENT '同一未结清周期内重复触发被合并的次数',
    resolved_at TIMESTAMP NULL COMMENT '结清时间（UTC+8），未结清为 NULL',
    CONSTRAINT fk_margin_call_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT uk_margin_call_active UNIQUE (account_id, active_flag)
) COMMENT='追保通知（同一账户同一未结清周期仅一条）';

CREATE TABLE IF NOT EXISTS deposit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '入账主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID（客户端提供）',
    account_id BIGINT NOT NULL COMMENT '账户ID',
    amount BIGINT NOT NULL COMMENT '入账金额，单位：分',
    currency VARCHAR(8) NOT NULL COMMENT '币种',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹 sha256(requestId|accountId|amount|currency)',
    ratio_after_bp INT NULL COMMENT '首次入账后维持担保比例，单位：基点；无负债为 NULL',
    resolved_margin_call_id BIGINT NULL COMMENT '本次入账结清的追保通知ID，未结清为 NULL',
    cancelled_order_ids VARCHAR(255) NOT NULL DEFAULT '' COMMENT '本次入账撤销的待发强平指令ID，逗号分隔',
    created_at TIMESTAMP NOT NULL COMMENT '入账时间（UTC+8）',
    CONSTRAINT uk_deposit_request UNIQUE (request_id),
    CONSTRAINT fk_deposit_account FOREIGN KEY (account_id) REFERENCES account (id)
) COMMENT='追加保证金入账（按 requestId 幂等）';

CREATE TABLE IF NOT EXISTS liquidation_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '强平指令主键',
    account_id BIGINT NOT NULL COMMENT '账户ID',
    margin_call_id BIGINT NULL COMMENT '关联追保通知ID；低于110%直接强平时为 NULL',
    reason VARCHAR(24) NOT NULL COMMENT '触发原因：DIRECT_BELOW_110 低于110%直接强平 / OVERDUE 追保逾期未补足',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待发（可撤销）/ DONE 已执行（终态）/ CANCELLED 已撤销（终态）',
    created_at TIMESTAMP NOT NULL COMMENT '生成时间（UTC+8）',
    closed_at TIMESTAMP NULL COMMENT '执行或撤销时间（UTC+8），未结束为 NULL',
    CONSTRAINT fk_liq_order_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_liq_order_call FOREIGN KEY (margin_call_id) REFERENCES margin_call (id)
) COMMENT='强制平仓指令（执行后为终态不可撤销）';

CREATE TABLE IF NOT EXISTS liquidation_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '平仓明细主键',
    order_id BIGINT NOT NULL COMMENT '强平指令ID',
    liability_id BIGINT NOT NULL COMMENT '被平仓负债ID',
    amount BIGINT NOT NULL COMMENT '本笔平仓偿还金额，单位：分',
    CONSTRAINT fk_liq_item_order FOREIGN KEY (order_id) REFERENCES liquidation_order (id),
    CONSTRAINT fk_liq_item_liability FOREIGN KEY (liability_id) REFERENCES liability (id)
) COMMENT='强平平仓明细（逐笔，最后一笔可为部分平仓）';
