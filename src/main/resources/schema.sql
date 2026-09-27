-- 化妆品备案服务建表脚本（H2 MySQL 兼容模式；语法同样兼容 MySQL 8）
-- 所有时刻列均存 UTC；DATE 列为 UTC 日界日期。

CREATE TABLE IF NOT EXISTS efficacy_catalog (
    code        VARCHAR(40)  NOT NULL PRIMARY KEY COMMENT '功效编码',
    name        VARCHAR(100) NOT NULL COMMENT '功效名称',
    special     TINYINT      NOT NULL COMMENT '是否特殊功效：1=特殊化妆品功效，0=普通功效',
    supported   TINYINT      NOT NULL COMMENT '是否允许宣称：1=支持，0=不支持的功效宣称'
);

CREATE TABLE IF NOT EXISTS restricted_ingredient (
    ingredient_code       VARCHAR(40)    NOT NULL PRIMARY KEY COMMENT '限用成分编码',
    name                  VARCHAR(100)   NOT NULL COMMENT '成分名称',
    max_concentration_pct DECIMAL(10, 4) NOT NULL COMMENT '配方中允许的最高质量百分比浓度，恰等合法，单位 %',
    unit                  VARCHAR(10)    NOT NULL DEFAULT '%' COMMENT '浓度单位'
);

CREATE TABLE IF NOT EXISTS test_report (
    report_no            VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '功效评价试验报告编号',
    efficacy_code        VARCHAR(40)  NOT NULL COMMENT '报告对应的功效编码',
    issued_date          DATE         NOT NULL COMMENT '报告出具日(UTC 日界)，备案提交日前3年内有效，恰满3年当日有效',
    conclusion_supported TINYINT      NOT NULL COMMENT '报告结论是否支持该功效：1=支持，0=不支持',
    conclusion_text      VARCHAR(500) NULL COMMENT '报告结论文本',
    created_at_utc       TIMESTAMP    NOT NULL COMMENT '入库时间(UTC)'
);

CREATE TABLE IF NOT EXISTS filing_record (
    id                        BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    filing_no                 VARCHAR(40)  NULL COMMENT '备案编号；形式审查通过后生成，实质变更生成新编号',
    product_code              VARCHAR(64)  NOT NULL COMMENT '产品编码，同一产品仅允许一个有效备案',
    product_name              VARCHAR(200) NOT NULL COMMENT '产品名称',
    version_no                INT          NOT NULL DEFAULT 1 COMMENT '备案版本号，每次变更递增',
    parent_filing_no          VARCHAR(40)  NULL COMMENT '变更来源备案编号，构成变更链；首次备案为空',
    change_type               VARCHAR(20)  NOT NULL COMMENT '变更类型：INITIAL=首次，SUBSTANTIVE=实质变更，SIMPLE=简易变更',
    status                    VARCHAR(30)  NOT NULL COMMENT '状态：SUBMITTED/FORM_REJECTED/PUBLIC/OBJECTION_REVIEW/EFFECTIVE/SUPERSEDED/WITHDRAWN/REJECTED_AFTER_OBJECTION',
    formulation_json          CLOB         NOT NULL COMMENT '配方快照JSON：成分编码、名称、质量百分比浓度',
    claims_json               CLOB         NOT NULL COMMENT '功效宣称快照JSON：功效编码列表',
    reports_json              CLOB         NOT NULL COMMENT '随备案提交的功效评价试验报告快照JSON',
    packaging_text            CLOB         NULL COMMENT '包装文字内容；仅包装文字类变更走简易变更',
    request_id                VARCHAR(64)  NOT NULL COMMENT '幂等键，同键同参返回首次结果，同键异参409',
    submitted_at_utc          TIMESTAMP    NOT NULL COMMENT '备案提交时刻(UTC)',
    form_reviewed_at_utc      TIMESTAMP    NULL COMMENT '形式审查完成时刻(UTC)',
    public_notice_date_utc    DATE         NULL COMMENT '公示起算日(UTC 日界)',
    public_expires_at_utc     TIMESTAMP    NULL COMMENT '公示期满时刻：起算日起第10个自然日24:00(UTC)',
    objection_received_at_utc TIMESTAMP    NULL COMMENT '公示异议受理时刻(UTC)',
    effective_at_utc          TIMESTAMP    NULL COMMENT '生效时刻(UTC)',
    withdrawn_at_utc          TIMESTAMP    NULL COMMENT '注销时刻(UTC)，注销为终态',
    reject_reason             CLOB         NULL COMMENT '驳回/异议成立原因汇总',
    created_at_utc            TIMESTAMP    NOT NULL,
    updated_at_utc            TIMESTAMP    NOT NULL,
    CONSTRAINT uk_filing_version UNIQUE (filing_no, version_no)
);
CREATE INDEX IF NOT EXISTS idx_filing_product ON filing_record (product_code, version_no);

CREATE TABLE IF NOT EXISTS review_detail (
    id             BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    filing_no      VARCHAR(40)  NOT NULL COMMENT '备案编号（驳回件也分配临时编号以便复算）',
    version_no     INT          NOT NULL COMMENT '备案版本号',
    stage          VARCHAR(20)  NOT NULL COMMENT '审查阶段：FORM=形式审查，OBJECTION=异议审查',
    rule_code      VARCHAR(40)  NOT NULL COMMENT '规则编码：资料齐全/功效目录/报告有效期/报告一致性/限用浓度等',
    passed         TINYINT      NOT NULL COMMENT '该规则是否通过：1=通过，0=不通过',
    detail         CLOB         NOT NULL COMMENT '审查结论说明，含期限口径与实测值',
    created_at_utc TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_review_filing ON review_detail (filing_no, version_no);

-- 幂等记录：指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id     VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '幂等键',
    operation      VARCHAR(20)  NOT NULL COMMENT '操作类型：SUBMIT/CHANGE',
    fingerprint    VARCHAR(128) NOT NULL COMMENT '业务字段指纹(SHA-256)，与 requestId 共同构成幂等约束',
    http_status    INT          NOT NULL COMMENT '首次响应HTTP状态码',
    outcome        VARCHAR(30)  NOT NULL COMMENT '首次处理结果状态，便于复算',
    filing_no      VARCHAR(40)  NULL COMMENT '关联备案编号',
    response_json  CLOB         NOT NULL COMMENT '首次响应体快照',
    created_at_utc TIMESTAMP    NOT NULL
);

-- 有效备案槽：以数据库唯一约束保证同一产品同时刻只有一个有效备案，版本切换在同事务内换槽
CREATE TABLE IF NOT EXISTS product_active_filing (
    product_code VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '产品编码',
    filing_no    VARCHAR(40) NOT NULL COMMENT '当前有效备案编号',
    version_no   INT         NOT NULL COMMENT '当前有效版本号',
    since_utc    TIMESTAMP   NOT NULL COMMENT '该备案生效(占据槽位)时刻(UTC)'
);

-- 备案编号顺序号单行表（行锁原子自增）
CREATE TABLE IF NOT EXISTS filing_sequence (
    id  TINYINT NOT NULL PRIMARY KEY COMMENT '固定为1的单行',
    seq BIGINT  NOT NULL COMMENT '已分配的备案编号顺序号'
);
MERGE INTO filing_sequence (id, seq) KEY(id) VALUES (1, 0);

-- ============================ 儿童化妆品分龄审查 ============================

-- 儿童分龄规则（合成）：0~36 月与 37~144 月两档，受理时冻结 rule_version
CREATE TABLE IF NOT EXISTS children_age_band_rule (
    band_code    VARCHAR(20) NOT NULL PRIMARY KEY COMMENT '年龄档编码：INFANT=0~36月，CHILD=37~144月',
    lower_months INT         NOT NULL COMMENT '档位下限（整数月，闭区间）',
    upper_months INT         NOT NULL COMMENT '档位上限（整数月，闭区间）',
    rule_version INT         NOT NULL COMMENT '分龄规则版本，受理时随备案冻结'
);

-- 儿童分档限用成分限值：跨档产品按两档较严者判定，恰等限值合格
CREATE TABLE IF NOT EXISTS children_band_limit (
    band_code             VARCHAR(20)     NOT NULL COMMENT '年龄档编码',
    ingredient_code       VARCHAR(40)     NOT NULL COMMENT '限用成分编码',
    max_concentration_pct DECIMAL(10, 4)  NOT NULL COMMENT '该档允许的最高质量百分比浓度，恰等合法，单位 %',
    PRIMARY KEY (band_code, ingredient_code)
);

-- 儿童功效合成目录：只允许目录内且 supported=1 的儿童功效
CREATE TABLE IF NOT EXISTS children_efficacy_catalog (
    code      VARCHAR(40)  NOT NULL PRIMARY KEY COMMENT '儿童功效编码',
    name      VARCHAR(100) NOT NULL COMMENT '功效名称',
    supported TINYINT      NOT NULL COMMENT '是否允许儿童宣称：1=允许，0=不允许'
);

-- 儿童安全报告登记（独立报告类型；成人功效报告不能替代）
CREATE TABLE IF NOT EXISTS children_safety_report (
    report_no              VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '儿童安全报告编号',
    band_code              VARCHAR(20)  NOT NULL COMMENT '适用年龄档编码',
    coverage_lower_months  INT          NOT NULL COMMENT '报告明确覆盖年龄下限（整数月，闭区间）',
    coverage_upper_months  INT          NOT NULL COMMENT '报告明确覆盖年龄上限（整数月，闭区间）',
    issued_date            DATE         NOT NULL COMMENT '报告出具日(UTC 日界)',
    conclusion_supported   TINYINT      NOT NULL COMMENT '报告结论是否支持：1=支持，0=不支持',
    conclusion_text        VARCHAR(500) NULL COMMENT '报告结论文本',
    created_at_utc         TIMESTAMP    NOT NULL COMMENT '入库时间(UTC)'
);

-- 儿童备案：每次补正生成新修订行，旧修订置 REVISION_SUPERSEDED；公示只取最新修订
CREATE TABLE IF NOT EXISTS children_filing (
    id                     BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    filing_no              VARCHAR(40)  NOT NULL COMMENT '儿童备案编号',
    product_code           VARCHAR(64)  NOT NULL COMMENT '产品编码，同一产品仅允许一个在途/有效儿童备案',
    product_name           VARCHAR(200) NOT NULL COMMENT '产品名称',
    version_no             INT          NOT NULL COMMENT '版本号，从1开始，成功修改（补正/变更）加1',
    revision_no            INT          NOT NULL COMMENT '修订号，材料补正生成新修订',
    parent_filing_no       VARCHAR(40)  NULL COMMENT '实质变更来源备案编号；首次备案为空',
    change_type            VARCHAR(20)  NOT NULL COMMENT '变更类型：INITIAL/SUBSTANTIVE/SIMPLE',
    status                 VARCHAR(30)  NOT NULL COMMENT '状态：PUBLIC/EFFECTIVE/SUPERSEDED/REVISION_SUPERSEDED',
    age_lower_months       INT          NOT NULL COMMENT '申请年龄下限（整数月，闭区间，0~144）',
    age_upper_months       INT          NOT NULL COMMENT '申请年龄上限（整数月，闭区间，0~144）',
    formulation_json       CLOB         NOT NULL COMMENT '配方快照JSON',
    claims_json            CLOB         NOT NULL COMMENT '儿童功效宣称快照JSON',
    safety_reports_json    CLOB         NOT NULL COMMENT '受理冻结的儿童安全报告清单快照JSON',
    labels_json            CLOB         NOT NULL COMMENT '受理冻结的适用年龄标识快照JSON',
    label_digest           VARCHAR(64)  NOT NULL COMMENT '受理冻结的标识文本摘要(SHA-256)',
    rule_version           INT          NOT NULL COMMENT '受理冻结的分龄规则版本',
    packaging_text         CLOB         NULL COMMENT '包装文字内容',
    request_id             VARCHAR(64)  NOT NULL COMMENT '幂等键',
    submitted_at_utc       TIMESTAMP    NOT NULL COMMENT '本修订提交时刻(UTC)',
    public_notice_date_utc DATE         NULL COMMENT '公示起算日(UTC 日界)',
    public_expires_at_utc  TIMESTAMP    NULL COMMENT '公示期满时刻(UTC)',
    effective_at_utc       TIMESTAMP    NULL COMMENT '生效时刻(UTC)',
    created_at_utc         TIMESTAMP    NOT NULL,
    updated_at_utc         TIMESTAMP    NOT NULL,
    CONSTRAINT uk_children_filing_version UNIQUE (filing_no, version_no, revision_no)
);
CREATE INDEX IF NOT EXISTS idx_children_filing_product ON children_filing (product_code, version_no);

-- 儿童产品有效备案槽：与成人槽位独立，保证同一产品同时刻唯一有效儿童备案
CREATE TABLE IF NOT EXISTS children_active_filing (
    product_code VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '产品编码',
    filing_no    VARCHAR(40) NOT NULL COMMENT '当前有效儿童备案编号',
    version_no   INT         NOT NULL COMMENT '当前有效版本号',
    since_utc    TIMESTAMP   NOT NULL COMMENT '占据槽位时刻(UTC)'
);

-- 儿童备案编号顺序号单行表（行锁原子自增）
CREATE TABLE IF NOT EXISTS children_filing_sequence (
    id  TINYINT NOT NULL PRIMARY KEY COMMENT '固定为1的单行',
    seq BIGINT  NOT NULL COMMENT '已分配的儿童备案编号顺序号'
);
MERGE INTO children_filing_sequence (id, seq) KEY(id) VALUES (1, 0);
