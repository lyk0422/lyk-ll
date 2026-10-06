-- 隧道照明调光工程建表脚本（H2 MODE=MySQL 兼容语法）
-- 时间字段统一为 UTC 时间戳；档位为整数，0 为熄灭，数值越大越亮。

CREATE TABLE IF NOT EXISTS dim_level (
    level INT NOT NULL PRIMARY KEY COMMENT '档位，0 为熄灭，数值越大越亮',
    watts INT NOT NULL COMMENT '该档位回路功率，单位瓦(W)'
) COMMENT='档位功率字典';

CREATE TABLE IF NOT EXISTS circuit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL COMMENT '回路编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '回路名称',
    current_level INT NOT NULL COMMENT '当前档位',
    initial_level INT NOT NULL COMMENT '初始化档位，用于日结能耗复算的起点',
    base_level INT NOT NULL COMMENT '保底亮度档位，车流量为 0 时降至该档',
    max_level INT NOT NULL COMMENT '最高档位，事故模式锁定在该档',
    brightness_threshold INT NOT NULL COMMENT '洞外亮度合成阈值，单位 lux；低于则升档，恰等不触发',
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '状态：NORMAL 正常 / ACCIDENT 事故锁定',
    locked_level INT NULL COMMENT '事故锁定档位，仅事故状态有效，否则为 NULL',
    last_adjusted_at TIMESTAMP NULL COMMENT '最近一次调光时间(UTC)，NULL 表示从未调光',
    transition_until TIMESTAMP NULL COMMENT '过渡曲线截止时间(UTC)，此前禁止下调，NULL 表示无过渡',
    UNIQUE KEY uk_circuit_code (code)
) COMMENT='照明回路';

CREATE TABLE IF NOT EXISTS write_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等键，由调用方生成',
    fingerprint VARCHAR(64) NOT NULL COMMENT '指纹，覆盖 requestId 与全部业务字段的 SHA-256',
    result_json VARCHAR(1024) NOT NULL COMMENT '首次执行结果快照，同键同参直接返回',
    created_at TIMESTAMP NOT NULL COMMENT '首次受理时间(UTC)',
    UNIQUE KEY uk_write_request_id (request_id)
) COMMENT='写操作幂等登记表';

CREATE TABLE IF NOT EXISTS dimming_command (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等键，对应 write_request.request_id',
    circuit_id BIGINT NOT NULL COMMENT '回路主键',
    source VARCHAR(16) NOT NULL COMMENT '触发来源：MANUAL 人工 / TRAFFIC 车流量 / BRIGHTNESS 洞外亮度 / ACCIDENT 事故',
    target_level INT NOT NULL COMMENT '目标档位',
    from_level INT NULL COMMENT '执行前档位，未执行成功为 NULL',
    status VARCHAR(16) NOT NULL COMMENT '状态：EXECUTING 执行中 / EXECUTED 已执行 / REJECTED 已拒绝',
    executing TINYINT NULL COMMENT '执行中标记：1 执行中，NULL 已结束；与 circuit_id 组成唯一键保证同一回路同时刻仅一条执行中指令',
    message VARCHAR(255) NULL COMMENT '结果说明',
    created_at TIMESTAMP NOT NULL COMMENT '指令创建时间(UTC)',
    UNIQUE KEY uk_command_request (request_id),
    UNIQUE KEY uk_command_executing (circuit_id, executing)
) COMMENT='调光指令';

CREATE TABLE IF NOT EXISTS dimming_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    circuit_id BIGINT NOT NULL COMMENT '回路主键',
    command_id BIGINT NOT NULL COMMENT '调光指令主键',
    from_level INT NOT NULL COMMENT '调光前档位',
    to_level INT NOT NULL COMMENT '调光后档位',
    source VARCHAR(16) NOT NULL COMMENT '触发来源，同 dimming_command.source',
    created_at TIMESTAMP NOT NULL COMMENT '调光生效时间(UTC)'
) COMMENT='调光留痕';

CREATE TABLE IF NOT EXISTS accident_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    circuit_id BIGINT NOT NULL COMMENT '回路主键',
    action VARCHAR(8) NOT NULL COMMENT '动作：LOCK 锁定 / UNLOCK 解除',
    level INT NULL COMMENT '锁定档位，仅 LOCK 有效',
    operator VARCHAR(64) NOT NULL COMMENT '操作人；UNLOCK 时为现场确认人',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等键',
    created_at TIMESTAMP NOT NULL COMMENT '事件发生时间(UTC)'
) COMMENT='事故锁定/解除留痕';

CREATE TABLE IF NOT EXISTS traffic_sample (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    circuit_id BIGINT NOT NULL COMMENT '回路主键',
    flow INT NOT NULL COMMENT '车流量，单位 辆/5分钟；恰等为 0 时降至保底亮度',
    sampled_at TIMESTAMP NOT NULL COMMENT '采样时间(UTC)，按 5 分钟滚动更新',
    created_at TIMESTAMP NOT NULL COMMENT '入库时间(UTC)'
) COMMENT='车流量合成检测数据';

CREATE TABLE IF NOT EXISTS daily_settlement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    circuit_id BIGINT NOT NULL COMMENT '回路主键',
    settle_date DATE NOT NULL COMMENT '结算日期(UTC)',
    dim_count INT NOT NULL COMMENT '当日调光次数',
    energy_mwh BIGINT NOT NULL COMMENT '当日能耗，单位毫瓦时(mWh)，由调光留痕复算',
    created_at TIMESTAMP NOT NULL COMMENT '结算生成时间(UTC)',
    UNIQUE KEY uk_settlement (circuit_id, settle_date)
) COMMENT='日结快照';
