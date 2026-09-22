-- ROS2 Launch 风格编排器 H2（MODE=MySQL）建表脚本
-- 数据仅保留在 JVM 生命周期内；每次启动嵌入式 H2 时自动执行。

CREATE TABLE IF NOT EXISTS launch_plan (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id         VARCHAR(64)  NOT NULL,
    version         VARCHAR(32)  NOT NULL,
    request_id      VARCHAR(128) NOT NULL,
    plan_name       VARCHAR(255),
    status          VARCHAR(32)  NOT NULL,
    payload_hash    VARCHAR(64),
    cancel_requested TINYINT     NOT NULL DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    CONSTRAINT uk_plan_id UNIQUE (plan_id),
    CONSTRAINT uk_request_id UNIQUE (request_id)
);
COMMENT ON COLUMN launch_plan.status IS '计划状态：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED';
COMMENT ON COLUMN launch_plan.cancel_requested IS '是否已请求取消：0-否 1-是，取消幂等标记';

CREATE TABLE IF NOT EXISTS plan_node (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id                  VARCHAR(64)  NOT NULL,
    node_id                  VARCHAR(64)  NOT NULL,
    node_name                VARCHAR(255),
    order_index              INT          NOT NULL,
    launch_delay_ms          BIGINT       NOT NULL DEFAULT 0,
    simulate_start_failure   TINYINT      NOT NULL DEFAULT 0,
    health_should_fail       TINYINT      NOT NULL DEFAULT 0,
    health_message           VARCHAR(512),
    compensation             VARCHAR(512),
    simulate_comp_failure    TINYINT      NOT NULL DEFAULT 0,
    state                    VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    compensated               TINYINT      NOT NULL DEFAULT 0,
    failure_reason           VARCHAR(512),
    health_status            VARCHAR(32),
    started_at               DATETIME(3),
    finished_at              DATETIME(3),
    CONSTRAINT uk_plan_node UNIQUE (plan_id, node_id)
);
COMMENT ON COLUMN plan_node.launch_delay_ms IS '启动前延迟，单位毫秒';
COMMENT ON COLUMN plan_node.state IS '节点状态：PENDING/STARTED/SUCCESS/FAILED/SKIPPED/COMPENSATED/CANCELLED';
COMMENT ON COLUMN plan_node.compensated IS '补偿是否已执行：0-未执行 1-已执行，保证补偿仅一次';
COMMENT ON COLUMN plan_node.health_status IS '最近一次健康检查结果：PASSED/FAILED，NULL 表示尚未检查';

CREATE TABLE IF NOT EXISTS node_param (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id   VARCHAR(64) NOT NULL,
    node_id   VARCHAR(64) NOT NULL,
    param_key VARCHAR(128) NOT NULL,
    param_value VARCHAR(1024)
);

CREATE TABLE IF NOT EXISTS node_dependency (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id     VARCHAR(64) NOT NULL,
    node_id     VARCHAR(64) NOT NULL,
    depends_on  VARCHAR(64) NOT NULL,
    dep_kind    VARCHAR(16) NOT NULL
);
COMMENT ON COLUMN node_dependency.dep_kind IS '依赖来源：EXPLICIT-显式依赖 CONDITION-条件谓词引用（隐式依赖）';

CREATE TABLE IF NOT EXISTS node_condition (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id        VARCHAR(64) NOT NULL,
    node_id        VARCHAR(64) NOT NULL,
    predicate      VARCHAR(32) NOT NULL,
    ref_node_id    VARCHAR(64) NOT NULL,
    param_name     VARCHAR(128),
    expected_value VARCHAR(256)
);
COMMENT ON COLUMN node_condition.predicate IS '条件谓词：NODE_SUCCESS/NODE_FAILED/PARAM_EQUALS';

CREATE TABLE IF NOT EXISTS plan_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id    VARCHAR(64)  NOT NULL,
    version    VARCHAR(32)  NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    node_id    VARCHAR(64),
    event_type VARCHAR(48)  NOT NULL,
    detail     VARCHAR(2048),
    event_time DATETIME(3)   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_plan_event ON plan_event (plan_id, id);
COMMENT ON COLUMN plan_event.node_id IS '事件关联节点，NULL 表示计划级事件';
