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

-- ======================================================================
-- 儿童化妆品分龄备案（独立于成人备案表；成人流程与历史报告不受影响）
-- 所有时刻列均存 UTC；年龄以整数月闭区间表示，与时间无关。
-- ======================================================================

-- 分龄规则版本（合成初始化；受理时冻结版本号，规则升级不回溯在审件）
CREATE TABLE IF NOT EXISTS child_age_rule (
    rule_version      VARCHAR(40)  NOT NULL COMMENT '分龄规则版本，如 CHILD-AGE-RULE-V1',
    tier_code         VARCHAR(20)  NOT NULL COMMENT '分档编码：TIER_0_36=0~36月，TIER_37_144=37~144月',
    age_from_months   INT          NOT NULL COMMENT '该档年龄下限(整数月，闭区间)',
    age_to_months     INT          NOT NULL COMMENT '该档年龄上限(整数月，闭区间)',
    created_at_utc    TIMESTAMP    NOT NULL COMMENT '规则入库时刻(UTC)',
    CONSTRAINT pk_child_age_rule PRIMARY KEY (rule_version, tier_code)
);

-- 儿童分档成分限值（合成规则；跨档成分取两档较严者，恰等限值合格）
CREATE TABLE IF NOT EXISTS child_ingredient_limit (
    rule_version          VARCHAR(40)    NOT NULL COMMENT '分龄规则版本',
    tier_code             VARCHAR(20)    NOT NULL COMMENT '分档编码：TIER_0_36/TIER_37_144',
    ingredient_code       VARCHAR(40)    NOT NULL COMMENT '限用成分编码',
    name                  VARCHAR(100)   NOT NULL COMMENT '成分名称',
    max_concentration_pct DECIMAL(10, 4) NOT NULL COMMENT '该档允许的最高质量百分比浓度，恰等合法，单位 %',
    CONSTRAINT pk_child_ingredient_limit PRIMARY KEY (rule_version, tier_code, ingredient_code)
);

-- 儿童允许功效目录（合成；只允许目录中的儿童功效，成人功效报告不能替代儿童安全报告）
CREATE TABLE IF NOT EXISTS child_efficacy_catalog (
    code     VARCHAR(40)  NOT NULL PRIMARY KEY COMMENT '儿童功效编码',
    name     VARCHAR(100) NOT NULL COMMENT '儿童功效名称',
    supported TINYINT     NOT NULL COMMENT '是否允许儿童宣称：1=允许，0=不允许（用于失败分支）'
);

-- 儿童安全报告登记（合成；每档需独立安全报告，报告必须明确覆盖申请年龄范围）
CREATE TABLE IF NOT EXISTS child_safety_report (
    report_no            VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '儿童安全评价报告编号',
    tier_code            VARCHAR(20)  NOT NULL COMMENT '报告对应的分档：TIER_0_36/TIER_37_144（每档独立报告）',
    issued_date          DATE         NOT NULL COMMENT '报告出具日(UTC 日界)，随受理冻结',
    covers_from_months   INT          NOT NULL COMMENT '报告明确覆盖的年龄下限(整数月，闭区间)',
    covers_to_months   INT          NOT NULL COMMENT '报告明确覆盖的年龄上限(整数月，闭区间)',
    conclusion_supported TINYINT    NOT NULL COMMENT '报告结论是否支持该档儿童使用：1=支持，0=不支持',
    conclusion_text    VARCHAR(500) NULL COMMENT '报告结论文本',
    created_at_utc     TIMESTAMP    NOT NULL COMMENT '入库时间(UTC)'
);

-- 儿童备案修订（每次提交/补正为一个修订 version_no；同 filing_no 多修订）
CREATE TABLE IF NOT EXISTS child_filing (
    id                     BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    filing_no              VARCHAR(40)  NOT NULL COMMENT '儿童备案编号；补正沿用同号，实质变更生成新号',
    product_code           VARCHAR(64)  NOT NULL COMMENT '产品编码，同一产品仅允许一个有效儿童备案',
    product_name           VARCHAR(200) NOT NULL COMMENT '产品名称',
    version_no             INT          NOT NULL COMMENT '修订/版本号，从1开始，每次成功提交/补正/变更+1',
    parent_filing_no       VARCHAR(40)  NULL COMMENT '实质变更来源备案编号，构成变更链；首次为空',
    change_type            VARCHAR(20)  NOT NULL COMMENT '变更类型：INITIAL=首次，CORRECTION=材料补正，SUBSTANTIVE=实质变更，SIMPLE=包装简易变更',
    status                 VARCHAR(30)  NOT NULL COMMENT '状态：SUBMITTED/FORM_REJECTED/PUBLIC/EFFECTIVE/SUPERSEDED/VOID(旧待审修订补正后失效)',
    age_from_months        INT          NOT NULL COMMENT '申请年龄下限(整数月，闭区间)，0<=下限<=上限<=144',
    age_to_months          INT          NOT NULL COMMENT '申请年龄上限(整数月，闭区间)',
    required_tiers_json    CLOB         NOT NULL COMMENT '本申请命中的分档编码列表JSON（单档1个，跨档2个且有序）',
    formulation_json       CLOB         NOT NULL COMMENT '配方快照JSON：成分编码、名称、质量百分比浓度',
    claims_json            CLOB         NOT NULL COMMENT '儿童功效宣称快照JSON：儿童功效编码列表',
    safety_reports_json    CLOB         NOT NULL COMMENT '受理冻结的分档安全报告清单快照JSON（含覆盖范围）',
    age_label_text         CLOB         NOT NULL COMMENT '适用年龄标识文本（标识必须明确标注适用年龄）',
    age_label_digest       VARCHAR(64)  NOT NULL COMMENT '标识文本摘要：结构化规范化后取SHA-256，冻结备查',
    age_rule_version       VARCHAR(40)  NOT NULL COMMENT '受理时冻结的分龄规则版本',
    packaging_text         CLOB         NULL COMMENT '包装文字内容；仅包装文字类变更可走简易变更',
    request_id             VARCHAR(64)  NOT NULL COMMENT '幂等键，同键同参返回首次冻结结果，同键异参409，失败不占键',
    submitted_at_utc       TIMESTAMP    NOT NULL COMMENT '提交/补正受理时刻(UTC)',
    form_reviewed_at_utc   TIMESTAMP    NULL COMMENT '形式审查完成时刻(UTC)',
    public_notice_date_utc DATE         NULL COMMENT '公示起算日(UTC 日界)',
    public_expires_at_utc  TIMESTAMP    NULL COMMENT '公示期满时刻：起算日起第10个自然日24:00(UTC)',
    effective_at_utc       TIMESTAMP    NULL COMMENT '生效时刻(UTC)',
    voided_at_utc          TIMESTAMP    NULL COMMENT '旧待审修订被补正新修订替代而失效的时刻(UTC)',
    reject_reason          CLOB         NULL COMMENT '形式审查驳回原因汇总（任一缺项一次列全）',
    created_at_utc         TIMESTAMP    NOT NULL,
    updated_at_utc         TIMESTAMP    NOT NULL,
    CONSTRAINT uk_child_filing_version UNIQUE (filing_no, version_no)
);
CREATE INDEX IF NOT EXISTS idx_child_filing_product ON child_filing (product_code, version_no);

-- 儿童备案审查明细（分龄规则逐条复算证据）
CREATE TABLE IF NOT EXISTS child_review_detail (
    id             BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    filing_no      VARCHAR(40)  NOT NULL COMMENT '儿童备案编号（驳回件也分配临时编号以便复算）',
    version_no     INT          NOT NULL COMMENT '修订版本号',
    rule_code      VARCHAR(40)  NOT NULL COMMENT '规则编码：年龄区间/分档命中/功效目录/分档安全报告/覆盖范围/标识/浓度等',
    passed         TINYINT      NOT NULL COMMENT '该规则是否通过：1=通过，0=不通过',
    detail         CLOB         NOT NULL COMMENT '审查结论说明，含分档、限值、覆盖范围口径与实测值',
    created_at_utc TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_child_review_filing ON child_review_detail (filing_no, version_no);

-- 儿童写操作幂等记录（业务、版本与幂等结果同事务；失败回滚则不占键）
CREATE TABLE IF NOT EXISTS child_idempotency_record (
    request_id     VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '幂等键',
    operation      VARCHAR(20)  NOT NULL COMMENT '操作类型：CHILD_SUBMIT/CHILD_CORRECT/CHILD_CHANGE',
    fingerprint    VARCHAR(128) NOT NULL COMMENT '完整参数(含版本、集合、精确时刻)结构化指纹SHA-256',
    http_status    INT          NOT NULL COMMENT '首次响应HTTP状态码',
    outcome        VARCHAR(30)  NOT NULL COMMENT '首次处理结果状态',
    filing_no      VARCHAR(40)  NULL COMMENT '关联儿童备案编号',
    response_json  CLOB         NOT NULL COMMENT '首次响应体快照',
    created_at_utc TIMESTAMP    NOT NULL
);

-- 儿童有效备案槽：数据库唯一约束保证同一产品同时刻只有一个有效儿童备案
CREATE TABLE IF NOT EXISTS child_product_active (
    product_code VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '产品编码',
    filing_no    VARCHAR(40) NOT NULL COMMENT '当前有效儿童备案编号',
    version_no   INT         NOT NULL COMMENT '当前有效版本号',
    since_utc    TIMESTAMP   NOT NULL COMMENT '该备案生效(占据槽位)时刻(UTC)'
);

-- 儿童备案编号顺序号单行表（行锁原子自增），与成人顺序号独立
CREATE TABLE IF NOT EXISTS child_filing_sequence (
    id  TINYINT NOT NULL PRIMARY KEY COMMENT '固定为1的单行',
    seq BIGINT  NOT NULL COMMENT '已分配的儿童备案编号顺序号'
);
MERGE INTO child_filing_sequence (id, seq) KEY(id) VALUES (1, 0);
