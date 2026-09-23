package com.example.starter.ros.db;

import com.example.starter.ros.EventRecord;
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

/**
 * 单调游标事件流数据访问。
 */
@Repository
public class EventRepository {

    private static final RowMapper<EventRecord> MAPPER = (rs, n) -> new EventRecord(
            rs.getLong("id"),
            rs.getString("transaction_id"),
            rs.getString("event_type"),
            rs.getString("payload_json"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public EventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入一条事务事件，返回生成的单调游标。 */
    public long insert(String transactionId, String eventType, String payloadJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO param_event (transaction_id, event_type, payload_json, created_at) "
                            + "VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, transactionId);
            ps.setString(2, eventType);
            ps.setString(3, payloadJson);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("event insert did not return generated cursor");
        }
        return key.longValue();
    }

    /** 严格返回游标大于 after 的事件（升序），最多 limit 条。 */
    public List<EventRecord> findAfter(long after, int limit) {
        return jdbcTemplate.query(
                "SELECT id, transaction_id, event_type, payload_json, created_at "
                        + "FROM param_event WHERE id > ? ORDER BY id ASC LIMIT ?",
                MAPPER, after, limit);
    }
}
