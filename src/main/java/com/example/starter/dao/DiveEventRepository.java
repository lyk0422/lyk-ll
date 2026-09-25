package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 潜次事件流访问：加压舱预案、结案后迟到体征、方案冻结。
 */
@Repository
public class DiveEventRepository {

    private static final RowMapper<DiveEventRow> MAPPER = (rs, n) -> new DiveEventRow(
            rs.getLong("id"),
            rs.getString("dive_request_id"),
            rs.getString("type"),
            rs.getString("payload"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public DiveEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insert(String requestId, String type, String payload, Instant now) {
        return jdbc.update("INSERT INTO dive_event (dive_request_id, type, payload, created_at_utc) "
                        + "VALUES (?, ?, ?, ?)",
                requestId, type, payload, Timestamp.from(now));
    }

    public List<DiveEventRow> findByDive(String requestId) {
        return jdbc.query("SELECT * FROM dive_event WHERE dive_request_id = ? ORDER BY id",
                MAPPER, requestId);
    }

    public long countByType(String requestId, String type) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM dive_event WHERE dive_request_id = ? AND type = ?",
                Integer.class, requestId, type);
        return count == null ? 0 : count;
    }
}
