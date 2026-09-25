-- 机组飞行执勤期合规模型，仅用于嵌入式 H2（MODE=MySQL）自动初始化。
-- 所有时刻统一以 UTC 存储；数据仅保留在 JVM 生命周期内，全部为合成数据。

CREATE TABLE IF NOT EXISTS duty_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    batch_id VARCHAR(64),
    crew_id VARCHAR(32) NOT NULL,
    flight_no VARCHAR(16) NOT NULL,
    report_time DATETIME NOT NULL,
    scheduled_end_time DATETIME NOT NULL,
    delay_minutes INT NOT NULL DEFAULT 0,
    legal TINYINT NOT NULL,
    reasons VARCHAR(512) NOT NULL DEFAULT '',
    created_time DATETIME NOT NULL,
    CONSTRAINT uk_duty_request UNIQUE (request_id),
    CONSTRAINT uk_duty_slot UNIQUE (crew_id, report_time)
);
COMMENT ON TABLE duty_record IS '机组飞行执勤记录（合成数据）';
COMMENT ON COLUMN duty_record.request_id IS '幂等请求ID，指纹=requestId+机组成员+航班+报到时刻';
COMMENT ON COLUMN duty_record.batch_id IS '所属校验批次ID，NULL表示单独报到未归入批次';
COMMENT ON COLUMN duty_record.report_time IS '报到时刻，UTC';
COMMENT ON COLUMN duty_record.scheduled_end_time IS '计划执勤结束时刻，UTC';
COMMENT ON COLUMN duty_record.delay_minutes IS '封板前已受理的延误延长累计，单位分钟';
COMMENT ON COLUMN duty_record.legal IS '成员级合规判定：1合法，0违规';
COMMENT ON COLUMN duty_record.reasons IS '违规原因码，逗号分隔，空串表示合法';
CREATE INDEX IF NOT EXISTS idx_duty_crew_report ON duty_record (crew_id, report_time);

CREATE TABLE IF NOT EXISTS check_batch (
    batch_id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    legal TINYINT NOT NULL,
    created_time DATETIME NOT NULL,
    finalized_time DATETIME
);
COMMENT ON TABLE check_batch IS '排班校验批次';
COMMENT ON COLUMN check_batch.status IS '批次状态：CHECKED已校验（可再校验）/FINALIZED已封板终态';
COMMENT ON COLUMN check_batch.legal IS '整组判定：1全部成员合法，0存在违规成员';
COMMENT ON COLUMN check_batch.finalized_time IS '封板时刻UTC，NULL表示未封板';

CREATE TABLE IF NOT EXISTS batch_member (
    batch_id VARCHAR(64) NOT NULL,
    crew_id VARCHAR(32) NOT NULL,
    legal TINYINT NOT NULL,
    reasons VARCHAR(512) NOT NULL DEFAULT '',
    CONSTRAINT pk_batch_member PRIMARY KEY (batch_id, crew_id)
);
COMMENT ON TABLE batch_member IS '批次成员聚合判定（成员在批次内可有多段值勤，任一违规则成员违规），成员状态须与duty_record及整组结论一致';
COMMENT ON COLUMN batch_member.reasons IS '成员在批次内各段值勤违规原因码并集，逗号分隔，空串表示合法';

CREATE TABLE IF NOT EXISTS batch_duty (
    batch_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_batch_duty PRIMARY KEY (batch_id, request_id)
);
COMMENT ON TABLE batch_duty IS '批次与值勤记录关联，一个批次包含多段值勤';

CREATE TABLE IF NOT EXISTS delay_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    crew_id VARCHAR(32) NOT NULL,
    duty_utc_date DATE NOT NULL,
    delay_minutes INT NOT NULL,
    applied TINYINT NOT NULL,
    created_time DATETIME NOT NULL
);
COMMENT ON TABLE delay_event IS '延误延长事件';
COMMENT ON COLUMN delay_event.duty_utc_date IS '报到时刻所属UTC日历日，同一日延长按机组成员累计，上限120分钟';
COMMENT ON COLUMN delay_event.applied IS '是否封板前受理：1已应用并重判，0封板后仅登记为迟到事件';
CREATE INDEX IF NOT EXISTS idx_delay_crew_day ON delay_event (crew_id, duty_utc_date);
