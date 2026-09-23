package com.example.starter.repo.jdbc;

import com.example.starter.domain.MessageStatus;
import com.example.starter.po.DeviceMessage;
import com.example.starter.repo.DeviceMessageRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 基于 {@link JdbcTemplate} 的消息访问。补发顺序：优先级降序、设备本地序号升序。
 */
@Repository
public class JdbcDeviceMessageRepository implements DeviceMessageRepository {

    private static final String COLUMNS =
            "id, device_id, topic, seq, priority, dedup_key, payload, status, attempts, enqueued_at, sent_at";

    private static final RowMapper<DeviceMessage> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp sentAt = rs.getTimestamp("sent_at");
        return new DeviceMessage(
                rs.getLong("id"),
                rs.getString("device_id"),
                rs.getString("topic"),
                rs.getLong("seq"),
                rs.getInt("priority"),
                rs.getString("dedup_key"),
                rs.getString("payload"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getTimestamp("enqueued_at").toInstant(),
                sentAt == null ? null : sentAt.toInstant());
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcDeviceMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public DeviceMessage insert(String deviceId, String topic, long seq, int priority,
                                String dedupKey, String payload, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO device_message (device_id, topic, seq, priority, dedup_key, payload, "
                            + "status, attempts, enqueued_at, sent_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, deviceId);
            ps.setString(2, topic);
            ps.setLong(3, seq);
            ps.setInt(4, priority);
            ps.setString(5, dedupKey);
            ps.setString(6, payload);
            ps.setString(7, MessageStatus.PENDING.name());
            ps.setInt(8, 0);
            ps.setTimestamp(9, Timestamp.from(now));
            ps.setObject(10, null);
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue();
        return new DeviceMessage(id, deviceId, topic, seq, priority, dedupKey, payload,
                MessageStatus.PENDING.name(), 0, now, null);
    }

    @Override
    public Optional<DeviceMessage> findByDedupKey(String dedupKey) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE dedup_key = ?",
                ROW_MAPPER, dedupKey).stream().findFirst();
    }

    @Override
    public List<DeviceMessage> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message ORDER BY id", ROW_MAPPER);
    }

    @Override
    public List<DeviceMessage> findPending(int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE status = ? "
                        + "ORDER BY priority DESC, seq ASC, id ASC LIMIT ?",
                ROW_MAPPER, MessageStatus.PENDING.name(), limit);
    }

    @Override
    public int countPendingByTopic(String topic) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_message WHERE topic = ? AND status = ?",
                Integer.class, topic, MessageStatus.PENDING.name());
        return n == null ? 0 : n;
    }

    @Override
    public long countAll() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_message", Long.class);
        return n == null ? 0L : n;
    }

    @Override
    public long countByStatus(MessageStatus status) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_message WHERE status = ?", Long.class, status.name());
        return n == null ? 0L : n;
    }

    @Override
    public Optional<DeviceMessage> findLatestPendingByTopic(String topic) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE topic = ? AND status = ? "
                        + "ORDER BY seq DESC, id DESC LIMIT 1",
                ROW_MAPPER, topic, MessageStatus.PENDING.name()).stream().findFirst();
    }

    @Override
    public boolean markInFlight(long id, Instant now) {
        int updated = jdbcTemplate.update(
                "UPDATE device_message SET status = ?, attempts = attempts + 1, sent_at = ? "
                        + "WHERE id = ? AND status = ?",
                MessageStatus.IN_FLIGHT.name(), Timestamp.from(now), id,
                MessageStatus.PENDING.name());
        return updated > 0;
    }

    @Override
    public boolean revertToPending(long id) {
        int updated = jdbcTemplate.update(
                "UPDATE device_message SET status = ?, sent_at = NULL WHERE id = ? AND status = ?",
                MessageStatus.PENDING.name(), id, MessageStatus.IN_FLIGHT.name());
        return updated > 0;
    }

    @Override
    public Optional<DeviceMessage> deleteById(long id) {
        Optional<DeviceMessage> message = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE id = ?",
                ROW_MAPPER, id).stream().findFirst();
        message.ifPresent(m -> jdbcTemplate.update("DELETE FROM device_message WHERE id = ?", id));
        return message;
    }

    @Override
    public Optional<DeviceMessage> deleteByDedupKey(String dedupKey) {
        Optional<DeviceMessage> message = findByDedupKey(dedupKey);
        message.ifPresent(m ->
                jdbcTemplate.update("DELETE FROM device_message WHERE dedup_key = ?", dedupKey));
        return message;
    }

    @Override
    public List<DeviceMessage> resetStaleInFlight(Instant cutoff) {
        List<DeviceMessage> stale = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE status = ? "
                        + "AND sent_at IS NOT NULL AND sent_at < ?",
                ROW_MAPPER, MessageStatus.IN_FLIGHT.name(), Timestamp.from(cutoff));
        if (!stale.isEmpty()) {
            jdbcTemplate.update(
                    "UPDATE device_message SET status = ?, sent_at = NULL "
                            + "WHERE status = ? AND sent_at IS NOT NULL AND sent_at < ?",
                    MessageStatus.PENDING.name(), MessageStatus.IN_FLIGHT.name(),
                    Timestamp.from(cutoff));
        }
        return stale;
    }

    @Override
    public List<DeviceMessage> resetAllInFlight() {
        List<DeviceMessage> inflight = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE status = ?",
                ROW_MAPPER, MessageStatus.IN_FLIGHT.name());
        if (!inflight.isEmpty()) {
            jdbcTemplate.update(
                    "UPDATE device_message SET status = ?, sent_at = NULL WHERE status = ?",
                    MessageStatus.PENDING.name(), MessageStatus.IN_FLIGHT.name());
        }
        return inflight;
    }

    @Override
    public Optional<DeviceMessage> findEvictable() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE status = ? "
                        + "ORDER BY priority ASC, enqueued_at ASC, id ASC LIMIT 1",
                ROW_MAPPER, MessageStatus.PENDING.name()).stream().findFirst();
    }

    @Override
    public Optional<DeviceMessage> findOldestPendingByTopic(String topic) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE topic = ? AND status = ? "
                        + "ORDER BY enqueued_at ASC, seq ASC, id ASC LIMIT 1",
                ROW_MAPPER, topic, MessageStatus.PENDING.name()).stream().findFirst();
    }
}
