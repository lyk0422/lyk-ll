-- 本地机坪除冰调度服务建表脚本（H2 MySQL 兼容模式；亦可在 MySQL 8 执行的通用子集）
-- 所有时刻均以 BIGINT 存储 UTC 时区的 epoch 毫秒；占用区间一律为半开 [start, end)。

-- 除冰车位（合成基础数据）
CREATE TABLE IF NOT EXISTS deicing_bay (
    bay_id      VARCHAR(32)  NOT NULL COMMENT '车位编号',
    bay_name    VARCHAR(64)  NOT NULL COMMENT '车位名称',
    active      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
    PRIMARY KEY (bay_id)
);

-- 除冰车（合成基础数据）：同一时刻只服务一个机位
CREATE TABLE IF NOT EXISTS deicing_truck (
    truck_id    VARCHAR(32)  NOT NULL COMMENT '除冰车编号',
    truck_name  VARCHAR(64)  NOT NULL COMMENT '除冰车名称',
    active      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
    PRIMARY KEY (truck_id)
);

-- 除冰时刻表：按除冰类型给出作业时长与两步法防冰液保持时间（HOT）
CREATE TABLE IF NOT EXISTS deice_timetable (
    deice_type    VARCHAR(16) NOT NULL COMMENT '除冰类型：ONE_STEP 一步法 / TWO_STEP 两步法',
    duration_min  INT         NOT NULL COMMENT '除冰作业时长（分钟）',
    hot_min       INT         NULL     COMMENT '防冰液保持时间 HOT（分钟）；一步法为空，两步法非空',
    PRIMARY KEY (deice_type)
);

-- 除冰排队申请（一条记录对应一次 requestId 幂等申请）
CREATE TABLE IF NOT EXISTS queue_request (
    request_id              VARCHAR(64)  NOT NULL COMMENT '申请幂等键（客户端给定，全局唯一）',
    seq                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申请序号；同一时刻到达按序号定序',
    flight_no               VARCHAR(32)  NOT NULL COMMENT '航班号',
    stand                   VARCHAR(32)  NOT NULL COMMENT '机位号',
    deice_type              VARCHAR(16)  NOT NULL COMMENT '除冰类型：ONE_STEP/TWO_STEP',
    required_bays           INT          NOT NULL COMMENT '所需车位数',
    target_departure_ts     BIGINT       NOT NULL COMMENT '目标起飞时刻（UTC epoch 毫秒）',
    expected_departure_ts   BIGINT       NOT NULL COMMENT '最新预计起飞时刻（UTC epoch 毫秒），延误时更新',
    deadline_ts             BIGINT       NOT NULL COMMENT '截止时刻=起飞时刻减除冰时长（UTC epoch 毫秒）',
    status                  VARCHAR(16)  NOT NULL COMMENT '状态：WAITING 排队中/SCHEDULED 已排定/IN_PROGRESS 作业中/DEICED 除冰完成待起飞/HOT_RISK HOT 风险/DEPARTED 已起飞/CANCELLED 已取消/HOT_EXPIRED HOT 失效',
    actual_start_ts         BIGINT       NULL     COMMENT '作业实际开始时刻（UTC epoch 毫秒），未开始为空',
    actual_finish_ts        BIGINT       NULL     COMMENT '作业实际完成时刻（UTC epoch 毫秒），未完成为空',
    hot_expires_ts          BIGINT       NULL     COMMENT '两步法防冰液失效终点=完成时刻+HOT（恰在该点起飞仍有效）',
    earliest_resched_ts     BIGINT       NULL     COMMENT 'HOT 风险后最早可重排（重新除冰开始）时刻',
    cancel_reason           VARCHAR(255) NULL     COMMENT '取消原因',
    round_no                INT          NOT NULL DEFAULT 1 COMMENT '除冰轮次：首次为1，重新除冰递增',
    created_ts              BIGINT       NOT NULL COMMENT '创建时刻（UTC epoch 毫秒）',
    updated_ts              BIGINT       NOT NULL COMMENT '最近更新时刻（UTC epoch 毫秒）',
    PRIMARY KEY (request_id)
);
CREATE INDEX IF NOT EXISTS idx_queue_status ON queue_request (status);
CREATE INDEX IF NOT EXISTS idx_queue_flight ON queue_request (flight_no);

-- 进行中航班唯一受理登记表：同一航班只允许一条非终态队列记录
CREATE TABLE IF NOT EXISTS flight_active (
    flight_no   VARCHAR(32) NOT NULL COMMENT '进行中航班号（唯一）',
    request_id  VARCHAR(64) NOT NULL COMMENT '对应申请号',
    created_ts  BIGINT      NOT NULL COMMENT '登记时刻（UTC epoch 毫秒）',
    PRIMARY KEY (flight_no)
);

-- 除冰资源占用：每辆车为一个机位占用一个车位的半开区间 [start_ts, end_ts)
CREATE TABLE IF NOT EXISTS deicing_assignment (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '占用记录自增主键',
    request_id    VARCHAR(64)  NOT NULL COMMENT '对应申请号',
    round_no      INT          NOT NULL DEFAULT 1 COMMENT '除冰轮次：首次为1，重新除冰递增',
    bay_id        VARCHAR(32)  NOT NULL COMMENT '占用的车位编号',
    truck_id      VARCHAR(32)  NOT NULL COMMENT '服务该车的除冰车编号',
    start_ts      BIGINT       NOT NULL COMMENT '占用起点（含，UTC epoch 毫秒）',
    end_ts        BIGINT       NOT NULL COMMENT '占用终点（不含，UTC epoch 毫秒）；端点相接不冲突',
    actual_end_ts BIGINT       NULL     COMMENT '作业实际结束时刻；未结束或按计划结束为空',
    state         VARCHAR(16)  NOT NULL COMMENT 'RESERVED 已预留/ACTIVE 作业中/DONE 已完成/RELEASED 已释放',
    CHECK (end_ts > start_ts),
    PRIMARY KEY (id),
    CONSTRAINT fk_assign_request FOREIGN KEY (request_id) REFERENCES queue_request (request_id)
);
CREATE INDEX IF NOT EXISTS idx_assign_bay_time ON deicing_assignment (bay_id, start_ts, end_ts);
CREATE INDEX IF NOT EXISTS idx_assign_truck_time ON deicing_assignment (truck_id, start_ts, end_ts);
CREATE INDEX IF NOT EXISTS idx_assign_request ON deicing_assignment (request_id);

-- 幂等操作台账：申请与各次确认/作业/终态操作均以 requestId+操作 为键，指纹覆盖全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_operation (
    request_id      VARCHAR(64)  NOT NULL COMMENT '申请幂等键',
    operation       VARCHAR(24)  NOT NULL COMMENT '操作类型：APPLY/DISPATCH/START/COMPLETE/DELAY/RESCHEDULE/DEPART/CANCEL',
    fingerprint     VARCHAR(64)  NOT NULL COMMENT '业务字段指纹（SHA-256 十六进制）',
    response_status INT          NOT NULL COMMENT '首次响应 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '首次响应体 JSON',
    created_ts      BIGINT       NOT NULL COMMENT '首次受理时刻（UTC epoch 毫秒）',
    PRIMARY KEY (request_id, operation)
);

-- 合成基础数据
MERGE INTO deicing_bay (bay_id, bay_name, active) KEY (bay_id) VALUES ('B1', '除冰车位1', 1);
MERGE INTO deicing_bay (bay_id, bay_name, active) KEY (bay_id) VALUES ('B2', '除冰车位2', 1);
MERGE INTO deicing_truck (truck_id, truck_name, active) KEY (truck_id) VALUES ('T1', '除冰车1', 1);
MERGE INTO deicing_truck (truck_id, truck_name, active) KEY (truck_id) VALUES ('T2', '除冰车2', 1);
-- 一步法作业 20 分钟、无 HOT；两步法作业 30 分钟、HOT 30 分钟
MERGE INTO deice_timetable (deice_type, duration_min, hot_min) KEY (deice_type) VALUES ('ONE_STEP', 20, NULL);
MERGE INTO deice_timetable (deice_type, duration_min, hot_min) KEY (deice_type) VALUES ('TWO_STEP', 30, 30);
