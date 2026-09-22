package com.example.starter.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 基于 JdbcTemplate 的帧审计/信号状态/变更事件仓库。
 *
 * <p>状态更新通过 SELECT ... FOR UPDATE 锁定目标状态行后再做新旧判定，
 * 保证并发帧不会交叉覆盖；首行插入冲突时回退到加锁重读再判定。
 */
@Repository
public class JdbcFrameRepository implements FrameRepository {

    private final JdbcTemplate jdbc;

    public JdbcFrameRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AuditRow insertAuditIfAbsent(String version, long canId, long timestampNs, long seq,
                                        byte[] payload, boolean accepted, String reason) {
        try {
            jdbc.update("INSERT INTO frame_audit (version, can_id, timestamp_ns, seq, payload, accepted, reason) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    version, canId, timestampNs, seq, payload, accepted, reason);
            return new AuditRow(0, version, canId, timestampNs, seq, payload, accepted, reason);
        } catch (DuplicateKeyException ex) {
            return null;
        }
    }

    @Override
    public AuditRow findAudit(String version, long canId, long seq) {
        List<AuditRow> rows = jdbc.query(
                "SELECT id, version, can_id, timestamp_ns, seq, payload, accepted, reason "
                        + "FROM frame_audit WHERE version = ? AND can_id = ? AND seq = ?",
                (rs, rowNum) -> new AuditRow(
                        rs.getLong("id"), rs.getString("version"), rs.getLong("can_id"),
                        rs.getLong("timestamp_ns"), rs.getLong("seq"), rs.getBytes("payload"),
                        rs.getBoolean("accepted"), rs.getString("reason")),
                version, canId, seq);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public UpsertOutcome upsertStateIfNewer(String version, long canId, String signalName,
                                            long rawValue, double physicalValue, String enumLabel,
                                            long timestampNs, long seq) {
        // 先锁定状态行（若存在），使同一信号的并发帧串行化，避免交叉覆盖。
        List<long[]> locked = jdbc.query(
                "SELECT raw_value FROM signal_state WHERE version = ? AND can_id = ? AND signal_name = ? "
                        + "FOR UPDATE",
                (rs, rowNum) -> new long[]{rs.getLong("raw_value")},
                version, canId, signalName);
        if (!locked.isEmpty()) {
            long oldRaw = locked.get(0)[0];
            if (!isNewer(version, canId, signalName, timestampNs, seq)) {
                return new UpsertOutcome(false, false, null);
            }
            updateState(version, canId, signalName, rawValue, physicalValue, enumLabel, timestampNs, seq);
            return new UpsertOutcome(true, false, oldRaw);
        }
        try {
            jdbc.update("INSERT INTO signal_state (version, can_id, signal_name, raw_value, physical_value, "
                            + "enum_label, timestamp_ns, seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    version, canId, signalName, rawValue, physicalValue, enumLabel, timestampNs, seq);
            return new UpsertOutcome(true, true, null);
        } catch (DuplicateKeyException ex) {
            // 并发首帧竞争：回退到加锁重读再判定。
            List<long[]> relocked = jdbc.query(
                    "SELECT raw_value FROM signal_state WHERE version = ? AND can_id = ? AND signal_name = ? "
                            + "FOR UPDATE",
                    (rs, rowNum) -> new long[]{rs.getLong("raw_value")},
                    version, canId, signalName);
            if (relocked.isEmpty()) {
                throw new IllegalStateException("状态行插入冲突后重读仍为空: " + signalName);
            }
            long oldRaw = relocked.get(0)[0];
            if (!isNewer(version, canId, signalName, timestampNs, seq)) {
                return new UpsertOutcome(false, false, null);
            }
            updateState(version, canId, signalName, rawValue, physicalValue, enumLabel, timestampNs, seq);
            return new UpsertOutcome(true, false, oldRaw);
        }
    }

    private void updateState(String version, long canId, String signalName,
                             long rawValue, double physicalValue, String enumLabel,
                             long timestampNs, long seq) {
        jdbc.update("UPDATE signal_state SET raw_value = ?, physical_value = ?, enum_label = ?, "
                        + "timestamp_ns = ?, seq = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE version = ? AND can_id = ? AND signal_name = ?",
                rawValue, physicalValue, enumLabel, timestampNs, seq, version, canId, signalName);
    }

    private boolean isNewer(String version, long canId, String signalName, long timestampNs, long seq) {
        List<StateOrderRow> rows = jdbc.query(
                "SELECT timestamp_ns, seq FROM signal_state WHERE version = ? AND can_id = ? AND signal_name = ?",
                (rs, rowNum) -> new StateOrderRow(rs.getLong("timestamp_ns"), rs.getLong("seq")),
                version, canId, signalName);
        if (rows.isEmpty()) {
            return true;
        }
        StateOrderRow current = rows.get(0);
        return timestampNs > current.timestampNs()
                || (timestampNs == current.timestampNs() && seq > current.seq());
    }

    @Override
    public void insertEvent(String version, long canId, String signalName, long seq, long timestampNs,
                            Long oldRaw, long newRaw) {
        jdbc.update("INSERT INTO signal_state_events (version, can_id, signal_name, seq, timestamp_ns, "
                        + "old_raw, new_raw) VALUES (?, ?, ?, ?, ?, ?, ?)",
                version, canId, signalName, seq, timestampNs, oldRaw, newRaw);
    }

    @Override
    public long countEvents(String version, long canId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM signal_state_events WHERE version = ? AND can_id = ?",
                Long.class, version, canId);
        return count == null ? 0L : count;
    }

    @Override
    public SignalStateRow findState(String version, long canId, String signalName) {
        List<SignalStateRow> rows = jdbc.query(
                "SELECT signal_name, raw_value, physical_value, enum_label, timestamp_ns, seq "
                        + "FROM signal_state WHERE version = ? AND can_id = ? AND signal_name = ?",
                (rs, rowNum) -> new SignalStateRow(
                        rs.getString("signal_name"), rs.getLong("raw_value"),
                        rs.getDouble("physical_value"), rs.getString("enum_label"),
                        rs.getLong("timestamp_ns"), rs.getLong("seq")),
                version, canId, signalName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private record StateOrderRow(long timestampNs, long seq) {
    }
}
