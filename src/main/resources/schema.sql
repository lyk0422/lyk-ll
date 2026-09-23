-- 电子证据保全与安全清除服务：H2（MODE=MySQL）建表脚本，仅合成数据，JVM 生命周期内有效。

-- 证据主表：状态 RETAINED（可保留）/ MARKED（已标记待清除）/ PURGED（已清除）
CREATE TABLE IF NOT EXISTS evidence (
    evidence_id  VARCHAR(128) NOT NULL,
    sha256       CHAR(64)     NOT NULL,
    collected_at TIMESTAMP    NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (evidence_id)
);
COMMENT ON COLUMN evidence.evidence_id IS '证据业务 ID，登记后不可变';
COMMENT ON COLUMN evidence.sha256 IS '证据合成字节内容的 SHA-256 十六进制摘要';
COMMENT ON COLUMN evidence.collected_at IS '采集时间（UTC）';
COMMENT ON COLUMN evidence.expires_at IS '到期时间（UTC），到期后且无活动保全方可清除';
COMMENT ON COLUMN evidence.status IS '状态：RETAINED 可保留 / MARKED 已标记待清除 / PURGED 已清除';
COMMENT ON COLUMN evidence.created_at IS '登记落库时间（UTC）';

-- 不可变保管事件链：仅追加，不更新不删除
CREATE TABLE IF NOT EXISTS custody_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    evidence_id VARCHAR(128) NOT NULL,
    event_type  VARCHAR(32)  NOT NULL,
    detail      VARCHAR(1024),
    event_time  TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON COLUMN custody_event.id IS '保管事件单调序号，同一证据按序号回放';
COMMENT ON COLUMN custody_event.event_type IS '事件类型：REGISTERED/MARKED/RESCUED/HELD/RELEASED/PURGED';
COMMENT ON COLUMN custody_event.event_time IS '事件发生时间（UTC）';
CREATE INDEX IF NOT EXISTS idx_custody_evidence ON custody_event (evidence_id, id);

-- 法律保全单：版本随创建(奇数起点 1)、解除递增；ACTIVE / RELEASED
CREATE TABLE IF NOT EXISTS legal_hold (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    case_id            VARCHAR(128) NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    version            BIGINT       NOT NULL,
    created_at         TIMESTAMP    NOT NULL,
    released_at        TIMESTAMP,
    create_request_id  VARCHAR(128) NOT NULL,
    release_request_id VARCHAR(128),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN legal_hold.case_id IS '案件编号';
COMMENT ON COLUMN legal_hold.status IS '保全状态：ACTIVE 活动 / RELEASED 已解除';
COMMENT ON COLUMN legal_hold.version IS '乐观版本：创建为 1，解除后为 2';
COMMENT ON COLUMN legal_hold.create_request_id IS '创建携带的幂等 requestId';
COMMENT ON COLUMN legal_hold.release_request_id IS '解除携带的幂等 requestId，空值表示未解除';
CREATE UNIQUE INDEX IF NOT EXISTS uk_hold_create_request ON legal_hold (create_request_id);

CREATE TABLE IF NOT EXISTS legal_hold_item (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    hold_id     BIGINT       NOT NULL,
    evidence_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (hold_id, evidence_id)
);
CREATE INDEX IF NOT EXISTS idx_hold_item_evidence ON legal_hold_item (evidence_id);

-- 清除批次：MARKED（标记阶段）/ PURGED（最终确认完成）
CREATE TABLE IF NOT EXISTS purge_batch (
    batch_id        VARCHAR(128) NOT NULL,
    mark_request_id VARCHAR(128) NOT NULL,
    cutoff_time     TIMESTAMP    NOT NULL,
    expected_version BIGINT,
    status          VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    finalized_at    TIMESTAMP,
    PRIMARY KEY (batch_id)
);
COMMENT ON COLUMN purge_batch.cutoff_time IS '标记请求的截止时间（UTC），仅 expires_at 不晚于该时刻视为到期';
COMMENT ON COLUMN purge_batch.expected_version IS '标记请求携带的保全全局预期版本，空值表示不校验';
COMMENT ON COLUMN purge_batch.status IS '批次状态：MARKED 已标记 / PURGED 已最终确认';
CREATE UNIQUE INDEX IF NOT EXISTS uk_batch_mark_request ON purge_batch (mark_request_id);

CREATE TABLE IF NOT EXISTS purge_batch_item (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id    VARCHAR(128) NOT NULL,
    evidence_id VARCHAR(128) NOT NULL,
    rescued     CHAR(1)      NOT NULL DEFAULT 'N',
    PRIMARY KEY (id),
    UNIQUE (batch_id, evidence_id)
);
COMMENT ON COLUMN purge_batch_item.rescued IS 'Y=最终确认时因活动保全被原子救回，N=正常候选';

-- 不可变清除清单：最终确认时一次性冻结写入，之后不更新不删除
CREATE TABLE IF NOT EXISTS purge_manifest (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id    VARCHAR(128) NOT NULL,
    evidence_id VARCHAR(128) NOT NULL,
    sha256      CHAR(64)     NOT NULL,
    purged_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (batch_id, evidence_id)
);

-- 幂等请求记录：request_key 唯一，fingerprint 为请求参数指纹，同键异指纹冲突
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_key  VARCHAR(160) NOT NULL,
    fingerprint  CHAR(64)     NOT NULL,
    response_ref VARCHAR(128),
    created_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (request_key)
);
COMMENT ON COLUMN idempotent_request.request_key IS '幂等键，按业务前缀加 requestId 构成';
COMMENT ON COLUMN idempotent_request.fingerprint IS '请求参数指纹（SHA-256），同键异指纹返回 409';
COMMENT ON COLUMN idempotent_request.response_ref IS '首笔成功结果引用（holdId 或 batchId）';

-- 保全全局版本序列：每次创建/解除保全单原子递增
CREATE TABLE IF NOT EXISTS seq_holder (
    id           INT    NOT NULL,
    hold_version BIGINT NOT NULL,
    PRIMARY KEY (id)
);
INSERT IGNORE INTO seq_holder (id, hold_version) VALUES (0, 0);
