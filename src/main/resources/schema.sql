-- 疫苗批签发审查服务本地建表脚本（H2 MySQL 兼容模式，合成数据，仅 JVM 生命周期内有效）

-- 批次审查主表：一个批号唯一对应一个审查流程
CREATE TABLE IF NOT EXISTS batch_review (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '审查流程主键',
    enterprise_name   VARCHAR(128) NOT NULL COMMENT '申报企业名称',
    product_name      VARCHAR(128) NOT NULL COMMENT '疫苗品种名称',
    batch_no          VARCHAR(64)  NOT NULL COMMENT '批号（合成数据，全局唯一，保证同批号同时刻仅一个流程）',
    status            VARCHAR(32)  NOT NULL COMMENT '审查状态：PENDING_CORRECTION/DOCUMENT_REVIEWED/INSPECTION_CHECKED/EVALUATION_PASSED/REJECTED/PUBLICIZING/OBJECTION_REVIEW/PUBLICITY_EFFECTIVE/WITHDRAWN/ISSUED',
    documents_json    CLOB         NULL COMMENT '当前已受理的资料目录代码列表 JSON',
    missing_json      CLOB         NULL COMMENT '一次性告知的缺项资料代码列表 JSON',
    declared_at       TIMESTAMP    NOT NULL COMMENT '申报受理时刻（带偏移量的业务时间）',
    correction_notice_date DATE    NULL COMMENT '补正告知日期（Asia/Shanghai 工作日起算日）',
    correction_deadline    DATE    NULL COMMENT '补正截止日期（告知日后第 5 个工作日，当日提交仍受理）',
    corrected_at      TIMESTAMP    NULL COMMENT '补正受理时刻，空值表示尚未补正',
    publicity_start_utc DATE     NULL COMMENT '公示开始 UTC 日期，空值表示尚未公示',
    publicity_end_utc   DATE     NULL COMMENT '公示期满 UTC 日期（开始日 +7 个自然日，恰当日期满生效）',
    conclusion        VARCHAR(32)  NULL COMMENT '综合评定结论：PASS/FAIL，空值表示尚未评定',
    issued_at         TIMESTAMP    NULL COMMENT '签发归档时刻，空值表示未归档',
    created_at        TIMESTAMP    NOT NULL COMMENT '记录创建时刻',
    updated_at        TIMESTAMP    NOT NULL COMMENT '记录最近更新时刻',
    CONSTRAINT uk_batch_review_batch_no UNIQUE (batch_no)
);

-- 检验项目明细（检验链，可逐项复算）
CREATE TABLE IF NOT EXISTS inspection_record (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '检验记录主键',
    review_id      BIGINT       NOT NULL COMMENT '所属审查流程 ID',
    item_code      VARCHAR(48)  NOT NULL COMMENT '检验项目代码：POTENCY/STERILITY/ABNORMAL_TOXICITY/PH_VALUE/MOISTURE',
    item_name      VARCHAR(64)  NOT NULL COMMENT '检验项目名称',
    critical       BOOLEAN      NOT NULL COMMENT '是否关键项目：效力/无菌/异常毒性为 TRUE',
    measured_value VARCHAR(48)  NOT NULL COMMENT '申报的实测值（数值或“无菌/有菌”）',
    limit_value    VARCHAR(48)  NOT NULL COMMENT '限值（含等号边界，恰等限值判合格）',
    comparison     VARCHAR(16)  NOT NULL COMMENT '判定方式：GTE/LTE/EQ/RANGE',
    limit_max      VARCHAR(48)  NULL COMMENT 'RANGE 判定上限（含边界），其余方式为空',
    qualified      BOOLEAN      NOT NULL COMMENT '检验核对阶段复算的合格结论',
    CONSTRAINT fk_inspection_review FOREIGN KEY (review_id) REFERENCES batch_review (id),
    CONSTRAINT uk_inspection_review_item UNIQUE (review_id, item_code)
);

-- 公示记录（公示链，可复算）
CREATE TABLE IF NOT EXISTS publicity_record (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '公示记录主键',
    review_id          BIGINT      NOT NULL COMMENT '所属审查流程 ID',
    start_utc_date     DATE        NOT NULL COMMENT '公示开始 UTC 日期',
    end_utc_date       DATE        NOT NULL COMMENT '公示期满 UTC 日期（开始日 +7 个自然日）',
    status             VARCHAR(24) NOT NULL COMMENT '公示状态：PUBLICIZING/OBJECTION_REVIEW/EFFECTIVE',
    objection_at       TIMESTAMP   NULL COMMENT '异议受理时刻，空值表示无异议',
    effective_at       TIMESTAMP   NULL COMMENT '期满生效时刻，空值表示未生效',
    CONSTRAINT fk_publicity_review FOREIGN KEY (review_id) REFERENCES batch_review (id),
    CONSTRAINT uk_publicity_review UNIQUE (review_id)
);

-- 企业-品种受理状态（连续不合格计数与暂停受理）
CREATE TABLE IF NOT EXISTS enterprise_product_status (
    id                  BIGINT AUTO_INCREMENT UNIQUE COMMENT '自增列（业务主键为企业+品种组合键）',
    enterprise_name     VARCHAR(128) NOT NULL COMMENT '企业名称',
    product_name        VARCHAR(128) NOT NULL COMMENT '疫苗品种名称',
    consecutive_failed  INT          NOT NULL DEFAULT 0 COMMENT '连续不合格批次数，合格一批即清零，恰为 3 触发暂停',
    suspended           BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否已暂停受理并转核查',
    suspended_at        TIMESTAMP    NULL COMMENT '暂停受理触发时刻，空值表示未暂停',
    updated_at          TIMESTAMP    NOT NULL COMMENT '最近更新时刻',
    CONSTRAINT pk_enterprise_product PRIMARY KEY (enterprise_name, product_name)
);

-- requestId 幂等记录
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id   VARCHAR(80)  NOT NULL COMMENT '业务请求幂等键 requestId',
    operation    VARCHAR(32)  NOT NULL COMMENT '操作类型：DECLARE/CORRECT/ISSUE',
    fingerprint  VARCHAR(96)  NOT NULL COMMENT '覆盖 requestId 与全部业务字段的 SHA-256 指纹',
    http_status  INT          NOT NULL COMMENT '首次请求返回的 HTTP 状态码',
    response_json CLOB        NOT NULL COMMENT '首次请求返回体 JSON，重放原样返回',
    created_at   TIMESTAMP    NOT NULL COMMENT '首次请求时刻',
    CONSTRAINT pk_idempotency PRIMARY KEY (request_id)
);

-- 进口疫苗合成通关单：一张通关单可关联多个包装子批，企业品种固定，核销整数支数
CREATE TABLE IF NOT EXISTS customs_declaration (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '通关单主键',
    declaration_no    VARCHAR(64)  NOT NULL COMMENT '合成通关单号（全局唯一，仅合成规则使用）',
    enterprise_name   VARCHAR(128) NOT NULL COMMENT '通关单登记企业名称，绑定子批企业必须一致',
    product_name      VARCHAR(128) NOT NULL COMMENT '通关单登记疫苗品种，绑定子批品种必须一致',
    valid_until       DATE         NOT NULL COMMENT '有效截止日期（UTC 日界判定，恰截止日签发拒绝）',
    total_quantity    INT          NOT NULL COMMENT '可核销总数量（正整数，单位：支）',
    version           INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，从 1 开始，每次成功变更 +1',
    revoked           BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '通关单是否已撤销：TRUE 后阻断全部签发',
    revoked_at        TIMESTAMP    NULL COMMENT '撤销时刻，空值表示未撤销',
    created_at        TIMESTAMP    NOT NULL COMMENT '登记时刻',
    updated_at        TIMESTAMP    NOT NULL COMMENT '最近更新时刻',
    CONSTRAINT uk_customs_declaration_no UNIQUE (declaration_no)
);

-- 包装子批与通关单的预占绑定：每个子批最多绑定一张通关单
CREATE TABLE IF NOT EXISTS customs_reservation (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '预占绑定主键',
    customs_id        BIGINT       NOT NULL COMMENT '所属通关单 ID',
    review_id         BIGINT       NOT NULL COMMENT '包装子批审查流程 ID（batch_review.id）',
    declared_quantity INT          NOT NULL COMMENT '本子批申报数量（正整数，单位：支）',
    status            VARCHAR(16)  NOT NULL COMMENT '预占状态：RESERVED 预占中/RELEASED 已释放（撤回或通关单撤销）/WRITTEN_OFF 签发时转已核销',
    version           INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，从 1 开始，每次成功变更 +1',
    reserved_at       TIMESTAMP    NOT NULL COMMENT '预占（绑定）时刻',
    released_at       TIMESTAMP    NULL COMMENT '释放时刻（签发前撤回或通关单撤销），空值表示未释放；释放只发生一次',
    written_off_at    TIMESTAMP    NULL COMMENT '已核销时刻（进口子批签发热点内转已核销），空值表示未核销',
    created_at        TIMESTAMP    NOT NULL COMMENT '记录创建时刻',
    updated_at        TIMESTAMP    NOT NULL COMMENT '记录最近更新时刻',
    CONSTRAINT fk_reservation_customs FOREIGN KEY (customs_id) REFERENCES customs_declaration (id),
    CONSTRAINT fk_reservation_review FOREIGN KEY (review_id) REFERENCES batch_review (id),
    CONSTRAINT uk_reservation_review UNIQUE (review_id)
);

-- 通关单风险事件流水（撤销影响已签发子批等，证据不可变）
CREATE TABLE IF NOT EXISTS customs_risk_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '风险事件主键',
    customs_id  BIGINT       NOT NULL COMMENT '所属通关单 ID',
    review_id   BIGINT       NULL COMMENT '关联包装子批审查流程 ID；无具体子批（如登记/撤销单本身）时为空',
    event_type  VARCHAR(48)  NOT NULL COMMENT '事件类型：CUSTOMS_REGISTERED/CUSTOMS_REVOKED/RESERVATION_RELEASED/ISSUE_BLOCKED 等',
    payload     CLOB         NULL COMMENT '事件附加信息 JSON',
    event_at    TIMESTAMP    NOT NULL COMMENT '事件发生的业务时刻',
    created_at  TIMESTAMP    NOT NULL COMMENT '事件落库时刻',
    CONSTRAINT fk_risk_customs FOREIGN KEY (customs_id) REFERENCES customs_declaration (id)
);

-- 审查事件流水（含补正、撤回、暂停、迟到资料等事件）
CREATE TABLE IF NOT EXISTS review_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件主键',
    review_id  BIGINT       NOT NULL COMMENT '所属审查流程 ID',
    event_type VARCHAR(48)  NOT NULL COMMENT '事件类型：DECLARED/CORRECTION_NOTICED/CORRECTION_ACCEPTED/WITHDRAWN/DOCUMENT_REVIEWED/INSPECTION_CHECKED/EVALUATION_PASSED/EVALUATION_FAILED/PUBLICITY_STARTED/OBJECTION_ACCEPTED/ISSUED_ARCHIVED/LATE_DOCUMENT/SUSPENSION_TRIGGERED',
    payload    CLOB         NULL COMMENT '事件附加信息 JSON',
    event_at   TIMESTAMP    NOT NULL COMMENT '事件发生的业务时刻',
    created_at TIMESTAMP    NOT NULL COMMENT '事件落库时刻',
    CONSTRAINT fk_event_review FOREIGN KEY (review_id) REFERENCES batch_review (id)
);
