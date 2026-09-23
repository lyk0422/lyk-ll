-- Nav2 风格动态 Costmap 服务建表脚本（H2 MODE=MySQL 兼容）

-- 地图不可变版本表：每个 (map_id, version) 一行，version 单调递增
CREATE TABLE IF NOT EXISTS costmap_version (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    map_id       VARCHAR(64)  NOT NULL COMMENT '地图业务 ID',
    version      BIGINT       NOT NULL COMMENT '地图版本号，从 1 开始单调递增',
    width        INT          NOT NULL COMMENT '格栅列数（x 方向格数）',
    height       INT          NOT NULL COMMENT '格栅行数（y 方向格数）',
    resolution   DOUBLE       NOT NULL COMMENT '每格物理边长，单位米，必须为正',
    origin_x     DOUBLE       NOT NULL COMMENT '格栅原点世界坐标 x，单位米',
    origin_y     DOUBLE       NOT NULL COMMENT '格栅原点世界坐标 y，单位米',
    inflation_radius_cells INT NOT NULL COMMENT '膨胀半径，单位格（0 表示关闭膨胀）',
    cost_decay   DOUBLE       NOT NULL COMMENT '膨胀代价随距离衰减系数（每格乘数的指数底数）',
    created_at   BIGINT       NOT NULL COMMENT '创建时间，Unix 毫秒，Asia/Shanghai 业务时区',
    UNIQUE (map_id, version)
);

-- 静态格栅表：静态层在版本创建后不可变
CREATE TABLE IF NOT EXISTS costmap_static_cell (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    map_id       VARCHAR(64)  NOT NULL COMMENT '地图业务 ID',
    version      BIGINT       NOT NULL COMMENT '所属地图版本',
    x            INT          NOT NULL COMMENT '格坐标 x（列），从 0 开始',
    y            INT          NOT NULL COMMENT '格坐标 y（行），从 0 开始',
    cost         INT          NOT NULL COMMENT '静态代价 0-100，100 表示致命障碍',
    created_at   BIGINT       NOT NULL COMMENT '写入时间，Unix 毫秒',
    UNIQUE (map_id, version, x, y)
);

-- 动态障碍物层：同一障碍物来源+格以 revision 区分更新版本
CREATE TABLE IF NOT EXISTS costmap_obstacle (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    map_id       VARCHAR(64)  NOT NULL COMMENT '地图业务 ID',
    version      BIGINT       NOT NULL COMMENT '写入时的当前地图版本（仅记录，视图始终读最新版本）',
    request_id   VARCHAR(128) NOT NULL COMMENT '幂等请求 ID，同一 requestId 同参重放不重复应用',
    source_id    VARCHAR(64)  NOT NULL COMMENT '来源 ID，同优先级同时间戳时按字典序决胜',
    source_type  VARCHAR(32)  NOT NULL COMMENT '来源类型：SAFETY/MANUAL/SENSOR/GENERIC，决定固定优先级',
    x            INT          NOT NULL COMMENT '格坐标 x（列）',
    y            INT          NOT NULL COMMENT '格坐标 y（行）',
    cost         INT          NOT NULL COMMENT '来源上报的障碍代价 0-100',
    revision     BIGINT       NOT NULL COMMENT '该 (map_id, source_id, x, y) 的单调更新序号',
    event_time   BIGINT       NOT NULL COMMENT '来源事件时间戳，Unix 毫秒；合并决胜第二关键字',
    expires_at   BIGINT       NULL COMMENT '过期时刻，Unix 毫秒；NULL 表示不过期',
    applied_at   BIGINT       NOT NULL COMMENT '服务端应用时间，Unix 毫秒',
    superseded   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已被同一障碍的更新版本取代：1 是 0 否'
);
-- requestId 幂等唯一约束由 costmap_idempotency 表保证；一个请求可覆盖多格，此处仅建普通索引
CREATE INDEX IF NOT EXISTS idx_obstacle_request ON costmap_obstacle (map_id, request_id);
CREATE INDEX IF NOT EXISTS idx_obstacle_live ON costmap_obstacle (map_id, x, y, superseded);
CREATE INDEX IF NOT EXISTS idx_obstacle_owner ON costmap_obstacle (map_id, source_id, x, y, revision);
CREATE UNIQUE INDEX IF NOT EXISTS uq_obstacle_owner_revision
    ON costmap_obstacle (map_id, source_id, x, y, revision);
CREATE INDEX IF NOT EXISTS idx_obstacle_expiry ON costmap_obstacle (expires_at, superseded);

-- 幂等记录表：保存每个 requestId 的请求指纹与应答
CREATE TABLE IF NOT EXISTS costmap_idempotency (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    map_id       VARCHAR(64)  NOT NULL COMMENT '地图业务 ID',
    request_id   VARCHAR(128) NOT NULL COMMENT '幂等请求 ID',
    fingerprint  VARCHAR(512) NOT NULL COMMENT '规范化请求参数指纹（SHA-256）',
    applied_revision BIGINT   NOT NULL COMMENT '首次应用时写入的 revision',
    recorded_at  BIGINT       NOT NULL COMMENT '首次记录时间，Unix 毫秒',
    UNIQUE (map_id, request_id)
);

-- 快照主表：一经发布内容不可变
CREATE TABLE IF NOT EXISTS costmap_snapshot (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    snapshot_id  VARCHAR(64)  NOT NULL COMMENT '快照业务 ID',
    map_id       VARCHAR(64)  NOT NULL COMMENT '地图业务 ID',
    map_version  BIGINT       NOT NULL COMMENT '快照对应的地图版本',
    created_at   BIGINT       NOT NULL COMMENT '发布时间，Unix 毫秒',
    UNIQUE (snapshot_id)
);

-- 快照单元表：发布时刻每格代价（静态/动态/膨胀分层及最终代价）
CREATE TABLE IF NOT EXISTS costmap_snapshot_cell (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    snapshot_id  VARCHAR(64)  NOT NULL COMMENT '所属快照业务 ID',
    x            INT          NOT NULL COMMENT '格坐标 x（列）',
    y            INT          NOT NULL COMMENT '格坐标 y（行）',
    static_cost  INT          NOT NULL DEFAULT 0 COMMENT '静态层代价 0-100',
    obstacle_cost INT         NULL COMMENT '动态获胜层代价 0-100；无来源为 NULL',
    inflated_cost INT         NOT NULL DEFAULT 0 COMMENT '膨胀层代价 0-100',
    final_cost   INT          NOT NULL COMMENT '发布时刻该格最终代价 0-100',
    winner_source_id VARCHAR(64) NULL COMMENT '获胜来源 ID；无动态来源为 NULL',
    winner_type  VARCHAR(32)  NULL COMMENT '获胜来源类型；无动态来源为 NULL',
    UNIQUE (snapshot_id, x, y)
);
