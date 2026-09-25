-- 种子批次认证服务建表脚本（H2 MySQL 兼容模式；时间均为 UTC）
CREATE TABLE IF NOT EXISTS seed_lot (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    lot_no           VARCHAR(40)  NOT NULL,
    applicant        VARCHAR(100) NOT NULL COMMENT '申报人/申报主体',
    variety_name     VARCHAR(100) NOT NULL COMMENT '品种名称（合成数据）',
    field_code       VARCHAR(60)  NOT NULL COMMENT '地块编号（合成数据）',
    area_mu          DECIMAL(10,2) NOT NULL COMMENT '申报种植面积，单位：亩',
    available_labels INT          NOT NULL DEFAULT 0 COMMENT '该批次当前可用标签数量，单位：枚，发证时同事务核减',
    status           VARCHAR(20)  NOT NULL COMMENT '批次状态：DECLARED 已申报/PENDING 待定/RECHECKING 复检中/UNQUALIFIED 不合格/QUALIFIED 分级合格待发证/CERTIFIED 已发证/CANCELLED 已注销',
    determined_grade VARCHAR(10)  COMMENT '分级结论：ORIGINAL 原种/BREEDER 良种；空表示尚无合格分级',
    recheck_count    INT          NOT NULL DEFAULT 0 COMMENT '已受理复检次数，每批次至多一次',
    unqualified_at   DATETIME(3)  COMMENT '首检不合格结论出具时间（UTC），复检 15 个自然日窗口起算点',
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL,
    CONSTRAINT uk_seed_lot_lot_no UNIQUE (lot_no)
);

CREATE TABLE IF NOT EXISTS field_inspection (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    lot_id        BIGINT       NOT NULL,
    round         INT          NOT NULL COMMENT '检验轮次：1 首检，2 复检',
    purity        DECIMAL(5,2) NOT NULL COMMENT '品种纯度，单位：%（百分比），如 99.90',
    pass_original TINYINT      NOT NULL COMMENT '是否达到原种门槛 99.9%：1 是 0 否',
    pass_breeder  TINYINT      NOT NULL COMMENT '是否达到良种门槛 99.0%：1 是 0 否',
    retestable    TINYINT      NOT NULL COMMENT '不合格可复检标记：低于良种门槛置 1，否则 0',
    inspector     VARCHAR(100) NOT NULL COMMENT '田间检验员（合成数据）',
    inspected_at  DATETIME(3)  NOT NULL COMMENT '田间检验时间（UTC）',
    created_at    DATETIME(3)  NOT NULL,
    CONSTRAINT uk_field_lot_round UNIQUE (lot_id, round),
    CONSTRAINT fk_field_lot FOREIGN KEY (lot_id) REFERENCES seed_lot (id)
);

CREATE TABLE IF NOT EXISTS lab_inspection (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    lot_id           BIGINT       NOT NULL,
    round            INT          NOT NULL COMMENT '检验轮次：1 首检，2 复检',
    germination_rate DECIMAL(5,2) NOT NULL COMMENT '发芽率，单位：%（百分比），如 90.00',
    pass_original    TINYINT      NOT NULL COMMENT '是否达到原种门槛 90%：1 是 0 否',
    pass_breeder     TINYINT      NOT NULL COMMENT '是否达到良种门槛 85%：1 是 0 否',
    retestable       TINYINT      NOT NULL COMMENT '不合格可复检标记：低于良种门槛置 1，否则 0',
    inspector        VARCHAR(100) NOT NULL COMMENT '室内检测员（合成数据）',
    inspected_at     DATETIME(3)  NOT NULL COMMENT '室内检测时间（UTC）',
    created_at       DATETIME(3)  NOT NULL,
    CONSTRAINT uk_lab_lot_round UNIQUE (lot_id, round),
    CONSTRAINT fk_lab_lot FOREIGN KEY (lot_id) REFERENCES seed_lot (id)
);

CREATE TABLE IF NOT EXISTS recheck_application (
    id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
    lot_id      BIGINT      NOT NULL,
    request_id  VARCHAR(64) NOT NULL COMMENT '复检受理幂等键',
    status      VARCHAR(20) NOT NULL COMMENT '复检申请状态：ACCEPTED 已受理',
    applied_at  DATETIME(3) NOT NULL COMMENT '复检申请提交时间（UTC）',
    deadline_at DATE        NOT NULL COMMENT '复检受理截止日（UTC 日界，含当日，即不合格日+15 日）',
    created_at  DATETIME(3) NOT NULL,
    CONSTRAINT uk_recheck_lot UNIQUE (lot_id),
    CONSTRAINT uk_recheck_request UNIQUE (request_id),
    CONSTRAINT fk_recheck_lot FOREIGN KEY (lot_id) REFERENCES seed_lot (id)
);

CREATE TABLE IF NOT EXISTS certificate (
    id           BIGINT      AUTO_INCREMENT PRIMARY KEY,
    cert_no      VARCHAR(40) NOT NULL COMMENT '证书号，按批次唯一',
    lot_id       BIGINT      NOT NULL,
    grade        VARCHAR(10) NOT NULL COMMENT '证书等级：ORIGINAL 原种/BREEDER 良种',
    label_count  INT         NOT NULL COMMENT '本证核减/配发标签数量，单位：枚',
    status       VARCHAR(10) NOT NULL COMMENT '证书状态：VALID 有效/VOID 已作废',
    issued_at    DATETIME(3) NOT NULL COMMENT '发证时间（UTC）',
    voided_at    DATETIME(3) COMMENT '作废时间（UTC），空表示未作废',
    void_reason  VARCHAR(200) COMMENT '作废原因；批次注销时同步作废并留痕',
    created_at   DATETIME(3) NOT NULL,
    CONSTRAINT uk_certificate_cert_no UNIQUE (cert_no),
    CONSTRAINT uk_certificate_lot UNIQUE (lot_id),
    CONSTRAINT fk_certificate_lot FOREIGN KEY (lot_id) REFERENCES seed_lot (id)
);

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id   VARCHAR(64)  NOT NULL,
    request_type VARCHAR(30)  NOT NULL COMMENT '请求类型：DECLARE/FIELD/LAB/RECHECK',
    fingerprint  VARCHAR(128) NOT NULL COMMENT 'requestId 与全部业务字段的 SHA-256 指纹',
    lot_id       BIGINT       COMMENT '关联批次，申报前为空',
    result_ref   VARCHAR(64)  COMMENT '首次成功结果引用（新建记录主键等）',
    result_snapshot CLOB     COMMENT '首次成功响应快照 JSON，同键同参重放时原样返回',
    created_at   DATETIME(3)  NOT NULL,
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);

CREATE TABLE IF NOT EXISTS lot_event (
    id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
    lot_id     BIGINT       NOT NULL,
    event_type VARCHAR(40)  NOT NULL COMMENT '事件类型：DECLARE/FIELD_SUBMIT/LAB_SUBMIT/CONCLUSION/RECHECK_ACCEPT/CERT_ISSUE/CERT_VOID/CANCEL 等',
    detail     VARCHAR(500) NOT NULL COMMENT '事件明细（留痕）',
    created_at DATETIME(3)  NOT NULL,
    CONSTRAINT fk_event_lot FOREIGN KEY (lot_id) REFERENCES seed_lot (id)
);
