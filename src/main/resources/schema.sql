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

-- ===== 船旗国检查报告互认（合成规则，仅用于演示，不访问真实机构）=====

CREATE TABLE IF NOT EXISTS recognition_rule_version (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    version                  INT          NOT NULL,
    max_evidence_valid_days  INT          NOT NULL,
    status                   VARCHAR(16)  NOT NULL,
    note                     VARCHAR(512),
    created_at               TIMESTAMP    NOT NULL,
    CONSTRAINT uq_rec_rule_version UNIQUE (version)
);
COMMENT ON COLUMN recognition_rule_version.version IS '互认规则版本号，从 1 开始；版本一经发布不可变，更新规则另发新版本';
COMMENT ON COLUMN recognition_rule_version.max_evidence_valid_days IS '外部证据最长有效天数（自然日，UTC 瞬时比较）；now>完成时刻+该天数拒绝，恰到截止允许';
COMMENT ON COLUMN recognition_rule_version.status IS 'ACTIVE 可引用 / FROZEN 停用（历史绑定仍冻结引用旧版本）';

CREATE TABLE IF NOT EXISTS recognition_flag (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_version_id  BIGINT       NOT NULL,
    flag_code        VARCHAR(16)  NOT NULL,
    CONSTRAINT fk_rec_flag_rule FOREIGN KEY (rule_version_id) REFERENCES recognition_rule_version (id),
    CONSTRAINT uq_rec_flag_code UNIQUE (rule_version_id, flag_code)
);
COMMENT ON COLUMN recognition_flag.flag_code IS '该规则版本承认的船旗代码（合成代码，如 SYN-PA，不代表真实机构）';

CREATE TABLE IF NOT EXISTS recognition_item_mapping (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_version_id  BIGINT       NOT NULL,
    flag_item_code   VARCHAR(48)  NOT NULL,
    survey_type      VARCHAR(16)  NOT NULL,
    class_item_code  VARCHAR(48)  NOT NULL,
    CONSTRAINT fk_rec_map_rule FOREIGN KEY (rule_version_id) REFERENCES recognition_rule_version (id),
    CONSTRAINT uq_rec_map_item UNIQUE (rule_version_id, flag_item_code)
);
COMMENT ON COLUMN recognition_item_mapping.flag_item_code IS '船旗国检查项目代码（外部报告逐项结论使用该代码）';
COMMENT ON COLUMN recognition_item_mapping.survey_type IS '映射到的船级检验类型：ANNUAL / INTERMEDIATE / SPECIAL';
COMMENT ON COLUMN recognition_item_mapping.class_item_code IS '映射替代的船级检验项目代码；互认只替代该项目完成证明，不替代其他门禁';

CREATE TABLE IF NOT EXISTS external_report (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_no         VARCHAR(64)  NOT NULL,
    revision_no       INT          NOT NULL,
    ship_imo          VARCHAR(32)  NOT NULL,
    flag_code         VARCHAR(16)  NOT NULL,
    completed_at      TIMESTAMP    NOT NULL,
    signature_marker  VARCHAR(64)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    superseded_by     BIGINT,
    request_id        VARCHAR(128) NOT NULL,
    fingerprint       VARCHAR(128) NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    CONSTRAINT fk_ext_report_supersede FOREIGN KEY (superseded_by) REFERENCES external_report (id),
    CONSTRAINT uq_ext_report_revision UNIQUE (report_no, revision_no)
);
COMMENT ON COLUMN external_report.report_no IS '外部检查报告编号，全局唯一；同编号更正生成新 revision_no 修订行';
COMMENT ON COLUMN external_report.revision_no IS '报告修订号，从 1 开始；成功更正 +1，同参重复导入不新增';
COMMENT ON COLUMN external_report.ship_imo IS '被检查船舶 IMO；绑定互认时必须与本船一致，跨船证据拒绝';
COMMENT ON COLUMN external_report.completed_at IS '检查完成时刻（UTC 精确瞬时）；证据有效期由该时刻加规则天数判定';
COMMENT ON COLUMN external_report.signature_marker IS '签认标记；只接受合成标记 SYNTH-FLAG-RECOGNITION/v1，不访问真实机构';
COMMENT ON COLUMN external_report.status IS 'CURRENT 当前最新修订 / SUPERSEDED 已被新修订替代（历史行不可变）';

CREATE TABLE IF NOT EXISTS external_report_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id   BIGINT       NOT NULL,
    item_code   VARCHAR(48)  NOT NULL,
    result      VARCHAR(16)  NOT NULL,
    note        VARCHAR(512),
    CONSTRAINT fk_ext_item_report FOREIGN KEY (report_id) REFERENCES external_report (id),
    CONSTRAINT uq_ext_item UNIQUE (report_id, item_code)
);
COMMENT ON COLUMN external_report_item.result IS '逐项结论：PASS 合格 / FAIL 不合格 / MAJOR_DEFECT 重大缺陷（不得当合格项目互认）';

CREATE TABLE IF NOT EXISTS recognition_binding (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id          VARCHAR(128) NOT NULL,
    survey_id           BIGINT       NOT NULL,
    report_id           BIGINT       NOT NULL,
    report_no           VARCHAR(64)  NOT NULL,
    revision_no         INT          NOT NULL,
    rule_version        INT          NOT NULL,
    flag_item_code      VARCHAR(48)  NOT NULL,
    survey_type         VARCHAR(16)  NOT NULL,
    class_item_code     VARCHAR(48)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    invalidated_reason  VARCHAR(64),
    created_at          TIMESTAMP    NOT NULL,
    invalidated_at      TIMESTAMP,
    fingerprint         VARCHAR(128) NOT NULL,
    active_flag         VARCHAR(1),
    CONSTRAINT fk_rec_binding_survey FOREIGN KEY (survey_id) REFERENCES survey (id),
    CONSTRAINT fk_rec_binding_report FOREIGN KEY (report_id) REFERENCES external_report (id)
);
COMMENT ON COLUMN recognition_binding.rule_version IS '绑定时冻结的互认规则版本；后续规则升级不改变本绑定';
COMMENT ON COLUMN recognition_binding.revision_no IS '绑定时冻结的报告修订号；报告更正后旧绑定原子失效，不得以旧标志发证';
COMMENT ON COLUMN recognition_binding.status IS 'ACTIVE 有效 / INVALIDATED 已失效（报告更正等）';
COMMENT ON COLUMN recognition_binding.invalidated_reason IS '失效原因代码，如 REPORT_REVISED';
COMMENT ON COLUMN recognition_binding.active_flag IS 'ACTIVE 为 Y，其余 NULL；唯一索引保证每个检验的每个船级项目至多一个有效绑定';
CREATE UNIQUE INDEX IF NOT EXISTS uq_rec_binding_active
    ON recognition_binding (survey_id, class_item_code, active_flag);

CREATE TABLE IF NOT EXISTS recognition_risk (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_no         VARCHAR(64)  NOT NULL,
    old_revision_no   INT          NOT NULL,
    new_revision_no   INT          NOT NULL,
    survey_id         BIGINT,
    reason            VARCHAR(64)  NOT NULL,
    created_at        TIMESTAMP    NOT NULL
);
COMMENT ON TABLE recognition_risk IS '互认风险记录：报告更正涉已签发历史证据时，历史不改，另记风险';
COMMENT ON COLUMN recognition_risk.survey_id IS '受影响的已签发检验（可能因清理历史数据而为空）';
