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

-- ============================ 儿童化妆品分龄审查通道 ============================

-- 分龄规则版本：0~36月与37~144月两档（整数月闭区间），受理时冻结规则版本
CREATE TABLE IF NOT EXISTS child_age_rule (
    rule_version      VARCHAR(40) NOT NULL COMMENT '分龄规则版本号，受理时随修订冻结',
    band_code         VARCHAR(20) NOT NULL COMMENT '档位编码：BAND_0_36=0~36月，BAND_37_144=37~144月',
    band_lower_months INT         NOT NULL COMMENT '档位下限（整数月，含）',
    band_upper_months INT         NOT NULL COMMENT '档位上限（整数月，含）',
    active            TINYINT     NOT NULL COMMENT '是否当前启用版本：1=启用，0=历史版本',
    PRIMARY KEY (rule_version, band_code)
);

-- 儿童限用成分分档限值：跨档产品按两档较严（较低）限值判定，恰等限值合格
CREATE TABLE IF NOT EXISTS child_restricted_ingredient (
    rule_version          VARCHAR(40)    NOT NULL COMMENT '分龄规则版本号',
    ingredient_code       VARCHAR(40)    NOT NULL COMMENT '限用成分编码',
    band_code             VARCHAR(20)    NOT NULL COMMENT '适用档位编码',
    max_concentration_pct DECIMAL(10, 4) NOT NULL COMMENT '该档位允许的最高质量百分比浓度，恰等合法，单位 %',
    PRIMARY KEY (rule_version, ingredient_code, band_code)
);

-- 儿童功效合成目录：儿童备案只允许该目录内且支持宣称的功效；成人功效目录不能替代
CREATE TABLE IF NOT EXISTS child_efficacy_catalog (
    code      VARCHAR(40)  NOT NULL PRIMARY KEY COMMENT '儿童功效编码',
    name      VARCHAR(100) NOT NULL COMMENT '儿童功效名称',
    supported TINYINT      NOT NULL COMMENT '是否允许宣称：1=支持，0=不支持'
);

-- 儿童安全报告登记（合成数据）：按档位独立，须明确覆盖申请年龄范围；成人功效报告不能替代
CREATE TABLE IF NOT EXISTS child_safety_report (
    report_no          VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '儿童安全报告编号',
    band_code          VARCHAR(20)  NOT NULL COMMENT '报告所属档位编码',
    cover_lower_months INT          NOT NULL COMMENT '报告覆盖年龄下限（整数月，含）',
    cover_upper_months INT          NOT NULL COMMENT '报告覆盖年龄上限（整数月，含）',
    conclusion_text    VARCHAR(500) NULL COMMENT '报告结论摘要文本',
    created_at_utc     TIMESTAMP    NOT NULL COMMENT '登记时刻(UTC)'
);

-- 儿童备案主表（聚合）：version_no 为乐观锁版本，从1开始，每次成功修改+1
CREATE TABLE IF NOT EXISTS child_filing (
    id                  BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    filing_no           VARCHAR(40)  NOT NULL UNIQUE COMMENT '儿童备案编号 SYN-C-000001',
    product_code        VARCHAR(64)  NOT NULL UNIQUE COMMENT '产品编码，同一产品仅允许一个儿童备案',
    product_name        VARCHAR(200) NOT NULL COMMENT '产品名称',
    version_no          INT          NOT NULL DEFAULT 1 COMMENT '聚合版本：从1开始，每次成功修改+1，修改须携带expectedVersion',
    status              VARCHAR(30)  NOT NULL COMMENT '状态：REVIEW_REJECTED=审查不通过(零生效)/IN_PUBLICITY=公示中/EFFECTIVE=生效',
    current_revision_no INT          NOT NULL COMMENT '当前最新修订号，公示只能使用该修订',
    request_id          VARCHAR(64)  NOT NULL COMMENT '创建立案的幂等键',
    created_at_utc      TIMESTAMP    NOT NULL,
    updated_at_utc      TIMESTAMP    NOT NULL
);

-- 儿童备案修订：受理时冻结报告清单、分龄规则版本与标识文本摘要
CREATE TABLE IF NOT EXISTS child_filing_revision (
    id                   BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    filing_no            VARCHAR(40)  NOT NULL COMMENT '所属儿童备案编号',
    revision_no          INT          NOT NULL COMMENT '修订号，从1开始，补正/变更生成新修订',
    revision_type        VARCHAR(20)  NOT NULL COMMENT '修订类型：INITIAL=首次，CORRECTION=材料补正，SUBSTANTIVE=实质变更，SIMPLE=简易变更',
    status               VARCHAR(20)  NOT NULL COMMENT '状态：REJECTED=审查不通过/PUBLIC=公示中/INVALIDATED=已被新修订取代/EFFECTIVE=生效/SUPERSEDED=已被生效新修订替代',
    age_lower_months     INT          NOT NULL COMMENT '申请年龄下限（整数月，含，0~144）',
    age_upper_months     INT          NOT NULL COMMENT '申请年龄上限（整数月，含，0~144）',
    claims_json          CLOB         NOT NULL COMMENT '儿童功效宣称快照JSON',
    formulation_json     CLOB         NOT NULL COMMENT '配方快照JSON',
    safety_reports_json  CLOB         NOT NULL COMMENT '受理冻结的分档安全报告清单快照JSON',
    labels_json          CLOB         NOT NULL COMMENT '受理冻结的分档适用年龄标识快照JSON',
    rule_version         VARCHAR(40)  NOT NULL COMMENT '受理冻结的分龄规则版本',
    label_digest         VARCHAR(128) NOT NULL COMMENT '受理冻结的标识文本摘要(SHA-256，结构化无歧义)',
    packaging_text       CLOB         NULL COMMENT '包装文字；仅包装文字类变更走简易变更',
    reject_reason        CLOB         NULL COMMENT '审查不通过原因汇总（缺项一次列全）',
    request_id           VARCHAR(64)  NOT NULL COMMENT '生成该修订的写操作幂等键',
    submitted_at_utc     TIMESTAMP    NOT NULL COMMENT '该修订提交时刻(UTC)',
    accepted_at_utc      TIMESTAMP    NULL COMMENT '受理(冻结)时刻(UTC)，审查通过进入公示时写入',
    public_expires_at_utc TIMESTAMP   NULL COMMENT '公示期满时刻：受理日(UTC)起第10个自然日24:00(UTC)',
    effective_at_utc     TIMESTAMP    NULL COMMENT '生效时刻(UTC)',
    created_at_utc       TIMESTAMP    NOT NULL,
    updated_at_utc       TIMESTAMP    NOT NULL,
    CONSTRAINT uk_child_revision UNIQUE (filing_no, revision_no)
);
CREATE INDEX IF NOT EXISTS idx_child_revision_status ON child_filing_revision (status, public_expires_at_utc);

-- 儿童备案编号顺序号单行表（行锁原子自增）
CREATE TABLE IF NOT EXISTS child_filing_sequence (
    id  TINYINT NOT NULL PRIMARY KEY COMMENT '固定为1的单行',
    seq BIGINT  NOT NULL COMMENT '已分配的儿童备案编号顺序号'
);
MERGE INTO child_filing_sequence (id, seq) KEY(id) VALUES (1, 0);
