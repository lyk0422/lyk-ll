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

-- ===================== 雨季溢流（合成许可，UTC 口径） =====================
-- 与既有上海本地时间口径的监测/限排链路完全独立：溢流不改写原浓度超标标记、不取消已生效限排令。

CREATE TABLE IF NOT EXISTS overflow_permit (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code                 VARCHAR(64)  NOT NULL,
    version                     INT          NOT NULL,
    valid_from_utc              TIMESTAMP    NOT NULL,
    valid_to_utc                TIMESTAMP    NOT NULL,
    min_hourly_rainfall_mm      DECIMAL(10,2) NOT NULL,
    max_consecutive_overflow_hours INT       NOT NULL,
    created_at                  TIMESTAMP    NOT NULL,
    CONSTRAINT uk_overflow_permit_version UNIQUE (outlet_code, version)
);
COMMENT ON COLUMN overflow_permit.version IS '溢流许可版本号，从 1 开始；每次携带 expectedVersion 的成功修改新增一行、版本加 1，旧版本永久保留为证据';
COMMENT ON COLUMN overflow_permit.valid_from_utc IS 'UTC 半开适用窗口起点（含），必须为 UTC 整点；溢流小时 [h,h+1) 完整落入 [valid_from,valid_to) 才合规';
COMMENT ON COLUMN overflow_permit.valid_to_utc IS 'UTC 半开适用窗口终点（不含），必须为 UTC 整点；不同版本窗口首尾相接允许、重叠拒绝';
COMMENT ON COLUMN overflow_permit.min_hourly_rainfall_mm IS '最低小时雨量门槛，单位 mm（合成），雨量恰等于门槛合法，缺雨量不得按零处理';
COMMENT ON COLUMN overflow_permit.max_consecutive_overflow_hours IS '允许的最大连续溢流小时数；连续合规溢流段跨许可版本或遇缺测小时必须断段，超出即专项异常';

CREATE TABLE IF NOT EXISTS rainfall_reading (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code     VARCHAR(64)  NOT NULL,
    hour_utc        TIMESTAMP    NOT NULL,
    version         INT          NOT NULL,
    rainfall_mm     DECIMAL(10,2) NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_rainfall_hour_version UNIQUE (outlet_code, hour_utc, version)
);
COMMENT ON COLUMN rainfall_reading.hour_utc IS '雨量所属 UTC 整点小时起点；无行即缺雨量，只能待核，不能当零或直接豁免';
COMMENT ON COLUMN rainfall_reading.version IS '该小时雨量版本，从 1 开始；复核冻结前更正新增版本并重算溢流标记，冻结后只允许独立调差';
COMMENT ON COLUMN rainfall_reading.rainfall_mm IS '该小时实测雨量，单位 mm（合成），0.0 是合法实测值，与缺测语义不同';

CREATE TABLE IF NOT EXISTS rainfall_adjustment (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code             VARCHAR(64)  NOT NULL,
    hour_utc                TIMESTAMP    NOT NULL,
    frozen_rainfall_version INT,
    new_rainfall_version    INT          NOT NULL,
    frozen_rainfall_mm      DECIMAL(10,2),
    new_rainfall_mm         DECIMAL(10,2) NOT NULL,
    reason                  VARCHAR(255) NOT NULL,
    created_at              TIMESTAMP    NOT NULL
);
COMMENT ON COLUMN rainfall_adjustment.frozen_rainfall_version IS '复核冻结时使用的雨量版本；冻结后更正不改日结快照，仅以独立调差留证，可为空表示无冻结引用';

CREATE TABLE IF NOT EXISTS overflow_hourly_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code     VARCHAR(64)  NOT NULL,
    hour_utc        TIMESTAMP    NOT NULL,
    avg_value       DECIMAL(18,4) NOT NULL,
    overflow_flag   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_overflow_hour UNIQUE (outlet_code, hour_utc)
);
COMMENT ON COLUMN overflow_hourly_record.hour_utc IS 'UTC 整点小时起点；该小时数据固定在 UTC 整点，关联同小时雨量与溢流标记';
COMMENT ON COLUMN overflow_hourly_record.avg_value IS '该小时排放浓度，单位 mg/L（合成）；是否溢流不改变浓度事实，双口径均值均以其为计算基础';
COMMENT ON COLUMN overflow_hourly_record.overflow_flag IS '溢流标记：TRUE 表示该小时申报溢流，须凭有效许可＋达标雨量落证后才算证据齐全的合规溢流';

CREATE TABLE IF NOT EXISTS overflow_mark (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code                 VARCHAR(64)  NOT NULL,
    hour_utc                    TIMESTAMP    NOT NULL,
    status                      VARCHAR(20)  NOT NULL,
    candidate_permit_version    INT,
    candidate_rainfall_version  INT,
    candidate_rainfall_mm       DECIMAL(10,2),
    frozen_permit_version       INT,
    frozen_rainfall_version     INT,
    frozen_rainfall_mm          DECIMAL(10,2),
    request_id                  VARCHAR(80),
    frozen_at                   TIMESTAMP,
    created_at                  TIMESTAMP    NOT NULL,
    CONSTRAINT uk_overflow_mark_hour UNIQUE (outlet_code, hour_utc)
);
COMMENT ON COLUMN overflow_mark.status IS '溢流标记状态：PENDING 缺雨量待核 / READY 证据齐全待复核 / INELIGIBLE 雨量更正后不达标 / FROZEN_COMPLIANT 已复核冻结的合规溢流';
COMMENT ON COLUMN overflow_mark.candidate_permit_version IS '复核前候选许可版本，雨量更正触发重算时同步刷新；冻结后以 frozen_* 列为准';
COMMENT ON COLUMN overflow_mark.frozen_permit_version IS '复核冻结所用许可版本；冻结后雨量更正只走独立调差，不改本列与日结快照';
COMMENT ON COLUMN overflow_mark.frozen_rainfall_version IS '复核冻结所用雨量版本；与许可版本、双口径日结在同一冻结事务内一致落库';

CREATE TABLE IF NOT EXISTS overflow_daily_freeze (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code     VARCHAR(64)  NOT NULL,
    day_utc         TIMESTAMP    NOT NULL,
    request_id      VARCHAR(80)  NOT NULL,
    frozen_at       TIMESTAMP    NOT NULL,
    CONSTRAINT uk_overflow_daily_freeze UNIQUE (outlet_code, day_utc)
);
COMMENT ON COLUMN overflow_daily_freeze.day_utc IS 'UTC 日起点 [day,day+1)；唯一约束承担标记复核与日结并发竞争冻结，先提交者冻结，竞争方复用同一快照';

CREATE TABLE IF NOT EXISTS overflow_daily_summary (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code     VARCHAR(64)  NOT NULL,
    day_utc         TIMESTAMP    NOT NULL,
    caliber         VARCHAR(8)   NOT NULL,
    average_value   DECIMAL(18,4),
    denominator     INT          NOT NULL,
    excluded_hours  INT          NOT NULL,
    missing_points  INT          NOT NULL,
    frozen_at       TIMESTAMP    NOT NULL,
    CONSTRAINT uk_overflow_daily_summary UNIQUE (outlet_code, day_utc, caliber),
    CONSTRAINT chk_summary_caliber CHECK (caliber IN ('NORMAL', 'ALL'))
);
COMMENT ON COLUMN overflow_daily_summary.caliber IS '统计口径：NORMAL 正常排放均值（仅排除证据齐全的合规溢流小时）/ ALL 全部排放均值（不排除）';
COMMENT ON COLUMN overflow_daily_summary.average_value IS '口径均值（合成单位 mg/L）；整日无记录时为空结果（average_value 为 NULL）而非 0';
COMMENT ON COLUMN overflow_daily_summary.denominator IS '该口径分母：参与均值的实际小时数；NORMAL=在册小时-合规溢流小时，ALL=在册小时';
COMMENT ON COLUMN overflow_daily_summary.excluded_hours IS '被排除的证据齐全合规溢流小时数；ALL 口径恒为 0';
COMMENT ON COLUMN overflow_daily_summary.missing_points IS '该 UTC 日 24 个整点槽位中缺测（无小时记录）的槽位数，与分母一并明示';

CREATE TABLE IF NOT EXISTS overflow_anomaly (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code         VARCHAR(64)  NOT NULL,
    anomaly_type        VARCHAR(32)  NOT NULL,
    segment_from_utc    TIMESTAMP    NOT NULL,
    segment_to_utc      TIMESTAMP    NOT NULL,
    consecutive_hours   INT          NOT NULL,
    limit_hours         INT          NOT NULL,
    permit_version      INT,
    detail              CLOB,
    created_at          TIMESTAMP    NOT NULL,
    CONSTRAINT uk_overflow_anomaly UNIQUE (outlet_code, anomaly_type, segment_from_utc)
);
COMMENT ON COLUMN overflow_anomaly.anomaly_type IS '专项异常类型：SEGMENT_TOO_LONG 连续合规溢流超过许可最大连续小时数';
COMMENT ON COLUMN overflow_anomaly.consecutive_hours IS '断段规则下实际连续小时数：跨许可版本或出现缺测小时必须断段后再计长';
