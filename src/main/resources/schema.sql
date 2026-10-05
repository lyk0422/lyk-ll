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
    exposure_site_code VARCHAR(32) COMMENT '归并到的暴露场所编码；未匹配场所为NULL，不参与聚集归并',
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

-- 合成暴露场所库：报告卡按场所编码或地址关键词自动归并
CREATE TABLE IF NOT EXISTS exposure_site (
    code            VARCHAR(32) PRIMARY KEY COMMENT '暴露场所编码',
    name            VARCHAR(128) NOT NULL COMMENT '暴露场所名称（合成）',
    address_keyword VARCHAR(128) NOT NULL COMMENT '地址匹配关键词：报告卡现住址包含该词即归入本场所'
);

-- 聚集事件：同一场所72小时(UTC)内3例及以上报告触发，恰3例即生成
CREATE TABLE IF NOT EXISTS cluster_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    event_no      VARCHAR(40) NOT NULL COMMENT '聚集事件编号（业务主键）',
    site_code     VARCHAR(32) NOT NULL COMMENT '暴露场所编码',
    status        VARCHAR(16) NOT NULL COMMENT '状态：OPEN进行中/CONFIRMED协查确认/EXCLUDED协查排除/CLOSED已关闭(终态)',
    first_case_at TIMESTAMP NOT NULL COMMENT '首例报告时刻(UTC)',
    last_case_at  TIMESTAMP NOT NULL COMMENT '末例报告时刻(UTC)，随新卡归入推进',
    card_count    INT NOT NULL COMMENT '涉及报告卡数量',
    open_key      VARCHAR(32) COMMENT '未关闭事件占位键=场所编码，关闭置NULL；唯一约束保证同场所同时刻只有一个未关闭事件',
    created_at    TIMESTAMP NOT NULL COMMENT '事件生成时刻(UTC)',
    closed_at     TIMESTAMP COMMENT '关闭时刻(UTC)；未关闭NULL',
    CONSTRAINT uk_cluster_event_no UNIQUE (event_no),
    CONSTRAINT uk_cluster_open UNIQUE (open_key)
);

-- 聚集事件涉及卡号（不可变追加）
CREATE TABLE IF NOT EXISTS cluster_event_card (
    event_id    BIGINT NOT NULL COMMENT '聚集事件ID',
    card_no     VARCHAR(40) NOT NULL COMMENT '报告卡编号',
    attached_at TIMESTAMP NOT NULL COMMENT '归入时刻(UTC)',
    CONSTRAINT pk_cluster_card PRIMARY KEY (event_id, card_no)
);

-- 迟到记录：事件关闭后迟到的报告卡只记迟到事件，可另行归并成新事件
CREATE TABLE IF NOT EXISTS cluster_late_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    site_code       VARCHAR(32) NOT NULL COMMENT '暴露场所编码',
    card_no         VARCHAR(40) NOT NULL COMMENT '迟到的报告卡编号',
    closed_event_no VARCHAR(40) NOT NULL COMMENT '此前已关闭的聚集事件编号',
    recorded_at     TIMESTAMP NOT NULL COMMENT '记录时刻(UTC)'
);

-- 协查单：聚集事件生成时自动推送相关机构；同一事件同时刻只允许一张进行中协查单
CREATE TABLE IF NOT EXISTS investigation (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键（协查单号）',
    event_no          VARCHAR(40) NOT NULL COMMENT '所属聚集事件编号',
    request_id        VARCHAR(64) NOT NULL COMMENT '幂等键（自动生成时为 auto-事件编号）',
    status            VARCHAR(16) NOT NULL COMMENT '状态：OPEN进行中/CONCLUDED已结案',
    conclusion        VARCHAR(16) COMMENT '协查结论：CONFIRMED确认聚集/EXCLUDED排除；未结案NULL',
    note              VARCHAR(512) COMMENT '协查说明，空值表示无',
    creator           VARCHAR(64) NOT NULL COMMENT '发起人（自动生成为system）',
    operator          VARCHAR(64) COMMENT '结案操作人；未结案NULL',
    active_event_key  VARCHAR(40) COMMENT '进行中占位键=事件编号，结案置NULL；唯一约束保证同事件仅一张进行中协查单',
    created_at        TIMESTAMP NOT NULL COMMENT '生成时刻(UTC)',
    concluded_at      TIMESTAMP COMMENT '结案时刻(UTC)；未结案NULL',
    CONSTRAINT uk_investigation_request UNIQUE (request_id),
    CONSTRAINT uk_investigation_active UNIQUE (active_event_key)
);

-- 协查单推送机构（相关机构=涉及卡片的报告机构，或手工指定）
CREATE TABLE IF NOT EXISTS investigation_target (
    investigation_id BIGINT NOT NULL COMMENT '协查单号',
    institution_code VARCHAR(32) NOT NULL COMMENT '被推送机构编码',
    CONSTRAINT pk_investigation_target PRIMARY KEY (investigation_id, institution_code)
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
