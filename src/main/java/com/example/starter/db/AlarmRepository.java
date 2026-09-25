package com.example.starter.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 告警记录数据访问。
 */
@Repository
public class AlarmRepository {

    private static final RowMapper<AlarmRow> MAPPER = (rs, n) -> new AlarmRow(
            rs.getLong("id"),
            rs.getLong("silo_id"),
            rs.getString("request_id"),
            rs.getDouble("max_temperature"),
            rs.getDouble("min_temperature"),
            rs.getBoolean("alarmed"),
            rs.getTimestamp("measured_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public AlarmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AlarmRow insert(long siloId, String requestId, double maxTemperature, double minTemperature,
                           boolean alarmed, LocalDateTime measuredAt) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO alarm (silo_id, request_id, max_temperature, min_temperature, alarmed, "
                            + "measured_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, siloId);
            ps.setString(2, requestId);
            ps.setDouble(3, maxTemperature);
            ps.setDouble(4, minTemperature);
            ps.setBoolean(5, alarmed);
            ps.setTimestamp(6, Timestamp.valueOf(measuredAt));
            ps.setTimestamp(7, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue();
        return new AlarmRow(id, siloId, requestId, maxTemperature, minTemperature, alarmed, measuredAt, now);
    }

    /**
     * 告警记录行。
     */
    public record AlarmRow(long id, long siloId, String requestId, double maxTemperature, double minTemperature,
                           boolean alarmed, LocalDateTime measuredAt, LocalDateTime createdAt) {
    }
}
