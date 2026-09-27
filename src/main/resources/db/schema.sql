-- 污水排放在线监测与限排联动：H2 内存库（MODE=MySQL）建表脚本
-- 仅保存合成数据；时间列均为 Asia/Shanghai 本地时间，由应用时钟写入。

CREATE TABLE IF NOT EXISTS discharge_outlet (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code     VARCHAR(64)  NOT NULL,
    outlet_name     VARCHAR(128) NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    daily_limit     DECIMAL(18,4) NOT NULL,
    hourly_limit    DECIMAL(18,4) NOT NULL,
    limit_unit      VARCHAR(32)  NOT NULL DEFAULT 'mg/L',
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    archived_at     TIMESTAMP,
    CONSTRAINT uk_outlet_code UNIQUE (outlet_code)
);
COMMENT ON COLUMN discharge_outlet.status IS '排口状态：REGISTERED 许可登记 / MONITORING 在线监测 / RESTRICTED 限排执行中 / ARCHIVED 已归档（终态）';
COMMENT ON COLUMN discharge_outlet.daily_limit IS '许可日均值限值，单位 mg/L，含本值达标（日均值恰等于限值判达标）';
COMMENT ON COLUMN discharge_outlet.hourly_limit IS '许可小时均值限值，单位 mg/L，含本值达标';

CREATE TABLE IF NOT EXISTS monitor_reading (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code     VARCHAR(64)  NOT NULL,
    period_type     VARCHAR(8)   NOT NULL,
    period_start    TIMESTAMP    NOT NULL,
    avg_value       DECIMAL(18,4) NOT NULL,
    sample_count    INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_reading_period UNIQUE (outlet_code, period_type, period_start)
);
COMMENT ON COLUMN monitor_reading.period_type IS '时段类型：HOUR 小时均值 / DAY 日均值';
COMMENT ON COLUMN monitor_reading.avg_value IS '时段平均浓度，单位 mg/L（合成数据）';
COMMENT ON COLUMN monitor_reading.period_start IS '时段起始时刻（整点）；排口＋时段类型＋时段起始唯一，用于读数去重';

CREATE TABLE IF NOT EXISTS exceedance_mark (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code     VARCHAR(64)  NOT NULL,
    mark_type       VARCHAR(16)  NOT NULL,
    period_start    TIMESTAMP    NOT NULL,
    period_end      TIMESTAMP    NOT NULL,
    limit_value     DECIMAL(18,4) NOT NULL,
    actual_value    DECIMAL(18,4) NOT NULL,
    chain_index     INT          NOT NULL DEFAULT 0,
    request_id      VARCHAR(80),
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_mark_period UNIQUE (outlet_code, mark_type, period_start)
);
COMMENT ON COLUMN exceedance_mark.mark_type IS '超标类型：DAILY_AVG 日均值超标 / HOURLY_AVG 小时均值超标';
COMMENT ON COLUMN exceedance_mark.chain_index IS '小时超标连续链序号，1=连续第 1 次；连续第 3 次触发限排令';
COMMENT ON COLUMN exceedance_mark.request_id IS '幂等请求号：日均值评价由调用方提供，小时链标记为自动派生号';
COMMENT ON COLUMN exceedance_mark.status IS '标记状态：ACTIVE 生效中 / RESOLVED 随限排令复测合格关闭';

CREATE TABLE IF NOT EXISTS restriction_order (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no                VARCHAR(64)  NOT NULL,
    outlet_code             VARCHAR(64)  NOT NULL,
    status                  VARCHAR(32)  NOT NULL,
    trigger_period_start    TIMESTAMP,
    frozen_total_qty        DECIMAL(18,4),
    issued_at               TIMESTAMP    NOT NULL,
    confirm_deadline        TIMESTAMP    NOT NULL,
    confirmed_at            TIMESTAMP,
    escalated_at            TIMESTAMP,
    escalate_reason         VARCHAR(255),
    retest_period_start     TIMESTAMP,
    retest_period_end       TIMESTAMP,
    retest_value            DECIMAL(18,4),
    release_request_id      VARCHAR(80),
    closed_at               TIMESTAMP,
    active_slot             VARCHAR(64),
    created_at              TIMESTAMP    NOT NULL,
    updated_at              TIMESTAMP    NOT NULL,
    CONSTRAINT uk_order_no UNIQUE (order_no),
    CONSTRAINT uk_active_order UNIQUE (outlet_code, active_slot),
    CONSTRAINT chk_order_frozen CHECK (status = 'PENDING_CONFIRM' OR frozen_total_qty IS NOT NULL)
);
COMMENT ON COLUMN restriction_order.status IS '限排令状态：PENDING_CONFIRM 待确认 / ACTIVE 已确认执行 / ESCALATED 超期未确认升级并联动限产 / CLOSED 复测合格关闭';
COMMENT ON COLUMN restriction_order.frozen_total_qty IS '限排生效时冻结的排放总量基数（合成单位：mg/L·小时累计），与状态转执行同事务写入，为空表示基数未定';
COMMENT ON COLUMN restriction_order.confirm_deadline IS '确认执行截止时刻=下达时刻+2 小时，恰满 2 小时仍未确认即升级';
COMMENT ON COLUMN restriction_order.active_slot IS '执行中占槽=排口编码，关闭后置空；配合唯一约束保证同一排口同时刻仅一个执行中限排令';

CREATE TABLE IF NOT EXISTS production_cut (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(64)  NOT NULL,
    outlet_code     VARCHAR(64)  NOT NULL,
    cut_ratio       DECIMAL(5,2) NOT NULL,
    reason          VARCHAR(255) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'EFFECTIVE',
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_cut_order UNIQUE (order_no)
);
COMMENT ON COLUMN production_cut.cut_ratio IS '联动限产比例，0.30 表示限产 30%';

CREATE TABLE IF NOT EXISTS data_gap (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code     VARCHAR(64)  NOT NULL,
    gap_start       TIMESTAMP    NOT NULL,
    gap_end         TIMESTAMP    NOT NULL,
    gap_minutes     INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    supervised_at   TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_gap_range UNIQUE (outlet_code, gap_start, gap_end)
);
COMMENT ON COLUMN data_gap.gap_minutes IS '连续缺失时长（分钟）；仅当严格大于 120 分钟才建缺失督办，恰 120 分钟不标记';
COMMENT ON COLUMN data_gap.status IS '缺失状态：SUPERVISED 已督办 / RESOLVED 已恢复';

CREATE TABLE IF NOT EXISTS outlet_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code     VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(40)  NOT NULL,
    event_time      TIMESTAMP    NOT NULL,
    late_flag       BOOLEAN      NOT NULL DEFAULT FALSE,
    payload         CLOB
);
COMMENT ON COLUMN outlet_event.event_type IS '事件类型：REGISTER/READING/MARK/ORDER_ISSUED/CONFIRM/ESCALATE/RELEASE/ARCHIVE/LATE_READING/GAP_SUPERVISE';
COMMENT ON COLUMN outlet_event.late_flag IS '是否归档后迟到事件；归档后迟到监测数据只记迟到事件不参与评价';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id      VARCHAR(80)  NOT NULL,
    request_type    VARCHAR(32)  NOT NULL,
    fingerprint     VARCHAR(64)  NOT NULL,
    result_json     CLOB         NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);
COMMENT ON COLUMN idempotent_request.fingerprint IS '请求指纹：SHA-256( requestId + 全部业务字段 )，同键同参回放首次结果，同键异参返回 409';

-- ===================== 雨季溢流（B 题新增，合成数据；时刻列均为 UTC 墙钟时间整点） =====================

CREATE TABLE IF NOT EXISTS overflow_permit_version (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code                 VARCHAR(64)   NOT NULL,
    version                     INT           NOT NULL,
    window_start_utc            TIMESTAMP     NOT NULL,
    window_end_utc              TIMESTAMP     NOT NULL,
    min_hourly_rainfall         DECIMAL(18,4) NOT NULL,
    max_consecutive_hours       INT           NOT NULL,
    status                      VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    request_id                  VARCHAR(80),
    created_at                  TIMESTAMP     NOT NULL,
    CONSTRAINT uk_overflow_permit_version UNIQUE (outlet_code, version),
    CONSTRAINT chk_permit_window CHECK (window_end_utc > window_start_utc),
    CONSTRAINT chk_permit_maxhours CHECK (max_consecutive_hours >= 1),
    CONSTRAINT chk_permit_rain CHECK (min_hourly_rainfall >= 0)
);
COMMENT ON COLUMN overflow_permit_version.version IS '溢流许可版本号：首版=1，每次携 expectedVersion 的成功修改+1，旧版本置 SUPERSEDED 但永久留证';
COMMENT ON COLUMN overflow_permit_version.window_start_utc IS 'UTC 半开适用窗口起点（含）；合规溢流小时必须 [hour,hour+1) 完整落入 [起点,终点)';
COMMENT ON COLUMN overflow_permit_version.window_end_utc IS 'UTC 半开适用窗口终点（不含）';
COMMENT ON COLUMN overflow_permit_version.min_hourly_rainfall IS '最低小时雨量门槛（合成单位 mm），雨量恰等于门槛合法（>= 判满足）';
COMMENT ON COLUMN overflow_permit_version.max_consecutive_hours IS '允许的最大连续合规溢流小时数；连续段长度严格大于该值即专项异常';
COMMENT ON COLUMN overflow_permit_version.status IS '版本状态：ACTIVE 当前有效 / SUPERSEDED 已被新版本取代（冻结标记仍引用旧版本）';

CREATE TABLE IF NOT EXISTS overflow_hour_record (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code                 VARCHAR(64)   NOT NULL,
    hour_utc                    TIMESTAMP     NOT NULL,
    discharge_value             DECIMAL(18,4) NOT NULL,
    rainfall_mm                 DECIMAL(18,4),
    rainfall_version            INT           NOT NULL DEFAULT 1,
    overflow_observed           BOOLEAN       NOT NULL DEFAULT FALSE,
    overflow_status             VARCHAR(16)   NOT NULL,
    evaluated_permit_version    INT,
    permit_version_frozen       INT,
    rainfall_version_frozen     INT,
    review_request_id           VARCHAR(80),
    frozen_at                   TIMESTAMP,
    created_at                  TIMESTAMP     NOT NULL,
    updated_at                  TIMESTAMP     NOT NULL,
    CONSTRAINT uk_overflow_hour UNIQUE (outlet_code, hour_utc),
    CONSTRAINT chk_rainfall_version CHECK (rainfall_version >= 1),
    CONSTRAINT chk_overflow_status CHECK (overflow_status IN ('NORMAL','PENDING','COMPLIANT','DENIED'))
);
COMMENT ON COLUMN overflow_hour_record.hour_utc IS 'UTC 整点小时起点（分秒必须为 0），半开小时区间 [hour_utc,hour_utc+1h)';
COMMENT ON COLUMN overflow_hour_record.discharge_value IS '该 UTC 小时合成排放浓度，单位 mg/L，双口径均值的被平均量';
COMMENT ON COLUMN overflow_hour_record.rainfall_mm IS '该小时雨量（合成单位 mm）；NULL=缺雨量，只能待核，禁止当零或直接豁免';
COMMENT ON COLUMN overflow_hour_record.rainfall_version IS '雨量版本号：首值=1，复核冻结前每次成功雨量更正+1；冻结后只能独立调差不改此值';
COMMENT ON COLUMN overflow_hour_record.overflow_observed IS '是否观测到溢流：true 才可能进入待核/合规判定，false 一律正常排放';
COMMENT ON COLUMN overflow_hour_record.overflow_status IS '溢流标记：NORMAL 正常排放 / PENDING 缺雨量待核 / COMPLIANT 证据齐全的合规溢流（仅该口径可被正常均值排除）/ DENIED 观测溢流但不满足许可条件';
COMMENT ON COLUMN overflow_hour_record.evaluated_permit_version IS '判定该标记时所用许可版本（跨版本相邻小时必须断段）';
COMMENT ON COLUMN overflow_hour_record.permit_version_frozen IS '复核冻结的许可版本；非空表示证据已冻结，之后雨量更正只能走独立调差';
COMMENT ON COLUMN overflow_hour_record.rainfall_version_frozen IS '复核冻结的雨量版本';
COMMENT ON COLUMN overflow_hour_record.frozen_at IS '复核冻结时刻（请求携带的精确 UTC 时刻）；未冻结为 NULL';

CREATE TABLE IF NOT EXISTS overflow_anomaly (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code                 VARCHAR(64)   NOT NULL,
    anomaly_type                VARCHAR(32)   NOT NULL,
    segment_start_utc           TIMESTAMP     NOT NULL,
    segment_end_utc             TIMESTAMP     NOT NULL,
    segment_hours               INT           NOT NULL,
    permit_version              INT           NOT NULL,
    created_at                  TIMESTAMP     NOT NULL,
    CONSTRAINT uk_overflow_anomaly UNIQUE (outlet_code, anomaly_type, segment_start_utc, segment_end_utc)
);
COMMENT ON COLUMN overflow_anomaly.anomaly_type IS '专项异常类型：SEGMENT_TOO_LONG 连续合规溢流段超出许可最大连续小时';
COMMENT ON COLUMN overflow_anomaly.segment_hours IS '连续段小时数；跨许可版本相邻或遇缺点（缺小时槽/非 COMPLIANT 点）必须断段';

CREATE TABLE IF NOT EXISTS rainfall_adjustment (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code                 VARCHAR(64)   NOT NULL,
    hour_utc                    TIMESTAMP     NOT NULL,
    frozen_rainfall_version     INT           NOT NULL,
    old_rainfall_mm             DECIMAL(18,4) NOT NULL,
    new_rainfall_mm             DECIMAL(18,4) NOT NULL,
    reason                      VARCHAR(255),
    request_id                  VARCHAR(80),
    created_at                  TIMESTAMP     NOT NULL
);
COMMENT ON COLUMN rainfall_adjustment.old_rainfall_mm IS '冻结快照中的旧雨量；调差独立留证，不回写小时记录、不改日结快照';

CREATE TABLE IF NOT EXISTS overflow_daily_settlement (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code                 VARCHAR(64)   NOT NULL,
    day_utc                     DATE          NOT NULL,
    normal_avg                  DECIMAL(18,4),
    all_avg                     DECIMAL(18,4) NOT NULL,
    normal_denominator          INT           NOT NULL,
    all_denominator             INT           NOT NULL,
    requested_hours             INT           NOT NULL,
    present_count               INT           NOT NULL,
    excluded_count              INT           NOT NULL,
    pending_count               INT           NOT NULL,
    missing_point_count         INT           NOT NULL,
    settled_at                  TIMESTAMP     NOT NULL,
    request_id                  VARCHAR(80),
    created_at                  TIMESTAMP     NOT NULL,
    CONSTRAINT uk_overflow_settlement UNIQUE (outlet_code, day_utc),
    CONSTRAINT chk_settlement_denom CHECK (normal_denominator >= 0 AND all_denominator > 0
        AND all_denominator = present_count AND normal_denominator = present_count - excluded_count
        AND present_count + missing_point_count = requested_hours)
);
COMMENT ON COLUMN overflow_daily_settlement.day_utc IS '日结所属 UTC 日期，半开区间 [00:00,24:00)';
COMMENT ON COLUMN overflow_daily_settlement.normal_avg IS '正常排放均值：仅排除证据齐全的 COMPLIANT 合规溢流小时；快照后不随后续调差改变';
COMMENT ON COLUMN overflow_daily_settlement.all_avg IS '全部排放均值：不排除任何小时';
COMMENT ON COLUMN overflow_daily_settlement.missing_point_count IS '缺点数=24-实际到点小时数；分母与缺点在快照中明示';
