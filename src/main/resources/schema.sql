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

CREATE TABLE IF NOT EXISTS rainfall_hourly (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '雨量记录主键',
    zone_id BIGINT NOT NULL COMMENT '所属测区',
    hour_start TIMESTAMP NOT NULL COMMENT '小时起点（UTC整点；Asia/Shanghai为UTC+8，同为整点）；与测区、版本联合唯一',
    rainfall_mm DOUBLE NOT NULL COMMENT '该小时合成雨量，单位毫米；有限非负',
    version INT NOT NULL COMMENT '更正版本，从1开始；每次显式更正加1，当前值取最大版本',
    corrected_at TIMESTAMP NOT NULL COMMENT '本次上报/更正的提交时刻',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    CONSTRAINT uk_rainfall_zone_hour_version UNIQUE (zone_id, hour_start, version)
) COMMENT='合成雨量小时数据（仅合成规则，不用于生产；JVM退出后丢失）';

CREATE TABLE IF NOT EXISTS threshold_policy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '策略版本主键',
    zone_id BIGINT NOT NULL COMMENT '所属测区',
    version INT NOT NULL COMMENT '策略版本，从1开始；发布时携带expectedVersion等于当前最新版本（无策略为0）',
    yellow_hourly_rate DOUBLE NOT NULL COMMENT '黄色预警基础小时速率阈值，单位毫米/小时；0<黄<红',
    red_hourly_rate DOUBLE NOT NULL COMMENT '红色报警基础小时速率阈值，单位毫米/小时；严格超过才报警，恰等判正常',
    rain_24h_threshold DOUBLE NOT NULL COMMENT '24小时雨量门槛，单位毫米；此前24个完整小时雨量合计达到（含恰等）门槛即启用折减',
    reduction_factor DOUBLE NOT NULL COMMENT '折减系数，0<系数<=1；启用折减时有效阈值=基础阈值×系数',
    effective_at TIMESTAMP NOT NULL COMMENT '生效时刻；不得早于提交时刻，只影响生效时刻（含）之后的读数评价',
    submitted_at TIMESTAMP NOT NULL COMMENT '提交时刻',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT uk_threshold_policy_zone_version UNIQUE (zone_id, version)
) COMMENT='雨量联动阈值策略版本';

CREATE TABLE IF NOT EXISTS resumption_evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '复工证据主键',
    assessment_id BIGINT NOT NULL COMMENT '所属复工评估',
    chain_id BIGINT NOT NULL COMMENT '所属处置链',
    zone_id BIGINT NOT NULL COMMENT '所属测区',
    phase VARCHAR(8) NOT NULL COMMENT '冻结阶段：APPLY申请时冻结、APPROVE批准时冻结',
    window_start TIMESTAMP NOT NULL COMMENT '安全累计窗口起点（取红色触发时刻与最迟变严策略生效时刻的较晚者）',
    window_end TIMESTAMP NOT NULL COMMENT '安全累计窗口终点（复工申请时刻）',
    result VARCHAR(8) NOT NULL COMMENT '冻结结果：PASS门禁通过',
    detail CLOB NOT NULL COMMENT '逐读数判定明细JSON：时刻、小时速率、策略版本、24小时雨量、是否折减、有效阈值、是否通过',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '冻结时间'
) COMMENT='复工安全门禁冻结证据';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) PRIMARY KEY COMMENT '幂等键，来自请求requestId',
    endpoint VARCHAR(128) NOT NULL COMMENT '受理端点',
    fingerprint VARCHAR(64) NOT NULL COMMENT 'requestId与全部业务字段的SHA-256指纹（十六进制）',
    response_status INT NOT NULL COMMENT '首次受理的HTTP状态码',
    response_body CLOB NOT NULL COMMENT '首次受理的响应报文（JSON），同键同参重放时原样返回',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT='幂等受理记录';
