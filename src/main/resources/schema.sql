-- 集装箱残损检验与修理放行服务：H2（MODE=MySQL）建表脚本
-- 所有时间字段均为 BIGINT，存 UTC 纪元毫秒；金额 DECIMAL(18,2)，单位由业务字段注释说明。

CREATE TABLE IF NOT EXISTS iicl_code (
    code               VARCHAR(16)  NOT NULL,
    description        VARCHAR(128) NOT NULL,
    default_repair_code VARCHAR(16) NOT NULL,
    CONSTRAINT pk_iicl_code PRIMARY KEY (code)
);
COMMENT ON TABLE  iicl_code IS '合成 IICL 风格残损代码表（仅合成数据）';
COMMENT ON COLUMN iicl_code.code IS '残损代码，如 IICL-2010';
COMMENT ON COLUMN iicl_code.description IS '残损描述（中文合成数据）';
COMMENT ON COLUMN iicl_code.default_repair_code IS '默认修理项编码，关联 repair_item.repair_code';

CREATE TABLE IF NOT EXISTS repair_item (
    repair_code  VARCHAR(16)     NOT NULL,
    name         VARCHAR(128)    NOT NULL,
    unit         VARCHAR(16)     NOT NULL,
    unit_cost    DECIMAL(18, 2)  NOT NULL,
    CONSTRAINT pk_repair_item PRIMARY KEY (repair_code)
);
COMMENT ON TABLE  repair_item IS '合成修理项目价目表';
COMMENT ON COLUMN repair_item.repair_code IS '修理项编码';
COMMENT ON COLUMN repair_item.name IS '修理项名称';
COMMENT ON COLUMN repair_item.unit IS '计量单位，如 处/米/件';
COMMENT ON COLUMN repair_item.unit_cost IS '单价（元/计量单位）';

CREATE TABLE IF NOT EXISTS damage_case (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    case_no              VARCHAR(40)  NOT NULL,
    container_no         VARCHAR(20)  NOT NULL,
    container_value      DECIMAL(18, 2) NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    active_flag          VARCHAR(20),
    current_estimate_id  BIGINT,
    approved_at_utc      BIGINT,
    valid_until_utc      BIGINT,
    repair_attempts      INT          NOT NULL DEFAULT 0,
    closed_at_utc        BIGINT,
    close_outcome        VARCHAR(32),
    created_at_utc       BIGINT       NOT NULL,
    updated_at_utc       BIGINT       NOT NULL,
    CONSTRAINT pk_damage_case PRIMARY KEY (id),
    CONSTRAINT uk_damage_case_no UNIQUE (case_no),
    CONSTRAINT uk_damage_case_active UNIQUE (active_flag)
);
COMMENT ON TABLE  damage_case IS '残损案主表；active_flag=在办箱号（结案置 NULL），同箱同时刻仅一个在办案';
COMMENT ON COLUMN damage_case.case_no IS '残损案编号，服务端生成';
COMMENT ON COLUMN damage_case.container_no IS '集装箱箱号';
COMMENT ON COLUMN damage_case.container_value IS '箱值（元），估价 30% 阈值基数';
COMMENT ON COLUMN damage_case.status IS '状态 REGISTERED/SURVEYED/ESTIMATED/REPAIR_APPROVED/REPAIR_COMPLETED/REWORK_REQUIRED/RELEASED/TOTAL_LOSS';
COMMENT ON COLUMN damage_case.active_flag IS '在办标志=箱号；结案为 NULL；UNIQUE 多 NULL 不冲突';
COMMENT ON COLUMN damage_case.current_estimate_id IS '当前估价版本 ID';
COMMENT ON COLUMN damage_case.approved_at_utc IS '最近一次修理批复时刻（UTC 毫秒）；无批复为 NULL';
COMMENT ON COLUMN damage_case.valid_until_utc IS '批复有效期截止（UTC 毫秒）=批复时刻+7个自然日';
COMMENT ON COLUMN damage_case.repair_attempts IS '已受理修理次数：首次修理+返修，最多 2';
COMMENT ON COLUMN damage_case.closed_at_utc IS '结案时刻（UTC 毫秒）；未结案 NULL';
COMMENT ON COLUMN damage_case.close_outcome IS '结案结果 RELEASED(放行)/TOTAL_LOSS(全损)';

CREATE TABLE IF NOT EXISTS survey (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    case_id       BIGINT      NOT NULL,
    request_id    VARCHAR(64) NOT NULL,
    surveyor      VARCHAR(64) NOT NULL,
    surveyed_at_utc BIGINT    NOT NULL,
    late_event    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at_utc BIGINT     NOT NULL,
    CONSTRAINT pk_survey PRIMARY KEY (id),
    CONSTRAINT uk_survey_request UNIQUE (request_id)
);
COMMENT ON TABLE survey IS '检验记录；结案后到达的检验记 late_event=TRUE';
COMMENT ON COLUMN survey.surveyed_at_utc IS '检验发生时刻（UTC 毫秒）';
COMMENT ON COLUMN survey.late_event IS 'TRUE=结案后迟到检验，仅记事件不影响案件';

CREATE TABLE IF NOT EXISTS damage_item (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    case_id          BIGINT      NOT NULL,
    survey_id        BIGINT      NOT NULL,
    code             VARCHAR(16) NOT NULL,
    location         VARCHAR(64) NOT NULL,
    responsibility   VARCHAR(16) NOT NULL,
    repair_code      VARCHAR(16) NOT NULL,
    repair_name      VARCHAR(128) NOT NULL,
    unit_cost        DECIMAL(18, 2) NOT NULL,
    quantity         INT         NOT NULL,
    amount           DECIMAL(18, 2) NOT NULL,
    created_at_utc   BIGINT      NOT NULL,
    CONSTRAINT pk_damage_item PRIMARY KEY (id)
);
COMMENT ON TABLE damage_item IS '残损清单明细（按合成 IICL 代码登记）';
COMMENT ON COLUMN damage_item.location IS '残损位置描述';
COMMENT ON COLUMN damage_item.responsibility IS '责任方 OWNER(箱东)/TERMINAL(码头)';
COMMENT ON COLUMN damage_item.unit_cost IS '修理单价（元）';
COMMENT ON COLUMN damage_item.quantity IS '数量（计量单位见 repair_item.unit）';
COMMENT ON COLUMN damage_item.amount IS '单项修理费=单价*数量（元）';

CREATE TABLE IF NOT EXISTS estimate_version (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    case_id          BIGINT      NOT NULL,
    version_no       INT         NOT NULL,
    request_id       VARCHAR(64) NOT NULL,
    subtotal_owner   DECIMAL(18, 2) NOT NULL,
    subtotal_terminal DECIMAL(18, 2) NOT NULL,
    total_amount     DECIMAL(18, 2) NOT NULL,
    max_item_amount  DECIMAL(18, 2) NOT NULL,
    ratio            DECIMAL(9, 6) NOT NULL,
    decision         VARCHAR(16) NOT NULL,
    created_at_utc   BIGINT      NOT NULL,
    CONSTRAINT pk_estimate_version PRIMARY KEY (id),
    CONSTRAINT uk_estimate_request UNIQUE (request_id),
    CONSTRAINT uk_estimate_version UNIQUE (case_id, version_no)
);
COMMENT ON TABLE estimate_version IS '估价版本；逾期重新估价生成新版本，旧版本保留';
COMMENT ON COLUMN estimate_version.version_no IS '同一案件内递增的估价版本号';
COMMENT ON COLUMN estimate_version.subtotal_owner IS '箱东责任修理费小计（元）';
COMMENT ON COLUMN estimate_version.subtotal_terminal IS '码头责任修理费小计（元）';
COMMENT ON COLUMN estimate_version.total_amount IS '修理费总额（元）';
COMMENT ON COLUMN estimate_version.max_item_amount IS '最大单项修理费（元），全损判定基数';
COMMENT ON COLUMN estimate_version.ratio IS '最大单项修理费/箱值，恰 0.300000 修理，>0.300000 全损';
COMMENT ON COLUMN estimate_version.decision IS 'REPAIR(修理)/TOTAL_LOSS(全损)，唯一判定';

CREATE TABLE IF NOT EXISTS estimate_item (
    estimate_id   BIGINT      NOT NULL,
    item_id       BIGINT      NOT NULL,
    amount        DECIMAL(18, 2) NOT NULL,
    CONSTRAINT pk_estimate_item PRIMARY KEY (estimate_id, item_id)
);
COMMENT ON TABLE estimate_item IS '估价版本残损项快照，支撑版本复算';

CREATE TABLE IF NOT EXISTS repair_cycle (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    case_id          BIGINT      NOT NULL,
    attempt_no       INT         NOT NULL,
    estimate_id      BIGINT      NOT NULL,
    approve_request_id VARCHAR(64) NOT NULL,
    approved_at_utc  BIGINT      NOT NULL,
    valid_until_utc  BIGINT      NOT NULL,
    complete_request_id VARCHAR(64),
    completed_at_utc BIGINT,
    reinspect_request_id VARCHAR(64),
    reinspected_at_utc BIGINT,
    reinspect_result VARCHAR(16),
    status           VARCHAR(16) NOT NULL,
    created_at_utc   BIGINT      NOT NULL,
    CONSTRAINT pk_repair_cycle PRIMARY KEY (id),
    CONSTRAINT uk_repair_attempt UNIQUE (case_id, attempt_no)
);
COMMENT ON TABLE repair_cycle IS '修理链：每次批复/完工/复检为一环；attempt_no 1=首次修理，2=唯一一次返修';
COMMENT ON COLUMN repair_cycle.valid_until_utc IS '批复有效期截止（UTC 毫秒），恰第7日完工有效';
COMMENT ON COLUMN repair_cycle.reinspect_result IS 'PASS(合格)/FAIL(不合格)；未复检 NULL';
COMMENT ON COLUMN repair_cycle.status IS 'APPROVED/COMPLETED/PASSED/FAILED/EXPIRED';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64)  NOT NULL,
    operation     VARCHAR(48)  NOT NULL,
    fingerprint   CHAR(64)     NOT NULL,
    case_no       VARCHAR(40),
    http_status   INT          NOT NULL,
    response_json CLOB         NOT NULL,
    created_at_utc BIGINT      NOT NULL,
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);
COMMENT ON TABLE idempotent_request IS 'requestId 幂等表：指纹=SHA-256(requestId+全部业务字段)';
COMMENT ON COLUMN idempotent_request.fingerprint IS '业务指纹十六进制；同键异参判 409';

CREATE TABLE IF NOT EXISTS case_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    case_id       BIGINT       NOT NULL,
    event_type    VARCHAR(32)  NOT NULL,
    payload       CLOB,
    created_at_utc BIGINT      NOT NULL,
    CONSTRAINT pk_case_event PRIMARY KEY (id)
);
COMMENT ON TABLE case_event IS '案件事件流水；结案后迟到的检验照片等只在此登记';
