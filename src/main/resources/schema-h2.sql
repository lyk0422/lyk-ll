-- ROS 参数风格配置中心：仅在 H2(MODE=MySQL) 内存库执行的建表脚本。
-- 进程退出后数据不保留；所有表使用 CREATE TABLE IF NOT EXISTS 以支持重复初始化。

CREATE TABLE IF NOT EXISTS param_schema (
    node_id         VARCHAR(128)  NOT NULL,
    group_name      VARCHAR(128)  NOT NULL,
    param_key       VARCHAR(128)  NOT NULL,
    param_type      VARCHAR(16)   NOT NULL,
    default_value   VARCHAR(1024),
    required_flag   BOOLEAN       NOT NULL,
    dynamic_flag    BOOLEAN       NOT NULL,
    dependency_expr VARCHAR(1024),
    CONSTRAINT pk_param_schema PRIMARY KEY (node_id, group_name, param_key)
);
COMMENT ON COLUMN param_schema.node_id IS '节点 ID';
COMMENT ON COLUMN param_schema.group_name IS '参数组名（ROS 参数命名空间/分组）';
COMMENT ON COLUMN param_schema.param_key IS '参数名';
COMMENT ON COLUMN param_schema.param_type IS '标量类型：STRING/INT/DOUBLE/BOOL';
COMMENT ON COLUMN param_schema.default_value IS '默认值文本表示；NULL 表示无默认值';
COMMENT ON COLUMN param_schema.required_flag IS '是否必填；候选快照中必填参数不得为空';
COMMENT ON COLUMN param_schema.dynamic_flag IS 'true=动态参数可热更新；false=静态参数只能创建待重启配置';
COMMENT ON COLUMN param_schema.dependency_expr IS '依赖表达式；基于整笔更新后的候选快照求值，NULL 表示无依赖';

CREATE TABLE IF NOT EXISTS node_state (
    node_id        VARCHAR(128) NOT NULL,
    version        BIGINT       NOT NULL,
    CONSTRAINT pk_node_state PRIMARY KEY (node_id)
);
COMMENT ON COLUMN node_state.node_id IS '节点 ID';
COMMENT ON COLUMN node_state.version IS '节点已提交配置的单调版本号，初始为 0';

CREATE TABLE IF NOT EXISTS committed_param (
    node_id     VARCHAR(128)  NOT NULL,
    param_key   VARCHAR(128)  NOT NULL,
    param_value VARCHAR(1024),
    updated_at  TIMESTAMP     NOT NULL,
    CONSTRAINT pk_committed_param PRIMARY KEY (node_id, param_key)
);
COMMENT ON COLUMN committed_param.node_id IS '节点 ID';
COMMENT ON COLUMN committed_param.param_key IS '参数名';
COMMENT ON COLUMN committed_param.param_value IS '显式提交值文本；行存在即覆盖默认值';
COMMENT ON COLUMN committed_param.updated_at IS '最近提交时间（UTC）';

CREATE TABLE IF NOT EXISTS transaction_record (
    transaction_id VARCHAR(128)  NOT NULL,
    request_hash   VARCHAR(64)   NOT NULL,
    status         VARCHAR(16)   NOT NULL,
    response_json  CLOB          NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    CONSTRAINT pk_transaction_record PRIMARY KEY (transaction_id)
);
COMMENT ON COLUMN transaction_record.transaction_id IS '事务 ID（幂等键，唯一）';
COMMENT ON COLUMN transaction_record.request_hash IS '请求规范化内容 SHA-256；同 ID 不同哈希返回 409';
COMMENT ON COLUMN transaction_record.status IS '原事务状态：COMMITTED/ROLLED_BACK';
COMMENT ON COLUMN transaction_record.response_json IS '原响应 JSON，同键同参原样返回';
COMMENT ON COLUMN transaction_record.created_at IS '首次处理时间（UTC）';

CREATE TABLE IF NOT EXISTS param_event (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    transaction_id VARCHAR(128) NOT NULL,
    event_type     VARCHAR(48)  NOT NULL,
    payload_json   CLOB         NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT pk_param_event PRIMARY KEY (id)
);
COMMENT ON COLUMN param_event.id IS '单调递增事件游标';
COMMENT ON COLUMN param_event.transaction_id IS '产生事件的事务 ID';
COMMENT ON COLUMN param_event.event_type IS '事件类型，如 PARAMETERS_COMMITTED';
COMMENT ON COLUMN param_event.payload_json IS '事件负载（各节点新版本摘要 JSON）';
COMMENT ON COLUMN param_event.created_at IS '事件落库时间（UTC）';

CREATE TABLE IF NOT EXISTS subscription_ack (
    subscriber_id VARCHAR(128) NOT NULL,
    ack_cursor    BIGINT       NOT NULL,
    CONSTRAINT pk_subscription_ack PRIMARY KEY (subscriber_id)
);
COMMENT ON COLUMN subscription_ack.subscriber_id IS '订阅者 ID';
COMMENT ON COLUMN subscription_ack.ack_cursor IS '已确认事件游标；重连只续传严格大于该游标的事件';

CREATE TABLE IF NOT EXISTS pending_reboot (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    node_id        VARCHAR(128) NOT NULL,
    param_key      VARCHAR(128) NOT NULL,
    new_value      VARCHAR(1024) NOT NULL,
    transaction_id VARCHAR(128) NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT pk_pending_reboot PRIMARY KEY (id),
    CONSTRAINT uq_pending_reboot_tx UNIQUE (transaction_id, node_id, param_key)
);
COMMENT ON COLUMN pending_reboot.id IS '自增主键';
COMMENT ON COLUMN pending_reboot.node_id IS '节点 ID';
COMMENT ON COLUMN pending_reboot.param_key IS '被修改的静态参数名';
COMMENT ON COLUMN pending_reboot.new_value IS '待重启后应用的新值文本';
COMMENT ON COLUMN pending_reboot.transaction_id IS '创建待重启配置的请求 ID（幂等键）';
COMMENT ON COLUMN pending_reboot.created_at IS '创建时间（UTC）';
