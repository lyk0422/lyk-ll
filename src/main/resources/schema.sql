-- 风电场限电出力分配与执行回报服务建表脚本（H2 MySQL 兼容模式）
-- 所有时间均为本地时间（Asia/Shanghai），功率单位为 kW。

CREATE TABLE IF NOT EXISTS farm (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL COMMENT '风场编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '风场名称',
    CONSTRAINT uk_farm_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS turbine (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    farm_id BIGINT NOT NULL COMMENT '所属风场 id',
    code VARCHAR(64) NOT NULL COMMENT '机组编码，风场内唯一',
    capacity_kw INT NOT NULL COMMENT '额定容量，单位 kW',
    available_kw INT NOT NULL COMMENT '最新可用出力，单位 kW，由执行回报刷新',
    CONSTRAINT uk_turbine_farm_code UNIQUE (farm_id, code)
);

CREATE TABLE IF NOT EXISTS curtailment_command (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    farm_id BIGINT NOT NULL COMMENT '目标风场 id',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键，调用方生成',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    target_kw INT NOT NULL COMMENT '限电后全场目标出力，单位 kW',
    status VARCHAR(16) NOT NULL COMMENT '状态：EXECUTING 执行中 / SUPERSEDED 已被新指令作废 / SEALED 日结封存（终态）',
    active_farm_id BIGINT NULL COMMENT '执行中占位键：仅 EXECUTING 时等于 farm_id，保证同风场同时刻仅一条执行中指令',
    issued_at TIMESTAMP NOT NULL COMMENT '指令下发时刻，超时判定基准',
    sealed_at TIMESTAMP NULL COMMENT '日结封存时刻，未封存为 NULL',
    CONSTRAINT uk_command_request UNIQUE (request_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_command_active ON curtailment_command (active_farm_id);

CREATE TABLE IF NOT EXISTS command_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_id BIGINT NOT NULL COMMENT '所属限电指令 id',
    round INT NOT NULL COMMENT '分摊轮次：1 为首次分摊，超时重分摊递增',
    turbine_id BIGINT NOT NULL COMMENT '机组 id',
    available_kw INT NOT NULL COMMENT '分摊时点的可用出力，单位 kW',
    min_kw INT NOT NULL COMMENT '单机最低技术出力（额定容量 15% 向下取整），单位 kW',
    headroom_kw INT NOT NULL COMMENT '可降空间 = available_kw - min_kw，单位 kW',
    target_kw INT NOT NULL COMMENT '分摊后该机组目标出力，单位 kW',
    CONSTRAINT uk_allocation_round UNIQUE (command_id, round, turbine_id)
);

CREATE TABLE IF NOT EXISTS power_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_id BIGINT NOT NULL COMMENT '所属限电指令 id',
    farm_id BIGINT NOT NULL COMMENT '风场 id（冗余便于查询）',
    turbine_id BIGINT NOT NULL COMMENT '机组 id',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键，调用方生成',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    report_time TIMESTAMP NOT NULL COMMENT '出力读数对应的采集时刻，乱序回报按此归位',
    actual_kw INT NOT NULL COMMENT '实际出力读数，单位 kW',
    late BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否封存/作废后的迟到回报：TRUE 仅记录事件，不参与分摊',
    received_at TIMESTAMP NOT NULL COMMENT '服务端接收时刻',
    CONSTRAINT uk_report_request UNIQUE (request_id),
    CONSTRAINT uk_report_point UNIQUE (command_id, turbine_id, report_time)
);

CREATE TABLE IF NOT EXISTS alarm_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_id BIGINT NOT NULL COMMENT '所属限电指令 id',
    turbine_id BIGINT NULL COMMENT '关联机组 id，指令级事件为 NULL',
    type VARCHAR(32) NOT NULL COMMENT '事件类型：CURTAIL_TIMEOUT 超时未达目标 / LATE_REPORT 封存后迟到回报',
    message VARCHAR(512) NOT NULL COMMENT '事件描述',
    created_at TIMESTAMP NOT NULL COMMENT '事件生成时刻'
);
