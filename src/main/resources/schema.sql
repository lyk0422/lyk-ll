-- 水泥窑替代燃料掺烧与排放联动服务：H2（MODE=MySQL）自动建表
-- 所有表 IF NOT EXISTS，数据仅在 JVM 生命周期内保留（合成数据）。

-- 合成危废经营许可库
CREATE TABLE IF NOT EXISTS hazardous_license (
    license_no   VARCHAR(64)  PRIMARY KEY,
    holder_name  VARCHAR(200) NOT NULL,
    valid_from   DATE         NOT NULL,
    valid_to     DATE         NOT NULL,
    revoked      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP(6) NOT NULL
);
COMMENT ON COLUMN hazardous_license.license_no IS '许可证编号（合成许可库主键）';
COMMENT ON COLUMN hazardous_license.holder_name IS '持证单位名称';
COMMENT ON COLUMN hazardous_license.valid_from IS '生效日期，含当日，时区 Asia/Shanghai';
COMMENT ON COLUMN hazardous_license.valid_to IS '截止日期，含当日；审核时刻晚于此日期即过期拒绝';
COMMENT ON COLUMN hazardous_license.revoked IS '是否被吊销：true 等同无效许可';
COMMENT ON COLUMN hazardous_license.created_at IS '录入时刻，Asia/Shanghai';

-- 掺烧方案主表
CREATE TABLE IF NOT EXISTS co_firing_plan (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    kiln_id              VARCHAR(64)  NOT NULL,
    license_no           VARCHAR(64)  NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    declared_heat_ratio  DECIMAL(6,4) NOT NULL,
    current_heat_ratio   DECIMAL(6,4) NOT NULL,
    floor_heat_ratio     DECIMAL(6,4) NOT NULL,
    chlorine_kg_h        DECIMAL(12,4),
    sulfur_kg_h          DECIMAL(12,4),
    reject_reason        VARCHAR(500),
    declare_request_id   VARCHAR(100),
    declared_at          TIMESTAMP(6) NOT NULL,
    approved_at          TIMESTAMP(6),
    executed_at          TIMESTAMP(6),
    stopped_at           TIMESTAMP(6),
    banned_at            TIMESTAMP(6),
    ban_date             DATE,
    settle_date          DATE,
    sealed_at            TIMESTAMP(6)
);
COMMENT ON COLUMN co_firing_plan.id IS '方案 ID';
COMMENT ON COLUMN co_firing_plan.kiln_id IS '窑口编码；同一窑口同一时刻仅允许一个执行中方案';
COMMENT ON COLUMN co_firing_plan.license_no IS '绑定的危废经营许可证编号';
COMMENT ON COLUMN co_firing_plan.status IS '状态：DECLARED/APPROVED/EXECUTING/STOPPED/REJECTED/BANNED/SEALED';
COMMENT ON COLUMN co_firing_plan.declared_heat_ratio IS '申报替代燃料热量替代率，无量纲，上限 0.4（恰 0.4 合法）';
COMMENT ON COLUMN co_firing_plan.current_heat_ratio IS '当前生效替代率，降掺后下调；升级时强制为保底比例';
COMMENT ON COLUMN co_firing_plan.floor_heat_ratio IS '降掺保底比例，无量纲';
COMMENT ON COLUMN co_firing_plan.chlorine_kg_h IS '按投料量加权的入窑氯总量，单位 kg/h；NULL 表示审核拒绝未形成';
COMMENT ON COLUMN co_firing_plan.sulfur_kg_h IS '按投料量加权的入窑硫总量，单位 kg/h；NULL 表示审核拒绝未形成';
COMMENT ON COLUMN co_firing_plan.reject_reason IS '整单拒绝原因（替代率/氯硫/许可校验失败）';
COMMENT ON COLUMN co_firing_plan.declare_request_id IS '申报 requestId，用于并发申报唯一受理排查';
COMMENT ON COLUMN co_firing_plan.ban_date IS '当日禁烧日期，Asia/Shanghai；NULL 表示未禁烧';
COMMENT ON COLUMN co_firing_plan.settle_date IS '日结日期；NULL 表示未日结封存';
COMMENT ON COLUMN co_firing_plan.sealed_at IS '封存时刻；非 NULL 表示终态，迟到读数只记事件';

-- 方案燃料组分
CREATE TABLE IF NOT EXISTS plan_fuel (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id             BIGINT       NOT NULL,
    fuel_name           VARCHAR(100) NOT NULL,
    alternative         BOOLEAN      NOT NULL,
    feed_rate_kg_h      DECIMAL(14,3) NOT NULL,
    heat_value_kcal_kg  DECIMAL(14,3) NOT NULL,
    chlorine_pct        DECIMAL(9,5) NOT NULL,
    sulfur_pct          DECIMAL(9,5) NOT NULL,
    CONSTRAINT fk_plan_fuel_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan(id)
);
COMMENT ON COLUMN plan_fuel.feed_rate_kg_h IS '入窑投料量，单位 kg/h';
COMMENT ON COLUMN plan_fuel.heat_value_kcal_kg IS '低位热值，单位 kcal/kg';
COMMENT ON COLUMN plan_fuel.chlorine_pct IS '氯质量分数，单位 %（0.05 表示 0.05%）';
COMMENT ON COLUMN plan_fuel.sulfur_pct IS '硫质量分数，单位 %';

-- 窑口执行槽：一行即一个执行中方案，主键约束保证并发切换唯一受理
CREATE TABLE IF NOT EXISTS kiln_execution_slot (
    kiln_id   VARCHAR(64) PRIMARY KEY,
    plan_id   BIGINT NOT NULL,
    CONSTRAINT uq_slot_plan UNIQUE (plan_id)
);
COMMENT ON COLUMN kiln_execution_slot.kiln_id IS '窑口编码，主键即同一窑口同时刻至多一个执行中方案';
COMMENT ON COLUMN kiln_execution_slot.plan_id IS '当前执行中方案 ID';

-- 排放读数：测点+采样时刻去重（按方案隔离）
CREATE TABLE IF NOT EXISTS emission_reading (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id       BIGINT       NOT NULL,
    point_code    VARCHAR(64)  NOT NULL,
    pollutant     VARCHAR(20)  NOT NULL,
    sampled_at    TIMESTAMP(6) NOT NULL,
    value_mg_m3   DECIMAL(12,3) NOT NULL,
    ingested_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_reading_dedup UNIQUE (plan_id, point_code, sampled_at),
    CONSTRAINT fk_reading_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan(id)
);
COMMENT ON COLUMN emission_reading.point_code IS '测点编码；与采样时刻共同构成去重键';
COMMENT ON COLUMN emission_reading.pollutant IS '污染物：NOX/SO2/PARTICULATE';
COMMENT ON COLUMN emission_reading.sampled_at IS '采样时刻，Asia/Shanghai；乱序上报按此时刻归位';
COMMENT ON COLUMN emission_reading.value_mg_m3 IS '监测值，单位 mg/m3（标准状态干基，合成口径）';

-- 封存后迟到读数事件（不进入评估与日结勾稽）
CREATE TABLE IF NOT EXISTS late_reading_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id       BIGINT       NOT NULL,
    point_code    VARCHAR(64)  NOT NULL,
    pollutant     VARCHAR(20)  NOT NULL,
    sampled_at    TIMESTAMP(6) NOT NULL,
    value_mg_m3   DECIMAL(12,3) NOT NULL,
    received_at   TIMESTAMP(6) NOT NULL
);
COMMENT ON COLUMN late_reading_event.received_at IS '迟到读数到达时刻；方案封存后仅登记不评估';

-- 小时评估单污染物结果
CREATE TABLE IF NOT EXISTS hour_evaluation (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id        BIGINT       NOT NULL,
    hour_start     TIMESTAMP(6) NOT NULL,
    pollutant      VARCHAR(20)  NOT NULL,
    average_mg_m3  DECIMAL(12,3) NOT NULL,
    limit_mg_m3    DECIMAL(12,3) NOT NULL,
    sample_count   INT          NOT NULL,
    exceeded       BOOLEAN      NOT NULL,
    CONSTRAINT uq_hour_pollutant UNIQUE (plan_id, hour_start, pollutant)
);
COMMENT ON COLUMN hour_evaluation.hour_start IS '自然小时起点（分钟秒为 00:00），Asia/Shanghai';
COMMENT ON COLUMN hour_evaluation.average_mg_m3 IS '该小时读数算术均值，单位 mg/m3';
COMMENT ON COLUMN hour_evaluation.limit_mg_m3 IS '标准限值，单位 mg/m3；均值恰等于限值判达标';
COMMENT ON COLUMN hour_evaluation.exceeded IS '该污染物本小时是否超标（严格大于限值）';

-- 小时评估汇总（连续超标计数与当小时生效替代率快照）
CREATE TABLE IF NOT EXISTS hour_evaluation_summary (
    plan_id             BIGINT       NOT NULL,
    hour_start          TIMESTAMP(6) NOT NULL,
    exceeded            BOOLEAN      NOT NULL,
    consecutive_count   INT          NOT NULL,
    effective_ratio     DECIMAL(6,4) NOT NULL,
    command_id          BIGINT,
    triggered_ban       BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (plan_id, hour_start)
);
COMMENT ON COLUMN hour_evaluation_summary.consecutive_count IS '截至本小时的连续超标小时数；恰 3 时触发当日禁烧，方案进入 BANNED 终态';
COMMENT ON COLUMN hour_evaluation_summary.effective_ratio IS '本小时生效热量替代率快照，日结实际替代率据此复算';
COMMENT ON COLUMN hour_evaluation_summary.command_id IS '本小时超标触发并下发的降掺指令 ID；未超标为 NULL';
COMMENT ON COLUMN hour_evaluation_summary.triggered_ban IS '本小时是否恰连续 3 次超标并触发当日禁烧';

-- 降掺指令链
CREATE TABLE IF NOT EXISTS reduction_command (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id           BIGINT       NOT NULL,
    hour_start        TIMESTAMP(6) NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    target_heat_ratio DECIMAL(6,4) NOT NULL,
    issued_at         TIMESTAMP(6) NOT NULL,
    confirm_deadline  TIMESTAMP(6) NOT NULL,
    confirmed_at      TIMESTAMP(6),
    escalated_at      TIMESTAMP(6)
);
COMMENT ON COLUMN reduction_command.status IS '指令状态：ISSUED 已下发/CONFIRMED 已确认/ESCALATED 超时升级';
COMMENT ON COLUMN reduction_command.target_heat_ratio IS '目标替代率：触发时按步长降掺；升级时强制为保底比例';
COMMENT ON COLUMN reduction_command.issued_at IS '下发时刻';
COMMENT ON COLUMN reduction_command.confirm_deadline IS '确认时限=下发+30 分钟；恰满未确认自动升级';
COMMENT ON COLUMN reduction_command.confirmed_at IS '执行确认时刻；时限内确认才允许为非空';
COMMENT ON COLUMN reduction_command.escalated_at IS '升级时刻，同时方案强制降掺至保底比例';

-- 当日禁烧标记（按窑口+日期唯一）
CREATE TABLE IF NOT EXISTS daily_ban (
    kiln_id            VARCHAR(64) NOT NULL,
    ban_date           DATE        NOT NULL,
    triggered_plan_id  BIGINT      NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (kiln_id, ban_date)
);
COMMENT ON COLUMN daily_ban.ban_date IS '禁烧日期，Asia/Shanghai；连续 3 个小时均值超标（恰 3 次）触发';
COMMENT ON COLUMN daily_ban.triggered_plan_id IS '触发禁烧的方案 ID，该方案置 BANNED 终态';

-- 幂等记录
CREATE TABLE IF NOT EXISTS idempotency_record (
    scope        VARCHAR(32)  NOT NULL,
    request_id   VARCHAR(100) NOT NULL,
    fingerprint  CHAR(64)     NOT NULL,
    response_json CLOB       NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (scope, request_id)
);
COMMENT ON COLUMN idempotency_record.scope IS '业务域：DECLARE_PLAN/CONFIRM_COMMAND';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等键；同键同参重放取 response_json';
COMMENT ON COLUMN idempotency_record.fingerprint IS '业务字段指纹 SHA-256；同键异参返回 409 且不改变执行参数';
COMMENT ON COLUMN idempotency_record.response_json IS '首次受理结果快照 JSON';
