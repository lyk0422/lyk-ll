-- ROS 2 Launch 风格依赖编排器建表脚本（H2 MySQL 兼容模式）。
-- 数据仅保留在 JVM 生命周期内，进程退出后丢失。

CREATE TABLE IF NOT EXISTS launch_plan (
    plan_id VARCHAR(64) NOT NULL PRIMARY KEY,
    plan_version INT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE launch_plan IS '启动计划主表，记录一次提交的编排计划';
COMMENT ON COLUMN launch_plan.plan_id IS '计划标识，由调用方提供，全局唯一';
COMMENT ON COLUMN launch_plan.plan_version IS '计划版本号，由调用方提供';
COMMENT ON COLUMN launch_plan.request_id IS '幂等请求标识，同一 requestId 同参提交幂等';
COMMENT ON COLUMN launch_plan.payload_hash IS '计划内容 SHA-256 摘要，用于识别同 requestId 异参冲突';
COMMENT ON COLUMN launch_plan.status IS '计划状态：READY/RUNNING/SUCCEEDED/FAILED/CANCELLED';
COMMENT ON COLUMN launch_plan.created_at IS '创建时间（应用时钟，Asia/Shanghai）';
COMMENT ON COLUMN launch_plan.updated_at IS '最近更新时间（应用时钟，Asia/Shanghai）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_launch_plan_request ON launch_plan (request_id);

CREATE TABLE IF NOT EXISTS launch_node (
    plan_id VARCHAR(64) NOT NULL,
    node_key VARCHAR(64) NOT NULL,
    params_json VARCHAR(2048) NULL,
    delay_millis BIGINT NOT NULL DEFAULT 0,
    condition_ref VARCHAR(64) NULL,
    condition_expect VARCHAR(16) NULL,
    deps_json VARCHAR(1024) NOT NULL,
    health_check VARCHAR(8) NOT NULL,
    compensation VARCHAR(128) NULL,
    state VARCHAR(16) NOT NULL,
    compensated BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (plan_id, node_key)
);
COMMENT ON TABLE launch_node IS '计划节点表，记录节点规格与运行时状态';
COMMENT ON COLUMN launch_node.plan_id IS '所属计划标识';
COMMENT ON COLUMN launch_node.node_key IS '节点标识，计划内唯一';
COMMENT ON COLUMN launch_node.params_json IS '节点参数 JSON；NULL 表示无参数';
COMMENT ON COLUMN launch_node.delay_millis IS '模拟启动延迟，单位毫秒';
COMMENT ON COLUMN launch_node.condition_ref IS '条件谓词引用的节点标识；NULL 表示无条件，引用构成隐式依赖边';
COMMENT ON COLUMN launch_node.condition_expect IS '条件期望的被引用节点状态，如 HEALTHY';
COMMENT ON COLUMN launch_node.deps_json IS '显式依赖节点标识列表 JSON';
COMMENT ON COLUMN launch_node.health_check IS '合成健康检查结果：PASS/FAIL';
COMMENT ON COLUMN launch_node.compensation IS '补偿/回滚动作名；NULL 表示无补偿';
COMMENT ON COLUMN launch_node.state IS '节点状态：PENDING/RUNNING/HEALTHY/FAILED/BLOCKED/SKIPPED/COMPENSATED/CANCELLED';
COMMENT ON COLUMN launch_node.compensated IS '补偿是否已执行，保证补偿只执行一次';

CREATE TABLE IF NOT EXISTS launch_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    node_key VARCHAR(64) NULL,
    event_type VARCHAR(40) NOT NULL,
    detail VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE launch_event IS '编排事件日志，记录计划与节点的状态迁移及补偿执行';
COMMENT ON COLUMN launch_event.id IS '事件自增主键';
COMMENT ON COLUMN launch_event.plan_id IS '所属计划标识';
COMMENT ON COLUMN launch_event.node_key IS '关联节点标识；NULL 表示计划级事件';
COMMENT ON COLUMN launch_event.event_type IS '事件类型，如 NODE_STARTED/COMPENSATION_EXECUTED';
COMMENT ON COLUMN launch_event.detail IS '事件补充说明；NULL 表示无';
COMMENT ON COLUMN launch_event.created_at IS '事件时间（应用时钟，Asia/Shanghai）';

CREATE INDEX IF NOT EXISTS idx_launch_event_plan ON launch_event (plan_id, id);
