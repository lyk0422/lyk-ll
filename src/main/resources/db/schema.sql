-- 水泥窑替代燃料掺烧服务：H2（MODE=MySQL）建表脚本
-- 所有时间均为 Asia/Shanghai 本地时间；质量单位 kg，浓度单位 mg/Nm3，比例为小数（0.40 表示 40%）

CREATE TABLE IF NOT EXISTS hazardous_waste_license (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    license_no  VARCHAR(64)  NOT NULL,
    holder      VARCHAR(128) NOT NULL,
    valid_from  DATE         NOT NULL,
    valid_to    DATE         NOT NULL,
    remark      VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_license_no UNIQUE (license_no)
);
COMMENT ON TABLE hazardous_waste_license IS '危废经营许可合成库，valid_to 含当日有效，早于当前日期即过期';

CREATE TABLE IF NOT EXISTS co_firing_plan (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id         VARCHAR(64)  NOT NULL,
    plan_no            VARCHAR(64)  NOT NULL,
    kiln_id            VARCHAR(32)  NOT NULL,
    license_id         BIGINT       NULL,
    substitution_rate  DECIMAL(6,4) NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    planned_start      TIMESTAMP    NOT NULL,
    planned_end        TIMESTAMP    NOT NULL,
    reject_reason      VARCHAR(500),
    reject_category    VARCHAR(32),
    submitted_at       TIMESTAMP,
    reviewed_at        TIMESTAMP,
    started_at         TIMESTAMP,
    stopped_at         TIMESTAMP,
    banned_at          TIMESTAMP,
    sealed_at          TIMESTAMP,
    sealed_date        DATE,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_plan_request_id UNIQUE (request_id),
    CONSTRAINT uk_plan_plan_no UNIQUE (plan_no),
    CONSTRAINT fk_plan_license FOREIGN KEY (license_id) REFERENCES hazardous_waste_license (id)
);
COMMENT ON TABLE co_firing_plan IS '掺烧方案主表，状态流转 SUBMITTED→APPROVED/REJECTED→EXECUTING→STOPPED/BANNED→SEALED';
COMMENT ON COLUMN co_firing_plan.substitution_rate IS '申报替代燃料热量替代率，上限0.40，恰等于合法';
COMMENT ON COLUMN co_firing_plan.status IS 'SUBMITTED已申报 APPROVED审核通过 REJECTED整单拒绝 EXECUTING执行中 STOPPED已停用 BANNED当日禁烧 SEALED已日结封存(终态)';

CREATE TABLE IF NOT EXISTS plan_fuel (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id         BIGINT        NOT NULL,
    fuel_name       VARCHAR(128)  NOT NULL,
    fuel_category   VARCHAR(16)   NOT NULL,
    high_cl_sulfur  BOOLEAN       NOT NULL DEFAULT FALSE,
    heat_value_mjkg DECIMAL(10,3) NOT NULL,
    mass_kg         DECIMAL(14,3) NOT NULL,
    chlorine_pct    DECIMAL(8,5)  NOT NULL DEFAULT 0,
    sulfur_pct      DECIMAL(8,5)  NOT NULL DEFAULT 0,
    CONSTRAINT fk_fuel_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan (id)
);
COMMENT ON TABLE plan_fuel IS '方案燃料明细，high_cl_sulfur=TRUE 的高氯硫燃料按质量加权计入入窑氯硫总量';
COMMENT ON COLUMN plan_fuel.heat_value_mjkg IS '燃料低位热值，单位 MJ/kg';
COMMENT ON COLUMN plan_fuel.chlorine_pct IS '氯质量分数百分比，如0.05表示0.05%';

CREATE TABLE IF NOT EXISTS kiln_active_plan (
    kiln_id     VARCHAR(32) PRIMARY KEY,
    plan_id     BIGINT       NOT NULL,
    acquired_at TIMESTAMP    NOT NULL,
    CONSTRAINT uk_active_plan_id UNIQUE (plan_id)
);
COMMENT ON TABLE kiln_active_plan IS '窑当前执行中方案占用锁，同一窑同时刻只允许一个执行中方案，靠主键唯一约束并发受理';

CREATE TABLE IF NOT EXISTS kiln_lock (
    kiln_id VARCHAR(32) PRIMARY KEY
);
COMMENT ON TABLE kiln_lock IS '窑级行锁，申报时 MERGE 后 SELECT FOR UPDATE 串行化同窑并发申报，保证唯一受理';

CREATE TABLE IF NOT EXISTS emission_reading (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id       BIGINT       NOT NULL,
    kiln_id       VARCHAR(32)  NOT NULL,
    measure_point VARCHAR(32)  NOT NULL,
    sample_time   TIMESTAMP    NOT NULL,
    nox           DECIMAL(10,3),
    so2           DECIMAL(10,3),
    dust          DECIMAL(10,3),
    is_late       BOOLEAN      NOT NULL DEFAULT FALSE,
    received_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_reading_dedup UNIQUE (plan_id, measure_point, sample_time),
    CONSTRAINT fk_reading_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan (id)
);
COMMENT ON TABLE emission_reading IS '排放读数，按 方案+测点+采样时刻 去重，乱序数据按采样时刻归位评估';
COMMENT ON COLUMN emission_reading.nox IS 'NOx小时内瞬时浓度 mg/Nm3，NULL表示该测点未提供该污染物';

CREATE TABLE IF NOT EXISTS hourly_assessment (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id           BIGINT       NOT NULL,
    hour_start        TIMESTAMP    NOT NULL,
    nox_avg           DECIMAL(10,3),
    so2_avg           DECIMAL(10,3),
    dust_avg          DECIMAL(10,3),
    reading_count     INT          NOT NULL DEFAULT 0,
    exceeded          BOOLEAN      NOT NULL DEFAULT FALSE,
    pollutants        VARCHAR(64),
    consecutive_no    INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_hour_plan UNIQUE (plan_id, hour_start),
    CONSTRAINT fk_hour_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan (id)
);
COMMENT ON TABLE hourly_assessment IS '小时评估结果，均值恰等于排放限值判达标(超标仅为严格大于)';
COMMENT ON COLUMN hourly_assessment.consecutive_no IS '截至该小时连续超标序号，达3触发当日禁烧';

CREATE TABLE IF NOT EXISTS reduction_command (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id            BIGINT       NOT NULL,
    hour_start         TIMESTAMP    NOT NULL,
    pollutant          VARCHAR(64),
    hour_avg           DECIMAL(10,3),
    status             VARCHAR(16)  NOT NULL,
    target_rate        DECIMAL(6,4) NOT NULL,
    triggered_at       TIMESTAMP    NOT NULL,
    issued_at          TIMESTAMP,
    confirmed_at       TIMESTAMP,
    escalated_at       TIMESTAMP,
    confirm_request_id VARCHAR(64),
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_command_hour UNIQUE (plan_id, hour_start),
    CONSTRAINT fk_command_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan (id)
);
COMMENT ON TABLE reduction_command IS '降掺指令链 TRIGGERED→ISSUED→CONFIRMED，超时未确认置ESCALATED并强制降至保底气比例';
COMMENT ON COLUMN reduction_command.status IS 'TRIGGERED已触发 ISSUED已下发 CONFIRMED已执行确认 ESCALATED恰满30分钟未确认自动升级';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64) PRIMARY KEY,
    request_type  VARCHAR(32) NOT NULL,
    fingerprint   VARCHAR(64) NOT NULL,
    http_status   INT         NOT NULL,
    response_body CLOB,
    plan_id       BIGINT,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE idempotent_request IS '申报/确认/切换/封存幂等记录，指纹覆盖requestId与全部业务字段，同键异参冲突';

CREATE TABLE IF NOT EXISTS late_reading_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id       BIGINT       NOT NULL,
    measure_point VARCHAR(32)  NOT NULL,
    sample_time   TIMESTAMP    NOT NULL,
    payload       CLOB,
    received_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE late_reading_event IS '方案日结封存后到达的迟到读数，仅登记事件，不再参与评估与日结';

CREATE TABLE IF NOT EXISTS ban_record (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id    BIGINT       NOT NULL,
    kiln_id    VARCHAR(32)  NOT NULL,
    ban_date   DATE         NOT NULL,
    reason     VARCHAR(500) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ban_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan (id)
);
COMMENT ON TABLE ban_record IS '连续3个小时均值超标触发的当日禁烧记录，按窑+日期拦截当日新执行方案';

CREATE TABLE IF NOT EXISTS daily_summary (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id                   BIGINT      NOT NULL,
    summary_date              DATE        NOT NULL,
    actual_substitution_rate  DECIMAL(6,4),
    exceeded_periods          CLOB,
    reduction_records         CLOB,
    effective_hours           CLOB,
    sealed                    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sealed_at                 TIMESTAMP,
    CONSTRAINT uk_summary_plan_day UNIQUE (plan_id, summary_date),
    CONSTRAINT fk_summary_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan (id)
);
COMMENT ON TABLE daily_summary IS '日结输出，实际替代率/超标时段/降掺记录三项勾稽可复算，sealed=TRUE为封存终态';
