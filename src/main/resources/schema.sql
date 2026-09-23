-- 需求响应事件执行与结算冻结：H2 内存库（MODE=MySQL）建表脚本
-- 时间均为 UTC；功率/电量类数值统一使用 DECIMAL(18,6)，单位在各列注释中说明。

-- 需求响应事件：UTC 半开区间 [start_time, end_time)，固定分钟粒度
CREATE TABLE IF NOT EXISTS dr_event (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_ref        VARCHAR(64)  NOT NULL COMMENT '事件业务编码，调用方提供，全局唯一',
    start_time       TIMESTAMP(3) NOT NULL COMMENT '事件起始时刻 UTC（含），半开区间左端',
    end_time         TIMESTAMP(3) NOT NULL COMMENT '事件结束时刻 UTC（不含），半开区间右端',
    interval_minutes INT          NOT NULL COMMENT '区间粒度（分钟），区间长度必须为其整数倍',
    version          INT          NOT NULL DEFAULT 1 COMMENT '事件聚合版本号，每次登记电表自增，冻结时校验 expectedVersion',
    created_at       TIMESTAMP(3) NOT NULL COMMENT '创建时刻 UTC',
    CONSTRAINT uk_dr_event_ref UNIQUE (event_ref),
    CONSTRAINT ck_dr_event_range CHECK (end_time > start_time AND interval_minutes > 0)
);

-- 电表参与登记：一块电表在一个事件中登记一次，携带承诺削减功率与基线版本标签
CREATE TABLE IF NOT EXISTS dr_enrollment (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id         BIGINT       NOT NULL COMMENT '所属事件 ID',
    meter_id         VARCHAR(64)  NOT NULL COMMENT '电表业务编码',
    committed_value  DECIMAL(18,6) NOT NULL COMMENT '承诺单区间最大交付量（kW，按固定区间折算为同数值电量口径）',
    baseline_version VARCHAR(64)  NOT NULL COMMENT '登记时声明的基线版本标签',
    created_at       TIMESTAMP(3) NOT NULL COMMENT '登记时刻 UTC',
    CONSTRAINT uk_dr_enrollment UNIQUE (event_id, meter_id),
    CONSTRAINT ck_dr_committed CHECK (committed_value >= 0)
);

-- 曲线点：同一电表、区间、数据类型只保留一个当前版本；更正走乐观版本号
CREATE TABLE IF NOT EXISTS dr_curve_point (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id     BIGINT        NOT NULL COMMENT '所属事件 ID',
    meter_id     VARCHAR(64)   NOT NULL COMMENT '电表业务编码',
    data_type    VARCHAR(8)    NOT NULL COMMENT '数据类型：BASELINE=基线，ACTUAL=实测',
    interval_idx INT           NOT NULL COMMENT '事件区间编号，从 0 开始，左闭右开',
    point_value  DECIMAL(18,6) NOT NULL COMMENT '有限非负小数功率值（kW），缺失即不允许结算',
    version      INT           NOT NULL COMMENT '当前版本号，首写为 1，每次更正加 1；更正须携带前版本号',
    updated_at   TIMESTAMP(3)  NOT NULL COMMENT '最近写入/更正时刻 UTC',
    CONSTRAINT uk_dr_curve_point UNIQUE (event_id, meter_id, data_type, interval_idx),
    CONSTRAINT ck_dr_curve_value CHECK (point_value >= 0),
    CONSTRAINT ck_dr_curve_type CHECK (data_type IN ('BASELINE', 'ACTUAL')),
    CONSTRAINT ck_dr_curve_version CHECK (version >= 1)
);

-- 冻结结算头：每个事件至多一笔成功冻结；requestId 全局唯一用于重放
CREATE TABLE IF NOT EXISTS dr_settlement_freeze (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id         BIGINT        NOT NULL COMMENT '被冻结事件 ID',
    request_id       VARCHAR(64)   NOT NULL COMMENT '冻结请求幂等键，同键异参返回 409',
    expected_version INT           NOT NULL COMMENT '冻结时校验通过的事件聚合版本',
    fingerprint      CHAR(64)      NOT NULL COMMENT '完整参数指纹（SHA-256），含事件版本与全部证据版本',
    total_delivered  DECIMAL(18,6) NOT NULL COMMENT '总交付量：各电表各区间交付量之和',
    frozen_at        TIMESTAMP(3)  NOT NULL COMMENT '冻结落账时刻 UTC',
    CONSTRAINT uk_dr_freeze_event UNIQUE (event_id),
    CONSTRAINT uk_dr_freeze_request UNIQUE (request_id)
);

-- 冻结的逐区间证据快照：冻结后原始曲线更正不影响本表
CREATE TABLE IF NOT EXISTS dr_freeze_interval (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    freeze_id         BIGINT        NOT NULL COMMENT '所属冻结头 ID',
    meter_id          VARCHAR(64)   NOT NULL COMMENT '电表业务编码',
    interval_idx      INT           NOT NULL COMMENT '事件区间编号，从 0 开始',
    baseline_value    DECIMAL(18,6) NOT NULL COMMENT '冻结快照中的基线值（kW）',
    actual_value      DECIMAL(18,6) NOT NULL COMMENT '冻结快照中的实测值（kW）',
    baseline_version  INT           NOT NULL COMMENT '冻结证据基线版本号',
    actual_version    INT           NOT NULL COMMENT '冻结证据实测版本号',
    delivered_value   DECIMAL(18,6) NOT NULL COMMENT '区间交付量：min(承诺值, max(0,基线-实测))',
    CONSTRAINT uk_dr_freeze_interval UNIQUE (freeze_id, meter_id, interval_idx)
);

-- 冻结后的更正建议：原始数据允许更正，但只能形成独立建议，不改写冻结结果
CREATE TABLE IF NOT EXISTS dr_adjustment (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id          BIGINT        NOT NULL COMMENT '所属事件 ID',
    freeze_id         BIGINT        NOT NULL COMMENT '关联冻结头 ID',
    meter_id          VARCHAR(64)   NOT NULL COMMENT '电表业务编码',
    interval_idx      INT           NOT NULL COMMENT '事件区间编号，从 0 开始',
    data_type         VARCHAR(8)    NOT NULL COMMENT '被更正数据类型：BASELINE/ACTUAL',
    frozen_version    INT           NOT NULL COMMENT '冻结快照中该点版本号',
    frozen_value      DECIMAL(18,6) NOT NULL COMMENT '冻结快照中该点数值（kW）',
    corrected_version INT           NOT NULL COMMENT '更正后的新版本号',
    corrected_value   DECIMAL(18,6) NOT NULL COMMENT '更正后的新数值（kW）',
    frozen_interval_delivered DECIMAL(18,6) NOT NULL COMMENT '冻结的该区间交付量',
    suggested_interval_delivered DECIMAL(18,6) NOT NULL COMMENT '按更正后数据重算的建议区间交付量',
    suggested_delta   DECIMAL(18,6) NOT NULL COMMENT '建议调整量=建议区间交付量-冻结区间交付量，正负表示补退方向',
    status            VARCHAR(8)    NOT NULL DEFAULT 'OPEN' COMMENT '建议状态：OPEN=待处理',
    created_at        TIMESTAMP(3)  NOT NULL COMMENT '建议生成时刻 UTC',
    CONSTRAINT uk_dr_adjustment UNIQUE (freeze_id, meter_id, interval_idx, data_type),
    CONSTRAINT ck_dr_adjustment_status CHECK (status IN ('OPEN'))
);
