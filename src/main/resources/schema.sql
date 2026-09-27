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
    score_controlled    BOOLEAN     NOT NULL DEFAULT FALSE COMMENT 'TRUE 表示该版本由反复潜水积分受控方案生成（虚构积分模型，不用于真实减压）；FALSE 为旧语义方案',
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
    operation           VARCHAR(24) NOT NULL COMMENT '操作类型：APPLY/PLAN/RECORD/SURFACE/SYMPTOM/CLOSE/CONTROLLED_PLAN/CORRECT',
    fingerprint         VARCHAR(64) NOT NULL COMMENT '请求指纹（含 requestId 与全部业务字段）',
    status_code         INT         NOT NULL COMMENT '首次响应 HTTP 状态码',
    response_json       TEXT        NOT NULL COMMENT '首次响应体，重放原样返回',
    created_at_utc      DATETIME(3) NOT NULL,
    CONSTRAINT pk_idempotency PRIMARY KEY (request_id)
);

-- ===== 反复潜水虚构积分控制（第二题新增；软件验证模型，不用于真实潜水减压） =====

-- 潜水员积分账户（每名潜水员一行；首次潜之前可视为积分 0、无出水记录）
CREATE TABLE IF NOT EXISTS diver_score_account (
    diver_code            VARCHAR(32) NOT NULL COMMENT '潜水员工号',
    version               INT         NOT NULL DEFAULT 1 COMMENT '账户版本，从 1 开始，每次成功结算 +1；方案携带的 expectedVersion 与此比较',
    residual_score        INT         NOT NULL DEFAULT 0 COMMENT '最近一次已确认出水的残留积分（0-100，含端点）：本次整数水下分钟 + 下潜前积分，上限 100',
    last_surfaced_at_utc  DATETIME(3) COMMENT '最近一次已确认出水时刻（UTC）；空值表示从未受控出水',
    last_dive_request_id  VARCHAR(64) COMMENT '最近一次已确认出水的来源潜次 requestId；空值表示尚无',
    active_dive_request_id VARCHAR(64) COMMENT '当前占用该潜水员的受控潜次；空值表示空闲。并发唯一性由 diver_dive_slot 的主键保证（两组员同潜次时该列同值，故本表不加唯一索引）',
    created_at_utc        DATETIME(3) NOT NULL,
    updated_at_utc        DATETIME(3) NOT NULL,
    CONSTRAINT pk_diver_score PRIMARY KEY (diver_code)
);

-- 潜水员受控下潜占用槽：每名潜水员至多一行；两组共享同一潜水员并发下潜时由主键唯一受理
CREATE TABLE IF NOT EXISTS diver_dive_slot (
    diver_code        VARCHAR(32) NOT NULL COMMENT '潜水员工号',
    dive_request_id   VARCHAR(64) NOT NULL COMMENT '占用该潜水员的受控潜次 requestId',
    occupied_at_utc   DATETIME(3) NOT NULL COMMENT '占用（方案通过门禁）时刻（UTC）',
    CONSTRAINT pk_diver_dive_slot PRIMARY KEY (diver_code)
);

-- 受控方案冻结快照：每生成一版受控方案冻结每名组员的账户版本、出水时刻与积分
CREATE TABLE IF NOT EXISTS controlled_plan (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    dive_request_id       VARCHAR(64) NOT NULL COMMENT '所属潜次 requestId',
    plan_version          INT         NOT NULL COMMENT '对应 dive_plan.version',
    plan_request_id       VARCHAR(64) NOT NULL COMMENT '生成该版受控方案的操作 requestId（幂等键）',
    at_utc                DATETIME(3) NOT NULL COMMENT '方案计算所用的精确时刻（UTC），按此派生当前积分与间隔',
    team_allowed_min      INT         NOT NULL COMMENT '两人允许分钟数取较短值；请求底时间恰用尽合法',
    created_at_utc        DATETIME(3) NOT NULL,
    CONSTRAINT uk_controlled_plan_dive_version UNIQUE (dive_request_id, plan_version)
);

-- 受控方案中每名潜水员的冻结项
CREATE TABLE IF NOT EXISTS controlled_plan_diver (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    dive_request_id          VARCHAR(64) NOT NULL COMMENT '所属潜次 requestId',
    plan_version             INT         NOT NULL COMMENT '对应 dive_plan.version',
    diver_code               VARCHAR(32) NOT NULL COMMENT '组员工号',
    expected_account_version INT         NOT NULL COMMENT '方案冻结的账户版本',
    last_surfaced_at_utc     DATETIME(3) COMMENT '方案冻结的最近出水时刻（UTC）；首次潜为空',
    residual_score           INT         NOT NULL COMMENT '方案冻结的出水残留积分（首次潜为 0）',
    current_score            INT         NOT NULL COMMENT '按 at 时刻与冻结出水时刻派生的当前积分',
    surface_minutes          BIGINT      NOT NULL COMMENT 'at 距冻结出水时刻的整分钟数；首次潜记为 0',
    allowed_min              INT         NOT NULL COMMENT '该潜水员允许分钟数 = 合成 NDL - 当前积分',
    ndl_minutes              INT         NOT NULL COMMENT '方案深度对应的合成免减压表分钟数',
    created_at_utc           DATETIME(3) NOT NULL,
    CONSTRAINT uk_controlled_diver UNIQUE (dive_request_id, plan_version, diver_code)
);

-- 历史水下记录更正：不追改已关闭潜次，另记独立差异
CREATE TABLE IF NOT EXISTS dive_record_correction (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    dive_request_id     VARCHAR(64) NOT NULL COMMENT '被更正的已关闭潜次 requestId',
    diver_code          VARCHAR(32) NOT NULL COMMENT '被更正记录的潜水员工号',
    old_bottom_min      INT         NOT NULL COMMENT '原记录水下分钟数（整数）',
    new_bottom_min      INT         NOT NULL COMMENT '更正后水下分钟数（正整数）',
    delta_min           INT         NOT NULL COMMENT '差异分钟数 = 新值 - 原值（可负）',
    request_id          VARCHAR(64) NOT NULL COMMENT '本次更正操作的幂等键',
    corrected_at_utc    DATETIME(3) NOT NULL COMMENT '更正受理时刻（UTC）',
    created_at_utc      DATETIME(3) NOT NULL,
    CONSTRAINT pk_correction PRIMARY KEY (id),
    CONSTRAINT uk_correction_request UNIQUE (request_id)
);

-- 受控出水结算的积分台账（与 bottom_time_ledger 并存；仅受控成对 BOTTOM 在出水原子更新）
CREATE TABLE IF NOT EXISTS score_settlement (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    diver_code          VARCHAR(32) NOT NULL COMMENT '结算潜水员工号',
    dive_request_id     VARCHAR(64) NOT NULL COMMENT '来源受控潜次',
    plan_version        INT         NOT NULL COMMENT '实际下潜消费的方案版本',
    pre_dive_score      INT         NOT NULL COMMENT '下潜前当前积分',
    bottom_min          INT         NOT NULL COMMENT '本次整数水下分钟数',
    residual_score      INT         NOT NULL COMMENT '出水残留积分 = 下潜前积分 + 本次分钟，上限 100',
    last_surfaced_at_utc DATETIME(3) NOT NULL COMMENT '本次出水时刻（UTC）',
    settled_at_utc      DATETIME(3) NOT NULL,
    CONSTRAINT uk_score_settlement UNIQUE (dive_request_id, diver_code)
);
