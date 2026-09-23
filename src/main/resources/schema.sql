-- 实验室样本保管库建表脚本（H2 MySQL 兼容模式；CREATE IF NOT EXISTS 便于上下文重复初始化）

CREATE TABLE IF NOT EXISTS specimen (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    specimen_id   VARCHAR(64)  NOT NULL,
    volume_nl     BIGINT       NOT NULL,
    remaining_nl  BIGINT       NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    custodian     VARCHAR(64)  NOT NULL,
    version       INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uk_specimen_id UNIQUE (specimen_id)
);
COMMENT ON COLUMN specimen.volume_nl IS '原始体积，单位纳升（nL，1nL=0.001微升），恒为正';
COMMENT ON COLUMN specimen.remaining_nl IS '尚未分装的剩余体积，单位纳升，不得为负；分装精确守恒';
COMMENT ON COLUMN specimen.status IS '样本状态：IN_STOCK 在库';
COMMENT ON COLUMN specimen.custodian IS '当前保管人；交接时必须与请求的当前持有人一致';
COMMENT ON COLUMN specimen.version IS '乐观锁版本，从0开始，每次变更加1';

CREATE TABLE IF NOT EXISTS aliquot (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    aliquot_id    VARCHAR(64)  NOT NULL,
    specimen_id   VARCHAR(64)  NOT NULL,
    volume_nl     BIGINT       NOT NULL,
    consumed_nl   BIGINT       NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL,
    custodian     VARCHAR(64)  NOT NULL,
    version       INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uk_aliquot_id UNIQUE (aliquot_id)
);
COMMENT ON COLUMN aliquot.volume_nl IS '分装管总体积，单位纳升，恒为正';
COMMENT ON COLUMN aliquot.consumed_nl IS '已被开始检测消耗的体积，单位纳升；隔离后仍保留';
COMMENT ON COLUMN aliquot.status IS '分装管状态：AVAILABLE 可占用 / QUARANTINED 污染隔离';
COMMENT ON COLUMN aliquot.custodian IS '当前保管人；污染隔离后为隔离保管人';
COMMENT ON COLUMN aliquot.version IS '乐观锁版本，从0开始，每次变更加1';

CREATE TABLE IF NOT EXISTS reservation (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id     VARCHAR(64)  NOT NULL,
    aliquot_id     VARCHAR(64)  NOT NULL,
    project_code   VARCHAR(64)  NOT NULL,
    volume_nl      BIGINT       NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    custodian      VARCHAR(64)  NOT NULL,
    version        INTEGER      NOT NULL DEFAULT 0,
    result_payload VARCHAR(4000),
    result_at      TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    CONSTRAINT uk_reservation_request_id UNIQUE (request_id)
);
COMMENT ON COLUMN reservation.request_id IS '检测申请幂等键；同键同参返回首次结果，同键异参返回409';
COMMENT ON COLUMN reservation.volume_nl IS '占用体积，单位纳升，恒为正且不得超过 aliquot 可用量';
COMMENT ON COLUMN reservation.status IS '申请状态：RESERVED 已预留 / CONSUMED 已消耗 / CANCELLED 已取消';
COMMENT ON COLUMN reservation.custodian IS '当前保管人（如检测岗位），交接时校验';
COMMENT ON COLUMN reservation.version IS '乐观锁版本，从0开始，每次变更加1';
COMMENT ON COLUMN reservation.result_payload IS '检测结果载荷；取消后到达的结果拒绝写入，该列保持 NULL';
COMMENT ON COLUMN reservation.result_at IS '结果写入时间（Asia/Shanghai），NULL 表示尚无结果';

CREATE TABLE IF NOT EXISTS idempotent_request (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id     VARCHAR(64)  NOT NULL,
    request_kind   VARCHAR(24)  NOT NULL,
    fingerprint    VARCHAR(128) NOT NULL,
    response_json  VARCHAR(4000) NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT uk_idempotent_request UNIQUE (request_id)
);
COMMENT ON COLUMN idempotent_request.request_kind IS '请求类型：ALIQUOT_SPLIT 分装 / ASSAY_RESERVE 检测申请';
COMMENT ON COLUMN idempotent_request.fingerprint IS '请求参数指纹（SHA-256）；同键指纹不符返回409';
COMMENT ON COLUMN idempotent_request.response_json IS '首次成功调用的响应快照，用于同键同参重放';

CREATE TABLE IF NOT EXISTS custody_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type    VARCHAR(16) NOT NULL,
    entity_id      VARCHAR(64) NOT NULL,
    event_kind     VARCHAR(16) NOT NULL,
    from_custodian VARCHAR(64),
    to_custodian   VARCHAR(64) NOT NULL,
    version_before INTEGER     NOT NULL,
    version_after  INTEGER     NOT NULL,
    occurred_at    TIMESTAMP   NOT NULL,
    note           VARCHAR(255)
);
COMMENT ON COLUMN custody_event.entity_type IS '实体类型：SPECIMEN 样本 / ALIQUOT 分装管 / RESERVATION 检测申请';
COMMENT ON COLUMN custody_event.event_kind IS '事件类型：INTAKE 入库 / CREATE 创建 / TRANSFER 交接 / QUARANTINE 隔离';
COMMENT ON COLUMN custody_event.from_custodian IS '交接前保管人；入库/创建事件为 NULL';
COMMENT ON COLUMN custody_event.occurred_at IS '事件发生时间（Asia/Shanghai），同一实体不得时间倒退';

CREATE INDEX IF NOT EXISTS idx_custody_entity ON custody_event (entity_type, entity_id, id);
CREATE INDEX IF NOT EXISTS idx_aliquot_specimen ON aliquot (specimen_id);
CREATE INDEX IF NOT EXISTS idx_reservation_aliquot ON reservation (aliquot_id);
