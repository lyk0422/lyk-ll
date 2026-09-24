-- 血液成分检疫隔离与放行服务建表脚本（H2 MySQL 兼容模式，合成数据，仅 JVM 生命周期内有效）
-- 时间字段统一为 UTC 时刻；NULL 表示该信息尚未回传或不适用。

CREATE TABLE IF NOT EXISTS blood_unit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    barcode VARCHAR(64) NOT NULL,
    component_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    initial_result VARCHAR(16) NULL,
    recheck_result VARCHAR(16) NULL,
    expire_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_blood_unit_barcode UNIQUE (barcode)
);
COMMENT ON TABLE blood_unit IS '成分血单位：每袋一行，条码唯一';
COMMENT ON COLUMN blood_unit.barcode IS '成分血唯一条码';
COMMENT ON COLUMN blood_unit.component_type IS '成分类型（合成数据，如红细胞/血浆/血小板）';
COMMENT ON COLUMN blood_unit.status IS '状态：PENDING待检/ISOLATED隔离/RELEASABLE可放行/RELEASED已放行/DISCARDED报废/EXPIRED_DISCARDED过期报废';
COMMENT ON COLUMN blood_unit.initial_result IS '初检结果：QUALIFIED合格/UNQUALIFIED不合格，NULL表示未回传';
COMMENT ON COLUMN blood_unit.recheck_result IS '复检结果：QUALIFIED合格/UNQUALIFIED不合格，NULL表示未回传';
COMMENT ON COLUMN blood_unit.expire_at IS '可放行截止时刻（UTC，效期当日23:59:59，含边界）';
COMMENT ON COLUMN blood_unit.created_at IS '入库时刻（UTC）';
COMMENT ON COLUMN blood_unit.updated_at IS '最近状态变更时刻（UTC）';

CREATE TABLE IF NOT EXISTS release_request (
    request_id VARCHAR(64) PRIMARY KEY,
    fingerprint VARCHAR(128) NOT NULL,
    barcode VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    unit_status VARCHAR(32) NOT NULL,
    message VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE release_request IS '放行请求幂等台账：request_id 唯一，记录首次处理结果用于同键重放';
COMMENT ON COLUMN release_request.request_id IS '放行请求幂等键（调用方提供）';
COMMENT ON COLUMN release_request.fingerprint IS '幂等指纹：requestId+条码+放行时间窗的SHA-256，同键异参判409';
COMMENT ON COLUMN release_request.barcode IS '目标成分血条码';
COMMENT ON COLUMN release_request.outcome IS '处理结果：RELEASED放行成功/REJECTED拒绝';
COMMENT ON COLUMN release_request.unit_status IS '响应时单位真实状态';
COMMENT ON COLUMN release_request.message IS '结果说明（拒绝原因等），NULL表示无';
COMMENT ON COLUMN release_request.created_at IS '首次处理时刻（UTC）';

CREATE TABLE IF NOT EXISTS issue_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    barcode VARCHAR(64) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_issue_record_request UNIQUE (request_id),
    CONSTRAINT uk_issue_record_barcode UNIQUE (barcode)
);
COMMENT ON TABLE issue_record IS '发血记录：与库存扣减同事务写入，同生同灭；每袋最多发血一次';
COMMENT ON COLUMN issue_record.request_id IS '来源放行请求幂等键';
COMMENT ON COLUMN issue_record.barcode IS '发出成分血条码（唯一，防止重复发血）';
COMMENT ON COLUMN issue_record.window_start IS '放行时间窗起点（UTC）';
COMMENT ON COLUMN issue_record.window_end IS '放行时间窗终点（UTC）';
COMMENT ON COLUMN issue_record.issued_at IS '实际放行时刻（UTC）';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    barcode VARCHAR(64) NOT NULL,
    test_type VARCHAR(16) NOT NULL,
    result VARCHAR(16) NOT NULL,
    unit_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE late_event IS '迟到事件：终态后回传的检测结果只记录、不改变状态';
COMMENT ON COLUMN late_event.barcode IS '成分血条码';
COMMENT ON COLUMN late_event.test_type IS '检测项：INITIAL初检/RECHECK复检';
COMMENT ON COLUMN late_event.result IS '回传结果：QUALIFIED合格/UNQUALIFIED不合格';
COMMENT ON COLUMN late_event.unit_status IS '回传到达时单位所处终态';
COMMENT ON COLUMN late_event.created_at IS '事件记录时刻（UTC）';
