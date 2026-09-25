package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 速率超速告警访问：速率大于 18m/min 才落告警，恰 18 不告警。
 */
@Repository
public class AlertRepository {

    private static final RowMapper<AlertRow> MAPPER = (rs, n) -> new AlertRow(
            rs.getLong("id"),
            rs.getString("dive_request_id"),
            rs.getInt("plan_version"),
            rs.getString("diver_code"),
            rs.getString("phase"),
            rs.getBigDecimal("rate_m_min"),
            rs.getTimestamp("event_time_utc").toInstant(),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insert(AlertRow row) {
        return jdbc.update("INSERT INTO speed_alert "
                        + "(dive_request_id, plan_version, diver_code, phase, rate_m_min, "
                        + "event_time_utc, created_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.diveRequestId(), row.planVersion(), row.diverCode(), row.phase(),
                row.rateMMin(), Timestamp.from(row.eventTimeUtc()),
                Timestamp.from(row.createdAtUtc() == null ? Instant.now() : row.createdAtUtc()));
    }

    public List<AlertRow> findByDive(String requestId) {
        return jdbc.query("SELECT * FROM speed_alert WHERE dive_request_id = ? ORDER BY id",
                MAPPER, requestId);
    }
}
