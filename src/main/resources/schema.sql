-- 传染病直报本地 H2（MODE=MySQL）建表脚本；仅合成数据，JVM 生命周期内有效。

-- 病种目录：category=A 甲类(2h)，B 乙类(24h)
CREATE TABLE IF NOT EXISTS disease_catalog (
    code        VARCHAR(32)  NOT NULL COMMENT '病种编码',
    name        VARCHAR(128) NOT NULL COMMENT '病种名称',
    category    VARCHAR(1)   NOT NULL COMMENT '管理类别：A=甲类 2小时，B=乙类 24小时',
    enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
    PRIMARY KEY (code)
);

-- 医疗机构目录（合成）
CREATE TABLE IF NOT EXISTS medical_facility (
    code        VARCHAR(32)  NOT NULL COMMENT '机构编码',
    name        VARCHAR(128) NOT NULL COMMENT '机构名称',
    enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
    PRIMARY KEY (code)
);

-- 卡号发号序列（仅用于生成业务卡片编号）
CREATE TABLE IF NOT EXISTS card_number_seq (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY
);

-- 报告卡主表（一张卡一行，current_version 指向最新版本）
-- dedup_key：有效卡固定为“患者|病种|发病日期”，撤回终态改写为卡片编号（天然唯一墓碑值）；
-- 单列唯一索引保证“同一患者+同一病种+同一发病日期至多一张有效卡”，撤回后可重新报告。
CREATE TABLE IF NOT EXISTS epidemic_card (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    card_number     VARCHAR(40)  NOT NULL COMMENT '卡片编号，业务唯一',
    patient_id      VARCHAR(64)  NOT NULL COMMENT '患者身份标识（合成）',
    patient_name    VARCHAR(64)  NULL COMMENT '患者姓名（合成）',
    disease_code    VARCHAR(32)  NOT NULL COMMENT '最新版本病种编码',
    onset_date      DATE         NOT NULL COMMENT '发病日期，按当地日期记录',
    dedup_key       VARCHAR(160) NOT NULL COMMENT '去重键：有效卡=患者|病种|发病日期，撤回终态=卡片编号墓碑值',
    current_version INT          NOT NULL DEFAULT 1 COMMENT '当前生效版本号，从1递增',
    status          VARCHAR(24)  NOT NULL COMMENT '卡片状态：PENDING_REVIEW 待审核/APPROVED 审核通过/WITHDRAWN 已撤回终态/ARCHIVED 已归档终审终态',
    created_at      DATETIME(3)  NOT NULL COMMENT '建卡时间(UTC)',
    updated_at      DATETIME(3)  NOT NULL COMMENT '最近状态变更时间(UTC)',
    PRIMARY KEY (id),
    UNIQUE (card_number)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_card_dedup_key ON epidemic_card (dedup_key);

-- 卡片版本链：初报为版本1，每次订正/特殊订正新增版本，历史完整保留
CREATE TABLE IF NOT EXISTS card_version (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    card_number         VARCHAR(40)  NOT NULL COMMENT '所属卡片编号',
    version_no          INT          NOT NULL COMMENT '版本号，从1递增',
    disease_code        VARCHAR(32)  NOT NULL COMMENT '本版病种编码',
    diagnosis_type      VARCHAR(16)  NOT NULL COMMENT '诊断类型：SUSPECTED 疑似/CONFIRMED 确诊',
    onset_date          DATE         NOT NULL COMMENT '本版发病日期',
    diagnosis_time      DATETIME(3)  NOT NULL COMMENT '诊断时刻(UTC)，报告时限起算点',
    submitted_at        DATETIME(3)  NOT NULL COMMENT '提交时刻(UTC)，时限判定点',
    deadline            DATETIME(3)  NOT NULL COMMENT '报告时限终点(UTC)：甲类诊断+2h，乙类诊断+24h',
    timely              TINYINT      NOT NULL COMMENT '是否及时：1=及时(提交<=时限终点)，0=迟报',
    late_reason         VARCHAR(512) NULL COMMENT '迟报原因，迟报时强制非空',
    facility_code       VARCHAR(32)  NOT NULL COMMENT '报告机构编码',
    reporter            VARCHAR(64)  NOT NULL COMMENT '报告人',
    change_kind         VARCHAR(24)  NOT NULL COMMENT '版本来源：INITIAL 初报/CORRECTION 普通订正/SPECIAL 特殊订正',
    disease_changed     TINYINT      NOT NULL DEFAULT 0 COMMENT '本版是否相对上版发生病种变更（含疑似转确诊）：1是0否',
    notes               VARCHAR(1024) NULL COMMENT '备注',
    created_at          DATETIME(3)  NOT NULL COMMENT '版本生成时间(UTC)',
    PRIMARY KEY (id),
    UNIQUE (card_number, version_no)
);

-- 审核轨迹（按版本记录；病种变更时旧版本审核结论作废留痕）
CREATE TABLE IF NOT EXISTS review_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    card_number     VARCHAR(40)  NOT NULL COMMENT '卡片编号',
    version_no      INT          NOT NULL COMMENT '被审核版本号',
    stage           VARCHAR(16)  NOT NULL COMMENT '审核阶段：REVIEW 初审/FINAL 终审归档/SPECIAL 特殊订正确认/WITHDRAWAL 撤回',
    reviewer        VARCHAR(64)  NOT NULL COMMENT '审核员账号',
    opinion         VARCHAR(512) NULL COMMENT '审核意见',
    outcome         VARCHAR(16)  NOT NULL COMMENT '审核结论：APPROVED 通过/VOIDED 作废',
    voided          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已作废留痕：1是0否',
    created_at      DATETIME(3)  NOT NULL COMMENT '审核时间(UTC)',
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_review_card ON review_record (card_number, version_no);

-- requestId 幂等表：指纹覆盖 requestId 与全部业务字段
-- result_kind：CREATED=首次产生新版本；DUPLICATE=命中重复去重未新建
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id      VARCHAR(80)  NOT NULL COMMENT '幂等键 requestId',
    request_type    VARCHAR(24)  NOT NULL COMMENT '请求类型：INITIAL 初报/CORRECTION 订正/SPECIAL 特殊订正发起',
    fingerprint     CHAR(64)     NOT NULL COMMENT '业务字段指纹 SHA-256 十六进制',
    result_kind     VARCHAR(16)  NOT NULL COMMENT '首次结果类型：CREATED 新建/DUPLICATE 重复去重',
    card_number     VARCHAR(40)  NOT NULL COMMENT '首次请求命中的卡片编号',
    version_no      INT          NOT NULL COMMENT '首次请求命中或产生的版本号',
    created_at      DATETIME(3)  NOT NULL COMMENT '首次请求时间(UTC)',
    PRIMARY KEY (request_id)
);

-- 归档后特殊订正：须两名不同审核员先后确认
CREATE TABLE IF NOT EXISTS special_correction (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id          VARCHAR(80)  NOT NULL COMMENT '特殊订正幂等键',
    card_number         VARCHAR(40)  NOT NULL COMMENT '目标卡片编号（已归档）',
    fingerprint         CHAR(64)     NOT NULL COMMENT '订正内容指纹',
    disease_code        VARCHAR(32)  NOT NULL COMMENT '订正后病种编码',
    diagnosis_type      VARCHAR(16)  NOT NULL COMMENT '订正后诊断类型',
    onset_date          DATE         NOT NULL COMMENT '订正后发病日期',
    diagnosis_time      DATETIME(3)  NOT NULL COMMENT '订正后诊断时刻(UTC)',
    timely              TINYINT      NOT NULL COMMENT '按发起时刻判定是否及时：1及时0迟报',
    late_reason         VARCHAR(512) NULL COMMENT '订正后迟报原因（迟报时必填）',
    notes               VARCHAR(1024) NULL COMMENT '订正说明/疫情核实情况',
    status              VARCHAR(16)  NOT NULL COMMENT '状态：PENDING 待生效（确认不足两人）/APPLIED 已生效',
    confirmer_1         VARCHAR(64)  NULL COMMENT '第一确认审核员',
    confirmer_2         VARCHAR(64)  NULL COMMENT '第二确认审核员（须与第一人不同）',
    applied_version_no  INT          NULL COMMENT '生效后生成的版本号',
    created_at          DATETIME(3)  NOT NULL COMMENT '发起时间(UTC)',
    applied_at          DATETIME(3)  NULL COMMENT '生效时间(UTC)',
    PRIMARY KEY (id),
    UNIQUE (request_id)
);
