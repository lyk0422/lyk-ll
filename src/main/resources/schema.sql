-- 温室环控服务建表脚本（H2 MySQL 兼容模式；数据仅保留于 JVM 生命周期内）
-- 温度单位：摄氏度(℃)，湿度单位：相对湿度百分比(%RH)，能耗单位：kWh，时间均为 UTC。

CREATE TABLE IF NOT EXISTS crop (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    crop_code         VARCHAR(64)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    stage_code        VARCHAR(64)  NOT NULL,
    day_temp_min      DECIMAL(6,2) NOT NULL,
    day_temp_max      DECIMAL(6,2) NOT NULL,
    day_humidity_min  DECIMAL(6,2) NOT NULL,
    day_humidity_max  DECIMAL(6,2) NOT NULL,
    night_temp_min    DECIMAL(6,2) NOT NULL,
    night_temp_max    DECIMAL(6,2) NOT NULL,
    night_humidity_min DECIMAL(6,2) NOT NULL,
    night_humidity_max DECIMAL(6,2) NOT NULL,
    created_at        TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_crop_code ON crop (crop_code);
COMMENT ON COLUMN crop.stage_code IS '作物当前生育期代码，设定点必须落在该生育期允许窗口内（含边界）';

CREATE TABLE IF NOT EXISTS zone (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code  VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_zone_code ON zone (zone_code);

CREATE TABLE IF NOT EXISTS climate_strategy (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_code           VARCHAR(64)  NOT NULL,
    crop_id                 BIGINT       NOT NULL,
    zone_id                 BIGINT       NOT NULL,
    day_temp_target         DECIMAL(6,2) NOT NULL,
    day_temp_tolerance      DECIMAL(6,2) NOT NULL,
    day_humidity_target     DECIMAL(6,2) NOT NULL,
    day_humidity_tolerance  DECIMAL(6,2) NOT NULL,
    night_temp_target       DECIMAL(6,2) NOT NULL,
    night_temp_tolerance    DECIMAL(6,2) NOT NULL,
    night_humidity_target   DECIMAL(6,2) NOT NULL,
    night_humidity_tolerance DECIMAL(6,2) NOT NULL,
    status                  VARCHAR(16)  NOT NULL,
    validation_message      CLOB,
    created_at              TIMESTAMP    NOT NULL,
    validated_at            TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_strategy_code ON climate_strategy (strategy_code);
CREATE INDEX IF NOT EXISTS idx_strategy_zone ON climate_strategy (zone_id);
COMMENT ON COLUMN climate_strategy.status IS '策略状态：DRAFT 编制 / VALIDATED 校验通过 / ACTIVE 生效中 / SUPERSEDED 已被新策略替代';
COMMENT ON COLUMN climate_strategy.day_temp_tolerance IS '白天温度允许半带宽(℃)，设定带=[目标值-容差,目标值+容差]，含边界';

CREATE TABLE IF NOT EXISTS zone_active_strategy (
    zone_id      BIGINT PRIMARY KEY,
    strategy_id  BIGINT NOT NULL,
    activated_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE zone_active_strategy IS '分区当前唯一生效策略；同分区并发首笔下发仅一行插入成功';

CREATE TABLE IF NOT EXISTS strategy_change_log (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_id          BIGINT NOT NULL,
    old_strategy_id  BIGINT,
    new_strategy_id  BIGINT NOT NULL,
    effective_at     TIMESTAMP NOT NULL,
    request_id       VARCHAR(64),
    created_at       TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_change_log_zone ON strategy_change_log (zone_id, effective_at);
COMMENT ON COLUMN strategy_change_log.effective_at IS '策略变更生效时刻(UTC)，同分区相邻两次间隔不得少于30分钟';

CREATE TABLE IF NOT EXISTS sensor_reading (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_id     BIGINT NOT NULL,
    sample_time TIMESTAMP NOT NULL,
    temperature DECIMAL(6,2) NOT NULL,
    humidity    DECIMAL(6,2) NOT NULL,
    late_flag   BOOLEAN NOT NULL DEFAULT FALSE,
    ingested_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_reading_zone_time ON sensor_reading (zone_id, sample_time);
COMMENT ON COLUMN sensor_reading.sample_time IS '采样时刻(UTC)，按分区+采样时刻去重，乱序上报按此时刻归位';
COMMENT ON COLUMN sensor_reading.late_flag IS '是否为日评估封存后才到达的迟到读数：true=仅登记迟到事件，不进入评估统计';

CREATE TABLE IF NOT EXISTS deviation_alert (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_id              BIGINT NOT NULL,
    strategy_id          BIGINT NOT NULL,
    status               VARCHAR(16) NOT NULL,
    first_deviate_time   TIMESTAMP NOT NULL,
    trigger_sample_time  TIMESTAMP NOT NULL,
    recovered_at         TIMESTAMP,
    acknowledged_at      TIMESTAMP,
    ack_request_id       VARCHAR(64),
    created_at           TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_alert_zone ON deviation_alert (zone_id, status);
COMMENT ON COLUMN deviation_alert.status IS '告警状态：OPEN 未确认 / ACKNOWLEDGED 已确认';
COMMENT ON COLUMN deviation_alert.trigger_sample_time IS '连续第3个偏离采样点的时刻，恰满3点触发';

CREATE TABLE IF NOT EXISTS late_reading_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_id     BIGINT NOT NULL,
    reading_id  BIGINT NOT NULL,
    sample_time TIMESTAMP NOT NULL,
    budget_day  DATE NOT NULL,
    created_at  TIMESTAMP NOT NULL
);
COMMENT ON TABLE late_reading_event IS '日评估封存后迟到读数事件，按 UTC 日界登记';

CREATE TABLE IF NOT EXISTS energy_budget (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_id       BIGINT NOT NULL,
    budget_day    DATE NOT NULL,
    budget_kwh    DECIMAL(10,3) NOT NULL CHECK (budget_kwh >= 0),
    remaining_kwh DECIMAL(10,3) NOT NULL CHECK (remaining_kwh >= 0),
    created_at    TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_budget_zone_day ON energy_budget (zone_id, budget_day);
COMMENT ON COLUMN energy_budget.budget_day IS '能耗预算日(UTC 日界)';
COMMENT ON COLUMN energy_budget.remaining_kwh IS '当日剩余预算(kWh)，扣减与指令入账同事务，不允许为负';

CREATE TABLE IF NOT EXISTS energy_command (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_id      BIGINT NOT NULL,
    budget_day   DATE NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    energy_kwh   DECIMAL(10,3) NOT NULL,
    request_id   VARCHAR(64),
    issued_at    TIMESTAMP NOT NULL,
    created_at   TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_command_request ON energy_command (request_id);
CREATE INDEX IF NOT EXISTS idx_command_zone_day ON energy_command (zone_id, budget_day);
COMMENT ON COLUMN energy_command.command_type IS '指令类型：HEATING 加热 / LIGHTING 补光（耗能）；VENTILATION 通风（零能耗）';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64) PRIMARY KEY,
    biz_type      VARCHAR(24) NOT NULL,
    fingerprint   VARCHAR(128) NOT NULL,
    http_status   INT NOT NULL,
    response_body CLOB,
    created_at    TIMESTAMP NOT NULL
);
COMMENT ON COLUMN idempotent_request.fingerprint IS 'SHA-256 指纹，覆盖 requestId 与全部业务字段；同键异参返回409';

CREATE TABLE IF NOT EXISTS daily_evaluation (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_id             BIGINT NOT NULL,
    budget_day          DATE NOT NULL,
    status              VARCHAR(16) NOT NULL,
    total_readings      INT NOT NULL,
    achieved_readings   INT NOT NULL,
    achievement_rate    DECIMAL(6,4) NOT NULL,
    total_kwh           DECIMAL(10,3) NOT NULL,
    heating_kwh         DECIMAL(10,3) NOT NULL,
    lighting_kwh        DECIMAL(10,3) NOT NULL,
    ventilation_count   INT NOT NULL,
    sealed_at           TIMESTAMP,
    detail_json         CLOB,
    created_at          TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_eval_zone_day ON daily_evaluation (zone_id, budget_day);
COMMENT ON COLUMN daily_evaluation.status IS '评估状态：OPEN 可复算 / SEALED 封存终态，重复封存幂等返回';
COMMENT ON COLUMN daily_evaluation.achievement_rate IS '设定达成率=落在设定带内采样点数/有效采样点数，取值0~1，含边界算达成';
