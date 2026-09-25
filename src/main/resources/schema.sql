-- 动物调运检疫出证与疫点封锁联动服务：H2(MODE=MySQL) 建表脚本
-- 全部时刻字段以 UTC 存储；数量单位为 头（猪牛羊）/只（禽）。

CREATE SEQUENCE IF NOT EXISTS cert_no_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS farm (
    farm_id    VARCHAR(64)  PRIMARY KEY,
    farm_name  VARCHAR(128) NOT NULL,
    created_at TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS farm_stock (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    farm_id          VARCHAR(64)  NOT NULL,
    animal_type      VARCHAR(16)  NOT NULL,
    ear_tag_range    VARCHAR(128) NOT NULL,
    total_count      INT          NOT NULL,
    available_count  INT          NOT NULL,
    version          INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_stock_batch UNIQUE (farm_id, animal_type, ear_tag_range)
);

CREATE TABLE IF NOT EXISTS quarantine_request (
    request_id    VARCHAR(64)  PRIMARY KEY,
    origin_farm   VARCHAR(64)  NOT NULL,
    destination   VARCHAR(128) NOT NULL,
    animal_type   VARCHAR(16)  NOT NULL,
    ear_tag_range VARCHAR(128) NOT NULL,
    quantity      INT          NOT NULL,
    status        VARCHAR(24)  NOT NULL,
    fingerprint   VARCHAR(96),
    reject_reason VARCHAR(255),
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS quarantine_certificate (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    certificate_no  VARCHAR(64)  NOT NULL,
    request_id      VARCHAR(64)  NOT NULL,
    farm_id         VARCHAR(64)  NOT NULL,
    animal_type     VARCHAR(16)  NOT NULL,
    ear_tag_range   VARCHAR(128) NOT NULL,
    quantity        INT          NOT NULL,
    issued_at       TIMESTAMP    NOT NULL,
    expires_at      TIMESTAMP    NOT NULL,
    status          VARCHAR(24)  NOT NULL,
    quarantine_flag BOOLEAN      NOT NULL DEFAULT FALSE,
    active_slot     VARCHAR(160),
    void_reason     VARCHAR(255),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uq_cert_no UNIQUE (certificate_no),
    CONSTRAINT uq_cert_request UNIQUE (request_id),
    CONSTRAINT uq_cert_active_slot UNIQUE (active_slot)
);

CREATE TABLE IF NOT EXISTS landing_report (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64)  NOT NULL,
    report_id       VARCHAR(64)  NOT NULL,
    quantity        INT          NOT NULL,
    reported_at     TIMESTAMP    NOT NULL,
    quarantine_flag BOOLEAN      NOT NULL DEFAULT FALSE,
    accepted        BOOLEAN      NOT NULL,
    note            VARCHAR(255),
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uq_landing_report UNIQUE (request_id, report_id)
);

CREATE TABLE IF NOT EXISTS blockade (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    blockade_id  VARCHAR(64)  NOT NULL,
    farm_id      VARCHAR(64)  NOT NULL,
    reason       VARCHAR(255),
    status       VARCHAR(16)  NOT NULL,
    effective_at TIMESTAMP    NOT NULL,
    fingerprint  VARCHAR(96),
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    CONSTRAINT uq_blockade_id UNIQUE (blockade_id)
);
CREATE INDEX IF NOT EXISTS idx_blockade_farm ON blockade (farm_id, status);

CREATE TABLE IF NOT EXISTS idempotent_command (
    command_key  VARCHAR(160) PRIMARY KEY,
    fingerprint  VARCHAR(96)  NOT NULL,
    result_json  CLOB         NOT NULL,
    created_at   TIMESTAMP    NOT NULL
);

COMMENT ON COLUMN farm_stock.total_count IS '初始可售存栏，单位：头/只';
COMMENT ON COLUMN farm_stock.available_count IS '当前可售存栏，单位：头/只；出证核减、封锁作废时返还';
COMMENT ON COLUMN quarantine_request.status IS '单据状态：SUBMITTED已申报/REJECTED封锁驳回/VOID封锁作废/CERTIFIED已出证/IN_TRANSIT在途/EXPIRED证失效/CLOSED已结案';
COMMENT ON COLUMN quarantine_certificate.issued_at IS '出证时刻，UTC';
COMMENT ON COLUMN quarantine_certificate.expires_at IS '有效期终点，UTC；猪牛羊=出证后24小时，禽=48小时，报告时刻不晚于该点有效';
COMMENT ON COLUMN quarantine_certificate.status IS '证状态：VALID有效/VOID封锁作废/EXPIRED超时失效/CLOSED已结案';
COMMENT ON COLUMN quarantine_certificate.quarantine_flag IS '强制隔离观察标记；封锁发布时已在途的证置TRUE，落地报告继承';
COMMENT ON COLUMN quarantine_certificate.active_slot IS '有效证唯一占位=养殖场#耳标段；作废或结案后置NULL，NULL不参与唯一约束';
COMMENT ON COLUMN landing_report.reported_at IS '落地报告时刻，UTC';
COMMENT ON COLUMN landing_report.accepted IS '本批是否被接受并累计；超量整批拒绝、超时与结案后迟到事件记录但置FALSE';
COMMENT ON COLUMN blockade.effective_at IS '封锁生效时刻，UTC；发布即即刻生效';
COMMENT ON COLUMN idempotent_command.command_key IS '幂等键=命令类型:业务键，例如 DECLARE:REQ-1、LANDING:REQ-1:R-1';
COMMENT ON COLUMN idempotent_command.fingerprint IS '业务键与全部业务字段的SHA-256指纹；同键异参返回409';
COMMENT ON COLUMN idempotent_command.result_json IS '首次处理结果JSON快照，同键同参重放直接返回';
