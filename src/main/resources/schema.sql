-- Nav2 风格动态代价地图服务的 H2 初始化脚本（MySQL 兼容模式）。
-- 数据仅在 JVM 生命周期内保留（H2 内存库）。

-- 地图版本快照表：发布后即不可变，后续障碍物更新与 TTL 清理不影响已保存内容。
CREATE TABLE IF NOT EXISTS costmap_snapshot (
    map_id            VARCHAR(64)  NOT NULL,
    version           BIGINT       NOT NULL,
    width             INT          NOT NULL,
    height            INT          NOT NULL,
    resolution        DOUBLE       NOT NULL,
    inflation_radius  DOUBLE       NOT NULL,
    grid              BLOB         NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (map_id, version)
);
COMMENT ON TABLE costmap_snapshot IS '代价地图版本快照；grid 为行主序字节数组，取值 0-255（0 空闲，254 致命障碍，255 未知）';
COMMENT ON COLUMN costmap_snapshot.map_id IS '地图标识';
COMMENT ON COLUMN costmap_snapshot.version IS '快照对应的地图版本号';
COMMENT ON COLUMN costmap_snapshot.width IS '地图宽度（格）';
COMMENT ON COLUMN costmap_snapshot.height IS '地图高度（格）';
COMMENT ON COLUMN costmap_snapshot.resolution IS '分辨率（米/格）';
COMMENT ON COLUMN costmap_snapshot.inflation_radius IS '膨胀半径（米）';
COMMENT ON COLUMN costmap_snapshot.grid IS '行主序栅格代价字节数组，长度 width*height';
COMMENT ON COLUMN costmap_snapshot.created_at IS '快照发布时间（UTC）';

-- 障碍物更新幂等记录表：同一 (map_id, request_id) 只允许一条记录。
CREATE TABLE IF NOT EXISTS obstacle_request (
    map_id          VARCHAR(64)  NOT NULL,
    request_id      VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    applied_version BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (map_id, request_id)
);
COMMENT ON TABLE obstacle_request IS '障碍物更新幂等记录；同 requestId 同参数重放不重复应用，异参数返回 409';
COMMENT ON COLUMN obstacle_request.map_id IS '地图标识';
COMMENT ON COLUMN obstacle_request.request_id IS '客户端请求标识（幂等键）';
COMMENT ON COLUMN obstacle_request.request_hash IS '请求参数规范化后的 SHA-256 摘要（十六进制）';
COMMENT ON COLUMN obstacle_request.applied_version IS '该请求首次应用后产生的地图版本号';
COMMENT ON COLUMN obstacle_request.created_at IS '首次接收时间（UTC）';

-- 障碍物更新审计表：记录每次成功应用的障碍物更新。
CREATE TABLE IF NOT EXISTS obstacle_update (
    map_id          VARCHAR(64)  NOT NULL,
    obstacle_id     VARCHAR(64)  NOT NULL,
    request_id      VARCHAR(128) NOT NULL,
    source_id       VARCHAR(128) NOT NULL,
    priority        INT          NOT NULL,
    obstacle_key    VARCHAR(128) NULL,
    x               INT          NOT NULL,
    y               INT          NOT NULL,
    width           INT          NOT NULL,
    height          INT          NOT NULL,
    cells           BLOB         NOT NULL,
    client_ts       BIGINT       NOT NULL,
    ttl_millis      BIGINT       NULL,
    expires_at      BIGINT       NULL,
    applied_version BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (map_id, obstacle_id)
);
COMMENT ON TABLE obstacle_update IS '障碍物更新审计；expires_at 为接收时刻加 TTL 的纪元毫秒，NULL 表示不过期';
COMMENT ON COLUMN obstacle_update.map_id IS '地图标识';
COMMENT ON COLUMN obstacle_update.obstacle_id IS '服务端生成的障碍物记录标识';
COMMENT ON COLUMN obstacle_update.request_id IS '客户端请求标识（幂等键）';
COMMENT ON COLUMN obstacle_update.source_id IS '障碍物来源标识，合并时按字典序作最终决胜项';
COMMENT ON COLUMN obstacle_update.priority IS '来源优先级，数值大者优先';
COMMENT ON COLUMN obstacle_update.obstacle_key IS '同一来源内的障碍物业务键；同键新更新替换旧记录，NULL 表示不参与替换';
COMMENT ON COLUMN obstacle_update.x IS '区域左上角 x（格，从 0 开始）';
COMMENT ON COLUMN obstacle_update.y IS '区域左上角 y（格，从 0 开始）';
COMMENT ON COLUMN obstacle_update.width IS '区域宽度（格）';
COMMENT ON COLUMN obstacle_update.height IS '区域高度（格）';
COMMENT ON COLUMN obstacle_update.cells IS '区域行主序代价字节数组，长度 width*height';
COMMENT ON COLUMN obstacle_update.client_ts IS '客户端时间戳（纪元毫秒），合并时大者优先';
COMMENT ON COLUMN obstacle_update.ttl_millis IS '生存时间（毫秒），NULL 表示不过期';
COMMENT ON COLUMN obstacle_update.expires_at IS '过期时刻（纪元毫秒，UTC），NULL 表示不过期';
COMMENT ON COLUMN obstacle_update.applied_version IS '应用后产生的地图版本号';
COMMENT ON COLUMN obstacle_update.created_at IS '记录创建时间（UTC）';
