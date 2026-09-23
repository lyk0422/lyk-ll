-- 证书透明日志本地演示库表结构（H2，MODE=MySQL）。
-- 所有数据均为合成数据，仅在 JVM 生命周期内保留。

-- 日志全局状态：单行表，行锁用于串行化追加，保证叶索引唯一且连续。
CREATE TABLE IF NOT EXISTS ct_log_state (
    id TINYINT NOT NULL PRIMARY KEY,
    tree_size BIGINT NOT NULL
);
COMMENT ON TABLE ct_log_state IS '日志全局状态（单行），tree_size 为当前已提交叶数量';
COMMENT ON COLUMN ct_log_state.id IS '固定为 0 的单行标识';
COMMENT ON COLUMN ct_log_state.tree_size IS '当前已提交叶数量，即下一个待分配的叶索引';

-- 叶条目：按提交顺序追加，leaf_index 唯一连续。
CREATE TABLE IF NOT EXISTS ct_leaf (
    leaf_index BIGINT NOT NULL PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    entry_hash CHAR(64) NOT NULL,
    leaf_bytes VARBINARY(8192) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE ct_leaf IS '证书透明日志叶条目，按提交顺序追加';
COMMENT ON COLUMN ct_leaf.leaf_index IS '叶索引，从 0 开始唯一连续分配';
COMMENT ON COLUMN ct_leaf.request_id IS '客户端幂等键，同一 request_id 仅允许对应同一条目';
COMMENT ON COLUMN ct_leaf.entry_hash IS '条目摘要，DER 字节的 SHA-256（小写十六进制），即 Merkle 叶数据';
COMMENT ON COLUMN ct_leaf.leaf_bytes IS 'Merkle 叶数据原始字节（即 DER 摘要的 32 字节）';
COMMENT ON COLUMN ct_leaf.created_at IS '提交时间，数据库时区';
CREATE UNIQUE INDEX IF NOT EXISTS uk_ct_leaf_request_id ON ct_leaf (request_id);

-- 观察者最新位置：较小树头不得倒退。
CREATE TABLE IF NOT EXISTS ct_observer_head (
    observer_id VARCHAR(128) NOT NULL PRIMARY KEY,
    tree_size BIGINT NOT NULL,
    root_hash CHAR(64) NOT NULL,
    observed_at TIMESTAMP NOT NULL,
    signature VARCHAR(256) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE ct_observer_head IS '观察者最新已接受的签名树头位置';
COMMENT ON COLUMN ct_observer_head.observer_id IS '观察者标识';
COMMENT ON COLUMN ct_observer_head.tree_size IS '观察者最新树大小，只增不减';
COMMENT ON COLUMN ct_observer_head.root_hash IS '观察者最新树头根哈希（小写十六进制）';
COMMENT ON COLUMN ct_observer_head.observed_at IS '观察者上报的观测时间（上报方时钟）';
COMMENT ON COLUMN ct_observer_head.signature IS '上报携带的 Ed25519 签名（十六进制）';
COMMENT ON COLUMN ct_observer_head.updated_at IS '服务端最近一次接受上报的时间，数据库时区';

-- 分裂视图告警：同一观察者同一 treeSize 仅一条，保留双方证据。
CREATE TABLE IF NOT EXISTS ct_split_alert (
    alert_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    alert_type VARCHAR(32) NOT NULL,
    observer_id VARCHAR(128) NOT NULL,
    tree_size BIGINT NOT NULL,
    log_root_hash CHAR(64) NOT NULL,
    observer_root_hash CHAR(64) NOT NULL,
    evidence VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE ct_split_alert IS '分裂视图告警：同一日志同一 treeSize 出现两个不同有效根';
COMMENT ON COLUMN ct_split_alert.alert_id IS '告警自增主键';
COMMENT ON COLUMN ct_split_alert.alert_type IS '告警类型，固定为 SPLIT_VIEW';
COMMENT ON COLUMN ct_split_alert.observer_id IS '触发告警的观察者标识';
COMMENT ON COLUMN ct_split_alert.tree_size IS '发生分歧的树大小';
COMMENT ON COLUMN ct_split_alert.log_root_hash IS '本日志在该树大小下的根哈希（小写十六进制）';
COMMENT ON COLUMN ct_split_alert.observer_root_hash IS '观察者上报的根哈希（小写十六进制）';
COMMENT ON COLUMN ct_split_alert.evidence IS '双方证据（JSON：双方根哈希、上报时间与签名）';
COMMENT ON COLUMN ct_split_alert.created_at IS '告警创建时间，数据库时区';
-- 同一日志（本服务仅单一日志）同一 treeSize 仅允许一条 SPLIT_VIEW 告警，
-- 与具体观察者无关：不同观察者在同一 treeSize 上报不同有效根也去重。
CREATE UNIQUE INDEX IF NOT EXISTS uk_ct_split_alert_tree_size ON ct_split_alert (tree_size);
