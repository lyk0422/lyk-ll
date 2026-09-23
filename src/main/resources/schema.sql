-- 需求响应事件执行与结算冻结：H2（MODE=MySQL）建表脚本，仅用于内嵌库自动初始化。
-- 所有时间字段以 UTC 存储；功率单位为 kW；DECIMAL 为有限非负小数。

CREATE TABLE IF NOT EXISTS dr_event (
    event_id         VARCHAR(64)  NOT NULL,
    start_time       TIMESTAMP    NOT NULL,
    end_time         TIMESTAMP    NOT NULL,
    interval_minutes INT          NOT NULL,
    version          INT          NOT NULL DEFAULT 1,
    frozen           TINYINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL,
    CONSTRAINT pk_dr_event PRIMARY KEY (event_id),
    CONSTRAINT ck_dr_event_time CHECK (end_time > start_time),
    CONSTRAINT ck_dr_event_interval CHECK (interval_minutes > 0)
);
COMMENT ON TABLE dr_event IS '需求响应事件表：UTC 半开区间 [start_time,end_time)，固定分钟粒度';
COMMENT ON COLUMN dr_event.event_id IS '事件业务主键';
COMMENT ON COLUMN dr_event.start_time IS '事件开始时刻（UTC，含）';
COMMENT ON COLUMN dr_event.end_time IS '事件结束时刻（UTC，不含）';
COMMENT ON COLUMN dr_event.interval_minutes IS '固定区间粒度（分钟，正整数，区间长度须被其整除）';
COMMENT ON COLUMN dr_event.version IS '事件当前版本号，每次登记电表或写入/更正曲线递增，冻结时校验 expectedVersion';
COMMENT ON COLUMN dr_event.frozen IS '冻结状态：0=未冻结，1=已冻结（冻结后结果不可改写）';

CREATE TABLE IF NOT EXISTS dr_enrollment (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    event_id         VARCHAR(64)  NOT NULL,
    meter_id         VARCHAR(64)  NOT NULL,
    committed_kw     DECIMAL(18,6) NOT NULL,
    baseline_version VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    CONSTRAINT pk_dr_enrollment PRIMARY KEY (id),
    CONSTRAINT uq_dr_enrollment_event_meter UNIQUE (event_id, meter_id),
    CONSTRAINT ck_dr_enrollment_committed CHECK (committed_kw >= 0)
);
COMMENT ON TABLE dr_enrollment IS '事件参与电表登记表：同一电表在时间重叠事件中不得重复承诺';
COMMENT ON COLUMN dr_enrollment.event_id IS '所属事件 ID';
COMMENT ON COLUMN dr_enrollment.meter_id IS '电表业务编号';
COMMENT ON COLUMN dr_enrollment.committed_kw IS '承诺削减功率（kW，非负）';
COMMENT ON COLUMN dr_enrollment.baseline_version IS '登记时指定的基线版本标识';

CREATE TABLE IF NOT EXISTS curve_point_current (
    event_id      VARCHAR(64)   NOT NULL,
    meter_id      VARCHAR(64)   NOT NULL,
    interval_index INT          NOT NULL,
    data_type     VARCHAR(8)    NOT NULL,
    point_value   DECIMAL(18,6) NOT NULL,
    version       INT           NOT NULL,
    updated_at    TIMESTAMP     NOT NULL,
    CONSTRAINT pk_curve_point_current PRIMARY KEY (event_id, meter_id, interval_index, data_type),
    CONSTRAINT ck_curve_current_value CHECK (point_value >= 0),
    CONSTRAINT ck_curve_current_index CHECK (interval_index >= 0),
    CONSTRAINT ck_curve_current_version CHECK (version >= 1)
);
COMMENT ON TABLE curve_point_current IS '曲线当前值表：同一电表、区间编号、数据类型仅一个当前版本';
COMMENT ON COLUMN curve_point_current.interval_index IS '事件内区间编号，从 0 开始，区间起点=事件起点+编号*粒度';
COMMENT ON COLUMN curve_point_current.data_type IS '数据类型：BASELINE=基线，ACTUAL=实测';
COMMENT ON COLUMN curve_point_current.point_value IS '功率值（kW，有限非负小数）';
COMMENT ON COLUMN curve_point_current.version IS '当前版本号，初始为 1，更正时按前版本号递增';

CREATE TABLE IF NOT EXISTS curve_point_history (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    event_id       VARCHAR(64)   NOT NULL,
    meter_id       VARCHAR(64)   NOT NULL,
    interval_index INT           NOT NULL,
    data_type      VARCHAR(8)    NOT NULL,
    version        INT           NOT NULL,
    point_value    DECIMAL(18,6) NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    CONSTRAINT pk_curve_point_history PRIMARY KEY (id),
    CONSTRAINT uq_curve_history_version UNIQUE (event_id, meter_id, interval_index, data_type, version),
    CONSTRAINT ck_curve_history_value CHECK (point_value >= 0)
);
COMMENT ON TABLE curve_point_history IS '曲线版本流水表：每次写入或更正追加不可变版本';

CREATE TABLE IF NOT EXISTS settlement (
    settlement_id    VARCHAR(64)   NOT NULL,
    event_id         VARCHAR(64)   NOT NULL,
    request_id       VARCHAR(128)  NOT NULL,
    event_version    INT           NOT NULL,
    total_delivered_kw DECIMAL(20,6) NOT NULL,
    frozen_at        TIMESTAMP     NOT NULL,
    CONSTRAINT pk_settlement PRIMARY KEY (settlement_id),
    CONSTRAINT uq_settlement_event UNIQUE (event_id),
    CONSTRAINT uq_settlement_request UNIQUE (request_id)
);
COMMENT ON TABLE settlement IS '冻结结算主表：每事件仅一笔，重放返回首笔结果';
COMMENT ON COLUMN settlement.event_version IS '冻结时锁定的事件版本证据';
COMMENT ON COLUMN settlement.total_delivered_kw IS '逐区间交付量合计（kW，固定粒度下可直接比较）';

CREATE TABLE IF NOT EXISTS settlement_interval (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    settlement_id    VARCHAR(64)   NOT NULL,
    event_id         VARCHAR(64)   NOT NULL,
    meter_id         VARCHAR(64)   NOT NULL,
    interval_index   INT           NOT NULL,
    interval_start   TIMESTAMP     NOT NULL,
    baseline_value   DECIMAL(18,6) NOT NULL,
    actual_value     DECIMAL(18,6) NOT NULL,
    baseline_version INT           NOT NULL,
    actual_version   INT           NOT NULL,
    delivered_kw     DECIMAL(18,6) NOT NULL,
    CONSTRAINT pk_settlement_interval PRIMARY KEY (id),
    CONSTRAINT uq_settlement_interval UNIQUE (settlement_id, meter_id, interval_index)
);
COMMENT ON TABLE settlement_interval IS '冻结逐区间证据表：交付量=min(承诺值,max(0,基线-实测))';

CREATE TABLE IF NOT EXISTS freeze_request (
    request_id   VARCHAR(128) NOT NULL,
    event_id     VARCHAR(64)  NOT NULL,
    fingerprint  VARCHAR(64)  NOT NULL,
    settlement_id VARCHAR(64) NOT NULL,
    created_at   TIMESTAMP     NOT NULL,
    CONSTRAINT pk_freeze_request PRIMARY KEY (request_id)
);
COMMENT ON TABLE freeze_request IS '冻结请求幂等表：同键同指纹重放，同键异参冲突';
COMMENT ON COLUMN freeze_request.fingerprint IS '完整参数指纹（事件版本与各电表基线/实测版本的 SHA-256）';

CREATE TABLE IF NOT EXISTS adjustment_advice (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    settlement_id      VARCHAR(64)   NOT NULL,
    event_id           VARCHAR(64)   NOT NULL,
    meter_id           VARCHAR(64)   NOT NULL,
    interval_index     INT           NOT NULL,
    frozen_delivered_kw DECIMAL(18,6) NOT NULL,
    revised_baseline_kw DECIMAL(18,6),
    revised_actual_kw   DECIMAL(18,6),
    revised_delivered_kw DECIMAL(18,6),
    delta_kw           DECIMAL(18,6) NOT NULL,
    baseline_version   INT,
    actual_version     INT,
    created_at         TIMESTAMP     NOT NULL,
    updated_at         TIMESTAMP     NOT NULL,
    CONSTRAINT pk_adjustment_advice PRIMARY KEY (id),
    CONSTRAINT uq_adjustment_interval UNIQUE (settlement_id, meter_id, interval_index)
);
COMMENT ON TABLE adjustment_advice IS '冻结后更正形成的独立调整建议：不回写冻结结果';
COMMENT ON COLUMN adjustment_advice.delta_kw IS '重算交付量-冻结交付量（kW），正为补结、负为追回';
COMMENT ON COLUMN adjustment_advice.revised_baseline_kw IS '更正后基线（kW）；该侧未更正时为空，表示沿用冻结值';
