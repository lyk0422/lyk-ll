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
    version INT NOT NULL DEFAULT 1 COMMENT '工单乐观版本，从1开始，发电车调度下发时冻结',
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
    event_type VARCHAR(32) NOT NULL COMMENT '轨迹事件类型：REPORTED/ASSESSED/DISPATCHED/QUEUED/RESTORED/SEALED/MERGED/CREATED_BY_MERGE/GENERATOR_DISPATCH_CANCELLED',
    detail VARCHAR(512) NULL COMMENT '事件详情，空表示无补充信息',
    created_at TIMESTAMP NOT NULL COMMENT '事件发生时间'
);

-- ==================== 重要用户发电车组合调度（B 题新增） ====================
-- 调度相关时间字段除特别说明外均为 UTC 时刻；容量单位 kW，均为正整数。

CREATE TABLE IF NOT EXISTS generator_vehicle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '登记幂等请求号',
    code VARCHAR(64) NOT NULL COMMENT '发电车编号（合成数据）',
    capacity_kw INT NOT NULL COMMENT '额定容量，正整数，单位kW',
    feeder_id VARCHAR(64) NOT NULL COMMENT '服务馈线编号，仅可调度至同馈线工单',
    available BOOLEAN NOT NULL COMMENT '可用状态：TRUE可用/FALSE停用',
    created_at TIMESTAMP NOT NULL COMMENT '登记时间（UTC）',
    CONSTRAINT uk_generator_vehicle_code UNIQUE (code),
    CONSTRAINT uk_generator_vehicle_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS generator_dispatch_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '创建（或改派创建）幂等请求号',
    fingerprint VARCHAR(128) NOT NULL COMMENT '结构化幂等指纹：含版本、集合与精确时刻',
    work_order_id BIGINT NOT NULL COMMENT '引用的未复电工单ID',
    frozen_order_status VARCHAR(24) NOT NULL COMMENT '下发时工单状态快照（冻结工单版本）',
    total_load_kw INT NOT NULL COMMENT '重要用户合计负荷快照，正整数，单位kW',
    total_capacity_kw INT NOT NULL COMMENT '组合车总容量，单位kW，必须>=合计负荷（恰等合法）',
    status VARCHAR(24) NOT NULL COMMENT 'DISPATCHED已下发/PARTIAL_ARRIVAL部分到场/POWER_SUPPLIED全部到场已供电/ARRIVAL_TIMEOUT到达超时(可改派)/REDISPATCHED已改派(终态)/CANCELLED已撤销(终态)',
    version INT NOT NULL COMMENT '乐观版本，从1开始，每次成功变更+1',
    dispatched_at TIMESTAMP NOT NULL COMMENT 'UTC下发时刻',
    deadline_at TIMESTAMP NOT NULL COMMENT 'UTC到场截止时刻=下发+30分钟，恰满未全到场即超时',
    all_arrived_at TIMESTAMP NULL COMMENT '全部到场时刻（UTC），空表示未全到场',
    supplied_at TIMESTAMP NULL COMMENT '临时供电开始时刻（UTC），空表示未供电；仅全到场且未超时才置位',
    replaced_by_id BIGINT NULL COMMENT '改派后新调度单ID，空表示未被改派',
    cancel_reason VARCHAR(48) NULL COMMENT '撤销原因：MERGED工单合并/FORMAL_RESTORE正式复电，空表示未撤销',
    created_at TIMESTAMP NOT NULL COMMENT '建单时间（UTC）',
    CONSTRAINT uk_gen_order_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS generator_dispatch_vehicle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '调度单ID',
    vehicle_id BIGINT NOT NULL COMMENT '发电车ID',
    carried_over BOOLEAN NOT NULL COMMENT '是否改派时从旧单保留的已到场车',
    arrived_at TIMESTAMP NULL COMMENT '该车到场时刻（UTC），空表示未到场；旧单终止后迟到不回写',
    CONSTRAINT uk_gen_order_vehicle UNIQUE (order_id, vehicle_id)
);

CREATE TABLE IF NOT EXISTS generator_load_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '调度单ID',
    seq INT NOT NULL COMMENT '清单序号，按请求顺序冻结',
    user_name VARCHAR(64) NOT NULL COMMENT '重要用户名称（合成数据）',
    feeder_id VARCHAR(64) NOT NULL COMMENT '用户所属馈线快照',
    load_kw INT NOT NULL COMMENT '该用户保安负荷，正整数，单位kW'
);

CREATE TABLE IF NOT EXISTS generator_occupation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id BIGINT NOT NULL COMMENT '发电车ID',
    order_id BIGINT NOT NULL COMMENT '占用该车的调度单ID',
    carried_over BOOLEAN NOT NULL COMMENT '是否改派结转产生的占用',
    occupied_at TIMESTAMP NOT NULL COMMENT '占用开始时刻（UTC）',
    released_at TIMESTAMP NULL COMMENT '占用释放时刻（UTC），空表示占用中；台账历史保留',
    release_reason VARCHAR(48) NULL COMMENT '释放原因：REDISPATCH_CARRIED改派结转/REDISPATCH_RELEASED改派释放/MERGED工单合并/FORMAL_RESTORE正式复电',
    CONSTRAINT uk_gen_occupation_vehicle_order UNIQUE (vehicle_id, order_id)
);

CREATE TABLE IF NOT EXISTS generator_supply_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '供电的调度单ID',
    total_load_kw INT NOT NULL COMMENT '供电时合计负荷，单位kW',
    total_capacity_kw INT NOT NULL COMMENT '供电时组合总容量，单位kW',
    supplied_at TIMESTAMP NOT NULL COMMENT '临时供电开始时刻（UTC）；调度单撤销后证据仍保留',
    CONSTRAINT uk_gen_supply_order UNIQUE (order_id)
);

CREATE TABLE IF NOT EXISTS generator_order_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '调度单ID',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：DISPATCHED/ARRIVED/ALL_ARRIVED_SUPPLIED/ARRIVAL_TIMEOUT/REDISPATCHED/CANCELLED/LATE_ARRIVAL',
    vehicle_id BIGINT NULL COMMENT '关联发电车ID，空表示单级事件',
    detail VARCHAR(512) NULL COMMENT '事件详情，空表示无补充信息',
    created_at TIMESTAMP NOT NULL COMMENT '事件记录时间（UTC）'
);

CREATE TABLE IF NOT EXISTS generator_idem_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号',
    fingerprint VARCHAR(128) NOT NULL COMMENT '结构化幂等指纹',
    operation VARCHAR(32) NOT NULL COMMENT '写操作类型：VEHICLE_REGISTER/DISPATCH/ARRIVAL/REASSIGN',
    response_snapshot CLOB NOT NULL COMMENT '首次成功结果的冻结快照（JSON），同键同参原样重放',
    created_at TIMESTAMP NOT NULL COMMENT '首次成功时间（UTC）',
    CONSTRAINT uk_gen_idem_request UNIQUE (request_id)
);
