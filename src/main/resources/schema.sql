-- ATM 加钞服务建表脚本（H2 MySQL 兼容模式；金额单位均为人民币元，时间均为 UTC）
CREATE TABLE IF NOT EXISTS atm (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    code                 VARCHAR(32)  NOT NULL,
    name                 VARCHAR(64)  NOT NULL,
    capacity             BIGINT       NOT NULL, -- 钞箱容量（元），单笔加钞上限，恰等容量合法
    daily_avg_withdrawal BIGINT       NOT NULL, -- 历史日均取现（元，合成数据），预测加钞依据
    frozen               TINYINT      NOT NULL DEFAULT 0, -- 是否因押运异常冻结：1 冻结，冻结后该 ATM 不受理新计划
    created_at           TIMESTAMP    NOT NULL,
    CONSTRAINT uk_atm_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS custodian (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(32) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    frozen     TINYINT    NOT NULL DEFAULT 0, -- 是否因长短款逾期被升级冻结：1 冻结，冻结后不得承接新押运任务
    created_at TIMESTAMP  NOT NULL,
    CONSTRAINT uk_custodian_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS replenish_plan (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_no             VARCHAR(40) NOT NULL,
    atm_id              BIGINT      NOT NULL,
    business_date       DATE        NOT NULL, -- 计划归属营业日（UTC 日界）
    predicted_amount    BIGINT      NOT NULL, -- 预测加钞额（元）
    planned_amount      BIGINT      NOT NULL, -- 计划加钞额（元），不得超过钞箱容量
    status              VARCHAR(32) NOT NULL, -- 计划状态，见 PlanStatus
    approved_at         TIMESTAMP   NULL,     -- 审批通过时刻（UTC），押运 4 小时时限起点
    escort_deadline     TIMESTAMP   NULL,     -- 押运时限=审批通过时刻+4h，恰满未完成双人打卡即异常
    escort_completed_at TIMESTAMP   NULL,     -- 第二名押运员打卡完成时刻（UTC）
    counted_amount      BIGINT      NULL,     -- 押运员双人清点数（元），NULL 表示尚未清点
    counted_at          TIMESTAMP   NULL,
    count_custodian_id  BIGINT      NULL,     -- 清点责任人（押运组长）ID，长短款挂账责任人
    device_amount       BIGINT      NULL,     -- 设备入账数（元），NULL 表示设备尚未确认
    device_confirmed_at TIMESTAMP   NULL,
    reconciled_at       TIMESTAMP   NULL,     -- 三方核销完成时刻（UTC）
    discrepancy_id      BIGINT      NULL,     -- 长短款挂账记录 ID，NULL 表示无挂账
    sealed_at           TIMESTAMP   NULL,     -- 日结封存时刻（UTC），非 NULL 即终态
    created_at          TIMESTAMP   NOT NULL,
    CONSTRAINT uk_plan_no UNIQUE (plan_no),
    CONSTRAINT uk_plan_atm_date UNIQUE (atm_id, business_date) -- 同一 ATM 每个 UTC 日唯一计划，兼作并发唯一受理约束
);

CREATE TABLE IF NOT EXISTS escort_checkin (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id      BIGINT    NOT NULL,
    custodian_id BIGINT    NOT NULL,
    seq          INT       NOT NULL, -- 打卡次序：按打卡时间定序 1/2
    checkin_time TIMESTAMP NOT NULL, -- 打卡时刻（UTC）
    created_at   TIMESTAMP NOT NULL,
    CONSTRAINT uk_checkin_plan_custodian UNIQUE (plan_id, custodian_id) -- 同一押运员对同一单只能打卡一次
);

CREATE TABLE IF NOT EXISTS plan_discrepancy (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id                   BIGINT   NOT NULL,
    amount                    BIGINT   NOT NULL, -- 挂账金额（元）=实际清点数-计划数；正为长款，负为短款
    status                    VARCHAR(16) NOT NULL, -- OPEN 挂账中 / SETTLED 已销账 / ESCALATED 逾期升级
    responsible_custodian_id  BIGINT   NOT NULL, -- 责任人（清点组长），逾期升级后冻结其新任务
    occurred_date             DATE     NOT NULL, -- 挂账发生日（UTC 日界）
    due_date                  DATE     NOT NULL, -- 销账截止日=发生日+3 个自然日，恰第 3 日提交仍受理
    settled_at                TIMESTAMP NULL,    -- 销账时刻（UTC），NULL 未销账
    created_at                TIMESTAMP NOT NULL,
    CONSTRAINT uk_discrepancy_plan UNIQUE (plan_id)
);

CREATE TABLE IF NOT EXISTS late_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id    BIGINT      NOT NULL,
    event_type VARCHAR(32) NOT NULL, -- 封存后迟到数据类型，如 COUNT/DEVICE_CONFIRM/SETTLE
    payload    CLOB,                 -- 迟到数据原文（JSON）
    created_at TIMESTAMP   NOT NULL
);

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64) NOT NULL,
    action        VARCHAR(32) NOT NULL, -- 幂等动作类型
    fingerprint   CHAR(64)    NOT NULL, -- 指纹=SHA-256(requestId+动作+全部业务字段)
    plan_id       BIGINT      NULL,
    status_code   INT         NULL,     -- 首次响应 HTTP 状态码；NULL 表示业务仍在执行的占位记录
    response_body CLOB,                 -- 首次响应报文；NULL 表示占位（同键并发请求据此判忙）
    created_at    TIMESTAMP   NOT NULL,
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);

CREATE TABLE IF NOT EXISTS seq_plan_no (
    id        INT PRIMARY KEY,
    seq_value BIGINT NOT NULL -- 计划单号自增序列值
);
MERGE INTO seq_plan_no (id, seq_value) KEY(id) VALUES (1, 0);
