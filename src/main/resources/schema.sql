-- 列车制动机试验与摘挂核对服务建表脚本(H2 MySQL 兼容模式 / MySQL 通用语法)
-- 所有时间列为服务器本地时区(Asia/Shanghai); 合成数据, 仅在 JVM 生命周期内保留。

-- 列车作业主表: 接入→摘挂→编组核对→制动试验→发车确认→销记
CREATE TABLE IF NOT EXISTS train_operation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '作业主键',
    track_no VARCHAR(32) NOT NULL COMMENT '股道号',
    train_no VARCHAR(32) NOT NULL COMMENT '车次',
    set_pressure_kpa INT NOT NULL COMMENT '制动管定压(kPa), 恰达定压视为恢复',
    status VARCHAR(24) NOT NULL COMMENT '作业状态: RECEIVED=作业中, DEPARTURE_CONFIRMED=已发车确认, CLOSED=已销记(终态)',
    consist_version INT NOT NULL DEFAULT 1 COMMENT '当前编组版本号, 摘挂后递增',
    recovery_pending BOOLEAN NOT NULL DEFAULT FALSE COMMENT '感度合格后是否等待风压恢复定压',
    recovery_ok BOOLEAN NOT NULL DEFAULT FALSE COMMENT '风压是否已恢复至定压(恰达定压为恢复)',
    active_track VARCHAR(32) DEFAULT NULL COMMENT '作业中占用的股道号, 销记后置空; NULL 表示不占股道',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间(服务器本地时区)',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间(服务器本地时区)',
    PRIMARY KEY (id)
);
-- 同一股道同时刻只允许一列作业中列车(H2 中唯一索引允许多个 NULL)
CREATE UNIQUE INDEX IF NOT EXISTS uk_operation_active_track ON train_operation (active_track);

-- 编组车辆快照: 每个编组版本保存完整车辆清单, 支持按版本复算
CREATE TABLE IF NOT EXISTS operation_car (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '车辆行主键',
    operation_id BIGINT NOT NULL COMMENT '所属作业ID',
    consist_version INT NOT NULL COMMENT '所属编组版本号',
    car_no VARCHAR(32) NOT NULL COMMENT '车辆号(合成数据)',
    length_coeff DECIMAL(6,2) NOT NULL COMMENT '换长',
    weight_tons DECIMAL(8,2) NOT NULL COMMENT '总重(吨)',
    brake_cutout BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否关门车(截断塞门关闭)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间(服务器本地时区)',
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_car_operation ON operation_car (operation_id, consist_version);

-- 编组核对记录: 与合成运统1 的辆数/换长/总重核对结果
CREATE TABLE IF NOT EXISTS consist_check (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '核对记录主键',
    operation_id BIGINT NOT NULL COMMENT '所属作业ID',
    consist_version INT NOT NULL COMMENT '核对的编组版本号',
    expected_cars INT NOT NULL COMMENT '运统1辆数',
    expected_length DECIMAL(8,2) NOT NULL COMMENT '运统1换长',
    expected_weight DECIMAL(10,2) NOT NULL COMMENT '运统1总重(吨)',
    actual_cars INT NOT NULL COMMENT '实际辆数',
    actual_length DECIMAL(8,2) NOT NULL COMMENT '实际换长',
    actual_weight DECIMAL(10,2) NOT NULL COMMENT '实际总重(吨)',
    matched BOOLEAN NOT NULL COMMENT '是否与运统1一致; 不一致拒绝发车',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '核对时间(服务器本地时区)',
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_check_operation ON consist_check (operation_id, consist_version);

-- 制动试验记录: 感度(减压50kPa)与安定(减压140kPa)
CREATE TABLE IF NOT EXISTS brake_test (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '试验记录主键',
    operation_id BIGINT NOT NULL COMMENT '所属作业ID',
    consist_version INT NOT NULL COMMENT '试验对应编组版本号',
    phase VARCHAR(16) NOT NULL COMMENT '试验项: SENSITIVITY=感度试验, STABILITY=安定试验',
    reduction_kpa INT NOT NULL COMMENT '减压量(kPa): 感度恰50合格, 安定恰140合格',
    all_cars_braked BOOLEAN DEFAULT NULL COMMENT '感度试验: 全列是否制动; NULL 表示不适用',
    emergency_occurred BOOLEAN DEFAULT NULL COMMENT '安定试验: 是否发生紧急制动; NULL 表示不适用',
    result VARCHAR(8) NOT NULL COMMENT '结论: PASS=合格, FAIL=不合格',
    valid BOOLEAN NOT NULL DEFAULT TRUE COMMENT '结论是否有效; 编组变更后在同一事务内作废',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '试验时间(服务器本地时区)',
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_test_operation ON brake_test (operation_id, consist_version);

-- 风压读数: 试验曲线复算的数据来源; 销记后迟到数据仅记迟到事件
CREATE TABLE IF NOT EXISTS pressure_reading (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '风压读数主键',
    operation_id BIGINT NOT NULL COMMENT '所属作业ID',
    pressure_kpa INT NOT NULL COMMENT '制动管风压(kPa)',
    occurred_at TIMESTAMP NOT NULL COMMENT '采集时间(上报方提供, 本地时区)',
    late BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否销记后迟到的数据(仅记迟到事件)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间(服务器本地时区)',
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_pressure_operation ON pressure_reading (operation_id);

-- 幂等受理记录: 指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL COMMENT '幂等键',
    endpoint VARCHAR(64) NOT NULL COMMENT '受理的业务端点',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹: 端点+requestId+全部业务字段的SHA-256',
    response_status INT DEFAULT NULL COMMENT '首次受理的HTTP状态码; NULL 表示处理中',
    response_body CLOB DEFAULT NULL COMMENT '首次受理的响应体JSON; NULL 表示处理中',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '受理时间(服务器本地时区)',
    PRIMARY KEY (request_id)
);
