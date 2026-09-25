-- 船级检验服务 H2 建表脚本（MODE=MySQL；本地与测试内存库自动执行）。
-- 日期列为 UTC 日界的 DATE；时刻列为 UTC TIMESTAMP。

CREATE TABLE IF NOT EXISTS ship (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    imo                  VARCHAR(32)  NOT NULL,
    name                 VARCHAR(128) NOT NULL,
    annual_anniversary   DATE         NOT NULL,
    delivery_date        DATE         NOT NULL,
    created_at           TIMESTAMP    NOT NULL,
    CONSTRAINT uq_ship_imo UNIQUE (imo)
);
COMMENT ON COLUMN ship.annual_anniversary IS '年度检验周年基准日（UTC），窗口按每年该日复算';
COMMENT ON COLUMN ship.delivery_date IS '交付/换证基准日（UTC），中间、特别检验周期据此复算';

CREATE TABLE IF NOT EXISTS survey (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64) NOT NULL,
    ship_id         BIGINT      NOT NULL,
    type            VARCHAR(16) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    cycle_anchor    DATE        NOT NULL,
    window_start    DATE        NOT NULL,
    window_end      DATE        NOT NULL,
    extension_until DATE,
    applied_at      TIMESTAMP   NOT NULL,
    boarded_at      TIMESTAMP,
    certified_at    TIMESTAMP,
    fingerprint     VARCHAR(128) NOT NULL,
    active_flag     VARCHAR(1),
    CONSTRAINT fk_survey_ship FOREIGN KEY (ship_id) REFERENCES ship (id),
    CONSTRAINT uq_survey_request UNIQUE (request_id)
);
COMMENT ON COLUMN survey.status IS 'APPLIED 进行中 / BOARDED 登轮完成 / CERTIFIED 已发证终态';
COMMENT ON COLUMN survey.active_flag IS '进行中为 Y，发证后置 NULL；配合唯一索引保证同船同类型同时刻仅一个进行中检验';
CREATE UNIQUE INDEX IF NOT EXISTS uq_survey_active ON survey (ship_id, type, active_flag);

CREATE TABLE IF NOT EXISTS defect (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    survey_id     BIGINT       NOT NULL,
    severity      VARCHAR(16)  NOT NULL,
    description   VARCHAR(512) NOT NULL,
    found_date    DATE         NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    deadline      DATE,
    submitted_at  DATE,
    reviewed_at   TIMESTAMP,
    note          VARCHAR(512),
    request_id    VARCHAR(64),
    fingerprint   VARCHAR(128),
    CONSTRAINT fk_defect_survey FOREIGN KEY (survey_id) REFERENCES survey (id)
);
COMMENT ON COLUMN defect.severity IS 'MAJOR 重大缺陷（发证前必须整改） / MINOR 一般缺陷（限 30 日复查，含第 30 日）';
COMMENT ON COLUMN defect.status IS 'OPEN 待整改 / RECTIFIED 已受理 / REJECTED_LATE 逾期不予受理';
COMMENT ON COLUMN defect.deadline IS '复查截止日（UTC，含当日）；重大缺陷发证前必须完成，无固定天数';

CREATE TABLE IF NOT EXISTS certificate (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    ship_id       BIGINT      NOT NULL,
    type          VARCHAR(16) NOT NULL,
    version       INT         NOT NULL,
    status        VARCHAR(16) NOT NULL,
    valid_from    DATE        NOT NULL,
    valid_until   DATE,
    survey_id     BIGINT,
    superseded_by BIGINT,
    created_at    TIMESTAMP   NOT NULL,
    valid_flag    VARCHAR(1),
    CONSTRAINT fk_cert_ship FOREIGN KEY (ship_id) REFERENCES ship (id),
    CONSTRAINT fk_cert_survey FOREIGN KEY (survey_id) REFERENCES survey (id)
);
COMMENT ON COLUMN certificate.status IS 'VALID 有效 / SUPERSEDED 被新证替代 / EXPIRED 缺陷逾期失效';
COMMENT ON COLUMN certificate.valid_until IS '有效期至（UTC，含当日）；展期批准与本字段更新在同一事务内完成';
COMMENT ON COLUMN certificate.valid_flag IS 'VALID 为 Y，其余状态 NULL；唯一索引保证按船+检验类型仅一张有效证书';
CREATE UNIQUE INDEX IF NOT EXISTS uq_certificate_valid ON certificate (ship_id, type, valid_flag);

CREATE TABLE IF NOT EXISTS extension_request (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id     VARCHAR(64) NOT NULL,
    survey_id      BIGINT      NOT NULL,
    certificate_id BIGINT,
    months         INT         NOT NULL,
    status         VARCHAR(16) NOT NULL,
    old_valid_until DATE,
    new_valid_until DATE,
    applied_at     TIMESTAMP   NOT NULL,
    decided_at     TIMESTAMP,
    note           VARCHAR(512),
    fingerprint    VARCHAR(128) NOT NULL,
    CONSTRAINT fk_ext_survey FOREIGN KEY (survey_id) REFERENCES survey (id),
    CONSTRAINT uq_ext_request UNIQUE (request_id)
);
COMMENT ON COLUMN extension_request.status IS 'PENDING 待批 / APPROVED 已批准（与证书有效期同事务更新） / REJECTED 驳回';
CREATE UNIQUE INDEX IF NOT EXISTS uq_ext_per_survey ON extension_request (survey_id);

CREATE TABLE IF NOT EXISTS idempotent_record (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id    VARCHAR(128) NOT NULL,
    fingerprint   VARCHAR(128) NOT NULL,
    http_status   INT          NOT NULL,
    response_body CLOB         NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uq_idem_request UNIQUE (request_id)
);
COMMENT ON COLUMN idempotent_record.fingerprint IS '业务指纹：覆盖 requestId 与全部业务字段（含操作类型）';

CREATE TABLE IF NOT EXISTS survey_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    survey_id   BIGINT       NOT NULL,
    event_type  VARCHAR(32)  NOT NULL,
    payload     CLOB,
    occurred_at TIMESTAMP    NOT NULL,
    CONSTRAINT fk_event_survey FOREIGN KEY (survey_id) REFERENCES survey (id)
);
COMMENT ON TABLE survey_event IS '检验过程事件；签发终态后迟到的检验记录仅登记 LATE_BOARD_RECORD';

CREATE TABLE IF NOT EXISTS window_ledger (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    ship_id         BIGINT      NOT NULL,
    type            VARCHAR(16) NOT NULL,
    cycle_anchor    DATE        NOT NULL,
    window_start    DATE        NOT NULL,
    window_end      DATE        NOT NULL,
    extension_until DATE,
    survey_id       BIGINT,
    computed_at     TIMESTAMP   NOT NULL,
    CONSTRAINT fk_ledger_ship FOREIGN KEY (ship_id) REFERENCES ship (id)
);
COMMENT ON TABLE window_ledger IS '窗口台账：可由船舶基准日、检验与展期记录整体复算重建';
