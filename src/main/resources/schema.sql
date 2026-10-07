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
    operation    VARCHAR(32)  NOT NULL COMMENT '操作类型：DECLARE/CORRECT/ISSUE/CUSTOMS_REGISTER/CUSTOMS_BIND/CUSTOMS_REVOKE/CUSTOMS_AMEND/SUBBATCH_WITHDRAW/COLDCHAIN_REGISTER/COLDCHAIN_READINGS/COLDCHAIN_CORRECT',
    fingerprint  VARCHAR(96)  NOT NULL COMMENT '覆盖 requestId 与全部业务字段的 SHA-256 指纹',
    http_status  INT          NOT NULL COMMENT '首次请求返回的 HTTP 状态码',
    response_json CLOB        NOT NULL COMMENT '首次请求返回体 JSON，重放原样返回',
    created_at   TIMESTAMP    NOT NULL COMMENT '首次请求时刻',
    CONSTRAINT pk_idempotency PRIMARY KEY (request_id)
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

-- 进口通关单台账：一张合成通关单可被多个包装子批共用核销，企业+品种固定不可改
CREATE TABLE IF NOT EXISTS customs_declaration (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '通关单主键',
    customs_no            VARCHAR(64)  NOT NULL COMMENT '合成通关单号（全局唯一）',
    enterprise_name       VARCHAR(128) NOT NULL COMMENT '通关单登记企业名称，绑定子批企业必须一致',
    product_name          VARCHAR(128) NOT NULL COMMENT '通关单登记疫苗品种，绑定子批品种必须一致，历史核销不可改',
    valid_until           TIMESTAMP    NOT NULL COMMENT '有效截止精确时刻：签发要求 now<该时刻，恰等于即失效拒绝',
    total_quantity        INT          NOT NULL COMMENT '可核销总支数（正整数，单位：支），修订仅允许增加',
    reserved_quantity     INT          NOT NULL DEFAULT 0 COMMENT '当前已预占未核销支数（单位：支，签发转核销、撤回/撤销释放）',
    written_off_quantity  INT          NOT NULL DEFAULT 0 COMMENT '累计已核销支数（单位：支，只增不减，历史证据不变）',
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '通关单状态：ACTIVE 有效/REVOKED 已撤销',
    version               INT          NOT NULL DEFAULT 1 COMMENT '乐观版本号：登记为 1，每次成功变更加 1',
    revoked_at            TIMESTAMP    NULL COMMENT '撤销时刻，空值表示未撤销',
    created_at            TIMESTAMP    NOT NULL COMMENT '登记时刻',
    updated_at            TIMESTAMP    NOT NULL COMMENT '最近变更时刻',
    CONSTRAINT uk_customs_no UNIQUE (customs_no),
    CONSTRAINT ck_customs_total_positive CHECK (total_quantity > 0),
    CONSTRAINT ck_customs_quantity_bounds CHECK (
        reserved_quantity >= 0 AND written_off_quantity >= 0
        AND reserved_quantity + written_off_quantity <= total_quantity)
);

-- 进口包装子批绑定：每个子批（审查流程）只能绑定一张通关单，先预占、签发转核销
CREATE TABLE IF NOT EXISTS subbatch_binding (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '绑定记录主键',
    review_id              BIGINT      NOT NULL COMMENT '包装子批对应的审查流程 ID（一子批仅一张通关单）',
    customs_declaration_id BIGINT      NOT NULL COMMENT '所绑定通关单 ID',
    batch_no               VARCHAR(64) NOT NULL COMMENT '子批评号（冗余，便于台账核对）',
    declared_quantity      INT         NOT NULL COMMENT '本子批申报预占支数（正整数，单位：支）',
    status                 VARCHAR(16) NOT NULL COMMENT '绑定状态：RESERVED 已预占/WRITTEN_OFF 已核销/RELEASED 已释放',
    version                INT         NOT NULL DEFAULT 1 COMMENT '乐观版本号：绑定为 1，撤回释放成功后加 1',
    reserved_at            TIMESTAMP   NOT NULL COMMENT '预占时刻',
    written_off_at         TIMESTAMP   NULL COMMENT '核销（进口签发）时刻，空值表示未核销',
    released_at            TIMESTAMP   NULL COMMENT '预占释放（签发前撤回或通关单撤销）时刻，空值表示未释放',
    created_at             TIMESTAMP   NOT NULL COMMENT '绑定创建时刻',
    updated_at             TIMESTAMP   NOT NULL COMMENT '最近变更时刻',
    CONSTRAINT fk_binding_review FOREIGN KEY (review_id) REFERENCES batch_review (id),
    CONSTRAINT fk_binding_customs FOREIGN KEY (customs_declaration_id) REFERENCES customs_declaration (id),
    CONSTRAINT uk_binding_review UNIQUE (review_id),
    CONSTRAINT ck_binding_quantity_positive CHECK (declared_quantity > 0)
);

-- 冷链运输台账：受控包装子批的运输片段与温证版本（一个审查流程至多一本台账，登记即启用冷链签发门禁）
CREATE TABLE IF NOT EXISTS cold_chain_ledger (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '冷链台账主键',
    review_id           BIGINT      NOT NULL COMMENT '受控子批对应的审查流程 ID（一个子批至多一本台账）',
    batch_no            VARCHAR(64) NOT NULL COMMENT '子批评号（冗余，便于台账核对）',
    transport_start_utc TIMESTAMP   NOT NULL COMMENT '完整运输起点 UTC 精确时刻（首片段 start，登记时冻结）',
    transport_end_utc   TIMESTAMP   NOT NULL COMMENT '完整运输终点 UTC 精确时刻（末片段 end，登记时冻结）',
    version             INT         NOT NULL DEFAULT 1 COMMENT '台账版本：登记为 1，每次成功补录/更正加 1',
    qualified_version   INT         NULL COMMENT '温证合格冻结的台账版本；仅当等于 version 时温证当前有效，空值表示从未合格',
    created_at          TIMESTAMP   NOT NULL COMMENT '台账登记时刻',
    updated_at          TIMESTAMP   NOT NULL COMMENT '最近变更时刻',
    CONSTRAINT uk_coldchain_review UNIQUE (review_id),
    CONSTRAINT fk_coldchain_review FOREIGN KEY (review_id) REFERENCES batch_review (id)
);

-- 冷链运输片段：每版本 1 至 5 个顺序片段，[start,end) 正长度且相邻端点完全相接；更正后旧版本片段冻结保留
CREATE TABLE IF NOT EXISTS cold_chain_segment (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '片段主键',
    ledger_id      BIGINT      NOT NULL COMMENT '所属冷链台账 ID',
    ledger_version INT         NOT NULL COMMENT '所属台账版本（更正后旧版本片段冻结保留，可复算）',
    segment_seq    INT         NOT NULL COMMENT '片段序号：每版本内从 1 连续递增',
    start_utc      TIMESTAMP   NOT NULL COMMENT '片段起点 UTC 精确时刻（含）',
    end_utc        TIMESTAMP   NOT NULL COMMENT '片段终点 UTC 精确时刻（片段区间本身不含，端点读数可取该时刻）',
    created_at     TIMESTAMP   NOT NULL COMMENT '片段登记时刻',
    CONSTRAINT fk_segment_ledger FOREIGN KEY (ledger_id) REFERENCES cold_chain_ledger (id),
    CONSTRAINT uk_segment_version_seq UNIQUE (ledger_id, ledger_version, segment_seq),
    CONSTRAINT ck_segment_positive CHECK (end_utc > start_utc)
);

-- 冷链温度读数：片内递增序号 + UTC 精确时刻 + 温度（摄氏度，合成合格闭区间 [2,8]，恰边界合格）
CREATE TABLE IF NOT EXISTS cold_chain_reading (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '读数主键',
    ledger_id      BIGINT      NOT NULL COMMENT '所属冷链台账 ID',
    ledger_version INT         NOT NULL COMMENT '所属台账版本',
    segment_seq    INT         NOT NULL COMMENT '所属片段序号',
    reading_seq    INT         NOT NULL COMMENT '片内读数序号（递增，必填，缺序号不得通过）',
    read_at        TIMESTAMP   NOT NULL COMMENT '读数采样 UTC 精确时刻，须落在片段闭区间内且不晚于提交时刻',
    temperature    DECIMAL(5,2) NOT NULL COMMENT '温度（摄氏度）；合成规则闭区间 [2,8]，任一点越界即不通过',
    created_at     TIMESTAMP   NOT NULL COMMENT '读数提交落库时刻',
    CONSTRAINT fk_reading_ledger FOREIGN KEY (ledger_id) REFERENCES cold_chain_ledger (id),
    CONSTRAINT uk_reading_version_seq UNIQUE (ledger_id, ledger_version, segment_seq, reading_seq)
);
