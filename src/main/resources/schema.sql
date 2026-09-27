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

-- 船旗国检查证据限定范围互认（合成规则，仅 JVM 内保留，不用于生产）。

CREATE TABLE IF NOT EXISTS flag_rule (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    version               INT         NOT NULL,
    flag_code             VARCHAR(8)  NOT NULL,
    max_evidence_age_days INT         NOT NULL,
    created_at            TIMESTAMP   NOT NULL,
    CONSTRAINT uq_flag_rule_version UNIQUE (version)
);
COMMENT ON TABLE flag_rule IS '合成互认规则版本：船旗代码 + 项目映射 + 最长证据有效天数；版本从 1 开始';
COMMENT ON COLUMN flag_rule.flag_code IS '船旗代码（合成，如 SYN-FLAG），不对应真实机构';
COMMENT ON COLUMN flag_rule.max_evidence_age_days IS '证据最长有效天数：互认申请时刻不得晚于完成时刻+该天数（恰到截止允许，UTC）';

CREATE TABLE IF NOT EXISTS flag_rule_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id     BIGINT      NOT NULL,
    item_code   VARCHAR(32) NOT NULL,
    survey_type VARCHAR(16) NOT NULL,
    CONSTRAINT fk_rule_item_rule FOREIGN KEY (rule_id) REFERENCES flag_rule (id),
    CONSTRAINT uq_rule_item UNIQUE (rule_id, item_code)
);
COMMENT ON TABLE flag_rule_item IS '规则版本内的船旗检查项目到船级检验类型映射；未映射项目申请互认拒绝';
COMMENT ON COLUMN flag_rule_item.survey_type IS '映射目标船级检验类型：ANNUAL / INTERMEDIATE / SPECIAL';

CREATE TABLE IF NOT EXISTS flag_report (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_no        VARCHAR(64) NOT NULL,
    ship_imo         VARCHAR(32) NOT NULL,
    flag_code        VARCHAR(8)  NOT NULL,
    current_revision INT         NOT NULL,
    created_at       TIMESTAMP   NOT NULL,
    CONSTRAINT uq_flag_report_no UNIQUE (report_no)
);
COMMENT ON TABLE flag_report IS '外部船旗国检查报告头；报告编号全局唯一，更正只追加新修订';
COMMENT ON COLUMN flag_report.current_revision IS '当前修订号，从 1 开始；更正成功加 1（携带 expectedVersion 乐观并发控制）';

CREATE TABLE IF NOT EXISTS flag_report_revision (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id    BIGINT       NOT NULL,
    revision     INT          NOT NULL,
    completed_at TIMESTAMP    NOT NULL,
    attestation  VARCHAR(64)  NOT NULL,
    request_id   VARCHAR(64)  NOT NULL,
    fingerprint  VARCHAR(128) NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    CONSTRAINT fk_rev_report FOREIGN KEY (report_id) REFERENCES flag_report (id),
    CONSTRAINT uq_report_revision UNIQUE (report_id, revision),
    CONSTRAINT uq_revision_request UNIQUE (request_id)
);
COMMENT ON TABLE flag_report_revision IS '报告修订：每次导入/更正生成一版，逐项结论挂在修订上，历史修订不可改';
COMMENT ON COLUMN flag_report_revision.completed_at IS '检查完成时刻（UTC，精确时刻）；证据有效期按该时刻+规则天数计算';
COMMENT ON COLUMN flag_report_revision.attestation IS '合成签认标记，只接受固定合成值，不访问真实机构';

CREATE TABLE IF NOT EXISTS flag_report_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    revision_id BIGINT      NOT NULL,
    item_code   VARCHAR(32) NOT NULL,
    conclusion  VARCHAR(16) NOT NULL,
    CONSTRAINT fk_item_revision FOREIGN KEY (revision_id) REFERENCES flag_report_revision (id),
    CONSTRAINT uq_revision_item UNIQUE (revision_id, item_code)
);
COMMENT ON TABLE flag_report_item IS '报告修订的逐项结论';
COMMENT ON COLUMN flag_report_item.conclusion IS 'PASS 合格 / FAIL 不合格 / MAJOR_DEFECT 重大缺陷（不得当合格项目）';

CREATE TABLE IF NOT EXISTS flag_recognition (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id     VARCHAR(64)  NOT NULL,
    survey_id      BIGINT       NOT NULL,
    report_id      BIGINT       NOT NULL,
    revision       INT          NOT NULL,
    rule_version   INT          NOT NULL,
    item_code      VARCHAR(32)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    active_flag    VARCHAR(1),
    created_at     TIMESTAMP    NOT NULL,
    invalidated_at TIMESTAMP,
    fingerprint    VARCHAR(128) NOT NULL,
    CONSTRAINT fk_rec_survey FOREIGN KEY (survey_id) REFERENCES survey (id),
    CONSTRAINT fk_rec_report FOREIGN KEY (report_id) REFERENCES flag_report (id),
    CONSTRAINT uq_rec_request UNIQUE (request_id)
);
COMMENT ON TABLE flag_recognition IS '互认绑定：把报告某项目结论绑定到未签发检验，冻结报告修订与规则版本';
COMMENT ON COLUMN flag_recognition.revision IS '绑定时冻结的报告修订号；报告更正后对未签发检验原子失效';
COMMENT ON COLUMN flag_recognition.status IS 'ACTIVE 有效 / INVALIDATED 已失效（更正触发，仅未签发检验）';
COMMENT ON COLUMN flag_recognition.active_flag IS 'ACTIVE 为 Y，失效置 NULL；唯一索引保证每个检验每个项目只一个有效绑定';
CREATE UNIQUE INDEX IF NOT EXISTS uq_recognition_active ON flag_recognition (survey_id, item_code, active_flag);

CREATE TABLE IF NOT EXISTS flag_risk_note (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    recognition_id BIGINT       NOT NULL,
    survey_id      BIGINT       NOT NULL,
    report_id      BIGINT       NOT NULL,
    old_revision   INT          NOT NULL,
    new_revision   INT          NOT NULL,
    note           VARCHAR(512) NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT fk_risk_rec FOREIGN KEY (recognition_id) REFERENCES flag_recognition (id)
);
COMMENT ON TABLE flag_risk_note IS '风险记录：报告更正时已签发检验的历史互认不改动，另记风险备查';
