-- 潜水作业服务建表脚本（H2 MySQL 兼容模式）。
-- 所有 *_at 时刻均为 UTC 纪元秒（BIGINT），时长为秒，速率单位 m/min。

-- 合成减压表：深度 -> 免减压极限（NDL，水底停留分钟），恰等于极限合法。
CREATE TABLE IF NOT EXISTS deco_table (
    depth_m           INT PRIMARY KEY,
    ndl_minutes       INT NOT NULL,
    safety_stop       BOOLEAN NOT NULL,
    comment           VARCHAR(200)
);

-- 幂等请求台账：requestId 全局唯一，指纹覆盖 requestId 与全部业务字段。
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id        VARCHAR(64) PRIMARY KEY,
    request_kind      VARCHAR(32) NOT NULL,
    fingerprint       VARCHAR(64) NOT NULL,
    http_status       INT NOT NULL,
    response_json     CLOB NOT NULL,
    created_at        BIGINT NOT NULL
);

-- 潜次主表。
CREATE TABLE IF NOT EXISTS dive_operation (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_no        VARCHAR(40) NOT NULL UNIQUE,
    application_request VARCHAR(64) NOT NULL UNIQUE,
    diver_a             VARCHAR(64) NOT NULL,
    diver_b             VARCHAR(64) NOT NULL,
    group_key           VARCHAR(140) NOT NULL,
    depth_m             INT NOT NULL,
    bottom_minutes      INT NOT NULL,
    status              VARCHAR(16) NOT NULL,
    plan_version        INT NOT NULL,
    exit_at             BIGINT,
    observation_due_at  BIGINT,
    chamber_at          BIGINT,
    closed_at           BIGINT,
    created_at          BIGINT NOT NULL
);
COMMENT ON COLUMN dive_operation.status IS 'PLANNED/EXECUTING/OBSERVING/CHAMBER/CLOSED';
COMMENT ON COLUMN dive_operation.exit_at IS '出水时刻 UTC 纪元秒；NULL 表示尚未出水';
COMMENT ON COLUMN dive_operation.observation_due_at IS '观察期满时刻=exit_at+7200 秒，NULL 表示尚未出水';
COMMENT ON COLUMN dive_operation.chamber_at IS '启动加压舱预案时刻 UTC 纪元秒；NULL 表示未启动';
COMMENT ON COLUMN dive_operation.closed_at IS '结案时刻 UTC 纪元秒；NULL 表示未结案';

-- 减压方案版本：每次方案变更新增版本，旧版本 active=0。
CREATE TABLE IF NOT EXISTS dive_plan (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_id    BIGINT NOT NULL,
    version         INT NOT NULL,
    depth_m         INT NOT NULL,
    bottom_minutes  INT NOT NULL,
    ndl_minutes     INT NOT NULL,
    at_limit        BOOLEAN NOT NULL,
    profile_json    CLOB NOT NULL,
    active          BOOLEAN NOT NULL,
    created_at      BIGINT NOT NULL,
    CONSTRAINT uk_plan_version UNIQUE (operation_id, version)
);

-- 水下记录（分段）。pair_state：PENDING 待配对（不生效）/EFFECTIVE 成对生效/FROZEN 方案变更冻结。
CREATE TABLE IF NOT EXISTS dive_segment (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_id      BIGINT NOT NULL,
    plan_version      INT NOT NULL,
    request_id        VARCHAR(64) NOT NULL,
    diver_id          VARCHAR(64) NOT NULL,
    phase             VARCHAR(16) NOT NULL,
    start_depth_m     INT NOT NULL,
    end_depth_m       INT NOT NULL,
    started_at        BIGINT NOT NULL,
    ended_at          BIGINT NOT NULL,
    duration_seconds  BIGINT NOT NULL,
    rate_m_per_min    DECIMAL(12,4),
    rate_alarm        BOOLEAN NOT NULL,
    pair_state        VARCHAR(16) NOT NULL,
    pair_key          VARCHAR(160) NOT NULL,
    pair_group_id     BIGINT,
    frozen            BOOLEAN NOT NULL,
    created_at        BIGINT NOT NULL
);
COMMENT ON COLUMN dive_segment.rate_m_per_min IS 'DESCEND/ASCEND 速率 m/min，BOTTOM 段为 NULL；>18 告警，=18 合法';
COMMENT ON COLUMN dive_segment.pair_state IS 'PENDING=单人未生效；EFFECTIVE=两人成对生效；FROZEN=方案变更冻结';
COMMENT ON COLUMN dive_segment.pair_group_id IS '成对生效后两条记录共享的配对组 id';

CREATE UNIQUE INDEX uk_segment_diver_span
    ON dive_segment (operation_id, diver_id, phase, started_at, ended_at);
CREATE INDEX idx_segment_pair ON dive_segment (operation_id, pair_state, pair_key);

-- 成对生效记录的配对组。
CREATE TABLE IF NOT EXISTS segment_pair (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_id      BIGINT NOT NULL,
    pair_key          VARCHAR(160) NOT NULL,
    plan_version      INT NOT NULL,
    duration_seconds  BIGINT NOT NULL,
    created_at        BIGINT NOT NULL
);

-- 潜水员水下时长台账：仅 EFFECTIVE 记录入账；方案冻结时随冻结记录原子删除。
CREATE TABLE IF NOT EXISTS diver_time_ledger (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    diver_id          VARCHAR(64) NOT NULL,
    operation_id      BIGINT NOT NULL,
    pair_group_id     BIGINT NOT NULL,
    started_at        BIGINT NOT NULL,
    ended_at          BIGINT NOT NULL,
    duration_seconds  BIGINT NOT NULL,
    created_at        BIGINT NOT NULL
);
CREATE INDEX idx_ledger_diver_time ON diver_time_ledger (diver_id, started_at, ended_at);

-- 进行中潜次占组表：PLANNED/EXECUTING/OBSERVING/CHAMBER 期间占组，结案释放，保证同组同时刻唯一受理。
CREATE TABLE IF NOT EXISTS active_dive_group (
    group_key     VARCHAR(140) PRIMARY KEY,
    operation_id  BIGINT NOT NULL,
    operation_no  VARCHAR(40) NOT NULL,
    occupied_at   BIGINT NOT NULL
);

-- 观察期体征记录。late=1 表示结案后迟到数据，仅登记事件、不改变状态。
CREATE TABLE IF NOT EXISTS vitals_record (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_id  BIGINT NOT NULL,
    request_id    VARCHAR(64) NOT NULL UNIQUE,
    diver_id      VARCHAR(64) NOT NULL,
    observed_at   BIGINT NOT NULL,
    symptomatic   BOOLEAN NOT NULL,
    symptoms      VARCHAR(500),
    late          BOOLEAN NOT NULL,
    created_at    BIGINT NOT NULL
);

-- 配对串行锁：同一 pair_key 的并发提交在 MERGE 行锁上排队，保证两人配对唯一成组。
CREATE TABLE IF NOT EXISTS pair_lock (
    pair_key  VARCHAR(160) PRIMARY KEY
);

-- 事件审计：速率告警、方案变更、加压舱预案、迟到体征、结案等。
CREATE TABLE IF NOT EXISTS dive_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_id  BIGINT,
    event_type    VARCHAR(40) NOT NULL,
    detail        VARCHAR(1000),
    created_at    BIGINT NOT NULL
);
CREATE INDEX idx_event_op ON dive_event (operation_id, id);
