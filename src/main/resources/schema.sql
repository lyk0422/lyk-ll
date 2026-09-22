-- ROS 2 风格节点注册中心建表脚本（H2 MODE=MySQL 与 MySQL 兼容语法）

-- 节点表：记录节点身份、生命周期状态与乐观版本号
CREATE TABLE IF NOT EXISTS ros_node (
    node_id        VARCHAR(128) NOT NULL COMMENT '节点唯一标识 nodeId',
    namespace      VARCHAR(256) NOT NULL COMMENT '节点命名空间，如 /robot',
    node_name      VARCHAR(128) NOT NULL COMMENT '节点名，不含命名空间',
    lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'UNCONFIGURED' COMMENT '生命周期状态：UNCONFIGURED/INACTIVE/ACTIVE/FINALIZED',
    version        BIGINT       NOT NULL DEFAULT 1 COMMENT '乐观版本号，每次合法迁移递增',
    alive          TINYINT      NOT NULL DEFAULT 1 COMMENT '是否存活：1=存活，0=异常退出已清理资源',
    registered_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间（Asia/Shanghai）',
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近状态变更时间（Asia/Shanghai）',
    PRIMARY KEY (node_id)
) COMMENT='ROS 2 模拟节点表';

-- 节点资源声明表：Topic/Service/Action
CREATE TABLE IF NOT EXISTS node_resource (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    node_id         VARCHAR(128) NOT NULL COMMENT '归属节点 nodeId',
    resource_category VARCHAR(16) NOT NULL COMMENT '资源类别：TOPIC/SERVICE/ACTION',
    fully_qualified_name VARCHAR(512) NOT NULL COMMENT '全限定资源名，如 /robot/cmd_vel',
    interface_type  VARCHAR(256) NOT NULL COMMENT '接口类型，如 std_msgs/msg/String',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '声明创建时间（Asia/Shanghai）',
    PRIMARY KEY (id),
    CONSTRAINT uk_resource_identity UNIQUE (node_id, resource_category, fully_qualified_name, interface_type),
    CONSTRAINT fk_resource_node FOREIGN KEY (node_id) REFERENCES ros_node (node_id)
) COMMENT='节点声明的 Topic/Service/Action 资源';

-- Service/Action 同名互斥在存活节点范围内由业务层校验（崩溃节点释放后名称可重新声明），
-- uk_resource_identity 已提供 (resource_category, fully_qualified_name) 前缀查询索引。

-- 幂等请求记录表
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(128) NOT NULL COMMENT '客户端提供的 requestId',
    request_kind  VARCHAR(32)  NOT NULL COMMENT '请求类别：REGISTER/DEREGISTER/TRANSITION/CRASH/CHECKPOINT_*',
    params_hash   VARCHAR(64)  NOT NULL COMMENT '归一化参数 SHA-256，异参重放据此判定',
    node_id       VARCHAR(128) NULL COMMENT '关联节点，无节点关联时为空',
    http_status   INT          NULL COMMENT '首次请求的 HTTP 状态码；NULL=同 requestId 请求仍在处理中（占坑）',
    response_body CLOB         NULL COMMENT '首次请求响应体 JSON，重放原样返回',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次请求时间（Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT='已处理 requestId 及首请求结果，用于重放与恢复';

-- 资源/生命周期变更事件表，revision 全局递增
CREATE TABLE IF NOT EXISTS resource_event (
    revision        BIGINT       NOT NULL COMMENT '全局递增事件版本号，订阅方按此消费',
    node_id         VARCHAR(128) NOT NULL COMMENT '事件所属节点',
    event_type      VARCHAR(32)  NOT NULL COMMENT '事件类型：RESOURCE_DECLARED/RESOURCE_RELEASED/NODE_CRASH_CLEANUP/LIFECYCLE_TRANSITION',
    resource_category VARCHAR(16) NULL COMMENT '资源类别，节点级事件为空',
    fully_qualified_name VARCHAR(512) NULL COMMENT '全限定资源名，节点级事件为空',
    interface_type  VARCHAR(256) NULL COMMENT '接口类型，节点级事件为空',
    detail          VARCHAR(512) NULL COMMENT '附加说明，如迁移方向或清理资源数',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件产生时间（Asia/Shanghai）',
    PRIMARY KEY (revision)
) COMMENT='资源与生命周期变更事件流';

-- 全局 revision 分配器，单表单行，事务内更新以保证连续递增；初值 0，首个事件 revision=1
CREATE TABLE IF NOT EXISTS revision_seq (
    id          INT NOT NULL DEFAULT 1 COMMENT '固定为 1 的单行标识',
    next_value  BIGINT NOT NULL DEFAULT 0 COMMENT '下一个事件 revision 的游标（先自增后取值）',
    PRIMARY KEY (id)
) COMMENT='事件 revision 序列表';

-- 检查点快照表（当前 JVM 内恢复使用）
CREATE TABLE IF NOT EXISTS registry_checkpoint (
    checkpoint_id VARCHAR(128) NOT NULL COMMENT '检查点标识',
    payload       CLOB         NOT NULL COMMENT '节点/资源/已处理 requestId/事件序列的 JSON 快照',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '检查点保存时间（Asia/Shanghai）',
    PRIMARY KEY (checkpoint_id)
) COMMENT='注册中心检查点快照';

-- 初值为 0：nextRevision() 先自增再读取，首个事件 revision 为 1
INSERT INTO revision_seq (id, next_value)
SELECT 1, 0
WHERE NOT EXISTS (SELECT 1 FROM revision_seq WHERE id = 1);
