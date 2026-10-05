-- 机场除冰排队服务建表脚本（H2 MODE=MySQL 兼容；合成数据，仅存于 JVM 内存）

CREATE TABLE IF NOT EXISTS deicing_bay (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    bay_code    VARCHAR(32)  NOT NULL,
    bay_name    VARCHAR(64)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_bay_code UNIQUE (bay_code)
);
COMMENT ON COLUMN deicing_bay.bay_code IS '除冰车位编号，唯一';

-- 航班时刻表：给出两步法防冰液保持时间 HOT（分钟）
CREATE TABLE IF NOT EXISTS flight_timetable (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    flight_number      VARCHAR(32)  NOT NULL,
    target_takeoff_time TIMESTAMP(3),
    hot_minutes        INT          NOT NULL,
    updated_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_timetable_flight UNIQUE (flight_number)
);
COMMENT ON COLUMN flight_timetable.hot_minutes IS '两步法防冰液保持时间（分钟），一步法不使用';

CREATE TABLE IF NOT EXISTS deicing_request (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id            VARCHAR(64)  NOT NULL,
    flight_number         VARCHAR(32)  NOT NULL,
    stand_code            VARCHAR(32)  NOT NULL,
    deicing_type          VARCHAR(16)  NOT NULL,
    required_bays         INT          NOT NULL,
    duration_minutes      INT          NOT NULL,
    target_takeoff_time   TIMESTAMP(3) NOT NULL,
    deadline              TIMESTAMP(3) NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    scheduled_start       TIMESTAMP(3),
    scheduled_end         TIMESTAMP(3),
    actual_start          TIMESTAMP(3),
    actual_end            TIMESTAMP(3),
    hot_minutes           INT,
    hot_risk_reason       VARCHAR(32),
    earliest_reassign_time TIMESTAMP(3),
    actual_takeoff_time   TIMESTAMP(3),
    fingerprint           CHAR(64)     NOT NULL,
    created_at            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_request_id UNIQUE (request_id)
);
COMMENT ON COLUMN deicing_request.id IS '主键，同时作为申请序号：自增，同刻到达按此定序';
COMMENT ON COLUMN deicing_request.deicing_type IS '除冰类型：ONE_STEP 一步法 / TWO_STEP 两步法';
COMMENT ON COLUMN deicing_request.deadline IS '截止时刻（UTC）= 目标起飞时刻减除冰时长';
COMMENT ON COLUMN deicing_request.status IS '状态：QUEUED/SCHEDULED/IN_PROGRESS/HOT_RISK/MANUAL_REVIEW/TAKEN_OFF/CANCELLED/HOT_EXPIRED';
COMMENT ON COLUMN deicing_request.actual_end IS '实际作业完成时刻（UTC），空值表示尚未登记完成；一步法据此结算库存';
COMMENT ON COLUMN deicing_request.hot_minutes IS '两步法 HOT 分钟数，一步法为空';
COMMENT ON COLUMN deicing_request.hot_risk_reason IS 'HOT 风险成因，空值表示无风险';
COMMENT ON COLUMN deicing_request.earliest_reassign_time IS 'HOT 风险时系统给出的最早可重排起飞时刻（UTC），空值表示无风险';
COMMENT ON COLUMN deicing_request.fingerprint IS '幂等指纹：requestId 与全部业务字段 SHA-256';

CREATE INDEX IF NOT EXISTS idx_request_flight ON deicing_request (flight_number);
CREATE INDEX IF NOT EXISTS idx_request_status ON deicing_request (status);

-- 每航班串行化申请的闩锁行，配合 SELECT ... FOR UPDATE 保证“同航班唯一受理”
CREATE TABLE IF NOT EXISTS flight_latch (
    flight_number VARCHAR(32) PRIMARY KEY
);
-- 预置合成航班闩锁，使并发申请直接走行锁串行化
MERGE INTO flight_latch (flight_number) KEY (flight_number)
SELECT flight_number FROM flight_timetable;

-- 出队调度全局闩锁：单行，SELECT ... FOR UPDATE 串行化车位分配
CREATE TABLE IF NOT EXISTS schedule_latch (
    id INT PRIMARY KEY,
    CONSTRAINT ck_schedule_latch_singleton CHECK (id = 1)
);
MERGE INTO schedule_latch (id) KEY (id) VALUES (1);

CREATE TABLE IF NOT EXISTS deicing_allocation (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id     VARCHAR(64)  NOT NULL,
    flight_number  VARCHAR(32)  NOT NULL,
    bay_code       VARCHAR(32)  NOT NULL,
    start_time     TIMESTAMP(3) NOT NULL,
    end_time       TIMESTAMP(3) NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT idx_allocation_request UNIQUE (request_id, bay_code)
);
COMMENT ON COLUMN deicing_allocation.start_time IS '车位占用开始（UTC，含）';
COMMENT ON COLUMN deicing_allocation.end_time IS '车位占用结束（UTC，不含），半开区间端点相接不冲突';

CREATE INDEX IF NOT EXISTS idx_allocation_bay_time ON deicing_allocation (bay_code, start_time, end_time);

-- 合成车位
MERGE INTO deicing_bay (bay_code, bay_name) KEY (bay_code) VALUES
    ('BAY-01', '一号除冰位'),
    ('BAY-02', '二号除冰位'),
    ('BAY-03', '三号除冰位'),
    ('BAY-04', '四号除冰位');

-- 合成航班时刻表（HOT 分钟数）
MERGE INTO flight_timetable (flight_number, hot_minutes) KEY (flight_number) VALUES
    ('CA101', 15),
    ('CA202', 20),
    ('MU510', 10),
    ('CZ303', 30),
    ('HU780', 15);

-- ---------------------------------------------------------------------------
-- 除冰液库存与配比管理
-- ---------------------------------------------------------------------------

-- 除冰液类型
CREATE TABLE IF NOT EXISTS fluid_type (
    fluid_code VARCHAR(32) PRIMARY KEY,
    fluid_name VARCHAR(64) NOT NULL
);
COMMENT ON COLUMN fluid_type.fluid_code IS '除冰液编码，唯一';

-- 合成配比表：每种除冰工艺 × 除冰液的单位作业消耗（升/车位占用分钟）
CREATE TABLE IF NOT EXISTS fluid_mixture (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    deicing_type      VARCHAR(16) NOT NULL,
    fluid_code        VARCHAR(32) NOT NULL,
    liters_per_minute INT         NOT NULL,
    CONSTRAINT uk_mixture_type_fluid UNIQUE (deicing_type, fluid_code)
);
COMMENT ON COLUMN fluid_mixture.liters_per_minute IS '单位作业消耗：单个车位每作业分钟消耗的升数';

-- 补料入库批次：按批次登记效期，消耗按先到期先出（FEFO）
CREATE TABLE IF NOT EXISTS fluid_batch (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no           VARCHAR(64)  NOT NULL,
    fluid_code         VARCHAR(32)  NOT NULL,
    quantity_in        INT          NOT NULL,
    quantity_available INT          NOT NULL,
    expiry_time        TIMESTAMP(3) NOT NULL,
    created_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_batch_no UNIQUE (batch_no),
    CONSTRAINT ck_batch_quantity CHECK (quantity_in > 0 AND quantity_available >= 0)
);
COMMENT ON COLUMN fluid_batch.quantity_in IS '入库量（升）';
COMMENT ON COLUMN fluid_batch.quantity_available IS '剩余量（升）= 入库量减已消耗，借出不扣减（台账单列借出）';
COMMENT ON COLUMN fluid_batch.expiry_time IS '效期截止（UTC），到期及之后批次不得参与预占与结算';

-- 库存预占：两步法受理时按车位占用时长 × 配比预占
CREATE TABLE IF NOT EXISTS inventory_reservation (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64)  NOT NULL,
    fluid_code VARCHAR(32)  NOT NULL,
    quantity   INT          NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_reservation_request_fluid UNIQUE (request_id, fluid_code)
);
COMMENT ON COLUMN inventory_reservation.quantity IS '预占量（升）= 所需车位数 × 作业分钟 × 配比';
COMMENT ON COLUMN inventory_reservation.status IS '状态：ACTIVE 预占中 / CONSUMED 已转消耗 / RELEASED 已释放';

-- 消耗台账：FEFO 批次扣减明细，一步法按实际作业时长结算、两步法由预占转入
CREATE TABLE IF NOT EXISTS inventory_consumption (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64)  NOT NULL,
    fluid_code VARCHAR(32)  NOT NULL,
    batch_id   BIGINT       NOT NULL,
    quantity   INT          NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
COMMENT ON COLUMN inventory_consumption.batch_id IS '扣减来源批次（fluid_batch.id）';
COMMENT ON COLUMN inventory_consumption.quantity IS '本次从该批次扣减的升数';

-- 跨班组借用单：requestId 幂等，指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS borrow_order (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id  VARCHAR(64)  NOT NULL,
    tank_id     VARCHAR(32)  NOT NULL,
    fluid_code  VARCHAR(32)  NOT NULL,
    quantity    INT          NOT NULL,
    from_team   VARCHAR(32)  NOT NULL,
    to_team     VARCHAR(32)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    fingerprint CHAR(64)     NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    returned_at TIMESTAMP(3),
    CONSTRAINT uk_borrow_request UNIQUE (request_id)
);
COMMENT ON COLUMN borrow_order.tank_id IS '液罐编号，同一液罐同时刻仅允许一张未归还借用单';
COMMENT ON COLUMN borrow_order.quantity IS '借出量（升），归还登记前计入台账借出';
COMMENT ON COLUMN borrow_order.status IS '状态：OPEN 未归还 / RETURNED 已归还';
COMMENT ON COLUMN borrow_order.fingerprint IS '幂等指纹：requestId 与全部业务字段 SHA-256';
COMMENT ON COLUMN borrow_order.returned_at IS '归还登记时刻（UTC），空值表示未归还';

-- 液罐未归还借用占位：主键保证同一液罐同时刻仅一张未归还借用单
CREATE TABLE IF NOT EXISTS tank_borrow_open (
    tank_id    VARCHAR(32) PRIMARY KEY,
    request_id VARCHAR(64)  NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

-- 库存操作全局闩锁：单行，SELECT ... FOR UPDATE 串行化预占/结算/借用
CREATE TABLE IF NOT EXISTS inventory_latch (
    id INT PRIMARY KEY,
    CONSTRAINT ck_inventory_latch_singleton CHECK (id = 1)
);
MERGE INTO inventory_latch (id) KEY (id) VALUES (1);

-- 合成除冰液类型
MERGE INTO fluid_type (fluid_code, fluid_name) KEY (fluid_code) VALUES
    ('TYPE_I', 'I 型除冰液'),
    ('TYPE_IV', 'IV 型防冰液');

-- 合成配比表（升/车位占用分钟）
MERGE INTO fluid_mixture (deicing_type, fluid_code, liters_per_minute)
KEY (deicing_type, fluid_code) VALUES
    ('ONE_STEP', 'TYPE_I', 6),
    ('TWO_STEP', 'TYPE_I', 6),
    ('TWO_STEP', 'TYPE_IV', 3);

-- 合成期初入库批次（期初库存为 0，全部库存经入库批次登记）
MERGE INTO fluid_batch (batch_no, fluid_code, quantity_in, quantity_available, expiry_time)
KEY (batch_no) VALUES
    ('BATCH-T1-SEED', 'TYPE_I', 100000, 100000, '2027-06-30 00:00:00'),
    ('BATCH-T4-SEED', 'TYPE_IV', 100000, 100000, '2027-06-30 00:00:00');
