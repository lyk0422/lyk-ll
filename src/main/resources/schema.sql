-- 多方签署流程服务建表脚本（H2 MySQL 兼容模式；时间列均为 UTC 时间戳）。
-- 数据仅保留在 JVM 生命周期内，文档与签名均为合成数据。

CREATE TABLE IF NOT EXISTS signing_package (
    id               VARCHAR(36)  NOT NULL COMMENT '签署包唯一标识（UUID）',
    status           VARCHAR(16)  NOT NULL COMMENT '流程状态：ACTIVE/COMPLETED/CANCELLED/EXPIRED',
    current_revision INT          NOT NULL COMMENT '当前文档修订号，从 1 开始递增',
    doc_sha256       VARCHAR(64)  NOT NULL COMMENT '当前修订文档的 SHA-256（十六进制小写）',
    current_step     INT          NOT NULL COMMENT '当前开放步骤序号，从 0 开始',
    expires_at       TIMESTAMP    NOT NULL COMMENT 'UTC 到期时间，到期后拒绝一切签名',
    version          INT            NOT NULL COMMENT '乐观锁版本号，每次状态变更递增',
    evidence_json    CLOB         NULL COMMENT '最终完成时冻结的证据包 JSON，未冻结为 NULL',
    evidence_hash    VARCHAR(64)  NULL COMMENT '证据包摘要 SHA-256，未冻结为 NULL',
    created_at       TIMESTAMP    NOT NULL COMMENT '创建时间（UTC）',
    updated_at       TIMESTAMP    NOT NULL COMMENT '最近变更时间（UTC）',
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS package_step (
    package_id VARCHAR(36)  NOT NULL COMMENT '所属签署包 ID',
    step_index INT          NOT NULL COMMENT '步骤序号，从 0 开始，按序开放',
    signer_id  VARCHAR(128) NOT NULL COMMENT '该步骤要求的签署人标识（同一步多人为并行签署）',
    PRIMARY KEY (package_id, step_index, signer_id)
);

CREATE TABLE IF NOT EXISTS signature (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    package_id      VARCHAR(36)  NOT NULL COMMENT '所属签署包 ID',
    revision        INT          NOT NULL COMMENT '签名所属文档修订号',
    step_index      INT          NOT NULL COMMENT '签名所属步骤序号',
    signer_id       VARCHAR(128) NOT NULL COMMENT '签署人标识',
    doc_sha256      VARCHAR(64)  NOT NULL COMMENT '签名时声明的文档摘要',
    signature_value VARCHAR(128) NOT NULL COMMENT '合成签名值（本地适配器可复现校验）',
    status          VARCHAR(16)  NOT NULL COMMENT '签名状态：VALID/INVALIDATED（修订后旧签名失效但保留历史）',
    created_at      TIMESTAMP    NOT NULL COMMENT '签名提交时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_signature_signer (package_id, revision, signer_id)
);

CREATE TABLE IF NOT EXISTS signing_event (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    package_id VARCHAR(36) NOT NULL COMMENT '所属签署包 ID',
    seq        INT         NOT NULL COMMENT '包内事件序号，从 1 开始连续递增',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型，如 PACKAGE_CREATED/SIGNATURE_SUBMITTED/STEP_COMPLETED/REVISION_CREATED/SIGNATURES_INVALIDATED/PACKAGE_CANCELLED/PACKAGE_EXPIRED/PACKAGE_COMPLETED/EVIDENCE_FROZEN',
    payload    CLOB        NOT NULL COMMENT '事件规范 JSON 负载（键序固定，用于哈希链）',
    prev_hash  VARCHAR(64) NOT NULL COMMENT '上一事件哈希，首事件为 64 个 0',
    event_hash VARCHAR(64) NOT NULL COMMENT 'SHA-256(prevHash || canonicalEvent)',
    created_at TIMESTAMP   NOT NULL COMMENT '事件时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_seq (package_id, seq)
);

CREATE TABLE IF NOT EXISTS request_log (
    request_id    VARCHAR(128) NOT NULL COMMENT '客户端请求键（幂等键）',
    package_id    VARCHAR(36)  NOT NULL COMMENT '目标签署包 ID',
    fingerprint   VARCHAR(64)  NOT NULL COMMENT '完整请求参数的 SHA-256 指纹',
    status_code   INT          NOT NULL COMMENT '首次处理结果的 HTTP 状态码',
    response_json CLOB         NOT NULL COMMENT '首次处理结果的响应体 JSON',
    created_at    TIMESTAMP    NOT NULL COMMENT '首次处理时间（UTC）',
    PRIMARY KEY (request_id)
);
