-- ATM 加钞计划与双人押运核销：H2 内存库（MODE=MySQL）建表脚本
-- 约定：金额一律以“分”为单位存储（BIGINT）；时间一律以 epoch 毫秒（UTC）存储（BIGINT）。

CREATE TABLE IF NOT EXISTS atm (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    atm_no               VARCHAR(32)  NOT NULL COMMENT 'ATM 编号，合成数据唯一键',
    name                 VARCHAR(64)  NOT NULL COMMENT 'ATM 名称（合成数据）',
    cassette_capacity    BIGINT       NOT NULL COMMENT '钞箱容量，单位：分；单笔加钞不得超过，恰等合法',
    avg_daily_withdrawal BIGINT       NOT NULL COMMENT '历史 7 日日均取现额，单位：分，用于预测加钞',
    escort_frozen        TINYINT      NOT NULL DEFAULT 0 COMMENT '押运异常冻结标记：1=冻结该 ATM 后续计划编制，0=正常',
    created_at           BIGINT       NOT NULL COMMENT '创建时刻，epoch 毫秒，UTC'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_atm_no ON atm (atm_no);

CREATE TABLE IF NOT EXISTS cash_box (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    box_no         VARCHAR(32) NOT NULL COMMENT '款箱编号，合成数据唯一键',
    atm_id         BIGINT      NOT NULL COMMENT '所属 ATM id',
    capacity       BIGINT      NOT NULL COMMENT '款箱容量，单位：分',
    loaded_amount  BIGINT      NOT NULL DEFAULT 0 COMMENT '当前已装填现金，单位：分',
    status         VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT '状态：AVAILABLE=可用，LOADED=已随计划加钞',
    created_at     BIGINT      NOT NULL COMMENT '创建时刻，epoch 毫秒，UTC'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_box_no ON cash_box (box_no);

CREATE TABLE IF NOT EXISTS guard (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    guard_no   VARCHAR(32) NOT NULL COMMENT '押运员编号，合成数据唯一键',
    name       VARCHAR(64) NOT NULL COMMENT '押运员姓名（合成数据）',
    frozen     TINYINT     NOT NULL DEFAULT 0 COMMENT '责任人冻结标记：1=长短款逾期升级，禁止接新押运打卡任务，0=正常',
    created_at BIGINT      NOT NULL COMMENT '创建时刻，epoch 毫秒，UTC'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_guard_no ON guard (guard_no);

CREATE TABLE IF NOT EXISTS replenish_plan (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_no             VARCHAR(32) NOT NULL COMMENT '加钞计划单号，唯一',
    request_id          VARCHAR(64) NOT NULL COMMENT '编制请求幂等键 requestId',
    atm_id              BIGINT      NOT NULL COMMENT 'ATM id',
    box_id              BIGINT      NOT NULL COMMENT '加钞款箱 id',
    plan_utc_date       CHAR(10)    NOT NULL COMMENT '计划所属 UTC 日界，yyyy-MM-dd；同一 ATM 每日唯一受理',
    predicted_amount    BIGINT      NOT NULL COMMENT '按历史日均取现预测的加钞额，单位：分',
    planned_amount      BIGINT      NOT NULL COMMENT '计划加钞额，单位：分；不得超过钞箱容量',
    status              VARCHAR(32) NOT NULL COMMENT '状态：PREPARED/APPROVED/IN_TRANSIT/ESCORT_DONE/ESCORT_ABNORMAL/COUNTED/RECONCILED/OVER_SHORT/OVER_SHORT_SETTLED/SEALED',
    approved_at         BIGINT      NULL COMMENT '审批通过时刻，epoch 毫秒，UTC；空=未审批',
    escort_deadline     BIGINT      NULL COMMENT '押运时限=审批通过时刻+4 小时，epoch 毫秒，UTC；恰满未完成打卡转异常',
    counted_amount      BIGINT      NULL COMMENT '押运员双人清点数，单位：分；空=未清点',
    counted_at          BIGINT      NULL COMMENT '双人清点完成时刻，epoch 毫秒，UTC',
    count_guard1_id     BIGINT      NULL COMMENT '清点责任人（押运员 1）id',
    count_guard2_id     BIGINT      NULL COMMENT '清点责任人（押运员 2）id',
    device_amount       BIGINT      NULL COMMENT '设备入账数，单位：分；空=设备未确认',
    device_confirmed_at BIGINT      NULL COMMENT '设备确认时刻，epoch 毫秒，UTC',
    reconciled_at       BIGINT      NULL COMMENT '三方一致核销时刻，epoch 毫秒，UTC；空=未核销',
    over_short_id       BIGINT      NULL COMMENT '长短款挂账记录 id；空=无挂账',
    sealed_at           BIGINT      NULL COMMENT '日结封存终态时刻，epoch 毫秒，UTC；空=未封存',
    active_flag          TINYINT    NULL DEFAULT 1 COMMENT '执行中单标记：1=执行中（参与同 ATM 同日唯一受理），NULL=已终态（封存/押运异常），放行同日新单',
    version             INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          BIGINT      NOT NULL COMMENT '创建时刻，epoch 毫秒，UTC',
    updated_at          BIGINT      NOT NULL COMMENT '最近更新时刻，epoch 毫秒，UTC'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_no ON replenish_plan (plan_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_request_id ON replenish_plan (request_id);
-- 并发对同一 ATM 同一 UTC 日编制“执行中单”时由数据库保证唯一受理；
-- 终态（封存/押运异常）active_flag 置 NULL，唯一索引放行多值 NULL，故终态后同日可再编新单。
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_atm_date_active ON replenish_plan (atm_id, plan_utc_date, active_flag);

CREATE TABLE IF NOT EXISTS escort_checkin (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id     BIGINT   NOT NULL COMMENT '加钞计划 id',
    guard_id    BIGINT   NOT NULL COMMENT '打卡押运员 id；同人同计划重复打卡拒绝',
    seq         INT      NOT NULL COMMENT '打卡次序，按打卡时间先后定序：1=首人，2=次人',
    checkin_at  BIGINT   NOT NULL COMMENT '打卡时刻，epoch 毫秒，UTC',
    created_at  BIGINT   NOT NULL COMMENT '落库时刻，epoch 毫秒，UTC'
);
-- 同一计划同一押运员只允许打卡一次（双人打卡、同人拒绝）
CREATE UNIQUE INDEX IF NOT EXISTS uk_checkin_plan_guard ON escort_checkin (plan_id, guard_id);
CREATE INDEX IF NOT EXISTS ix_checkin_plan ON escort_checkin (plan_id);

CREATE TABLE IF NOT EXISTS over_short (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id            BIGINT      NOT NULL COMMENT '关联加钞计划 id，一单一挂账',
    direction          VARCHAR(8)  NOT NULL COMMENT '挂账方向：LONG=长款（实际>计划），SHORT=短款（实际<计划）',
    amount             BIGINT      NOT NULL COMMENT '挂账金额=实际(设备入账数)-计划数，单位：分，带符号：正为长款、负为短款',
    planned_amount     BIGINT      NOT NULL COMMENT '挂账时计划数，单位：分',
    actual_amount      BIGINT      NOT NULL COMMENT '挂账时实际数（设备入账数），单位：分',
    status             VARCHAR(16) NOT NULL COMMENT '状态：OPEN=未销账，SETTLED=已销账，ESCALATED=逾期升级',
    occurred_at        BIGINT      NOT NULL COMMENT '挂账发生（设备确认）时刻，epoch 毫秒，UTC',
    deadline_utc_date  CHAR(10)    NOT NULL COMMENT '销账截止 UTC 日=发生日+3 个自然日，恰第 3 日受理合法',
    settled_at         BIGINT      NULL COMMENT '销账受理时刻，epoch 毫秒，UTC；空=未销账',
    settle_request_id  VARCHAR(64) NULL COMMENT '销账请求幂等键',
    escalated_at       BIGINT      NULL COMMENT '逾期升级时刻，epoch 毫秒，UTC；空=未升级',
    created_at         BIGINT      NOT NULL COMMENT '创建时刻，epoch 毫秒，UTC',
    updated_at         BIGINT      NOT NULL COMMENT '最近更新时刻，epoch 毫秒，UTC'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_overshort_plan ON over_short (plan_id);

CREATE TABLE IF NOT EXISTS plan_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id     BIGINT      NOT NULL COMMENT '关联加钞计划 id',
    event_type  VARCHAR(40) NOT NULL COMMENT '事件类型，如 ESCORT_ABNORMAL/LATE_COUNT/LATE_DEVICE_CONFIRM/LATE_SEAL',
    late        TINYINT     NOT NULL DEFAULT 0 COMMENT '是否封存后迟到数据：1=迟到（仅登记事件，不改状态），0=正常流转事件',
    detail      VARCHAR(500) NOT NULL COMMENT '事件说明',
    event_at    BIGINT      NOT NULL COMMENT '事件发生时刻，epoch 毫秒，UTC',
    created_at  BIGINT      NOT NULL COMMENT '事件落库时刻，epoch 毫秒，UTC'
);
CREATE INDEX IF NOT EXISTS ix_event_plan ON plan_event (plan_id);

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64)  NOT NULL COMMENT '幂等键 requestId',
    endpoint      VARCHAR(128) NOT NULL COMMENT '业务端点名，参与指纹',
    fingerprint   VARCHAR(64)  NOT NULL COMMENT '业务指纹=SHA-256(endpoint + 全部业务字段)，同键异参判定冲突',
    http_status   INT          NOT NULL COMMENT '首次响应 HTTP 状态码，重放原样返回',
    response_body CLOB         NULL COMMENT '首次响应体 JSON，重放原样返回',
    created_at    BIGINT       NOT NULL COMMENT '首次受理时刻，epoch 毫秒，UTC',
    PRIMARY KEY (request_id)
);
