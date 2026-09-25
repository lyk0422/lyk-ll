-- 通信基站备电调度服务建表脚本（H2，MODE=MySQL），所有时间字段均为 UTC。
CREATE TABLE IF NOT EXISTS station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL COMMENT '基站编码，全局唯一',
    load_kw DECIMAL(12,2) NOT NULL COMMENT '基站平均负载功率，单位千瓦',
    battery_capacity_kwh DECIMAL(12,2) NOT NULL COMMENT '电池组额定容量，单位千瓦时',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '创建时间，UTC',
    CONSTRAINT uk_station_code UNIQUE (code)
);
COMMENT ON TABLE station IS '基站档案表';

CREATE TABLE IF NOT EXISTS generator (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL COMMENT '油机编号，全局唯一',
    status VARCHAR(16) NOT NULL COMMENT '油机状态：IDLE 空闲 / ASSIGNED 已分配',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '创建时间，UTC',
    CONSTRAINT uk_generator_code UNIQUE (code)
);
COMMENT ON TABLE generator IS '油机档案表';

CREATE TABLE IF NOT EXISTS battery_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code VARCHAR(64) NOT NULL COMMENT '基站编码',
    reading_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '抄表时刻，UTC；乱序到达按该时刻归位',
    remaining_percent DECIMAL(5,2) NOT NULL COMMENT '电池剩余容量百分比，0-100',
    request_id VARCHAR(64) NOT NULL COMMENT '上报请求幂等键',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '入库时间，UTC',
    CONSTRAINT uk_reading_station_time UNIQUE (station_code, reading_time)
);
COMMENT ON TABLE battery_reading IS '电池容量读数表，按基站+抄表时刻去重';

CREATE TABLE IF NOT EXISTS outage_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code VARCHAR(64) NOT NULL COMMENT '基站编码',
    outage_start_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '停电起始时刻，UTC',
    status VARCHAR(16) NOT NULL COMMENT '事件状态：ACTIVE 停电中 / RESOLVED 已恢复',
    resolved_at TIMESTAMP WITH TIME ZONE NULL COMMENT '市电恢复时刻，UTC；未恢复为空',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '创建时间，UTC',
    CONSTRAINT uk_outage_station_start UNIQUE (station_code, outage_start_time)
);
COMMENT ON TABLE outage_event IS '基站停电事件表，同一事件期内重复告警合并';

CREATE TABLE IF NOT EXISTS dispatch_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '调度请求幂等键',
    station_code VARCHAR(64) NOT NULL COMMENT '基站编码',
    generator_code VARCHAR(64) NULL COMMENT '当前服务油机编号；发电异常待改派时为空',
    status VARCHAR(24) NOT NULL COMMENT '调度单状态：DISPATCHED 在途 / GENERATING 发电中 / GENERATION_ABNORMAL 发电异常 / RELEASED 已解除 / CLOSED 已结案',
    support_hours DECIMAL(10,4) NOT NULL COMMENT '派单时估算的电池可支撑时长，单位小时',
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '预计到达时限，UTC',
    assigned_at TIMESTAMP WITH TIME ZONE NULL COMMENT '当前油机分配时刻，UTC；未分配为空',
    confirmed_at TIMESTAMP WITH TIME ZONE NULL COMMENT '发电确认时刻，UTC；未确认为空',
    closed_at TIMESTAMP WITH TIME ZONE NULL COMMENT '结案时刻，UTC；未结案为空',
    unfinished_key VARCHAR(64) NULL COMMENT '未完成唯一键=基站编码；结案或解除后置空，保证同站同时刻仅一张未完成调度单',
    busy_generator_key VARCHAR(64) NULL COMMENT '在役油机唯一键=油机编号；释放后置空，保证同油机同时刻只服务一站',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '创建时间，UTC',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '最后更新时间，UTC',
    CONSTRAINT uk_dispatch_request UNIQUE (request_id),
    CONSTRAINT uk_dispatch_unfinished UNIQUE (unfinished_key),
    CONSTRAINT uk_dispatch_busy_generator UNIQUE (busy_generator_key)
);
COMMENT ON TABLE dispatch_order IS '油机调度单表';

CREATE TABLE IF NOT EXISTS order_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '调度单ID',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型，如 CREATED/CONFIRMED/ESCALATED/REASSIGNED/RELEASED/CLOSED/LATE_CONFIRM',
    detail VARCHAR(512) NOT NULL COMMENT '事件说明',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件时间，UTC'
);
COMMENT ON TABLE order_event IS '调度单事件流水表，迟到确认等只记事件不改结论';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等键',
    api_name VARCHAR(64) NOT NULL COMMENT '接口名称',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹，覆盖 requestId 与全部业务字段',
    response_body CLOB NOT NULL COMMENT '首次成功响应快照，JSON',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '创建时间，UTC'
);
COMMENT ON TABLE idempotency_record IS '幂等记录表，同键同参返回首次结果，同键异参拒绝';
