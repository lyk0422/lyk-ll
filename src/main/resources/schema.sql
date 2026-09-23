-- SLAM 位姿图后端建表脚本（H2 MySQL 兼容模式自动初始化；幂等可重复执行）

CREATE TABLE IF NOT EXISTS pose_graph (
    graph_id        VARCHAR(128) NOT NULL,
    current_version BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP(6) NOT NULL,
    updated_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_pose_graph PRIMARY KEY (graph_id)
);
COMMENT ON COLUMN pose_graph.current_version IS '当前有效图版本；关键帧或 VALID 边写入时递增，REJECTED 回环不递增';

CREATE TABLE IF NOT EXISTS key_frame (
    id           VARCHAR(128) NOT NULL,
    graph_id     VARCHAR(128) NOT NULL,
    source       VARCHAR(128) NOT NULL,
    version      BIGINT       NOT NULL,
    pose_json    CLOB         NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_key_frame PRIMARY KEY (id)
);
COMMENT ON COLUMN key_frame.pose_json IS '关键帧位姿 [x(米), y(米), theta(弧度)] 的 JSON 文本';
COMMENT ON COLUMN key_frame.version IS '关键帧数据版本';
CREATE INDEX IF NOT EXISTS idx_key_frame_graph ON key_frame (graph_id);

CREATE TABLE IF NOT EXISTS edge (
    id               VARCHAR(128) NOT NULL,
    request_id       VARCHAR(128) NOT NULL,
    graph_id         VARCHAR(128) NOT NULL,
    source           VARCHAR(128) NOT NULL,
    version          BIGINT       NOT NULL,
    type             VARCHAR(16)  NOT NULL,
    from_frame_id    VARCHAR(128) NOT NULL,
    to_frame_id      VARCHAR(128) NOT NULL,
    measurement_json CLOB         NOT NULL,
    information_json CLOB,
    score            DOUBLE,
    residual         DOUBLE,
    status           VARCHAR(16)  NOT NULL,
    reject_reason    VARCHAR(512),
    content_hash     VARCHAR(128) NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_edge PRIMARY KEY (id),
    CONSTRAINT uq_edge_request_id UNIQUE (request_id)
);
COMMENT ON COLUMN edge.type IS '边类型：ODOMETRY 里程计边 / LOOP 回环约束';
COMMENT ON COLUMN edge.status IS '审计状态：VALID 进入有效图；REJECTED 阈值校验未通过仅留存审计';
COMMENT ON COLUMN edge.score IS '回环匹配分数(0~1，越高越可信)；里程计边为 NULL';
COMMENT ON COLUMN edge.residual IS '回环初始残差；里程计边为 NULL';
COMMENT ON COLUMN edge.information_json IS '信息矩阵(3x3 按行展开) JSON，用于协方差检查';
CREATE INDEX IF NOT EXISTS idx_edge_graph ON edge (graph_id, status);

CREATE TABLE IF NOT EXISTS optimize_task (
    task_id           VARCHAR(128) NOT NULL,
    graph_id          VARCHAR(128) NOT NULL,
    target_version    BIGINT       NOT NULL,
    status            VARCHAR(24)  NOT NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    failure_reason    CLOB,
    snapshot_json     CLOB,
    snapshot_summary  VARCHAR(512),
    result_version    BIGINT,
    created_at        TIMESTAMP(6) NOT NULL,
    updated_at        TIMESTAMP(6) NOT NULL,
    started_at        TIMESTAMP(6),
    finished_at       TIMESTAMP(6),
    CONSTRAINT pk_optimize_task PRIMARY KEY (task_id)
);
COMMENT ON COLUMN optimize_task.target_version IS '请求记录的目标图版本；合并任务取被合并请求中最高版本';
COMMENT ON COLUMN optimize_task.status IS 'PENDING 待执行/RUNNING 运行中/SUCCEEDED 成功/FAILED_RETRYABLE 可重试失败/SUPERSEDED 已被取代';
COMMENT ON COLUMN optimize_task.snapshot_json IS '执行时固化的输入快照 JSON；重试复用，不随新图变化';
CREATE INDEX IF NOT EXISTS idx_task_claim ON optimize_task (status, created_at);
CREATE INDEX IF NOT EXISTS idx_task_graph ON optimize_task (graph_id, status);

CREATE TABLE IF NOT EXISTS task_request (
    request_id         VARCHAR(128) NOT NULL,
    task_id            VARCHAR(128) NOT NULL,
    graph_id           VARCHAR(128) NOT NULL,
    target_version     BIGINT       NOT NULL,
    merged_into_task_id VARCHAR(128),
    created_at         TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_task_request PRIMARY KEY (request_id)
);
COMMENT ON COLUMN task_request.merged_into_task_id IS '请求被合并时指向承接任务 ID；未合并为 NULL';
CREATE INDEX IF NOT EXISTS idx_request_task ON task_request (task_id);

CREATE TABLE IF NOT EXISTS optimize_result (
    result_id      VARCHAR(128) NOT NULL,
    task_id        VARCHAR(128) NOT NULL,
    graph_id       VARCHAR(128) NOT NULL,
    graph_version  BIGINT       NOT NULL,
    frame_ids_json CLOB         NOT NULL,
    poses_json     CLOB         NOT NULL,
    cost           DOUBLE       NOT NULL,
    iterations     INT          NOT NULL,
    created_at     TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_optimize_result PRIMARY KEY (result_id),
    CONSTRAINT uq_result_task UNIQUE (task_id)
);
COMMENT ON COLUMN optimize_result.graph_version IS '结果基于的有效图版本；旧版本运行结果被标记 SUPERSEDED 且不得覆盖新版本结果';
CREATE INDEX IF NOT EXISTS idx_result_graph ON optimize_result (graph_id, graph_version);
