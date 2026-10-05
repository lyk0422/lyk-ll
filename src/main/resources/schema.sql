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
COMMENT ON COLUMN deicing_request.status IS '状态：QUEUED/SCHEDULED/IN_PROGRESS/HOT_RISK/TAKEN_OFF/CANCELLED/HOT_EXPIRED';
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

-- 除冰液配比表：每种液记录单位作业消耗（升/分钟）与期初库存（升）
CREATE TABLE IF NOT EXISTS deicing_fluid (
    fluid_code                    VARCHAR(32) PRIMARY KEY,
    fluid_name                    VARCHAR(64)    NOT NULL,
    consumption_per_minute_liters DECIMAL(10,2)  NOT NULL,
    opening_liters                DECIMAL(14,2)  NOT NULL,
    created_at                    TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
COMMENT ON COLUMN deicing_fluid.consumption_per_minute_liters IS '合成配比：每分钟作业消耗（升），按车位占用时长或实际作业时长计费';
COMMENT ON COLUMN deicing_fluid.opening_liters IS '期初库存（升），与 source=OPENING 批次合计一致';

-- 补料入库批次：先到期先出（FEFO）；效期耗尽的批次不参与预占与扣减
CREATE TABLE IF NOT EXISTS fluid_batch (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no         VARCHAR(64)   NOT NULL,
    fluid_code       VARCHAR(32)   NOT NULL,
    source           VARCHAR(16)   NOT NULL,
    quantity_liters  DECIMAL(14,2) NOT NULL,
    remaining_liters DECIMAL(14,2) NOT NULL,
    expiry_time      TIMESTAMP(3)  NOT NULL,
    created_at       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_batch_no UNIQUE (batch_no),
    CONSTRAINT ck_batch_source CHECK (source IN ('OPENING', 'INBOUND'))
);
COMMENT ON COLUMN fluid_batch.source IS '批次来源：OPENING 期初 / INBOUND 补料入库';
COMMENT ON COLUMN fluid_batch.expiry_time IS '效期截止（UTC），到期及之后批次视为耗尽，拒绝预占';
COMMENT ON COLUMN fluid_batch.remaining_liters IS '剩余量（升）；结算超扣时可为负，表示断供透支';

-- 两步法预占：受理同事务写入；取消释放、作业完成转消耗
CREATE TABLE IF NOT EXISTS fluid_reservation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64)    NOT NULL,
    fluid_code      VARCHAR(32)    NOT NULL,
    quantity_liters DECIMAL(14,2)  NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    created_at      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_reservation_request UNIQUE (request_id)
);
COMMENT ON COLUMN fluid_reservation.status IS '状态：ACTIVE 预占中 / SETTLED 已转消耗 / RELEASED 已释放';

-- 消耗台账：一步法按实际作业时长结算，两步法按预占量转结
CREATE TABLE IF NOT EXISTS fluid_consumption (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64)   NOT NULL,
    fluid_code      VARCHAR(32)   NOT NULL,
    quantity_liters DECIMAL(14,2) NOT NULL,
    job_minutes     INT           NOT NULL,
    created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
COMMENT ON COLUMN fluid_consumption.job_minutes IS '计费作业时长（分钟）：一步法为实际时长，两步法为车位占用时长';

-- 跨班组借用单：requestId 幂等；同一液罐同时刻只允许一张未归还借用单
CREATE TABLE IF NOT EXISTS fluid_borrow (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64)   NOT NULL,
    fluid_code      VARCHAR(32)   NOT NULL,
    tank_code       VARCHAR(32)   NOT NULL,
    quantity_liters DECIMAL(14,2) NOT NULL,
    from_team       VARCHAR(32)   NOT NULL,
    to_team         VARCHAR(32)   NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    fingerprint     CHAR(64)      NOT NULL,
    created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    returned_at     TIMESTAMP(3),
    CONSTRAINT uk_borrow_request UNIQUE (request_id)
);
COMMENT ON COLUMN fluid_borrow.tank_code IS '出借液罐编号；同时刻仅允许一张未归还借用单';
COMMENT ON COLUMN fluid_borrow.status IS '状态：OPEN 未归还 / RETURNED 已归还';
COMMENT ON COLUMN fluid_borrow.fingerprint IS '幂等指纹：requestId 与全部业务字段 SHA-256';

-- 人工确认队列：库存不足的两步法申请转入，不静默降级
CREATE TABLE IF NOT EXISTS manual_review_queue (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id    VARCHAR(64)  NOT NULL,
    flight_number VARCHAR(32)  NOT NULL,
    reason        VARCHAR(32)  NOT NULL,
    detail        VARCHAR(256) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_review_request UNIQUE (request_id)
);
COMMENT ON COLUMN manual_review_queue.reason IS '转人工成因：INSUFFICIENT_FLUID 库存不足';
COMMENT ON COLUMN manual_review_queue.status IS '状态：PENDING 待人工确认';

-- 合成除冰液配比与期初库存
MERGE INTO deicing_fluid (fluid_code, fluid_name, consumption_per_minute_liters, opening_liters)
KEY (fluid_code) VALUES
    ('TYPE-I', '一步法除冰液（合成）', 40.00, 20000.00),
    ('TYPE-II', '两步法防冰液（合成）', 25.00, 10000.00);

-- 期初库存批次（效期远期，视为永不在测试窗口内耗尽）
MERGE INTO fluid_batch (batch_no, fluid_code, source, quantity_liters, remaining_liters, expiry_time)
KEY (batch_no) VALUES
    ('OPENING-TYPE-I', 'TYPE-I', 'OPENING', 20000.00, 20000.00, '2027-12-31 00:00:00'),
    ('OPENING-TYPE-II', 'TYPE-II', 'OPENING', 10000.00, 10000.00, '2027-12-31 00:00:00');
