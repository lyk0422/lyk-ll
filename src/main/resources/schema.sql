-- 配网故障抢修排序与重要用户保电服务建表脚本（H2 MySQL 兼容模式）
-- 时间字段均为 Asia/Shanghai 本地时间；空值含义见各列注释。

CREATE TABLE IF NOT EXISTS repair_team (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL COMMENT '班组名称',
    CONSTRAINT uk_repair_team_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS important_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_name VARCHAR(64) NOT NULL COMMENT '重要用户名称（合成数据）',
    level VARCHAR(16) NOT NULL COMMENT '重要等级：SPECIAL特级/FIRST一级/SECOND二级',
    feeder_id VARCHAR(64) NOT NULL COMMENT '所属馈线编号',
    load_kw INT NOT NULL COMMENT '临时保电负荷，单位kW，正整数（合成数据）',
    CONSTRAINT uk_important_user_name UNIQUE (user_name)
);

CREATE TABLE IF NOT EXISTS work_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    feeder_id VARCHAR(64) NOT NULL COMMENT '馈线编号',
    fault_point VARCHAR(128) NOT NULL COMMENT '故障点，合并单为多个故障点拼接',
    affected_users INT NOT NULL COMMENT '影响用户数，归并后累计',
    important_level VARCHAR(16) NOT NULL COMMENT '涉及重要用户最高等级：SPECIAL/FIRST/SECOND/NONE',
    status VARCHAR(24) NOT NULL COMMENT 'PENDING待派单/QUEUED挂单/EXECUTING执行中/RESTORED已复电/SEALED日结封存(终态)/MERGED已合并',
    team_id BIGINT NULL COMMENT '执行或挂单等待的班组ID，空表示尚未指派',
    executing_team_id BIGINT NULL COMMENT '执行中班组ID，仅EXECUTING状态非空，唯一索引保证班组唯一执行单',
    outage_start_at TIMESTAMP NOT NULL COMMENT '停电开始时间（首次接报时间）',
    restored_at TIMESTAMP NULL COMMENT '复电完成时间，空表示未复电',
    merged_into_id BIGINT NULL COMMENT '合并目标工单ID，空表示未被合并',
    version INT NOT NULL DEFAULT 1 COMMENT '工单版本，从1开始，成功变更加1',
    created_at TIMESTAMP NOT NULL COMMENT '工单创建时间',
    CONSTRAINT uk_executing_team UNIQUE (executing_team_id)
);

CREATE TABLE IF NOT EXISTS fault_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹：requestId与全部业务字段的散列',
    feeder_id VARCHAR(64) NOT NULL COMMENT '馈线编号',
    fault_point VARCHAR(128) NOT NULL COMMENT '故障点',
    affected_users INT NOT NULL COMMENT '本次接报影响用户数',
    important_level VARCHAR(16) NOT NULL COMMENT '接报涉及重要用户等级：SPECIAL/FIRST/SECOND/NONE',
    reported_at TIMESTAMP NOT NULL COMMENT '接报时间',
    dedup_key VARCHAR(256) NOT NULL COMMENT '去重键：故障点+时段（小时）',
    merged_into_id BIGINT NULL COMMENT '归并到的首次接报ID，空表示首报',
    work_order_id BIGINT NOT NULL COMMENT '关联工单ID',
    CONSTRAINT uk_fault_report_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS dispatch_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹',
    work_order_id BIGINT NOT NULL COMMENT '工单ID',
    team_id BIGINT NOT NULL COMMENT '目标班组ID',
    result VARCHAR(16) NOT NULL COMMENT '派单结果：DISPATCHED已派单/QUEUED挂单',
    created_at TIMESTAMP NOT NULL COMMENT '派单时间',
    CONSTRAINT uk_dispatch_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS restore_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹',
    work_order_id BIGINT NOT NULL COMMENT '工单ID',
    confirmer_role VARCHAR(16) NOT NULL COMMENT '确认角色：DISPATCHER调度/FIELD现场',
    confirmer VARCHAR(64) NOT NULL COMMENT '确认人',
    load_kw DECIMAL(12,2) NULL COMMENT '现场回报恢复负荷，单位kW；调度确认可为空',
    created_at TIMESTAMP NOT NULL COMMENT '确认时间',
    CONSTRAINT uk_restore_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS load_recovery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_order_id BIGINT NOT NULL COMMENT '工单ID',
    load_kw DECIMAL(12,2) NOT NULL COMMENT '恢复负荷，单位kW',
    recorded_at TIMESTAMP NOT NULL COMMENT '负荷恢复记录时间，与复电确认同事务'
);

CREATE TABLE IF NOT EXISTS generator_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_order_id BIGINT NOT NULL COMMENT '工单ID',
    triggered_at TIMESTAMP NOT NULL COMMENT '发电车流程启动时间（特级用户停电满5分钟未恢复）',
    CONSTRAINT uk_generator_order UNIQUE (work_order_id)
);

CREATE TABLE IF NOT EXISTS late_report_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_order_id BIGINT NOT NULL COMMENT '工单ID',
    event_type VARCHAR(32) NOT NULL COMMENT '迟到事件类型，如LATE_RESTORE_CONFIRM',
    detail VARCHAR(512) NULL COMMENT '事件详情，空表示无补充信息',
    created_at TIMESTAMP NOT NULL COMMENT '事件记录时间'
);

CREATE TABLE IF NOT EXISTS work_order_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_order_id BIGINT NOT NULL COMMENT '工单ID',
    event_type VARCHAR(32) NOT NULL COMMENT '轨迹事件类型：REPORTED/ASSESSED/DISPATCHED/QUEUED/RESTORED/SEALED/MERGED/CREATED_BY_MERGE',
    detail VARCHAR(512) NULL COMMENT '事件详情，空表示无补充信息',
    created_at TIMESTAMP NOT NULL COMMENT '事件发生时间'
);

CREATE TABLE IF NOT EXISTS generator_truck (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    truck_code VARCHAR(64) NOT NULL COMMENT '发电车编号（合成数据）',
    capacity_kw INT NOT NULL COMMENT '额定容量，单位kW，正整数',
    feeder_id VARCHAR(64) NOT NULL COMMENT '服务馈线编号',
    available BOOLEAN NOT NULL COMMENT '是否可用：TRUE可用/FALSE停用',
    version INT NOT NULL COMMENT '车辆版本，从1开始，成功变更加1',
    created_at TIMESTAMP NOT NULL COMMENT '登记时间',
    CONSTRAINT uk_generator_truck_code UNIQUE (truck_code)
);

CREATE TABLE IF NOT EXISTS generator_dispatch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_order_id BIGINT NOT NULL COMMENT '关联未复电工单ID',
    work_order_version INT NOT NULL COMMENT '调度时冻结的工单版本',
    total_load_kw INT NOT NULL COMMENT '调度时冻结的合计保电负荷，单位kW',
    status VARCHAR(24) NOT NULL COMMENT 'DISPATCHED已下发/ARRIVAL_TIMEOUT到达超时/SUPPLYING供电中/REASSIGNED已改派/CANCELLED已撤销',
    version INT NOT NULL COMMENT '调度单版本，从1开始，成功变更加1',
    predecessor_id BIGINT NULL COMMENT '改派来源调度单ID，空表示首派',
    dispatched_at TIMESTAMP NOT NULL COMMENT '下发时间（Asia/Shanghai），30分钟到达时限起算点',
    supplied_at TIMESTAMP NULL COMMENT '全部到场确认供电时间，空表示未供电',
    closed_at TIMESTAMP NULL COMMENT '终态关闭时间（改派/撤销），空表示未关闭',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间'
);

CREATE TABLE IF NOT EXISTS generator_dispatch_truck (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispatch_id BIGINT NOT NULL COMMENT '调度单ID',
    truck_id BIGINT NOT NULL COMMENT '发电车ID',
    capacity_kw INT NOT NULL COMMENT '冻结的车辆容量快照，单位kW',
    arrived_at TIMESTAMP NULL COMMENT '到场时间，空表示未到场',
    active_occupation BIGINT NULL COMMENT '占用中等于truck_id，释放后置空；唯一索引保证一车一个未结束占用',
    CONSTRAINT uk_dispatch_truck UNIQUE (dispatch_id, truck_id),
    CONSTRAINT uk_active_occupation UNIQUE (active_occupation)
);

CREATE TABLE IF NOT EXISTS generator_dispatch_load (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispatch_id BIGINT NOT NULL COMMENT '调度单ID',
    user_name VARCHAR(64) NOT NULL COMMENT '重要用户名称（冻结快照）',
    load_kw INT NOT NULL COMMENT '冻结的保电负荷，单位kW',
    CONSTRAINT uk_dispatch_load UNIQUE (dispatch_id, user_name)
);

CREATE TABLE IF NOT EXISTS generator_dispatch_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispatch_id BIGINT NOT NULL COMMENT '调度单ID',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：TIMEOUT/LATE_ARRIVAL/REASSIGNED/CANCELLED/SUPPLYING',
    detail VARCHAR(512) NULL COMMENT '事件详情，空表示无补充信息',
    created_at TIMESTAMP NOT NULL COMMENT '事件时间'
);

CREATE TABLE IF NOT EXISTS generator_idempotency (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等请求号',
    fingerprint VARCHAR(128) NOT NULL COMMENT '结构化幂等指纹（长度前缀编码的SHA-256）',
    result_json VARCHAR(4096) NOT NULL COMMENT '首次冻结结果JSON，同键同参重放时原样返回',
    created_at TIMESTAMP NOT NULL COMMENT '记录时间'
);
