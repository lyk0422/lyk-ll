-- 食品添加剂限量与配方合规审查：H2（MODE=MySQL）建表脚本
-- 时间一律存 UTC；金额/用量单位见表与字段注释

CREATE TABLE IF NOT EXISTS standard_version (
    version      VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '标准版本号，如 V1/V2',
    published_at TIMESTAMP    NOT NULL COMMENT '版本发布时刻（UTC）'
);

CREATE TABLE IF NOT EXISTS food_category (
    category_code VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '食品分类编码',
    name_cn       VARCHAR(64) NOT NULL COMMENT '食品分类中文名'
);

CREATE TABLE IF NOT EXISTS additive_catalog (
    additive_code VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '添加剂编码',
    name_cn       VARCHAR(64) NOT NULL COMMENT '添加剂中文名',
    function_type VARCHAR(32) NOT NULL COMMENT '功能类别，如 PRESERVATIVE 防腐剂、ANTIOXIDANT 抗氧化剂',
    unit          VARCHAR(16) NOT NULL DEFAULT 'g/kg' COMMENT '计量单位，默认 g/kg'
);

CREATE TABLE IF NOT EXISTS additive_limit (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    additive_code  VARCHAR(32)    NOT NULL COMMENT '添加剂编码',
    category_code  VARCHAR(32)    NOT NULL COMMENT '食品分类编码',
    standard_version VARCHAR(32)  NOT NULL COMMENT '适用标准版本',
    max_amount     DECIMAL(18,6)  NOT NULL COMMENT '最大使用量 g/kg，恰等该值合法',
    CONSTRAINT uk_limit UNIQUE (additive_code, category_code, standard_version)
);

CREATE TABLE IF NOT EXISTS compound_ingredient (
    compound_code VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '复合配料编码',
    name_cn       VARCHAR(64) NOT NULL COMMENT '复合配料中文名'
);

CREATE TABLE IF NOT EXISTS compound_ingredient_additive (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    compound_code  VARCHAR(32)    NOT NULL COMMENT '复合配料编码',
    additive_code  VARCHAR(32)    NOT NULL COMMENT '含有的添加剂编码',
    amount         DECIMAL(18,6)  NOT NULL COMMENT '添加剂在复合配料中的含量 g/kg',
    CONSTRAINT uk_compound_additive UNIQUE (compound_code, additive_code)
);

CREATE TABLE IF NOT EXISTS recipe (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id     VARCHAR(64) NOT NULL COMMENT '提交幂等键',
    product_code   VARCHAR(32) NOT NULL COMMENT '产品编码',
    recipe_version VARCHAR(32) NOT NULL COMMENT '配方版本号',
    category_code  VARCHAR(32) NOT NULL COMMENT '产品食品分类编码',
    status         VARCHAR(32) NOT NULL COMMENT 'SUBMITTED/COMPLIANT/REJECTED/APPROVED',
    fingerprint    VARCHAR(64) NOT NULL COMMENT 'requestId 与全部业务字段的 SHA-256 指纹',
    review_detail  CLOB        NULL COMMENT '最近一次合规审查逐项判定明细 JSON',
    reviewed_std   VARCHAR(32) NULL COMMENT '审查所依据的标准版本',
    snapshot_json  CLOB        NULL COMMENT '批准时冻结的配方快照 JSON',
    approved_std   VARCHAR(32) NULL COMMENT '批准时生效标准版本',
    created_at     TIMESTAMP   NOT NULL COMMENT '提交时刻（UTC）',
    reviewed_at    TIMESTAMP   NULL COMMENT '审查时刻（UTC）',
    approved_at    TIMESTAMP   NULL COMMENT '批准时刻（UTC）',
    CONSTRAINT uk_recipe_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS recipe_additive (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipe_id     BIGINT         NOT NULL COMMENT '所属配方 ID',
    additive_code VARCHAR(32)    NOT NULL COMMENT '直接加入的添加剂编码',
    amount        DECIMAL(18,6)  NOT NULL COMMENT '直接加入量 g/kg（以终产品计）',
    seq_no        INT            NOT NULL COMMENT '提交顺序号'
);

CREATE TABLE IF NOT EXISTS recipe_ingredient (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipe_id     BIGINT         NOT NULL COMMENT '所属配方 ID',
    compound_code VARCHAR(32)    NOT NULL COMMENT '复合配料编码',
    proportion    DECIMAL(18,6)  NOT NULL COMMENT '复合配料占终产品质量比例，0~1',
    seq_no        INT            NOT NULL COMMENT '提交顺序号'
);

CREATE TABLE IF NOT EXISTS filing (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(64) NOT NULL COMMENT '备案幂等键',
    product_code     VARCHAR(32) NOT NULL COMMENT '产品编码',
    recipe_id        BIGINT      NOT NULL COMMENT '依据的已批准配方 ID',
    recipe_version   VARCHAR(32) NOT NULL COMMENT '备案配方版本（冻结快照）',
    standard_version VARCHAR(32) NOT NULL COMMENT '批准时标准版本',
    status           VARCHAR(32) NOT NULL COMMENT 'ACTIVE/REVIEW_PENDING/SUSPENDED/CANCELLED',
    snapshot_json    CLOB        NOT NULL COMMENT '备案执行所依据的批准版本快照 JSON',
    filed_at         TIMESTAMP   NOT NULL COMMENT '备案时刻（UTC）',
    review_due_date  DATE        NULL COMMENT '复审截止日（UTC 日界），标准发布日+90 个自然日',
    reviewed_at      TIMESTAMP   NULL COMMENT '复审完成时刻（UTC）',
    reviewed_std     VARCHAR(32) NULL COMMENT '复审所依据的新标准版本',
    suspended_at     TIMESTAMP   NULL COMMENT '暂停时刻（UTC）：逾期/复审不合格/被新版本替代',
    suspend_reason   VARCHAR(255) NULL COMMENT '暂停原因',
    cancelled_at     TIMESTAMP   NULL COMMENT '注销时刻（UTC），注销为终态',
    active_token     VARCHAR(8)  NULL COMMENT '有效备案占位：ACTIVE/REVIEW_PENDING 时为 X，否则 NULL，配合唯一约束保证同一产品仅一个有效版本',
    CONSTRAINT uk_filing_request UNIQUE (request_id),
    CONSTRAINT uk_filing_active UNIQUE (product_code, active_token)
);

CREATE TABLE IF NOT EXISTS production_report (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    filing_id    BIGINT       NOT NULL COMMENT '所属备案 ID',
    batch_no     VARCHAR(64)  NOT NULL COMMENT '生产批号',
    quantity_kg  DECIMAL(18,6) NOT NULL COMMENT '本批产量 kg',
    reported_at  TIMESTAMP    NOT NULL COMMENT '上报时刻（UTC）',
    CONSTRAINT uk_report_batch UNIQUE (filing_id, batch_no)
);

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id  VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等键',
    fingerprint VARCHAR(64) NOT NULL COMMENT '首次请求指纹',
    status_code INT         NOT NULL COMMENT '首次响应 HTTP 状态码',
    response    CLOB        NOT NULL COMMENT '首次响应体 JSON',
    created_at  TIMESTAMP   NOT NULL COMMENT '首次请求时刻（UTC）'
);
