-- 跑道临时关闭巡检与开放决策服务建表脚本（H2 MySQL 兼容模式）
-- 所有时间字段均为 UTC 瞬时值；空值含义见各列 COMMENT。

CREATE TABLE IF NOT EXISTS runway_closure_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '关闭事件主键',
    runway_id VARCHAR(32) NOT NULL COMMENT '跑道编号，如 01L',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型: FOD=异物, PAVEMENT_DAMAGE=道面破损',
    status VARCHAR(32) NOT NULL COMMENT '状态: TEMP_CLOSED临时关闭/INSPECTING巡检评估中/REOPENED已恢复开放/ARCHIVED已归档(终态)',
    escalated TINYINT NOT NULL DEFAULT 0 COMMENT '巡检迟延是否已升级: 0否 1是(关闭后满15分钟未开始巡检)',
    repair_required TINYINT NOT NULL DEFAULT 0 COMMENT '是否修复类事件(评估为修复后恢复): 0否 1是',
    repair_cleared TINYINT NOT NULL DEFAULT 0 COMMENT '修复后复测是否合格: 0否 1是',
    reported_at TIMESTAMP NOT NULL COMMENT '接报时间(UTC)',
    closed_at TIMESTAMP NOT NULL COMMENT '临时关闭生效时间(UTC), 与接报同步',
    reopened_at TIMESTAMP NULL COMMENT '恢复开放时间(UTC), 未开放为空',
    archived_at TIMESTAMP NULL COMMENT '归档时间(UTC), 未归档为空',
    active_key VARCHAR(32) NULL COMMENT '进行中事件占用键=跑道编号, 终态置空; 唯一约束保证同一跑道同时刻仅一个进行中事件',
    request_id VARCHAR(64) NOT NULL COMMENT '接报幂等请求号',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时间(UTC)',
    updated_at TIMESTAMP NOT NULL COMMENT '记录更新时间(UTC)',
    CONSTRAINT uk_closure_active_key UNIQUE (active_key)
) COMMENT='跑道临时关闭事件';

CREATE TABLE IF NOT EXISTS inspection_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '巡检记录主键',
    closure_event_id BIGINT NOT NULL COMMENT '关联关闭事件ID',
    inspector_count INT NOT NULL COMMENT '巡检人数; 低能见度(RVR低于阈值)须>=2人生效',
    rvr_meters INT NOT NULL COMMENT '合成跑道视程RVR(米)',
    friction_coefficient DECIMAL(4,3) NOT NULL COMMENT '道面摩擦系数; 低于0.350须持续关闭, 恰0.350合格',
    assessment VARCHAR(32) NOT NULL COMMENT '评估结论: CLEAR_TO_REOPEN清除即可恢复/REPAIR_REQUIRED修复后恢复',
    effective TINYINT NOT NULL COMMENT '记录是否生效: 0否(低能见度单人或终态迟到) 1是',
    late TINYINT NOT NULL DEFAULT 0 COMMENT '是否归档/开放后迟到记录: 0否 1是',
    delayed TINYINT NOT NULL DEFAULT 0 COMMENT '是否迟延开始(开始时间不早于关闭后15分钟): 0否 1是',
    started_at TIMESTAMP NOT NULL COMMENT '巡检开始时间(UTC)',
    recorded_at TIMESTAMP NOT NULL COMMENT '记录写入时间(UTC)',
    request_id VARCHAR(64) NOT NULL COMMENT '评估幂等请求号',
    CONSTRAINT fk_inspection_event FOREIGN KEY (closure_event_id) REFERENCES runway_closure_event (id)
) COMMENT='跑道巡检评估记录(系数轨迹来源)';

CREATE TABLE IF NOT EXISTS notam_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '通告记录主键',
    closure_event_id BIGINT NOT NULL COMMENT '关联关闭事件ID',
    notam_type VARCHAR(16) NOT NULL COMMENT '通告类型: CLOSURE关闭/REOPEN恢复开放',
    content VARCHAR(512) NOT NULL COMMENT '合成航行通告内容',
    published_at TIMESTAMP NOT NULL COMMENT '发布时间(UTC), 与状态变更同事务',
    request_id VARCHAR(64) NOT NULL COMMENT '发布幂等请求号',
    CONSTRAINT fk_notam_event FOREIGN KEY (closure_event_id) REFERENCES runway_closure_event (id)
) COMMENT='合成航行通告发布记录';

CREATE TABLE IF NOT EXISTS closure_status_timeline (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '时间线主键',
    closure_event_id BIGINT NOT NULL COMMENT '关联关闭事件ID',
    from_status VARCHAR(32) NULL COMMENT '原状态, 事件创建时为空',
    to_status VARCHAR(32) NOT NULL COMMENT '目标状态',
    reason VARCHAR(255) NOT NULL COMMENT '流转原因',
    occurred_at TIMESTAMP NOT NULL COMMENT '发生时间(UTC)',
    CONSTRAINT fk_timeline_event FOREIGN KEY (closure_event_id) REFERENCES runway_closure_event (id)
) COMMENT='关闭事件状态时间线';

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '幂等记录主键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型: REPORT接报/INSPECT评估/REOPEN恢复',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求指纹: requestId与全部业务字段的SHA-256',
    response_body CLOB NOT NULL COMMENT '首次成功响应快照JSON, 同键同参重放时原样返回',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时间(UTC)',
    CONSTRAINT uk_idempotency_op_req UNIQUE (operation, request_id)
) COMMENT='幂等请求记录';
