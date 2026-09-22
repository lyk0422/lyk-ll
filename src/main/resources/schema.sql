-- ROS 2 Control 风格仲裁服务建表脚本（H2 MODE=MySQL；时间均为 UTC epoch 毫秒，位置单位弧度）

-- 资源（关节/执行器）定义
CREATE TABLE IF NOT EXISTS t_resource (
    resource_name     VARCHAR(128) PRIMARY KEY COMMENT '资源名（关节或执行器标识）',
    min_position      DOUBLE NOT NULL COMMENT '位置下限（弧度）',
    max_position      DOUBLE NOT NULL COMMENT '位置上限（弧度）',
    max_velocity      DOUBLE NOT NULL COMMENT '最大允许速度绝对值（弧度/秒）',
    max_acceleration  DOUBLE NOT NULL COMMENT '最大允许加速度绝对值（弧度/秒^2）',
    safety_action     VARCHAR(32) NOT NULL COMMENT '失权/失联时安全动作 HOLD/ZERO_VELOCITY/PRESET_POSITION',
    preset_position   DOUBLE COMMENT 'PRESET_POSITION 预设位置（弧度），其余动作为空',
    last_position     DOUBLE COMMENT '最近应用目标位置（弧度），无指令为空',
    last_timestamp_ms BIGINT COMMENT '最近应用目标时戳（UTC epoch 毫秒），无指令为空'
);

-- 控制器心跳与失联标记
CREATE TABLE IF NOT EXISTS t_controller (
    controller_id     VARCHAR(128) PRIMARY KEY COMMENT '控制器标识',
    last_heartbeat_ms BIGINT NOT NULL COMMENT '最近心跳时间（UTC epoch 毫秒）',
    lost              BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已判定失联并完成降级（去重标记）',
    version           BIGINT NOT NULL DEFAULT 0 COMMENT '行版本号（乐观锁）'
);

-- 写权限租约历史：每次申请每个资源一行；撤销只更新状态不删行
CREATE TABLE IF NOT EXISTS t_lease (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    controller_id   VARCHAR(128) NOT NULL COMMENT '持有方控制器标识',
    resource_name   VARCHAR(128) NOT NULL COMMENT '被占有的资源名',
    request_id      VARCHAR(128) NOT NULL COMMENT '申请幂等键（控制器侧）',
    priority        INT NOT NULL COMMENT '优先级，数值越大优先级越高',
    acquired_at_ms  BIGINT NOT NULL COMMENT '授权时间（UTC epoch 毫秒）',
    expires_at_ms   BIGINT NOT NULL COMMENT '租约到期时间（UTC epoch 毫秒）',
    active          BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否当前有效持有者',
    revoked_reason  VARCHAR(32) COMMENT '失效原因 RELEASED/PREEMPTED/ESTOP/LEASE_EXPIRED/CONTROLLER_LOST，有效为空'
);

-- 同一次申请（控制器+幂等键+资源）唯一，支持同参重放幂等、异参重放冲突检测
CREATE UNIQUE INDEX IF NOT EXISTS uk_lease_controller_request_resource
    ON t_lease (controller_id, request_id, resource_name);
CREATE INDEX IF NOT EXISTS idx_lease_active ON t_lease (active, expires_at_ms);
CREATE INDEX IF NOT EXISTS idx_lease_controller ON t_lease (controller_id, active);

-- 当前有效占用：resource_name 主键在数据库层强制任一资源同一时刻只有一个持有者
CREATE TABLE IF NOT EXISTS t_active_claim (
    resource_name   VARCHAR(128) PRIMARY KEY COMMENT '被占有的资源名',
    lease_id        BIGINT NOT NULL COMMENT '对应 t_lease 主键',
    controller_id   VARCHAR(128) NOT NULL COMMENT '持有方控制器标识',
    request_id      VARCHAR(128) NOT NULL COMMENT '申请幂等键',
    priority        INT NOT NULL COMMENT '优先级',
    acquired_at_ms  BIGINT NOT NULL COMMENT '授权时间（UTC epoch 毫秒）',
    expires_at_ms   BIGINT NOT NULL COMMENT '租约到期时间（UTC epoch 毫秒）'
);

CREATE INDEX IF NOT EXISTS idx_active_claim_controller ON t_active_claim (controller_id);

-- 运动指令（非法/过期指令不写入）
CREATE TABLE IF NOT EXISTS t_command (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    controller_id       VARCHAR(128) NOT NULL COMMENT '发送控制器标识',
    resource_name       VARCHAR(128) NOT NULL COMMENT '目标资源名',
    request_id          VARCHAR(128) NOT NULL COMMENT '控制器侧幂等键',
    timestamp_ms        BIGINT NOT NULL COMMENT '指令时戳（UTC epoch 毫秒）',
    sequence            BIGINT NOT NULL COMMENT '控制器内单调递增序列号',
    target_position     DOUBLE NOT NULL COMMENT '目标位置（弧度）',
    target_velocity     DOUBLE NOT NULL COMMENT '目标速度（弧度/秒）',
    target_acceleration DOUBLE NOT NULL COMMENT '目标加速度（弧度/秒^2）',
    status              VARCHAR(16) NOT NULL COMMENT '状态 PENDING/EXECUTED/REJECTED/TIMED_OUT',
    enqueued_at_ms      BIGINT NOT NULL COMMENT '入队时间（UTC epoch 毫秒）',
    finished_at_ms      BIGINT COMMENT '终态时间（UTC epoch 毫秒），PENDING 为空',
    reject_reason       VARCHAR(255) COMMENT '拒绝原因，其余状态为空'
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_command_controller_request ON t_command (controller_id, request_id);
CREATE INDEX IF NOT EXISTS idx_command_resource ON t_command (resource_name, id);
CREATE INDEX IF NOT EXISTS idx_command_status ON t_command (status, enqueued_at_ms);

-- 安全降级事件：event_key 唯一保证重复检测不重复执行降级动作
CREATE TABLE IF NOT EXISTS t_safety_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    event_key       VARCHAR(255) NOT NULL COMMENT '幂等触发键（原因+资源+来源）',
    reason          VARCHAR(32) NOT NULL COMMENT '触发原因 LEASE_EXPIRED/CONTROLLER_LOST/COMMAND_TIMEOUT',
    resource_name   VARCHAR(128) NOT NULL COMMENT '涉及资源名',
    controller_id   VARCHAR(128) COMMENT '关联控制器，系统侧触发可为空',
    action          VARCHAR(32) NOT NULL COMMENT '实际执行安全动作 HOLD/ZERO_VELOCITY/PRESET_POSITION',
    target_position DOUBLE COMMENT 'PRESET_POSITION 目标位置（弧度），其余动作为空',
    event_ms        BIGINT NOT NULL COMMENT '事件时间（UTC epoch 毫秒）'
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_safety_event_key ON t_safety_event (event_key);

-- 急停全局状态（单行 id=1）
CREATE TABLE IF NOT EXISTS t_estop_state (
    id              BIGINT PRIMARY KEY COMMENT '固定主键 1',
    active          BOOLEAN NOT NULL COMMENT '是否急停生效中',
    triggered_at_ms BIGINT COMMENT '最近急停触发时间（UTC epoch 毫秒），未触发为空',
    recovered_at_ms BIGINT COMMENT '最近显式恢复时间（UTC epoch 毫秒），未恢复为空'
);

MERGE INTO t_estop_state (id, active, triggered_at_ms, recovered_at_ms) KEY (id)
VALUES (1, FALSE, NULL, NULL);
