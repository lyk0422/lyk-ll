-- Nav2 风格动态 Costmap 服务建表脚本（H2 MySQL 兼容模式；类型语法同时兼容 MySQL 8）。
-- 所有代价均为 Nav2 costmap 风格的 0..254 无符号整数代价；坐标单位均为栅格单元格（cell），
-- 原点在左上角，x 向右（列）、y 向下（行），行优先存储；时间均为 epoch 毫秒（UTC 绝对时刻）。
-- 索引一律内联声明，避免 MySQL 不支持 CREATE INDEX IF NOT EXISTS 的兼容性问题。

-- 代价地图：不可变版本头 + 静态格栅 + 膨胀参数；live_data 为当前动态查询视图缓存。
CREATE TABLE IF NOT EXISTS costmap (
    id                   VARCHAR(64)  NOT NULL COMMENT '地图版本 ID（客户端指定或服务端生成），主键',
    width                INT          NOT NULL COMMENT '栅格列数（x 方向单元格数）',
    height               INT          NOT NULL COMMENT '栅格行数（y 方向单元格数）',
    resolution           DOUBLE       NOT NULL COMMENT '每单元格对应的物理边长（米/单元格）',
    origin_x             DOUBLE       NOT NULL COMMENT '地图原点物理 x 坐标（米）',
    origin_y             DOUBLE       NOT NULL COMMENT '地图原点物理 y 坐标（米）',
    inscribed_radius     DOUBLE       NOT NULL COMMENT '内切膨胀半径（单元格）；该距离内代价为 253',
    inflation_radius     DOUBLE       NOT NULL COMMENT '最大膨胀半径（单元格）；超出后膨胀代价为 0',
    cost_scaling_factor  DOUBLE       NOT NULL COMMENT '膨胀衰减系数，越大衰减越快',
    static_data          BLOB         NOT NULL COMMENT '静态格栅代价，int[width*height] 二进制，行优先，254=致命障碍',
    live_data            BLOB         NOT NULL COMMENT '当前动态视图缓存，int[width*height] 二进制，行优先',
    winner_data          BLOB         NOT NULL COMMENT '当前视图每格获胜语句序号，long[width*height] 二进制，行优先，0=无获胜声明',
    live_seq             BIGINT       NOT NULL COMMENT '动态视图单调版本序号，每次数据变更加 1',
    alloc_seq            BIGINT       NOT NULL COMMENT '障碍物语句序号分配高水位，恢复快照后仍单调递增',
    snapshot_seq         BIGINT       NOT NULL COMMENT '已发布快照序号计数',
    created_at           BIGINT       NOT NULL COMMENT '创建时刻（epoch 毫秒）',
    CONSTRAINT pk_costmap PRIMARY KEY (id)
);

-- 动态障碍物声明（含 OCCUPY 占据 / CLEAR 清除两类），同一 requestId 仅可应用一次。
CREATE TABLE IF NOT EXISTS obstacle_statement (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    map_id          VARCHAR(64)  NOT NULL COMMENT '所属地图 ID',
    request_id      VARCHAR(128) NOT NULL COMMENT '客户端幂等请求 ID；同地图内唯一',
    source_id       VARCHAR(64)  NOT NULL COMMENT '障碍物来源 ID（传感器/上报方标识）',
    obstacle_id     VARCHAR(64)  NOT NULL COMMENT '来源内障碍物身份标识；同一障碍物多次上报为不同版本行',
    priority        INT          NOT NULL COMMENT '来源公开优先级，数值越大优先级越高，平局按时间戳、来源 ID 决胜',
    kind            VARCHAR(16)  NOT NULL COMMENT '声明类型：OCCUPY=占据（254），CLEAR=清除动态占据（0）',
    x0              INT          NOT NULL COMMENT '区域最小列（含），单元格坐标',
    y0              INT          NOT NULL COMMENT '区域最小行（含），单元格坐标',
    x1              INT          NOT NULL COMMENT '区域最大列（含），单元格坐标',
    y1              INT          NOT NULL COMMENT '区域最大行（含），单元格坐标',
    event_time      BIGINT       NOT NULL COMMENT '来源观测时间戳（epoch 毫秒）',
    ttl_millis      BIGINT       NOT NULL COMMENT '存活时长（毫秒）；event_time+ttl_millis 为过期时刻',
    expire_at       BIGINT       NOT NULL COMMENT '过期时刻（epoch 毫秒）；清理仅删除 expire_at<=当前时刻的行',
    param_fingerprint CHAR(64)   NOT NULL COMMENT '请求关键参数 SHA-256 指纹，用于同 requestId 异参 409 判定',
    seq             BIGINT       NOT NULL COMMENT '应用序号（地图内单调递增）',
    created_at      BIGINT       NOT NULL COMMENT '服务端接收时刻（epoch 毫秒）',
    CONSTRAINT pk_obstacle_statement PRIMARY KEY (id),
    CONSTRAINT uq_obstacle_request UNIQUE (map_id, request_id),
    INDEX idx_obstacle_map (map_id),
    INDEX idx_obstacle_expire (map_id, expire_at)
);

-- 幂等记录：障碍物行可能因 TTL 过期被物理删除，但请求指纹必须永久保留以支持重放判定。
CREATE TABLE IF NOT EXISTS obstacle_request (
    map_id          VARCHAR(64)  NOT NULL COMMENT '所属地图 ID',
    request_id      VARCHAR(128) NOT NULL COMMENT '客户端幂等请求 ID',
    param_fingerprint CHAR(64)   NOT NULL COMMENT '请求关键参数 SHA-256 指纹，异参重放冲突 409',
    applied_seq     BIGINT       NOT NULL COMMENT '该请求首次应用得到的语句序号',
    created_at      BIGINT       NOT NULL COMMENT '首次接收时刻（epoch 毫秒）',
    CONSTRAINT pk_obstacle_request PRIMARY KEY (map_id, request_id)
);

-- 已发布快照：不可变；cell_data 在发布时刻固化，后续更新与 TTL 清理不影响该表。
CREATE TABLE IF NOT EXISTS costmap_snapshot (
    map_id          VARCHAR(64)  NOT NULL COMMENT '所属地图 ID',
    snapshot_id     VARCHAR(128) NOT NULL COMMENT '快照 ID（客户端指定或服务端生成）',
    seq             BIGINT       NOT NULL COMMENT '地图内快照序号',
    label           VARCHAR(255) COMMENT '可选备注',
    width           INT          NOT NULL COMMENT '栅格列数',
    height          INT          NOT NULL COMMENT '栅格行数',
    live_seq        BIGINT       NOT NULL COMMENT '发布时动态视图版本序号',
    alloc_seq       BIGINT       NOT NULL COMMENT '发布时语句序号高水位，恢复后新语句序号继续单调递增',
    cell_data       BLOB         NOT NULL COMMENT '发布时刻固化的全图代价，int[width*height] 二进制，行优先',
    winner_data     BLOB         NOT NULL COMMENT '发布时刻每格获胜语句序号，long[width*height] 二进制，行优先，0=无获胜声明',
    created_at      BIGINT       NOT NULL COMMENT '发布时刻（epoch 毫秒）',
    CONSTRAINT pk_costmap_snapshot PRIMARY KEY (map_id, snapshot_id)
);

-- 快照发布时刻的活动障碍物声明副本，用于恢复查询视图时核对内容。
CREATE TABLE IF NOT EXISTS snapshot_statement (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    map_id          VARCHAR(64)  NOT NULL COMMENT '所属地图 ID',
    snapshot_id     VARCHAR(128) NOT NULL COMMENT '所属快照 ID',
    request_id      VARCHAR(128) NOT NULL COMMENT '原始幂等请求 ID',
    source_id       VARCHAR(64)  NOT NULL COMMENT '障碍物来源 ID',
    obstacle_id     VARCHAR(64)  NOT NULL COMMENT '来源内障碍物身份标识',
    priority        INT          NOT NULL COMMENT '来源优先级，数值越大优先级越高',
    kind            VARCHAR(16)  NOT NULL COMMENT '声明类型：OCCUPY/CLEAR',
    x0              INT          NOT NULL COMMENT '区域最小列（含）',
    y0              INT          NOT NULL COMMENT '区域最小行（含）',
    x1              INT          NOT NULL COMMENT '区域最大列（含）',
    y1              INT          NOT NULL COMMENT '区域最大行（含）',
    event_time      BIGINT       NOT NULL COMMENT '来源观测时间戳（epoch 毫秒）',
    ttl_millis      BIGINT       NOT NULL COMMENT '存活时长（毫秒）',
    expire_at       BIGINT       NOT NULL COMMENT '过期时刻（epoch 毫秒），快照内仅存档不参与清理',
    stmt_seq        BIGINT       NOT NULL COMMENT '原始应用序号，恢复视图后用于栅格决胜与增量计算',
    CONSTRAINT pk_snapshot_statement PRIMARY KEY (id),
    INDEX idx_snapshot_stmt (map_id, snapshot_id)
);
