-- 叶片巡检与维修服务建表脚本（H2 MODE=MySQL 兼容语法，幂等可重复执行）

CREATE TABLE IF NOT EXISTS blade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    blade_code VARCHAR(64) NOT NULL COMMENT '叶片编号，全局唯一',
    turbine_id VARCHAR(64) NOT NULL COMMENT '所属机组编号',
    CONSTRAINT uk_blade_code UNIQUE (blade_code)
);
COMMENT ON TABLE blade IS '叶片台账，启动时初始化的合成数据';

CREATE TABLE IF NOT EXISTS inspection_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    blade_id BIGINT NOT NULL COMMENT '叶片ID',
    position VARCHAR(128) NOT NULL COMMENT '巡检位置，如 叶根0-5m',
    inspector VARCHAR(64) NOT NULL COMMENT '巡检人',
    inspected_at TIMESTAMP NOT NULL COMMENT '巡检时刻，取自应用时钟',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻',
    CONSTRAINT uk_inspection_request UNIQUE (request_id)
);
COMMENT ON TABLE inspection_record IS '巡检记录，按叶片与位置登记';

CREATE TABLE IF NOT EXISTS grading_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    defect_type VARCHAR(64) NOT NULL COMMENT '缺陷类型，* 表示通配规则',
    max_depth_ratio DECIMAL(6,4) NULL COMMENT '深度占壁厚比上限(含)，NULL 表示不限',
    level VARCHAR(16) NOT NULL COMMENT '分级结果：SURFACE 表面 / SANDWICH 夹层 / STRUCTURAL 结构',
    priority INT NOT NULL COMMENT '规则优先级，数值小者优先',
    CONSTRAINT uk_grading_type_priority UNIQUE (defect_type, priority)
);
COMMENT ON TABLE grading_rule IS '合成分级表，按缺陷类型与深度比定级';

CREATE TABLE IF NOT EXISTS defect (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    inspection_id BIGINT NULL COMMENT '所属巡检记录ID，NULL 表示未关联',
    blade_id BIGINT NOT NULL COMMENT '叶片ID',
    position VARCHAR(128) NOT NULL COMMENT '缺陷位置',
    defect_type VARCHAR(64) NOT NULL COMMENT '缺陷类型，如 CRACK/EROSION/DELAMINATION',
    length_mm DECIMAL(10,2) NULL COMMENT '缺陷长度，单位毫米，NULL 表示未测量',
    depth_ratio DECIMAL(6,4) NOT NULL COMMENT '缺陷深度占壁厚比，0-1',
    level VARCHAR(16) NOT NULL COMMENT '合成分级结果：SURFACE/SANDWICH/STRUCTURAL',
    status VARCHAR(16) NOT NULL COMMENT 'OPEN 开放 / CLOSED 已闭环',
    registered_at TIMESTAMP NOT NULL COMMENT '首次缺陷登记时刻，逾期升级保留，不可改',
    closed_at TIMESTAMP NULL COMMENT '闭环时刻，NULL 表示未闭环',
    CONSTRAINT uk_defect_request UNIQUE (request_id),
    CONSTRAINT fk_defect_inspection FOREIGN KEY (inspection_id) REFERENCES inspection_record (id)
);
COMMENT ON TABLE defect IS '缺陷登记，原缺陷字段不可被维修记录覆盖';

CREATE TABLE IF NOT EXISTS root_cause_evaluation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    blade_id BIGINT NOT NULL COMMENT '叶片ID',
    defect_type VARCHAR(64) NOT NULL COMMENT '复发缺陷类型',
    trigger_defect_id BIGINT NOT NULL COMMENT '触发评估的第 2 次缺陷ID',
    status VARCHAR(16) NOT NULL COMMENT 'IN_PROGRESS 评估中 / COMPLETED 已完成',
    triggered_at TIMESTAMP NOT NULL COMMENT '触发时刻',
    completed_at TIMESTAMP NULL COMMENT '完成时刻，NULL 表示评估中',
    conclusion VARCHAR(512) NULL COMMENT '评估结论，完成时填写',
    CONSTRAINT uk_eval_trigger_defect UNIQUE (trigger_defect_id),
    CONSTRAINT fk_eval_defect FOREIGN KEY (trigger_defect_id) REFERENCES defect (id)
);
COMMENT ON TABLE root_cause_evaluation IS '根因评估，同类缺陷90日内恰第2次复发触发';

CREATE TABLE IF NOT EXISTS curtailment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    blade_id BIGINT NOT NULL COMMENT '叶片ID',
    reason VARCHAR(32) NOT NULL COMMENT 'STRUCTURAL_DEFECT 结构级缺陷 / ROOT_CAUSE_EVAL 根因评估',
    source_defect_id BIGINT NULL COMMENT '来源缺陷ID，reason=STRUCTURAL_DEFECT 时有值',
    source_evaluation_id BIGINT NULL COMMENT '来源评估ID，reason=ROOT_CAUSE_EVAL 时有值',
    started_at TIMESTAMP NOT NULL COMMENT '限功率开始时刻',
    lifted_at TIMESTAMP NULL COMMENT '限功率撤销时刻，NULL 表示仍生效',
    CONSTRAINT fk_curt_defect FOREIGN KEY (source_defect_id) REFERENCES defect (id),
    CONSTRAINT fk_curt_eval FOREIGN KEY (source_evaluation_id) REFERENCES root_cause_evaluation (id)
);
COMMENT ON TABLE curtailment IS '限功率链，撤销与复检合格在同一事务内写入';

CREATE TABLE IF NOT EXISTS repair_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    defect_id BIGINT NOT NULL COMMENT '关联缺陷ID',
    blade_id BIGINT NOT NULL COMMENT '叶片ID',
    status VARCHAR(16) NOT NULL COMMENT 'IN_PROGRESS 进行中 / CLOSED 已结案',
    deadline_at TIMESTAMP NOT NULL COMMENT '维修期限=首次登记时刻+7日，恰第7日闭环有效',
    created_at TIMESTAMP NOT NULL COMMENT '建单时刻',
    closed_at TIMESTAMP NULL COMMENT '结案时刻，NULL 表示未结案',
    active_blade_key BIGINT NULL COMMENT '进行中等于 blade_id，结案置 NULL；唯一约束保证同叶片同时刻仅一张进行中维修单',
    CONSTRAINT uk_repair_request UNIQUE (request_id),
    CONSTRAINT uk_repair_active_blade UNIQUE (active_blade_key),
    CONSTRAINT fk_repair_defect FOREIGN KEY (defect_id) REFERENCES defect (id)
);
COMMENT ON TABLE repair_order IS '维修单台账';

CREATE TABLE IF NOT EXISTS repair_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    repair_order_id BIGINT NOT NULL COMMENT '维修单ID',
    technician VARCHAR(64) NOT NULL COMMENT '维修人',
    description VARCHAR(512) NOT NULL COMMENT '维修内容',
    repaired_at TIMESTAMP NOT NULL COMMENT '维修时刻',
    CONSTRAINT uk_repair_record_request UNIQUE (request_id),
    CONSTRAINT fk_repair_record_order FOREIGN KEY (repair_order_id) REFERENCES repair_order (id)
);
COMMENT ON TABLE repair_record IS '维修记录，追加式，不覆盖原缺陷';

CREATE TABLE IF NOT EXISTS reinspection_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    repair_order_id BIGINT NOT NULL COMMENT '维修单ID',
    defect_id BIGINT NOT NULL COMMENT '缺陷ID',
    result VARCHAR(8) NOT NULL COMMENT 'PASS 合格 / FAIL 不合格',
    inspector VARCHAR(64) NOT NULL COMMENT '复检人',
    inspected_at TIMESTAMP NOT NULL COMMENT '复检时刻',
    CONSTRAINT uk_reinspect_request UNIQUE (request_id),
    CONSTRAINT fk_reinspect_order FOREIGN KEY (repair_order_id) REFERENCES repair_order (id),
    CONSTRAINT fk_reinspect_defect FOREIGN KEY (defect_id) REFERENCES defect (id)
);
COMMENT ON TABLE reinspection_record IS '复检记录，合格则同事务撤销限功率并结案';

CREATE TABLE IF NOT EXISTS escalation_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    defect_id BIGINT NOT NULL COMMENT '逾期缺陷ID，每缺陷仅一条',
    first_registered_at TIMESTAMP NOT NULL COMMENT '首次缺陷登记时刻，升级时快照保留',
    escalated_at TIMESTAMP NOT NULL COMMENT '升级告警时刻',
    message VARCHAR(512) NOT NULL COMMENT '告警内容',
    CONSTRAINT uk_escalation_defect UNIQUE (defect_id),
    CONSTRAINT fk_escalation_defect FOREIGN KEY (defect_id) REFERENCES defect (id)
);
COMMENT ON TABLE escalation_alert IS '逾期升级告警记录，保留首次登记时刻';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_key VARCHAR(160) PRIMARY KEY COMMENT '幂等键，端点前缀+requestId',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求指纹，覆盖 requestId 与全部业务字段',
    response_json CLOB NOT NULL COMMENT '首次成功响应快照',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻'
);
COMMENT ON TABLE idempotency_record IS '写操作幂等记录，同键同参返回首次结果，同键异参409';
