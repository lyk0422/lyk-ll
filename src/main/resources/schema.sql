-- 潜水作业服务建表脚本（H2 MODE=MySQL 下执行；语法同时兼容 MySQL 8）
-- 时间列一律为 UTC，毫秒精度；空值表示该阶段尚未发生。

-- 潜水员（合成数据）
CREATE TABLE IF NOT EXISTS diver (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    diver_code  VARCHAR(32)  NOT NULL COMMENT '潜水员工号，合成数据',
    name        VARCHAR(64)  NOT NULL COMMENT '潜水员姓名，合成数据',
    created_at  DATETIME(3)  NOT NULL,
    CONSTRAINT uk_diver_code UNIQUE (diver_code)
);

-- 合成免减压极限表：深度 -> 免减压极限（NDL），单位米/分钟
CREATE TABLE IF NOT EXISTS deco_table_entry (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    depth_m         INT NOT NULL COMMENT '表定深度，米',
    ndl_minutes     INT NOT NULL COMMENT '该深度免减压极限，分钟；恰等于该值合法',
    CONSTRAINT uk_deco_depth UNIQUE (depth_m)
);

-- 潜次主表（一个作业申请对应一个潜次）
CREATE TABLE IF NOT EXISTS dive (
    request_id          VARCHAR(64)  NOT NULL COMMENT '作业申请 requestId，潜次主键',
    apply_fingerprint   VARCHAR(64)  NOT NULL COMMENT '申请参数指纹（SHA-256，含 requestId 与全部业务字段）',
    team_key            VARCHAR(140) NOT NULL COMMENT '潜水组规范化键：两名潜水员工号升序拼接',
    active_team_slot    VARCHAR(140) COMMENT '进行中占用槽：未结案为 team_key，结案置空；唯一索引保证同组并发唯一受理',
    diver_a_code        VARCHAR(32)  NOT NULL COMMENT '组员一工号',
    diver_b_code        VARCHAR(32)  NOT NULL COMMENT '组员二工号',
    planned_depth_m     INT          NOT NULL COMMENT '申请计划深度，米',
    planned_bottom_min  INT          NOT NULL COMMENT '申请计划水下（底）时间，分钟',
    status              VARCHAR(16)  NOT NULL COMMENT '状态：APPLIED/PLANNED/DIVING/SURFACED/CLOSED/FROZEN',
    current_plan_version INT         NOT NULL DEFAULT 0 COMMENT '当前减压方案版本号，0 表示尚未出方案',
    surfaced_at_utc     DATETIME(3)  COMMENT '出水时刻（UTC），观察期起点；空值表示尚未出水',
    symptomatic         BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '观察期是否出现症状：TRUE 即启动加压舱预案并冻结结案',
    closed_at_utc       DATETIME(3)  COMMENT '结案时刻（UTC），终态时间戳；空值表示未结案',
    created_at_utc      DATETIME(3)  NOT NULL COMMENT '申请受理时刻（UTC）',
    updated_at_utc      DATETIME(3)  NOT NULL,
    CONSTRAINT pk_dive PRIMARY KEY (request_id),
    CONSTRAINT uk_dive_active_team UNIQUE (active_team_slot)
);

-- 减压方案（可多版本；方案变更后旧版本 frozen=TRUE）
CREATE TABLE IF NOT EXISTS dive_plan (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    dive_request_id     VARCHAR(64) NOT NULL COMMENT '所属潜次申请 requestId',
    version             INT         NOT NULL COMMENT '方案版本，从 1 递增',
    depth_m             INT         NOT NULL COMMENT '方案深度，米',
    bottom_min          INT         NOT NULL COMMENT '方案底时间，分钟',
    ndl_limit_min       INT         NOT NULL COMMENT '查表得到的免减压极限，分钟',
    descent_rate_limit  INT         NOT NULL COMMENT '下潜速率上限 18，米/分钟',
    ascent_rate_limit   INT         NOT NULL COMMENT '出水速率上限 18，米/分钟',
    profile_json        TEXT        NOT NULL COMMENT '减压剖面 JSON：下潜/底时间/上升各段，可复算',
    frozen              BOOLEAN     NOT NULL DEFAULT FALSE COMMENT 'TRUE 表示该版本已被后续方案变更冻结',
    fingerprint         VARCHAR(64) NOT NULL COMMENT '出方案请求指纹（含 requestId 与全部业务字段）',
    created_at_utc      DATETIME(3) NOT NULL,
    CONSTRAINT uk_plan_version UNIQUE (dive_request_id, version)
);

-- 水下记录（每名潜水员每个方案版本每个阶段一条；单人记录 effective=FALSE）
CREATE TABLE IF NOT EXISTS dive_record (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    dive_request_id     VARCHAR(64) NOT NULL COMMENT '所属潜次',
    plan_version        INT         NOT NULL COMMENT '记录所依据的方案版本',
    diver_code          VARCHAR(32) NOT NULL COMMENT '提交记录的潜水员工号',
    phase               VARCHAR(16) NOT NULL COMMENT '阶段：DESCENT 下潜 / BOTTOM 水底 / ASCENT 上升',
    from_depth_m        INT         NOT NULL COMMENT '起始深度，米（DESCENT 为 0）',
    to_depth_m          INT         NOT NULL COMMENT '目标深度，米（ASCENT 为 0）',
    duration_min        INT         NOT NULL COMMENT '该段历时，分钟',
    rate_m_min          DECIMAL(6,2) NOT NULL COMMENT '实测速率，米/分钟=深度差/历时；底时间阶段为 0',
    effective           BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '两人成对后生效；单人记录 FALSE 不生效',
    frozen              BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '方案变更后旧记录冻结 TRUE，不再生效且不参与台账',
    paired_record_id    BIGINT      COMMENT '配对手的对应记录 id；未配对为空',
    event_time_utc      DATETIME(3) NOT NULL COMMENT '该段记录对应的作业时刻（UTC）',
    created_at_utc      DATETIME(3) NOT NULL,
    CONSTRAINT uk_record_phase UNIQUE (dive_request_id, plan_version, diver_code, phase)
);

-- 速率超速告警（成对生效时复算产生）
CREATE TABLE IF NOT EXISTS speed_alert (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    dive_request_id     VARCHAR(64) NOT NULL,
    plan_version        INT         NOT NULL COMMENT '告警对应的方案版本',
    diver_code          VARCHAR(32) NOT NULL COMMENT '超速潜水员工号',
    phase               VARCHAR(16) NOT NULL COMMENT 'DESCENT 或 ASCENT',
    rate_m_min          DECIMAL(6,2) NOT NULL COMMENT '实测速率，米/分钟；大于 18 才告警，恰 18 不告警',
    event_time_utc      DATETIME(3) NOT NULL,
    created_at_utc      DATETIME(3) NOT NULL
);

-- 潜水员累计水下时间台账（仅成对生效的 BOTTOM 记录入账；24 小时 UTC 滚动窗口复算）
CREATE TABLE IF NOT EXISTS bottom_time_ledger (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    diver_code          VARCHAR(32) NOT NULL COMMENT '潜水员工号',
    dive_request_id     VARCHAR(64) NOT NULL COMMENT '来源潜次',
    plan_version        INT         NOT NULL COMMENT '来源方案版本',
    bottom_min          INT         NOT NULL COMMENT '本次计入的水下时间，分钟',
    started_at_utc      DATETIME(3) NOT NULL COMMENT '水下时间归属时刻（UTC），24 小时窗口按此复算',
    CONSTRAINT uk_ledger_entry UNIQUE (dive_request_id, plan_version, diver_code)
);

-- 潜次事件流（加压舱预案、迟到体征、方案冻结等）
CREATE TABLE IF NOT EXISTS dive_event (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    dive_request_id     VARCHAR(64) NOT NULL,
    type                VARCHAR(24) NOT NULL COMMENT 'CHAMBER_PROTOCOL 加压舱预案 / LATE_VITALS 结案后迟到体征 / PLAN_FROZEN 旧方案冻结',
    payload             VARCHAR(512) COMMENT '事件说明与迟到数据摘要',
    created_at_utc      DATETIME(3) NOT NULL
);

-- 幂等台账：requestId 为键，指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64) NOT NULL COMMENT '操作幂等键 requestId',
    operation           VARCHAR(24) NOT NULL COMMENT '操作类型：APPLY/PLAN/RECORD/SURFACE/SYMPTOM/CLOSE',
    fingerprint         VARCHAR(64) NOT NULL COMMENT '请求指纹（含 requestId 与全部业务字段）',
    status_code         INT         NOT NULL COMMENT '首次响应 HTTP 状态码',
    response_json       TEXT        NOT NULL COMMENT '首次响应体，重放原样返回',
    created_at_utc      DATETIME(3) NOT NULL,
    CONSTRAINT pk_idempotency PRIMARY KEY (request_id)
);
