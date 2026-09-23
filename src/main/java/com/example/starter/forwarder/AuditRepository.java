package com.example.starter.forwarder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 审计表数据访问。
 */
@Repository
public class AuditRepository {

    /**
     * 审计记录行。
     */
    public record AuditRow(
            long id,
            AuditEventType eventType,
            String deviceId,
            String topic,
            Long deviceSeq,
            String dedupKey,
            String detail,
            LocalDateTime createdAt) {
    }

    private static final RowMapper<AuditRow> MAPPER = new RowMapper<>() {
        @Override
        public AuditRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            long seq = rs.getLong("device_seq");
            return new AuditRow(
                    rs.getLong("id"),
                    AuditEventType.valueOf(rs.getString("event_type")),
                    rs.getString("device_id"),
                    rs.getString("topic"),
                    rs.wasNull() ? null : seq,
                    rs.getString("dedup_key"),
                    rs.getString("detail"),
                    rs.getTimestamp("created_at").toLocalDateTime());
        }
    };

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条审计记录。
     */
    public void record(AuditEventType type, String deviceId, String topic, Long deviceSeq,
                       String dedupKey, String detail, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO forwarder_audit
                            (event_type, device_id, topic, device_seq, dedup_key, detail, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                type.name(), deviceId, topic, deviceSeq, dedupKey, detail, Timestamp.valueOf(now));
    }

    /**
     * 依据消息行写审计的便捷方法。
     */
    public void recordFor(AuditEventType type, MessageView message, String detail, LocalDateTime now) {
        record(type, message.deviceId(), message.topic(), message.deviceSeq(),
                message.dedupKey(), detail, now);
    }

    public List<AuditRow> findAll() {
        return jdbc.query("SELECT * FROM forwarder_audit ORDER BY id ASC", MAPPER);
    }

    public List<AuditRow> findByType(AuditEventType type) {
        return jdbc.query("SELECT * FROM forwarder_audit WHERE event_type = ? ORDER BY id ASC",
                MAPPER, type.name());
    }

    public long countByType(AuditEventType type) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM forwarder_audit WHERE event_type = ?",
                Long.class, type.name());
        return count == null ? 0 : count;
    }
}
