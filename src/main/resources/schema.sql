-- 大型游乐设施日检与载客联锁服务：H2 内存库（MODE=MySQL）建表脚本
-- 所有表在 JVM 生命周期内有效，重启后由合成数据初始化器重新播种。

CREATE TABLE IF NOT EXISTS ride (
    id                   VARCHAR(64)  NOT NULL,
    name                 VARCHAR(128) NOT NULL,
    load_threshold       DOUBLE       NOT NULL,
    speed_threshold      DOUBLE       NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    biz_date             DATE         NULL,
    daily_check_passed   BOOLEAN      NOT NULL DEFAULT FALSE,
    trial_passed_count   INT          NOT NULL DEFAULT 0,
    reopen_count         INT          NOT NULL DEFAULT 0,
    open_seq             INT          NOT NULL DEFAULT 0,
    archived_at          TIMESTAMP    NULL,
    updated_at           TIMESTAMP    NOT NULL,
    CONSTRAINT pk_ride PRIMARY KEY (id)
);

COMMENT ON COLUMN ride.id IS '设备业务编号（合成数据）';
COMMENT ON COLUMN ride.name IS '设备名称';
COMMENT ON COLUMN ride.load_threshold IS '载荷阈值，单位 kg；运行读数严格大于该值触发缓停，恰等阈值合格';
COMMENT ON COLUMN ride.speed_threshold IS '速度阈值，单位 m/s；运行读数严格大于该值触发缓停，恰等阈值合格';
COMMENT ON COLUMN ride.status IS '当前状态：PENDING_DAILY_CHECK/TRIAL/READY/OPEN/SLOW_STOPPED/FAULT_STOPPED/RECHECK_PASSED/ARCHIVED';
COMMENT ON COLUMN ride.biz_date IS '当前状态链所属营业日（Asia/Shanghai），NULL 表示当日流程未开始';
COMMENT ON COLUMN ride.daily_check_passed IS '当日日检是否逐项合格；FALSE 时禁止开放';
COMMENT ON COLUMN ride.trial_passed_count IS '当日合格空载试运行次数，恰需 3 次方可开放，第 4 次非法';
COMMENT ON COLUMN ride.reopen_count IS '当日故障/缓停复检后已复开次数，最大 2 次（恰 2 次合法），第 3 次当日禁开';
COMMENT ON COLUMN ride.open_seq IS '当日最近一次开放序号：1 首次开放，2/3 复开，0 未开放';
COMMENT ON COLUMN ride.archived_at IS '闭园归档时间，非空表示终态；归档后仅记录迟到运行事件';

CREATE TABLE IF NOT EXISTS checklist_item (
    id          BIGINT AUTO_INCREMENT NOT NULL,
    ride_id     VARCHAR(64)  NOT NULL,
    item_code   VARCHAR(64)  NOT NULL,
    item_name   VARCHAR(128) NOT NULL,
    CONSTRAINT pk_checklist_item PRIMARY KEY (id),
    CONSTRAINT uq_checklist_item UNIQUE (ride_id, item_code)
);

COMMENT ON COLUMN checklist_item.ride_id IS '适用设备编号';
COMMENT ON COLUMN checklist_item.item_code IS '日检检查项编码（合成清单）';
COMMENT ON COLUMN checklist_item.item_name IS '日检检查项名称（压杠、安全带、联锁装置等）';

CREATE TABLE IF NOT EXISTS daily_check_record (
    id           BIGINT AUTO_INCREMENT NOT NULL,
    request_id   VARCHAR(64)  NULL,
    ride_id      VARCHAR(64)  NOT NULL,
    biz_date     DATE         NOT NULL,
    qualified    BOOLEAN      NOT NULL,
    failed_items VARCHAR(512) NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_daily_check_record PRIMARY KEY (id)
);

COMMENT ON COLUMN daily_check_record.request_id IS '幂等请求编号，NULL 表示复检等内部场景';
COMMENT ON COLUMN daily_check_record.biz_date IS '日检营业日（Asia/Shanghai）';
COMMENT ON COLUMN daily_check_record.qualified IS '清单是否逐项合格；任一不合格为 FALSE，不可进入试运行';
COMMENT ON COLUMN daily_check_record.failed_items IS '不合格检查项编码列表（逗号分隔），全部合格为空串';

CREATE TABLE IF NOT EXISTS daily_check_item_result (
    id          BIGINT AUTO_INCREMENT NOT NULL,
    record_id   BIGINT       NOT NULL,
    item_code   VARCHAR(64)  NOT NULL,
    qualified   BOOLEAN      NOT NULL,
    remark      VARCHAR(255) NOT NULL,
    CONSTRAINT pk_daily_check_item_result PRIMARY KEY (id)
);

COMMENT ON COLUMN daily_check_item_result.record_id IS '所属日检记录 ID';
COMMENT ON COLUMN daily_check_item_result.qualified IS '该检查项是否合格';
COMMENT ON COLUMN daily_check_item_result.remark IS '检查备注（合成描述）';

CREATE TABLE IF NOT EXISTS trial_run (
    id               BIGINT AUTO_INCREMENT NOT NULL,
    request_id       VARCHAR(64)  NOT NULL,
    ride_id          VARCHAR(64)  NOT NULL,
    biz_date         DATE         NOT NULL,
    attempt_seq      INT          NOT NULL,
    pressure_bar_ok  BOOLEAN      NOT NULL,
    seat_belt_ok     BOOLEAN      NOT NULL,
    interlock_ok     BOOLEAN      NOT NULL,
    no_anomaly       BOOLEAN      NOT NULL,
    qualified        BOOLEAN      NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    CONSTRAINT pk_trial_run PRIMARY KEY (id),
    CONSTRAINT uq_trial_request UNIQUE (request_id),
    CONSTRAINT uq_trial_attempt UNIQUE (ride_id, biz_date, attempt_seq)
);

COMMENT ON COLUMN trial_run.attempt_seq IS '当日试运行次序（含不合格尝试），用于复算';
COMMENT ON COLUMN trial_run.pressure_bar_ok IS '压杠装置是否正常；FALSE 则整机立即停运';
COMMENT ON COLUMN trial_run.seat_belt_ok IS '安全带装置是否正常；FALSE 则整机立即停运';
COMMENT ON COLUMN trial_run.interlock_ok IS '联锁装置是否正常；FALSE 则整机立即停运';
COMMENT ON COLUMN trial_run.no_anomaly IS '空载试运行是否无异常';
COMMENT ON COLUMN trial_run.qualified IS '本次试运行是否合格（安全装置全部正常且无异常）';

CREATE TABLE IF NOT EXISTS open_event (
    id          BIGINT AUTO_INCREMENT NOT NULL,
    request_id  VARCHAR(64) NOT NULL,
    ride_id     VARCHAR(64) NOT NULL,
    biz_date    DATE        NOT NULL,
    open_seq    INT         NOT NULL,
    created_at  TIMESTAMP   NOT NULL,
    CONSTRAINT pk_open_event PRIMARY KEY (id),
    CONSTRAINT uq_open_request UNIQUE (request_id),
    CONSTRAINT uq_open_seq UNIQUE (ride_id, biz_date, open_seq)
);

COMMENT ON COLUMN open_event.open_seq IS '开放序号：1 首次开放，2/3 复开；同设备同日同序号唯一，并发开放仅一条成功';

CREATE TABLE IF NOT EXISTS ride_status_event (
    id           BIGINT AUTO_INCREMENT NOT NULL,
    ride_id      VARCHAR(64)  NOT NULL,
    biz_date     DATE         NOT NULL,
    event_type   VARCHAR(32)  NOT NULL,
    request_id   VARCHAR(64)  NULL,
    detail       VARCHAR(512) NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_ride_status_event PRIMARY KEY (id)
);

COMMENT ON COLUMN ride_status_event.event_type IS '启停链事件：DAILY_CHECK_PASSED/TRIAL_COMPLETED/OPEN/REOPEN/SLOW_STOP/FAULT_STOP/RECHECK_PASSED/ARCHIVE/LATE_READING';
COMMENT ON COLUMN ride_status_event.request_id IS '触发事件的幂等请求编号（可空，如系统复检链）';
COMMENT ON COLUMN ride_status_event.detail IS '事件详情，如失效安全装置、超阈值读数、迟到标记';

CREATE TABLE IF NOT EXISTS operation_reading (
    id                   BIGINT AUTO_INCREMENT NOT NULL,
    ride_id              VARCHAR(64) NOT NULL,
    biz_date             DATE        NOT NULL,
    load_value           DOUBLE      NOT NULL,
    speed_value          DOUBLE      NOT NULL,
    triggered_slow_stop  BOOLEAN     NOT NULL,
    late_after_archive   BOOLEAN     NOT NULL,
    created_at           TIMESTAMP   NOT NULL,
    CONSTRAINT pk_operation_reading PRIMARY KEY (id)
);

COMMENT ON COLUMN operation_reading.load_value IS '运行载荷读数，单位 kg';
COMMENT ON COLUMN operation_reading.speed_value IS '运行速度读数，单位 m/s';
COMMENT ON COLUMN operation_reading.triggered_slow_stop IS '是否严格超过载荷或速度阈值并触发缓停';
COMMENT ON COLUMN operation_reading.late_after_archive IS '是否闭园归档后的迟到读数：只记迟到事件，不改变终态';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id     VARCHAR(64)  NOT NULL,
    request_type   VARCHAR(32)  NOT NULL,
    ride_id        VARCHAR(64)  NOT NULL,
    fingerprint    VARCHAR(64)  NOT NULL,
    response_code  INT          NOT NULL,
    response_body  CLOB         NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);

COMMENT ON COLUMN idempotent_request.request_type IS '请求类型：DAILY_CHECK/TRIAL_RUN/OPEN/SAFETY_FAULT/READING/RECHECK/ARCHIVE';
COMMENT ON COLUMN idempotent_request.fingerprint IS 'SHA-256(requestId + 全部业务字段)；同键异参判定为冲突';
COMMENT ON COLUMN idempotent_request.response_code IS '首次请求的 HTTP 状态码，重放时原样返回';
COMMENT ON COLUMN idempotent_request.response_body IS '首次请求的响应体 JSON，重放时原样返回';
