-- 传染病直报服务建表脚本（H2 MySQL 兼容模式；时间均为 UTC 口径）

-- 病种目录（合成数据）
CREATE TABLE IF NOT EXISTS disease (
    code      VARCHAR(32) PRIMARY KEY COMMENT '病种编码',
    name      VARCHAR(128) NOT NULL COMMENT '病种名称',
    category  VARCHAR(8) NOT NULL COMMENT '病种分类：A=甲类(2小时时限), B=乙类(24小时时限)'
);

-- 医疗机构目录（合成数据）
CREATE TABLE IF NOT EXISTS institution (
    code VARCHAR(32) PRIMARY KEY COMMENT '机构编码',
    name VARCHAR(128) NOT NULL COMMENT '机构名称'
);

-- 报告卡主表：一张有效卡一行
CREATE TABLE IF NOT EXISTS card (
    card_no           VARCHAR(40) PRIMARY KEY COMMENT '卡片编号（业务主键）',
    patient_id        VARCHAR(64) NOT NULL COMMENT '患者证件号（合成数据），去重维度',
    patient_name      VARCHAR(64) NOT NULL COMMENT '患者姓名',
    disease_code      VARCHAR(32) NOT NULL COMMENT '当前病种编码，去重维度',
    onset_date        DATE NOT NULL COMMENT '发病日期（UTC口径），去重维度',
    institution_code  VARCHAR(32) NOT NULL COMMENT '报告医疗机构编码',
    current_version   INT NOT NULL COMMENT '当前最新版本号，初报为1',
    status            VARCHAR(20) NOT NULL COMMENT '状态：PENDING_REVIEW待审核/PENDING_FINAL待终审/ARCHIVED已归档(只读)/WITHDRAWN已撤回(终态)',
    dedup_key         VARCHAR(96) COMMENT '有效卡去重指纹(患者|病种|发病日期)；撤回后置NULL，唯一索引仅约束有效卡',
    created_at        TIMESTAMP NOT NULL COMMENT '创建时刻(UTC)',
    updated_at        TIMESTAMP NOT NULL COMMENT '最近更新时刻(UTC)',
    CONSTRAINT uk_card_dedup UNIQUE (dedup_key)
);

-- 报告卡版本表（不可变快照，历史完整保留）
CREATE TABLE IF NOT EXISTS card_version (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    card_no           VARCHAR(40) NOT NULL COMMENT '卡片编号',
    version_no        INT NOT NULL COMMENT '版本号，卡内从1递增',
    change_type       VARCHAR(16) NOT NULL COMMENT '变更类型：INITIAL初报/CORRECTION订正/SPECIAL特殊订正',
    disease_code      VARCHAR(32) NOT NULL COMMENT '该病种编码',
    disease_name      VARCHAR(128) NOT NULL COMMENT '该病种名称快照',
    category          VARCHAR(8) NOT NULL COMMENT '该版本病种分类快照：A/B',
    case_type         VARCHAR(16) NOT NULL COMMENT '病例类型：SUSPECTED疑似/CONFIRMED确诊',
    patient_id        VARCHAR(64) NOT NULL COMMENT '患者证件号',
    patient_name      VARCHAR(64) NOT NULL COMMENT '患者姓名',
    age               INT COMMENT '年龄（岁），空值未知',
    gender            VARCHAR(16) COMMENT '性别，空值未知',
    onset_date        DATE NOT NULL COMMENT '发病日期（UTC口径）',
    diagnosis_time    TIMESTAMP NOT NULL COMMENT '诊断时刻(UTC)，报告时限起算点',
    institution_code  VARCHAR(32) NOT NULL COMMENT '报告医疗机构编码',
    reporter          VARCHAR(64) NOT NULL COMMENT '报告人',
    address           VARCHAR(256) COMMENT '现住址（合成），空值表示未填写',
    phone             VARCHAR(32) COMMENT '联系电话（合成），空值表示未填写',
    late              BOOLEAN NOT NULL COMMENT '是否迟报：提交时刻晚于诊断时刻+时限为true，恰在终点为false',
    late_reason       VARCHAR(512) COMMENT '迟报原因；迟报时强制非空，及时为NULL',
    request_id        VARCHAR(64) NOT NULL COMMENT '产生该版本的幂等键',
    submitted_at      TIMESTAMP NOT NULL COMMENT '提交时刻(UTC)',
    CONSTRAINT uk_version UNIQUE (card_no, version_no)
);

-- 审核轨迹（不可变，提交/审核/作废/撤回/归档/特殊订正确认均留痕）
CREATE TABLE IF NOT EXISTS audit_trail (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    card_no     VARCHAR(40) NOT NULL COMMENT '卡片编号',
    version_no  INT NOT NULL COMMENT '轨迹发生时版本号',
    action      VARCHAR(32) NOT NULL COMMENT '动作：SUBMIT/REVIEW_APPROVED/REVIEW_VOIDED/WITHDRAWN/ARCHIVED/SPECIAL_STARTED/SPECIAL_FIRST_CONFIRMED/SPECIAL_SECOND_CONFIRMED/CORRECTED',
    operator    VARCHAR(64) NOT NULL COMMENT '操作人（审核员/报告人）',
    detail      VARCHAR(1024) COMMENT '动作说明，空值表示无补充说明',
    created_at  TIMESTAMP NOT NULL COMMENT '发生时刻(UTC)'
);
CREATE INDEX IF NOT EXISTS idx_audit_card ON audit_trail (card_no, id);

-- 归档后特殊订正流程（双人不同审核员先后确认）
CREATE TABLE IF NOT EXISTS special_correction (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键（流程编号）',
    card_no            VARCHAR(40) NOT NULL COMMENT '卡片编号',
    request_id         VARCHAR(64) NOT NULL COMMENT '发起特殊订正的幂等键',
    disease_code       VARCHAR(32) COMMENT '订正后病种编码，NULL表示沿用',
    case_type          VARCHAR(16) COMMENT '订正后病例类型，NULL表示沿用',
    diagnosis_time     TIMESTAMP COMMENT '订正后诊断时刻(UTC)，NULL表示沿用',
    reason             VARCHAR(512) NOT NULL COMMENT '疫情核实结论/变更原因',
    initiator          VARCHAR(64) NOT NULL COMMENT '发起人',
    first_reviewer     VARCHAR(64) COMMENT '第一名审核员；未确认NULL',
    first_at           TIMESTAMP COMMENT '第一名确认时刻(UTC)；未确认NULL',
    second_reviewer    VARCHAR(64) COMMENT '第二名审核员（须与第一人不同）',
    second_at          TIMESTAMP COMMENT '第二名确认并生效时刻(UTC)',
    status             VARCHAR(20) NOT NULL COMMENT '流程状态：WAITING_SECOND单人已确认待第二人/EFFECTIVE双人确认已生效',
    effective_version  INT COMMENT '生效后生成的新版本号；未生效NULL',
    created_at         TIMESTAMP NOT NULL COMMENT '创建时刻(UTC)',
    CONSTRAINT uk_special_request UNIQUE (request_id)
);

-- 幂等记录：指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id    VARCHAR(64) NOT NULL COMMENT '幂等键',
    scope         VARCHAR(16) NOT NULL COMMENT '场景：SUBMIT初报/CORRECTION订正/SPECIAL特殊订正发起',
    fingerprint   VARCHAR(64) NOT NULL COMMENT '请求参数SHA-256指纹（含requestId）',
    card_no       VARCHAR(40) NOT NULL COMMENT '首次成功请求关联卡片编号',
    version_no    INT COMMENT '首次成功请求产生的版本号（特殊订正发起时为当前版本）',
    response_json CLOB NOT NULL COMMENT '首次成功响应快照，重放原样返回',
    created_at    TIMESTAMP NOT NULL COMMENT '首次请求时刻(UTC)',
    CONSTRAINT pk_idempotency PRIMARY KEY (request_id, scope)
);
