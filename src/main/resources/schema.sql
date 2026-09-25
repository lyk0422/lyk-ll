-- 尾矿库防汛服务建表脚本（H2 MySQL 兼容模式），本地运行与测试均由 spring.sql.init 自动执行。
-- 时间字段均为 Asia/Shanghai 本地时间；金额/库容单位见各列注释；NULL 含义见各列注释。

CREATE TABLE IF NOT EXISTS tailings_pond (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    name VARCHAR(128) NOT NULL COMMENT '尾矿库名称',
    max_flood_level DECIMAL(10,3) NOT NULL COMMENT '允许最高调洪水位(米)',
    downstream_safe_discharge DECIMAL(12,3) NOT NULL COMMENT '下游安全泄量(立方米/秒)',
    dry_beach_min DECIMAL(10,3) NOT NULL COMMENT '干滩长度安全下限(米)',
    phreatic_line_min DECIMAL(10,3) NOT NULL COMMENT '浸润线埋深安全下限(米)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
);
COMMENT ON TABLE tailings_pond IS '尾矿库基础信息';

CREATE TABLE IF NOT EXISTS storage_curve (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    pond_id BIGINT NOT NULL COMMENT '尾矿库ID',
    water_level DECIMAL(10,3) NOT NULL COMMENT '库水位(米)',
    capacity DECIMAL(18,3) NOT NULL COMMENT '该水位对应总库容(立方米)',
    CONSTRAINT uk_storage_curve UNIQUE (pond_id, water_level)
);
COMMENT ON TABLE storage_curve IS '库容曲线(合成数据):水位-库容离散点,查询时线性插值';

CREATE TABLE IF NOT EXISTS reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    pond_id BIGINT NOT NULL COMMENT '尾矿库ID',
    point_code VARCHAR(64) NOT NULL COMMENT '测点编号',
    reading_type VARCHAR(16) NOT NULL COMMENT '读数类型:RAINFALL降雨(毫米)/WATER_LEVEL水位(米)/DRY_BEACH干滩长度(米)/PHREATIC_LINE浸润线埋深(米)',
    sample_time TIMESTAMP NOT NULL COMMENT '采样时刻,按测点+类型+时刻去重,乱序到达按时刻归位',
    reading_value DECIMAL(18,3) NOT NULL COMMENT '读数值,单位随读数类型',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间',
    CONSTRAINT uk_reading UNIQUE (pond_id, point_code, reading_type, sample_time)
);
COMMENT ON TABLE reading IS '监测读数(降雨/水位等),按测点+采样时刻去重';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    pond_id BIGINT NOT NULL COMMENT '尾矿库ID',
    point_code VARCHAR(64) NOT NULL COMMENT '测点编号',
    reading_type VARCHAR(16) NOT NULL COMMENT '读数类型,同reading.reading_type',
    sample_time TIMESTAMP NOT NULL COMMENT '采样时刻',
    reading_value DECIMAL(18,3) NOT NULL COMMENT '读数值,单位随读数类型',
    detail VARCHAR(512) NOT NULL COMMENT '迟到原因说明(归档终态后到达)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间'
);
COMMENT ON TABLE late_event IS '归档后到达的迟到读数事件,迟到读数只记事件不入reading表';

CREATE TABLE IF NOT EXISTS rainfall_warning (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    pond_id BIGINT NOT NULL COMMENT '尾矿库ID',
    rainfall_mm DECIMAL(10,3) NOT NULL COMMENT '预警降雨量(毫米)',
    warn_time TIMESTAMP NOT NULL COMMENT '预警时刻',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间',
    CONSTRAINT uk_warning_request UNIQUE (request_id)
);
COMMENT ON TABLE rainfall_warning IS '降雨预警,调洪流程起点';

CREATE TABLE IF NOT EXISTS danger_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    pond_id BIGINT NOT NULL COMMENT '尾矿库ID',
    description VARCHAR(512) NOT NULL COMMENT '险情报送内容',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '报送时间',
    CONSTRAINT uk_danger_request UNIQUE (request_id)
);
COMMENT ON TABLE danger_report IS '险情报送,与泄流方案共用同一处置链';

CREATE TABLE IF NOT EXISTS flood_calc (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID,同键同参返回首次结果,同键异参返回409',
    pond_id BIGINT NOT NULL COMMENT '尾矿库ID',
    current_level DECIMAL(10,3) NOT NULL COMMENT '当前库水位(米)',
    design_flood_volume DECIMAL(18,3) NOT NULL COMMENT '设计洪水量(立方米)',
    available_capacity DECIMAL(18,3) NOT NULL COMMENT '按库容曲线反查的可用调洪库容(立方米)',
    dry_beach_length DECIMAL(10,3) NOT NULL COMMENT '干滩长度(米)',
    phreatic_depth DECIMAL(10,3) NOT NULL COMMENT '浸润线埋深(米)',
    capacity_verdict VARCHAR(16) NOT NULL COMMENT '库容口径判定:SAFE安全/CRITICAL临界/MUST_DISCHARGE须泄流',
    safety_verdict VARCHAR(16) NOT NULL COMMENT '安全口径判定:SAFE安全/CRITICAL临界/DANGER险',
    verdict VARCHAR(16) NOT NULL COMMENT '综合判定(两口径取最重):SAFE/CRITICAL/MUST_DISCHARGE/DANGER',
    snapshot CLOB NOT NULL COMMENT '演算输入快照与判定口径(JSON),可复算',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '演算时间',
    CONSTRAINT uk_calc_request UNIQUE (request_id)
);
COMMENT ON TABLE flood_calc IS '调洪演算结果及可复算快照';

CREATE TABLE IF NOT EXISTS discharge_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    calc_id BIGINT NOT NULL COMMENT '关联调洪演算ID',
    pond_id BIGINT NOT NULL COMMENT '尾矿库ID',
    orifices CLOB NOT NULL COMMENT '孔口组合JSON:[{name,discharge}],discharge单位立方米/秒',
    total_discharge DECIMAL(12,3) NOT NULL COMMENT '总泄量(立方米/秒),不得超过下游安全泄量,恰等合法',
    status VARCHAR(16) NOT NULL COMMENT 'EFFECTIVE生效/COMPLETED已完结(归档后)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT uk_plan_request UNIQUE (request_id)
);
COMMENT ON TABLE discharge_plan IS '泄流方案,同一尾矿库仅允许一个生效方案';

CREATE TABLE IF NOT EXISTS pond_active_plan (
    pond_id BIGINT PRIMARY KEY COMMENT '尾矿库ID,主键保证唯一生效方案',
    plan_id BIGINT NOT NULL COMMENT '生效中的泄流方案ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间'
);
COMMENT ON TABLE pond_active_plan IS '处置链守卫:同一尾矿库同一时刻仅允许一个生效泄流方案';

CREATE TABLE IF NOT EXISTS discharge_command (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    plan_id BIGINT NOT NULL COMMENT '泄流方案ID,一个方案仅下达一次指令',
    pond_id BIGINT NOT NULL COMMENT '尾矿库ID',
    status VARCHAR(16) NOT NULL COMMENT 'ISSUED已下达/ACKED已签收/EXECUTING执行中/RECEIPTED已回执/ESCALATED超时已升级',
    issued_at TIMESTAMP NOT NULL COMMENT '下达时刻',
    ack_deadline TIMESTAMP NOT NULL COMMENT '签收时限(下达后15分钟),恰满15分钟未签收自动升级值班领导',
    acked_at TIMESTAMP NULL COMMENT '签收时刻,NULL表示未签收',
    executed_at TIMESTAMP NULL COMMENT '执行时刻,NULL表示未执行',
    receipted_at TIMESTAMP NULL COMMENT '回执时刻,NULL表示未回执',
    actual_orifices CLOB NULL COMMENT '回执实际孔口组合JSON,NULL表示未回执',
    actual_discharge DECIMAL(12,3) NULL COMMENT '回执实际泄量(立方米/秒),NULL表示未回执',
    deviation_pct DECIMAL(8,4) NULL COMMENT '回执与方案泄量偏差百分比,恰10%视为一致,NULL表示未回执',
    deviation_reason VARCHAR(512) NULL COMMENT '偏差超10%时必填的原因说明,否则为NULL',
    escalated TINYINT(1) NOT NULL DEFAULT 0 COMMENT '签收超时是否已升级值班领导:0否,1是',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT uk_command_request UNIQUE (request_id),
    CONSTRAINT uk_command_plan UNIQUE (plan_id)
);
COMMENT ON TABLE discharge_command IS '泄流指令链:下达-签收-执行-回执';

CREATE TABLE IF NOT EXISTS pond_executing_plan (
    pond_id BIGINT PRIMARY KEY COMMENT '尾矿库ID,主键保证同时刻唯一执行中方案',
    plan_id BIGINT NOT NULL COMMENT '执行中的泄流方案ID',
    command_id BIGINT NOT NULL COMMENT '执行中的泄流指令ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间'
);
COMMENT ON TABLE pond_executing_plan IS '执行守卫:同一尾矿库同一时刻仅允许一个执行中泄流方案';

CREATE TABLE IF NOT EXISTS review_archive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    pond_id BIGINT NOT NULL COMMENT '尾矿库ID,唯一约束保证归档终态,重复归档返回幂等成功',
    calc_id BIGINT NULL COMMENT '关联调洪演算ID',
    plan_id BIGINT NULL COMMENT '关联泄流方案ID',
    command_id BIGINT NULL COMMENT '关联泄流指令ID',
    status VARCHAR(16) NOT NULL DEFAULT 'ARCHIVED' COMMENT 'ARCHIVED已归档(终态)',
    archived_at TIMESTAMP NOT NULL COMMENT '归档时刻',
    CONSTRAINT uk_archive_request UNIQUE (request_id),
    CONSTRAINT uk_archive_pond UNIQUE (pond_id)
);
COMMENT ON TABLE review_archive IS '复盘归档,归档为终态';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求ID',
    fingerprint VARCHAR(128) NOT NULL COMMENT 'requestId+全部业务字段的SHA-256指纹',
    response_body CLOB NOT NULL COMMENT '首次响应JSON快照,同键同参重放该结果',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次处理时间'
);
COMMENT ON TABLE idempotency_record IS '幂等记录:同键同参返回首次结果,同键异参返回409';
