-- 变电站倒闸操作票与防误闭锁服务建表脚本（H2，MODE=MySQL）
-- 时间列统一为 UTC 时间戳（TIMESTAMP），由应用层 Clock 写入。

CREATE TABLE IF NOT EXISTS switching_ticket (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id   VARCHAR(64)  NOT NULL,
    interval_id  VARCHAR(64)  NOT NULL,
    title        VARCHAR(128) NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    reviewer_id  VARCHAR(64),
    issued_at    TIMESTAMP,
    expires_at   TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);
COMMENT ON TABLE switching_ticket IS '倒闸操作票主表';
COMMENT ON COLUMN switching_ticket.id IS '主键，自增';
COMMENT ON COLUMN switching_ticket.request_id IS '拟票幂等请求号';
COMMENT ON COLUMN switching_ticket.interval_id IS '间隔编号';
COMMENT ON COLUMN switching_ticket.title IS '操作任务名称（合成数据）';
COMMENT ON COLUMN switching_ticket.status IS '状态：DRAFT拟票/REVIEWED已审核/ISSUED已预发/EXECUTING执行中/ARCHIVED已归档(终态)/EXCEPTION异常中止/EXPIRED已作废';
COMMENT ON COLUMN switching_ticket.reviewer_id IS '审核人编号，未审核为空';
COMMENT ON COLUMN switching_ticket.issued_at IS '预发时刻（UTC），未预发为空';
COMMENT ON COLUMN switching_ticket.expires_at IS '作废时刻（UTC）=预发+24小时，恰满即作废，未预发为空';
COMMENT ON COLUMN switching_ticket.created_at IS '创建时刻（UTC）';
COMMENT ON COLUMN switching_ticket.updated_at IS '最近更新时刻（UTC）';

CREATE TABLE IF NOT EXISTS switching_step (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_id      BIGINT      NOT NULL,
    seq            INT         NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    device_id      VARCHAR(64) NOT NULL,
    expected_state VARCHAR(16) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    operator_id    VARCHAR(64),
    guardian_id    VARCHAR(64),
    executed_at    TIMESTAMP,
    verified       BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_step_ticket_seq UNIQUE (ticket_id, seq)
);
COMMENT ON TABLE switching_step IS '操作票步骤表，逐项执行轨迹可复算';
COMMENT ON COLUMN switching_step.ticket_id IS '所属操作票主键';
COMMENT ON COLUMN switching_step.seq IS '步序，从1开始，票内唯一';
COMMENT ON COLUMN switching_step.operation_type IS '操作类型：OPEN_BREAKER/CLOSE_BREAKER/OPEN_DISCONNECTOR/CLOSE_DISCONNECTOR/APPLY_GROUND/REMOVE_GROUND/BUS_OUTAGE';
COMMENT ON COLUMN switching_step.device_id IS '操作设备编号';
COMMENT ON COLUMN switching_step.expected_state IS '该步预期设备状态：OPEN/CLOSED/APPLIED/REMOVED/OUTAGE';
COMMENT ON COLUMN switching_step.status IS '步骤状态：PENDING待执行/DONE已执行';
COMMENT ON COLUMN switching_step.operator_id IS '操作人编号，未执行为空';
COMMENT ON COLUMN switching_step.guardian_id IS '监护人编号，未执行为空，必须与操作人不同';
COMMENT ON COLUMN switching_step.executed_at IS '执行时刻（UTC），未执行为空';
COMMENT ON COLUMN switching_step.verified IS '遥信是否已回报核对一致';

CREATE TABLE IF NOT EXISTS device_state (
    device_id   VARCHAR(64) PRIMARY KEY,
    interval_id VARCHAR(64) NOT NULL,
    device_type VARCHAR(16) NOT NULL,
    state       VARCHAR(16) NOT NULL,
    updated_at  TIMESTAMP   NOT NULL
);
COMMENT ON TABLE device_state IS '间隔设备实时状态表（合成数据）';
COMMENT ON COLUMN device_state.device_id IS '设备编号';
COMMENT ON COLUMN device_state.interval_id IS '所属间隔编号';
COMMENT ON COLUMN device_state.device_type IS '设备类型：BREAKER开关/DISCONNECTOR刀闸/GROUND接地线/BUS母线';
COMMENT ON COLUMN device_state.state IS '设备状态：OPEN分/CLOSED合/APPLIED已挂/REMOVED已拆/ENERGIZED带电/OUTAGE停电';
COMMENT ON COLUMN device_state.updated_at IS '状态更新时刻（UTC）';

CREATE TABLE IF NOT EXISTS interlock_rule (
    rule_code   VARCHAR(64) PRIMARY KEY,
    description VARCHAR(256) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE
);
COMMENT ON TABLE interlock_rule IS '防误闭锁规则库（合成数据）';
COMMENT ON COLUMN interlock_rule.rule_code IS '规则编码';
COMMENT ON COLUMN interlock_rule.description IS '规则中文说明';
COMMENT ON COLUMN interlock_rule.enabled IS '是否启用';

CREATE TABLE IF NOT EXISTS interlock_hit (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_id  BIGINT,
    step_seq   INT,
    rule_code  VARCHAR(64)  NOT NULL,
    detail     VARCHAR(512) NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
COMMENT ON TABLE interlock_hit IS '闭锁命中记录，可复算';
COMMENT ON COLUMN interlock_hit.ticket_id IS '关联操作票主键，可为空（解锁等场景）';
COMMENT ON COLUMN interlock_hit.step_seq IS '关联步序，可为空';
COMMENT ON COLUMN interlock_hit.rule_code IS '命中的闭锁规则编码';
COMMENT ON COLUMN interlock_hit.detail IS '命中详情';
COMMENT ON COLUMN interlock_hit.created_at IS '命中时刻（UTC）';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64) PRIMARY KEY,
    endpoint      VARCHAR(64)  NOT NULL,
    fingerprint   VARCHAR(64)  NOT NULL,
    response_body CLOB         NOT NULL,
    http_status   INT          NOT NULL,
    created_at    TIMESTAMP    NOT NULL
);
COMMENT ON TABLE idempotent_request IS '幂等请求登记表，指纹覆盖requestId与全部业务字段';
COMMENT ON COLUMN idempotent_request.request_id IS '幂等请求号';
COMMENT ON COLUMN idempotent_request.endpoint IS '接口标识';
COMMENT ON COLUMN idempotent_request.fingerprint IS '请求指纹SHA-256，覆盖requestId与全部业务字段';
COMMENT ON COLUMN idempotent_request.response_body IS '首次响应报文快照（JSON）';
COMMENT ON COLUMN idempotent_request.http_status IS '首次响应HTTP状态码';
COMMENT ON COLUMN idempotent_request.created_at IS '登记时刻（UTC）';

CREATE TABLE IF NOT EXISTS interval_execution_lock (
    interval_id VARCHAR(64) PRIMARY KEY,
    ticket_id   BIGINT    NOT NULL,
    acquired_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE interval_execution_lock IS '间隔执行锁，同一间隔同时刻仅允许一张执行中操作票';
COMMENT ON COLUMN interval_execution_lock.interval_id IS '间隔编号';
COMMENT ON COLUMN interval_execution_lock.ticket_id IS '持有锁的操作票主键';
COMMENT ON COLUMN interval_execution_lock.acquired_at IS '加锁时刻（UTC）';

CREATE TABLE IF NOT EXISTS telemetry (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    interval_id VARCHAR(64) NOT NULL,
    device_id   VARCHAR(64) NOT NULL,
    ts          TIMESTAMP   NOT NULL,
    state       VARCHAR(16) NOT NULL,
    late        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP   NOT NULL,
    CONSTRAINT uk_telemetry_interval_ts UNIQUE (interval_id, ts)
);
COMMENT ON TABLE telemetry IS '遥信回报表，按间隔+时标去重';
COMMENT ON COLUMN telemetry.interval_id IS '间隔编号';
COMMENT ON COLUMN telemetry.device_id IS '设备编号';
COMMENT ON COLUMN telemetry.ts IS '遥信时标（UTC）';
COMMENT ON COLUMN telemetry.state IS '回报状态：OPEN/CLOSED/APPLIED/REMOVED/OUTAGE';
COMMENT ON COLUMN telemetry.late IS '是否迟到遥信（票已归档后到达，仅记录不处理）';
COMMENT ON COLUMN telemetry.created_at IS '入库时刻（UTC）';

CREATE TABLE IF NOT EXISTS ground_wire (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    wire_no     VARCHAR(64) NOT NULL,
    status      VARCHAR(24) NOT NULL,
    version     INT         NOT NULL,
    ticket_id   BIGINT,
    interval_id VARCHAR(64),
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL,
    CONSTRAINT uk_ground_wire_no UNIQUE (wire_no)
);
COMMENT ON TABLE ground_wire IS '临时接地线实物表（合成数据），乐观锁版本控制';
COMMENT ON COLUMN ground_wire.id IS '主键，自增';
COMMENT ON COLUMN ground_wire.wire_no IS '接地线唯一编号';
COMMENT ON COLUMN ground_wire.status IS '状态：AVAILABLE可用/BORROWED借出/APPLIED已挂接/REMOVED_PENDING_RETURN已拆除待归还';
COMMENT ON COLUMN ground_wire.version IS '乐观锁版本，从1开始，每次成功变更加1';
COMMENT ON COLUMN ground_wire.ticket_id IS '当前占用操作票主键，可用状态为空';
COMMENT ON COLUMN ground_wire.interval_id IS '借用绑定间隔编号，可用状态为空';
COMMENT ON COLUMN ground_wire.created_at IS '登记时刻（UTC）';
COMMENT ON COLUMN ground_wire.updated_at IS '最近变更时刻（UTC）';

CREATE TABLE IF NOT EXISTS ground_wire_loan (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    wire_id     BIGINT      NOT NULL,
    ticket_id   BIGINT      NOT NULL,
    interval_id VARCHAR(64) NOT NULL,
    borrowed_at TIMESTAMP   NOT NULL,
    returned_at TIMESTAMP,
    keeper_id   VARCHAR(64)
);
COMMENT ON TABLE ground_wire_loan IS '临时接地线借用/归还历史，归还后保留不删除';
COMMENT ON COLUMN ground_wire_loan.id IS '主键，自增';
COMMENT ON COLUMN ground_wire_loan.wire_id IS '接地线主键';
COMMENT ON COLUMN ground_wire_loan.ticket_id IS '借用绑定操作票主键';
COMMENT ON COLUMN ground_wire_loan.interval_id IS '借用绑定间隔编号';
COMMENT ON COLUMN ground_wire_loan.borrowed_at IS '借出时刻（UTC）';
COMMENT ON COLUMN ground_wire_loan.returned_at IS '归还核销时刻（UTC），未归还为空';
COMMENT ON COLUMN ground_wire_loan.keeper_id IS '归还核销仓管编号，未归还为空';

CREATE TABLE IF NOT EXISTS ground_wire_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    wire_id     BIGINT      NOT NULL,
    ticket_id   BIGINT      NOT NULL,
    step_seq    INT         NOT NULL,
    action      VARCHAR(16) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    guardian_id VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP   NOT NULL
);
COMMENT ON TABLE ground_wire_event IS '接地线挂接/拆除双人确认证据，仅插入不可覆盖';
COMMENT ON COLUMN ground_wire_event.id IS '主键，自增';
COMMENT ON COLUMN ground_wire_event.wire_id IS '接地线主键';
COMMENT ON COLUMN ground_wire_event.ticket_id IS '关联操作票主键';
COMMENT ON COLUMN ground_wire_event.step_seq IS '引用本票步序';
COMMENT ON COLUMN ground_wire_event.action IS '动作：APPLY挂接/REMOVE拆除';
COMMENT ON COLUMN ground_wire_event.operator_id IS '操作人编号';
COMMENT ON COLUMN ground_wire_event.guardian_id IS '监护人编号，必须与操作人不同';
COMMENT ON COLUMN ground_wire_event.created_at IS '证据时刻（UTC）';

CREATE TABLE IF NOT EXISTS unlock_record (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id    VARCHAR(64)  NOT NULL,
    rule_code    VARCHAR(64)  NOT NULL,
    authorizer_a VARCHAR(64)  NOT NULL,
    authorizer_b VARCHAR(64)  NOT NULL,
    reason       VARCHAR(256) NOT NULL,
    action       VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL
);
COMMENT ON TABLE unlock_record IS '紧急解锁留痕表，解锁与闭锁恢复同一事务成对写入';
COMMENT ON COLUMN unlock_record.device_id IS '解锁设备编号';
COMMENT ON COLUMN unlock_record.rule_code IS '被解锁的闭锁规则编码';
COMMENT ON COLUMN unlock_record.authorizer_a IS '授权人甲编号';
COMMENT ON COLUMN unlock_record.authorizer_b IS '授权人乙编号，必须与甲不同';
COMMENT ON COLUMN unlock_record.reason IS '解锁原因，非空';
COMMENT ON COLUMN unlock_record.action IS '动作：UNLOCK解锁/RESTORE闭锁恢复';
COMMENT ON COLUMN unlock_record.created_at IS '记录时刻（UTC）';
