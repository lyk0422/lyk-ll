-- 多传感器时间同步：H2（MODE=MySQL）自动建表，数据仅存活于 JVM 生命周期内。

-- 传感器原始消息事件表（重放与重建的事实来源）
CREATE TABLE IF NOT EXISTS sensor_message (
    sensor      VARCHAR(16)  NOT NULL COMMENT '传感器类型 LIDAR/RADAR/CAMERA/IMU',
    message_id  VARCHAR(64)  NOT NULL COMMENT '消息 ID，同一传感器内唯一',
    raw_ts      BIGINT       NOT NULL COMMENT '原始时钟时戳，epoch 纳秒',
    payload     CLOB         NULL     COMMENT '合成数据负载（JSON 文本，可空）',
    received_at BIGINT       NOT NULL COMMENT '到达服务的墙钟时刻，epoch 毫秒',
    CONSTRAINT pk_sensor_message PRIMARY KEY (sensor, message_id)
);
CREATE INDEX IF NOT EXISTS idx_sensor_message_ts ON sensor_message (sensor, raw_ts);

-- 时钟漂移校正参数版本表（热更新，带版本号与生效时刻）
CREATE TABLE IF NOT EXISTS clock_correction (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '版本记录 ID',
    sensor        VARCHAR(16) NOT NULL COMMENT '传感器类型',
    version       BIGINT      NOT NULL COMMENT '版本号，按传感器自增',
    slope         DOUBLE      NOT NULL COMMENT '漂移斜率，1.0 表示无频偏',
    offset_nanos  BIGINT      NOT NULL COMMENT '固定偏移，纳秒',
    effective_at  BIGINT      NOT NULL COMMENT '生效时刻，epoch 纳秒（事件时间）',
    created_at    BIGINT      NOT NULL COMMENT '热更新写入时刻，epoch 毫秒（重建历史帧时的截止线）',
    CONSTRAINT uk_correction_version UNIQUE (sensor, version)
);
CREATE INDEX IF NOT EXISTS idx_correction_effective ON clock_correction (sensor, effective_at);

-- 融合帧表（COMMITTED 帧不可变；确定性失败留 REJECTED 记录）
CREATE TABLE IF NOT EXISTS fusion_frame (
    frame_id          VARCHAR(48)  PRIMARY KEY COMMENT '帧 ID，与目标融合时刻一一对应 F-<targetTs>',
    target_ts         BIGINT       NOT NULL COMMENT '目标融合时刻，epoch 纳秒',
    status            VARCHAR(16)  NOT NULL COMMENT '帧状态 COMMITTED/REJECTED',
    committed_at      BIGINT       NOT NULL COMMENT '提交或判定时刻，epoch 毫秒',
    reason            VARCHAR(48)  NULL COMMENT 'REJECTED 时的稳定原因码',
    reason_detail     VARCHAR(512) NULL COMMENT '可定位到传感器的原因描述',
    correction_cutoff BIGINT       NULL COMMENT '构建时校正版本的 created_at 截止线，用于历史重建'
);

-- 融合帧输入证据表：消息 ID、原始/校正时戳、时间差、是否插值
CREATE TABLE IF NOT EXISTS frame_input (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '证据记录 ID',
    frame_id           VARCHAR(48) NOT NULL COMMENT '所属融合帧 ID',
    sensor             VARCHAR(16) NOT NULL COMMENT '传感器类型',
    message_id         VARCHAR(128) NOT NULL COMMENT '采用消息 ID；插值时为 前一ID~后一ID',
    raw_ts             BIGINT      NOT NULL COMMENT '原始时戳，epoch 纳秒（插值时为插值时刻反算值）',
    corrected_ts       BIGINT      NOT NULL COMMENT '漂移校正后时戳，epoch 纳秒',
    time_delta_nanos   BIGINT      NOT NULL COMMENT '校正时戳与目标融合时刻之差，纳秒',
    interpolated       BOOLEAN     NOT NULL COMMENT '是否由相邻消息线性插值得到',
    anchor_message_ids VARCHAR(256) NOT NULL COMMENT '锚点消息 ID 列表，逗号分隔',
    correction_version BIGINT      NOT NULL COMMENT '实际使用的校正参数版本号',
    payload            CLOB        NULL COMMENT '采用或插值后的负载（插值失败保留为 null）',
    CONSTRAINT uk_frame_input UNIQUE (frame_id, sensor)
);

-- 丢弃消息表：迟到、重复消息的可定位原因记录
CREATE TABLE IF NOT EXISTS dropped_message (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '丢弃记录 ID',
    sensor       VARCHAR(16) NOT NULL COMMENT '传感器类型',
    message_id   VARCHAR(64) NOT NULL COMMENT '消息 ID',
    raw_ts       BIGINT      NULL COMMENT '原始时戳，epoch 纳秒（重复消息可能无体时可空）',
    received_at  BIGINT      NOT NULL COMMENT '到达时刻，epoch 毫秒',
    reason       VARCHAR(32) NOT NULL COMMENT '稳定原因码 LATE/DUPLICATE',
    reason_detail VARCHAR(512) NOT NULL COMMENT '原因描述，含水位线/阈值等定位信息'
);
CREATE INDEX IF NOT EXISTS idx_dropped_sensor ON dropped_message (sensor, message_id);
