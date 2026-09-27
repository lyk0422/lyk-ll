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

-- 雨季溢流证据标记与双口径统计：业务时刻字段均为 UTC；created_at/updated_at 为应用时钟（Asia/Shanghai）。

CREATE TABLE IF NOT EXISTS overflow_permit (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code             VARCHAR(64)  NOT NULL,
    version                 INT          NOT NULL,
    valid_from              TIMESTAMP    NOT NULL,
    valid_to                TIMESTAMP    NOT NULL,
    min_rainfall            DECIMAL(10,2) NOT NULL,
    max_consecutive_hours   INT          NOT NULL,
    created_at              TIMESTAMP    NOT NULL,
    updated_at              TIMESTAMP    NOT NULL,
    CONSTRAINT uk_permit_outlet_version UNIQUE (outlet_code, version)
);
COMMENT ON COLUMN overflow_permit.version IS '许可版本号：从 1 开始，每次成功变更加 1；新增/修改须携带 expectedVersion 乐观校验';
COMMENT ON COLUMN overflow_permit.valid_from IS 'UTC 半开适用窗口起点（含）';
COMMENT ON COLUMN overflow_permit.valid_to IS 'UTC 半开适用窗口终点（不含）；小时 [h,h+1h) 完整落窗才可标合规溢流';
COMMENT ON COLUMN overflow_permit.min_rainfall IS '最低小时雨量门槛，单位 mm（合成）；雨量恰等于门槛合法';
COMMENT ON COLUMN overflow_permit.max_consecutive_hours IS '允许的最大连续溢流小时数；连续合规溢流段超过该值即专项异常';

CREATE TABLE IF NOT EXISTS overflow_hourly (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code      VARCHAR(64)  NOT NULL,
    hour_start       TIMESTAMP    NOT NULL,
    rainfall         DECIMAL(10,2),
    rainfall_version INT          NOT NULL DEFAULT 1,
    overflow_flag    BOOLEAN      NOT NULL,
    eval_status      VARCHAR(24)  NOT NULL,
    permit_version   INT,
    reviewed         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT uk_overflow_hour UNIQUE (outlet_code, hour_start)
);
COMMENT ON COLUMN overflow_hourly.hour_start IS 'UTC 整点，小时区间 [hour_start, hour_start+1h)；排口＋整点唯一';
COMMENT ON COLUMN overflow_hourly.rainfall IS '该小时雨量，单位 mm（合成）；NULL 表示缺雨量，只能待核，不当零也不直接豁免';
COMMENT ON COLUMN overflow_hourly.rainfall_version IS '雨量版本：从 1 开始，复核冻结前更正雨量加 1 并重算；冻结后不得再更正';
COMMENT ON COLUMN overflow_hourly.overflow_flag IS '溢流标记：该小时是否申报雨季溢流';
COMMENT ON COLUMN overflow_hourly.eval_status IS '评估状态：NORMAL 非溢流 / COMPLIANT 合规溢流（证据齐全）/ PENDING_REVIEW 待核（缺雨量）/ NON_COMPLIANT 不合规溢流';
COMMENT ON COLUMN overflow_hourly.permit_version IS '评估所用溢流许可版本，复核时冻结；NULL 表示评估时无许可';
COMMENT ON COLUMN overflow_hourly.reviewed IS '是否已复核冻结；冻结后只能独立调差，不改日结快照';

CREATE TABLE IF NOT EXISTS overflow_daily_snapshot (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code       VARCHAR(64)  NOT NULL,
    day_start         TIMESTAMP    NOT NULL,
    permit_version    INT,
    rainfall_versions VARCHAR(1024),
    normal_avg        DECIMAL(18,4),
    normal_count      INT          NOT NULL,
    all_avg           DECIMAL(18,4),
    all_count         INT          NOT NULL,
    missing_hours     VARCHAR(2048),
    frozen_by         VARCHAR(16)  NOT NULL,
    request_id        VARCHAR(80),
    created_at        TIMESTAMP    NOT NULL,
    CONSTRAINT uk_snapshot_outlet_day UNIQUE (outlet_code, day_start)
);
COMMENT ON COLUMN overflow_daily_snapshot.day_start IS 'UTC 日起点（00:00）；排口＋日唯一，标记复核与日结竞争冻结，先冻者为准、结果一致';
COMMENT ON COLUMN overflow_daily_snapshot.permit_version IS '冻结时所用溢流许可版本，NULL 表示无许可';
COMMENT ON COLUMN overflow_daily_snapshot.rainfall_versions IS '冻结时各小时雨量版本（JSON：UTC整点→版本）';
COMMENT ON COLUMN overflow_daily_snapshot.normal_avg IS '正常排放均值，单位 mg/L：仅排除证据齐全的合规溢流小时；空集合为 NULL（无结果而非零）';
COMMENT ON COLUMN overflow_daily_snapshot.normal_count IS '正常口径分母（计入小时数）';
COMMENT ON COLUMN overflow_daily_snapshot.all_avg IS '全部排放均值，单位 mg/L：不排除任何小时；空集合为 NULL（无结果而非零）';
COMMENT ON COLUMN overflow_daily_snapshot.all_count IS '全口径分母（计入小时数）';
COMMENT ON COLUMN overflow_daily_snapshot.missing_hours IS '缺点明示：区间内无浓度读数的 UTC 整点（JSON 数组）';
COMMENT ON COLUMN overflow_daily_snapshot.frozen_by IS '冻结来源：REVIEW 标记复核 / SETTLEMENT 日结';

CREATE TABLE IF NOT EXISTS overflow_anomaly (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code    VARCHAR(64)  NOT NULL,
    segment_start  TIMESTAMP    NOT NULL,
    segment_end    TIMESTAMP    NOT NULL,
    segment_hours  INT          NOT NULL,
    max_allowed    INT          NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    created_at     TIMESTAMP    NOT NULL
);
COMMENT ON COLUMN overflow_anomaly.segment_start IS '超限连续段起点（UTC 整点，含）';
COMMENT ON COLUMN overflow_anomaly.segment_end IS '超限连续段终点（UTC 整点，不含）';
COMMENT ON COLUMN overflow_anomaly.segment_hours IS '连续合规溢流小时数；连续小时跨许可或出现缺点已断段';
COMMENT ON COLUMN overflow_anomaly.max_allowed IS '该段许可允许的最大连续溢流小时数；segment_hours 超过它即专项异常';
COMMENT ON COLUMN overflow_anomaly.status IS '专项异常状态：OPEN 待处理 / CLOSED 已闭环';

CREATE TABLE IF NOT EXISTS overflow_adjustment (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code  VARCHAR(64)  NOT NULL,
    hour_start   TIMESTAMP    NOT NULL,
    adjust_value DECIMAL(18,4) NOT NULL,
    reason       VARCHAR(255) NOT NULL,
    request_id   VARCHAR(80),
    created_at   TIMESTAMP    NOT NULL
);
COMMENT ON COLUMN overflow_adjustment.hour_start IS '调差对应的 UTC 整点';
COMMENT ON COLUMN overflow_adjustment.adjust_value IS '独立调差值，单位 mg/L（合成，可正可负）；复核冻结后只能独立调差，不改日结快照';
