package com.example.starter.repository;

import com.example.starter.enums.AuditEventType;
import com.example.starter.model.AuditRecord;
import com.example.starter.model.DeviceMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 审计记录仓储。
 */
@Repository
public class AuditRepository {

    private static final AuditRowMapper ROW_MAPPER = new AuditRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public AuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 写入一条审计记录。 */
    public void insert(AuditRecord record) {
        jdbcTemplate.update(
                "INSERT INTO audit_record "
                        + "(event_type, message_id, device_id, topic, seq, priority, reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                record.getEventType().name(),
                record.getMessageId(),
                record.getDeviceId(),
                record.getTopic(),
                record.getSeq(),
                record.getPriority(),
                record.getReason(),
                record.getCreatedAt());
    }

    /** 便捷构造并写入针对某条消息的审计事件。 */
    public void record(AuditEventType type, DeviceMessage message, String reason, long now) {
        AuditRecord record = new AuditRecord();
        record.setEventType(type);
        if (message != null) {
            record.setMessageId(message.getMessageId());
            record.setDeviceId(message.getDeviceId());
            record.setTopic(message.getTopic());
            record.setSeq(message.getSeq());
            record.setPriority(message.getPriority());
        }
        record.setReason(reason);
        record.setCreatedAt(now);
        insert(record);
    }

    /** 按事件类型查询审计记录（测试与排障用）。 */
    public List<AuditRecord> findByEventType(AuditEventType type) {
        return jdbcTemplate.query(
                "SELECT id, event_type, message_id, device_id, topic, seq, priority, reason, created_at "
                        + "FROM audit_record WHERE event_type = ? ORDER BY id ASC",
                ROW_MAPPER, type.name());
    }

    /** 查询全部审计记录，按时间升序（测试与排障用）。 */
    public List<AuditRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT id, event_type, message_id, device_id, topic, seq, priority, reason, created_at "
                        + "FROM audit_record ORDER BY id ASC",
                ROW_MAPPER);
    }

    private static final class AuditRowMapper implements RowMapper<AuditRecord> {
        @Override
        public AuditRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            AuditRecord record = new AuditRecord();
            record.setId(rs.getLong("id"));
            record.setEventType(AuditEventType.valueOf(rs.getString("event_type")));
            record.setMessageId(rs.getString("message_id"));
            record.setDeviceId(rs.getString("device_id"));
            record.setTopic(rs.getString("topic"));
            long seq = rs.getLong("seq");
            record.setSeq(rs.wasNull() ? null : seq);
            int priority = rs.getInt("priority");
            record.setPriority(rs.wasNull() ? null : priority);
            record.setReason(rs.getString("reason"));
            record.setCreatedAt(rs.getLong("created_at"));
            return record;
        }
    }
}
