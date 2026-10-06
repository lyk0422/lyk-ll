-- 隧道照明调光 schema（H2 MySQL 兼容模式，时间均为 Asia/Shanghai 本地时间，NULL 表示从未发生）

CREATE TABLE circuit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    code VARCHAR(64) NOT NULL COMMENT '回路编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '回路名称',
    current_level INT NOT NULL COMMENT '当前档位，0=关闭至5=全亮',
    initial_level INT NOT NULL COMMENT '初始化档位，用于日结能耗复算的基准',
    locked TINYINT(1) NOT NULL DEFAULT 0 COMMENT '事故锁定标记，1=锁定中，普通调光请求不得覆盖',
    last_adjusted_at DATETIME NULL COMMENT '最近一次调光完成时间，NULL=从未调整；稳定10分钟后方可再调',
    transition_until DATETIME NULL COMMENT '过渡曲线截止时间，NULL=不在过渡期；过渡期间禁止下调',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    CONSTRAINT uk_circuit_code UNIQUE (code)
) COMMENT='照明回路';

CREATE TABLE dimming_command (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    circuit_code VARCHAR(64) NOT NULL COMMENT '回路编码',
    source VARCHAR(16) NOT NULL COMMENT '触发来源：MANUAL/BRIGHTNESS/TRAFFIC/ACCIDENT',
    from_level INT NOT NULL COMMENT '调光前档位',
    to_level INT NOT NULL COMMENT '目标档位',
    status VARCHAR(16) NOT NULL COMMENT '状态：EXECUTING/COMPLETED',
    active_circuit_code VARCHAR(64) NULL COMMENT '执行中占位=回路编码，完成置NULL；唯一约束保证同一回路只允许一条执行中指令',
    created_at DATETIME NOT NULL COMMENT '指令创建时间',
    completed_at DATETIME NULL COMMENT '完成时间，NULL=执行中',
    CONSTRAINT uk_command_active UNIQUE (active_circuit_code)
) COMMENT='调光指令';

CREATE TABLE dimming_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    circuit_code VARCHAR(64) NOT NULL COMMENT '回路编码',
    command_id BIGINT NOT NULL COMMENT '关联调光指令',
    from_level INT NOT NULL COMMENT '调光前档位',
    to_level INT NOT NULL COMMENT '调光后档位',
    source VARCHAR(16) NOT NULL COMMENT '触发来源：MANUAL/BRIGHTNESS/TRAFFIC/ACCIDENT',
    created_at DATETIME NOT NULL COMMENT '调光发生时间',
    INDEX idx_record_circuit_time (circuit_code, created_at)
) COMMENT='调光留痕，日结能耗按此复算';

CREATE TABLE accident_lock_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    circuit_code VARCHAR(64) NOT NULL COMMENT '回路编码',
    action VARCHAR(8) NOT NULL COMMENT '动作：LOCK=锁定/UNLOCK=解除',
    alarm_id VARCHAR(64) NULL COMMENT '事故告警编号，解除时可为NULL',
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    detail VARCHAR(256) NULL COMMENT '备注，如现场确认人',
    created_at DATETIME NOT NULL COMMENT '留痕时间，与锁定/解除状态变更同事务写入'
) COMMENT='事故锁定/解除留痕';

CREATE TABLE traffic_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    circuit_code VARCHAR(64) NOT NULL COMMENT '回路编码',
    brightness DOUBLE NOT NULL COMMENT '洞外亮度，单位 lux',
    traffic_flow INT NOT NULL COMMENT '车流量，单位 辆/采样周期',
    recorded_at DATETIME NOT NULL COMMENT '检测时间',
    INDEX idx_sample_circuit_time (circuit_code, recorded_at)
) COMMENT='合成检测数据采样，车流量按5分钟滚动更新';

CREATE TABLE idempotency_record (
    request_id VARCHAR(128) PRIMARY KEY COMMENT '幂等请求标识，全局唯一',
    endpoint VARCHAR(128) NOT NULL COMMENT '接口路径',
    fingerprint VARCHAR(64) NOT NULL COMMENT '指纹=SHA-256(requestId+全部业务字段)，同键异参返回409',
    http_status INT NULL COMMENT '首次执行结果的HTTP状态，NULL=仍在执行中',
    response_body CLOB NULL COMMENT '首次执行结果报文，同键同参原样返回',
    created_at DATETIME NOT NULL COMMENT '首次接收时间'
) COMMENT='写操作幂等记录';
