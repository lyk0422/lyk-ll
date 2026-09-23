-- 标定参数版本表：每份标定（唯一键 + 版本）的内容、状态与适用范围
CREATE TABLE IF NOT EXISTS calibration_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    calibration_key VARCHAR(128) NOT NULL COMMENT '标定参数唯一键，如 front-camera-intrinsics',
    sensor_type VARCHAR(16) NOT NULL COMMENT '传感器类型：CAMERA/LIDAR/RADAR/IMU',
    version VARCHAR(64) NOT NULL COMMENT '该标定键下的版本号',
    content_digest VARCHAR(128) NOT NULL COMMENT '标定内容摘要（SHA-256 十六进制）',
    payload CLOB NOT NULL COMMENT '标定参数内容（JSON 原文）',
    status VARCHAR(16) NOT NULL COMMENT '状态：DRAFT 草稿/APPROVED 已批准/IN_CANARY 灰度中/EFFECTIVE 已生效/ROLLED_BACK 已回滚',
    effective_from TIMESTAMP NOT NULL COMMENT '生效起始时间（含，Asia/Shanghai 业务时区）',
    effective_to TIMESTAMP NULL COMMENT '生效结束时间（不含）；NULL 表示长期有效',
    vehicle_range VARCHAR(255) NOT NULL COMMENT '车辆范围：ALL 全部车辆，或 VEHICLES:v1,v2 显式集合',
    sensor_id VARCHAR(128) NOT NULL COMMENT '适用传感器标识；ALL 表示该键下通用',
    map_version_from INT NULL COMMENT '适用地图版本下界（含）；NULL 表示不设下界',
    map_version_to INT NULL COMMENT '适用地图版本上界（含）；NULL 表示不设上界',
    validation_error TEXT NULL COMMENT '最近一次校验失败原因；仅 DRAFT 且校验失败时非空',
    created_by VARCHAR(128) NOT NULL COMMENT '创建操作者',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间',
    approved_by VARCHAR(128) NULL COMMENT '审批操作者；NULL 未审批',
    approved_at TIMESTAMP NULL COMMENT '审批时间；NULL 未审批',
    activated_by VARCHAR(128) NULL COMMENT '全量生效操作者；NULL 未全量生效',
    activated_at TIMESTAMP NULL COMMENT '全量生效时间；NULL 未全量生效',
    rolled_back_by VARCHAR(128) NULL COMMENT '回滚操作者；NULL 未回滚',
    rolled_back_at TIMESTAMP NULL COMMENT '回滚时间；NULL 未回滚',
    canary_release_id BIGINT NULL COMMENT '将该版本置为灰度中的发布单 ID；NULL 表示非灰度版本',
    CONSTRAINT uq_cal_version UNIQUE (calibration_key, version)
);
CREATE INDEX IF NOT EXISTS idx_cal_lookup ON calibration_version (calibration_key, sensor_id, status);

-- 灰度发布单：一次显式车辆集合的批量切换
CREATE TABLE IF NOT EXISTS canary_release (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    calibration_key VARCHAR(128) NOT NULL COMMENT '灰度针对的标定键',
    new_version_id BIGINT NOT NULL COMMENT '灰度发布的新版本 ID',
    status VARCHAR(16) NOT NULL COMMENT '发布单状态：ROLLED_OUT 已发布/ROLLED_BACK 已回滚',
    operator VARCHAR(128) NOT NULL COMMENT '发布操作者',
    created_at TIMESTAMP NOT NULL COMMENT '发布时间（批量切换原子提交时刻）',
    rollback_operator VARCHAR(128) NULL COMMENT '回滚操作者；NULL 未回滚',
    rolled_back_at TIMESTAMP NULL COMMENT '回滚时间；NULL 未回滚',
    reason TEXT NULL COMMENT '回滚原因',
    CONSTRAINT fk_release_version FOREIGN KEY (new_version_id) REFERENCES calibration_version (id)
);
CREATE INDEX IF NOT EXISTS idx_release_key ON canary_release (calibration_key, status);

-- 灰度车辆明细：每辆车发布前解析到的版本，回滚时逐车恢复
CREATE TABLE IF NOT EXISTS canary_vehicle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id BIGINT NOT NULL COMMENT '所属灰度发布单 ID',
    vehicle_id VARCHAR(64) NOT NULL COMMENT '参与灰度的车辆标识',
    previous_version_id BIGINT NULL COMMENT '发布前该车解析到的版本 ID；NULL 表示此前无有效版本',
    CONSTRAINT uq_release_vehicle UNIQUE (release_id, vehicle_id),
    CONSTRAINT fk_vehicle_release FOREIGN KEY (release_id) REFERENCES canary_release (id),
    CONSTRAINT fk_vehicle_previous FOREIGN KEY (previous_version_id) REFERENCES calibration_version (id)
);
CREATE INDEX IF NOT EXISTS idx_vehicle_lookup ON canary_vehicle (vehicle_id, release_id);

-- 活跃灰度车辆占用表：仅保留 ROLLED_OUT 发布单的车辆行，回滚时删除。
-- 同一标定键下同一车辆至多被一个活跃灰度覆盖；主键由数据库兜底并发冲突。
CREATE TABLE IF NOT EXISTS active_canary_vehicle (
    calibration_key VARCHAR(128) NOT NULL COMMENT '灰度针对的标定键',
    vehicle_id VARCHAR(64) NOT NULL COMMENT '被灰度占用的车辆标识',
    release_id BIGINT NOT NULL COMMENT '占用该车辆的活跃发布单 ID',
    PRIMARY KEY (calibration_key, vehicle_id)
);

-- 解析历史：resolutionId 与当时命中的参数版本快照，后续发布不得改写
CREATE TABLE IF NOT EXISTS resolution_record (
    resolution_id CHAR(36) NOT NULL COMMENT '解析流水号（UUID），融合结果据此追溯',
    calibration_key VARCHAR(128) NOT NULL COMMENT '解析使用的标定键',
    sensor_id VARCHAR(128) NOT NULL COMMENT '解析使用的传感器标识',
    vehicle_id VARCHAR(64) NOT NULL COMMENT '解析车辆标识',
    map_version INT NULL COMMENT '解析时地图版本；NULL 表示不区分地图',
    resolved_time TIMESTAMP NOT NULL COMMENT '解析请求中的业务时间',
    resolved_at TIMESTAMP NOT NULL COMMENT '解析请求处理时刻',
    selected_version_id BIGINT NOT NULL COMMENT '当时命中版本 ID',
    selected_version VARCHAR(64) NOT NULL COMMENT '当时命中版本号（冗余快照，便于历史展示）',
    content_digest VARCHAR(128) NOT NULL COMMENT '当时命中内容摘要（冗余快照）',
    payload CLOB NOT NULL COMMENT '当时命中参数内容快照（JSON），历史不可变',
    canary_release_id BIGINT NULL COMMENT '当时是否经由某灰度单命中；NULL 为常规生效版本',
    PRIMARY KEY (resolution_id)
);
CREATE INDEX IF NOT EXISTS idx_resolution_version ON resolution_record (selected_version_id);
