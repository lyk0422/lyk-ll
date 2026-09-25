package com.example.starter.tailings.repo;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 降雨预警与险情报送存取。
 */
@Repository
public class EventRepository {

    private final JdbcTemplate jdbc;

    public EventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertWarning(String requestId, long pondId, java.math.BigDecimal rainfallMm,
                              LocalDateTime warnTime) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rainfall_warning (request_id, pond_id, rainfall_mm, warn_time)"
                            + " VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setLong(2, pondId);
            ps.setBigDecimal(3, rainfallMm);
            ps.setTimestamp(4, Timestamp.valueOf(warnTime));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public long insertDangerReport(String requestId, long pondId, String description) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO danger_report (request_id, pond_id, description) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setLong(2, pondId);
            ps.setString(3, description);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
