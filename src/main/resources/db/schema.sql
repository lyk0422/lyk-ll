-- rosbridge WebSocket 网关状态表（H2 内存库，MODE=MySQL；进程退出后数据不保留）
CREATE TABLE IF NOT EXISTS ws_connection (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id       VARCHAR(128) NOT NULL,                  -- 客户端业务标识，重连时复用
    session_id      VARCHAR(64)  NOT NULL,                  -- 每次 WebSocket 连接的唯一标识
    status          VARCHAR(16)  NOT NULL,                  -- ACTIVE 连接中；CLOSED 已断开
    connected_at    BIGINT       NOT NULL,                  -- 建立连接时间（epoch 毫秒，Asia/Shanghai 时区展示）
    disconnected_at BIGINT,                                 -- 断开时间（epoch 毫秒）；NULL 表示仍在线
    CONSTRAINT uk_ws_connection_session UNIQUE (session_id)
);

CREATE TABLE IF NOT EXISTS subscription_ref (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic        VARCHAR(255) NOT NULL,                     -- ROS 2 Topic 名称
    message_type VARCHAR(255) NOT NULL,                     -- 消息类型，如 std_msgs/String
    qos          VARCHAR(128) NOT NULL,                     -- QoS 配置 JSON 串；topic+type+qos 决定上游订阅复用粒度
    refcount     INT          NOT NULL DEFAULT 0,           -- 活跃客户端引用数；归零后进入宽限期，宽限结束释放上游
    grace_until  BIGINT,                                    -- 延迟释放截止时间（epoch 毫秒）；NULL 表示未进入宽限期
    CONSTRAINT uk_subscription_ref_key UNIQUE (topic, message_type, qos)
);

CREATE TABLE IF NOT EXISTS client_subscription (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id    VARCHAR(128) NOT NULL,                     -- 订阅所属客户端
    topic        VARCHAR(255) NOT NULL,                     -- ROS 2 Topic 名称
    message_type VARCHAR(255) NOT NULL,                     -- 消息类型
    qos          VARCHAR(128) NOT NULL,                     -- QoS 配置 JSON 串
    session_id   VARCHAR(64)  NOT NULL,                     -- 发起订阅时的连接会话
    created_at   BIGINT       NOT NULL,                     -- 首次订阅时间（epoch 毫秒）
    CONSTRAINT uk_client_subscription UNIQUE (client_id, topic, message_type, qos)
);

CREATE TABLE IF NOT EXISTS subscription_cursor (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic        VARCHAR(255) NOT NULL,                     -- Topic 名称
    message_type VARCHAR(255) NOT NULL,                     -- 消息类型
    qos          VARCHAR(128) NOT NULL,                     -- QoS 配置 JSON 串
    last_cursor  BIGINT       NOT NULL DEFAULT 0,           -- 该共享订阅已分配的最大单调游标（从 1 开始递增）
    CONSTRAINT uk_subscription_cursor_key UNIQUE (topic, message_type, qos)
);

CREATE TABLE IF NOT EXISTS client_cursor (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id   VARCHAR(128) NOT NULL,                      -- 客户端标识
    topic       VARCHAR(255) NOT NULL,                      -- Topic 名称
    message_type VARCHAR(255) NOT NULL,                     -- 消息类型
    qos         VARCHAR(128) NOT NULL,                      -- QoS 配置 JSON 串
    ack_cursor  BIGINT       NOT NULL DEFAULT 0,            -- 客户端已 ACK 的最大游标；重连只投递大于该值的消息
    updated_at  BIGINT       NOT NULL,                      -- 最近 ACK 时间（epoch 毫秒）
    CONSTRAINT uk_client_cursor_key UNIQUE (client_id, topic, message_type, qos)
);

CREATE TABLE IF NOT EXISTS service_call (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id    VARCHAR(128) NOT NULL,                     -- 调用方客户端
    request_id   VARCHAR(128) NOT NULL,                     -- 客户端提供的请求幂等键
    service      VARCHAR(255) NOT NULL,                     -- ROS 2 Service 名称
    status       VARCHAR(16)  NOT NULL,                     -- PENDING 进行中；COMPLETED 成功；TIMEOUT 超时；FAILED 失败
    result_json  CLOB,                                       -- 成功结果 JSON；未完成或失败时为 NULL
    error_msg    VARCHAR(1024),                             -- 失败原因；其余状态为 NULL
    created_at   BIGINT       NOT NULL,                     -- 发起时间（epoch 毫秒）
    completed_at BIGINT,                                    -- 完成时间（epoch 毫秒）；NULL 表示未完成
    CONSTRAINT uk_service_call_request UNIQUE (client_id, request_id)
);

CREATE TABLE IF NOT EXISTS action_goal (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id    VARCHAR(128) NOT NULL,                     -- 提交方客户端
    goal_id      VARCHAR(128) NOT NULL,                     -- 客户端提供的 Goal 幂等键
    action       VARCHAR(255) NOT NULL,                     -- ROS 2 Action 名称
    status       VARCHAR(16)  NOT NULL,                     -- PENDING 执行中；CANCELLED 已取消；SUCCEEDED 成功；FAILED 失败
    result_json  CLOB,                                       -- 最终结果 JSON；终态前为 NULL
    error_msg    VARCHAR(1024),                             -- 失败原因；其余状态为 NULL
    created_at   BIGINT       NOT NULL,                     -- 提交时间（epoch 毫秒）
    completed_at BIGINT,                                    -- 终态时间（epoch 毫秒）；NULL 表示未结束
    CONSTRAINT uk_action_goal_key UNIQUE (client_id, goal_id)
);
