package com.example.starter.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.domain.RetestRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 复测记录的数据访问。
 */
@Repository
public class RetestRepository {

    private static final RowMapper<RetestRecord> MAPPER = (rs, n) -> new RetestRecord(
            rs.getLong("id"),
            rs.getLong("transmitter_id"),
            rs.getLong("directive_id"),
            rs.getInt("actual_power_w"),
            rs.getInt("limit_w"),
            rs.getString("result"),
            rs.getTimestamp("measured_at").toLocalDateTime(),
            rs.getString("recorded_by"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public RetestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 落库复测记录（与限功率解除同事务）。
     *
     * @return 复测记录主键
     */
    public long insert(long transmitterId, long directiveId, int actualPowerW, int limitW, String result,
                       LocalDateTime measuredAt, String recordedBy, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO retest_record (transmitter_id, directive_id, actual_power_w, limit_w, result,"
                            + " measured_at, recorded_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, transmitterId);
            ps.setLong(2, directiveId);
            ps.setInt(3, actualPowerW);
            ps.setInt(4, limitW);
            ps.setString(5, result);
            ps.setTimestamp(6, Timestamp.valueOf(measuredAt));
            ps.setString(7, recordedBy);
            ps.setTimestamp(8, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<RetestRecord> findByDirective(long directiveId) {
        return jdbc.query("SELECT * FROM retest_record WHERE directive_id = ? ORDER BY id", MAPPER, directiveId);
    }
}
