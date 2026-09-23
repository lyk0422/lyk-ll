package com.example.starter.repo.jdbc;

import com.example.starter.domain.AuditType;
import com.example.starter.po.AuditRecord;
import com.example.starter.repo.AuditRepository;
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

/** 基于 {@link JdbcTemplate} 的审计记录访问。 */
@Repository
public class JdbcAuditRepository implements AuditRepository {

    private static final RowMapper<AuditRecord> ROW_MAPPER = (rs, rowNum) -> new AuditRecord(
            rs.getLong("id"),
            rs.getString("event_type"),
            rs.getString("device_id"),
            rs.getString("topic"),
            (Long) rs.getObject("seq"),
            rs.getString("dedup_key"),
            (Long) rs.getObject("message_id"),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AuditRecord insert(AuditType type, String deviceId, String topic, Long seq,
                              String dedupKey, Long messageId, String reason, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO audit_record (event_type, device_id, topic, seq, dedup_key, "
                            + "message_id, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, type.name());
            ps.setString(2, deviceId);
            ps.setString(3, topic);
            ps.setObject(4, seq);
            ps.setString(5, dedupKey);
            ps.setObject(6, messageId);
            ps.setString(7, reason);
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue();
        return new AuditRecord(id, type.name(), deviceId, topic, seq, dedupKey, messageId,
                reason, now);
    }

    @Override
    public List<AuditRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT id, event_type, device_id, topic, seq, dedup_key, message_id, reason, created_at "
                        + "FROM audit_record ORDER BY id",
                ROW_MAPPER);
    }

    @Override
    public List<AuditRecord> findByType(AuditType type) {
        return jdbcTemplate.query(
                "SELECT id, event_type, device_id, topic, seq, dedup_key, message_id, reason, created_at "
                        + "FROM audit_record WHERE event_type = ? ORDER BY id",
                ROW_MAPPER, type.name());
    }

    @Override
    public long countByType(AuditType type) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE event_type = ?",
                Long.class, type.name());
        return n == null ? 0L : n;
    }

    @Override
    public boolean existsDelivered(String dedupKey) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE event_type = ? AND dedup_key = ?",
                Long.class, AuditType.DELIVERED.name(), dedupKey);
        return n != null && n > 0;
    }
}
