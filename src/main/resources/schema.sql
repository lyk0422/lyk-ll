-- ROS 2 风格节点注册中心建表脚本（H2 MODE=MySQL，内存库，JVM 退出后数据丢失）

CREATE TABLE IF NOT EXISTS ros_node (
    node_id    VARCHAR(128) PRIMARY KEY COMMENT '节点ID',
    namespace  VARCHAR(256) NOT NULL COMMENT '命名空间,以/开头',
    node_name  VARCHAR(128) NOT NULL COMMENT '节点名(不含命名空间)',
    state      VARCHAR(20)  NOT NULL COMMENT '生命周期状态:UNCONFIGURED/INACTIVE/ACTIVE/FINALIZED',
    version    BIGINT       NOT NULL COMMENT '乐观版本号,每次注册或状态迁移递增,初始为0',
    crashed    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否异常退出:0-正常,1-异常退出',
    created_at TIMESTAMP    NOT NULL COMMENT '注册时间(Asia/Shanghai展示)',
    updated_at TIMESTAMP    NOT NULL COMMENT '最近状态变更时间'
);

CREATE TABLE IF NOT EXISTS node_resource (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    node_id        VARCHAR(128) NOT NULL COMMENT '所属节点ID',
    category       VARCHAR(16)  NOT NULL COMMENT '资源类别:TOPIC/SERVICE/ACTION',
    fq_name        VARCHAR(512) NOT NULL COMMENT '全限定名称(命名空间+资源名),以/开头',
    interface_type VARCHAR(256) NOT NULL COMMENT '接口类型,如std_msgs/msg/String',
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT fk_resource_node FOREIGN KEY (node_id) REFERENCES ros_node (node_id)
);
CREATE INDEX IF NOT EXISTS idx_resource_node ON node_resource (node_id);

-- 仅存活节点对互斥类资源(SERVICE/ACTION)的占位声明,数据库级唯一约束保证并发注册安全
CREATE TABLE IF NOT EXISTS service_claim (
    category VARCHAR(16)  NOT NULL COMMENT '资源类别:SERVICE/ACTION',
    fq_name  VARCHAR(512) NOT NULL COMMENT '全限定名称',
    node_id  VARCHAR(128) NOT NULL COMMENT '当前持有该名称的存活节点ID',
    PRIMARY KEY (category, fq_name)
);

CREATE TABLE IF NOT EXISTS request_record (
    request_id   VARCHAR(128) PRIMARY KEY COMMENT '客户端幂等请求ID',
    node_id      VARCHAR(128) COMMENT '关联节点ID,可空',
    op           VARCHAR(24)  NOT NULL COMMENT '操作类型:REGISTER/DEREGISTER/TRANSITION/CRASH',
    request_hash VARCHAR(64)  NOT NULL COMMENT '规范化请求参数的SHA-256摘要',
    response     CLOB         NOT NULL COMMENT '首次成功响应JSON快照,重放时原样返回',
    created_at   TIMESTAMP    NOT NULL COMMENT '首次处理时间'
);

CREATE TABLE IF NOT EXISTS node_event (
    revision   BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '全局递增事件版本,订阅方按此顺序消费',
    node_id    VARCHAR(128) NOT NULL COMMENT '事件所属节点ID',
    event_type VARCHAR(32)  NOT NULL COMMENT '事件类型:REGISTERED/STATE_CHANGED/DEREGISTERED/CRASHED',
    payload    CLOB         NOT NULL COMMENT '事件负载JSON',
    confirmed  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已被任一订阅方确认:0-未确认,1-已确认',
    created_at TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_event_node ON node_event (node_id);

CREATE TABLE IF NOT EXISTS subscriber_cursor (
    subscriber_id VARCHAR(128) PRIMARY KEY COMMENT '订阅方ID',
    last_revision BIGINT       NOT NULL DEFAULT 0 COMMENT '已确认到的最大revision,只允许单调前进'
);

-- 节点检查点:供当前JVM内恢复节点状态、版本与已处理requestId
CREATE TABLE IF NOT EXISTS node_checkpoint (
    node_id          VARCHAR(128) PRIMARY KEY COMMENT '节点ID',
    state            VARCHAR(20)  NOT NULL COMMENT '检查点时生命周期状态',
    version          BIGINT       NOT NULL COMMENT '检查点时节点版本',
    resources_json   CLOB         NOT NULL COMMENT '检查点时资源声明JSON快照',
    processed_reqids CLOB         NOT NULL COMMENT '已处理requestId JSON数组,恢复后重放不得重复发布事件',
    saved_at         TIMESTAMP    NOT NULL COMMENT '检查点保存时间'
);
