-- 群塔防碰撞服务建表脚本（H2 MySQL 兼容模式）。
-- 仅在嵌入式 H2 下由 Spring Boot 自动执行；外部 MySQL 部署默认不自动执行该脚本。
-- 所有时间列均以 UTC 存储，DATETIME(3) 毫秒精度，业务比较口径以列注释为准。

-- 塔机台账：额定吊重单位 kg，合成数据。
CREATE TABLE IF NOT EXISTS tower_crane (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    crane_code      VARCHAR(64)  NOT NULL COMMENT '塔机编号，业务唯一键',
    rated_load_kg   DECIMAL(10,2) NOT NULL COMMENT '额定载荷，单位 kg（千克）；申请吊重超过该值即拒绝',
    created_at      DATETIME(3)  NOT NULL COMMENT '创建时刻，UTC',
    CONSTRAINT uk_tower_crane_code UNIQUE (crane_code)
);

-- 重叠区台账：两台及以上塔机回转范围重叠的区域。
CREATE TABLE IF NOT EXISTS overlap_zone (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    zone_code       VARCHAR(64)  NOT NULL COMMENT '重叠区编号，业务唯一键',
    description     VARCHAR(255) NULL COMMENT '区域说明，可空',
    created_at      DATETIME(3)  NOT NULL COMMENT '创建时刻，UTC',
    CONSTRAINT uk_overlap_zone_code UNIQUE (zone_code)
);

-- 重叠区授权申请：requestId 幂等键；半开区间 [start_time, end_time) 端点相接不冲突。
-- status 口径：GRANTED 已授权且有效；RELEASED 已释放（完成/取消，历史时段仍可作为合法作业依据）。
-- result 口径：GRANTED 批准；REJECTED_OVERLOAD 超载拒绝（不占用区域）。
CREATE TABLE IF NOT EXISTS zone_authorization (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id      VARCHAR(64)  NOT NULL COMMENT '授权申请幂等键，由调用方保证全局唯一',
    crane_code      VARCHAR(64)  NOT NULL COMMENT '申请塔机编号',
    zone_code       VARCHAR(64)  NOT NULL COMMENT '申请进入的重叠区编号',
    start_time      DATETIME(3)  NOT NULL COMMENT '作业开始时刻，UTC，半开区间左端（含）',
    end_time        DATETIME(3)  NOT NULL COMMENT '作业结束时刻，UTC，半开区间右端（不含）；位姿判罚口径下该时刻点按授权内处理',
    load_kg         DECIMAL(10,2) NOT NULL COMMENT '本次吊重，单位 kg',
    fingerprint     CHAR(64)     NOT NULL COMMENT 'SHA-256 指纹，覆盖 requestId 与全部业务字段；同键异参判定冲突',
    result          VARCHAR(24)  NOT NULL COMMENT '首次结论：GRANTED 批准 / REJECTED_OVERLOAD 超载拒绝',
    status          VARCHAR(16)  NOT NULL COMMENT '授权状态：GRANTED 有效 / RELEASED 已释放',
    reject_reason   VARCHAR(255) NULL COMMENT '拒绝原因，仅超载拒绝时填写，可空',
    granted_at      DATETIME(3)  NULL COMMENT '授权下发时刻，UTC，可空（拒绝时为空）',
    released_at     DATETIME(3)  NULL COMMENT '释放时刻，UTC；为空表示尚未释放，授权状态以 status 列为准',
    release_type    VARCHAR(16)  NULL COMMENT '释放方式：COMPLETED 作业完成 / CANCELLED 取消，可空',
    created_at      DATETIME(3)  NOT NULL COMMENT '首次申请落库时刻，UTC',
    updated_at      DATETIME(3)  NOT NULL COMMENT '最近状态变更时刻，UTC',
    CONSTRAINT uk_auth_request_id UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_auth_zone_status ON zone_authorization (zone_code, status);
CREATE INDEX IF NOT EXISTS idx_auth_crane_zone ON zone_authorization (crane_code, zone_code);

-- 位姿读数：按“塔机 + 时间戳”去重；乱序到达按时间戳归位，不重复触发预警。
CREATE TABLE IF NOT EXISTS pose_reading (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    crane_code          VARCHAR(64)  NOT NULL COMMENT '上报塔机编号',
    ts                  DATETIME(3)  NOT NULL COMMENT '采样时刻，UTC，1 秒粒度；吊钩在该秒的位姿',
    zone_code           VARCHAR(64)  NULL COMMENT '吊钩当前所在重叠区编号；为空表示吊钩不在任何重叠区内',
    slewing_angle_deg   DECIMAL(9,4) NULL COMMENT '回转角度，单位度，可空（合成数据）',
    amplitude_m         DECIMAL(9,4) NULL COMMENT '幅度，单位米，可空（合成数据）',
    hook_height_m       DECIMAL(9,4) NULL COMMENT '吊钩高度，单位米，可空（合成数据）',
    received_at         DATETIME(3)  NOT NULL COMMENT '服务接收时刻，UTC',
    CONSTRAINT uk_pose_crane_ts UNIQUE (crane_code, ts)
);
CREATE INDEX IF NOT EXISTS idx_pose_crane_ts ON pose_reading (crane_code, ts);

-- 制动指令：同一越限事件只允许下发一次（warning_id 唯一），重复触发返回首次指令。
-- 先于 intrusion_warning 创建，以便后者外键引用本表。
CREATE TABLE IF NOT EXISTS brake_command (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    command_code    VARCHAR(64)  NOT NULL COMMENT '制动指令业务编号，唯一',
    warning_id      BIGINT       NOT NULL COMMENT '触发制动的越限预警主键，唯一约束保证幂等',
    crane_code      VARCHAR(64)  NOT NULL COMMENT '被制动塔机编号',
    zone_code       VARCHAR(64)  NOT NULL COMMENT '越限重叠区编号',
    issued_at       DATETIME(3)  NOT NULL COMMENT '制动下发时刻，UTC（持续 3 秒未退出后下发）',
    created_at      DATETIME(3)  NOT NULL COMMENT '落库时刻，UTC',
    CONSTRAINT uk_brake_code UNIQUE (command_code),
    CONSTRAINT uk_brake_warning UNIQUE (warning_id)
);

-- 越限预警：吊钩进入未授权重叠区即生成；事件不可删除，修正须写 event_correction 留痕。
-- status：ACTIVE 持续中；CLEARED 已退出（未制动）；BRAKED 已制动；CLOSED_AFTER_BRAKE 制动后退出。
CREATE TABLE IF NOT EXISTS intrusion_warning (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    warning_code    VARCHAR(64)  NOT NULL COMMENT '预警业务编号，唯一',
    crane_code      VARCHAR(64)  NOT NULL COMMENT '越限塔机编号',
    zone_code       VARCHAR(64)  NOT NULL COMMENT '被侵入的重叠区编号',
    start_ts        DATETIME(3)  NOT NULL COMMENT '首次越限采样时刻，UTC，乱序归位后可能前移',
    last_ts         DATETIME(3)  NOT NULL COMMENT '最近一次越限采样时刻，UTC',
    exit_ts         DATETIME(3)  NULL COMMENT '退出未授权区域采样时刻，UTC；为空表示尚未退出',
    status          VARCHAR(20)  NOT NULL COMMENT 'ACTIVE 持续中 / CLEARED 已退出 / BRAKED 已制动 / CLOSED_AFTER_BRAKE 制动后退出',
    brake_id        BIGINT       NULL COMMENT '关联制动指令主键，可空；每个事件至多一条',
    created_at      DATETIME(3)  NOT NULL COMMENT '预警生成时刻，UTC',
    updated_at      DATETIME(3)  NOT NULL COMMENT '最近状态变更时刻，UTC',
    CONSTRAINT uk_warning_code UNIQUE (warning_code),
    CONSTRAINT fk_warning_brake FOREIGN KEY (brake_id) REFERENCES brake_command (id)
);
CREATE INDEX IF NOT EXISTS idx_warning_crane_zone ON intrusion_warning (crane_code, zone_code, start_ts);

-- 事件修正留痕：预警/制动事件本身不可删除、不可直接改写，任何修正只追加审计记录。
CREATE TABLE IF NOT EXISTS event_correction (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    warning_id      BIGINT       NOT NULL COMMENT '被修正的预警主键',
    reason          VARCHAR(64)  NOT NULL COMMENT '修正原因代码',
    detail          VARCHAR(1000) NOT NULL COMMENT '修正说明，不回写原事件',
    created_at      DATETIME(3)  NOT NULL COMMENT '修正记录时刻，UTC',
    CONSTRAINT fk_correction_warning FOREIGN KEY (warning_id) REFERENCES intrusion_warning (id)
);
