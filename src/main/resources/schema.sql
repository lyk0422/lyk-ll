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
    version INT NOT NULL DEFAULT 1 COMMENT '工单版本号，从1开始，成功变更加1',
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

-- 发电车临时保电：合成发电车池、组合调度单、车辆占用与供电证据。
-- 时间字段均为 Asia/Shanghai 本地时间（下发/到场请求以UTC精确时刻传入后转换存储）。

CREATE TABLE IF NOT EXISTS generator_truck (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    capacity_kw INT NOT NULL COMMENT '发电车容量，单位kW，正整数',
    feeder_id VARCHAR(64) NOT NULL COMMENT '服务馈线编号',
    available BOOLEAN NOT NULL COMMENT '可用状态：TRUE可用/FALSE停用，停用不可派',
    version INT NOT NULL COMMENT '车辆版本号，从1开始，成功变更加1',
    created_at TIMESTAMP NOT NULL COMMENT '登记时间'
);

CREATE TABLE IF NOT EXISTS generator_dispatch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_order_id BIGINT NOT NULL COMMENT '未复电工单ID',
    order_version INT NOT NULL COMMENT '调度时冻结的工单版本号',
    total_load_kw INT NOT NULL COMMENT '调度时冻结的合计负荷，单位kW',
    status VARCHAR(16) NOT NULL COMMENT 'IN_PROGRESS待到场/SUPPLYING全部到场供电中/TIMED_OUT到达超时/REASSIGNED已改派/CANCELLED已撤销',
    version INT NOT NULL COMMENT '调度单版本号，从1开始，成功变更加1',
    dispatched_at TIMESTAMP NOT NULL COMMENT '下发时刻（请求以UTC精确时刻传入，存为本地时间）',
    superseded_by_id BIGINT NULL COMMENT '整体改派生成的新调度单ID，空表示未被改派',
    created_at TIMESTAMP NOT NULL COMMENT '调度单创建时间'
);

CREATE TABLE IF NOT EXISTS dispatch_load (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispatch_id BIGINT NOT NULL COMMENT '调度单ID',
    user_name VARCHAR(64) NOT NULL COMMENT '重要用户名称（合成数据）',
    load_kw INT NOT NULL COMMENT '冻结负荷，单位kW，正整数'
);

CREATE TABLE IF NOT EXISTS dispatch_truck (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispatch_id BIGINT NOT NULL COMMENT '调度单ID',
    truck_id BIGINT NOT NULL COMMENT '发电车ID',
    arrived_at TIMESTAMP NULL COMMENT '到场时刻，空表示未到场；逐车唯一记录，迟到到场不重复占车',
    CONSTRAINT uk_dispatch_truck UNIQUE (dispatch_id, truck_id)
);

CREATE TABLE IF NOT EXISTS active_occupation (
    truck_id BIGINT PRIMARY KEY COMMENT '发电车ID，主键保证一车仅一个未结束占用',
    dispatch_id BIGINT NOT NULL COMMENT '占用该车的调度单ID',
    occupied_at TIMESTAMP NOT NULL COMMENT '占用开始时间'
);

CREATE TABLE IF NOT EXISTS supply_evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispatch_id BIGINT NOT NULL COMMENT '调度单ID',
    work_order_id BIGINT NOT NULL COMMENT '工单ID',
    total_load_kw INT NOT NULL COMMENT '供电负荷，单位kW，全部到场才入账',
    supplied_at TIMESTAMP NOT NULL COMMENT '全部到场供电时刻；撤销调度后证据保留',
    CONSTRAINT uk_supply_dispatch UNIQUE (dispatch_id)
);

CREATE TABLE IF NOT EXISTS gen_idempotency (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求号',
    fingerprint VARCHAR(128) NOT NULL COMMENT '结构化幂等指纹：长度前缀编码的SHA-256',
    op VARCHAR(32) NOT NULL COMMENT '操作类型：TRUCK_REGISTER/TRUCK_AVAILABILITY/DISPATCH_CREATE/DISPATCH_ARRIVAL/DISPATCH_REASSIGN/REPLACEMENT_CREATE/REPLACEMENT_ARRIVAL/REPLACEMENT_SWITCH_CONFIRM/REPLACEMENT_WITHDRAW',
    entity_id BIGINT NOT NULL COMMENT '主实体ID（车辆、调度单或接替单）',
    outcome CLOB NOT NULL COMMENT '首次冻结结果JSON，同键同参重放',
    created_at TIMESTAMP NOT NULL COMMENT '首次成功时间'
);

-- 热备接替：SUPPLYING调度单内一辆供电旧车由1至3辆同馈线热备新车替换。
-- 时间字段均为 Asia/Shanghai 本地时间（请求以UTC精确时刻传入后转换存储）。

CREATE TABLE IF NOT EXISTS truck_replacement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispatch_id BIGINT NOT NULL COMMENT '原SUPPLYING调度单ID',
    work_order_id BIGINT NOT NULL COMMENT '工单ID',
    old_truck_id BIGINT NOT NULL COMMENT '被接替的供电旧车ID',
    order_version INT NOT NULL COMMENT '申请时冻结的工单版本号',
    dispatch_version INT NOT NULL COMMENT '申请时冻结的原调度单版本号',
    total_load_kw INT NOT NULL COMMENT '冻结的合计负荷，单位kW，切换前后均须被覆盖',
    old_capacity_kw INT NOT NULL COMMENT '切换前旧车容量证据，单位kW',
    new_capacity_kw INT NULL COMMENT '切换后新车容量合计证据，单位kW，空表示未切换',
    status VARCHAR(16) NOT NULL COMMENT 'STANDBY热备预占中/SWITCHED已切换/WITHDRAWN已撤回/CLOSED复电或合并关闭',
    version INT NOT NULL COMMENT '接替单版本号，从1开始，成功变更加1',
    created_at TIMESTAMP NOT NULL COMMENT '接替单创建时间（请求UTC时刻转本地）',
    switched_at TIMESTAMP NULL COMMENT '切换完成时刻，空表示未切换'
);

CREATE TABLE IF NOT EXISTS replacement_truck (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    replacement_id BIGINT NOT NULL COMMENT '接替单ID',
    truck_id BIGINT NOT NULL COMMENT '热备新车ID',
    arrived_at TIMESTAMP NULL COMMENT '到场时刻，空表示未到场；逐车唯一记录',
    CONSTRAINT uk_replacement_truck UNIQUE (replacement_id, truck_id)
);

CREATE TABLE IF NOT EXISTS replacement_reservation (
    truck_id BIGINT PRIMARY KEY COMMENT '热备预占的新车ID，主键保证一车仅一个未结束预占',
    replacement_id BIGINT NOT NULL COMMENT '预占该车的接替单ID',
    reserved_at TIMESTAMP NOT NULL COMMENT '预占开始时间'
);

CREATE TABLE IF NOT EXISTS active_replacement (
    old_truck_id BIGINT PRIMARY KEY COMMENT '被接替的旧车ID，主键保证一车仅一个未结束接替',
    replacement_id BIGINT NOT NULL COMMENT '未结束接替单ID',
    created_at TIMESTAMP NOT NULL COMMENT '接替创建时间'
);

CREATE TABLE IF NOT EXISTS replacement_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    replacement_id BIGINT NOT NULL COMMENT '接替单ID',
    operator VARCHAR(64) NOT NULL COMMENT '切换确认合成操作员，两人不同名才切换',
    confirmed_at TIMESTAMP NOT NULL COMMENT '确认时刻（请求UTC时刻转本地）',
    CONSTRAINT uk_replacement_operator UNIQUE (replacement_id, operator)
);
