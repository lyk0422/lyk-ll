-- 药物警戒个例管理服务建表脚本（H2 MODE=MySQL 兼容；IF NOT EXISTS 保证重复初始化安全）

-- 个例主表：同一患者＋同一药品＋同一不良反应事件仅允许一个活动个例
CREATE TABLE IF NOT EXISTS pv_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(40) NOT NULL,
    patient_code VARCHAR(64) NOT NULL COMMENT '合成患者唯一标识',
    patient_name VARCHAR(128) NOT NULL COMMENT '合成患者姓名',
    drug_code VARCHAR(64) NOT NULL COMMENT '合成药品编码',
    drug_name VARCHAR(255) NOT NULL COMMENT '合成药品名称',
    reaction_event VARCHAR(255) NOT NULL COMMENT '不良反应事件标准名称，去重键之一',
    seriousness VARCHAR(32) NOT NULL COMMENT '严重程度：FATAL=致死，LIFE_THREATENING=危及生命，OTHER_SERIOUS=其他严重',
    status VARCHAR(16) NOT NULL COMMENT '状态：REPORTED=已初报（活动，含随访中），CLOSED=已关闭（终态）',
    awareness_date DATE NOT NULL COMMENT '获知日 T0，按 UTC 日界',
    initial_submitted_at DATETIME(3) NOT NULL COMMENT '初报提交时刻（UTC）',
    initial_due_date DATE NOT NULL COMMENT '初报期限日（UTC），致死/危及生命=T0+6日，其他严重=T0+14日',
    on_time BOOLEAN NOT NULL COMMENT '是否及时：提交时刻不晚于期限日23:59:59(UTC)为true',
    late_reason VARCHAR(500) COMMENT '迟报原因，迟报(on_time=false)时强制非空；及时为NULL',
    latest_follow_up_date DATE COMMENT '最近随访日（UTC 日界），无随访为NULL',
    follow_up_count INT NOT NULL DEFAULT 0 COMMENT '并入信息条数：含随访与重复初报按随访并入，初报本身不计',
    related_case_no VARCHAR(40) COMMENT '关联原个例编号：关闭后新建个例时引用，否则NULL',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    -- 活动去重生成列：活动个例取 患者|药品|事件 组合键，关闭个例为NULL（UNIQUE 允许多个NULL）
    active_dedup VARCHAR(400) GENERATED ALWAYS AS
        (CASE WHEN status = 'CLOSED' THEN NULL
              ELSE CONCAT(patient_code, '|', drug_code, '|', reaction_event) END),
    CONSTRAINT uk_pv_case_no UNIQUE (case_no),
    CONSTRAINT uk_pv_case_active_dedup UNIQUE (active_dedup)
);

-- 提交幂等表：初报与随访共用，指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS pv_submission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等键',
    request_kind VARCHAR(16) NOT NULL COMMENT 'INITIAL=初报，FOLLOW_UP=随访',
    request_fingerprint VARCHAR(64) NOT NULL COMMENT 'SHA-256指纹，覆盖requestId与全部业务字段',
    case_no VARCHAR(40) COMMENT '所属个例编号；占位记录在业务完成前为NULL',
    version_no INT COMMENT '版本号：初报为1，每次随访递增；占位记录为NULL',
    merged_duplicate BOOLEAN NOT NULL COMMENT '初报请求是否因活动个例重复而按随访并入',
    response_json CLOB COMMENT '首次成功响应的JSON快照',
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_pv_submission_request UNIQUE (request_id)
);

-- 随访记录表（初报本身不入此表；重复初报并入与随访均写入）
CREATE TABLE IF NOT EXISTS pv_follow_up (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(40) NOT NULL,
    version_no INT NOT NULL COMMENT '个例内递增版本号，初报=1，首条随访=2',
    follow_up_date DATE NOT NULL COMMENT '随访信息日（UTC 日界）',
    info_text VARCHAR(2000) NOT NULL COMMENT '随访/并入信息内容',
    reporter_code VARCHAR(64) COMMENT '合成报告人编码；重复初报并入时为空',
    reporter_name VARCHAR(128) COMMENT '合成报告人姓名',
    source_request_id VARCHAR(64) NOT NULL COMMENT '来源请求requestId（随访或重复初报并入）',
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_pv_follow_up_version UNIQUE (case_no, version_no)
);

-- 监管递交批次
CREATE TABLE IF NOT EXISTS pv_submission_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(40) NOT NULL COMMENT '唯一递交批次号',
    request_id VARCHAR(64) NOT NULL COMMENT '递交请求幂等键',
    request_fingerprint VARCHAR(64) NOT NULL COMMENT 'SHA-256指纹，覆盖requestId与纳入个例编号集合',
    case_count INT NOT NULL COMMENT '批次内个例数量，快照固定',
    created_at DATETIME(3) NOT NULL COMMENT '批次提交确认时刻（UTC）',
    CONSTRAINT uk_pv_batch_no UNIQUE (batch_no),
    CONSTRAINT uk_pv_batch_request UNIQUE (request_id)
);

-- 批次明细：提交时刻已确认个例的内容快照，迟到随访不改变既有批次
CREATE TABLE IF NOT EXISTS pv_batch_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(40) NOT NULL,
    case_no VARCHAR(40) NOT NULL COMMENT '快照时个例编号',
    version_no INT NOT NULL COMMENT '快照时个例最新版本号',
    snapshot_json CLOB NOT NULL COMMENT '个例状态与时限字段的JSON快照',
    ordinal INT NOT NULL COMMENT '批次内顺序，按个例编号排序固定',
    CONSTRAINT uk_pv_batch_item UNIQUE (batch_no, case_no)
);
