-- 刷新令牌轮换与重放遏制建表脚本（H2 MODE=MySQL，兼容 MySQL 语法子集）。

-- 令牌族：一次会话一条
CREATE TABLE IF NOT EXISTS token_family (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    family_id     VARCHAR(64)  NOT NULL COMMENT '对外暴露的令牌族标识',
    user_id       VARCHAR(128) NOT NULL COMMENT '合成用户标识',
    client_id     VARCHAR(128) NOT NULL COMMENT '绑定的客户端标识',
    status        VARCHAR(16)  NOT NULL COMMENT '族状态：ACTIVE 可轮换；REVOKED 已撤销',
    created_at    BIGINT       NOT NULL COMMENT '创建时间，epoch 毫秒，UTC',
    updated_at    BIGINT       NOT NULL COMMENT '最近更新时间，epoch 毫秒，UTC',
    revoked_at    BIGINT       NULL     COMMENT '撤销时间，epoch 毫秒，UTC；NULL 表示未撤销',
    revoke_reason VARCHAR(32)  NULL     COMMENT '撤销原因：REPLAY_DETECTED 重放检测；LOGOUT 登出；NULL 未撤销',
    CONSTRAINT uk_family_id UNIQUE (family_id)
);

-- 刷新令牌：只保存 SHA-256、父子关系、状态、到期时间，不保存明文
CREATE TABLE IF NOT EXISTS refresh_token (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    family_id    VARCHAR(64)  NOT NULL COMMENT '所属令牌族标识',
    token_hash   VARCHAR(64)  NOT NULL COMMENT '令牌明文的 SHA-256 十六进制摘要',
    parent_hash  VARCHAR(64)  NULL     COMMENT '父令牌 SHA-256；族内首个令牌为 NULL',
    sequence_no  INT          NOT NULL COMMENT '族内序号，首个令牌为 0，每轮换一次加 1',
    status       VARCHAR(16)  NOT NULL COMMENT '令牌状态：ACTIVE 未消费；CONSUMED 已消费；EXPIRED 已到期；REVOKED 随族撤销',
    client_id    VARCHAR(128) NOT NULL COMMENT '签发时绑定的客户端标识',
    expires_at   BIGINT       NOT NULL COMMENT '到期时间，epoch 毫秒，UTC；now >= 该值即失效',
    consumed_at  BIGINT       NULL     COMMENT '消费时间，epoch 毫秒，UTC；NULL 表示未消费',
    created_at   BIGINT       NOT NULL COMMENT '创建时间，epoch 毫秒，UTC',
    CONSTRAINT uk_token_hash UNIQUE (token_hash)
);
CREATE INDEX IF NOT EXISTS idx_token_family ON refresh_token (family_id);

-- 轮换请求记录：支撑安全重试幂等与请求指纹冲突检测
CREATE TABLE IF NOT EXISTS rotation_request (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    family_id            VARCHAR(64)  NOT NULL COMMENT '所属令牌族标识',
    request_id           VARCHAR(128) NOT NULL COMMENT '请求键，客户端生成的幂等键',
    client_id            VARCHAR(128) NOT NULL COMMENT '请求携带的客户端标识',
    presented_token_hash VARCHAR(64)  NOT NULL COMMENT '请求携带的当前令牌 SHA-256',
    request_fingerprint  VARCHAR(64)  NOT NULL COMMENT '请求指纹：族+请求键+客户端+令牌哈希的 SHA-256',
    child_token_hash     VARCHAR(64)  NOT NULL COMMENT '本次轮换产生的子令牌 SHA-256',
    created_at           BIGINT       NOT NULL COMMENT '记录时间，epoch 毫秒，UTC',
    CONSTRAINT uk_family_request UNIQUE (family_id, request_id)
);

-- 安全事件：每个族至多一条 REPLAY_DETECTED
CREATE TABLE IF NOT EXISTS security_event (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    family_id           VARCHAR(64)  NOT NULL COMMENT '所属令牌族标识',
    event_type          VARCHAR(32)  NOT NULL COMMENT '事件类型，当前为 REPLAY_DETECTED',
    request_id          VARCHAR(128) NULL     COMMENT '触发事件的请求键',
    token_hash          VARCHAR(64)  NOT NULL COMMENT '触发事件的令牌 SHA-256',
    request_fingerprint VARCHAR(64)  NOT NULL COMMENT '请求指纹：族+请求键+客户端+令牌哈希的 SHA-256',
    detail              VARCHAR(512) NULL     COMMENT '事件补充说明',
    created_at          BIGINT       NOT NULL COMMENT '事件时间，epoch 毫秒，UTC',
    CONSTRAINT uk_family_event UNIQUE (family_id, event_type)
);
CREATE INDEX IF NOT EXISTS idx_event_family ON security_event (family_id);
