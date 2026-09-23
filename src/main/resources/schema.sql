-- 标定参数版本表；H2 MySQL 兼容模式（MODE=MySQL）。
CREATE TABLE IF NOT EXISTS calibration_param (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    param_key          VARCHAR(128) NOT NULL COMMENT '全局唯一参数键，如 cam_front.intrinsic',
    kind               VARCHAR(32)  NOT NULL COMMENT '参数类别 INTRINSIC/EXTRINSIC/IMU_PARAMS',
    sensor_type        VARCHAR(32)  NOT NULL COMMENT '传感器类型 CAMERA/LIDAR/RADAR/IMU',
    version            VARCHAR(64)  NOT NULL COMMENT '参数版本号，同一参数键下唯一',
    content_json       CLOB         NOT NULL COMMENT '参数内容 JSON',
    content_digest     CHAR(64)     NOT NULL COMMENT '内容 SHA-256 摘要（hex）',
    status             VARCHAR(32)  NOT NULL COMMENT '状态 DRAFT/APPROVED/CANARY/ACTIVE/ROLLED_BACK/SUPERSEDED',
    effective_from     TIMESTAMP(6) NULL COMMENT '生效起始时间 UTC 含边界；NULL 不限制',
    effective_to       TIMESTAMP(6) NULL COMMENT '生效结束时间 UTC 不含边界；NULL 不限制',
    vehicle_scope_json VARCHAR(4000) NULL COMMENT '适用车辆 JSON 数组；NULL/[] 表示全部车辆',
    sensor_scope_json  VARCHAR(4000) NULL COMMENT '适用传感器 JSON 数组；NULL/[] 不额外限制',
    map_version_min    VARCHAR(64)  NULL COMMENT '适用地图版本下界 含；NULL 不限制',
    map_version_max    VARCHAR(64)  NULL COMMENT '适用地图版本上界 含；NULL 不限制',
    created_by         VARCHAR(128) NOT NULL COMMENT '创建者',
    created_at         TIMESTAMP(6) NOT NULL COMMENT '创建时间 UTC',
    approved_by        VARCHAR(128) NULL COMMENT '审批操作者',
    approved_at        TIMESTAMP(6) NULL COMMENT '审批时间 UTC',
    version_no         BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    CONSTRAINT uk_param_key_version UNIQUE (param_key, version)
);
COMMENT ON TABLE calibration_param IS '标定参数版本（相机内参/外参/IMU 参数）';

-- 灰度发布单
CREATE TABLE IF NOT EXISTS canary_release (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id    VARCHAR(64)  NOT NULL COMMENT '发布单号，业务唯一',
    param_id      BIGINT       NOT NULL COMMENT '灰度新版本参数 ID',
    param_key     VARCHAR(128) NOT NULL COMMENT '参数键',
    new_version   VARCHAR(64)  NOT NULL COMMENT '灰度新版本号',
    vehicles_json CLOB         NOT NULL COMMENT '参与灰度车辆集合 JSON 数组',
    status        VARCHAR(32)  NOT NULL COMMENT '发布单状态 ACTIVE/PROMOTED/ROLLED_BACK',
    created_by    VARCHAR(128) NOT NULL COMMENT '发布操作者',
    created_at    TIMESTAMP(6) NOT NULL COMMENT '发布时间 UTC',
    finished_by   VARCHAR(128) NULL COMMENT '回滚/全量操作者',
    finished_at   TIMESTAMP(6) NULL COMMENT '回滚/全量时间 UTC',
    remark        VARCHAR(512) NULL COMMENT '备注',
    CONSTRAINT uk_release_id UNIQUE (release_id)
);
COMMENT ON TABLE canary_release IS '灰度发布单';

-- 灰度车辆明细
CREATE TABLE IF NOT EXISTS canary_vehicle (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id        BIGINT       NOT NULL COMMENT '所属发布单主键',
    vehicle_id        VARCHAR(128) NOT NULL COMMENT '灰度车辆标识',
    previous_param_id BIGINT       NULL COMMENT '发布前该车辆解析到的参数版本 ID；NULL 表示此前无有效版本',
    previous_version  VARCHAR(64)  NULL COMMENT '发布前版本号',
    CONSTRAINT uk_release_vehicle UNIQUE (release_id, vehicle_id)
);
COMMENT ON TABLE canary_vehicle IS '灰度车辆明细及各自发布前版本';

-- 参数解析历史（只增不改）
CREATE TABLE IF NOT EXISTS resolution_record (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    resolution_id     VARCHAR(64)  NOT NULL COMMENT '解析追踪 ID UUID，对外可查询',
    param_key         VARCHAR(128) NOT NULL COMMENT '解析的参数键',
    vehicle_id        VARCHAR(128) NOT NULL COMMENT '解析车辆',
    sensor_id         VARCHAR(256) NULL COMMENT '解析传感器标识',
    map_version       VARCHAR(64)  NULL COMMENT '解析时地图版本',
    resolve_time      TIMESTAMP(6) NOT NULL COMMENT '解析时间 UTC',
    resolved_param_id BIGINT       NOT NULL COMMENT '当时命中的参数版本 ID（历史快照不可变）',
    resolved_version  VARCHAR(64)  NOT NULL COMMENT '当时命中的版本号',
    content_digest    CHAR(64)     NOT NULL COMMENT '当时版本内容摘要',
    content_json      CLOB         NOT NULL COMMENT '当时版本内容快照 JSON，后续发布/回滚不改写',
    CONSTRAINT uk_resolution_id UNIQUE (resolution_id)
);
COMMENT ON TABLE resolution_record IS '参数解析历史，resolutionId 永久指向当时版本';

-- 参数状态流转审计
CREATE TABLE IF NOT EXISTS param_status_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    param_id    BIGINT       NOT NULL COMMENT '参数版本 ID',
    from_status VARCHAR(32)  NULL COMMENT '流转前状态；NULL 表示新建',
    to_status   VARCHAR(32)  NOT NULL COMMENT '流转后状态',
    operator    VARCHAR(128) NOT NULL COMMENT '操作者',
    operated_at TIMESTAMP(6) NOT NULL COMMENT '操作时间 UTC',
    release_ref VARCHAR(64)  NULL COMMENT '关联发布单号（灰度/回滚时）',
    remark      VARCHAR(512) NULL COMMENT '备注'
);
COMMENT ON TABLE param_status_log IS '参数状态转换审计，保存操作者与时间';

CREATE INDEX IF NOT EXISTS idx_param_lookup ON calibration_param (param_key, status);
CREATE INDEX IF NOT EXISTS idx_canary_vehicle_rid ON canary_vehicle (release_id);
CREATE INDEX IF NOT EXISTS idx_resolution_time ON resolution_record (resolve_time);
