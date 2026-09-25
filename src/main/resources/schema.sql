-- 城市内涝泵站联动预降服务：H2（MODE=MySQL）建表脚本，随应用启动自动执行。
-- 时间统一为 Asia/Shanghai 时区的本地时间；水位/开度单位见各列注释。

CREATE TABLE warning_level (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    level_code          VARCHAR(16)  NOT NULL,
    level_name          VARCHAR(32)  NOT NULL,
    sort_no             INT          NOT NULL,
    trigger_pre_drain   BOOLEAN      NOT NULL
);
COMMENT ON TABLE  warning_level IS '合成气象预警级别表（蓝/黄/橙/红）';
COMMENT ON COLUMN warning_level.level_code IS '预警级别编码 BLUE/YELLOW/ORANGE/RED';
COMMENT ON COLUMN warning_level.sort_no IS '级别序号，越大越严重，橙色=3 起触发预降';
COMMENT ON COLUMN warning_level.trigger_pre_drain IS '是否触发河道预降：橙色及以上为 TRUE';

CREATE TABLE pump_station (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code       VARCHAR(32)  NOT NULL,
    station_name       VARCHAR(64)  NOT NULL,
    basin_zone         VARCHAR(32)  NOT NULL,
    safety_low_level   DECIMAL(6,2) NOT NULL,
    run_status         VARCHAR(16)  NOT NULL DEFAULT 'STOPPED',
    gate_opening       DECIMAL(5,2) NOT NULL DEFAULT 0
);
COMMENT ON TABLE  pump_station IS '合成泵站台账';
COMMENT ON COLUMN pump_station.station_code IS '泵站唯一编码';
COMMENT ON COLUMN pump_station.basin_zone IS '流域分区编码，同分区仅允许一套活跃启停序列';
COMMENT ON COLUMN pump_station.safety_low_level IS '安全下限水位（米）；严格低于该值立即停泵，恰等合法运行';
COMMENT ON COLUMN pump_station.run_status IS '当前机组状态：RUNNING 运行中 / STOPPED 已停机';
COMMENT ON COLUMN pump_station.gate_opening IS '当前出水闸门开度（百分比 0-100），运行中必须大于 0';
ALTER TABLE pump_station ADD CONSTRAINT uk_pump_station_code UNIQUE (station_code);
ALTER TABLE pump_station ADD CONSTRAINT ck_pump_station_gate
    CHECK (run_status <> 'RUNNING' OR gate_opening > 0);

CREATE TABLE pre_drain_plan (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id          VARCHAR(64)  NOT NULL,
    basin_zone          VARCHAR(32)  NOT NULL,
    warning_level_code  VARCHAR(16)  NOT NULL,
    river_name          VARCHAR(64)  NOT NULL,
    target_level        DECIMAL(6,2) NOT NULL,
    warning_time        TIMESTAMP    NOT NULL,
    gate_opening        DECIMAL(5,2) NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    archived_at         TIMESTAMP,
    fingerprint         VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMP    NOT NULL
);
COMMENT ON TABLE  pre_drain_plan IS '预降决策单：气象预警触发，按流域分区联动各泵站';
COMMENT ON COLUMN pre_drain_plan.request_id IS '预警请求幂等键';
COMMENT ON COLUMN pre_drain_plan.target_level IS '河道预降目标水位（米），复测恰达目标位即停泵';
COMMENT ON COLUMN pre_drain_plan.warning_time IS '预警发布时刻，亦为首轮启机时刻';
COMMENT ON COLUMN pre_drain_plan.gate_opening IS '联动启泵要求的闸门开度（百分比）';
COMMENT ON COLUMN pre_drain_plan.status IS '状态：DRAINING 预降中 / COMPLETED 已停泵 / ARCHIVED 已复盘归档（终态）';
COMMENT ON COLUMN pre_drain_plan.fingerprint IS 'requestId 与全部业务字段的 SHA-256 指纹';
ALTER TABLE pre_drain_plan ADD CONSTRAINT uk_pre_drain_plan_request UNIQUE (request_id);

CREATE TABLE zone_sequence_lock (
    basin_zone   VARCHAR(32) NOT NULL,
    plan_id      BIGINT      NOT NULL,
    locked_at    TIMESTAMP   NOT NULL
);
COMMENT ON TABLE zone_sequence_lock IS '流域分区活跃启停序列锁，序列完成即释放';
ALTER TABLE zone_sequence_lock ADD CONSTRAINT pk_zone_sequence_lock PRIMARY KEY (basin_zone);

CREATE TABLE pump_command (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id        BIGINT,
    station_code   VARCHAR(32)  NOT NULL,
    action         VARCHAR(8)   NOT NULL,
    sequence_no    INT          NOT NULL DEFAULT 0,
    command_time   TIMESTAMP    NOT NULL,
    gate_opening   DECIMAL(5,2) NOT NULL,
    gate_adjusted  BOOLEAN      NOT NULL DEFAULT FALSE,
    request_id     VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL
);
COMMENT ON TABLE  pump_command IS '泵站启停序列台账，复算启停过程以此为准';
COMMENT ON COLUMN pump_command.action IS '启停动作：START 启机 / STOP 停机';
COMMENT ON COLUMN pump_command.sequence_no IS '同一预降单内序列序号，从 1 递增';
COMMENT ON COLUMN pump_command.command_time IS '启停时刻；同站同时刻唯一任务（并发唯一受理）';
COMMENT ON COLUMN pump_command.gate_opening IS '该动作确认的闸门开度（百分比）；START 必须大于 0';
COMMENT ON COLUMN pump_command.gate_adjusted IS '闸门是否已与机组在同一事务内调整到位';
ALTER TABLE pump_command ADD CONSTRAINT uk_pump_command_station_time
    UNIQUE (station_code, command_time);
ALTER TABLE pump_command ADD CONSTRAINT ck_pump_command_action CHECK (action IN ('START', 'STOP'));
ALTER TABLE pump_command ADD CONSTRAINT ck_pump_command_start_gate
    CHECK (action <> 'START' OR (gate_opening > 0 AND gate_adjusted = TRUE));

CREATE TABLE water_level_reading (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code  VARCHAR(32)  NOT NULL,
    reading_time  TIMESTAMP    NOT NULL,
    level         DECIMAL(6,2) NOT NULL,
    plan_id       BIGINT,
    recorded_at   TIMESTAMP    NOT NULL
);
COMMENT ON TABLE  water_level_reading IS '预降期间河道/液位复测数据，按站点+时刻去重';
COMMENT ON COLUMN water_level_reading.level IS '实测水位（米）';
COMMENT ON COLUMN water_level_reading.plan_id IS '关联预降单，归档后迟到数据不入本表';
ALTER TABLE water_level_reading ADD CONSTRAINT uk_reading_station_time
    UNIQUE (station_code, reading_time);

CREATE TABLE late_level_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code  VARCHAR(32)  NOT NULL,
    reading_time  TIMESTAMP    NOT NULL,
    level         DECIMAL(6,2) NOT NULL,
    plan_id       BIGINT,
    recorded_at   TIMESTAMP    NOT NULL
);
COMMENT ON TABLE late_level_event IS '复盘归档终态后迟到的液位数据，仅登记迟到事件';

CREATE TABLE loss_contact_order (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code      VARCHAR(32)  NOT NULL,
    last_reading_time TIMESTAMP,
    declared_at       TIMESTAMP    NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    open_flag         VARCHAR(32),
    request_id        VARCHAR(64)  NOT NULL,
    created_at        TIMESTAMP    NOT NULL
);
COMMENT ON TABLE  loss_contact_order IS '失联派单台账，30 分钟无液位数据（恰满 30 分钟）即判定失联';
COMMENT ON COLUMN loss_contact_order.last_reading_time IS '失联前最后一条液位数据时刻，从无数据时为空';
COMMENT ON COLUMN loss_contact_order.status IS '派单状态：OPEN 待处置 / CLOSED 已闭环';
COMMENT ON COLUMN loss_contact_order.open_flag IS '开放中等于站点编码，闭环后为空；配合唯一约束保证同站仅一张开放单';
ALTER TABLE loss_contact_order ADD CONSTRAINT uk_loss_order_open
    UNIQUE (station_code, open_flag);

CREATE TABLE review_archive (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id      BIGINT      NOT NULL,
    request_id   VARCHAR(64) NOT NULL,
    fingerprint  VARCHAR(64) NOT NULL,
    archived_at  TIMESTAMP   NOT NULL,
    remark       VARCHAR(255),
    created_at   TIMESTAMP   NOT NULL
);
COMMENT ON TABLE review_archive IS '复盘归档记录，归档为终态，重复归档幂等成功';
ALTER TABLE review_archive ADD CONSTRAINT uk_review_archive_plan UNIQUE (plan_id);

CREATE TABLE idempotent_request (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(64)  NOT NULL,
    request_type     VARCHAR(24)  NOT NULL,
    fingerprint      VARCHAR(64)  NOT NULL,
    response_status  INT          NOT NULL,
    response_body    CLOB,
    created_at       TIMESTAMP    NOT NULL
);
COMMENT ON TABLE idempotent_request IS 'requestId 幂等台账：同键同参重放首次结果，同键异参返回 409';
ALTER TABLE idempotent_request ADD CONSTRAINT uk_idempotent_request
    UNIQUE (request_id, request_type);
