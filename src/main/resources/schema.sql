-- 叶片巡检与维修服务建表脚本（H2，MODE=MySQL）。
-- 时间列均为 UTC；NULL 统一表示“未发生/未结案/未撤销”。

CREATE TABLE IF NOT EXISTS blade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    blade_code VARCHAR(64) NOT NULL COMMENT '叶片编号（合成数据）',
    turbine_no VARCHAR(64) NOT NULL COMMENT '所属机组编号',
    created_at TIMESTAMP NOT NULL COMMENT '创建时刻(UTC)'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_blade_code ON blade(blade_code);

CREATE TABLE IF NOT EXISTS defect_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    blade_id BIGINT NOT NULL COMMENT '叶片ID',
    blade_position VARCHAR(128) NOT NULL COMMENT '缺陷在叶片上的位置（按叶片与位置登记）',
    defect_level VARCHAR(16) NOT NULL COMMENT '缺陷分级：SURFACE表面/SANDWICH夹层/STRUCTURAL结构',
    defect_type VARCHAR(64) NOT NULL COMMENT '缺陷类别，用于同类复发统计',
    detected_at TIMESTAMP NOT NULL COMMENT '首次缺陷登记时刻(UTC)，逾期升级须保留',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻(UTC)',
    CONSTRAINT fk_defect_blade FOREIGN KEY (blade_id) REFERENCES blade(id)
);
CREATE INDEX IF NOT EXISTS idx_defect_blade_type ON defect_record(blade_id, defect_type, detected_at);

CREATE TABLE IF NOT EXISTS repair_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    defect_id BIGINT NOT NULL COMMENT '缺陷记录ID；维修单关联但不覆盖原缺陷',
    blade_id BIGINT NOT NULL COMMENT '叶片ID',
    status VARCHAR(16) NOT NULL COMMENT 'IN_PROGRESS进行中/CLOSED已结案',
    deadline_at TIMESTAMP NOT NULL COMMENT '维修期限=缺陷登记时刻+7日(UTC)，恰第7日闭环有效',
    closed_at TIMESTAMP NULL COMMENT '结案时刻(UTC)，NULL表示未结案',
    close_result VARCHAR(8) NULL COMMENT 'ON_TIME按期闭环/LATE逾期闭环，NULL表示未结案',
    escalated BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已逾期升级：FALSE否/TRUE是',
    active_blade_id BIGINT NULL COMMENT '进行中维修单占用的叶片ID，结案置NULL；唯一索引保证同叶片同时刻仅一张进行中维修单',
    created_at TIMESTAMP NOT NULL COMMENT '创建时刻(UTC)',
    CONSTRAINT fk_ro_defect FOREIGN KEY (defect_id) REFERENCES defect_record(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ro_defect ON repair_order(defect_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ro_active_blade ON repair_order(active_blade_id);

CREATE TABLE IF NOT EXISTS reinspection_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repair_order_id BIGINT NOT NULL COMMENT '维修单ID',
    result VARCHAR(8) NOT NULL COMMENT '复检结果：PASS合格/FAIL不合格',
    inspected_at TIMESTAMP NOT NULL COMMENT '复检时刻(UTC)',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻(UTC)',
    CONSTRAINT fk_ri_order FOREIGN KEY (repair_order_id) REFERENCES repair_order(id)
);
CREATE INDEX IF NOT EXISTS idx_ri_order ON reinspection_record(repair_order_id);

CREATE TABLE IF NOT EXISTS power_limit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    blade_id BIGINT NOT NULL COMMENT '叶片ID',
    reason VARCHAR(32) NOT NULL COMMENT '限功率原因：STRUCTURAL_DEFECT结构级缺陷/ROOT_CAUSE_ASSESSMENT根因评估',
    started_at TIMESTAMP NOT NULL COMMENT '限功率开始时刻(UTC)',
    revoked_at TIMESTAMP NULL COMMENT '撤销时刻(UTC)，NULL表示仍生效',
    active_blade_id BIGINT NULL COMMENT '生效中限功率占用的叶片ID，撤销置NULL；唯一索引保证同叶片仅一条生效中限功率',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻(UTC)'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pl_active_blade ON power_limit(active_blade_id);
CREATE INDEX IF NOT EXISTS idx_pl_blade ON power_limit(blade_id, started_at);

CREATE TABLE IF NOT EXISTS alert_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repair_order_id BIGINT NOT NULL COMMENT '逾期升级的维修单ID，每单仅告警一次',
    blade_id BIGINT NOT NULL COMMENT '叶片ID',
    defect_id BIGINT NOT NULL COMMENT '原缺陷记录ID',
    first_detected_at TIMESTAMP NOT NULL COMMENT '首次缺陷登记时刻(UTC)，升级时保留',
    alert_at TIMESTAMP NOT NULL COMMENT '告警时刻(UTC)',
    message VARCHAR(255) NOT NULL COMMENT '告警内容',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻(UTC)'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_alert_order ON alert_record(repair_order_id);

CREATE TABLE IF NOT EXISTS root_cause_assessment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    blade_id BIGINT NOT NULL COMMENT '叶片ID',
    defect_type VARCHAR(64) NOT NULL COMMENT '触发评估的同类缺陷类别',
    trigger_count INT NOT NULL COMMENT '触发时90日窗口内同类缺陷次数，恰为2时触发',
    window_start TIMESTAMP NOT NULL COMMENT '统计窗口起点(UTC)=第二次登记时刻-90日',
    window_end TIMESTAMP NOT NULL COMMENT '统计窗口终点(UTC)=第二次缺陷登记时刻',
    status VARCHAR(16) NOT NULL COMMENT 'IN_PROGRESS评估中/COMPLETED已完成',
    started_at TIMESTAMP NOT NULL COMMENT '评估开始时刻(UTC)',
    completed_at TIMESTAMP NULL COMMENT '评估完成时刻(UTC)，NULL表示评估中',
    active_key VARCHAR(160) NULL COMMENT '评估中占用的 bladeId:defectType 键，完成置NULL；唯一索引防止重复评估',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻(UTC)'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rca_active ON root_cause_assessment(active_key);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等键',
    endpoint VARCHAR(96) NOT NULL COMMENT '写操作端点标识',
    fingerprint VARCHAR(64) NOT NULL COMMENT 'requestId与全部业务字段的SHA-256指纹',
    response_body CLOB NULL COMMENT '首次成功响应快照，同键同参重放该结果',
    created_at TIMESTAMP NOT NULL COMMENT '创建时刻(UTC)'
);
