-- 危化品运输路线管制与押运打卡：建表脚本（H2 MODE=MySQL 兼容语法）

CREATE TABLE IF NOT EXISTS goods_category (
    category_code  VARCHAR(32)  NOT NULL PRIMARY KEY,
    category_name  VARCHAR(128) NOT NULL,
    required_level INT          NOT NULL
);
COMMENT ON COLUMN goods_category.required_level IS '要求的最低路线通行等级，数字越大管制越严';

CREATE TABLE IF NOT EXISTS route_info (
    route_code VARCHAR(32)  NOT NULL PRIMARY KEY,
    route_name VARCHAR(128) NOT NULL,
    pass_level INT          NOT NULL,
    forbidden  TINYINT      NOT NULL DEFAULT 0
);
COMMENT ON COLUMN route_info.pass_level IS '路线通行等级，须不低于危化品类别要求等级';
COMMENT ON COLUMN route_info.forbidden IS '是否禁行路线：1 禁行一律驳回，0 可按等级/时段判定';

CREATE TABLE IF NOT EXISTS route_waypoint_def (
    id              BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    route_code      VARCHAR(32) NOT NULL,
    seq             INT         NOT NULL,
    name            VARCHAR(128) NOT NULL,
    offset_minutes  INT         NOT NULL,
    CONSTRAINT uk_waypoint_def UNIQUE (route_code, seq)
);
COMMENT ON COLUMN route_waypoint_def.seq IS '途经点序号，从 0 起';
COMMENT ON COLUMN route_waypoint_def.offset_minutes IS '相对计划发运时刻的偏移分钟数，计划通过时刻=计划发运+偏移';

CREATE TABLE IF NOT EXISTS control_period (
    id         BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    route_code VARCHAR(32) NOT NULL,
    start_time TIMESTAMP   NOT NULL,
    end_time   TIMESTAMP   NOT NULL
);
COMMENT ON COLUMN control_period.start_time IS '管制起始时刻，半开区间[start,end)，计划时刻==起始视为受管制';
COMMENT ON COLUMN control_period.end_time IS '管制结束时刻，半开区间[start,end)，计划时刻==结束视为不受管制';

CREATE TABLE IF NOT EXISTS vehicle (
    plate              VARCHAR(32) NOT NULL PRIMARY KEY,
    frozen             TINYINT     NOT NULL DEFAULT 0,
    frozen_shipment_id BIGINT,
    frozen_at          TIMESTAMP
);
COMMENT ON COLUMN vehicle.frozen IS '是否因失联被冻结：1 冻结，冻结期间拒绝新运单申请';
COMMENT ON COLUMN vehicle.frozen_shipment_id IS '导致冻结的运单ID；未冻结为NULL';

-- 在途车辆占用表：一辆车同时刻至多一个未终态运单（主键冲突即并发拒绝）
CREATE TABLE IF NOT EXISTS vehicle_active_shipment (
    vehicle_plate VARCHAR(32) NOT NULL PRIMARY KEY,
    shipment_id   BIGINT      NOT NULL
);
COMMENT ON TABLE vehicle_active_shipment IS '执行中运单占用：申请时插入，运单终态（驳回/结案/失联）时删除';

CREATE TABLE IF NOT EXISTS shipment (
    id                  BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    shipment_no         VARCHAR(40) NOT NULL,
    vehicle_plate       VARCHAR(32) NOT NULL,
    goods_category      VARCHAR(32) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    is_terminal         TINYINT     NOT NULL DEFAULT 0,
    current_approval_id BIGINT,
    planned_departure   TIMESTAMP   NOT NULL,
    created_at          TIMESTAMP   NOT NULL,
    CONSTRAINT uk_shipment_no UNIQUE (shipment_no)
);
COMMENT ON COLUMN shipment.status IS '运单状态：APPLIED/ROUTE_REJECTED/ROUTE_APPROVED/DISPATCHED/IN_TRANSIT/COMPLETED/LOST_CONTACT';
COMMENT ON COLUMN shipment.is_terminal IS '是否终态：1 终态（驳回/结案/失联），核销与失联并发唯一终态判定依据';
COMMENT ON COLUMN shipment.current_approval_id IS '当前生效路线核准记录ID，改线原子切换';

CREATE TABLE IF NOT EXISTS route_approval (
    id            BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    shipment_id   BIGINT      NOT NULL,
    route_code    VARCHAR(32) NOT NULL,
    version_no    INT         NOT NULL,
    status        VARCHAR(32) NOT NULL,
    reject_reason VARCHAR(512),
    created_at    TIMESTAMP   NOT NULL,
    decided_at    TIMESTAMP,
    CONSTRAINT uk_approval_version UNIQUE (shipment_id, version_no)
);
COMMENT ON COLUMN route_approval.status IS 'PENDING/APPROVED/REJECTED/TERMINATED';
COMMENT ON COLUMN route_approval.version_no IS '路线版本号：首版1，紧急改线递增';

CREATE TABLE IF NOT EXISTS approval_waypoint (
    id           BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    approval_id  BIGINT      NOT NULL,
    shipment_id  BIGINT      NOT NULL,
    seq          INT         NOT NULL,
    name         VARCHAR(128) NOT NULL,
    planned_time TIMESTAMP   NOT NULL,
    state        VARCHAR(32) NOT NULL,
    checked_time TIMESTAMP,
    CONSTRAINT uk_approval_waypoint UNIQUE (approval_id, seq)
);
COMMENT ON COLUMN approval_waypoint.state IS 'PENDING未打卡/TIMED_OUT超时偏离/CHECKED准点/CHECKED_LATE迟到补打';
COMMENT ON COLUMN approval_waypoint.planned_time IS '计划通过时刻，Asia/Shanghai';
COMMENT ON COLUMN approval_waypoint.checked_time IS '实际打卡时刻；未打卡为NULL';

CREATE TABLE IF NOT EXISTS checkin_record (
    id          BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    shipment_id BIGINT    NOT NULL,
    approval_id BIGINT    NOT NULL,
    seq         INT       NOT NULL,
    check_time  TIMESTAMP NOT NULL,
    request_id  VARCHAR(64),
    created_at  TIMESTAMP NOT NULL,
    CONSTRAINT uk_checkin_dedup UNIQUE (shipment_id, approval_id, seq)
);
COMMENT ON TABLE checkin_record IS '押运打卡记录，去重键：运单+路线版本+途经点序号';

CREATE TABLE IF NOT EXISTS shipment_event (
    id          BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    shipment_id BIGINT      NOT NULL,
    event_type  VARCHAR(48) NOT NULL,
    detail      VARCHAR(1024),
    event_time  TIMESTAMP   NOT NULL
);
COMMENT ON COLUMN shipment_event.event_type IS '事件：ROUTE_REJECTED/CHECKIN_REVERSED/DEVIATION_WARNING/LOST_CONTACT/LATE_CHECKIN_AFTER_CLOSE/REROUTE_SWITCH';

CREATE TABLE IF NOT EXISTS idempotent_record (
    request_id    VARCHAR(64) NOT NULL PRIMARY KEY,
    fingerprint   VARCHAR(64) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    http_status   INT,
    response_body CLOB,
    created_at    TIMESTAMP   NOT NULL
);
COMMENT ON COLUMN idempotent_record.fingerprint IS '业务指纹SHA-256，覆盖requestId与全部业务字段；同键异参返回409';
COMMENT ON COLUMN idempotent_record.status IS 'PROCESSING处理中/COMPLETED已完成（含首次失败响应）';
