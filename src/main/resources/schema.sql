-- 变电站倒闸操作票与防误闭锁服务建表脚本（H2 MySQL 兼容模式，时间均为 UTC）
CREATE TABLE IF NOT EXISTS switching_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '操作票主键',
    order_no VARCHAR(64) NOT NULL COMMENT '操作票编号（业务唯一）',
    interval_id VARCHAR(64) NOT NULL COMMENT '所属间隔编号',
    title VARCHAR(255) NOT NULL COMMENT '操作票标题',
    status VARCHAR(16) NOT NULL COMMENT '状态：DRAFT拟票/REVIEWED已审核/EXECUTING执行中/ARCHIVED已归档(终态)/ABORTED异常中止/EXPIRED超24小时作废',
    created_by VARCHAR(64) NOT NULL COMMENT '拟票人',
    reviewed_by VARCHAR(64) DEFAULT NULL COMMENT '审核人，未审核为空',
    issued_at TIMESTAMP DEFAULT NULL COMMENT '预发时间（UTC），24小时有效期起算点',
    executing_interval VARCHAR(64) DEFAULT NULL COMMENT '执行中占用的间隔槽位；唯一约束保证同一间隔同时刻仅一张执行中操作票，非执行态为空',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
    updated_at TIMESTAMP NOT NULL COMMENT '更新时间（UTC）',
    CONSTRAINT uk_switching_order_no UNIQUE (order_no),
    CONSTRAINT uk_switching_order_exec_interval UNIQUE (executing_interval)
);
COMMENT ON TABLE switching_order IS '倒闸操作票主表';

CREATE TABLE IF NOT EXISTS order_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '步骤主键',
    order_id BIGINT NOT NULL COMMENT '所属操作票ID',
    seq INT NOT NULL COMMENT '步序，从1开始，票内唯一',
    device_id VARCHAR(64) NOT NULL COMMENT '操作设备编号',
    action VARCHAR(32) NOT NULL COMMENT '操作类型：OPEN_BREAKER/CLOSE_BREAKER/OPEN_DISCONNECTOR/CLOSE_DISCONNECTOR/ATTACH_GROUND/REMOVE_GROUND/BUS_OUTAGE',
    expected_state VARCHAR(32) NOT NULL COMMENT '操作后设备预期状态：ON/OFF/OPEN/CLOSED/ATTACHED/REMOVED/DEENERGIZED',
    status VARCHAR(16) NOT NULL COMMENT '步骤状态：PENDING待执行/DONE已执行；作废票保留已执行步骤',
    operator_name VARCHAR(64) DEFAULT NULL COMMENT '操作人，双确认之一',
    guardian_name VARCHAR(64) DEFAULT NULL COMMENT '监护人，双确认之一，须与操作人不同',
    executed_at TIMESTAMP DEFAULT NULL COMMENT '执行完成时间（UTC），未执行为空',
    CONSTRAINT uk_order_step_seq UNIQUE (order_id, seq)
);
COMMENT ON TABLE order_step IS '操作票步骤表，步序轨迹可由此复算';

CREATE TABLE IF NOT EXISTS device_state (
    device_id VARCHAR(64) PRIMARY KEY COMMENT '设备编号',
    interval_id VARCHAR(64) NOT NULL COMMENT '所属间隔编号',
    device_type VARCHAR(32) NOT NULL COMMENT '设备类型：BREAKER开关/DISCONNECTOR刀闸/GROUND_WIRE接地线/BUS母线',
    bus_id VARCHAR(64) DEFAULT NULL COMMENT '所属母线编号（出线开关使用），非出线设备为空',
    state VARCHAR(32) NOT NULL COMMENT '当前状态：ON/OFF/OPEN/CLOSED/ATTACHED/REMOVED/ENERGIZED/DEENERGIZED',
    unlocked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '紧急解锁标志：TRUE表示闭锁已解除未恢复；正常流程下解锁与恢复同事务完成，稳态为FALSE',
    updated_at TIMESTAMP NOT NULL COMMENT '状态更新时间（UTC）'
);
COMMENT ON TABLE device_state IS '设备实时状态表（合成数据）';

CREATE TABLE IF NOT EXISTS interlock_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '规则主键',
    rule_code VARCHAR(64) NOT NULL COMMENT '规则编码（业务唯一）',
    description VARCHAR(255) NOT NULL COMMENT '规则描述',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
    CONSTRAINT uk_interlock_rule_code UNIQUE (rule_code)
);
COMMENT ON TABLE interlock_rule IS '防误闭锁规则库（合成数据）';

CREATE TABLE IF NOT EXISTS interlock_hit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '命中记录主键',
    order_id BIGINT NOT NULL COMMENT '命中时操作票ID',
    step_seq INT NOT NULL COMMENT '命中时步序',
    rule_code VARCHAR(64) NOT NULL COMMENT '命中的闭锁规则编码',
    detail VARCHAR(512) NOT NULL COMMENT '命中详情',
    hit_at TIMESTAMP NOT NULL COMMENT '命中时间（UTC）'
);
COMMENT ON TABLE interlock_hit IS '闭锁命中记录表，可复算';

CREATE TABLE IF NOT EXISTS telemetry_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '遥信事件主键',
    interval_id VARCHAR(64) NOT NULL COMMENT '间隔编号，与时标联合去重',
    device_id VARCHAR(64) NOT NULL COMMENT '设备编号',
    state VARCHAR(32) NOT NULL COMMENT '回报的实际状态',
    event_time TIMESTAMP NOT NULL COMMENT '遥信时标（UTC），与间隔联合去重',
    late BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否迟到事件：TRUE表示到达时无执行中操作票（如已归档），仅记录不参与判定',
    received_at TIMESTAMP NOT NULL COMMENT '接收时间（UTC）',
    CONSTRAINT uk_telemetry_dedup UNIQUE (interval_id, event_time)
);
COMMENT ON TABLE telemetry_event IS '遥信回报事件表，按间隔+时标去重';

CREATE TABLE IF NOT EXISTS unlock_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '解锁记录主键',
    order_id BIGINT NOT NULL COMMENT '关联操作票ID',
    device_id VARCHAR(64) NOT NULL COMMENT '解锁设备编号',
    authorized_by1 VARCHAR(64) NOT NULL COMMENT '授权人一',
    authorized_by2 VARCHAR(64) NOT NULL COMMENT '授权人二，须与授权人一不同',
    reason VARCHAR(255) NOT NULL COMMENT '解锁原因',
    unlocked_at TIMESTAMP NOT NULL COMMENT '解锁时间（UTC）',
    restored_at TIMESTAMP NOT NULL COMMENT '闭锁恢复时间（UTC），与解锁在同一事务内写入，不允许只解锁不恢复',
    CONSTRAINT uk_unlock_order_device UNIQUE (order_id, device_id)
);
COMMENT ON TABLE unlock_record IS '紧急解锁留痕表，可复算';

CREATE TABLE IF NOT EXISTS order_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件主键',
    order_id BIGINT NOT NULL COMMENT '操作票ID',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：DRAFTED/REVIEWED/ISSUED/STEP_DONE/INTERLOCK_HIT/UNLOCK/INTERLOCK_RESTORED/ARCHIVED/ABORTED/EXPIRED',
    actor VARCHAR(64) DEFAULT NULL COMMENT '操作人，系统事件为空',
    detail VARCHAR(512) NOT NULL COMMENT '事件详情',
    event_at TIMESTAMP NOT NULL COMMENT '事件时间（UTC）'
);
COMMENT ON TABLE order_event IS '操作票流转事件表，步序轨迹可复算';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) PRIMARY KEY COMMENT '幂等键',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹：requestId与全部业务字段的SHA-256，同键异参判冲突',
    response_status INT NOT NULL COMMENT '首次响应的HTTP状态码',
    response_body CLOB NOT NULL COMMENT '首次响应报文（JSON），同键同参原样返回',
    created_at TIMESTAMP NOT NULL COMMENT '记录时间（UTC）'
);
COMMENT ON TABLE idempotency_record IS '幂等记录表';
