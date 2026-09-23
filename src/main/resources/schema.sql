-- 电子证据保全与安全清除服务建表脚本（H2 MySQL 兼容模式，时间均为 UTC）

CREATE TABLE IF NOT EXISTS domain_state (
    id TINYINT NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL
);
COMMENT ON TABLE domain_state IS '全局注册表状态，单行记录领域版本号';
COMMENT ON COLUMN domain_state.id IS '单行标识，固定为 1';
COMMENT ON COLUMN domain_state.version IS '领域版本号，证据/保全/清除任一变更后递增，用于预期版本校验';

INSERT INTO domain_state (id, version)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM domain_state WHERE id = 1);

CREATE TABLE IF NOT EXISTS evidence (
    evidence_id VARCHAR(64) NOT NULL PRIMARY KEY,
    sha256 CHAR(64) NOT NULL,
    collected_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE evidence IS '电子证据登记表';
COMMENT ON COLUMN evidence.evidence_id IS '证据唯一标识，业务主键';
COMMENT ON COLUMN evidence.sha256 IS '证据内容 SHA-256 摘要，小写十六进制 64 字符';
COMMENT ON COLUMN evidence.collected_at IS '采集时间（UTC）';
COMMENT ON COLUMN evidence.expires_at IS '到期时间（UTC），到期且未被保全后才允许清除';
COMMENT ON COLUMN evidence.status IS '状态：ACTIVE 可保留 / MARKED 已标记待清除 / PURGED 已清除';
COMMENT ON COLUMN evidence.version IS '乐观锁版本，初始 1，状态变更递增';
COMMENT ON COLUMN evidence.created_at IS '登记时间（UTC）';
COMMENT ON COLUMN evidence.updated_at IS '最近一次状态变更时间（UTC）';

CREATE TABLE IF NOT EXISTS custody_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    evidence_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    detail VARCHAR(1024),
    occurred_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE custody_event IS '证据保管事件表，仅追加不可变';
COMMENT ON COLUMN custody_event.id IS '事件自增主键';
COMMENT ON COLUMN custody_event.evidence_id IS '关联证据标识';
COMMENT ON COLUMN custody_event.event_type IS '事件类型：REGISTERED/HOLD_CREATED/HOLD_RELEASED/MARKED/RESCUED/PURGED';
COMMENT ON COLUMN custody_event.detail IS '事件补充说明，可空表示无补充信息';
COMMENT ON COLUMN custody_event.occurred_at IS '事件发生时间（UTC）';
CREATE INDEX IF NOT EXISTS idx_custody_event_evidence ON custody_event (evidence_id);

CREATE TABLE IF NOT EXISTS legal_hold (
    hold_id VARCHAR(64) NOT NULL PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    case_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    released_at TIMESTAMP,
    release_request_id VARCHAR(128)
);
COMMENT ON TABLE legal_hold IS '法律保全单';
COMMENT ON COLUMN legal_hold.hold_id IS '保全单唯一标识';
COMMENT ON COLUMN legal_hold.request_id IS '创建请求幂等键，全局唯一';
COMMENT ON COLUMN legal_hold.fingerprint IS '创建请求参数指纹（案件+证据集合+预期版本），同键异参拒绝';
COMMENT ON COLUMN legal_hold.case_id IS '关联案件编号';
COMMENT ON COLUMN legal_hold.status IS '状态：ACTIVE 生效中 / RELEASED 已解除';
COMMENT ON COLUMN legal_hold.version IS '保全单版本，创建为 1，解除后递增为 2';
COMMENT ON COLUMN legal_hold.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN legal_hold.released_at IS '解除时间（UTC），未解除为空';
COMMENT ON COLUMN legal_hold.release_request_id IS '解除请求幂等键，未解除为空';
CREATE UNIQUE INDEX IF NOT EXISTS uk_legal_hold_request ON legal_hold (request_id);

CREATE TABLE IF NOT EXISTS legal_hold_item (
    hold_id VARCHAR(64) NOT NULL,
    evidence_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (hold_id, evidence_id)
);
COMMENT ON TABLE legal_hold_item IS '法律保全单与证据的关联表';
COMMENT ON COLUMN legal_hold_item.hold_id IS '保全单标识';
COMMENT ON COLUMN legal_hold_item.evidence_id IS '被保全的证据标识';

CREATE TABLE IF NOT EXISTS purge_batch (
    batch_id VARCHAR(64) NOT NULL PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    cutoff TIMESTAMP NOT NULL,
    expected_version BIGINT,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    manifest_json CLOB,
    confirm_request_id VARCHAR(128),
    confirm_fingerprint CHAR(64),
    created_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP
);
COMMENT ON TABLE purge_batch IS '清除批次表，两阶段清除的标记与确认记录';
COMMENT ON COLUMN purge_batch.batch_id IS '清除批次唯一标识';
COMMENT ON COLUMN purge_batch.request_id IS '标记请求幂等键，全局唯一';
COMMENT ON COLUMN purge_batch.fingerprint IS '标记请求参数指纹（证据集合+截止时间+预期版本），同键异参拒绝';
COMMENT ON COLUMN purge_batch.cutoff IS '到期截止时间（UTC），仅到期时间不晚于该值的证据可被标记';
COMMENT ON COLUMN purge_batch.expected_version IS '标记请求携带的预期领域版本，可空表示不校验';
COMMENT ON COLUMN purge_batch.status IS '状态：MARKED 已标记待确认 / CONFIRMED 已最终确认';
COMMENT ON COLUMN purge_batch.version IS '批次版本，标记为 1，确认后递增为 2';
COMMENT ON COLUMN purge_batch.manifest_json IS '最终确认时冻结的不可变清除清单 JSON，未确认为空';
COMMENT ON COLUMN purge_batch.confirm_request_id IS '最终确认请求幂等键，未确认为空';
COMMENT ON COLUMN purge_batch.confirm_fingerprint IS '最终确认请求参数指纹（批次+预期版本）';
COMMENT ON COLUMN purge_batch.created_at IS '标记时间（UTC）';
COMMENT ON COLUMN purge_batch.confirmed_at IS '最终确认时间（UTC），未确认为空';
CREATE UNIQUE INDEX IF NOT EXISTS uk_purge_batch_request ON purge_batch (request_id);

CREATE TABLE IF NOT EXISTS purge_batch_item (
    batch_id VARCHAR(64) NOT NULL,
    evidence_id VARCHAR(64) NOT NULL,
    final_status VARCHAR(16),
    reason VARCHAR(256),
    PRIMARY KEY (batch_id, evidence_id)
);
COMMENT ON TABLE purge_batch_item IS '清除批次候选清单，确认后记录每项最终结果';
COMMENT ON COLUMN purge_batch_item.batch_id IS '所属清除批次标识';
COMMENT ON COLUMN purge_batch_item.evidence_id IS '候选证据标识';
COMMENT ON COLUMN purge_batch_item.final_status IS '最终结果：空表示待确认 / PURGED 已清除 / RESCUED 已被保全救回';
COMMENT ON COLUMN purge_batch_item.reason IS '最终结果说明，可空表示无补充信息';
