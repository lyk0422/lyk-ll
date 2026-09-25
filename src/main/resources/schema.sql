-- 危化品运输路线管制与押运打卡服务建表脚本（H2，MODE=MySQL）
-- 所有时间字段均为 DATETIME，按应用时区 Asia/Shanghai 解释；空值含义见各列注释。

CREATE TABLE IF NOT EXISTS route (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    pass_level  INT          NOT NULL,
    CONSTRAINT uk_route_code UNIQUE (code)
);
COMMENT ON TABLE route IS '运输路线主数据（合成数据）';
COMMENT ON COLUMN route.code IS '路线编码，全局唯一';
COMMENT ON COLUMN route.name IS '路线名称';
COMMENT ON COLUMN route.pass_level IS '路线通行等级，危化品等级须小于等于该等级方可通行';

CREATE TABLE IF NOT EXISTS route_restriction (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id   BIGINT   NOT NULL,
    start_time DATETIME NOT NULL,
    end_time   DATETIME NOT NULL,
    CONSTRAINT fk_restriction_route FOREIGN KEY (route_id) REFERENCES route (id)
);
COMMENT ON TABLE route_restriction IS '路线管制时段，半开区间 [start_time, end_time)，计划通过时刻恰为 end_time 视为不受管制';
COMMENT ON COLUMN route_restriction.start_time IS '管制开始时刻（含）';
COMMENT ON COLUMN route_restriction.end_time IS '管制结束时刻（不含）';

CREATE TABLE IF NOT EXISTS route_waypoint (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id       BIGINT      NOT NULL,
    seq            INT         NOT NULL,
    name           VARCHAR(128) NOT NULL,
    offset_minutes INT         NOT NULL,
    CONSTRAINT uk_waypoint_route_seq UNIQUE (route_id, seq),
    CONSTRAINT fk_waypoint_route FOREIGN KEY (route_id) REFERENCES route (id)
);
COMMENT ON TABLE route_waypoint IS '路线途经点模板，计划通过时刻 = 计划发运时刻 + offset_minutes';
COMMENT ON COLUMN route_waypoint.seq IS '途经点序号，路线内从 1 递增';
COMMENT ON COLUMN route_waypoint.offset_minutes IS '相对计划发运时刻的偏移分钟数';

CREATE TABLE IF NOT EXISTS waybill (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id        VARCHAR(64)  NOT NULL,
    fingerprint       VARCHAR(128) NOT NULL,
    vehicle_code      VARCHAR(64)  NOT NULL,
    hazmat_level      INT          NOT NULL,
    route_id          BIGINT       NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    planned_departure DATETIME     NOT NULL,
    route_generation  INT          NOT NULL DEFAULT 1,
    closed_at         DATETIME     NULL,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    CONSTRAINT uk_waybill_request UNIQUE (request_id),
    CONSTRAINT fk_waybill_route FOREIGN KEY (route_id) REFERENCES route (id)
);
COMMENT ON TABLE waybill IS '运输运单，状态机：PENDING(申请)->APPROVED(核准)->IN_TRANSIT(在途)->CLOSED(结案)/LOST(失联)；REJECTED 为核准拒绝';
COMMENT ON COLUMN waybill.request_id IS '幂等请求标识，全局唯一';
COMMENT ON COLUMN waybill.fingerprint IS '幂等指纹，覆盖 requestId 与全部业务字段；同键异参返回 409';
COMMENT ON COLUMN waybill.hazmat_level IS '危化品类别等级，须小于等于路线通行等级';
COMMENT ON COLUMN waybill.route_generation IS '当前路线代次：发运为 1，每次紧急改线生效 +1';
COMMENT ON COLUMN waybill.closed_at IS '结案/失联终态时刻，非终态为空';

CREATE TABLE IF NOT EXISTS waybill_waypoint (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    waybill_id        BIGINT      NOT NULL,
    generation        INT         NOT NULL DEFAULT 1,
    seq               INT         NOT NULL,
    planned_pass_time DATETIME    NOT NULL,
    checkin_time      DATETIME    NULL,
    status            VARCHAR(16) NOT NULL,
    CONSTRAINT uk_wbwp_waybill_gen_seq UNIQUE (waybill_id, generation, seq),
    CONSTRAINT fk_wbwp_waybill FOREIGN KEY (waybill_id) REFERENCES waybill (id)
);
COMMENT ON TABLE waybill_waypoint IS '运单途经点实例，发运时按路线模板生成；状态：PENDING/CHECKED/TIMEOUT/CANCELLED';
COMMENT ON COLUMN waybill_waypoint.generation IS '路线代次，与运单 route_generation 对应，改线后新路线从序号 1 重新编排';
COMMENT ON COLUMN waybill_waypoint.checkin_time IS '实际打卡时刻，未打卡为空';
COMMENT ON COLUMN waybill_waypoint.status IS 'PENDING 待打卡；CHECKED 已打卡；TIMEOUT 超时未打卡（偏离预警）；CANCELLED 改线终止';

CREATE TABLE IF NOT EXISTS checkin_record (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id   VARCHAR(64)  NOT NULL,
    fingerprint  VARCHAR(128) NOT NULL,
    waybill_id   BIGINT       NOT NULL,
    generation   INT          NOT NULL DEFAULT 1,
    waypoint_seq INT          NOT NULL,
    checkin_time DATETIME     NOT NULL,
    result       VARCHAR(32)  NOT NULL,
    message      VARCHAR(256) NULL,
    created_at   DATETIME     NOT NULL,
    CONSTRAINT uk_checkin_request UNIQUE (request_id),
    CONSTRAINT uk_checkin_waybill_gen_seq UNIQUE (waybill_id, generation, waypoint_seq),
    CONSTRAINT fk_checkin_waybill FOREIGN KEY (waybill_id) REFERENCES waybill (id)
);
COMMENT ON TABLE checkin_record IS '押运打卡记录，按 运单+途经点序号 去重，request_id 幂等';
COMMENT ON COLUMN checkin_record.generation IS '打卡时运单路线代次';
COMMENT ON COLUMN checkin_record.result IS '打卡结果：ACCEPTED 正常；LATE_AFTER_CLOSE 结案后迟到仅记事件';
COMMENT ON COLUMN checkin_record.message IS '结果说明，无补充信息为空';

CREATE TABLE IF NOT EXISTS alert (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    waybill_id   BIGINT      NULL,
    vehicle_code VARCHAR(64) NOT NULL,
    type         VARCHAR(32) NOT NULL,
    message      VARCHAR(256) NOT NULL,
    created_at   DATETIME    NOT NULL
);
COMMENT ON TABLE alert IS '告警事件：DEVIATION 偏离预警；LOST 失联；ORDER_VIOLATION 打卡逆序；LATE_AFTER_CLOSE 结案后迟到打卡';
COMMENT ON COLUMN alert.waybill_id IS '关联运单，车辆级告警可为空';

CREATE TABLE IF NOT EXISTS vehicle_freeze (
    vehicle_code VARCHAR(64) PRIMARY KEY,
    waybill_id   BIGINT      NOT NULL,
    reason       VARCHAR(256) NOT NULL,
    frozen_at    DATETIME    NOT NULL
);
COMMENT ON TABLE vehicle_freeze IS '车辆冻结记录，冻结期间拒绝该车新申请；vehicle_code 主键保证一车一条';

CREATE TABLE IF NOT EXISTS reroute_request (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id   VARCHAR(64)  NOT NULL,
    fingerprint  VARCHAR(128) NOT NULL,
    waybill_id   BIGINT       NOT NULL,
    new_route_id BIGINT       NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   DATETIME     NOT NULL,
    approved_at  DATETIME     NULL,
    CONSTRAINT uk_reroute_request UNIQUE (request_id),
    CONSTRAINT fk_reroute_waybill FOREIGN KEY (waybill_id) REFERENCES waybill (id),
    CONSTRAINT fk_reroute_route FOREIGN KEY (new_route_id) REFERENCES route (id)
);
COMMENT ON TABLE reroute_request IS '紧急改线申请，状态：PENDING 待核准；APPROVED 已核准（与原路线终止同事务生效）；REJECTED 已拒绝';
COMMENT ON COLUMN reroute_request.approved_at IS '核准生效时刻，未核准为空';
