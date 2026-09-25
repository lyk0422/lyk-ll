-- 血液成分检疫隔离与放行服务建表脚本（H2 MySQL 兼容模式）
-- 所有时间列均为 UTC 时刻；expiry_date 为效期当日（UTC 日期），
-- 允许放行的最后边界为该日 23:59:59.999... UTC。

CREATE TABLE IF NOT EXISTS blood_unit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    barcode VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    initial_result VARCHAR(8) NULL,
    recheck_result VARCHAR(8) NULL,
    expiry_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_blood_unit_barcode UNIQUE (barcode)
);

COMMENT ON TABLE blood_unit IS '成分血单位：每袋成分血一行，条码唯一';
COMMENT ON COLUMN blood_unit.barcode IS '成分血唯一条码';
COMMENT ON COLUMN blood_unit.status IS '状态：PENDING_TEST 待检 / QUARANTINED 隔离 / RELEASABLE 可放行 / RELEASED 已放行 / DISCARDED 报废（终态） / EXPIRED_DISCARDED 过期报废（终态）';
COMMENT ON COLUMN blood_unit.initial_result IS '初检结果：PASS/FAIL，NULL 表示未回传';
COMMENT ON COLUMN blood_unit.recheck_result IS '复检结果：PASS/FAIL，NULL 表示未回传';
COMMENT ON COLUMN blood_unit.expiry_date IS '效期当日（UTC 日期）；当日 23:59:59 UTC 之后禁止放行';
COMMENT ON COLUMN blood_unit.created_at IS '入库时刻（UTC）';
COMMENT ON COLUMN blood_unit.updated_at IS '最近状态变更时刻（UTC）';
COMMENT ON COLUMN blood_unit.version IS '乐观锁版本号';

CREATE TABLE IF NOT EXISTS result_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    barcode VARCHAR(64) NOT NULL,
    test_type VARCHAR(16) NOT NULL,
    result VARCHAR(8) NOT NULL,
    late BOOLEAN NOT NULL,
    recorded_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE result_event IS '检测结果回传事件流水，含终态后的迟到事件';
COMMENT ON COLUMN result_event.test_type IS '检测项：INITIAL 初检 / RECHECK 复检';
COMMENT ON COLUMN result_event.result IS '回传结果：PASS 合格 / FAIL 不合格';
COMMENT ON COLUMN result_event.late IS '是否迟到事件（单位已处终态时回传，不改变状态）';
COMMENT ON COLUMN result_event.recorded_at IS '事件记录时刻（UTC）';

CREATE TABLE IF NOT EXISTS release_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    barcode VARCHAR(64) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    detail VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_release_order_request UNIQUE (request_id)
);

COMMENT ON TABLE release_order IS '放行单：以 request_id 幂等，指纹覆盖 requestId+条码+时间窗';
COMMENT ON COLUMN release_order.request_id IS '放行请求幂等键';
COMMENT ON COLUMN release_order.fingerprint IS 'SHA-256(requestId|barcode|windowStart|windowEnd)，同键异参返回 409';
COMMENT ON COLUMN release_order.window_start IS '放行时间窗起点（UTC）';
COMMENT ON COLUMN release_order.window_end IS '放行时间窗终点（UTC）';
COMMENT ON COLUMN release_order.outcome IS '放行结果：RELEASED 成功 / REJECTED 拒绝';
COMMENT ON COLUMN release_order.detail IS '拒绝原因或补充说明，成功时为 NULL';
COMMENT ON COLUMN release_order.created_at IS '放行单创建时刻（UTC）';

CREATE TABLE IF NOT EXISTS issue_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    barcode VARCHAR(64) NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_issue_record_request UNIQUE (request_id)
);

COMMENT ON TABLE issue_record IS '发血记录：与放行扣减同事务同生同灭';
COMMENT ON COLUMN issue_record.request_id IS '对应放行单幂等键';
COMMENT ON COLUMN issue_record.barcode IS '发出的成分血条码';
COMMENT ON COLUMN issue_record.issued_at IS '发血时刻（UTC）';
