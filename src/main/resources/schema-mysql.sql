-- 标定参数版本表（MySQL 8 方言；本地默认不使用此脚本，仅在 SQL_PLATFORM=mysql 时执行）
CREATE TABLE IF NOT EXISTS calibration_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    param_key       VARCHAR(128)  NOT NULL COMMENT '参数唯一键，如 cam_front/intrinsic',
    sensor_type     VARCHAR(16)   NOT NULL COMMENT '传感器类型 CAMERA/LIDAR/RADAR/IMU',
    kind            VARCHAR(16)   NOT NULL COMMENT '参数类别 INTRINSIC/EXTRINSIC/IMU',
    version         VARCHAR(64)   NOT NULL COMMENT '版本号（同一 param_key 下唯一）',
    content_sha256  CHAR(64)      NOT NULL COMMENT '参数内容 SHA-256 摘要（十六进制）',
    content_json    JSON          NOT NULL COMMENT '参数内容 JSON 原文',
    status          VARCHAR(16)   NOT NULL COMMENT '状态 DRAFT/APPROVED/CANARY/ACTIVE/ROLLED_BACK/SUPERSEDED',
    effective_from  TIMESTAMP(3)  NULL COMMENT '生效起始时间 UTC（含），NULL 为立即生效',
    effective_to    TIMESTAMP(3)  NULL COMMENT '生效结束时间 UTC（不含），NULL 为长期有效',
    vehicle_from    VARCHAR(64)   NULL COMMENT '适用车辆范围下限（含，字典序），NULL 不限',
    vehicle_to      VARCHAR(64)   NULL COMMENT '适用车辆范围上限（含，字典序），NULL 不限',
    sensor_from     VARCHAR(64)   NULL COMMENT '适用传感器范围下限（含，字典序），NULL 不限',
    sensor_to       VARCHAR(64)   NULL COMMENT '适用传感器范围上限（含，字典序），NULL 不限',
    map_from        VARCHAR(64)   NULL COMMENT '适用地图版本下限（含，字典序），NULL 不限',
    map_to          VARCHAR(64)   NULL COMMENT '适用地图版本上限（含，字典序），NULL 不限',
    created_by      VARCHAR(64)   NOT NULL COMMENT '创建操作者',
    created_at      TIMESTAMP(3)  NOT NULL COMMENT '创建时间 UTC',
    approved_by     VARCHAR(64)   NULL COMMENT '审批操作者，NULL 未审批',
    approved_at     TIMESTAMP(3)  NULL COMMENT '审批时间 UTC，NULL 未审批',
    activated_by    VARCHAR(64)   NULL COMMENT '全量生效操作者，NULL 未生效',
    activated_at    TIMESTAMP(3)  NULL COMMENT '全量生效时间 UTC，NULL 未生效',
    rolled_back_by  VARCHAR(64)   NULL COMMENT '回滚操作者，NULL 未回滚',
    rolled_back_at  TIMESTAMP(3)  NULL COMMENT '回滚时间 UTC，NULL 未回滚',
    UNIQUE KEY uk_calib_key_version (param_key, version)
) COMMENT='标定参数版本（相机内参/外参/IMU）';

CREATE TABLE IF NOT EXISTS canary_release (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id         VARCHAR(40)  NOT NULL COMMENT '灰度批次业务 ID（UUID，追溯用）',
    param_key          VARCHAR(128) NOT NULL COMMENT '目标参数键',
    canary_version_id  BIGINT       NOT NULL COMMENT '灰度采用的新版本 ID',
    status             VARCHAR(16)  NOT NULL COMMENT '批次状态 IN_PROGRESS/PROMOTED/ROLLED_BACK',
    created_by         VARCHAR(64)  NOT NULL COMMENT '发起操作者',
    created_at         TIMESTAMP(3) NOT NULL COMMENT '发起时间 UTC',
    promoted_by        VARCHAR(64)  NULL COMMENT '转正操作者，NULL 未转正',
    promoted_at        TIMESTAMP(3) NULL COMMENT '转正时间 UTC，NULL 未转正',
    rolled_back_by     VARCHAR(64)  NULL COMMENT '回滚操作者，NULL 未回滚',
    rolled_back_at     TIMESTAMP(3) NULL COMMENT '回滚时间 UTC，NULL 未回滚',
    UNIQUE KEY uk_canary_release_id (release_id)
) COMMENT='灰度发布批次（显式车辆集合，批量原子切换）';

CREATE TABLE IF NOT EXISTS canary_vehicle (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id           BIGINT       NOT NULL COMMENT '所属灰度批次主键',
    vehicle_id           VARCHAR(64)  NOT NULL COMMENT '灰度车辆编号',
    previous_version_id  BIGINT       NULL COMMENT '发布前该车辆解析的基线版本 ID，NULL 表示无基线',
    KEY idx_canary_vehicle_release (release_id),
    CONSTRAINT fk_canary_vehicle_release FOREIGN KEY (release_id) REFERENCES canary_release (id)
) COMMENT='灰度车辆条目及发布前版本快照';

CREATE TABLE IF NOT EXISTS canary_vehicle_lock (
    param_key   VARCHAR(128) NOT NULL COMMENT '参数键',
    vehicle_id  VARCHAR(64)  NOT NULL COMMENT '车辆编号',
    PRIMARY KEY (param_key, vehicle_id)
) COMMENT='进行中灰度车辆占用锁，转正/回滚时释放';

CREATE TABLE IF NOT EXISTS resolution_record (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    resolution_id       VARCHAR(40)  NOT NULL COMMENT '可追溯解析 ID（UUID）',
    param_key           VARCHAR(128) NOT NULL COMMENT '解析的参数键',
    vehicle_id          VARCHAR(64)  NOT NULL COMMENT '解析时车辆编号',
    sensor_id           VARCHAR(64)  NULL COMMENT '解析时传感器标识',
    map_version         VARCHAR(64)  NULL COMMENT '解析时地图版本',
    resolved_at         TIMESTAMP(3) NOT NULL COMMENT '解析时刻 UTC',
    selected_version_id BIGINT       NOT NULL COMMENT '命中的标定版本 ID（历史快照，不随后续发布改变）',
    canary_release_pk   BIGINT       NULL COMMENT '命中时所属进行中灰度批次主键，基线解析为 NULL',
    UNIQUE KEY uk_resolution_id (resolution_id),
    KEY idx_resolution_version (selected_version_id)
) COMMENT='参数解析历史（只追加，历史引用不可变）';

CREATE TABLE IF NOT EXISTS status_transition (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id    BIGINT       NOT NULL COMMENT '标定版本 ID',
    from_status   VARCHAR(16)  NULL COMMENT '转换前状态，创建时为 NULL',
    to_status     VARCHAR(16)  NOT NULL COMMENT '转换后状态',
    operator      VARCHAR(64)  NOT NULL COMMENT '操作者',
    operated_at   TIMESTAMP(3) NOT NULL COMMENT '转换时间 UTC',
    remark        VARCHAR(512) NULL COMMENT '备注（灰度批次 ID、回滚/转正说明）',
    KEY idx_transition_version (version_id)
) COMMENT='版本状态流转审计（操作者与时间）';
