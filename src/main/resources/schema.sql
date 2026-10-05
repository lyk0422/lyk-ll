-- 温室环控服务建表脚本（H2 MySQL 兼容模式；时间一律以 UTC 存储）

CREATE TABLE IF NOT EXISTS crop (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    crop_code   VARCHAR(64)  NOT NULL,
    crop_name   VARCHAR(128) NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_crop_code UNIQUE (crop_code)
);
COMMENT ON TABLE crop IS '作物（合成数据）';
COMMENT ON COLUMN crop.crop_code IS '作物编码，业务唯一键';

CREATE TABLE IF NOT EXISTS crop_stage (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    crop_code       VARCHAR(64)  NOT NULL,
    stage_code      VARCHAR(64)  NOT NULL,
    stage_name      VARCHAR(128) NOT NULL,
    day_temp_min    DECIMAL(5,2) NOT NULL,
    day_temp_max    DECIMAL(5,2) NOT NULL,
    night_temp_min  DECIMAL(5,2) NOT NULL,
    night_temp_max  DECIMAL(5,2) NOT NULL,
    humidity_min    DECIMAL(5,2) NOT NULL,
    humidity_max    DECIMAL(5,2) NOT NULL,
    CONSTRAINT uk_crop_stage UNIQUE (crop_code, stage_code)
);
COMMENT ON TABLE crop_stage IS '作物生育期允许窗口（摄氏度/%RH，边界含等号）';

CREATE TABLE IF NOT EXISTS zone_info (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code   VARCHAR(64)  NOT NULL,
    zone_name   VARCHAR(128) NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_zone_code UNIQUE (zone_code)
);
COMMENT ON TABLE zone_info IS '温室分区（合成数据）';

CREATE TABLE IF NOT EXISTS climate_strategy (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_code       VARCHAR(64)  NOT NULL,
    zone_code           VARCHAR(64)  NOT NULL,
    crop_code           VARCHAR(64)  NOT NULL,
    stage_code          VARCHAR(64)  NOT NULL,
    day_temp            DECIMAL(5,2) NOT NULL,
    night_temp          DECIMAL(5,2) NOT NULL,
    humidity_setpoint   DECIMAL(5,2) NOT NULL,
    temp_tolerance      DECIMAL(5,2) NOT NULL,
    humidity_tolerance  DECIMAL(5,2) NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    request_id          VARCHAR(96),
    effective_time      TIMESTAMP(6),
    created_at          TIMESTAMP(6) NOT NULL,
    validated_at        TIMESTAMP(6),
    dispatched_at       TIMESTAMP(6),
    expired_at          TIMESTAMP(6),
    CONSTRAINT uk_strategy_code UNIQUE (strategy_code),
    CONSTRAINT uk_zone_effective UNIQUE (zone_code, effective_time)
);
COMMENT ON TABLE climate_strategy IS '气候策略：编制→校验→下发→执行→评估/失效';
COMMENT ON COLUMN climate_strategy.status IS 'DRAFT/VALIDATED/SUSPENDED/DISPATCHED/EXECUTING/EXPIRED/EVALUATED；SUSPENDED=与进行中告警处置不兼容而挂起，无任何副作用';
COMMENT ON COLUMN climate_strategy.day_temp IS '白天温度设定点，单位摄氏度';
COMMENT ON COLUMN climate_strategy.night_temp IS '夜间温度设定点，单位摄氏度；与白天设定点差值不得超过8摄氏度（含8）';
COMMENT ON COLUMN climate_strategy.temp_tolerance IS '温度允许带宽半宽，单位摄氏度';
COMMENT ON COLUMN climate_strategy.humidity_tolerance IS '湿度允许带宽半宽，单位%RH';
COMMENT ON COLUMN climate_strategy.effective_time IS '生效时刻（UTC），同一分区同一时刻唯一，空值表示尚未下发';

CREATE TABLE IF NOT EXISTS sensor_reading (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code     VARCHAR(64)  NOT NULL,
    sample_time   TIMESTAMP(6) NOT NULL,
    temperature   DECIMAL(5,2) NOT NULL,
    humidity      DECIMAL(5,2) NOT NULL,
    ingested_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_zone_sample UNIQUE (zone_code, sample_time)
);
COMMENT ON TABLE sensor_reading IS '传感器读数，按分区+采样时刻去重，乱序按时刻归位';
COMMENT ON COLUMN sensor_reading.temperature IS '实际温度，单位摄氏度';
COMMENT ON COLUMN sensor_reading.humidity IS '实际相对湿度，单位%RH';

CREATE TABLE IF NOT EXISTS late_reading_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code     VARCHAR(64)  NOT NULL,
    sample_time   TIMESTAMP(6) NOT NULL,
    temperature   DECIMAL(5,2) NOT NULL,
    humidity      DECIMAL(5,2) NOT NULL,
    recorded_at   TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE late_reading_event IS '日评估封存后到达的迟到读数，仅记录事件不参与评估';

CREATE TABLE IF NOT EXISTS deviation_alarm (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code         VARCHAR(64)  NOT NULL,
    strategy_code     VARCHAR(64)  NOT NULL,
    start_sample_time TIMESTAMP(6) NOT NULL,
    trigger_time      TIMESTAMP(6) NOT NULL,
    reading_count     INT          NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    ack_request_id    VARCHAR(96),
    created_at        TIMESTAMP(6) NOT NULL,
    acknowledged_at   TIMESTAMP(6)
);
COMMENT ON TABLE deviation_alarm IS '连续3个采样点偏离设定带触发的偏差告警，恰在第3点触发';
COMMENT ON COLUMN deviation_alarm.status IS 'ACTIVE/ACKNOWLEDGED';

CREATE TABLE IF NOT EXISTS energy_budget (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code     VARCHAR(64)   NOT NULL,
    budget_date   DATE          NOT NULL,
    budget_kwh    DECIMAL(12,3),
    consumed_kwh  DECIMAL(12,3) NOT NULL DEFAULT 0,
    CONSTRAINT uk_zone_budget_date UNIQUE (zone_code, budget_date)
);
COMMENT ON TABLE energy_budget IS '每日（UTC日界）分区能耗预算，单位kWh';

CREATE TABLE IF NOT EXISTS control_command (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code     VARCHAR(64)   NOT NULL,
    command_type  VARCHAR(24)   NOT NULL,
    energy_kwh    DECIMAL(12,3) NOT NULL,
    budget_date   DATE          NOT NULL,
    request_id    VARCHAR(96)  NOT NULL,
    issued_at     TIMESTAMP(6)  NOT NULL,
    CONSTRAINT uk_command_request UNIQUE (request_id)
);
COMMENT ON TABLE control_command IS '环控指令：HEAT加热/SUPPLEMENT_LIGHT补光耗能，VENTILATE通风零能耗';
COMMENT ON COLUMN control_command.energy_kwh IS '本次指令计量能耗，单位kWh；通风为0';

CREATE TABLE IF NOT EXISTS daily_evaluation (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code           VARCHAR(64)  NOT NULL,
    eval_date           DATE         NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    in_band_count       INT          NOT NULL,
    total_count         INT          NOT NULL,
    achievement_rate    DECIMAL(6,4) NOT NULL,
    budget_kwh          DECIMAL(12,3),
    consumed_kwh        DECIMAL(12,3) NOT NULL,
    energy_detail_json  CLOB         NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    sealed_at           TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_zone_eval_date UNIQUE (zone_code, eval_date)
);
COMMENT ON TABLE daily_evaluation IS '日评估，SEALED 为终态；重复封存幂等返回';
COMMENT ON COLUMN daily_evaluation.achievement_rate IS '设定达成率=带内采样点数/总采样点数，取值0~1';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id      VARCHAR(96)  NOT NULL,
    request_kind    VARCHAR(32)  NOT NULL,
    fingerprint     VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    response_status INT,
    response_body   CLOB,
    created_at      TIMESTAMP(6) NOT NULL,
    finished_at     TIMESTAMP(6),
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);
COMMENT ON TABLE idempotent_request IS 'requestId 幂等记录：PROCESSING占位→DONE落首次结果；指纹覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN idempotent_request.status IS 'PROCESSING=首个请求处理中，DONE=首次结果已落库，FAILED=首个请求回滚';

CREATE TABLE IF NOT EXISTS alarm_handling (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    handling_code       VARCHAR(64)  NOT NULL,
    alarm_id            BIGINT       NOT NULL,
    zone_code           VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    temp_min            DECIMAL(5,2) NOT NULL,
    temp_max            DECIMAL(5,2) NOT NULL,
    humidity_min        DECIMAL(5,2) NOT NULL,
    humidity_max        DECIMAL(5,2) NOT NULL,
    request_id          VARCHAR(96),
    complete_request_id VARCHAR(96),
    created_at          TIMESTAMP(6) NOT NULL,
    completed_at        TIMESTAMP(6),
    CONSTRAINT uk_handling_code UNIQUE (handling_code)
);
COMMENT ON TABLE alarm_handling IS '告警处置：针对偏差告警的临时管控窗口，IN_PROGRESS 期间同分区新策略须与其兼容，否则下发挂起';
COMMENT ON COLUMN alarm_handling.status IS 'IN_PROGRESS=处置进行中（参与仲裁）；COMPLETED=处置完结（终态，不再参与仲裁）';
COMMENT ON COLUMN alarm_handling.temp_min IS '处置期间允许的设定温度下限，单位摄氏度，边界含等号；昼夜设定点均须落在窗口内才兼容';
COMMENT ON COLUMN alarm_handling.temp_max IS '处置期间允许的设定温度上限，单位摄氏度，边界含等号';
COMMENT ON COLUMN alarm_handling.humidity_min IS '处置期间允许的湿度设定点下限，单位%RH，边界含等号';
COMMENT ON COLUMN alarm_handling.humidity_max IS '处置期间允许的湿度设定点上限，单位%RH，边界含等号';
COMMENT ON COLUMN alarm_handling.request_id IS '发起处置的幂等 requestId';
COMMENT ON COLUMN alarm_handling.complete_request_id IS '完结处置的幂等 requestId，空值表示尚未完结';

CREATE TABLE IF NOT EXISTS strategy_rehearsal (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_code     VARCHAR(64)   NOT NULL,
    request_id        VARCHAR(96)   NOT NULL,
    model_version     VARCHAR(32)   NOT NULL,
    effective_time    TIMESTAMP(6)  NOT NULL,
    eval_date         DATE          NOT NULL,
    day_temp          DECIMAL(5,2)  NOT NULL,
    night_temp        DECIMAL(5,2)  NOT NULL,
    humidity_setpoint DECIMAL(5,2)  NOT NULL,
    duration_minutes  BIGINT        NOT NULL,
    hourly_rate_kwh   DECIMAL(12,3) NOT NULL,
    estimated_kwh     DECIMAL(12,3) NOT NULL,
    budget_kwh        DECIMAL(12,3),
    consumed_kwh      DECIMAL(12,3),
    remaining_kwh     DECIMAL(12,3),
    handling_conflict VARCHAR(256),
    verdict           VARCHAR(16)   NOT NULL,
    created_at        TIMESTAMP(6)  NOT NULL
);
COMMENT ON TABLE strategy_rehearsal IS '策略下发前预演留档：合成能耗模型估算与仲裁结论随策略版本保存，输入齐全可复算';
COMMENT ON COLUMN strategy_rehearsal.model_version IS '合成能耗模型版本，复算时须使用同版本系数';
COMMENT ON COLUMN strategy_rehearsal.eval_date IS '生效时刻所在 UTC 日期，决定比对哪一天的能耗预算';
COMMENT ON COLUMN strategy_rehearsal.duration_minutes IS '执行期时长：生效时刻到当日 UTC 日界的分钟数';
COMMENT ON COLUMN strategy_rehearsal.hourly_rate_kwh IS '合成模型小时能耗速率，单位 kWh/h';
COMMENT ON COLUMN strategy_rehearsal.estimated_kwh IS '执行期预估能耗，单位 kWh，保留 3 位小数';
COMMENT ON COLUMN strategy_rehearsal.budget_kwh IS '预演时当日预算（kWh）；空值表示当日未分配预算，不校验能耗';
COMMENT ON COLUMN strategy_rehearsal.remaining_kwh IS '预演时当日剩余预算（kWh）=预算-已耗；空值同上';
COMMENT ON COLUMN strategy_rehearsal.handling_conflict IS '不兼容的进行中处置编码（逗号分隔），空值表示无处置冲突';
COMMENT ON COLUMN strategy_rehearsal.verdict IS 'PASS=放行下发；SUSPENDED=与进行中处置不兼容挂起；REJECTED=预估能耗超当日剩余预算拒绝下发';
