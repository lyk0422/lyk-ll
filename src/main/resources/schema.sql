-- rosbridge WebSocket 网关状态表（H2 内存库，MODE=MySQL，仅 JVM 生命周期，进程退出不保留）
-- 时间字段一律为 UTC 纪元毫秒数。

CREATE TABLE IF NOT EXISTS ws_client (
    client_id        VARCHAR(128) NOT NULL,
    connected        TINYINT      NOT NULL,
    rate_limit_per_sec INT        NOT NULL,
    queue_cap        INT          NOT NULL,
    connected_at     BIGINT       NOT NULL,
    disconnected_at  BIGINT       NULL
);
ALTER TABLE ws_client ADD CONSTRAINT pk_ws_client PRIMARY KEY (client_id);
COMMENT ON TABLE ws_client IS 'WebSocket 客户端连接与限速/队列配置';
COMMENT ON COLUMN ws_client.client_id IS '客户端标识，重连时复用';
COMMENT ON COLUMN ws_client.connected IS '是否存在活跃连接：1=在线，0=断连宽限/离线';
COMMENT ON COLUMN ws_client.rate_limit_per_sec IS '该客户端发送速率上限，单位：条/秒';
COMMENT ON COLUMN ws_client.queue_cap IS '该客户端待发送队列上限，单位：条';
COMMENT ON COLUMN ws_client.connected_at IS '本次连接建立时间，UTC 纪元毫秒';
COMMENT ON COLUMN ws_client.disconnected_at IS '最近一次断连时间，UTC 纪元毫秒；NULL 表示从未断连';

CREATE TABLE IF NOT EXISTS shared_subscription (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    topic           VARCHAR(255) NOT NULL,
    msg_type        VARCHAR(255) NOT NULL,
    qos_key         VARCHAR(128) NOT NULL,
    ref_count       INT          NOT NULL,
    next_cursor     BIGINT       NOT NULL,
    upstream_active TINYINT      NOT NULL,
    grace_until     BIGINT       NULL,
    created_at      BIGINT       NOT NULL
);
ALTER TABLE shared_subscription ADD CONSTRAINT pk_shared_subscription PRIMARY KEY (id);
ALTER TABLE shared_subscription ADD CONSTRAINT uk_shared_subscription UNIQUE (topic, msg_type, qos_key);
COMMENT ON TABLE shared_subscription IS 'Topic+类型+QoS 维度的共享上游订阅与引用计数';
COMMENT ON COLUMN shared_subscription.ref_count IS '活跃客户端订阅引用计数；为 0 且过宽限期后释放上游';
COMMENT ON COLUMN shared_subscription.next_cursor IS '下一条共享消息的单调游标，已分配的最大游标 + 1';
COMMENT ON COLUMN shared_subscription.upstream_active IS '上游 ROS 订阅是否已建立：1=是，0=已释放';
COMMENT ON COLUMN shared_subscription.grace_until IS '宽限期截止时间，UTC 纪元毫秒；NULL 表示未在宽限中';

CREATE TABLE IF NOT EXISTS client_subscription (
    client_id        VARCHAR(128) NOT NULL,
    shared_id        BIGINT       NOT NULL,
    last_ack_cursor  BIGINT       NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    subscribed_at    BIGINT       NOT NULL,
    grace_until      BIGINT       NULL
);
ALTER TABLE client_subscription ADD CONSTRAINT pk_client_subscription PRIMARY KEY (client_id, shared_id);
COMMENT ON TABLE client_subscription IS '客户端到共享订阅的订阅关系与确认游标';
COMMENT ON COLUMN client_subscription.last_ack_cursor IS '该客户端已确认的最大单调游标；0 表示尚无确认';
COMMENT ON COLUMN client_subscription.status IS '状态：ACTIVE=活跃，GRACING=断连宽限中';

CREATE TABLE IF NOT EXISTS topic_message (
    shared_id   BIGINT      NOT NULL,
    cursor      BIGINT      NOT NULL,
    payload     CLOB        NOT NULL,
    created_at  BIGINT      NOT NULL
);
ALTER TABLE topic_message ADD CONSTRAINT pk_topic_message PRIMARY KEY (shared_id, cursor);
COMMENT ON TABLE topic_message IS '共享订阅的最近消息留存，用于同 JVM 断线重连续传，仅保留配置窗口内数据';
COMMENT ON COLUMN topic_message.cursor IS '共享订阅内单调递增游标，从 1 开始';

CREATE TABLE IF NOT EXISTS service_call (
    client_id     VARCHAR(128) NOT NULL,
    request_id    VARCHAR(128) NOT NULL,
    service_name  VARCHAR(255) NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    deadline      BIGINT       NOT NULL,
    result_json   CLOB         NULL,
    created_at    BIGINT       NOT NULL,
    completed_at  BIGINT       NULL
);
ALTER TABLE service_call ADD CONSTRAINT pk_service_call PRIMARY KEY (client_id, request_id);
COMMENT ON TABLE service_call IS 'Service 调用状态，CAS 保证超时与迟到结果只完成一次';
COMMENT ON COLUMN service_call.status IS 'PENDING=进行中，OK=成功，TIMEOUT=超时，ERROR=错误';
COMMENT ON COLUMN service_call.deadline IS '超时截止时间，UTC 纪元毫秒';

CREATE TABLE IF NOT EXISTS action_goal (
    client_id     VARCHAR(128) NOT NULL,
    goal_id       VARCHAR(128) NOT NULL,
    action_name   VARCHAR(255) NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    result_json   CLOB         NULL,
    created_at    BIGINT       NOT NULL,
    completed_at  BIGINT       NULL
);
ALTER TABLE action_goal ADD CONSTRAINT pk_action_goal PRIMARY KEY (client_id, goal_id);
COMMENT ON TABLE action_goal IS 'Action goal 状态，取消与最终结果通过 CAS 保持幂等';
COMMENT ON COLUMN action_goal.status IS 'ACTIVE=执行中，CANCELED=已取消，SUCCEEDED=成功，ABORTED=异常终止';
