-- 边坡雷达监测预警与停产撤人服务建表脚本（H2，MODE=MySQL）。
-- 时间字段均为 Asia/Shanghai 本地时间；速率先于入库时由位移曲线复算并冗余存储，可再次复算核对。

CREATE TABLE IF NOT EXISTS slope_zone (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '测区主键',
    code VARCHAR(64) NOT NULL COMMENT '测区编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '测区名称',
    warn_hourly_rate DOUBLE NOT NULL COMMENT '黄色预警小时速率阈值，单位毫米/小时；严格超过才预警，恰等判正常',
    alarm_daily_rate DOUBLE NOT NULL COMMENT '红色报警日速率阈值，单位毫米/天；严格超过才报警，恰等判正常',
    safety_hourly_rate DOUBLE NOT NULL COMMENT '复工安全线小时速率，单位毫米/小时；连续24小时低于该值方可申请复工',
    personnel_total INT NOT NULL COMMENT '测区应撤人员总数，撤人确认须全员到位',
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '测区状态：NORMAL正常、STOPPED停产、LOCKED撤人并封锁作业',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT uk_slope_zone_code UNIQUE (code)
) COMMENT='边坡监测测区';

CREATE TABLE IF NOT EXISTS radar_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '读数主键',
    zone_id BIGINT NOT NULL COMMENT '所属测区',
    measured_at TIMESTAMP NOT NULL COMMENT '监测时刻；与测区联合唯一，乱序数据按该时刻归位',
    displacement_mm DOUBLE NOT NULL COMMENT '累计位移，单位毫米',
    hourly_rate DOUBLE NULL COMMENT '小时速率，单位毫米/小时；首条读数无前置点为NULL',
    daily_rate DOUBLE NULL COMMENT '日速率，单位毫米/天；无24小时前基准读数时为NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    CONSTRAINT uk_radar_reading_zone_ts UNIQUE (zone_id, measured_at)
) COMMENT='边坡雷达位移读数';

CREATE TABLE IF NOT EXISTS disposal_chain (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '处置链主键',
    zone_id BIGINT NOT NULL COMMENT '所属测区；同一测区同时刻仅允许一条EXECUTING链',
    level VARCHAR(8) NOT NULL COMMENT '预警级别：YELLOW黄色、RED红色',
    status VARCHAR(16) NOT NULL COMMENT '链状态：EXECUTING执行中、RESUMED已复工、ARCHIVED已归档（终态）',
    triggered_at TIMESTAMP NOT NULL COMMENT '预警触发时刻（取触发读数的监测时刻）',
    evacuate_deadline TIMESTAMP NULL COMMENT '撤人截止时刻，触发后30分钟；恰满30分钟判定为按时',
    evacuated_at TIMESTAMP NULL COMMENT '撤人确认时刻；NULL表示尚未确认',
    archived_at TIMESTAMP NULL COMMENT '归档时刻；NULL表示未归档',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT='预警处置链';

CREATE TABLE IF NOT EXISTS evacuation_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '撤人确认主键',
    chain_id BIGINT NOT NULL COMMENT '所属处置链',
    zone_id BIGINT NOT NULL COMMENT '所属测区',
    confirmed_at TIMESTAMP NOT NULL COMMENT '撤人确认时刻',
    arrived_count INT NOT NULL COMMENT '到位人数，须等于测区应撤总数',
    on_time BOOLEAN NOT NULL COMMENT '是否在30分钟截止前（含恰满30分钟）确认',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT='撤人确认记录';

CREATE TABLE IF NOT EXISTS resumption_assessment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '复工评估主键',
    chain_id BIGINT NOT NULL COMMENT '所属处置链',
    zone_id BIGINT NOT NULL COMMENT '所属测区',
    applied_at TIMESTAMP NOT NULL COMMENT '复工申请时刻；须不早于红色预警触发后24小时（恰满24小时允许）',
    site_check_confirmed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '现场核查是否已确认',
    monitoring_review_confirmed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '监测复核是否已确认',
    site_check_confirmed_at TIMESTAMP NULL COMMENT '现场核查确认时刻（Asia/Shanghai）；NULL表示未确认，复工冻结证据原样记录',
    monitoring_review_confirmed_at TIMESTAMP NULL COMMENT '监测复核确认时刻（Asia/Shanghai）；NULL表示未确认，复工冻结证据原样记录',
    status VARCHAR(16) NOT NULL DEFAULT 'APPLIED' COMMENT '评估状态：APPLIED已申请、APPROVED双确认通过已批准',
    approved_at TIMESTAMP NULL COMMENT '批准时刻（第二个确认到达时刻）；NULL表示未批准',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT='复工评估记录';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '迟到事件主键',
    zone_id BIGINT NOT NULL COMMENT '所属测区',
    chain_id BIGINT NULL COMMENT '关联的已归档处置链',
    measured_at TIMESTAMP NOT NULL COMMENT '迟到数据的监测时刻',
    displacement_mm DOUBLE NOT NULL COMMENT '迟到数据的累计位移，单位毫米',
    reason VARCHAR(128) NOT NULL COMMENT '迟到原因，如ARCHIVED_LATE_DATA（归档后迟到位移）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间'
) COMMENT='归档后迟到位移事件';

CREATE TABLE IF NOT EXISTS rainfall_hour (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '小时雨量主键',
    zone_id BIGINT NOT NULL COMMENT '所属测区',
    hour_utc TIMESTAMP NOT NULL COMMENT '雨量小时起点（UTC整点）；与测区联合唯一，评价时按Asia/Shanghai时刻换算到UTC取点',
    rain_mm DOUBLE NOT NULL COMMENT '该小时降雨量，单位毫米；有限且非负',
    version INT NOT NULL COMMENT '小时数据版本，从1开始；同小时异值显式更正成功后加1',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次写入时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近更正时间',
    CONSTRAINT uk_rainfall_hour_zone_hour UNIQUE (zone_id, hour_utc)
) COMMENT='合成小时雨量（UTC整点，仅JVM内存合成数据）';

CREATE TABLE IF NOT EXISTS threshold_policy_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '策略版本主键',
    zone_id BIGINT NOT NULL COMMENT '所属测区',
    version INT NOT NULL COMMENT '策略版本号，测区内从1开始；发布成功加1，发布须携带expectedVersion',
    yellow_hourly_rate DOUBLE NOT NULL COMMENT '黄色预警小时速率基础阈值，单位毫米/小时；要求0<黄<红，恰等判正常',
    red_daily_rate DOUBLE NOT NULL COMMENT '红色报警日速率基础阈值，单位毫米/天；要求严格大于黄色阈值',
    rain_gate_mm DOUBLE NOT NULL COMMENT '近24个完整小时累计雨量门槛，单位毫米；累计雨量恰达该值即启用折减',
    reduction_factor DOUBLE NOT NULL COMMENT '折减系数，取值(0,1]；启用折减后阈值=基础阈值×系数',
    submitted_at TIMESTAMP NOT NULL COMMENT '策略提交时刻（Asia/Shanghai业务时间）',
    effective_at TIMESTAMP NOT NULL COMMENT '策略生效时刻（Asia/Shanghai）；不得早于提交时刻，仅影响该时刻及之后的读数评价',
    tightened_from_previous BOOLEAN NOT NULL COMMENT '相对上一版本是否变严：黄色/红色基础阈值降低或折减系数变小为TRUE；变宽为FALSE，变宽不撤销既有红色处置链',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    CONSTRAINT uk_threshold_policy_zone_version UNIQUE (zone_id, version)
) COMMENT='雨量联动阈值策略版本';

CREATE TABLE IF NOT EXISTS resumption_evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '复工证据主键',
    assessment_id BIGINT NOT NULL COMMENT '所属复工评估；一次批准仅一条冻结证据',
    chain_id BIGINT NOT NULL COMMENT '所属处置链',
    zone_id BIGINT NOT NULL COMMENT '所属测区',
    frozen_at TIMESTAMP NOT NULL COMMENT '证据冻结时刻（第二次复工确认的confirmedAt，Asia/Shanghai）',
    window_start TIMESTAMP NOT NULL COMMENT '安全连续计时起点：红色触发时刻，或窗口内最近一次变严策略的生效时刻',
    window_end TIMESTAMP NOT NULL COMMENT '证据评价截止时刻（批准时刻）',
    last_tightened_at TIMESTAMP NULL COMMENT '窗口内最近一次策略变严生效时刻；NULL表示窗口内未发生变严切换',
    evidence_json CLOB NOT NULL COMMENT '冻结的一致证据快照（结构化JSON）：逐时刻实际有效阈值、24小时雨量与折减、双确认结果',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    CONSTRAINT uk_resumption_evidence_assessment UNIQUE (assessment_id)
) COMMENT='复工批准冻结的一致证据';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) PRIMARY KEY COMMENT '幂等键，来自请求requestId',
    endpoint VARCHAR(128) NOT NULL COMMENT '受理端点',
    fingerprint VARCHAR(64) NOT NULL COMMENT 'requestId与全部业务字段的SHA-256指纹（十六进制）',
    response_status INT NOT NULL COMMENT '首次受理的HTTP状态码',
    response_body CLOB NOT NULL COMMENT '首次受理的响应报文（JSON），同键同参重放时原样返回',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT='幂等受理记录';
