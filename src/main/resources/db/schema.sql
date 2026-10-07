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

-- ================= 雨季溢流证据与双口径统计（cems-02 新增，合成数据，不用于生产） =================
-- 溢流证据独立留痕：原排放浓度读数、超标链与限排令记录不因溢流标记而改变。

CREATE TABLE IF NOT EXISTS overflow_permit (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code         VARCHAR(64)  NOT NULL,
    version             INT          NOT NULL,
    window_start_utc    TIMESTAMP    NOT NULL,
    window_end_utc      TIMESTAMP    NOT NULL,
    min_rainfall_mm     DECIMAL(18,4) NOT NULL,
    max_consecutive_hours INT        NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    CONSTRAINT uk_overflow_permit_version UNIQUE (outlet_code, version)
);
COMMENT ON COLUMN overflow_permit.outlet_code IS '排口编码，关联 discharge_outlet.outlet_code';
COMMENT ON COLUMN overflow_permit.version IS '许可版本号：从 1 开始，每次成功变更加 1；同排口内唯一';
COMMENT ON COLUMN overflow_permit.window_start_utc IS 'UTC 半开适用窗口起点（含本时刻），秒级精确时刻';
COMMENT ON COLUMN overflow_permit.window_end_utc IS 'UTC 半开适用窗口终点（不含本时刻）';
COMMENT ON COLUMN overflow_permit.min_rainfall_mm IS '最低小时雨量门槛，单位 mm；恰等门槛合法';
COMMENT ON COLUMN overflow_permit.max_consecutive_hours IS '允许的最大连续溢流小时数；连续段严格超过即专项异常';
COMMENT ON COLUMN overflow_permit.status IS '许可状态：ACTIVE 现行 / SUPERSEDED 已被新版本取代';
COMMENT ON COLUMN overflow_permit.created_at IS '创建时刻（Asia/Shanghai 本地时间，由应用时钟写入）';

CREATE TABLE IF NOT EXISTS overflow_hour (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code     VARCHAR(64)  NOT NULL,
    hour_utc        TIMESTAMP    NOT NULL,
    rainfall_mm     DECIMAL(18,4),
    overflow_flag   BOOLEAN      NOT NULL,
    avg_value       DECIMAL(18,4) NOT NULL,
    mark_status     VARCHAR(16)  NOT NULL,
    permit_version  INT,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_overflow_hour UNIQUE (outlet_code, hour_utc)
);
COMMENT ON COLUMN overflow_hour.hour_utc IS 'UTC 整点时刻（分秒必须为 0），排口＋整点唯一';
COMMENT ON COLUMN overflow_hour.rainfall_mm IS '该小时雨量，单位 mm；NULL 表示缺雨量（只能待核，不当零也不豁免）';
COMMENT ON COLUMN overflow_hour.overflow_flag IS '该小时是否发生溢流（合成标记）';
COMMENT ON COLUMN overflow_hour.avg_value IS '该小时排放浓度均值，单位 mg/L（合成数据）；原浓度记录永远保留';
COMMENT ON COLUMN overflow_hour.mark_status IS '溢流标记状态：PENDING 待核（缺雨量）/ COMPLIANT 合规溢流（证据齐全）/ NORMAL 非合规溢流或普通小时';
COMMENT ON COLUMN overflow_hour.permit_version IS '判定合规溢流时所用许可版本；非合规标记为 NULL';
COMMENT ON COLUMN overflow_hour.created_at IS '创建时刻（Asia/Shanghai 本地时间）';
COMMENT ON COLUMN overflow_hour.updated_at IS '最近更新时刻（Asia/Shanghai 本地时间）';

CREATE TABLE IF NOT EXISTS overflow_counter (
    outlet_code      VARCHAR(64) PRIMARY KEY,
    rainfall_version INT          NOT NULL,
    updated_at       TIMESTAMP    NOT NULL
);
COMMENT ON COLUMN overflow_counter.rainfall_version IS '雨量版本号：首次上报小时数据置 1，每次成功雨量更正/调差加 1；无记录表示尚无雨量数据';
COMMENT ON COLUMN overflow_counter.updated_at IS '最近变更时刻（Asia/Shanghai 本地时间）';

CREATE TABLE IF NOT EXISTS overflow_review (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code      VARCHAR(64)  NOT NULL,
    day_utc          DATE         NOT NULL,
    permit_version   INT,
    rainfall_version INT          NOT NULL,
    normal_avg       DECIMAL(18,4),
    normal_count     INT          NOT NULL,
    all_avg          DECIMAL(18,4),
    all_count        INT          NOT NULL,
    missing_count    INT          NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    frozen_at        TIMESTAMP    NOT NULL,
    request_id       VARCHAR(80),
    CONSTRAINT uk_overflow_review UNIQUE (outlet_code, day_utc)
);
COMMENT ON COLUMN overflow_review.day_utc IS 'UTC 日结日期；同排口每日仅可冻结一次';
COMMENT ON COLUMN overflow_review.permit_version IS '冻结时现行溢流许可版本；无许可为 NULL';
COMMENT ON COLUMN overflow_review.rainfall_version IS '冻结时雨量版本号；无雨量数据记 0';
COMMENT ON COLUMN overflow_review.normal_avg IS '正常排放均值（mg/L）：仅排除证据齐全的合规溢流小时；空集合为 NULL（无结果而非零）';
COMMENT ON COLUMN overflow_review.normal_count IS '正常排放口径分母（计入小时数）';
COMMENT ON COLUMN overflow_review.all_avg IS '全部排放均值（mg/L）：不排除任何已上报小时；空集合为 NULL';
COMMENT ON COLUMN overflow_review.all_count IS '全部排放口径分母（计入小时数）';
COMMENT ON COLUMN overflow_review.missing_count IS '当日缺点数：UTC 日内无小时数据的整点数';
COMMENT ON COLUMN overflow_review.status IS '快照状态：FROZEN 已冻结（冻结后只能独立调差，不改本快照）';
COMMENT ON COLUMN overflow_review.frozen_at IS '冻结时刻（Asia/Shanghai 本地时间）';
COMMENT ON COLUMN overflow_review.request_id IS '冻结请求的幂等键';

CREATE TABLE IF NOT EXISTS overflow_adjustment (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code      VARCHAR(64)  NOT NULL,
    day_utc          DATE         NOT NULL,
    hour_utc         TIMESTAMP    NOT NULL,
    old_rainfall_mm  DECIMAL(18,4),
    new_rainfall_mm  DECIMAL(18,4) NOT NULL,
    rainfall_version INT          NOT NULL,
    request_id       VARCHAR(80),
    created_at       TIMESTAMP    NOT NULL
);
COMMENT ON COLUMN overflow_adjustment.day_utc IS '所属已冻结 UTC 日结日期';
COMMENT ON COLUMN overflow_adjustment.hour_utc IS '被调差的 UTC 整点';
COMMENT ON COLUMN overflow_adjustment.old_rainfall_mm IS '调差前雨量（mm）；NULL 表示原缺雨量';
COMMENT ON COLUMN overflow_adjustment.new_rainfall_mm IS '调差后雨量（mm）';
COMMENT ON COLUMN overflow_adjustment.rainfall_version IS '本次调差后的雨量版本号';
COMMENT ON COLUMN overflow_adjustment.request_id IS '调差请求的幂等键';
COMMENT ON COLUMN overflow_adjustment.created_at IS '调差登记时刻（Asia/Shanghai 本地时间）；独立调差不改日结快照';

CREATE TABLE IF NOT EXISTS overflow_anomaly (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code       VARCHAR(64)  NOT NULL,
    segment_start_utc TIMESTAMP    NOT NULL,
    segment_end_utc   TIMESTAMP    NOT NULL,
    hour_count        INT          NOT NULL,
    max_allowed       INT          NOT NULL,
    permit_version    INT          NOT NULL,
    reason            VARCHAR(255) NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    CONSTRAINT uk_overflow_anomaly UNIQUE (outlet_code, segment_start_utc, segment_end_utc)
);
COMMENT ON COLUMN overflow_anomaly.segment_start_utc IS '连续合规溢流段起点（UTC 整点，含）；跨许可版本或出现缺点必须断段';
COMMENT ON COLUMN overflow_anomaly.segment_end_utc IS '连续合规溢流段终点（UTC 整点，不含）';
COMMENT ON COLUMN overflow_anomaly.hour_count IS '该连续段小时数（严格超过许可最长连续小时数才登记）';
COMMENT ON COLUMN overflow_anomaly.max_allowed IS '判定所用许可允许的最大连续溢流小时数';
COMMENT ON COLUMN overflow_anomaly.permit_version IS '该段所用溢流许可版本';
COMMENT ON COLUMN overflow_anomaly.reason IS '专项异常原因说明';
COMMENT ON COLUMN overflow_anomaly.status IS '异常状态：OPEN 待处置 / CLOSED 已处置';
COMMENT ON COLUMN overflow_anomaly.created_at IS '登记时刻（Asia/Shanghai 本地时间）';

-- ================= 监测仪检修人工替代证据与有效监测统计（cems-03 新增，合成数据，不用于生产） =================
-- 补证独立留痕：原机测读数、缺点告警、超标链与限排令不被删除或撤销；替代超标仅追加独立来源。

CREATE TABLE IF NOT EXISTS maintenance_permit (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code         VARCHAR(64)  NOT NULL,
    version             INT          NOT NULL,
    window_start_utc    TIMESTAMP    NOT NULL,
    window_end_utc      TIMESTAMP    NOT NULL,
    confirmer_a         VARCHAR(64)  NOT NULL,
    confirmer_b         VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    confirmed_at        TIMESTAMP    NOT NULL,
    CONSTRAINT uk_maintenance_permit_version UNIQUE (outlet_code, version),
    CONSTRAINT chk_maintenance_confirmers_distinct CHECK (confirmer_a <> confirmer_b)
);
COMMENT ON COLUMN maintenance_permit.version IS '检修许可版本号：从 1 开始，每次成功变更加 1；同排口内唯一';
COMMENT ON COLUMN maintenance_permit.window_start_utc IS 'UTC 检修半开窗口起点（含本时刻），秒级精确时刻';
COMMENT ON COLUMN maintenance_permit.window_end_utc IS 'UTC 检修半开窗口终点（不含本时刻）；替代小时 [hour,hour+1) 必须完整落窗';
COMMENT ON COLUMN maintenance_permit.confirmer_a IS '确认人 A（合成人员标识），须与确认人 B 不同';
COMMENT ON COLUMN maintenance_permit.confirmer_b IS '确认人 B（合成人员标识），须与确认人 A 不同';
COMMENT ON COLUMN maintenance_permit.status IS '许可状态：ACTIVE 现行 / SUPERSEDED 已被新版本取代';
COMMENT ON COLUMN maintenance_permit.created_at IS '创建时刻（Asia/Shanghai 本地时间，由应用时钟写入）';
COMMENT ON COLUMN maintenance_permit.confirmed_at IS '双人确认时刻（Asia/Shanghai 本地时间，与创建同事务）';

CREATE TABLE IF NOT EXISTS lab_report (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_no           VARCHAR(64)  NOT NULL,
    outlet_code         VARCHAR(64)  NOT NULL,
    window_start_utc    TIMESTAMP    NOT NULL,
    window_end_utc      TIMESTAMP    NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    CONSTRAINT uk_lab_report_no UNIQUE (report_no)
);
COMMENT ON COLUMN lab_report.report_no IS '合成检验报告编号，全局唯一；跨排口引用拒绝；同一采样窗口允许不同报告号（对应不同版本检验证据）';
COMMENT ON COLUMN lab_report.window_start_utc IS '采样 UTC 半开窗口起点（含本时刻）；替代该小时时必须恰等于整点 hour';
COMMENT ON COLUMN lab_report.window_end_utc IS '采样 UTC 半开窗口终点（不含本时刻）；替代该小时时必须恰等于 hour+1，跨小时报告拒绝';
COMMENT ON COLUMN lab_report.created_at IS '登记时刻（Asia/Shanghai 本地时间）';

CREATE TABLE IF NOT EXISTS replacement_hour (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code         VARCHAR(64)  NOT NULL,
    hour_utc            TIMESTAMP    NOT NULL,
    version             INT          NOT NULL,
    concentration       DECIMAL(18,4) NOT NULL,
    report_no           VARCHAR(64)  NOT NULL,
    submitter           VARCHAR(64)  NOT NULL,
    reviewer            VARCHAR(64),
    status              VARCHAR(16)  NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    confirmed_at        TIMESTAMP,
    request_id          VARCHAR(80),
    active_slot         VARCHAR(64),
    CONSTRAINT uk_replacement_version UNIQUE (outlet_code, hour_utc, version),
    CONSTRAINT uk_replacement_active UNIQUE (outlet_code, hour_utc, active_slot)
);
COMMENT ON COLUMN replacement_hour.hour_utc IS '被替代的缺机测 UTC 整点（分秒为 0）；排口＋整点＋版本唯一';
COMMENT ON COLUMN replacement_hour.version IS '该小时替代版本号：从 1 起，每次成功提交（旧版失效后）加 1';
COMMENT ON COLUMN replacement_hour.concentration IS '人工替代浓度（mg/L，合成）：有限非负数，缺报告不能按零浓度填补';
COMMENT ON COLUMN replacement_hour.report_no IS '同排口合成检验报告编号；报告窗口必须与该小时精确一致';
COMMENT ON COLUMN replacement_hour.submitter IS '替代证据提交人（合成人员标识）';
COMMENT ON COLUMN replacement_hour.reviewer IS '替代复核人（须不同于提交人）；未复核为空';
COMMENT ON COLUMN replacement_hour.status IS '版本状态：PENDING 待复核 / EFFECTIVE 生效中 / SUPERSEDED 被新版本替代 / INVALIDATED 补到合法机测后失效';
COMMENT ON COLUMN replacement_hour.confirmed_at IS '复核生效时刻（Asia/Shanghai 本地时间）；未复核为空';
COMMENT ON COLUMN replacement_hour.request_id IS '提交请求幂等键';
COMMENT ON COLUMN replacement_hour.active_slot IS '生效中占槽=排口编码，仅 EFFECTIVE 版本非空，SUPERSEDED/INVALIDATED/PENDING 置空；配合唯一约束保证同小时最多一个当前有效版本';

CREATE TABLE IF NOT EXISTS maintenance_review (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code         VARCHAR(64)  NOT NULL,
    day_utc             DATE         NOT NULL,
    permit_version      INT,
    effective_avg       DECIMAL(18,4),
    counted_count       INT          NOT NULL,
    missing_count       INT          NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    frozen_at           TIMESTAMP    NOT NULL,
    request_id          VARCHAR(80),
    CONSTRAINT uk_maintenance_review UNIQUE (outlet_code, day_utc)
);
COMMENT ON COLUMN maintenance_review.day_utc IS 'UTC 日结日期；同排口每日仅可封账一次';
COMMENT ON COLUMN maintenance_review.permit_version IS '封账时现行检修许可版本；无许可为 NULL';
COMMENT ON COLUMN maintenance_review.effective_avg IS '有效监测均值（mg/L）：机测优先、无机测才取有效替代；空集合为 NULL（无结果而非零）';
COMMENT ON COLUMN maintenance_review.counted_count IS '有效口径分母（计入小时数，机测与有效替代不双计）';
COMMENT ON COLUMN maintenance_review.missing_count IS '当日缺点数：UTC 日内既无机测也无有效替代的整点数';
COMMENT ON COLUMN maintenance_review.status IS '快照状态：FROZEN 已冻结（冻结后只能独立调差，不改本快照）';
COMMENT ON COLUMN maintenance_review.frozen_at IS '封账时刻（Asia/Shanghai 本地时间）';
COMMENT ON COLUMN maintenance_review.request_id IS '封账请求幂等键';

CREATE TABLE IF NOT EXISTS maintenance_review_item (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code             VARCHAR(64)  NOT NULL,
    day_utc                 DATE         NOT NULL,
    hour_utc                TIMESTAMP    NOT NULL,
    source                  VARCHAR(16)  NOT NULL,
    concentration           DECIMAL(18,4) NOT NULL,
    replacement_version     INT,
    report_no               VARCHAR(64),
    CONSTRAINT uk_maintenance_review_item UNIQUE (outlet_code, day_utc, hour_utc)
);
COMMENT ON COLUMN maintenance_review_item.source IS '封账时该小时来源：MACHINE 合法机测 / REPLACEMENT 有效替代（机测优先，两者不双计）';
COMMENT ON COLUMN maintenance_review_item.replacement_version IS '来源为替代时冻结的替代版本；机测为 NULL';
COMMENT ON COLUMN maintenance_review_item.report_no IS '来源为替代时冻结的检验报告号；机测为 NULL';

CREATE TABLE IF NOT EXISTS maintenance_adjustment (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code             VARCHAR(64)  NOT NULL,
    day_utc                 DATE         NOT NULL,
    hour_utc                TIMESTAMP    NOT NULL,
    change_type             VARCHAR(24)  NOT NULL,
    old_source              VARCHAR(16),
    old_value               DECIMAL(18,4),
    new_source              VARCHAR(16)  NOT NULL,
    new_value               DECIMAL(18,4) NOT NULL,
    replacement_version     INT,
    request_id              VARCHAR(80),
    created_at              TIMESTAMP    NOT NULL
);
COMMENT ON COLUMN maintenance_adjustment.change_type IS '调差类型：MACHINE_BACKFILL 封账后补到合法机测 / REPLACEMENT_CORRECTION 封账后更正替代';
COMMENT ON COLUMN maintenance_adjustment.old_source IS '冻结时旧来源：MACHINE/REPLACEMENT；原缺点（无来源）为 NULL';
COMMENT ON COLUMN maintenance_adjustment.old_value IS '冻结时旧值（mg/L）；原缺点为 NULL';
COMMENT ON COLUMN maintenance_adjustment.new_source IS '调差后新来源：MACHINE/REPLACEMENT';
COMMENT ON COLUMN maintenance_adjustment.new_value IS '调差后新值（mg/L）';
COMMENT ON COLUMN maintenance_adjustment.replacement_version IS '调差涉及的替代版本；补机测为 NULL';
COMMENT ON COLUMN maintenance_adjustment.request_id IS '调差请求幂等键';
COMMENT ON COLUMN maintenance_adjustment.created_at IS '调差登记时刻（Asia/Shanghai 本地时间）；独立调差不改封账快照';

CREATE TABLE IF NOT EXISTS substitute_exceedance (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    outlet_code             VARCHAR(64)  NOT NULL,
    hour_utc                TIMESTAMP    NOT NULL,
    replacement_version     INT          NOT NULL,
    limit_value             DECIMAL(18,4) NOT NULL,
    actual_value            DECIMAL(18,4) NOT NULL,
    status                  VARCHAR(16)  NOT NULL,
    created_at              TIMESTAMP    NOT NULL,
    CONSTRAINT uk_substitute_exceedance_version UNIQUE (outlet_code, hour_utc, replacement_version)
);
COMMENT ON COLUMN substitute_exceedance.replacement_version IS '产生该替代超标的替代版本；同小时新版本独立追加，不覆盖旧记录';
COMMENT ON COLUMN substitute_exceedance.limit_value IS '评价所用原排口小时限值（mg/L，含本值达标）';
COMMENT ON COLUMN substitute_exceedance.actual_value IS '替代浓度实测（mg/L，合成）；严格大于限值才追加';
COMMENT ON COLUMN substitute_exceedance.status IS '独立来源状态：ACTIVE 随当前替代有效 / SUPERSEDED 被新替代版本取代 / INVALIDATED 因补到机测失效；历史只留证不删除';
