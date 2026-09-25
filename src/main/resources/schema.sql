-- 边坡雷达监测预警服务建表脚本（仅用于 H2 内存库 MODE=MySQL；IF NOT EXISTS 保证可重复初始化）
-- 所有时刻均为 Asia/Shanghai 业务时刻，由调用方显式传入；位移单位毫米(mm)。

CREATE TABLE IF NOT EXISTS monitor_zone (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code           VARCHAR(64)  NOT NULL,
    zone_name           VARCHAR(128) NOT NULL,
    warn_hourly_rate    DECIMAL(14,4) NOT NULL,
    alarm_daily_rate    DECIMAL(14,4) NOT NULL,
    safety_hourly_rate  DECIMAL(14,4) NOT NULL,
    production_status   VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    operation_locked    TINYINT      NOT NULL DEFAULT 0,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_zone_code UNIQUE (zone_code)
);
COMMENT ON COLUMN monitor_zone.zone_code IS '测区编码，业务唯一';
COMMENT ON COLUMN monitor_zone.warn_hourly_rate IS '黄色预警小时速率阈值，毫米/小时，严格大于才触发黄色预警，恰等判正常';
COMMENT ON COLUMN monitor_zone.alarm_daily_rate IS '红色报警日速率阈值，毫米/日，严格大于才触发红色预警，恰等判正常';
COMMENT ON COLUMN monitor_zone.safety_hourly_rate IS '复工安全线小时速率，毫米/小时，须严格低于该值';
COMMENT ON COLUMN monitor_zone.production_status IS '生产终态：ACTIVE生产中/STOPPED已停产/RESUMED已复工，同一时刻只能处于一种';
COMMENT ON COLUMN monitor_zone.operation_locked IS '作业封锁：0未封锁，1已封锁；与撤人确认在同一事务内置1，复工时清零';

CREATE TABLE IF NOT EXISTS radar_reading (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code        VARCHAR(64)  NOT NULL,
    reading_time     DATETIME(3)  NOT NULL,
    displacement_mm  DECIMAL(16,4) NOT NULL,
    late_flag        TINYINT      NOT NULL DEFAULT 0,
    received_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_reading_zone_time UNIQUE (zone_code, reading_time)
);
COMMENT ON COLUMN radar_reading.reading_time IS '雷达读数时刻；测区+时刻唯一约束实现去重，乱序数据按此时刻归位复算';
COMMENT ON COLUMN radar_reading.displacement_mm IS '该时刻累计位移，毫米';
COMMENT ON COLUMN radar_reading.late_flag IS '迟到标记：0正常，1归档后迟到数据（只记迟到事件，不影响已归档处置链）';

CREATE TABLE IF NOT EXISTS zone_person (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code    VARCHAR(64) NOT NULL,
    person_id    VARCHAR(64) NOT NULL,
    on_site      TINYINT     NOT NULL DEFAULT 1,
    located_at   DATETIME(3) NOT NULL,
    CONSTRAINT uk_zone_person UNIQUE (zone_code, person_id)
);
COMMENT ON COLUMN zone_person.on_site IS '人员定位：1预警时仍在测区范围内(须撤离)，0已在范围外';

CREATE TABLE IF NOT EXISTS disposal_chain (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    chain_no             VARCHAR(48) NOT NULL,
    zone_code            VARCHAR(64) NOT NULL,
    request_id           VARCHAR(64) NOT NULL,
    alarm_level          VARCHAR(8)  NOT NULL,
    trigger_time         DATETIME(3) NOT NULL,
    status               VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    active_key           VARCHAR(64),
    evac_status          VARCHAR(12),
    evac_confirm_time    DATETIME(3),
    resume_status        VARCHAR(12),
    resume_apply_time    DATETIME(3),
    resume_decide_time   DATETIME(3),
    archive_time         DATETIME(3),
    created_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_chain_no UNIQUE (chain_no),
    CONSTRAINT uk_chain_request UNIQUE (request_id),
    CONSTRAINT uk_chain_active_key UNIQUE (active_key)
);
COMMENT ON COLUMN disposal_chain.alarm_level IS '预警级别：YELLOW黄色/RED红色';
COMMENT ON COLUMN disposal_chain.trigger_time IS '预警触发时刻（红色撤人30分钟、复工24小时均以此为基准之一）';
COMMENT ON COLUMN disposal_chain.status IS '处置链状态：ACTIVE执行中/ARCHIVED已归档（终态）';
COMMENT ON COLUMN disposal_chain.active_key IS '执行中取测区编码，归档置NULL；唯一索引保证同一测区同时刻仅一条执行中处置链、并发预警唯一受理';
COMMENT ON COLUMN disposal_chain.evac_status IS '撤人状态：NULL黄色链无撤人/PENDING待全员到位/CONFIRMED已全员确认/TIMEOUT超时';
COMMENT ON COLUMN disposal_chain.resume_status IS '复工状态：NULL未申请/APPLIED已申请/APPROVED已批准/REJECTED已驳回';

CREATE TABLE IF NOT EXISTS evacuation_person (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    chain_id      BIGINT      NOT NULL,
    zone_code     VARCHAR(64) NOT NULL,
    person_id     VARCHAR(64) NOT NULL,
    arrived_at    DATETIME(3),
    CONSTRAINT uk_evac_person UNIQUE (chain_id, person_id)
);
COMMENT ON COLUMN evacuation_person.arrived_at IS '人员到达集合点时刻，NULL表示尚未到位；撤人确认记录据此可复算';

CREATE TABLE IF NOT EXISTS resume_assessment (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    chain_id            BIGINT      NOT NULL,
    apply_request_id    VARCHAR(64) NOT NULL,
    apply_time          DATETIME(3) NOT NULL,
    safe_since          DATETIME(3) NOT NULL,
    status              VARCHAR(12) NOT NULL,
    site_confirmed      TINYINT,
    monitor_confirmed   TINYINT,
    decide_request_id   VARCHAR(64),
    decide_time         DATETIME(3),
    decided_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    remark              VARCHAR(255),
    CONSTRAINT uk_resume_chain UNIQUE (chain_id),
    CONSTRAINT uk_resume_apply_request UNIQUE (apply_request_id)
);
COMMENT ON COLUMN resume_assessment.safe_since IS '连续安全窗口起点=触发后最后一个不低于安全线的速率时刻，无则取触发时刻';
COMMENT ON COLUMN resume_assessment.status IS '复工评估：APPLIED待双确认/APPROVED现场核查与监测复核均通过并复工/REJECTED任一未通过';
COMMENT ON COLUMN resume_assessment.site_confirmed IS '现场核查确认：1通过，0未通过';
COMMENT ON COLUMN resume_assessment.monitor_confirmed IS '监测复核确认：1通过，0未通过';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id     VARCHAR(64)  NOT NULL,
    request_type   VARCHAR(24)  NOT NULL,
    zone_code      VARCHAR(64),
    fingerprint    CHAR(64)     NOT NULL,
    http_status    INT          NOT NULL,
    response_json  CLOB,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);
COMMENT ON COLUMN idempotent_request.fingerprint IS 'SHA-256指纹，覆盖requestId与全部业务字段的规范化JSON';
COMMENT ON COLUMN idempotent_request.response_json IS '首次响应JSON，同键同参原样重放';

CREATE TABLE IF NOT EXISTS data_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code     VARCHAR(64) NOT NULL,
    event_type    VARCHAR(24) NOT NULL,
    reading_time  DATETIME(3),
    payload       VARCHAR(512),
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
COMMENT ON COLUMN data_event.event_type IS '事件类型：LATE_READING归档后迟到位移数据';
