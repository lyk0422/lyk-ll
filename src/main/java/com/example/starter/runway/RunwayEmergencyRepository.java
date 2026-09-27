package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 跑道紧急关闭事件数据访问：runway_emergency 表；active_flag=1 占用同跑道唯一进行中紧急事件。
 */
@Repository
public class RunwayEmergencyRepository {

    private static final RowMapper<RunwayEmergency> MAPPER = (rs, rowNum) -> new RunwayEmergency(
            rs.getLong("id"),
            rs.getString("runway_id"),
            rs.getString("reason"),
            EmergencyStatus.valueOf(rs.getString("status")),
            (Integer) rs.getObject("active_flag"),
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant(),
            rs.getInt("version"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public RunwayEmergencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RunwayEmergency insert(RunwayEmergency emergency) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO runway_emergency (runway_id, reason, status, active_flag, started_at, ended_at,"
                            + " version, request_id, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, emergency.runwayId());
            ps.setString(2, emergency.reason());
            ps.setString(3, emergency.status().name());
            ps.setObject(4, emergency.activeFlag(), Types.TINYINT);
            ps.setTimestamp(5, Timestamp.from(emergency.startedAt()));
            ps.setTimestamp(6, emergency.endedAt() == null ? null : Timestamp.from(emergency.endedAt()));
            ps.setInt(7, emergency.version());
            ps.setString(8, emergency.requestId());
            ps.setTimestamp(9, Timestamp.from(emergency.createdAt()));
            ps.setTimestamp(10, Timestamp.from(emergency.updatedAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return findById(id).orElseThrow();
    }

    public Optional<RunwayEmergency> findById(long id) {
        List<RunwayEmergency> rows = jdbc.query("SELECT * FROM runway_emergency WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<RunwayEmergency> findActive(String runwayId) {
        List<RunwayEmergency> rows = jdbc.query(
                "SELECT * FROM runway_emergency WHERE runway_id = ? AND active_flag = 1", MAPPER, runwayId);
        return rows.stream().findFirst();
    }

    /**
     * 乐观结束：仅当 id 与 expectedVersion 匹配且仍进行中时写入，返回受影响行数。
     */
    public int casEnd(long id, int expectedVersion, Instant endedAt, Instant updatedAt) {
        return jdbc.update("UPDATE runway_emergency SET status = 'ENDED', active_flag = NULL, ended_at = ?,"
                        + " version = version + 1, updated_at = ? WHERE id = ? AND version = ? AND active_flag = 1",
                Timestamp.from(endedAt), Timestamp.from(updatedAt), id, expectedVersion);
    }
}
