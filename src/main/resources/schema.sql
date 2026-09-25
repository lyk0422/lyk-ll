-- 群塔防碰撞服务建表脚本（H2 MySQL 兼容模式，仅合成数据）
-- 时间列均为 UTC 时刻（TIMESTAMP，无时区），由应用以 Instant 写入。

CREATE TABLE IF NOT EXISTS tower_crane (
    id VARCHAR(64) PRIMARY KEY COMMENT '塔机编号',
    name VARCHAR(128) NOT NULL COMMENT '塔机名称',
    rated_load_tonnes DECIMAL(10,3) NOT NULL COMMENT '额定载荷，单位吨；吊重超过该值即拒绝授权'
) COMMENT='塔机';

CREATE TABLE IF NOT EXISTS overlap_zone (
    id VARCHAR(64) PRIMARY KEY COMMENT '重叠区编号',
    name VARCHAR(128) NOT NULL COMMENT '重叠区名称',
    center_x_m DOUBLE NOT NULL COMMENT '重叠区圆心 X 坐标，单位米（场地平面坐标系）',
    center_y_m DOUBLE NOT NULL COMMENT '重叠区圆心 Y 坐标，单位米',
    radius_m DOUBLE NOT NULL COMMENT '重叠区半径，单位米；吊钩距圆心不超过半径视为进入'
) COMMENT='塔机回转重叠区';

CREATE TABLE IF NOT EXISTS zone_authorization (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '授权记录主键',
    request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等请求编号，全局唯一',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的哈希，同键异参返回 409',
    crane_id VARCHAR(64) NOT NULL COMMENT '塔机编号',
    zone_id VARCHAR(64) NOT NULL COMMENT '重叠区编号',
    start_time_utc TIMESTAMP NOT NULL COMMENT '作业开始时刻（UTC，含），授权冲突按半开区间 [start, end) 判定',
    end_time_utc TIMESTAMP NOT NULL COMMENT '作业结束时刻（UTC）；冲突判定不含该端点，位姿恰在该时刻按授权内处理',
    load_tonnes DECIMAL(10,3) NOT NULL COMMENT '吊重，单位吨',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE=生效中，COMPLETED=作业完成已释放，CANCELLED=已取消释放',
    created_at_utc TIMESTAMP NOT NULL COMMENT '授权创建时刻（UTC）',
    released_at_utc TIMESTAMP NULL COMMENT '释放时刻（UTC）；NULL 表示仍生效。释放前时刻的位姿仍按授权内处理',
    CONSTRAINT uq_authorization_request UNIQUE (request_id)
) COMMENT='重叠区作业授权';

CREATE TABLE IF NOT EXISTS pose_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '位姿读数主键',
    crane_id VARCHAR(64) NOT NULL COMMENT '塔机编号',
    ts_utc TIMESTAMP NOT NULL COMMENT '读数采集时刻（UTC，1 秒粒度）；乱序到达按该时间戳归位',
    hook_x_m DOUBLE NOT NULL COMMENT '吊钩 X 坐标，单位米',
    hook_y_m DOUBLE NOT NULL COMMENT '吊钩 Y 坐标，单位米',
    received_at_utc TIMESTAMP NOT NULL COMMENT '服务端接收时刻（UTC）',
    CONSTRAINT uq_pose_crane_ts UNIQUE (crane_id, ts_utc)
) COMMENT='塔机位姿读数；按塔机+时间戳去重，重复读数不重复触发预警';

CREATE TABLE IF NOT EXISTS breach_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '越限事件主键',
    crane_id VARCHAR(64) NOT NULL COMMENT '塔机编号',
    zone_id VARCHAR(64) NOT NULL COMMENT '越限重叠区编号',
    first_detected_at_utc TIMESTAMP NOT NULL COMMENT '首次越限时刻（UTC，按读数时间戳归位后的最小值）',
    last_detected_at_utc TIMESTAMP NOT NULL COMMENT '最近一次越限时刻（UTC）',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN=预警中，BRAKED=已制动，RESOLVED=已退出区域',
    resolved_at_utc TIMESTAMP NULL COMMENT '退出区域时刻（UTC）；NULL 表示尚未退出',
    created_at_utc TIMESTAMP NOT NULL COMMENT '事件创建时刻（UTC）'
) COMMENT='越限预警事件；不可删除、不可修改，修正以新记录留痕';

CREATE TABLE IF NOT EXISTS brake_command (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '制动指令主键',
    breach_event_id BIGINT NOT NULL COMMENT '关联越限事件主键；唯一约束保证同一事件只下发一次',
    crane_id VARCHAR(64) NOT NULL COMMENT '塔机编号',
    zone_id VARCHAR(64) NOT NULL COMMENT '重叠区编号',
    issued_at_utc TIMESTAMP NOT NULL COMMENT '指令下发时刻（UTC，按触发制动的读数时间戳）',
    reason VARCHAR(256) NOT NULL COMMENT '下发原因（持续越限时长等）',
    CONSTRAINT uq_brake_event UNIQUE (breach_event_id)
) COMMENT='自动制动指令；同一越限事件幂等只下发一次';
