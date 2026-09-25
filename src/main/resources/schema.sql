-- 电梯困人救援与维保超期联动服务建表脚本（H2 MySQL 兼容模式）
-- 所有时间戳列为 UTC 瞬时（以 TIMESTAMP 存储 UTC 本地时间），日期列为 UTC 日界。

CREATE TABLE IF NOT EXISTS elevator (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    key_supervision TINYINT NOT NULL DEFAULT 0,
    stop_case_id BIGINT,
    last_maintained_on DATE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_elevator_code UNIQUE (code)
);
COMMENT ON TABLE elevator IS '电梯台账：状态 RUNNING/STOPPED，停梯原因与重点监察标记';
COMMENT ON COLUMN elevator.code IS '电梯编号，全局唯一';
COMMENT ON COLUMN elevator.status IS '运行状态：RUNNING 运行中，STOPPED 已停梯';
COMMENT ON COLUMN elevator.key_supervision IS '重点监察标记：0 否，1 是（维保逾期触发）';
COMMENT ON COLUMN elevator.stop_case_id IS '触发强制停梯的救援单ID；维保逾期锁定等非案件停梯为 NULL';
COMMENT ON COLUMN elevator.last_maintained_on IS '最近一次维保完成日期（UTC 日界），维保周期以此起算 15 日';
COMMENT ON COLUMN elevator.version IS '乐观锁版本号';
COMMENT ON COLUMN elevator.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS staff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    cert_no VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_staff_cert UNIQUE (cert_no)
);
COMMENT ON TABLE staff IS '维保/施救人员台账，均为合成数据';
COMMENT ON COLUMN staff.name IS '人员姓名';
COMMENT ON COLUMN staff.cert_no IS '持证编号，全局唯一；施救与维保须持证人员';
COMMENT ON COLUMN staff.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS rescue_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    report_fingerprint VARCHAR(128) NOT NULL,
    elevator_id BIGINT NOT NULL,
    elevator_code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    active_key VARCHAR(64),
    reported_at TIMESTAMP NOT NULL,
    dispatch_request_id VARCHAR(64),
    dispatch_fingerprint VARCHAR(128),
    dispatched_at TIMESTAMP,
    dispatcher VARCHAR(64),
    arrived_at TIMESTAMP,
    escalated TINYINT NOT NULL DEFAULT 0,
    escalated_at TIMESTAMP,
    rescued_at TIMESTAMP,
    rescuer1_id BIGINT,
    rescuer2_id BIGINT,
    rechecked_at TIMESTAMP,
    recheck_result VARCHAR(16),
    close_request_id VARCHAR(64),
    close_fingerprint VARCHAR(128),
    closed_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_rescue_request UNIQUE (request_id),
    CONSTRAINT uk_rescue_active UNIQUE (active_key),
    CONSTRAINT uk_rescue_dispatch_req UNIQUE (dispatch_request_id),
    CONSTRAINT uk_rescue_close_req UNIQUE (close_request_id)
);
COMMENT ON TABLE rescue_case IS '救援案件：接报→派单→到场→施救→复检恢复→回访结案';
COMMENT ON COLUMN rescue_case.request_id IS '接报幂等键';
COMMENT ON COLUMN rescue_case.report_fingerprint IS '接报指纹：requestId 与全部业务字段的摘要，同键异参判 409';
COMMENT ON COLUMN rescue_case.status IS '状态：RECEIVED/DISPATCHED/ARRIVED/RESCUING/RECHECKED/CLOSED，CLOSED 为终态';
COMMENT ON COLUMN rescue_case.active_key IS '进行中唯一键=电梯编号，结案置 NULL；保证同一电梯同时刻仅一个进行中救援单';
COMMENT ON COLUMN rescue_case.reported_at IS '接报时间（UTC），到场时限以此为起点 30 分钟';
COMMENT ON COLUMN rescue_case.escalated IS '超时升级标记：0 否，1 是；恰满 30 分钟未到场即升级通报';
COMMENT ON COLUMN rescue_case.escalated_at IS '升级通报时间（UTC）';
COMMENT ON COLUMN rescue_case.recheck_result IS '复检结果：PASS/FAIL；PASS 与恢复运行同事务完成';

CREATE TABLE IF NOT EXISTS rescue_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    elevator_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    detail VARCHAR(512),
    occurred_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE rescue_event IS '救援时间线事件，可复算案件流转全过程';
COMMENT ON COLUMN rescue_event.event_type IS '事件类型：REPORTED/DISPATCHED/ARRIVED/ESCALATED/RESCUED/RECHECKED/CLOSED/FORCE_STOP/LATE_MAINTENANCE';
COMMENT ON COLUMN rescue_event.occurred_at IS '事件发生时间（UTC）';

CREATE TABLE IF NOT EXISTS maintenance_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    elevator_id BIGINT NOT NULL,
    staff1_id BIGINT NOT NULL,
    staff2_id BIGINT,
    case_id BIGINT,
    period_start DATE NOT NULL,
    due_date DATE NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    result VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_maint_request UNIQUE (request_id)
);
COMMENT ON TABLE maintenance_record IS '维保台账：周期 15 日，恰第 15 日完成合规，逾期锁定电梯并列重点监察';
COMMENT ON COLUMN maintenance_record.request_id IS '维保登记幂等键';
COMMENT ON COLUMN maintenance_record.fingerprint IS '登记指纹：requestId 与全部业务字段的摘要';
COMMENT ON COLUMN maintenance_record.case_id IS '关联救援单；结案后迟到的维保记录只记迟到事件不改案件状态';
COMMENT ON COLUMN maintenance_record.period_start IS '本周期起始日期（UTC 日界）';
COMMENT ON COLUMN maintenance_record.due_date IS '应完成日期（UTC 日界）= 周期起始 + 15 日，当日完成合规';
COMMENT ON COLUMN maintenance_record.result IS '结果：COMPLIANT 合规 / OVERDUE 逾期 / LATE_EVENT 结案后迟到仅记事件';

CREATE TABLE IF NOT EXISTS elevator_stop_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    elevator_id BIGINT NOT NULL,
    action VARCHAR(8) NOT NULL,
    reason VARCHAR(255),
    case_id BIGINT,
    occurred_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE elevator_stop_log IS '停复梯链：每次停梯/复梯一条记录，可复算电梯状态流转';
COMMENT ON COLUMN elevator_stop_log.action IS '动作：STOP 停梯 / RESUME 复梯';
COMMENT ON COLUMN elevator_stop_log.reason IS '原因：TRAPPED_TWICE_90D 90日2次困人 / MAINTENANCE_OVERDUE 维保逾期 / RECHECK_PASS 复检合格复梯';
COMMENT ON COLUMN elevator_stop_log.occurred_at IS '发生时间（UTC）';
