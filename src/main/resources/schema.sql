-- 爆破作业审批与振动监测联动：H2（MODE=MySQL）建表脚本
-- 所有时刻列均以 UTC 写入（TIMESTAMP(6)），允许作业时段判断在应用层按矿区时区换算。

CREATE TABLE mine (
    mine_code              VARCHAR(64)  NOT NULL PRIMARY KEY,
    mine_name              VARCHAR(128) NOT NULL,
    time_zone              VARCHAR(48)  NOT NULL DEFAULT 'Asia/Shanghai',
    frozen                 BOOLEAN      NOT NULL DEFAULT FALSE,
    frozen_at              TIMESTAMP(6),
    frozen_reason          VARCHAR(256),
    unfrozen_at            TIMESTAMP(6),
    unfreeze_reviewer      VARCHAR(64),
    version                BIGINT       NOT NULL DEFAULT 0
);
COMMENT ON TABLE  mine IS '矿区；frozen=TRUE 表示监管冻结，新申请拒收、在途申请驳回、已批准未起爆作业作废';
COMMENT ON COLUMN mine.time_zone IS '矿区所在时区（IANA，如 Asia/Shanghai），允许作业时段 06:00-22:00 按此时区判定';
COMMENT ON COLUMN mine.frozen IS '冻结状态：TRUE 冻结立即生效，FALSE 已由监管复核解冻或从未冻结';
COMMENT ON COLUMN mine.frozen_at IS '最近一次冻结生效时刻（UTC），NULL 表示从未冻结';
COMMENT ON COLUMN mine.unfrozen_at IS '最近一次监管复核解冻时刻（UTC），NULL 表示尚未解冻';
COMMENT ON COLUMN mine.version IS '乐观锁版本号，冻结/解冻行级竞争兜底';

CREATE TABLE blast_zone (
    zone_code                  VARCHAR(64)    NOT NULL PRIMARY KEY,
    mine_code                  VARCHAR(64)    NOT NULL,
    zone_name                  VARCHAR(128)   NOT NULL,
    max_charge_per_delay_kg    DECIMAL(12,3)  NOT NULL
);
COMMENT ON TABLE  blast_zone IS '爆区备案；单段药量超过 max_charge_per_delay_kg（kg）的申请直接拒绝';
COMMENT ON COLUMN blast_zone.max_charge_per_delay_kg IS '该爆区备案单段最大药量，单位 kg';

CREATE TABLE monitoring_point (
    zone_code          VARCHAR(64)    NOT NULL,
    point_code         VARCHAR(64)    NOT NULL,
    point_name         VARCHAR(128)   NOT NULL,
    velocity_limit_mm_s DECIMAL(10,3) NOT NULL,
    PRIMARY KEY (zone_code, point_code)
);
COMMENT ON TABLE  monitoring_point IS '爆区备案监测测点；振动回传覆盖全部测点方视为“回传齐全”';
COMMENT ON COLUMN monitoring_point.velocity_limit_mm_s IS '该测点质点振速阈值，单位 mm/s，超过即触发矿区冻结';

CREATE TABLE blast_application (
    id                     BIGINT         AUTO_INCREMENT PRIMARY KEY,
    request_id             VARCHAR(96)    NOT NULL,
    mine_code              VARCHAR(64)    NOT NULL,
    zone_code              VARCHAR(64)    NOT NULL,
    applicant              VARCHAR(64)    NOT NULL,
    planned_time           TIMESTAMP(6)   NOT NULL,
    charge_per_delay_kg    DECIMAL(12,3)  NOT NULL,
    status                 VARCHAR(16)    NOT NULL,
    detonate_time          TIMESTAMP(6),
    reject_reason          VARCHAR(256),
    created_at             TIMESTAMP(6)   NOT NULL,
    updated_at             TIMESTAMP(6)   NOT NULL,
    approved_zone          VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status = 'APPROVED' THEN zone_code END),
    approved_time          TIMESTAMP(6) GENERATED ALWAYS AS (CASE WHEN status = 'APPROVED' THEN planned_time END),
    CONSTRAINT uq_application_request UNIQUE (request_id),
    CONSTRAINT uq_approved_slot UNIQUE (approved_zone, approved_time)
);
COMMENT ON TABLE blast_application IS '爆破作业申请；状态机 SUBMITTED→APPROVED→FILED→ARCHIVED，旁路 REJECTED/VOIDED';
COMMENT ON COLUMN blast_application.request_id IS '申请幂等键，同键同参返回首次结果，同键异参 409';
COMMENT ON COLUMN blast_application.status IS 'SUBMITTED 在途;APPROVED 已批准未起爆;REJECTED 驳回;FILED 已起爆备案;VOIDED 冻结作废;ARCHIVED 评估封档终态';
COMMENT ON COLUMN blast_application.planned_time IS '计划起爆时刻（UTC），允许时段按矿区时区 06:00（含）至 22:00（不含）判定';
COMMENT ON COLUMN blast_application.charge_per_delay_kg IS '单段药量，单位 kg';
COMMENT ON COLUMN blast_application.detonate_time IS '实际起爆备案时刻（UTC），未备案为 NULL';
COMMENT ON COLUMN blast_application.reject_reason IS '驳回/作废原因，NULL 表示未被驳回或作废';
CREATE INDEX idx_application_mine_status ON blast_application (mine_code, status);

CREATE TABLE idempotent_request (
    request_id      VARCHAR(96)  NOT NULL,
    biz_type        VARCHAR(24)  NOT NULL,
    fingerprint     VARCHAR(64)  NOT NULL,
    http_status     INT          NOT NULL,
    response_json   CLOB         NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (request_id, biz_type)
);
COMMENT ON TABLE idempotent_request IS '幂等记录；指纹覆盖 requestId 与全部业务字段，同键异参返回 409';
COMMENT ON COLUMN idempotent_request.biz_type IS '业务类型：APPLY/FILING/REVIEW/EVALUATION';
COMMENT ON COLUMN idempotent_request.fingerprint IS 'requestId+全部业务字段的 SHA-256 十六进制指纹';

CREATE TABLE vibration_reading (
    id               BIGINT         AUTO_INCREMENT PRIMARY KEY,
    application_id   BIGINT         NOT NULL,
    zone_code        VARCHAR(64)    NOT NULL,
    point_code       VARCHAR(64)    NOT NULL,
    sample_time      TIMESTAMP(6)   NOT NULL,
    velocity_mm_s    DECIMAL(10,3)  NOT NULL,
    over_limit       BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP(6)   NOT NULL,
    CONSTRAINT uq_reading_slot UNIQUE (zone_code, point_code, sample_time)
);
COMMENT ON TABLE vibration_reading IS '振动监测读数；按爆区＋测点＋采样时刻去重';
COMMENT ON COLUMN vibration_reading.application_id IS '读数归属的已备案作业 ID';
COMMENT ON COLUMN vibration_reading.sample_time IS '采样时刻（UTC）';
COMMENT ON COLUMN vibration_reading.velocity_mm_s IS '质点振动速度峰值，单位 mm/s';
COMMENT ON COLUMN vibration_reading.over_limit IS '是否超过该测点备案阈值，TRUE 触发矿区冻结';

CREATE TABLE late_reading_event (
    id               BIGINT         AUTO_INCREMENT PRIMARY KEY,
    application_id   BIGINT         NOT NULL,
    zone_code        VARCHAR(64)    NOT NULL,
    point_code       VARCHAR(64)    NOT NULL,
    sample_time      TIMESTAMP(6)   NOT NULL,
    velocity_mm_s    DECIMAL(10,3)  NOT NULL,
    created_at       TIMESTAMP(6)   NOT NULL,
    CONSTRAINT uq_late_slot UNIQUE (zone_code, point_code, sample_time)
);
COMMENT ON TABLE late_reading_event IS '封档后迟到振动读数事件；只记录，不改变已封档评估结论；按爆区＋测点＋采样时刻去重';

CREATE TABLE freeze_event (
    id                        BIGINT         AUTO_INCREMENT PRIMARY KEY,
    mine_code                 VARCHAR(64)    NOT NULL,
    trigger_application_id    BIGINT,
    zone_code                 VARCHAR(64),
    point_code                VARCHAR(64),
    sample_time               TIMESTAMP(6),
    velocity_mm_s             DECIMAL(10,3),
    threshold_mm_s            DECIMAL(10,3),
    frozen_at                 TIMESTAMP(6)   NOT NULL
);
COMMENT ON TABLE freeze_event IS '冻结触发事件，仅在矿区由未冻结转为冻结时落一条';

CREATE TABLE unfreeze_review (
    id            BIGINT         AUTO_INCREMENT PRIMARY KEY,
    mine_code     VARCHAR(64)    NOT NULL,
    request_id    VARCHAR(96)    NOT NULL,
    reviewer      VARCHAR(64)    NOT NULL,
    confirmed     BOOLEAN        NOT NULL,
    reviewed_at   TIMESTAMP(6)   NOT NULL,
    CONSTRAINT uq_unfreeze_request UNIQUE (request_id)
);
COMMENT ON TABLE unfreeze_review IS '监管复核解冻记录，按 requestId 幂等';

CREATE TABLE effect_evaluation (
    id              BIGINT         AUTO_INCREMENT PRIMARY KEY,
    application_id  BIGINT         NOT NULL,
    request_id      VARCHAR(96)    NOT NULL,
    evaluator       VARCHAR(64)    NOT NULL,
    conclusion      VARCHAR(512)   NOT NULL,
    archived_at     TIMESTAMP(6)   NOT NULL,
    CONSTRAINT uq_evaluation_application UNIQUE (application_id),
    CONSTRAINT uq_evaluation_request UNIQUE (request_id)
);
COMMENT ON TABLE effect_evaluation IS '爆破效果评估；封档为终态，作业维度唯一';
