-- Autoware 风格路线版本与地图匹配服务：H2 内存库（MODE=MySQL）自动建表脚本。
-- 仅在嵌入式库初始化时由 Spring 自动执行；数据仅保留在 JVM 生命周期内。

CREATE TABLE IF NOT EXISTS map_version (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    version        VARCHAR(64)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    published_at   TIMESTAMP    NOT NULL,
    deprecated_at  TIMESTAMP    NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_map_version_version ON map_version (version);

COMMENT ON TABLE map_version IS '地图版本表；热更新只允许发布新版本，已有版本行不可变，废弃仅置状态';
COMMENT ON COLUMN map_version.version IS '地图版本号，业务唯一键';
COMMENT ON COLUMN map_version.status IS '版本状态：ACTIVE 有效 / DEPRECATED 已废弃';
COMMENT ON COLUMN map_version.published_at IS '发布时间（Asia/Shanghai）';
COMMENT ON COLUMN map_version.deprecated_at IS '废弃时间，NULL 表示仍有效';

CREATE TABLE IF NOT EXISTS map_node (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    version   VARCHAR(64) NOT NULL,
    node_id   VARCHAR(64) NOT NULL,
    x         DOUBLE      NOT NULL,
    y         DOUBLE      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_map_node ON map_node (version, node_id);

COMMENT ON TABLE map_node IS '地图端点（节点）表，按版本快照保存，新版本发布后旧版本坐标不变';
COMMENT ON COLUMN map_node.node_id IS '端点业务 ID，版本内唯一';
COMMENT ON COLUMN map_node.x IS '端点平面 X 坐标，单位米';
COMMENT ON COLUMN map_node.y IS '端点平面 Y 坐标，单位米';

CREATE TABLE IF NOT EXISTS map_segment (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    version        VARCHAR(64) NOT NULL,
    segment_id     VARCHAR(64) NOT NULL,
    from_node_id   VARCHAR(64) NOT NULL,
    to_node_id     VARCHAR(64) NOT NULL,
    length_m       DOUBLE      NOT NULL,
    valid          TINYINT     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_map_segment ON map_segment (version, segment_id);
CREATE INDEX IF NOT EXISTS idx_map_segment_version ON map_segment (version);

COMMENT ON TABLE map_segment IS '路段表，按版本快照保存有向拓扑：from_node_id 指向 to_node_id';
COMMENT ON COLUMN map_segment.segment_id IS '路段业务 ID，版本内唯一';
COMMENT ON COLUMN map_segment.from_node_id IS '有向起点端点 ID';
COMMENT ON COLUMN map_segment.to_node_id IS '有向终点端点 ID';
COMMENT ON COLUMN map_segment.length_m IS '路段标称长度，单位米';
COMMENT ON COLUMN map_segment.valid IS '路段是否有效：1 有效，0 失效（仅作候选拒绝依据，不参与连通）';

CREATE TABLE IF NOT EXISTS route_request (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id     VARCHAR(64) NOT NULL,
    map_version    VARCHAR(64) NOT NULL,
    input_hash     CHAR(64)    NOT NULL,
    start_x        DOUBLE      NOT NULL,
    start_y        DOUBLE      NOT NULL,
    start_heading  DOUBLE      NOT NULL,
    end_x          DOUBLE      NOT NULL,
    end_y          DOUBLE      NOT NULL,
    end_heading    DOUBLE      NOT NULL,
    state          VARCHAR(16) NOT NULL,
    error_code     VARCHAR(48) NULL,
    created_at     TIMESTAMP   NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_route_request_id ON route_request (request_id);
CREATE INDEX IF NOT EXISTS idx_route_request_version_hash ON route_request (map_version, input_hash);

COMMENT ON TABLE route_request IS '路线请求表；提交时固定绑定地图版本，后续热更新不得改变其版本与结果';
COMMENT ON COLUMN route_request.request_id IS '客户端提供的请求幂等键';
COMMENT ON COLUMN route_request.map_version IS '提交时绑定的地图版本号快照';
COMMENT ON COLUMN route_request.input_hash IS '起终点输入参数的 SHA-256 摘要，用于同参/异参重放判定';
COMMENT ON COLUMN route_request.start_heading IS '起点航向角，单位度（0~360，正北顺时针）';
COMMENT ON COLUMN route_request.state IS '请求状态：MATCHED 匹配成功 / FAILED 匹配失败';
COMMENT ON COLUMN route_request.error_code IS '失败错误码，成功为 NULL';
COMMENT ON COLUMN route_request.created_at IS '首次提交时间（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS route_candidate (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id         VARCHAR(64) NOT NULL,
    endpoint           VARCHAR(8)  NOT NULL,
    rank_no            INT         NOT NULL,
    segment_id         VARCHAR(64) NOT NULL,
    distance_m         DOUBLE      NOT NULL,
    heading_delta_deg  DOUBLE      NOT NULL,
    distance_score     DOUBLE      NOT NULL,
    heading_score      DOUBLE      NOT NULL,
    connectivity_score DOUBLE      NOT NULL,
    total_score        DOUBLE      NOT NULL,
    selected           TINYINT     NOT NULL,
    reject_reasons     VARCHAR(255) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_route_candidate_request ON route_candidate (request_id);

COMMENT ON TABLE route_candidate IS '匹配候选明细表，保存每个候选的评分构成、排序、选中标记与拒绝原因';
COMMENT ON COLUMN route_candidate.endpoint IS '候选所属端点：START 起点 / END 终点';
COMMENT ON COLUMN route_candidate.rank_no IS '同端点内按总分降序的排名（1 起），总分相同按路段 ID 字典序';
COMMENT ON COLUMN route_candidate.distance_m IS '输入点到路段几何的垂直距离，单位米';
COMMENT ON COLUMN route_candidate.heading_delta_deg IS '输入航向与路段方向夹角，单位度（0~180）';
COMMENT ON COLUMN route_candidate.distance_score IS '距离评分，0~100，越近越高';
COMMENT ON COLUMN route_candidate.heading_score IS '航向评分，0~100，夹角越小越高';
COMMENT ON COLUMN route_candidate.connectivity_score IS '连通性评分：属于可连通候选对为 100，否则 0';
COMMENT ON COLUMN route_candidate.total_score IS '总分=距离+航向+连通性评分';
COMMENT ON COLUMN route_candidate.selected IS '是否为确定性规则选中的候选：1 是 0 否';
COMMENT ON COLUMN route_candidate.reject_reasons IS '拒绝原因码逗号分隔，空串表示未被拒绝';

CREATE TABLE IF NOT EXISTS route_match (
    request_id        VARCHAR(64) PRIMARY KEY,
    start_segment_id  VARCHAR(64) NOT NULL,
    end_segment_id    VARCHAR(64) NOT NULL,
    path_node_ids     CLOB        NOT NULL,
    path_segment_ids  CLOB        NOT NULL,
    total_score       DOUBLE      NOT NULL
);

COMMENT ON TABLE route_match IS '匹配成功结果表；保存选中起终路段与确定性连通路径';
COMMENT ON COLUMN route_match.path_node_ids IS '连通路径经过的节点 ID JSON 数组（含起终衔接节点）';
COMMENT ON COLUMN route_match.path_segment_ids IS '连通路径经过的路段 ID JSON 数组';
COMMENT ON COLUMN route_match.total_score IS '起终选中候选总分之和';
