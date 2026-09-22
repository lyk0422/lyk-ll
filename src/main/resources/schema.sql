-- 多传感器时间同步与数据配对服务建表脚本（H2 MySQL 兼容模式）
-- 所有时戳字段均为纪元毫秒（UTC），单位毫秒；NULL 表示该事件/记录不涉及该属性。

CREATE TABLE IF NOT EXISTS drift_correction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sensor_type VARCHAR(16) NOT NULL COMMENT '传感器类型: LIDAR/RADAR/CAMERA/IMU',
    version INT NOT NULL COMMENT '漂移校正参数版本, 同一传感器内单调递增',
    offset_ms BIGINT NOT NULL COMMENT '时钟漂移校正偏移量, 单位毫秒; 校正时戳 = 原始时戳 + 偏移量',
    effective_from BIGINT NOT NULL COMMENT '生效时刻(纪元毫秒); 仅作用于原始时戳不小于该值且尚未提交的消息',
    created_at BIGINT NOT NULL COMMENT '记录创建时刻(纪元毫秒)',
    CONSTRAINT uk_drift_sensor_version UNIQUE (sensor_type, version)
);

CREATE TABLE IF NOT EXISTS sensor_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sensor_type VARCHAR(16) NOT NULL COMMENT '传感器类型: LIDAR/RADAR/CAMERA/IMU',
    message_id VARCHAR(64) NOT NULL COMMENT '传感器消息ID, 同一传感器内唯一',
    raw_ts BIGINT NOT NULL COMMENT '原始时戳(纪元毫秒, 传感器时钟)',
    corrected_ts BIGINT NOT NULL COMMENT '校正时戳(纪元毫秒) = 原始时戳 + 漂移偏移量',
    drift_version INT NOT NULL COMMENT '校正所用漂移参数版本, 0 表示无校正参数(偏移为0)',
    status VARCHAR(16) NOT NULL COMMENT '消息状态: ACCEPTED-已入缓冲 / DISCARDED-已丢弃',
    discard_reason VARCHAR(40) NULL COMMENT '丢弃原因码, 仅 DISCARDED 时有值, 否则为 NULL',
    created_at BIGINT NOT NULL COMMENT '记录创建时刻(纪元毫秒)',
    CONSTRAINT uk_msg_sensor_msgid UNIQUE (sensor_type, message_id)
);

CREATE TABLE IF NOT EXISTS fusion_frame (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_ts BIGINT NOT NULL COMMENT '目标融合时刻(纪元毫秒)',
    status VARCHAR(16) NOT NULL COMMENT '帧状态: COMMITTED-已提交, 提交后不可变',
    created_at BIGINT NOT NULL COMMENT '记录创建时刻(纪元毫秒)',
    CONSTRAINT uk_frame_target UNIQUE (target_ts)
);

CREATE TABLE IF NOT EXISTS fusion_frame_input (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    frame_id BIGINT NOT NULL COMMENT '所属融合帧ID, 关联 fusion_frame.id',
    sensor_type VARCHAR(16) NOT NULL COMMENT '传感器类型: LIDAR/RADAR/CAMERA/IMU',
    message_id VARCHAR(160) NOT NULL COMMENT '输入消息ID; 插值时为前后两条消息ID以 + 连接',
    raw_ts BIGINT NOT NULL COMMENT '原始时戳(纪元毫秒); 插值时为目标融合时刻',
    corrected_ts BIGINT NOT NULL COMMENT '校正时戳(纪元毫秒); 插值时为目标融合时刻',
    delta_ms BIGINT NOT NULL COMMENT '校正时戳与目标融合时刻之差(毫秒), 插值时为 0',
    interpolated BOOLEAN NOT NULL COMMENT '是否插值输入: TRUE-由前后样本插值, FALSE-直接采样',
    CONSTRAINT fk_input_frame FOREIGN KEY (frame_id) REFERENCES fusion_frame (id)
);

CREATE TABLE IF NOT EXISTS sync_event (
    seq BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '全局事件序号, 单调递增, 重放按此排序',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型: MESSAGE_ACCEPTED/MESSAGE_DUPLICATE/MESSAGE_DISCARDED/DRIFT_UPDATED/FRAME_COMMITTED',
    sensor_type VARCHAR(16) NULL COMMENT '相关传感器类型, 无则为 NULL',
    message_id VARCHAR(160) NULL COMMENT '相关消息ID, 无则为 NULL',
    target_ts BIGINT NULL COMMENT '相关目标融合时刻(纪元毫秒), 无则为 NULL',
    raw_ts BIGINT NULL COMMENT '消息原始时戳(纪元毫秒), 无则为 NULL',
    drift_version INT NULL COMMENT '漂移参数版本, 无则为 NULL',
    offset_ms BIGINT NULL COMMENT '漂移偏移量(毫秒), 无则为 NULL',
    effective_from BIGINT NULL COMMENT '漂移参数生效时刻(纪元毫秒), 无则为 NULL',
    reason VARCHAR(40) NULL COMMENT '丢弃/失败原因码, 无则为 NULL',
    created_at BIGINT NOT NULL COMMENT '事件记录时刻(纪元毫秒)'
);
