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

-- 登轮完成的项目完成证明（互认只替代其中映射项目的完成证明，不替代窗口/缺陷/发证门禁）。
CREATE TABLE IF NOT EXISTS survey_item_completion (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    survey_id  BIGINT      NOT NULL,
    item_code  VARCHAR(32) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    CONSTRAINT fk_completion_survey FOREIGN KEY (survey_id) REFERENCES survey (id),
    CONSTRAINT uq_completion_item UNIQUE (survey_id, item_code)
);
COMMENT ON TABLE survey_item_completion IS '登轮时记录的本船检验项目完成证明；发证门禁要求必需项目由本表或有效互认绑定覆盖';
COMMENT ON COLUMN survey_item_completion.item_code IS '合成船级检查项目代码：HULL_STRUCTURE/MACHINERY/SAFETY_EQUIPMENT/RADIO_EQUIPMENT/LOAD_LINE';

-- 合成互认规则版本：船旗代码 + 船级检查项目映射 + 最长证据有效天数。
CREATE TABLE IF NOT EXISTS recognition_rule (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id         VARCHAR(64) NOT NULL,
    rule_version       INT         NOT NULL,
    flag_code          VARCHAR(8)  NOT NULL,
    max_evidence_days  INT         NOT NULL,
    fingerprint        VARCHAR(128) NOT NULL,
    created_at         TIMESTAMP   NOT NULL,
    CONSTRAINT uq_rule_version UNIQUE (rule_version),
    CONSTRAINT uq_rule_request UNIQUE (request_id)
);
COMMENT ON TABLE recognition_rule IS '合成互认规则版本（仅合成数据，不用于生产）；版本全局唯一、创建后不可变';
COMMENT ON COLUMN recognition_rule.flag_code IS '规则适用的船旗代码（合成）';
COMMENT ON COLUMN recognition_rule.max_evidence_days IS '证据最长有效天数：申请互认时刻须不晚于完成时刻+该天数（恰到截止允许）';

CREATE TABLE IF NOT EXISTS recognition_rule_item (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id   BIGINT      NOT NULL,
    item_code VARCHAR(32) NOT NULL,
    CONSTRAINT fk_rule_item_rule FOREIGN KEY (rule_id) REFERENCES recognition_rule (id),
    CONSTRAINT uq_rule_item UNIQUE (rule_id, item_code)
);
COMMENT ON TABLE recognition_rule_item IS '规则版本映射的船级检查项目集合；报告逐项结论须与之对应';

-- 外部船旗国检查报告（证据）；更正生成新修订行，报告编号全局唯一。
CREATE TABLE IF NOT EXISTS inspection_report (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id          VARCHAR(64)  NOT NULL,
    report_no           VARCHAR(64)  NOT NULL,
    ship_imo            VARCHAR(32)  NOT NULL,
    flag_code           VARCHAR(8)   NOT NULL,
    completed_at        TIMESTAMP    NOT NULL,
    attestation         VARCHAR(32)  NOT NULL,
    revision            INT          NOT NULL,
    version             INT          NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    supersedes_id       BIGINT,
    content_fingerprint VARCHAR(128) NOT NULL,
    fingerprint         VARCHAR(128) NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    active_flag         VARCHAR(1),
    CONSTRAINT uq_report_request UNIQUE (request_id),
    CONSTRAINT uq_report_no_revision UNIQUE (report_no, revision)
);
COMMENT ON TABLE inspection_report IS '外部船旗国检查报告证据；仅接受合成签认标记，不访问真实机构';
COMMENT ON COLUMN inspection_report.report_no IS '报告编号：全局唯一（同一编号的所有修订属于同一报告）';
COMMENT ON COLUMN inspection_report.completed_at IS '检查完成时刻（UTC）；证据有效期按该时刻+规则最长有效天数计算';
COMMENT ON COLUMN inspection_report.attestation IS '签认标记：仅接受合成标记 SYNTHETIC-ATTEST';
COMMENT ON COLUMN inspection_report.revision IS '修订号：首次导入为 1，每次更正 +1';
COMMENT ON COLUMN inspection_report.version IS '实体版本：从 1 开始，每次成功更正 +1，更正请求须携带 expectedVersion';
COMMENT ON COLUMN inspection_report.status IS 'ACTIVE 当前有效修订 / SUPERSEDED 被更正修订替代';
COMMENT ON COLUMN inspection_report.content_fingerprint IS '报告业务内容指纹（不含 requestId）：同编号同内容重复导入不新增，异内容冲突';
COMMENT ON COLUMN inspection_report.active_flag IS 'ACTIVE 为 Y，SUPERSEDED 为 NULL；唯一索引保证同一报告编号仅一个有效修订';
CREATE UNIQUE INDEX IF NOT EXISTS uq_report_active ON inspection_report (report_no, active_flag);

CREATE TABLE IF NOT EXISTS inspection_report_item (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id  BIGINT      NOT NULL,
    item_code  VARCHAR(32) NOT NULL,
    conclusion VARCHAR(16) NOT NULL,
    CONSTRAINT fk_report_item_report FOREIGN KEY (report_id) REFERENCES inspection_report (id),
    CONSTRAINT uq_report_item UNIQUE (report_id, item_code)
);
COMMENT ON TABLE inspection_report_item IS '报告逐项结论：PASS 合格 / FAIL 不合格 / MAJOR_DEFECT 重大缺陷（不得当合格项目）';

-- 互认绑定：报告修订 × 未签发检验 × 规则版本 × 项目；每个项目只一个有效绑定。
CREATE TABLE IF NOT EXISTS recognition_binding (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64)  NOT NULL,
    survey_id       BIGINT       NOT NULL,
    report_id       BIGINT       NOT NULL,
    report_no       VARCHAR(64)  NOT NULL,
    report_revision INT          NOT NULL,
    rule_id         BIGINT       NOT NULL,
    rule_version    INT          NOT NULL,
    item_code       VARCHAR(32)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    fingerprint     VARCHAR(128) NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    invalidated_at  TIMESTAMP,
    active_flag     VARCHAR(1),
    CONSTRAINT fk_binding_survey FOREIGN KEY (survey_id) REFERENCES survey (id),
    CONSTRAINT fk_binding_report FOREIGN KEY (report_id) REFERENCES inspection_report (id),
    CONSTRAINT fk_binding_rule FOREIGN KEY (rule_id) REFERENCES recognition_rule (id),
    CONSTRAINT uq_binding_request_item UNIQUE (request_id, item_code)
);
COMMENT ON TABLE recognition_binding IS '互认绑定：冻结申请时的报告修订号与规则版本；只替代映射项目的完成证明';
COMMENT ON COLUMN recognition_binding.status IS 'ACTIVE 有效 / INVALIDATED 报告更正后未签发检验的旧互认失效';
COMMENT ON COLUMN recognition_binding.report_revision IS '绑定时冻结的报告修订号；发证须与当前有效修订一致';
COMMENT ON COLUMN recognition_binding.rule_version IS '绑定时冻结的规则版本';
COMMENT ON COLUMN recognition_binding.active_flag IS 'ACTIVE 为 Y，INVALIDATED 为 NULL；唯一索引保证每检验每项目只一个有效绑定';
CREATE UNIQUE INDEX IF NOT EXISTS uq_binding_active ON recognition_binding (survey_id, item_code, active_flag);

-- 已签发检验的旧互认被更正时不改历史证据，另记风险。
CREATE TABLE IF NOT EXISTS recognition_risk (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    binding_id BIGINT       NOT NULL,
    survey_id  BIGINT       NOT NULL,
    report_no  VARCHAR(64)  NOT NULL,
    note       VARCHAR(512) NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    CONSTRAINT fk_risk_binding FOREIGN KEY (binding_id) REFERENCES recognition_binding (id)
);
COMMENT ON TABLE recognition_risk IS '报告更正波及已签发检验的历史互认时登记的风险记录；历史证据不修改';
