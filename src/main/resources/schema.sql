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
COMMENT ON COLUMN shipment_event.event_type IS '事件：ROUTE_REJECTED/CHECKIN_REVERSED/DEVIATION_WARNING/LOST_CONTACT/LATE_CHECKIN_AFTER_CLOSE/REROUTE_SWITCH/RELAY_POINT_SET/RELAY_HANDOVER/RELAY_CHECKIN_REVERSED/CURFEW_FROZEN/RESERVATION_CREATED/RESERVATION_RELEASED/QUALIFICATION_REVOKED_RISK';

-- 夜间禁行时段规则：计划运输窗口跨越禁行起点的运单须设置安全接力点并在起点前完成换班
CREATE TABLE IF NOT EXISTS night_curfew (
    id           BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    route_code   VARCHAR(32) NOT NULL,
    curfew_start TIMESTAMP   NOT NULL,
    curfew_end   TIMESTAMP   NOT NULL
);
COMMENT ON COLUMN night_curfew.curfew_start IS '夜间禁行起始时刻，半开区间[start,end)：恰满起点即判禁行，换班须严格在此前完成';
COMMENT ON COLUMN night_curfew.curfew_end IS '夜间禁行结束时刻，半开区间[start,end)：等于结束时刻视为可通行';

-- 接力段：运单在某途经点设置的押运换班段，换班记录含双方编号与时刻
CREATE TABLE IF NOT EXISTS relay_segment (
    id                   BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    shipment_id          BIGINT      NOT NULL,
    approval_id          BIGINT      NOT NULL,
    relay_seq            INT         NOT NULL,
    status               VARCHAR(16) NOT NULL,
    personnel_controlled TINYINT     NOT NULL DEFAULT 0,
    out_escort_id        VARCHAR(32),
    out_time             TIMESTAMP,
    in_escort_id         VARCHAR(32),
    in_time              TIMESTAMP,
    created_at           TIMESTAMP   NOT NULL,
    completed_at         TIMESTAMP,
    CONSTRAINT uk_relay_segment UNIQUE (shipment_id, approval_id, relay_seq)
);
COMMENT ON COLUMN relay_segment.personnel_controlled IS '是否启用人员管控：1 接班须先完成资格预约，0 保持旧行为';
COMMENT ON COLUMN relay_segment.relay_seq IS '安全接力点途经点序号；按运单+路线版本+途经点序号去重';
COMMENT ON COLUMN relay_segment.status IS 'IN_PROGRESS进行中/COMPLETED换班完成/FROZEN禁行冻结';
COMMENT ON COLUMN relay_segment.out_escort_id IS '交班押运员编号；换班未完成为NULL';
COMMENT ON COLUMN relay_segment.out_time IS '交班打卡时刻（Asia/Shanghai）；未完成为NULL';
COMMENT ON COLUMN relay_segment.in_escort_id IS '接班押运员编号；换班未完成为NULL';
COMMENT ON COLUMN relay_segment.in_time IS '接班打卡时刻（Asia/Shanghai），须严格晚于交班时刻；未完成为NULL';
COMMENT ON COLUMN relay_segment.completed_at IS '换班完成时刻=接班打卡时刻，须严格早于夜间禁行起点；未完成为NULL';

-- 进行中接力段占用：同一运单同时刻只允许一个进行中接力段（主键冲突即并发拒绝）
CREATE TABLE IF NOT EXISTS shipment_active_relay (
    shipment_id BIGINT NOT NULL PRIMARY KEY,
    segment_id  BIGINT NOT NULL
);
COMMENT ON TABLE shipment_active_relay IS '进行中接力段占用：创建接力段时插入，换班完成或禁行冻结时删除';

-- 接力双人打卡：同一接力段每种角色仅一条，单人打卡不生效
CREATE TABLE IF NOT EXISTS relay_checkin (
    id          BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    segment_id  BIGINT      NOT NULL,
    shipment_id BIGINT      NOT NULL,
    escort_id   VARCHAR(32) NOT NULL,
    role        VARCHAR(8)  NOT NULL,
    check_time  TIMESTAMP   NOT NULL,
    request_id  VARCHAR(64),
    created_at  TIMESTAMP   NOT NULL,
    CONSTRAINT uk_relay_checkin UNIQUE (segment_id, role)
);
COMMENT ON COLUMN relay_checkin.escort_id IS '押运员编号（合成数据）';
COMMENT ON COLUMN relay_checkin.role IS 'OUT交班/IN接班；交接班双方编号须不同，否则单人打卡不生效';
COMMENT ON COLUMN relay_checkin.check_time IS '打卡时刻（Asia/Shanghai）；接班时刻须严格晚于交班时刻，否则逆序拒绝并告警';

-- 禁行冻结处置登记：与运单冻结同一事务写入
CREATE TABLE IF NOT EXISTS disposal_record (
    id           BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    shipment_id  BIGINT       NOT NULL,
    segment_id   BIGINT,
    reason       VARCHAR(512) NOT NULL,
    curfew_start TIMESTAMP    NOT NULL,
    created_at   TIMESTAMP    NOT NULL
);
COMMENT ON TABLE disposal_record IS '未按时完成换班的禁行冻结处置登记，与冻结同一事务完成';
COMMENT ON COLUMN disposal_record.segment_id IS '被冻结的进行中接力段ID；未设置接力点为NULL';
COMMENT ON COLUMN disposal_record.curfew_start IS '命中的夜间禁行起点（恰满起点即判定）';

-- 接班员合成资格：人员编号 + 可押运货类 + UTC 有效区间 + 撤销状态，版本从 1 起
CREATE TABLE IF NOT EXISTS escort_qualification (
    id             BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    escort_id      VARCHAR(32) NOT NULL,
    goods_category VARCHAR(32) NOT NULL,
    valid_from_utc TIMESTAMP   NOT NULL,
    valid_to_utc   TIMESTAMP   NOT NULL,
    revoked        TINYINT     NOT NULL DEFAULT 0,
    version_no     INT         NOT NULL,
    request_id     VARCHAR(64),
    created_at     TIMESTAMP   NOT NULL,
    updated_at     TIMESTAMP   NOT NULL
);
COMMENT ON TABLE escort_qualification IS '接班员合成资格（不对接真实资格机构）；撤销与IN打卡经行锁形成一致次序';
COMMENT ON COLUMN escort_qualification.valid_from_utc IS '资格有效区间起点（UTC），闭区间';
COMMENT ON COLUMN escort_qualification.valid_to_utc IS '资格有效区间终点（UTC），闭区间；须完整覆盖预约班段[start,end)';
COMMENT ON COLUMN escort_qualification.revoked IS '是否已撤销：1 撤销，撤销后拒绝新预约且受控段IN重查拒绝';
COMMENT ON COLUMN escort_qualification.version_no IS '乐观锁版本：从1起，修改用expectedVersion，成功加1，过期409';

-- 人员资格预约：运单+接力段+接班员的班段占用，校验与占用同一事务提交
CREATE TABLE IF NOT EXISTS escort_reservation (
    id                    BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    shipment_id           BIGINT      NOT NULL,
    segment_id            BIGINT      NOT NULL,
    escort_id             VARCHAR(32) NOT NULL,
    qualification_id      BIGINT      NOT NULL,
    qualification_version INT         NOT NULL,
    shift_start_utc       TIMESTAMP   NOT NULL,
    shift_end_utc         TIMESTAMP   NOT NULL,
    status                VARCHAR(16) NOT NULL,
    version_no            INT         NOT NULL,
    request_id            VARCHAR(64),
    created_at            TIMESTAMP   NOT NULL,
    updated_at            TIMESTAMP   NOT NULL
);
COMMENT ON TABLE escort_reservation IS '人员资格预约：同一接班员的ACTIVE班段[start,end)不得内部重叠（端点相接允许）';
COMMENT ON COLUMN escort_reservation.qualification_id IS '预约校验依据的资格记录ID（证据）';
COMMENT ON COLUMN escort_reservation.qualification_version IS '预约校验时的资格版本号（证据）';
COMMENT ON COLUMN escort_reservation.shift_start_utc IS '班段起点（UTC），半开区间[start,end)，实际接班时刻恰等于终点拒绝';
COMMENT ON COLUMN escort_reservation.shift_end_utc IS '班段终点（UTC），半开区间[start,end)，起止有序且被资格区间覆盖';
COMMENT ON COLUMN escort_reservation.status IS 'ACTIVE占用中/RELEASED已释放（撤销或运单终结）/CONSUMED换班完成核销';
COMMENT ON COLUMN escort_reservation.version_no IS '乐观锁版本：从1起，修改用expectedVersion，成功加1，过期409';

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
