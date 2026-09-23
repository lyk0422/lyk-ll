-- ROS 参数风格配置中心建表脚本（H2 MySQL 兼容模式）

-- 参数 Schema：定义类型、默认值、必填、动态/静态属性与依赖表达式
CREATE TABLE IF NOT EXISTS param_schema (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    node          VARCHAR(128) NOT NULL COMMENT '节点名（ROS 2 Node）',
    group_name    VARCHAR(128) NOT NULL COMMENT '参数组名',
    name          VARCHAR(128) NOT NULL COMMENT '参数名（节点内唯一）',
    type          VARCHAR(16)  NOT NULL COMMENT '参数类型：STRING/INTEGER/DOUBLE/BOOLEAN',
    required_flag BOOLEAN      NOT NULL COMMENT '是否必填',
    dynamic_flag  BOOLEAN      NOT NULL COMMENT '是否可热更新；false 为静态参数',
    default_value CLOB         NULL COMMENT '默认值 JSON 字面量；NULL 表示无默认值',
    depends_on    CLOB         NULL COMMENT '依赖表达式；基于整笔更新后的候选快照求值；NULL 表示无约束',
    CONSTRAINT ux_param_schema UNIQUE (node, name)
);

-- 节点配置单调版本
CREATE TABLE IF NOT EXISTS node_config_version (
    node          VARCHAR(128) PRIMARY KEY COMMENT '节点名',
    version       BIGINT NOT NULL COMMENT '当前已提交配置的单调版本，初始为 0'
);

-- 参数值：source=COMMITTED 为已提交生效值，PENDING 为待重启的静态参数值
CREATE TABLE IF NOT EXISTS param_value (
    node          VARCHAR(128) NOT NULL COMMENT '节点名',
    name          VARCHAR(128) NOT NULL COMMENT '参数名',
    source        VARCHAR(16)  NOT NULL COMMENT '值来源：COMMITTED 已提交 / PENDING 待重启',
    value_text    CLOB         NULL COMMENT '参数值 JSON 文本；NULL 表示显式清空',
    PRIMARY KEY (node, name, source)
);

-- 幂等事务记录
CREATE TABLE IF NOT EXISTS transaction_record (
    transaction_id VARCHAR(128) PRIMARY KEY COMMENT '调用方生成的幂等事务键',
    request_hash   VARCHAR(64)  NOT NULL COMMENT '请求体规范化哈希；同键异参判定为 409',
    status         VARCHAR(16)  NOT NULL COMMENT '处理状态：PROCESSING 处理中 / COMMITTED 已提交 / REJECTED 未提交（失败）',
    response_text  CLOB         NULL COMMENT '终态处理结果 JSON，重复请求原样返回；处理中为 NULL',
    created_at     BIGINT       NOT NULL COMMENT '创建时间（毫秒，Asia/Shanghai 展示）',
    updated_at     BIGINT       NOT NULL COMMENT '最近状态变更时间（毫秒）'
);

-- 配置事件流：单调游标，仅原子提交成功的事务写入一条
CREATE TABLE IF NOT EXISTS config_event (
    cursor         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '单调事件游标',
    transaction_id VARCHAR(128) NOT NULL COMMENT '对应的事务 ID',
    event_type     VARCHAR(32)  NOT NULL COMMENT '事件类型，如 CONFIG_COMMITTED',
    payload        CLOB         NOT NULL COMMENT '事件内容 JSON（各节点新版本与变更）',
    created_at     BIGINT       NOT NULL COMMENT '事件时间（毫秒）'
);

-- 订阅者 ACK 位置
CREATE TABLE IF NOT EXISTS subscriber_cursor (
    subscriber_id  VARCHAR(128) PRIMARY KEY COMMENT '订阅者标识',
    acked_cursor   BIGINT NOT NULL COMMENT '已确认的最大事件游标；重连只续传大于该游标的事件'
);
