package com.example.starter.ventilation.repo;

import com.example.starter.ventilation.domain.EventLogRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 有序事件流水仓储：自增 id 即全序，可供按序查询全部指令、反馈与状态迁移。
 */
@Repository
public class EventLogRepository {

    private static final RowMapper<EventLogRow> MAPPER = (rs, n) -> new EventLogRow(
            rs.getLong("id"),
            rs.getString("event_type"),
            nullableLong(rs, "session_id"),
            rs.getString("zone_code"),
            rs.getString("request_id"),
            rs.getString("detail"),
            rs.getLong("event_time"));

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private final JdbcTemplate jdbc;

    public EventLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加事件。 */
    public void append(String eventType, Long sessionId, String zoneCode, String requestId,
                       String detail, long eventTime) {
        jdbc.update(
                "INSERT INTO event_log (event_type, session_id, zone_code, request_id, detail, event_time) "
                        + "VALUES (?,?,?,?,?,?)",
                eventType, sessionId, zoneCode, requestId, detail, eventTime);
    }

    /** 按会话查询事件（指令/反馈/状态迁移全序）。 */
    public List<EventLogRow> findBySession(long sessionId) {
        return jdbc.query(
                "SELECT id, event_type, session_id, zone_code, request_id, detail, event_time "
                        + "FROM event_log WHERE session_id = ? ORDER BY id ASC", MAPPER, sessionId);
    }

    /** 按分区查询事件。 */
    public List<EventLogRow> findByZone(String zoneCode) {
        return jdbc.query(
                "SELECT id, event_type, session_id, zone_code, request_id, detail, event_time "
                        + "FROM event_log WHERE zone_code = ? ORDER BY id ASC", MAPPER, zoneCode);
    }

    /** 分页查询全部事件，按全序。 */
    public List<EventLogRow> findPage(long afterId, int limit) {
        return jdbc.query(
                "SELECT id, event_type, session_id, zone_code, request_id, detail, event_time "
                        + "FROM event_log WHERE id > ? ORDER BY id ASC LIMIT ?",
                MAPPER, afterId, limit);
    }
}
