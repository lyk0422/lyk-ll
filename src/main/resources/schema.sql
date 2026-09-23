-- SLAM 位姿图后端 H2（MODE=MySQL）建表脚本；进程退出后内存数据不保留。

-- 图版本元数据：单行表，行锁串行化图写入与任务提交。current_version 单位为有效写入次数，初始 0。
CREATE TABLE IF NOT EXISTS graph_meta (
    id INT PRIMARY KEY,
    current_version INT NOT NULL
);
-- 幂等初始化：命名内存库复用时不覆盖已有版本
INSERT INTO graph_meta (id, current_version)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM graph_meta WHERE id = 1);

-- 关键帧表
CREATE TABLE IF NOT EXISTS keyframes (
    id               VARCHAR(64)  PRIMARY KEY,
    source           VARCHAR(64)  NOT NULL,
    version          INT          NOT NULL,
    x                DOUBLE       NOT NULL,
    y                DOUBLE       NOT NULL,
    theta            DOUBLE       NOT NULL,
    measurement_json CLOB         NOT NULL,
    request_id       VARCHAR(96)  NOT NULL UNIQUE,
    content_hash     VARCHAR(64)  NOT NULL,
    graph_version    INT          NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL
);

-- 图边表（VALID 有效边 + REJECTED 回环审计记录）
CREATE TABLE IF NOT EXISTS edges (
    id               VARCHAR(64)  PRIMARY KEY,
    type             VARCHAR(16)  NOT NULL,
    source           VARCHAR(64)  NOT NULL,
    version          INT          NOT NULL,
    from_keyframe_id VARCHAR(64)  NOT NULL,
    to_keyframe_id   VARCHAR(64)  NOT NULL,
    dx               DOUBLE       NOT NULL,
    dy               DOUBLE       NOT NULL,
    dtheta           DOUBLE       NOT NULL,
    covariance_json  CLOB         NOT NULL,
    score            DOUBLE       NULL,
    residual         DOUBLE       NULL,
    request_id       VARCHAR(96)  NOT NULL UNIQUE,
    content_hash     VARCHAR(64)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    reject_reason    VARCHAR(1024) NULL,
    -- 有效边为写入后的图版本；REJECTED 回环固定为 0，表示不触发图版本递增
    graph_version    INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_edges_from_kf FOREIGN KEY (from_keyframe_id) REFERENCES keyframes (id),
    CONSTRAINT fk_edges_to_kf   FOREIGN KEY (to_keyframe_id)   REFERENCES keyframes (id)
);

-- 优化任务表
CREATE TABLE IF NOT EXISTS opt_tasks (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id           VARCHAR(96)  NOT NULL UNIQUE,
    target_graph_version INT          NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    snapshot_json        CLOB         NULL,
    snapshot_digest      VARCHAR(64)  NULL,
    merged_into_id       BIGINT       NULL,
    retry_count          INT          NOT NULL DEFAULT 0,
    failure_reason       VARCHAR(2048) NULL,
    created_at           TIMESTAMP(6) NOT NULL,
    started_at           TIMESTAMP(6) NULL,
    finished_at          TIMESTAMP(6) NULL
);

-- 优化结果表：每个图版本至多一条结果，旧任务不得覆盖更新版本结果
CREATE TABLE IF NOT EXISTS opt_results (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id       BIGINT       NOT NULL,
    graph_version INT          NOT NULL UNIQUE,
    poses_json    CLOB         NOT NULL,
    final_cost    DOUBLE       NOT NULL,
    iterations    INT          NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_results_task FOREIGN KEY (task_id) REFERENCES opt_tasks (id)
);
