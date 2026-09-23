package com.example.starter.ctlog.repo;

import com.example.starter.ctlog.domain.LeafEntry;
import com.example.starter.ctlog.domain.ObserverHead;
import com.example.starter.ctlog.domain.SplitAlert;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 日志、观察者位置与告警的数据访问。
 *
 * <p>追加通过单行状态表 {@code ct_log_state} 的行锁串行化，
 * 保证叶索引唯一连续；观察者位置只允许单调推进；
 * 告警依赖 (observer_id, tree_size) 唯一约束原子去重。</p>
 */
@Repository
public class CtLogRepository {

    private final JdbcTemplate jdbc;

    public CtLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 在单行状态表上取写锁并读取当前树大小，调用方须处于事务内。
     */
    public long lockAndGetSize() {
        Long size = jdbc.queryForObject(
                "SELECT tree_size FROM ct_log_state WHERE id = 0 FOR UPDATE", Long.class);
        return size == null ? 0L : size;
    }

    /**
     * 已提交树大小加一，调用方须处于持有状态行锁的事务内。
     */
    public void incrementSize() {
        jdbc.update("UPDATE ct_log_state SET tree_size = tree_size + 1 WHERE id = 0");
    }

    public void insertLeaf(long leafIndex, String requestId, byte[] entryHash, String entryHashHex) {
        jdbc.update(
                "INSERT INTO ct_leaf (leaf_index, request_id, entry_hash, leaf_bytes) VALUES (?, ?, ?, ?)",
                leafIndex, requestId, entryHashHex, entryHash);
    }

    public LeafEntry findLeafByRequestId(String requestId) {
        List<LeafEntry> rows = jdbc.query(
                "SELECT leaf_index, request_id, leaf_bytes, created_at FROM ct_leaf WHERE request_id = ?",
                LEAF_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 读取当前已提交树大小（不加锁，读已提交前缀）。
     */
    public long currentSize() {
        Long size = jdbc.queryForObject("SELECT tree_size FROM ct_log_state WHERE id = 0", Long.class);
        return size == null ? 0L : size;
    }

    /**
     * 读取 [startInclusive, endExclusive) 范围内的叶数据（Merkle 叶输入），按索引升序。
     */
    public List<byte[]> leafHashesBetween(long startInclusive, long endExclusive) {
        return jdbc.query(
                "SELECT leaf_bytes FROM ct_leaf WHERE leaf_index >= ? AND leaf_index < ? ORDER BY leaf_index",
                (rs, n) -> rs.getBytes("leaf_bytes"), startInclusive, endExclusive);
    }

    public ObserverHead findObserverHead(String observerId) {
        List<ObserverHead> rows = jdbc.query(
                "SELECT observer_id, tree_size, root_hash, observed_at, signature, updated_at "
                        + "FROM ct_observer_head WHERE observer_id = ?",
                OBSERVER_HEAD_MAPPER, observerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行锁读取观察者最新位置，调用方须处于事务内。
     */
    public ObserverHead findObserverHeadForUpdate(String observerId) {
        List<ObserverHead> rows = jdbc.query(
                "SELECT observer_id, tree_size, root_hash, observed_at, signature, updated_at "
                        + "FROM ct_observer_head WHERE observer_id = ? FOR UPDATE",
                OBSERVER_HEAD_MAPPER, observerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertObserverHead(ObserverHead head) {
        jdbc.update(
                "INSERT INTO ct_observer_head (observer_id, tree_size, root_hash, observed_at, signature) "
                        + "VALUES (?, ?, ?, ?, ?)",
                head.observerId(), head.treeSize(), head.rootHash(),
                Timestamp.from(head.observedAt()), head.signature());
    }

    /**
     * 仅当新位置更大时推进观察者最新位置；返回是否推进成功。
     */
    public boolean advanceObserverHead(ObserverHead head) {
        int updated = jdbc.update(
                "UPDATE ct_observer_head SET tree_size = ?, root_hash = ?, observed_at = ?, signature = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observer_id = ? AND tree_size < ?",
                head.treeSize(), head.rootHash(), Timestamp.from(head.observedAt()), head.signature(),
                head.observerId(), head.treeSize());
        return updated == 1;
    }

    /**
     * 原子插入唯一 SPLIT_VIEW 告警；同观察者同 treeSize 已存在时返回 false。
     */
    public boolean insertAlertIfAbsent(SplitAlert alert) {
        try {
            jdbc.update(
                    "INSERT INTO ct_split_alert (alert_type, observer_id, tree_size, log_root_hash, "
                            + "observer_root_hash, evidence) VALUES (?, ?, ?, ?, ?, ?)",
                    alert.alertType(), alert.observerId(), alert.treeSize(), alert.logRootHash(),
                    alert.observerRootHash(), alert.evidence());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<SplitAlert> findAlerts() {
        return jdbc.query(
                "SELECT alert_id, alert_type, observer_id, tree_size, log_root_hash, observer_root_hash, "
                        + "evidence, created_at FROM ct_split_alert ORDER BY alert_id",
                ALERT_MAPPER);
    }

    static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    static final RowMapper<LeafEntry> LEAF_MAPPER = (rs, n) -> new LeafEntry(
            rs.getLong("leaf_index"),
            rs.getString("request_id"),
            rs.getBytes("leaf_bytes"),
            toInstant(rs.getTimestamp("created_at")));

    static final RowMapper<ObserverHead> OBSERVER_HEAD_MAPPER = (rs, n) -> new ObserverHead(
            rs.getString("observer_id"),
            rs.getLong("tree_size"),
            rs.getString("root_hash"),
            toInstant(rs.getTimestamp("observed_at")),
            rs.getString("signature"),
            toInstant(rs.getTimestamp("updated_at")));

    static final RowMapper<SplitAlert> ALERT_MAPPER = (rs, n) -> new SplitAlert(
            rs.getLong("alert_id"),
            rs.getString("alert_type"),
            rs.getString("observer_id"),
            rs.getLong("tree_size"),
            rs.getString("log_root_hash"),
            rs.getString("observer_root_hash"),
            rs.getString("evidence"),
            toInstant(rs.getTimestamp("created_at")));
}
