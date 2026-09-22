-- Nav2 风格动态代价地图：H2 MODE=MySQL 下自动建表，语法同时兼容 MySQL 8。
-- 所有代价取值 0..254，255 保留为“未知”。时间戳业务时区为 Asia/Shanghai，存储使用 epoch 毫秒。

-- 不可变地图版本：静态栅格 + 膨胀参数 + 当前更新序号
CREATE TABLE IF NOT EXISTS map_version (
    version_id       VARCHAR(64)  NOT NULL COMMENT '地图版本ID,创建后不可变',
    width            INT          NOT NULL COMMENT '栅格列数,单位格',
    height           INT          NOT NULL COMMENT '栅格行数,单位格',
    resolution       DOUBLE       NOT NULL COMMENT '分辨率,单位米/格',
    inflation_radius DOUBLE       NOT NULL COMMENT '障碍物膨胀半径,单位米',
    cost_decay       DOUBLE       NOT NULL COMMENT '膨胀代价指数衰减系数,单位1/米',
    static_grid      BLOB         NOT NULL COMMENT '静态层原始代价0..254,行优先字节数组',
    static_inflated  BLOB         NOT NULL COMMENT '静态层创建时一次预计算的膨胀代价0..254,行优先,后续更新不重建',
    seq              BIGINT       NOT NULL DEFAULT 0 COMMENT '已应用障碍物更新的逻辑序号',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '版本创建时间',
    PRIMARY KEY (version_id)
);

-- 障碍物更新请求：request_id 唯一，用于同参幂等重放 / 异参 409
CREATE TABLE IF NOT EXISTS obstacle_update (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    version_id   VARCHAR(64)  NOT NULL COMMENT '所属地图版本ID',
    request_id   VARCHAR(128) NOT NULL COMMENT '客户端幂等请求ID,全局唯一',
    source_id    VARCHAR(64)  NOT NULL COMMENT '障碍物来源ID,合并决胜第三键',
    priority     INT          NOT NULL COMMENT '来源优先级,数值越大越优先,合并决胜第一键',
    x_min        INT          NOT NULL COMMENT '障碍物区域最小列(含)',
    y_min        INT          NOT NULL COMMENT '障碍物区域最小行(含)',
    x_max        INT          NOT NULL COMMENT '障碍物区域最大列(含)',
    y_max        INT          NOT NULL COMMENT '障碍物区域最大行(含)',
    timestamp_ms BIGINT       NOT NULL COMMENT '障碍物事件时间戳,epoch毫秒',
    ttl_ms       BIGINT       NOT NULL COMMENT '存活时长,单位毫秒',
    expires_at   BIGINT       NOT NULL COMMENT '过期时刻=timestamp_ms+ttl_ms,epoch毫秒',
    fingerprint  VARCHAR(64)  NOT NULL COMMENT '除request_id外请求参数的SHA-256指纹',
    seq          BIGINT       NOT NULL COMMENT '该更新应用后地图版本的逻辑序号',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '请求落库时间',
    PRIMARY KEY (id),
    UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_obstacle_update_version ON obstacle_update (version_id);

-- 障碍物单元格：每次更新在区域内每格写一行；同格多行由合并策略决胜
CREATE TABLE IF NOT EXISTS obstacle_cell (
    version_id   VARCHAR(64)  NOT NULL COMMENT '所属地图版本ID',
    x            INT          NOT NULL COMMENT '格点列号',
    y            INT          NOT NULL COMMENT '格点行号',
    request_id   VARCHAR(128) NOT NULL COMMENT '写入该行的更新请求ID',
    source_id    VARCHAR(64)  NOT NULL COMMENT '障碍物来源ID',
    priority     INT          NOT NULL COMMENT '来源优先级,大者优先',
    timestamp_ms BIGINT       NOT NULL COMMENT '障碍物事件时间戳,合并决胜第二键',
    expires_at   BIGINT       NOT NULL COMMENT '该行过期时刻,仅本行到期才允许清理',
    PRIMARY KEY (version_id, x, y, request_id)
);
CREATE INDEX IF NOT EXISTS idx_obstacle_cell_expiry ON obstacle_cell (version_id, expires_at);

-- 增量重算后的合并代价缓存：只包含被更新/膨胀波及的格点
CREATE TABLE IF NOT EXISTS dynamic_cost (
    version_id   VARCHAR(64) NOT NULL COMMENT '所属地图版本ID',
    x            INT         NOT NULL COMMENT '格点列号',
    y            INT         NOT NULL COMMENT '格点行号',
    cost         INT         NOT NULL COMMENT '静态层与动态层合并后的总代价0..254',
    updated_seq  BIGINT      NOT NULL COMMENT '最近一次重算该格时的更新序号',
    PRIMARY KEY (version_id, x, y)
);

-- 已发布快照：一经发布不可变，不受后续更新与 TTL 清理影响
CREATE TABLE IF NOT EXISTS costmap_snapshot (
    snapshot_id  VARCHAR(64) NOT NULL COMMENT '快照ID',
    version_id   VARCHAR(64) NOT NULL COMMENT '所属地图版本ID',
    seq          BIGINT      NOT NULL COMMENT '发布时地图版本的更新序号',
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照发布时间',
    PRIMARY KEY (snapshot_id)
);

-- 快照冻结的格点代价：缺失行表示与静态层一致
CREATE TABLE IF NOT EXISTS snapshot_cell (
    snapshot_id  VARCHAR(64) NOT NULL COMMENT '所属快照ID',
    x            INT         NOT NULL COMMENT '格点列号',
    y            INT         NOT NULL COMMENT '格点行号',
    cost         INT         NOT NULL COMMENT '发布时刻冻结的总代价0..254',
    PRIMARY KEY (snapshot_id, x, y)
);
