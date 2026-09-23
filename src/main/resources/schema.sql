-- 刷新令牌轮换服务建表脚本（H2 MySQL 兼容模式）。
-- 仅保存令牌 SHA-256 散列、父子关系、状态与到期时间，不保存任何明文令牌。

CREATE TABLE IF NOT EXISTS token_family (
    id            VARCHAR(36)  NOT NULL,
    user_id       VARCHAR(128) NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL,
    revoked_at    TIMESTAMP(6),
    revoke_reason VARCHAR(32),
    CONSTRAINT pk_token_family PRIMARY KEY (id)
);
COMMENT ON TABLE token_family IS '令牌族（会话）：ACTIVE 可轮换，REVOKED 为终态';
COMMENT ON COLUMN token_family.status IS '族状态：ACTIVE / REVOKED';
COMMENT ON COLUMN token_family.revoked_at IS '撤销时间（UTC），未撤销为 NULL';
COMMENT ON COLUMN token_family.revoke_reason IS '撤销原因：LOGOUT / REPLAY_DETECTED，未撤销为 NULL';

CREATE TABLE IF NOT EXISTS refresh_token (
    token_hash  CHAR(64)     NOT NULL,
    family_id   VARCHAR(36)  NOT NULL,
    parent_hash CHAR(64),
    seq         INT          NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    client_id   VARCHAR(128) NOT NULL,
    issued_at   TIMESTAMP(6) NOT NULL,
    expires_at  TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6),
    CONSTRAINT pk_refresh_token PRIMARY KEY (token_hash),
    CONSTRAINT fk_refresh_token_family FOREIGN KEY (family_id) REFERENCES token_family (id),
    CONSTRAINT fk_refresh_token_parent FOREIGN KEY (parent_hash) REFERENCES refresh_token (token_hash),
    CONSTRAINT uq_refresh_token_family_seq UNIQUE (family_id, seq)
);
COMMENT ON TABLE refresh_token IS '刷新令牌：仅存 SHA-256 散列与父子关系，不存明文';
COMMENT ON COLUMN refresh_token.token_hash IS '明文令牌的 SHA-256 十六进制散列';
COMMENT ON COLUMN refresh_token.parent_hash IS '父令牌散列，根令牌为 NULL';
COMMENT ON COLUMN refresh_token.seq IS '族内代数，根令牌为 0';
COMMENT ON COLUMN refresh_token.status IS '令牌状态：ACTIVE / CONSUMED / REVOKED / EXPIRED';
COMMENT ON COLUMN refresh_token.expires_at IS '到期时间（UTC），now >= expiresAt 即失效';
COMMENT ON COLUMN refresh_token.consumed_at IS '被轮换消费时间（UTC），未消费为 NULL';

CREATE TABLE IF NOT EXISTS rotation_record (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    request_id  VARCHAR(128) NOT NULL,
    family_id   VARCHAR(36)  NOT NULL,
    parent_hash CHAR(64)     NOT NULL,
    client_id   VARCHAR(128) NOT NULL,
    child_hash  CHAR(64)     NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_rotation_record PRIMARY KEY (id),
    CONSTRAINT uq_rotation_request UNIQUE (request_id),
    CONSTRAINT uq_rotation_child UNIQUE (child_hash)
);
COMMENT ON TABLE rotation_record IS '轮换请求指纹：同请求键重放返回首次子令牌，改令牌或客户端冲突';
COMMENT ON COLUMN rotation_record.request_id IS '请求键，全局唯一；同键不同令牌/客户端视为冲突';

CREATE TABLE IF NOT EXISTS security_event (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(32)  NOT NULL,
    family_id  VARCHAR(36)  NOT NULL,
    token_hash CHAR(64),
    request_id VARCHAR(128),
    detail     VARCHAR(512),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_security_event PRIMARY KEY (id),
    CONSTRAINT uq_security_event_family_type UNIQUE (family_id, event_type)
);
COMMENT ON TABLE security_event IS '安全事件：每族每类事件唯一，终态事件只记录一次';
COMMENT ON COLUMN security_event.event_type IS '事件类型：REPLAY_DETECTED / LOGOUT';
COMMENT ON COLUMN security_event.request_id IS '触达事件的请求键，可能为 NULL';
