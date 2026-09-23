-- 多方签署流程本地 H2（MODE=MySQL）建表脚本；全部表使用 IF NOT EXISTS，可重复执行。
-- 时间列统一存储 UTC 时间；文档摘要、签名、事件哈希均为小写十六进制 SHA-256。

CREATE TABLE IF NOT EXISTS signing_package (
    id               VARCHAR(36)  NOT NULL,
    current_revision INT          NOT NULL,
    document_hash    CHAR(64)     NOT NULL,
    expires_at       TIMESTAMP(6) NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    frozen_at        TIMESTAMP(6) NULL,
    evidence_digest  CHAR(64)     NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_signing_package PRIMARY KEY (id)
);
COMMENT ON TABLE signing_package IS '签署包主表：记录当前修订号、当前文档摘要、UTC 到期时间与流程状态';
COMMENT ON COLUMN signing_package.id IS '签署包 ID（UUID）';
COMMENT ON COLUMN signing_package.current_revision IS '当前修订号，从 1 开始，每次修订 +1';
COMMENT ON COLUMN signing_package.document_hash IS '当前修订文档的 SHA-256 十六进制摘要';
COMMENT ON COLUMN signing_package.expires_at IS '流程 UTC 到期时间，到期后拒绝任何签名';
COMMENT ON COLUMN signing_package.status IS '流程状态：PENDING 进行中 / COMPLETED 已完成冻结 / CANCELLED 已取消 / EXPIRED 已过期';
COMMENT ON COLUMN signing_package.frozen_at IS '证据包冻结时间（UTC），未冻结为空';
COMMENT ON COLUMN signing_package.evidence_digest IS '冻结证据包的 SHA-256 摘要，冻结后保持稳定，未冻结为空';
COMMENT ON COLUMN signing_package.created_at IS '建包时间（UTC）';

CREATE TABLE IF NOT EXISTS signing_step (
    package_id VARCHAR(36)  NOT NULL,
    step_order INT          NOT NULL,
    signer     VARCHAR(128) NOT NULL,
    CONSTRAINT pk_signing_step PRIMARY KEY (package_id, step_order, signer)
);
COMMENT ON TABLE signing_step IS '签署步骤表：每个步骤一行一名并行签署人，步骤按 step_order 有序开放';
COMMENT ON COLUMN signing_step.package_id IS '所属签署包 ID';
COMMENT ON COLUMN signing_step.step_order IS '步骤序号，从 1 开始，前一步全部签署人完成后才开放下一步';
COMMENT ON COLUMN signing_step.signer IS '该步骤的并行签署人标识（同一步可出现多行）';

CREATE TABLE IF NOT EXISTS signature_record (
    id                      VARCHAR(36)  NOT NULL,
    package_id              VARCHAR(36)  NOT NULL,
    revision                INT          NOT NULL,
    step_order              INT          NOT NULL,
    signer                  VARCHAR(128) NOT NULL,
    document_hash           CHAR(64)     NOT NULL,
    signature_value         VARCHAR(128) NOT NULL,
    status                  VARCHAR(16)  NOT NULL,
    request_id              VARCHAR(64)  NOT NULL,
    created_at              TIMESTAMP(6) NOT NULL,
    invalidated_at          TIMESTAMP(6) NULL,
    invalidated_by_revision INT          NULL,
    CONSTRAINT pk_signature_record PRIMARY KEY (id),
    CONSTRAINT uq_signature_revision_signer UNIQUE (package_id, revision, step_order, signer),
    CONSTRAINT uq_signature_request UNIQUE (request_id)
);
COMMENT ON TABLE signature_record IS '签名记录表：修订后旧记录置为 INVALIDATED 但永久保留作为失效历史';
COMMENT ON COLUMN signature_record.id IS '签名记录 ID（UUID）';
COMMENT ON COLUMN signature_record.package_id IS '所属签署包 ID';
COMMENT ON COLUMN signature_record.revision IS '该签名针对的文档修订号';
COMMENT ON COLUMN signature_record.step_order IS '签署人所属步骤序号';
COMMENT ON COLUMN signature_record.signer IS '签署人标识';
COMMENT ON COLUMN signature_record.document_hash IS '提交签名时携带的文档 SHA-256 摘要';
COMMENT ON COLUMN signature_record.signature_value IS '可复现的合成签名值（本地验证适配器校验）';
COMMENT ON COLUMN signature_record.status IS '签名状态：VALID 当前修订有效 / INVALIDATED 已被后续修订失效';
COMMENT ON COLUMN signature_record.request_id IS '客户端提交的幂等请求键 requestId，全局唯一';
COMMENT ON COLUMN signature_record.created_at IS '签名提交时间（UTC）';
COMMENT ON COLUMN signature_record.invalidated_at IS '签名失效时间（UTC），仍有效为空';
COMMENT ON COLUMN signature_record.invalidated_by_revision IS '使该签名失效的新修订号，未失效为空';

CREATE TABLE IF NOT EXISTS sign_event (
    id            BIGINT AUTO_INCREMENT NOT NULL,
    package_id    VARCHAR(36)  NOT NULL,
    seq           INT          NOT NULL,
    event_type    VARCHAR(24)  NOT NULL,
    revision      INT          NOT NULL,
    detail        CLOB         NOT NULL,
    previous_hash CHAR(64)     NOT NULL,
    event_hash    CHAR(64)     NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_sign_event PRIMARY KEY (id),
    CONSTRAINT uq_sign_event_seq UNIQUE (package_id, seq)
);
COMMENT ON TABLE sign_event IS '签署事件链：按 seq 有序，event_hash = SHA-256(previous_hash || canonicalEvent)';
COMMENT ON COLUMN sign_event.id IS '事件自增主键';
COMMENT ON COLUMN sign_event.package_id IS '所属签署包 ID';
COMMENT ON COLUMN sign_event.seq IS '包内事件序号，从 1 开始连续递增';
COMMENT ON COLUMN sign_event.event_type IS '事件类型：PACKAGE_CREATED/SIGNED/REVISION_CREATED/CANCELLED/EXPIRED/COMPLETED';
COMMENT ON COLUMN sign_event.revision IS '事件发生时签署包的当前修订号';
COMMENT ON COLUMN sign_event.detail IS '事件规范 JSON（键按字典序、无多余空白），用于哈希与证据固化';
COMMENT ON COLUMN sign_event.previous_hash IS '前一事件哈希；首事件使用 64 个 0';
COMMENT ON COLUMN sign_event.event_hash IS '本事件哈希 SHA-256(previous_hash || canonicalEvent)';
COMMENT ON COLUMN sign_event.created_at IS '事件产生时间（UTC）';

CREATE TABLE IF NOT EXISTS evidence_package (
    package_id  VARCHAR(36)  NOT NULL,
    content     CLOB         NOT NULL,
    digest      CHAR(64)     NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_evidence_package PRIMARY KEY (package_id)
);
COMMENT ON TABLE evidence_package IS '证据包冻结快照：最后一个有效签名提交时在同一事务写入，之后不可变';
COMMENT ON COLUMN evidence_package.package_id IS '所属签署包 ID';
COMMENT ON COLUMN evidence_package.content IS '证据包规范 JSON：文档摘要、步骤、有效签名、失效历史、有序事件';
COMMENT ON COLUMN evidence_package.digest IS '证据包内容 SHA-256 摘要，重复查询保持稳定';
COMMENT ON COLUMN evidence_package.created_at IS '证据包冻结时间（UTC）';

CREATE TABLE IF NOT EXISTS sign_request (
    request_id  VARCHAR(64)  NOT NULL,
    package_id  VARCHAR(36)  NOT NULL,
    fingerprint CHAR(64)     NOT NULL,
    http_status INT          NOT NULL,
    response    CLOB         NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_sign_request PRIMARY KEY (request_id)
);
COMMENT ON TABLE sign_request IS '签名提交幂等记录：请求键对应完整参数指纹与首次结果快照';
COMMENT ON COLUMN sign_request.request_id IS '幂等请求键 requestId';
COMMENT ON COLUMN sign_request.package_id IS '请求的签署包 ID（参与指纹）';
COMMENT ON COLUMN sign_request.fingerprint IS '完整参数（含包 ID、签署人、修订号、摘要、期望版本、签名值）的 SHA-256 指纹';
COMMENT ON COLUMN sign_request.http_status IS '首次请求返回的 HTTP 状态码，重放时原样返回';
COMMENT ON COLUMN sign_request.response IS '首次响应体 JSON 快照，同键同参直接返回';
COMMENT ON COLUMN sign_request.created_at IS '首次请求时间（UTC）';
