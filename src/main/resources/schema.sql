-- ROS 2 Control 风格指令仲裁与安全降级服务建表脚本
-- 兼容 H2 (MODE=MySQL) 与 MySQL；时间戳均为 UTC epoch 毫秒（BIGINT），NULL 表示无值/未发生。

CREATE TABLE IF NOT EXISTS resource (
    resource_id          VARCHAR(64)  NOT NULL COMMENT '资源（关节/执行器）标识',
    min_position         DOUBLE       NOT NULL COMMENT '目标位置下限',
    max_position         DOUBLE       NOT NULL COMMENT '目标位置上限',
    max_velocity         DOUBLE       NOT NULL COMMENT '速度上限（绝对值）',
    max_acceleration     DOUBLE       NOT NULL COMMENT '加速度上限（绝对值）',
    command_ttl_ms       BIGINT       NOT NULL COMMENT '指令有效期（毫秒），超时指令拒绝入队',
    safe_mode            VARCHAR(32)  NOT NULL COMMENT '安全降级模式：HOLD保持/ZERO_VELOCITY零速度/PRESET_POSITION预设位置',
    preset_position      DOUBLE       NULL     COMMENT '预设安全位置，仅 PRESET_POSITION 模式使用，其余为 NULL',
    holder_claim_id      BIGINT       NULL     COMMENT '当前有效持有租约ID，NULL 表示空闲',
    holder_controller_id VARCHAR(64)  NULL     COMMENT '当前有效持有者控制器ID，NULL 表示空闲',
    holder_priority      INT          NULL     COMMENT '当前持有者优先级，NULL 表示空闲',
    safe_applied         BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '本持有周期内是否已执行安全降级，保证降级动作只执行一次',
    last_command_seq     BIGINT       NULL     COMMENT '最近已接受指令序列号，NULL 表示尚未接受指令',
    last_command_ts      BIGINT       NULL     COMMENT '最近已接受指令时戳（epoch毫秒），用于单调性与时钟回拨校验',
    PRIMARY KEY (resource_id)
);

CREATE TABLE IF NOT EXISTS claim (
    claim_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '租约ID',
    request_id       VARCHAR(64)  NOT NULL COMMENT '幂等键：控制器请求ID，异参重放返回409',
    controller_id    VARCHAR(64)  NOT NULL COMMENT '控制器ID',
    resource_id      VARCHAR(64)  NOT NULL COMMENT '资源标识',
    priority         INT          NOT NULL COMMENT '申请优先级，数值越大越高',
    lease_ms         BIGINT       NOT NULL COMMENT '租约时长（毫秒）',
    state            VARCHAR(16)  NOT NULL COMMENT '租约状态：ACTIVE有效/RELEASED已释放/PREEMPTED被抢占/EXPIRED已到期/ESTOPPED被急停撤销',
    lease_expires_at BIGINT       NOT NULL COMMENT '租约到期时刻（epoch毫秒）',
    created_at       BIGINT       NOT NULL COMMENT '创建时刻（epoch毫秒）',
    updated_at       BIGINT       NOT NULL COMMENT '最近更新时刻（epoch毫秒）',
    PRIMARY KEY (claim_id),
    CONSTRAINT uk_claim_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS command (
    command_id    BIGINT      NOT NULL AUTO_INCREMENT COMMENT '指令ID',
    resource_id   VARCHAR(64) NOT NULL COMMENT '资源标识',
    controller_id VARCHAR(64) NOT NULL COMMENT '提交控制器ID',
    seq           BIGINT      NOT NULL COMMENT '指令序列号，按资源+控制器唯一',
    ts            BIGINT      NOT NULL COMMENT '指令时戳（epoch毫秒）',
    position      DOUBLE      NOT NULL COMMENT '目标位置',
    velocity      DOUBLE      NOT NULL COMMENT '目标速度',
    acceleration  DOUBLE      NOT NULL COMMENT '目标加速度',
    status        VARCHAR(16) NOT NULL COMMENT '指令状态：QUEUED待执行/DROPPED已丢弃（急停或降级清空）',
    created_at    BIGINT      NOT NULL COMMENT '入库时刻（epoch毫秒）',
    PRIMARY KEY (command_id),
    CONSTRAINT uk_command_seq UNIQUE (resource_id, controller_id, seq)
);

CREATE TABLE IF NOT EXISTS estop_state (
    id         TINYINT  NOT NULL COMMENT '固定单行ID，恒为1',
    active     BOOLEAN  NOT NULL COMMENT '紧急停止是否生效',
    updated_at BIGINT   NOT NULL COMMENT '最近变更时刻（epoch毫秒）',
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS safe_state_event (
    event_id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '降级事件ID',
    resource_id       VARCHAR(64) NOT NULL COMMENT '资源标识',
    claim_id          BIGINT      NOT NULL COMMENT '触发降级的租约ID，与资源联合唯一保证同一租约只降级一次',
    trigger_reason    VARCHAR(32) NOT NULL COMMENT '触发原因：LEASE_EXPIRED租约到期/COMMAND_TIMEOUT指令超时/CONTROLLER_LOST控制器失联',
    applied_position  DOUBLE      NULL     COMMENT '降级后目标位置，HOLD/ZERO_VELOCITY 模式下为保持位置，NULL 表示无历史位置',
    applied_velocity  DOUBLE      NULL     COMMENT '降级后目标速度，ZERO_VELOCITY 为0，其余为 NULL',
    applied_at        BIGINT      NOT NULL COMMENT '降级执行时刻（epoch毫秒）',
    PRIMARY KEY (event_id),
    CONSTRAINT uk_safe_event UNIQUE (resource_id, claim_id)
);
