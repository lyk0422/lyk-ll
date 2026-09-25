-- 种子批次田间检验与认证分级服务：H2（MODE=MySQL）建表脚本
-- 数据仅在 JVM 生命周期内保留；时间一律以 UTC 存储。

-- 品种档案（合成主数据，无外部机构依赖）
CREATE TABLE IF NOT EXISTS variety (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    CONSTRAINT uq_variety_code UNIQUE (code)
);
COMMENT ON TABLE variety IS '品种档案（合成数据）';
COMMENT ON COLUMN variety.code IS '品种编码，申报时引用';

-- 地块档案（合成主数据）
CREATE TABLE IF NOT EXISTS field_plot (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    location    VARCHAR(128) NOT NULL,
    CONSTRAINT uq_plot_code UNIQUE (code)
);
COMMENT ON TABLE field_plot IS '地块档案（合成数据）';
COMMENT ON COLUMN field_plot.code IS '地块编码，申报时引用';

-- 种子批次（申报记录）
CREATE TABLE IF NOT EXISTS seed_lot (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    lot_no           VARCHAR(64)  NOT NULL,
    variety_code     VARCHAR(64)  NOT NULL,
    plot_code        VARCHAR(64)  NOT NULL,
    declared_grade   VARCHAR(16)  NOT NULL,
    label_quantity   INT          NOT NULL,
    available_labels INT          NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    declared_at      TIMESTAMP    NOT NULL,
    revoked_at       TIMESTAMP    NULL,
    revoke_reason    VARCHAR(256) NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT uq_seed_lot_no UNIQUE (lot_no)
);
COMMENT ON TABLE seed_lot IS '种子批次：按 申报→田间检验→室内检测→分级发证 流转';
COMMENT ON COLUMN seed_lot.lot_no IS '批次业务编号，唯一';
COMMENT ON COLUMN seed_lot.declared_grade IS '申报等级：ORIGINAL=原种，IMPROVED=良种';
COMMENT ON COLUMN seed_lot.label_quantity IS '申报标签数量（张），发证核减基数';
COMMENT ON COLUMN seed_lot.available_labels IS '当前可用标签数量（张），发证同事务核减';
COMMENT ON COLUMN seed_lot.status IS '状态：PENDING=待定，CERTIFIED=已发证，CANCELLED=注销（终态）';
COMMENT ON COLUMN seed_lot.revoked_at IS '注销时间（UTC），空值表示未注销';

-- 幂等请求记录：指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64)  NOT NULL,
    request_type  VARCHAR(24)  NOT NULL,
    fingerprint   VARCHAR(64)  NOT NULL,
    lot_no        VARCHAR(64)  NULL,
    http_status   INT          NOT NULL,
    response_body CLOB         NULL,
    created_at    TIMESTAMP    NOT NULL,
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);
COMMENT ON TABLE idempotent_request IS '申报与检验提交的 requestId 幂等记录';
COMMENT ON COLUMN idempotent_request.fingerprint IS 'SHA-256(requestId + 全部业务字段)，同键异参判定冲突';
COMMENT ON COLUMN idempotent_request.http_status IS '首次响应 HTTP 状态码，重放时原样返回';

-- 田间检验记录（纯度，百分数，保留三位小数；含首检与复检）
CREATE TABLE IF NOT EXISTS field_inspection (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    lot_no        VARCHAR(64) NOT NULL,
    request_id    VARCHAR(64) NOT NULL,
    purity        DECIMAL(6,3) NOT NULL,
    result        VARCHAR(16) NOT NULL,
    inspect_round INT NOT NULL,
    effective     BOOLEAN NOT NULL,
    inspector     VARCHAR(64) NOT NULL,
    inspected_at  TIMESTAMP NOT NULL,
    created_at    TIMESTAMP NOT NULL,
    CONSTRAINT uq_field_request UNIQUE (request_id),
    CONSTRAINT uq_field_lot_round UNIQUE (lot_no, inspect_round)
);
COMMENT ON TABLE field_inspection IS '田间检验记录（首检保留可查，复检为唯一有效结论）';
COMMENT ON COLUMN field_inspection.purity IS '品种纯度（百分数，%），如 99.900';
COMMENT ON COLUMN field_inspection.result IS '结论：PASS=合格，FAIL=不合格（按申报等级门槛判定）';
COMMENT ON COLUMN field_inspection.inspect_round IS '检验轮次：1=首检，2=复检';
COMMENT ON COLUMN field_inspection.effective IS '是否当前有效结论；同一批次同一类型仅一条为 TRUE';

-- 室内检测记录（发芽率）
CREATE TABLE IF NOT EXISTS lab_inspection (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    lot_no           VARCHAR(64) NOT NULL,
    request_id       VARCHAR(64) NOT NULL,
    germination_rate DECIMAL(6,3) NOT NULL,
    result           VARCHAR(16) NOT NULL,
    inspect_round    INT NOT NULL,
    effective        BOOLEAN NOT NULL,
    inspector        VARCHAR(64) NOT NULL,
    inspected_at     TIMESTAMP NOT NULL,
    created_at       TIMESTAMP NOT NULL,
    CONSTRAINT uq_lab_request UNIQUE (request_id),
    CONSTRAINT uq_lab_lot_round UNIQUE (lot_no, inspect_round)
);
COMMENT ON TABLE lab_inspection IS '室内检测记录（首检保留可查，复检为唯一有效结论）';
COMMENT ON COLUMN lab_inspection.germination_rate IS '发芽率（百分数，%），如 90.000';
COMMENT ON COLUMN lab_inspection.result IS '结论：PASS=合格，FAIL=不合格（按申报等级门槛判定）';
COMMENT ON COLUMN lab_inspection.inspect_round IS '检测轮次：1=首检，2=复检';
COMMENT ON COLUMN lab_inspection.effective IS '是否当前有效结论；同一批次同一类型仅一条为 TRUE';

-- 证书
CREATE TABLE IF NOT EXISTS certificate (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    certificate_no   VARCHAR(64) NOT NULL,
    lot_no           VARCHAR(64) NOT NULL,
    grade            VARCHAR(16) NOT NULL,
    deducted_labels  INT NOT NULL,
    status           VARCHAR(16) NOT NULL,
    issued_at        TIMESTAMP NOT NULL,
    revoked_at       TIMESTAMP NULL,
    CONSTRAINT uq_certificate_no UNIQUE (certificate_no),
    CONSTRAINT uq_certificate_lot UNIQUE (lot_no)
);
COMMENT ON TABLE certificate IS '认证证书；每批次唯一，并发发证仅一次成功';
COMMENT ON COLUMN certificate.grade IS '证书等级：ORIGINAL=原种，IMPROVED=良种';
COMMENT ON COLUMN certificate.deducted_labels IS '发证核减的标签数量（张）';
COMMENT ON COLUMN certificate.status IS 'VALID=有效，REVOKED=已作废（批次注销同步作废）';

-- 证书作废 / 注销留痕
CREATE TABLE IF NOT EXISTS certification_audit (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    lot_no      VARCHAR(64) NOT NULL,
    action      VARCHAR(24) NOT NULL,
    detail      VARCHAR(512) NOT NULL,
    operator    VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP NOT NULL
);
COMMENT ON TABLE certification_audit IS '认证状态变更留痕：批次注销、证书作废等';
COMMENT ON COLUMN certification_audit.action IS '动作：LOT_REVOKED=批次注销，CERT_REVOKED=证书作废';
