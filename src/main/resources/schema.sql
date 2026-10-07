-- 化妆品备案服务建表脚本（H2 MySQL 兼容模式；语法同样兼容 MySQL 8）
-- 所有时刻列均存 UTC；DATE 列为 UTC 日界日期。

CREATE TABLE IF NOT EXISTS efficacy_catalog (
    code          VARCHAR(40)  NOT NULL PRIMARY KEY COMMENT '功效编码',
    name          VARCHAR(100) NOT NULL COMMENT '功效名称',
    special       TINYINT      NOT NULL COMMENT '是否特殊功效：1=特殊化妆品功效，0=普通功效',
    supported     TINYINT      NOT NULL COMMENT '是否允许宣称(成人)：1=支持，0=不支持的功效宣称',
    child_allowed TINYINT      NOT NULL DEFAULT 0 COMMENT '是否儿童合成目录允许宣称：1=允许，0=不允许；儿童备案只允许本列允许的功效'
);

CREATE TABLE IF NOT EXISTS restricted_ingredient (
    ingredient_code            VARCHAR(40)    NOT NULL PRIMARY KEY COMMENT '限用成分编码',
    name                       VARCHAR(100)   NOT NULL COMMENT '成分名称',
    max_concentration_pct      DECIMAL(10, 4) NOT NULL COMMENT '配方中允许的最高质量百分比浓度(成人)，恰等合法，单位 %',
    unit                       VARCHAR(10)    NOT NULL DEFAULT '%' COMMENT '浓度单位',
    child_max_band_0_36_pct    DECIMAL(10, 4) NULL COMMENT '儿童分龄限值：0~36月档最高质量百分比浓度，恰等合法，单位 %；NULL=该档不设限',
    child_max_band_37_144_pct  DECIMAL(10, 4) NULL COMMENT '儿童分龄限值：37~144月档最高质量百分比浓度，恰等合法，单位 %；NULL=该档不设限'
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
    filing_category           VARCHAR(10)  NOT NULL DEFAULT 'ADULT' COMMENT '备案类别：ADULT=成人备案，CHILD=儿童备案；儿童备案须通过分龄审查',
    age_lower_months          INT          NULL COMMENT '儿童备案申请年龄下限(整数月，闭区间，0<=下限<=上限<=144)；成人备案为NULL',
    age_upper_months          INT          NULL COMMENT '儿童备案申请年龄上限(整数月，闭区间)；成人备案为NULL',
    age_rule_version          VARCHAR(20)  NULL COMMENT '受理时冻结的分龄规则版本；成人备案为NULL',
    child_reports_json        CLOB         NULL COMMENT '受理时冻结的儿童安全报告清单快照JSON；成人备案为NULL',
    label_texts_json          CLOB         NULL COMMENT '受理时冻结的适用年龄标识文本快照JSON(按分龄档)；成人备案为NULL',
    label_digest              VARCHAR(128) NULL COMMENT '受理时冻结的标识文本摘要(SHA-256，结构化无歧义)；成人备案为NULL',
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

-- 儿童分龄规则版本单行表（合成初始化；受理时冻结当前版本到备案）
CREATE TABLE IF NOT EXISTS age_rule_version (
    id           TINYINT     NOT NULL PRIMARY KEY COMMENT '固定为1的单行',
    rule_version VARCHAR(20) NOT NULL COMMENT '当前分龄规则版本号',
    effective_from DATE      NOT NULL COMMENT '规则生效日(UTC 日界)'
);

-- 儿童分龄档定义：整数月闭区间，0<=下限<=上限<=144
CREATE TABLE IF NOT EXISTS age_band_rule (
    rule_version VARCHAR(20) NOT NULL COMMENT '分龄规则版本号',
    band_code    VARCHAR(20) NOT NULL COMMENT '分龄档编码：BAND_0_36 / BAND_37_144',
    lower_months INT         NOT NULL COMMENT '档下限(整数月，含)',
    upper_months INT         NOT NULL COMMENT '档上限(整数月，含)',
    PRIMARY KEY (rule_version, band_code)
);

-- 儿童安全报告（合成登记）：独立于成人功效评价试验报告，成人报告不能替代
CREATE TABLE IF NOT EXISTS child_safety_report (
    report_no            VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '儿童安全报告编号',
    band_code            VARCHAR(20)  NOT NULL COMMENT '报告对应分龄档编码',
    coverage_lower_months INT         NOT NULL COMMENT '报告明确覆盖的年龄下限(整数月，含)',
    coverage_upper_months INT         NOT NULL COMMENT '报告明确覆盖的年龄上限(整数月，含)',
    conclusion_supported TINYINT      NOT NULL COMMENT '报告结论是否支持该年龄段使用安全：1=支持，0=不支持',
    conclusion_text      VARCHAR(500) NULL COMMENT '报告结论文本',
    created_at_utc       TIMESTAMP    NOT NULL COMMENT '入库时间(UTC)'
);

-- 包装标签印次台账：领用绑定到已生效备案的具体修订；备案标识修订后旧印次不得继续领用
CREATE TABLE IF NOT EXISTS label_print_run (
    id                 BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    print_run_no       VARCHAR(40)  NOT NULL COMMENT '印次号，全局唯一',
    product_code       VARCHAR(64)  NOT NULL COMMENT '所属产品编码',
    filing_no          VARCHAR(40)  NOT NULL COMMENT '登记时引用的已生效备案编号',
    filing_version_no  INT          NOT NULL COMMENT '登记时引用的备案修订号',
    filing_category    VARCHAR(10)  NOT NULL COMMENT '备案类别快照：ADULT/CHILD',
    label_digest       VARCHAR(128) NOT NULL COMMENT '登记时冻结的标签文本摘要(SHA-256)，须与备案一致',
    band_digests_json  CLOB         NULL COMMENT '儿童印次冻结的各年龄档独立标识摘要JSON(bandCode->digest)；成人印次为NULL',
    printed_count      INT          NOT NULL COMMENT '印刷总张数(正整数)',
    issued_count       INT          NOT NULL DEFAULT 0 COMMENT '已领用张数',
    destroyed_count    INT          NOT NULL DEFAULT 0 COMMENT '已登记销毁张数(仅未领部分)',
    status             VARCHAR(20)  NOT NULL COMMENT '状态：ACTIVE=可领用，DISABLED=备案换版/注销后原子禁用，不允许复活',
    version_no         INT          NOT NULL DEFAULT 1 COMMENT '印次台账版本号，1起，每次修改(领用/销毁/禁用)加1，修改须携带expectedVersion',
    disabled_reason    VARCHAR(500) NULL COMMENT '禁用原因(备案实质变更生效/注销)；未禁用为NULL',
    disabled_at_utc    TIMESTAMP    NULL COMMENT '禁用时刻(UTC)；未禁用为NULL',
    request_id         VARCHAR(64)  NOT NULL COMMENT '登记幂等键',
    created_at_utc     TIMESTAMP    NOT NULL COMMENT '登记时刻(UTC)',
    updated_at_utc     TIMESTAMP    NOT NULL COMMENT '最近修改时刻(UTC)',
    CONSTRAINT uk_print_run_no UNIQUE (print_run_no)
);
CREATE INDEX IF NOT EXISTS idx_print_run_filing ON label_print_run (filing_no);

-- 合成包装批号：首次领用时登记所属产品，之后仅允许同产品印次领用，跨产品借用拒绝
CREATE TABLE IF NOT EXISTS packaging_batch (
    batch_no       VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '合成包装批号',
    product_code   VARCHAR(64) NOT NULL COMMENT '首次领用时登记所属产品编码，之后不可改',
    created_at_utc TIMESTAMP   NOT NULL COMMENT '登记时刻(UTC)'
);

-- 标签领用凭据：每次领用一条，可追溯；备案换版/注销后仅追加风险关联，不抹历史
CREATE TABLE IF NOT EXISTS label_issue (
    id            BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    print_run_no  VARCHAR(40)  NOT NULL COMMENT '领用的印次号',
    batch_no      VARCHAR(64)  NOT NULL COMMENT '合成包装批号',
    product_code  VARCHAR(64)  NOT NULL COMMENT '产品编码(与印次一致)',
    quantity      INT          NOT NULL COMMENT '领用张数(正整数)',
    request_id    VARCHAR(64)  NOT NULL COMMENT '领用幂等键',
    issued_at_utc TIMESTAMP    NOT NULL COMMENT '领用时刻(UTC)',
    risk_flag     TINYINT      NOT NULL DEFAULT 0 COMMENT '风险关联标记：备案换版/注销后置1，仅追加不抹历史',
    risk_note     VARCHAR(500) NULL COMMENT '风险关联说明；未关联为NULL',
    risk_at_utc   TIMESTAMP    NULL COMMENT '风险关联时刻(UTC)；未关联为NULL'
);
CREATE INDEX IF NOT EXISTS idx_issue_run ON label_issue (print_run_no);
CREATE INDEX IF NOT EXISTS idx_issue_batch ON label_issue (batch_no);

-- 未领标签销毁登记：仅未领部分可销毁，留证；已领部分不能销毁退回余额
CREATE TABLE IF NOT EXISTS label_destruction (
    id               BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    print_run_no     VARCHAR(40)  NOT NULL COMMENT '销毁的印次号',
    quantity         INT          NOT NULL COMMENT '销毁张数(正整数)',
    reason           VARCHAR(500) NULL COMMENT '销毁原因',
    request_id       VARCHAR(64)  NOT NULL COMMENT '销毁幂等键',
    destroyed_at_utc TIMESTAMP    NOT NULL COMMENT '销毁登记时刻(UTC)'
);
CREATE INDEX IF NOT EXISTS idx_destruction_run ON label_destruction (print_run_no);
