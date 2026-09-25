-- 基站备电调度服务建表脚本（H2 MySQL 兼容模式；时间列均存绝对时刻，按 UTC 解读）

CREATE TABLE IF NOT EXISTS base_station (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code        VARCHAR(64)  NOT NULL COMMENT '基站编号（业务唯一键）',
    station_name        VARCHAR(128) NOT NULL COMMENT '基站名称',
    battery_capacity_wh DECIMAL(12,2) NOT NULL COMMENT '电池组额定容量，单位 Wh',
    discharge_rate_w    DECIMAL(12,2) NOT NULL COMMENT '电池组平均放电功率，单位 W',
    current_remaining_wh DECIMAL(12,2) NULL COMMENT '最近已知剩余电池容量，单位 Wh，NULL 表示尚无抄表；市电恢复回充满后重置为额定容量',
    created_at          TIMESTAMP(6) NOT NULL COMMENT '创建时刻，UTC',
    CONSTRAINT uk_station_code UNIQUE (station_code)
);

CREATE TABLE IF NOT EXISTS outage_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id      BIGINT NOT NULL COMMENT '所属基站 ID',
    outage_start_at TIMESTAMP(6) NOT NULL COMMENT '停电起始时刻，UTC',
    status          VARCHAR(32) NOT NULL COMMENT '事件状态：ACTIVE 停电中 / RESTORED 已恢复',
    restored_at     TIMESTAMP(6) NULL COMMENT '市电恢复时刻，UTC，停电中为 NULL',
    created_at      TIMESTAMP(6) NOT NULL COMMENT '创建时刻，UTC',
    CONSTRAINT uk_station_outage UNIQUE (station_id, outage_start_at),
    CONSTRAINT fk_event_station FOREIGN KEY (station_id) REFERENCES base_station (id)
);

CREATE TABLE IF NOT EXISTS capacity_reading (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id        BIGINT NOT NULL COMMENT '所属基站 ID',
    read_at           TIMESTAMP(6) NOT NULL COMMENT '抄表时刻，UTC；按基站+抄表时刻去重',
    remaining_wh      DECIMAL(12,2) NOT NULL COMMENT '剩余电池容量，单位 Wh',
    estimated_minutes INT NOT NULL COMMENT '按当前放电功率估算的可支撑分钟数',
    created_at        TIMESTAMP(6) NOT NULL COMMENT '入库时刻，UTC（可晚于抄表时刻，支持乱序归位）',
    CONSTRAINT uk_station_reading UNIQUE (station_id, read_at),
    CONSTRAINT fk_reading_station FOREIGN KEY (station_id) REFERENCES base_station (id)
);

-- 油机主数据：status 为油机当前服务状态
CREATE TABLE IF NOT EXISTS generator (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    gen_code    VARCHAR(64)  NOT NULL COMMENT '油机编号（业务唯一键）',
    gen_name    VARCHAR(128) NOT NULL COMMENT '油机名称',
    status      VARCHAR(32)  NOT NULL COMMENT '状态：IDLE 空闲 / DISPATCHED 在途 / GENERATING 发电中',
    current_order_id BIGINT NULL COMMENT '当前在途或发电中的调度单 ID，空闲为 NULL',
    created_at  TIMESTAMP(6) NOT NULL COMMENT '创建时刻，UTC',
    CONSTRAINT uk_gen_code UNIQUE (gen_code)
);

-- 调度单。终态：RELEASED 已解除（市电恢复）/ CLOSED 已结案。
CREATE TABLE IF NOT EXISTS dispatch_order (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id           VARCHAR(64) NOT NULL COMMENT '调度请求幂等键',
    station_id           BIGINT NOT NULL COMMENT '被保障基站 ID',
    event_id             BIGINT NOT NULL COMMENT '关联停电事件 ID',
    gen_id               BIGINT NULL COMMENT '当前服务油机 ID；改派后指向新油机',
    status               VARCHAR(32) NOT NULL COMMENT '状态：PENDING 待派单 / DISPATCHED 油机在途 / GENERATING 发电确认 / GENERATION_ABNORMAL 发电异常 / RELEASED 已解除 / CLOSED 已结案',
    arrival_deadline_utc TIMESTAMP(6) NOT NULL COMMENT '油机预计到达时限，UTC',
    dispatch_deadline    TIMESTAMP(6) NOT NULL COMMENT '派单后油机应到位启动的截止时刻（=派单+30 分钟），UTC，恰满判超时',
    confirmed_at         TIMESTAMP(6) NULL COMMENT '发电确认回传时刻，UTC',
    abnormal_at          TIMESTAMP(6) NULL COMMENT '发电异常升级时刻，UTC',
    released_at          TIMESTAMP(6) NULL COMMENT '解除时刻，UTC',
    closed_at            TIMESTAMP(6) NULL COMMENT '结案时刻，UTC',
    created_at           TIMESTAMP(6) NOT NULL COMMENT '创建时刻，UTC',
    updated_at           TIMESTAMP(6) NOT NULL COMMENT '最近更新时刻，UTC',
    CONSTRAINT uk_order_request UNIQUE (request_id),
    CONSTRAINT fk_order_station FOREIGN KEY (station_id) REFERENCES base_station (id),
    CONSTRAINT fk_order_event FOREIGN KEY (event_id) REFERENCES outage_event (id),
    CONSTRAINT fk_order_gen FOREIGN KEY (gen_id) REFERENCES generator (id)
);

-- 改派历史：原单改派时记录原油机与新油机
CREATE TABLE IF NOT EXISTS dispatch_reassignment (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id         BIGINT NOT NULL COMMENT '调度单 ID',
    old_gen_id       BIGINT NULL COMMENT '改派前油机 ID，首次自动派单失败时可为 NULL',
    new_gen_id       BIGINT NOT NULL COMMENT '改派后油机 ID',
    reassigned_at    TIMESTAMP(6) NOT NULL COMMENT '改派时刻，UTC',
    reason           VARCHAR(64) NOT NULL COMMENT '改派原因：CONFIRM_TIMEOUT 发电确认超时',
    CONSTRAINT fk_reassign_order FOREIGN KEY (order_id) REFERENCES dispatch_order (id)
);

-- 同站同时刻只允许一张未完成调度单：活动调度单占坑，终态删坑，靠主键冲突保证并发唯一
CREATE TABLE IF NOT EXISTS station_active_order (
    station_id BIGINT PRIMARY KEY COMMENT '基站 ID（一个基站至多一条活动调度单）',
    order_id   BIGINT NOT NULL COMMENT '活动调度单 ID',
    CONSTRAINT fk_station_lock_order FOREIGN KEY (order_id) REFERENCES dispatch_order (id)
);

-- 同油机同时刻只服务一站：占坑表，靠主键冲突保证并发唯一分配
CREATE TABLE IF NOT EXISTS generator_active_order (
    gen_id   BIGINT PRIMARY KEY COMMENT '油机 ID（一台油机同时刻至多服务一站）',
    order_id BIGINT NOT NULL COMMENT '占坑的调度单 ID',
    CONSTRAINT fk_gen_lock_order FOREIGN KEY (order_id) REFERENCES dispatch_order (id)
);

-- 调度单生命周期事件（含迟到发电确认）
CREATE TABLE IF NOT EXISTS order_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT NOT NULL COMMENT '调度单 ID',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：DISPATCHED 派单 / CONFIRMED 发电确认 / ABNORMAL 异常升级 / REASSIGNED 改派 / RELEASED 解除 / CLOSED 结案 / LATE_CONFIRM 迟到确认',
    gen_id     BIGINT NULL COMMENT '事件关联油机 ID',
    event_at   TIMESTAMP(6) NOT NULL COMMENT '事件时刻，UTC',
    note       VARCHAR(255) NULL COMMENT '备注，如迟到确认不改变终态',
    created_at TIMESTAMP(6) NOT NULL COMMENT '入库时刻，UTC',
    CONSTRAINT fk_event_order FOREIGN KEY (order_id) REFERENCES dispatch_order (id)
);

-- 幂等记录：指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id      VARCHAR(64) PRIMARY KEY COMMENT '请求幂等键 requestId',
    request_type    VARCHAR(16) NOT NULL COMMENT '请求类型：ALERT 告警 / READING 抄表 / DISPATCH 调度 / CONFIRM 发电确认 / RESTORE 恢复 / CLOSE 结案',
    fingerprint     VARCHAR(64) NOT NULL COMMENT 'requestId+全部业务字段的 SHA-256 指纹',
    http_status     INT NOT NULL COMMENT '首次响应 HTTP 状态码',
    response_body   CLOB NOT NULL COMMENT '首次响应体快照，同键同参原样返回',
    created_at      TIMESTAMP(6) NOT NULL COMMENT '首次请求时刻，UTC'
);
