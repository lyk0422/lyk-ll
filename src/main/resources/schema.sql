-- 血液成分放行服务建表脚本（H2 MySQL 兼容模式；时间均以 UTC 时刻存储）
CREATE TABLE IF NOT EXISTS blood_unit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    barcode VARCHAR(64) NOT NULL,
    component_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    initial_result VARCHAR(16),
    repeat_result VARCHAR(16),
    initial_received_at DATETIME(3),
    repeat_received_at DATETIME(3),
    expires_at DATETIME(3) NOT NULL,
    expired_at DATETIME(3),
    scrap_reason VARCHAR(64),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_blood_unit_barcode UNIQUE (barcode)
);
COMMENT ON TABLE blood_unit IS '成分血库存单位，一袋一条码，状态随检测回传/放行/过期流转';
COMMENT ON COLUMN blood_unit.barcode IS '唯一条码';
COMMENT ON COLUMN blood_unit.component_type IS '成分类型：RED_CELL 红细胞/PLASMA 血浆/PLATELET 血小板';
COMMENT ON COLUMN blood_unit.status IS '状态：PENDING 待检/QUARANTINED 隔离/RELEASABLE 可放行/RELEASED 已放行/SCRAPPED 报废/EXPIRED 过期报废';
COMMENT ON COLUMN blood_unit.initial_result IS '初检结果：PASS 合格/FAIL 不合格，NULL 表示未回传';
COMMENT ON COLUMN blood_unit.repeat_result IS '复检结果：PASS 合格/FAIL 不合格，NULL 表示未回传';
COMMENT ON COLUMN blood_unit.initial_received_at IS '初检结果回传时刻(UTC)，NULL 表示未回传';
COMMENT ON COLUMN blood_unit.repeat_received_at IS '复检结果回传时刻(UTC)，NULL 表示未回传';
COMMENT ON COLUMN blood_unit.expires_at IS '效期边界时刻(UTC)：该时刻(含)之前可放行，其后禁止放行并判过期报废';
COMMENT ON COLUMN blood_unit.expired_at IS '过期判定时刻(UTC)，仅终态 EXPIRED 非空';
COMMENT ON COLUMN blood_unit.scrap_reason IS '报废原因：TEST_FAILED 检测不合格/MANUAL 人工报废/EXPIRED 过期';
COMMENT ON COLUMN blood_unit.version IS '乐观锁版本号，每次状态流转自增';

CREATE TABLE IF NOT EXISTS test_result_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    barcode VARCHAR(64) NOT NULL,
    test_type VARCHAR(16) NOT NULL,
    result VARCHAR(16) NOT NULL,
    accepted BOOLEAN NOT NULL,
    received_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL
);
COMMENT ON TABLE test_result_event IS '检测结果回传事件，终态后的回传记为迟到事件(accepted=FALSE)，不复活单位';
COMMENT ON COLUMN test_result_event.test_type IS '检测类型：INITIAL 初检/REPEAT 复检';
COMMENT ON COLUMN test_result_event.result IS '检测结果：PASS 合格/FAIL 不合格';
COMMENT ON COLUMN test_result_event.accepted IS '是否被采纳并驱动状态流转：FALSE 为终态后的迟到事件';
COMMENT ON COLUMN test_result_event.received_at IS '结果回传时刻(UTC)';
CREATE INDEX IF NOT EXISTS idx_test_event_barcode ON test_result_event (barcode);

CREATE TABLE IF NOT EXISTS release_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    barcode VARCHAR(64) NOT NULL,
    window_start DATETIME(3) NOT NULL,
    window_end DATETIME(3) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reject_reason VARCHAR(64),
    issued_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_release_order_request UNIQUE (request_id)
);
COMMENT ON TABLE release_order IS '放行单，以 request_id 幂等；指纹覆盖 requestId、条码与放行时间窗';
COMMENT ON COLUMN release_order.request_id IS '放行请求幂等键，同键只产生一个首次结果';
COMMENT ON COLUMN release_order.window_start IS '放行时间窗起点(UTC，含)';
COMMENT ON COLUMN release_order.window_end IS '放行时间窗终点(UTC，含)';
COMMENT ON COLUMN release_order.fingerprint IS '幂等指纹 SHA-256(requestId|barcode|windowStart|windowEnd)';
COMMENT ON COLUMN release_order.status IS '放行结论：SUCCESS 放行成功/REJECTED 拒绝放行';
COMMENT ON COLUMN release_order.reject_reason IS '拒绝原因：NOT_RELEASABLE 不可放行/UNIT_RELEASED 已放行/SCRAPPED 已报废/EXPIRED 已过期/OUTSIDE_WINDOW 不在时间窗';
COMMENT ON COLUMN release_order.issued_at IS '实际放行发血时刻(UTC)，仅成功放行非空';

CREATE TABLE IF NOT EXISTS blood_issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    barcode VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    issued_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_blood_issue_barcode UNIQUE (barcode),
    CONSTRAINT uk_blood_issue_request UNIQUE (request_id)
);
COMMENT ON TABLE blood_issue IS '发血记录，与库存放行状态同事务写入，一条码仅允许一条发血记录';
COMMENT ON COLUMN blood_issue.barcode IS '发血成分条码，唯一，保证并发只放行一次';
COMMENT ON COLUMN blood_issue.request_id IS '触发发血的放行单 requestId';
COMMENT ON COLUMN blood_issue.issued_at IS '发血时刻(UTC)';
COMMENT ON COLUMN blood_issue.expires_at IS '发血时该单位效期边界(UTC)';
