-- rosbridge 网关状态表；运行于 H2 内存库（MODE=MySQL），进程退出后数据不保留。
-- 所有时间字段均为毫秒级 Unix 时间戳（UTC 时钟），空值表示对应事件尚未发生。

CREATE TABLE IF NOT EXISTS rb_client (
    client_id VARCHAR(128) NOT NULL PRIMARY KEY,
    rate_limit_per_second INT NOT NULL DEFAULT 0,
    send_queue_capacity INT NOT NULL DEFAULT 64,
    last_connect_at BIGINT NOT NULL,
    last_disconnect_at BIGINT
);
COMMENT ON TABLE rb_client IS '客户端连接配置与最近连接时间';
COMMENT ON COLUMN rb_client.client_id IS '客户端标识，握手查询参数 clientId';
COMMENT ON COLUMN rb_client.rate_limit_per_second IS 'Topic 消息速率上限（条/秒），0 表示不限速';
COMMENT ON COLUMN rb_client.send_queue_capacity IS '单客户端待确认发送队列上限（条）';
COMMENT ON COLUMN rb_client.last_connect_at IS '最近一次建立连接的毫秒时间戳';
COMMENT ON COLUMN rb_client.last_disconnect_at IS '最近一次断连毫秒时间戳，空值表示当前在线';

CREATE TABLE IF NOT EXISTS rb_upstream_subscription (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic VARCHAR(256) NOT NULL,
    msg_type VARCHAR(128) NOT NULL,
    qos_hash CHAR(64) NOT NULL,
    qos_json VARCHAR(1024) NOT NULL,
    ref_count INT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    CONSTRAINT uk_upstream_topic_type_qos UNIQUE (topic, msg_type, qos_hash)
);
COMMENT ON TABLE rb_upstream_subscription IS '共享上游订阅及引用计数，topic/类型/QoS 相同则复用同一行';
COMMENT ON COLUMN rb_upstream_subscription.topic IS 'ROS 2 Topic 名称';
COMMENT ON COLUMN rb_upstream_subscription.msg_type IS 'ROS 2 消息类型，如 std_msgs/msg/String';
COMMENT ON COLUMN rb_upstream_subscription.qos_hash IS '规范化 QoS 参数的 SHA-256 十六进制摘要';
COMMENT ON COLUMN rb_upstream_subscription.qos_json IS '规范化后的 QoS JSON 原文';
COMMENT ON COLUMN rb_upstream_subscription.ref_count IS '当前引用该上游的活跃客户端订阅数，归零即可释放上游资源';
COMMENT ON COLUMN rb_upstream_subscription.created_at IS '上游订阅创建毫秒时间戳';

CREATE TABLE IF NOT EXISTS rb_client_subscription (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id VARCHAR(128) NOT NULL,
    upstream_id BIGINT NOT NULL,
    active TINYINT NOT NULL DEFAULT 1,
    ack_cursor BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    CONSTRAINT uk_client_upstream UNIQUE (client_id, upstream_id),
    CONSTRAINT fk_cs_upstream FOREIGN KEY (upstream_id)
        REFERENCES rb_upstream_subscription (id)
);
COMMENT ON TABLE rb_client_subscription IS '客户端到共享上游订阅的引用及该客户端确认到的游标';
COMMENT ON COLUMN rb_client_subscription.client_id IS '客户端标识';
COMMENT ON COLUMN rb_client_subscription.upstream_id IS '关联 rb_upstream_subscription.id';
COMMENT ON COLUMN rb_client_subscription.active IS '是否有效：1 在订阅中（含断连宽限期），0 已取消';
COMMENT ON COLUMN rb_client_subscription.ack_cursor IS '客户端已 ACK 的最大单调游标；重连后仅投递大于该游标的消息';
COMMENT ON COLUMN rb_client_subscription.created_at IS '首次订阅毫秒时间戳';

CREATE TABLE IF NOT EXISTS rb_topic_message (
    cursor_value BIGINT NOT NULL PRIMARY KEY,
    upstream_id BIGINT NOT NULL,
    topic VARCHAR(256) NOT NULL,
    msg_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    created_at BIGINT NOT NULL
);
COMMENT ON TABLE rb_topic_message IS '共享订阅消息与网关单调游标，供断连重连续投';
COMMENT ON COLUMN rb_topic_message.cursor_value IS '网关全局单调递增游标，从 1 开始';
COMMENT ON COLUMN rb_topic_message.upstream_id IS '产生该消息的共享上游订阅 id';
COMMENT ON COLUMN rb_topic_message.topic IS 'ROS 2 Topic 名称（冗余便于排查）';
COMMENT ON COLUMN rb_topic_message.msg_type IS 'ROS 2 消息类型（冗余便于排查）';
COMMENT ON COLUMN rb_topic_message.payload IS '消息体 JSON 原文';
COMMENT ON COLUMN rb_topic_message.created_at IS '消息入库毫秒时间戳';
CREATE INDEX IF NOT EXISTS idx_tm_upstream_cursor ON rb_topic_message (upstream_id, cursor_value);

CREATE TABLE IF NOT EXISTS rb_service_call (
    request_id VARCHAR(128) NOT NULL PRIMARY KEY,
    client_id VARCHAR(128) NOT NULL,
    service_name VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(32),
    error_message VARCHAR(512),
    result_text TEXT,
    created_at BIGINT NOT NULL,
    completed_at BIGINT
);
COMMENT ON TABLE rb_service_call IS '带 requestId 的 Service 调用状态，保证完成/超时幂等';
COMMENT ON COLUMN rb_service_call.request_id IS '客户端提供的调用请求标识';
COMMENT ON COLUMN rb_service_call.client_id IS '发起调用的客户端标识';
COMMENT ON COLUMN rb_service_call.service_name IS 'ROS 2 Service 名称';
COMMENT ON COLUMN rb_service_call.status IS '调用状态：PENDING/OK/FAILED/TIMED_OUT';
COMMENT ON COLUMN rb_service_call.error_code IS '失败错误码，如 TIMEOUT；成功为空';
COMMENT ON COLUMN rb_service_call.error_message IS '失败原因文本';
COMMENT ON COLUMN rb_service_call.result_text IS '成功结果 JSON 原文';
COMMENT ON COLUMN rb_service_call.created_at IS '调用发起毫秒时间戳';
COMMENT ON COLUMN rb_service_call.completed_at IS '调用最终完成毫秒时间戳，空值表示未完成';

CREATE TABLE IF NOT EXISTS rb_action_goal (
    goal_id VARCHAR(128) NOT NULL PRIMARY KEY,
    client_id VARCHAR(128) NOT NULL,
    action_name VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_text TEXT,
    created_at BIGINT NOT NULL,
    completed_at BIGINT
);
COMMENT ON TABLE rb_action_goal IS 'Action goal 状态，取消与最终结果通过条件更新保持幂等';
COMMENT ON COLUMN rb_action_goal.goal_id IS '客户端提供的 goal 标识';
COMMENT ON COLUMN rb_action_goal.client_id IS '提交 goal 的客户端标识';
COMMENT ON COLUMN rb_action_goal.action_name IS 'ROS 2 Action 名称';
COMMENT ON COLUMN rb_action_goal.status IS 'goal 状态：PENDING/CANCELING/OK/CANCELED';
COMMENT ON COLUMN rb_action_goal.result_text IS '最终结果 JSON 原文（含取消时的空结果）';
COMMENT ON COLUMN rb_action_goal.created_at IS 'goal 提交毫秒时间戳';
COMMENT ON COLUMN rb_action_goal.completed_at IS 'goal 最终结束毫秒时间戳，空值表示未结束';

-- 网关全局共享单调游标号段，next_value 为下一条消息将分配的游标。
CREATE TABLE IF NOT EXISTS rb_cursor_sequence (
    id TINYINT NOT NULL PRIMARY KEY,
    next_value BIGINT NOT NULL
);
COMMENT ON TABLE rb_cursor_sequence IS '全局单调游标分配器，仅允许单行';
COMMENT ON COLUMN rb_cursor_sequence.next_value IS '下一条 Topic 消息将获得的游标值，从 1 开始';
INSERT INTO rb_cursor_sequence (id, next_value) SELECT 0, 1
WHERE NOT EXISTS (SELECT 1 FROM rb_cursor_sequence WHERE id = 0);
