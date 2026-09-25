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
import java.util.List;

/**
 * 复测记录数据访问。
 */
@Repository
public class RetestRepository {

    private static final RowMapper<RetestPo> MAPPER = (rs, n) -> new RetestPo(
            rs.getLong("id"),
            rs.getLong("silo_id"),
            rs.getLong("operation_id"),
            rs.getString("request_id"),
            rs.getInt("round_no"),
            rs.getDouble("max_temperature"),
            rs.getDouble("min_temperature"),
            rs.getDouble("gradient"),
            rs.getBoolean("passed"),
            rs.getTimestamp("measured_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public RetestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RetestPo insert(long siloId, long operationId, String requestId, int roundNo, double maxTemperature,
                           double minTemperature, double gradient, boolean passed, LocalDateTime measuredAt) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO retest (silo_id, operation_id, request_id, round_no, max_temperature, "
                            + "min_temperature, gradient, passed, measured_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, siloId);
            ps.setLong(2, operationId);
            ps.setString(3, requestId);
            ps.setInt(4, roundNo);
            ps.setDouble(5, maxTemperature);
            ps.setDouble(6, minTemperature);
            ps.setDouble(7, gradient);
            ps.setBoolean(8, passed);
            ps.setTimestamp(9, Timestamp.valueOf(measuredAt));
            ps.setTimestamp(10, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue();
        return new RetestPo(id, siloId, operationId, requestId, roundNo, maxTemperature, minTemperature,
                gradient, passed, measuredAt, now);
    }

    public List<RetestPo> findBySilo(long siloId) {
        return jdbc.query("SELECT * FROM retest WHERE silo_id = ? ORDER BY id", MAPPER, siloId);
    }
}
