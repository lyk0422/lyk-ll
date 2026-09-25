-- 船级检验服务建表脚本（H2 MODE=MySQL，同时兼容 MySQL 基本语法）
-- 所有日期列均为 UTC 日期（UTC 日界），时间戳为系统时刻。

CREATE TABLE IF NOT EXISTS ship (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    imo              VARCHAR(32)  NOT NULL COMMENT '船舶 IMO 编号（合成数据）',
    name             VARCHAR(128) NOT NULL COMMENT '船名',
    anniversary_date DATE         NOT NULL COMMENT '船级周年日（UTC，月日每年复用）',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ship_imo ON ship (imo);

CREATE TABLE IF NOT EXISTS survey_type (
    code       VARCHAR(16) PRIMARY KEY COMMENT '检验类型：ANNUAL 年度 / INTERMEDIATE 中间 / SPECIAL 特别',
    name       VARCHAR(64) NOT NULL COMMENT '类型名称',
    extendable TINYINT      NOT NULL COMMENT '是否允许展期：1 可展期（仅年度），0 禁止'
);

-- 检验单：active_slot=1 表示进行中，唯一约束保证同船同类型同时刻仅一个进行中检验；
-- 终态后置 NULL（唯一索引允许多个 NULL）。
CREATE TABLE IF NOT EXISTS survey (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    apply_request_id VARCHAR(64)  NOT NULL COMMENT '申请幂等键 requestId',
    ship_id          BIGINT       NOT NULL COMMENT '船舶 ID',
    type_code        VARCHAR(16)  NOT NULL COMMENT '检验类型',
    status           VARCHAR(24)  NOT NULL COMMENT '状态：APPLIED 已申请/BOARDING 已登轮/RECTIFYING 整改中/ISSUED 已发证',
    anniversary_date DATE        NULL COMMENT '本次周期周年日（UTC）',
    window_start     DATE         NULL COMMENT '检验窗口首日（UTC，含当日）',
    window_end       DATE         NULL COMMENT '检验窗口末日（UTC，含当日，含已批准展期）',
    extension_months INT          NOT NULL DEFAULT 0 COMMENT '已批准展期月数（年度检验至多一次、至多 3）',
    applied_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时刻',
    boarded_at       DATE         NULL COMMENT '登轮检验完成日（UTC）',
    issued_at        DATE         NULL COMMENT '发证日（UTC）',
    active_slot      TINYINT      NULL COMMENT '进行中标志：1 进行中，NULL 已终结',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_survey_active ON survey (ship_id, type_code, active_slot);

CREATE TABLE IF NOT EXISTS survey_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    survey_id   BIGINT       NOT NULL COMMENT '检验单 ID',
    event_type  VARCHAR(32)  NOT NULL COMMENT '事件类型：APPLY/BOARD/DEFECT_FOUND/RECTIFY/ISSUE/LATE_RECORD 等',
    event_date  DATE         NULL COMMENT '事件对应 UTC 日期',
    detail      VARCHAR(1024) NULL COMMENT '事件说明',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_event_survey ON survey_event (survey_id, id);

-- 缺陷链：MAJOR 重大未整改禁止发证；GENERAL 一般限 30 日复查（恰第 30 日受理，逾期证书失效）。
CREATE TABLE IF NOT EXISTS defect (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    survey_id            BIGINT      NOT NULL COMMENT '检验单 ID',
    severity             VARCHAR(16) NOT NULL COMMENT '严重程度：MAJOR 重大 / GENERAL 一般',
    description          VARCHAR(256) NOT NULL COMMENT '缺陷描述（合成数据）',
    status               VARCHAR(24) NOT NULL COMMENT 'OPEN 待整改/RECTIFIED 已整改待复查/ACCEPTED 复查受理/EXPIRED 逾期',
    found_date           DATE        NOT NULL COMMENT '发现日（UTC）',
    due_date             DATE        NULL COMMENT '一般缺陷复查期限：发现日+30 日（UTC，含当日）',
    rectified_date       DATE        NULL COMMENT '整改完成日（UTC）',
    recheck_submit_date  DATE        NULL COMMENT '复查提交日（UTC）',
    accepted_date        DATE        NULL COMMENT '复查受理日（UTC）',
    created_at           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_defect_survey ON defect (survey_id, id);

-- 证书：按“船＋检验类型”仅允许一证有效，valid_flag=1 唯一；旧证失效与新证签发同事务。
CREATE TABLE IF NOT EXISTS certificate (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    cert_no        VARCHAR(64)  NOT NULL COMMENT '证书编号（按船+类型+版本生成）',
    ship_id        BIGINT       NOT NULL,
    type_code      VARCHAR(16)  NOT NULL,
    survey_id      BIGINT       NOT NULL COMMENT '来源检验单 ID',
    version        INT          NOT NULL COMMENT '证书版本，从 1 递增',
    status         VARCHAR(16)  NOT NULL COMMENT 'VALID 有效/INVALID 被新版本替代/EXPIRED 复查逾期失效',
    valid_from     DATE         NOT NULL COMMENT '有效期起始（UTC）',
    valid_until    DATE         NOT NULL COMMENT '有效期截止（UTC，展期批准后同事务更新）',
    recheck_due    DATE         NULL COMMENT '一般缺陷复查截止（UTC，空表示无待复查缺陷）',
    valid_flag     TINYINT      NULL COMMENT '有效标志：1 当前有效，NULL 已失效',
    issued_at      DATE         NOT NULL COMMENT '签发日（UTC）',
    invalidated_at DATE         NULL COMMENT '失效日（UTC）',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cert_valid ON certificate (ship_id, type_code, valid_flag);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cert_no ON certificate (cert_no);

CREATE TABLE IF NOT EXISTS extension_application (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(64)  NOT NULL COMMENT '展期申请幂等键',
    approve_request_id VARCHAR(64) NULL COMMENT '展期批准幂等键',
    ship_id          BIGINT       NOT NULL,
    type_code        VARCHAR(16)  NOT NULL,
    certificate_id   BIGINT       NOT NULL COMMENT '被展期证书 ID',
    months           INT          NOT NULL COMMENT '申请展期月数（1-3，恰 3 个月合法）',
    status           VARCHAR(16)  NOT NULL COMMENT 'PENDING 待批准/APPROVED 已批准/REJECTED 已拒绝',
    old_valid_until  DATE         NULL COMMENT '批准前有效期截止（UTC）',
    new_valid_until  DATE         NULL COMMENT '批准后有效期截止（UTC）',
    applied_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at       TIMESTAMP    NULL,
    approved_flag    TINYINT      NULL COMMENT '已批准标志：1 已批准，NULL 未批准；一证书限一次'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_extension_cert ON extension_application (certificate_id, approved_flag);

-- 窗口台账：可由检验单、展期记录复算重建。
CREATE TABLE IF NOT EXISTS annual_window_ledger (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    ship_id         BIGINT      NOT NULL,
    cycle_year      INT         NOT NULL COMMENT '周期年份（周年日所在公历年，UTC）',
    anniversary_date DATE       NOT NULL COMMENT '该周期周年日（UTC）',
    window_start    DATE        NOT NULL COMMENT '窗口首日 周年日-3 月（UTC，含）',
    window_end      DATE        NOT NULL COMMENT '窗口末日 周年日+3 月（UTC，含）',
    extended_end    DATE        NULL COMMENT '展期后末日（UTC，空为未展期）',
    survey_id       BIGINT      NULL COMMENT '命中窗口的检验单 ID',
    boarded_at      DATE        NULL COMMENT '登轮完成日（UTC）',
    state           VARCHAR(24) NOT NULL COMMENT 'EMPTY 无检验/IN_WINDOW 窗口内完成/OUT_OF_WINDOW 窗口外/PENDING 进行中',
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ledger_cycle ON annual_window_ledger (ship_id, cycle_year);

-- 幂等请求：指纹覆盖 requestId 与全部业务字段；同键同参重放首次结果，同键异参冲突。
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id   VARCHAR(64) PRIMARY KEY,
    operation    VARCHAR(32)  NOT NULL COMMENT '操作类型：APPLY_SURVEY/BOARD/RECTIFY/ISSUE/RECHECK/EXTENSION_APPLY/EXTENSION_APPROVE',
    fingerprint  VARCHAR(128) NOT NULL COMMENT '业务字段指纹（SHA-256）',
    http_status  INT          NOT NULL COMMENT '首次响应 HTTP 状态码',
    result_json  CLOB         NOT NULL COMMENT '首次响应体快照',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
