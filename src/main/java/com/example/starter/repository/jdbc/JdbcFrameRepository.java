package com.example.starter.repository.jdbc;

import com.example.starter.domain.SignalStateRow;
import com.example.starter.repository.FrameRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

import java.sql.Types;

import java.util.List;
import java.util.Optional;

/**
 * 帧审计、信号最新状态与状态变更事件的 JDBC 实现。
 *
 * <p>状态覆盖与事件去重均由单行条件 SQL 完成（frame_seq 比较 + NOT EXISTS 守卫），
 * 不依赖应用层加锁，保证并发帧下旧时戳/低序号帧不会覆盖状态，
 * 相同帧重放不会重复产生事件。</p>
 */
@Repository
public class JdbcFrameRepository implements FrameRepository {

    private static final RowMapper<SignalStateRow> STATE_MAPPER = (rs, rowNum) -> new SignalStateRow(
            rs.getString("signal_name"),
            rs.getLong("raw_value"),
            rs.getDouble("physical_value"),
            (String) rs.getObject("enum_text"),
            rs.getLong("frame_seq"),
            rs.getLong("timestamp_ns"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcFrameRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insertAudit(long versionId, long canId, long frameSeq, long timestampNs,
                            byte[] payload, boolean accepted, String rejectReason, long createdAtNano) {
        jdbcTemplate.update(
                "INSERT INTO frame_audit(version_id, can_id, frame_seq, timestamp_ns, payload, "
                        + "accepted, reject_reason, created_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                versionId, canId, frameSeq, timestampNs, payload, accepted, rejectReason, createdAtNano);
    }

    @Override
    public boolean existsSameFrame(long versionId, long canId, long frameSeq, long timestampNs, byte[] payload) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM frame_audit WHERE version_id = ? AND can_id = ? AND frame_seq = ? "
                        + "AND timestamp_ns = ? AND payload = ?",
                Integer.class, versionId, canId, frameSeq, timestampNs, payload);
        return count != null && count > 0;
    }

    @Override
    public Optional<Long> findLatestAcceptedTimestamp(long versionId, long canId) {
        List<Long> values = jdbcTemplate.queryForList(
                "SELECT MAX(timestamp_ns) FROM frame_audit WHERE version_id = ? AND can_id = ? AND accepted = TRUE",
                Long.class, versionId, canId);
        return Optional.ofNullable(values.isEmpty() ? null : values.get(0));
    }

    @Override
    public Optional<Long> findLatestAcceptedSeq(long versionId, long canId) {
        List<Long> values = jdbcTemplate.queryForList(
                "SELECT MAX(frame_seq) FROM frame_audit WHERE version_id = ? AND can_id = ? AND accepted = TRUE",
                Long.class, versionId, canId);
        return Optional.ofNullable(values.isEmpty() ? null : values.get(0));
    }

    @Override
    public Optional<SignalStateRow> findState(long versionId, long canId, String signalName) {
        return jdbcTemplate.query(
                "SELECT signal_name, raw_value, physical_value, enum_text, frame_seq, timestamp_ns "
                        + "FROM signal_state WHERE version_id = ? AND can_id = ? AND signal_name = ?",
                STATE_MAPPER, versionId, canId, signalName).stream().findFirst();
    }

    @Override
    public int upsertStateIfNewer(long versionId, long canId, String signalName,
                                  long rawValue, double physicalValue, String enumText,
                                  long frameSeq, long timestampNs) {
        int updated = jdbcTemplate.update(
                "UPDATE signal_state SET raw_value = ?, physical_value = ?, enum_text = ?, "
                        + "frame_seq = ?, timestamp_ns = ? "
                        + "WHERE version_id = ? AND can_id = ? AND signal_name = ? AND frame_seq < ?",
                rawValue, physicalValue, enumText, frameSeq, timestampNs,
                versionId, canId, signalName, frameSeq);
        if (updated > 0) {
            return updated;
        }
        // 不存在旧行，或旧行序号更新（并发抢先）：仅当现有行序号不更新时插入
        return jdbcTemplate.update(
                "INSERT INTO signal_state(version_id, can_id, signal_name, raw_value, physical_value, "
                        + "enum_text, frame_seq, timestamp_ns) "
                        + "SELECT ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM signal_state "
                        + "WHERE version_id = ? AND can_id = ? AND signal_name = ? AND frame_seq >= ?)",
                versionId, canId, signalName, rawValue, physicalValue, enumText, frameSeq, timestampNs,
                versionId, canId, signalName, frameSeq);
    }

    @Override
    public int insertEventIfAbsent(long versionId, long canId, long frameSeq, long timestampNs,
                                   String signalName, Long oldRawValue, long newRawValue, long createdAtNano) {
        Object oldRawArg = oldRawValue == null
                ? new SqlParameterValue(Types.BIGINT, null)
                : oldRawValue;
        return jdbcTemplate.update(
                "INSERT INTO state_change_event(version_id, can_id, frame_seq, timestamp_ns, signal_name, "
                        + "old_raw_value, new_raw_value, created_at_nano) "
                        + "SELECT ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM state_change_event "
                        + "WHERE version_id = ? AND can_id = ? AND frame_seq = ? AND signal_name = ?)",
                versionId, canId, frameSeq, timestampNs, signalName,
                oldRawArg, newRawValue, createdAtNano,
                versionId, canId, frameSeq, signalName);
    }

    @Override
    public List<SignalStateRow> listStates(long versionId, long canId) {
        return jdbcTemplate.query(
                "SELECT signal_name, raw_value, physical_value, enum_text, frame_seq, timestamp_ns "
                        + "FROM signal_state WHERE version_id = ? AND can_id = ? ORDER BY signal_name",
                STATE_MAPPER, versionId, canId);
    }
}
