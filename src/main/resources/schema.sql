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
