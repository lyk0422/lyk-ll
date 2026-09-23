-- 证书透明日志本地库表结构（H2，MODE=MySQL）。
-- 所有时间字段均为 UTC 纪元毫秒（BIGINT），空值含义见各列注释。

CREATE TABLE IF NOT EXISTS ct_leaf (
    leaf_index BIGINT PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    entry_hash CHAR(64) NOT NULL,
    leaf_hash CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT uk_ct_leaf_request UNIQUE (request_id)
);
COMMENT ON TABLE ct_leaf IS '证书透明日志叶条目，按提交顺序追加';
COMMENT ON COLUMN ct_leaf.leaf_index IS '叶索引，从 0 开始连续分配，并发下唯一';
COMMENT ON COLUMN ct_leaf.request_id IS '客户端请求标识，用于幂等去重；同键异条目返回 409';
COMMENT ON COLUMN ct_leaf.entry_hash IS 'DER 字节的 SHA-256 摘要（小写十六进制），即日志追加的叶内容';
COMMENT ON COLUMN ct_leaf.leaf_hash IS 'Merkle 叶哈希 SHA-256(0x00 || entryHash 字节)（小写十六进制）';
COMMENT ON COLUMN ct_leaf.created_at IS '入库时间，UTC 纪元毫秒';

CREATE TABLE IF NOT EXISTS observer_state (
    observer_id VARCHAR(128) PRIMARY KEY,
    max_tree_size BIGINT NOT NULL,
    last_root_hash CHAR(64) NOT NULL,
    updated_at BIGINT NOT NULL
);
COMMENT ON TABLE observer_state IS '观察者最新位置，较小树头不得使其倒退';
COMMENT ON COLUMN observer_state.observer_id IS '观察者标识';
COMMENT ON COLUMN observer_state.max_tree_size IS '该观察者已上报的最大树大小（最新位置）';
COMMENT ON COLUMN observer_state.last_root_hash IS '最新位置对应的根哈希（小写十六进制）';
COMMENT ON COLUMN observer_state.updated_at IS '状态更新时间，UTC 纪元毫秒';

CREATE TABLE IF NOT EXISTS observer_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    observer_id VARCHAR(128) NOT NULL,
    tree_size BIGINT NOT NULL,
    root_hash CHAR(64) NOT NULL,
    reported_at BIGINT NOT NULL,
    received_at BIGINT NOT NULL,
    CONSTRAINT uk_observer_report UNIQUE (observer_id, tree_size, root_hash)
);
COMMENT ON TABLE observer_report IS '验签通过的观察者树头上报记录，重复上报幂等';
COMMENT ON COLUMN observer_report.id IS '自增主键';
COMMENT ON COLUMN observer_report.observer_id IS '观察者标识';
COMMENT ON COLUMN observer_report.tree_size IS '上报的树大小，必须大于 0';
COMMENT ON COLUMN observer_report.root_hash IS '上报的根哈希（小写十六进制）';
COMMENT ON COLUMN observer_report.reported_at IS '观察者签名中的时间，UTC 纪元毫秒';
COMMENT ON COLUMN observer_report.received_at IS '服务端接收时间，UTC 纪元毫秒';

CREATE TABLE IF NOT EXISTS split_view_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_type VARCHAR(32) NOT NULL,
    observer_id VARCHAR(128) NOT NULL,
    tree_size BIGINT NOT NULL,
    local_root_hash CHAR(64) NOT NULL,
    reported_root_hash CHAR(64) NOT NULL,
    reported_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT uk_split_view_tree UNIQUE (tree_size)
);
COMMENT ON TABLE split_view_alert IS '分裂视图告警：同一日志同一 treeSize 出现不同有效根时原子创建，全局唯一';
COMMENT ON COLUMN split_view_alert.id IS '自增主键';
COMMENT ON COLUMN split_view_alert.alert_type IS '告警类型，固定为 SPLIT_VIEW';
COMMENT ON COLUMN split_view_alert.observer_id IS '首个触发告警的观察者标识';
COMMENT ON COLUMN split_view_alert.tree_size IS '发生分歧的树大小';
COMMENT ON COLUMN split_view_alert.local_root_hash IS '本地日志在该树大小的根哈希（小写十六进制）';
COMMENT ON COLUMN split_view_alert.reported_root_hash IS '观察者上报的分歧根哈希（小写十六进制）';
COMMENT ON COLUMN split_view_alert.reported_at IS '观察者签名中的时间，UTC 纪元毫秒';
COMMENT ON COLUMN split_view_alert.created_at IS '告警创建时间，UTC 纪元毫秒';
