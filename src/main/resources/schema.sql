-- 电梯困人救援与维保超期联动服务建表脚本（H2，MODE=MySQL）
-- 时间列一律按 UTC 存储；日期列为 UTC 日界。

CREATE TABLE IF NOT EXISTS elevator (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128),
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    key_supervision BOOLEAN NOT NULL DEFAULT FALSE,
    commissioned_day DATE NOT NULL,
    last_maintenance_day DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_elevator_code UNIQUE (code)
);
COMMENT ON TABLE elevator IS '电梯台账';
COMMENT ON COLUMN elevator.code IS '电梯编号，全局唯一';
COMMENT ON COLUMN elevator.name IS '电梯名称';
COMMENT ON COLUMN elevator.status IS '运行状态：RUNNING 运行 / STOPPED 停梯';
COMMENT ON COLUMN elevator.key_supervision IS '是否列入重点监察（维保逾期触发）';
COMMENT ON COLUMN elevator.commissioned_day IS '投运日期（UTC 日），维保 15 日周期起算点';
COMMENT ON COLUMN elevator.last_maintenance_day IS '最近一次有效维保日（UTC 日），NULL 表示尚未维保';
COMMENT ON COLUMN elevator.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS staff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    cert_no VARCHAR(64) NOT NULL,
    certified BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_staff_cert UNIQUE (cert_no)
);
COMMENT ON TABLE staff IS '维保/救援人员';
COMMENT ON COLUMN staff.name IS '姓名（合成数据）';
COMMENT ON COLUMN staff.cert_no IS '持证编号，全局唯一';
COMMENT ON COLUMN staff.certified IS '是否持有效证书，施救须两人均持证';
COMMENT ON COLUMN staff.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS rescue_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    elevator_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'REPORTED',
    reported_at TIMESTAMP NOT NULL,
    reported_day DATE NOT NULL,
    dispatched_at TIMESTAMP,
    arrived_at TIMESTAMP,
    rescued_at TIMESTAMP,
    restored_at TIMESTAMP,
    closed_at TIMESTAMP,
    timeout_escalated BOOLEAN NOT NULL DEFAULT FALSE,
    escalated_at TIMESTAMP,
    active_elevator_key BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rescue_request UNIQUE (request_id),
    CONSTRAINT uk_rescue_active UNIQUE (active_elevator_key),
    CONSTRAINT fk_rescue_elevator FOREIGN KEY (elevator_id) REFERENCES elevator (id)
);
COMMENT ON TABLE rescue_order IS '救援单：接报→派单→到场→施救→复检恢复→回访结案';
COMMENT ON COLUMN rescue_order.request_id IS '接报幂等键';
COMMENT ON COLUMN rescue_order.fingerprint IS '幂等指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN rescue_order.status IS '状态：REPORTED/DISPATCHED/ARRIVED/RESCUED/RESTORED/CLOSED';
COMMENT ON COLUMN rescue_order.reported_at IS '接报时间（UTC）';
COMMENT ON COLUMN rescue_order.reported_day IS '接报日（UTC 日界），90 日困人统计窗口基准';
COMMENT ON COLUMN rescue_order.dispatched_at IS '派单时间（UTC）';
COMMENT ON COLUMN rescue_order.arrived_at IS '到场时间（UTC），接报后满 30 分钟未到场判超时';
COMMENT ON COLUMN rescue_order.rescued_at IS '施救完成时间（UTC）';
COMMENT ON COLUMN rescue_order.restored_at IS '复检合格并恢复运行时间（UTC）';
COMMENT ON COLUMN rescue_order.closed_at IS '回访结案时间（UTC），结案为终态';
COMMENT ON COLUMN rescue_order.timeout_escalated IS '是否因 30 分钟到场超时升级通报';
COMMENT ON COLUMN rescue_order.escalated_at IS '升级通报时间（UTC）';
COMMENT ON COLUMN rescue_order.active_elevator_key IS '进行中救援占坑位=电梯ID，结案置 NULL；唯一约束保证同电梯仅一单进行中';

CREATE TABLE IF NOT EXISTS rescue_staff (
    rescue_id BIGINT NOT NULL,
    staff_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rescue_id, staff_id, role),
    CONSTRAINT fk_rs_rescue FOREIGN KEY (rescue_id) REFERENCES rescue_order (id),
    CONSTRAINT fk_rs_staff FOREIGN KEY (staff_id) REFERENCES staff (id)
);
COMMENT ON TABLE rescue_staff IS '救援单人员关联';
COMMENT ON COLUMN rescue_staff.role IS '角色：DISPATCH 派单 / RESCUE 施救（须两名持证）';

CREATE TABLE IF NOT EXISTS rescue_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rescue_id BIGINT NOT NULL,
    event VARCHAR(24) NOT NULL,
    detail VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_event_rescue FOREIGN KEY (rescue_id) REFERENCES rescue_order (id)
);
COMMENT ON TABLE rescue_event IS '救援时间线事件，可复算';
COMMENT ON COLUMN rescue_event.event IS '事件：REPORTED/DISPATCHED/ARRIVED/ESCALATED/RESCUED/RESTORED/RESUMED/CLOSED';
COMMENT ON COLUMN rescue_event.detail IS '事件说明';
COMMENT ON COLUMN rescue_event.created_at IS '事件发生时间（UTC）';

CREATE TABLE IF NOT EXISTS stop_resume_chain (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    elevator_id BIGINT NOT NULL,
    action VARCHAR(8) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    rescue_id BIGINT,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_chain_elevator FOREIGN KEY (elevator_id) REFERENCES elevator (id)
);
COMMENT ON TABLE stop_resume_chain IS '停复梯链，可复算';
COMMENT ON COLUMN stop_resume_chain.action IS '动作：STOP 停梯 / RESUME 复梯';
COMMENT ON COLUMN stop_resume_chain.reason IS '原因：RESCUE 困人停梯 / FORCE_STOP_90D 90日2次强制停梯 / MAINTENANCE_OVERDUE 维保逾期锁定 / RECHECK_PASS 复检合格复梯';
COMMENT ON COLUMN stop_resume_chain.rescue_id IS '关联救援单，维保锁定等非救援原因为 NULL';
COMMENT ON COLUMN stop_resume_chain.created_at IS '发生时间（UTC）';

CREATE TABLE IF NOT EXISTS maintenance_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    elevator_id BIGINT NOT NULL,
    request_id VARCHAR(64),
    performed_day DATE NOT NULL,
    due_day DATE,
    result VARCHAR(16) NOT NULL,
    note VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_maint_elevator FOREIGN KEY (elevator_id) REFERENCES elevator (id)
);
COMMENT ON TABLE maintenance_record IS '维保台账，可复算';
COMMENT ON COLUMN maintenance_record.request_id IS '维保登记幂等键';
COMMENT ON COLUMN maintenance_record.performed_day IS '维保执行日（UTC 日）';
COMMENT ON COLUMN maintenance_record.due_day IS '应维保截止日（UTC 日）= 上次维保/投运日 + 15 日';
COMMENT ON COLUMN maintenance_record.result IS '结果：COMPLIANT 合规 / OVERDUE 逾期（锁定并重点监察） / LATE_EVENT 迟到事件（不变更状态）';
COMMENT ON COLUMN maintenance_record.note IS '备注';

CREATE TABLE IF NOT EXISTS maintenance_obligation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    elevator_id BIGINT NOT NULL,
    base_day DATE NOT NULL,
    due_day DATE NOT NULL,
    due_at TIMESTAMP NOT NULL,
    dispatch_open_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    version INT NOT NULL DEFAULT 1,
    settled_by VARCHAR(16),
    settled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_obligation_cycle UNIQUE (elevator_id, due_day),
    CONSTRAINT fk_obligation_elevator FOREIGN KEY (elevator_id) REFERENCES elevator (id)
);
COMMENT ON TABLE maintenance_obligation IS '维保履约义务：绑定电梯、上次有效维保基准日与固定UTC到期时刻，同电梯同到期周期唯一';
COMMENT ON COLUMN maintenance_obligation.base_day IS '周期基准日（UTC 日）：上次有效维保日，无则投运日';
COMMENT ON COLUMN maintenance_obligation.due_day IS '应维保截止日（UTC 日）= 基准日 + 15 日';
COMMENT ON COLUMN maintenance_obligation.due_at IS '固定UTC到期时刻 = due_day 00:00:00 UTC';
COMMENT ON COLUMN maintenance_obligation.dispatch_open_at IS '可派发起点（UTC）= due_at - 24 小时，恰在起点允许派发';
COMMENT ON COLUMN maintenance_obligation.status IS '状态：OPEN 待履约 / FULFILLED 尝试完成 / SETTLED 手工登记结清';
COMMENT ON COLUMN maintenance_obligation.version IS '乐观锁版本，从 1 开始，每次成功变更加 1';
COMMENT ON COLUMN maintenance_obligation.settled_by IS '结清方式：ATTEMPT 尝试完成 / MANUAL 手工登记；未结清为 NULL';
COMMENT ON COLUMN maintenance_obligation.settled_at IS '结清时间（UTC），未结清为 NULL';
COMMENT ON COLUMN maintenance_obligation.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS maintenance_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    obligation_id BIGINT NOT NULL,
    elevator_id BIGINT NOT NULL,
    staff1_id BIGINT NOT NULL,
    staff2_id BIGINT NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    version INT NOT NULL DEFAULT 1,
    maintenance_record_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    suspended_at TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT fk_attempt_obligation FOREIGN KEY (obligation_id) REFERENCES maintenance_obligation (id),
    CONSTRAINT fk_attempt_elevator FOREIGN KEY (elevator_id) REFERENCES elevator (id),
    CONSTRAINT fk_attempt_staff1 FOREIGN KEY (staff1_id) REFERENCES staff (id),
    CONSTRAINT fk_attempt_staff2 FOREIGN KEY (staff2_id) REFERENCES staff (id)
);
COMMENT ON TABLE maintenance_attempt IS '履约尝试：同一义务可多次续派；两名持证人员占半开作业窗，同窗不可重叠';
COMMENT ON COLUMN maintenance_attempt.obligation_id IS '所属履约义务';
COMMENT ON COLUMN maintenance_attempt.elevator_id IS '冗余电梯ID，救援接报时按梯原子挂起';
COMMENT ON COLUMN maintenance_attempt.staff1_id IS '作业人员1（合成持证人员）';
COMMENT ON COLUMN maintenance_attempt.staff2_id IS '作业人员2（合成持证人员）';
COMMENT ON COLUMN maintenance_attempt.window_start IS '作业窗起点（UTC，含），半开区间 [start, end)';
COMMENT ON COLUMN maintenance_attempt.window_end IS '作业窗终点（UTC，不含），半开区间 [start, end)';
COMMENT ON COLUMN maintenance_attempt.status IS '状态：SCHEDULED 已派 / IN_PROGRESS 作业中 / SUSPENDED 救援挂起 / COMPLETED 完成 / LATE_REPORTED 迟到回报 / CANCELLED 手工结清取消';
COMMENT ON COLUMN maintenance_attempt.version IS '乐观锁版本，从 1 开始，每次成功变更加 1';
COMMENT ON COLUMN maintenance_attempt.maintenance_record_id IS '完成时登记的维保记录ID，未完成为 NULL';
COMMENT ON COLUMN maintenance_attempt.created_at IS '派发时间（UTC）';
COMMENT ON COLUMN maintenance_attempt.started_at IS '开始作业时间（UTC），未开始为 NULL';
COMMENT ON COLUMN maintenance_attempt.suspended_at IS '救援挂起时间（UTC），未挂起为 NULL';
COMMENT ON COLUMN maintenance_attempt.completed_at IS '完成/迟到回报时间（UTC），未终结为 NULL';

CREATE TABLE IF NOT EXISTS maintenance_attempt_item (
    attempt_id BIGINT NOT NULL,
    item VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (attempt_id, item),
    CONSTRAINT fk_item_attempt FOREIGN KEY (attempt_id) REFERENCES maintenance_attempt (id)
);
COMMENT ON TABLE maintenance_attempt_item IS '履约尝试已完成清单项，救援挂起后保留';
COMMENT ON COLUMN maintenance_attempt_item.item IS '清单项编码（合成规则固定集合）';
COMMENT ON COLUMN maintenance_attempt_item.created_at IS '回报时间（UTC）';

CREATE TABLE IF NOT EXISTS idempotency_record (
    scope VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body CLOB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scope, request_id)
);
COMMENT ON TABLE idempotency_record IS '幂等受理记录：同键同参返回首次结果，同键异参 409';
COMMENT ON COLUMN idempotency_record.scope IS '业务边界：REPORT/DISPATCH/CLOSE/MAINTENANCE/MAINT_SCAN/MAINT_DISPATCH/MAINT_START/MAINT_ITEMS/MAINT_COMPLETE';
COMMENT ON COLUMN idempotency_record.fingerprint IS '幂等指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN idempotency_record.response_status IS '首次受理返回的 HTTP 状态';
COMMENT ON COLUMN idempotency_record.response_body IS '首次受理返回的报文';
