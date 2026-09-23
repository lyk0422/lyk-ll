-- ROS 参数风格配置中心建表脚本（H2 MODE=MySQL；命名规范兼容 MySQL）
-- 进程退出后内存库自动消失，无需持久化恢复。

-- 参数 Schema：类型、默认值、必填、动态/静态、依赖表达式
CREATE TABLE IF NOT EXISTS ros_param_schema (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    node            VARCHAR(128)  NOT NULL COMMENT '所属 ROS 节点名',
    group_name      VARCHAR(128)  NOT NULL COMMENT '参数组名',
    name            VARCHAR(128)  NOT NULL COMMENT '参数名（节点+组内唯一）',
    type            VARCHAR(16)   NOT NULL COMMENT '参数类型：STRING/INTEGER/DOUBLE/BOOLEAN',
    default_value   VARCHAR(1024) NULL     COMMENT '默认值字面量，读取时按 type 解析；NULL 表示无默认值',
    required_flag   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否必填：1 必填，候选快照不得缺失',
    dynamic_flag    TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否可热更新：1 动态，0 静态只能待重启',
    dependency_expr VARCHAR(1024) NULL     COMMENT '依赖表达式(SpEL)，基于整笔更新后的候选快照求值须为 true；NULL 无约束',
    UNIQUE (node, group_name, name)
);

-- 节点单调版本：每次成功热更新 +1
CREATE TABLE IF NOT EXISTS ros_node_version (
    node        VARCHAR(128) NOT NULL COMMENT '节点名',
    version     BIGINT       NOT NULL DEFAULT 0 COMMENT '当前单调版本号，初始为 0',
    updated_at  BIGINT       NOT NULL DEFAULT 0 COMMENT '最近更新时间（毫秒，Asia/Shanghai）',
    PRIMARY KEY (node)
);

-- 已提交参数值（动态参数热更新后的生效值；静态参数不会写入此表）
CREATE TABLE IF NOT EXISTS ros_param_value (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    node        VARCHAR(128)  NOT NULL COMMENT '节点名',
    group_name  VARCHAR(128)  NOT NULL COMMENT '参数组名',
    name        VARCHAR(128)  NOT NULL COMMENT '参数名',
    param_value VARCHAR(1024) NULL     COMMENT '已提交值的字符串形式，读取时按 Schema 类型还原；NULL 表示显式置空',
    version     BIGINT       NOT NULL DEFAULT 0 COMMENT '写入该行时的节点版本',
    UNIQUE (node, group_name, name)
);

-- 事务幂等记录：保存已完成事务的请求指纹与原始结果
CREATE TABLE IF NOT EXISTS ros_transaction (
    transaction_id      VARCHAR(128) NOT NULL COMMENT '调用方生成的幂等事务 ID',
    committed           TINYINT(1)   NOT NULL COMMENT '事务是否原子提交成功：1 成功，0 全量回滚',
    request_fingerprint VARCHAR(2048) NOT NULL COMMENT '请求参数指纹，同键异参返回 409',
    response_json       CLOB         NOT NULL COMMENT '原始响应 JSON，同键同参原样返回',
    created_at          BIGINT       NOT NULL COMMENT '完成时间（毫秒，Asia/Shanghai）',
    PRIMARY KEY (transaction_id)
);

-- 参数事件流：游标全库单调递增
CREATE TABLE IF NOT EXISTS ros_param_event (
    cursor          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '单调事件游标，从 1 开始',
    transaction_id  VARCHAR(128) NOT NULL COMMENT '产生事件的事务 ID',
    type            VARCHAR(32)  NOT NULL COMMENT '事件类型：COMMITTED 等',
    node            VARCHAR(128) NULL     COMMENT '主要关联节点；NULL 表示事务级汇总事件',
    payload         CLOB         NOT NULL COMMENT '事件内容 JSON',
    created_at      BIGINT       NOT NULL COMMENT '事件创建时间（毫秒，Asia/Shanghai）'
);

-- 订阅者 ACK 位置
CREATE TABLE IF NOT EXISTS ros_event_ack (
    subscriber_id VARCHAR(128) NOT NULL COMMENT '订阅者标识',
    cursor        BIGINT       NOT NULL DEFAULT 0 COMMENT '已确认的最大事件游标，重连只续传大于它的事件',
    updated_at    BIGINT       NOT NULL DEFAULT 0 COMMENT '最近确认时间（毫秒）',
    PRIMARY KEY (subscriber_id)
);

-- 待重启配置：静态参数变更只能落在这里，重启前不会生效
CREATE TABLE IF NOT EXISTS ros_pending_restart (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id  VARCHAR(128)  NOT NULL COMMENT '来源事务 ID',
    node            VARCHAR(128)  NOT NULL COMMENT '节点名',
    group_name      VARCHAR(128)  NOT NULL COMMENT '参数组名',
    name            VARCHAR(128)  NOT NULL COMMENT '静态参数名',
    param_value     VARCHAR(1024) NULL     COMMENT '待重启应用的值（字符串形式）；NULL 表示显式置空',
    created_at      BIGINT       NOT NULL COMMENT '记录时间（毫秒，Asia/Shanghai）',
    UNIQUE (node, group_name, name)
);
