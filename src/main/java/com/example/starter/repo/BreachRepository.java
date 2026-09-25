package com.example.starter.repo;

import com.example.starter.domain.BreachEvent;
import com.example.starter.domain.BreachStatus;
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
import java.util.Optional;

/**
 * 越限预警事件数据访问。事件不可删除、不可修改业务字段，仅允许按规则推进状态（留痕）。
 */
@Repository
public class BreachRepository {

    private static final RowMapper<BreachEvent> MAPPER = (rs, n) -> new BreachEvent(
            rs.getLong("id"),
            rs.getString("crane_id"),
            rs.getString("zone_id"),
            rs.getTimestamp("first_detected_at_utc").toInstant(),
            rs.getTimestamp("last_detected_at_utc").toInstant(),
            BreachStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("resolved_at_utc") == null ? null : rs.getTimestamp("resolved_at_utc").toInstant(),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public BreachRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BreachEvent insert(String craneId, String zoneId, Instant detectedAtUtc, Instant createdAtUtc) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO breach_event (crane_id, zone_id, first_detected_at_utc, last_detected_at_utc,"
                            + " status, resolved_at_utc, created_at_utc) VALUES (?,?,?,?, 'OPEN', NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, craneId);
            ps.setString(2, zoneId);
            ps.setTimestamp(3, Timestamp.from(detectedAtUtc));
            ps.setTimestamp(4, Timestamp.from(detectedAtUtc));
            ps.setTimestamp(5, Timestamp.from(createdAtUtc));
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        return new BreachEvent(id, craneId, zoneId, detectedAtUtc, detectedAtUtc, BreachStatus.OPEN, null, createdAtUtc);
    }

    /**
     * 查询塔机未了结（预警中或已制动未退出）的越限事件。
     */
    public List<BreachEvent> findOpenByCrane(String craneId) {
        return jdbc.query("SELECT * FROM breach_event WHERE crane_id = ? AND status IN ('OPEN','BRAKED') ORDER BY id",
                MAPPER, craneId);
    }

    public Optional<BreachEvent> findOpenByCraneAndZone(String craneId, String zoneId) {
        return jdbc.query("SELECT * FROM breach_event WHERE crane_id = ? AND zone_id = ?"
                        + " AND status IN ('OPEN','BRAKED') ORDER BY id",
                MAPPER, craneId, zoneId).stream().findFirst();
    }

    /**
     * 按时间戳归位更新越限窗口：首越限取更小值、末越限取更大值，兼容乱序到达。
     */
    public void updateWindow(long id, Instant firstDetectedAtUtc, Instant lastDetectedAtUtc) {
        jdbc.update("UPDATE breach_event SET first_detected_at_utc = ?, last_detected_at_utc = ? WHERE id = ?",
                Timestamp.from(firstDetectedAtUtc), Timestamp.from(lastDetectedAtUtc), id);
    }

    public void markBraked(long id) {
        jdbc.update("UPDATE breach_event SET status = 'BRAKED' WHERE id = ?", id);
    }

    public void markResolved(long id, Instant resolvedAtUtc) {
        jdbc.update("UPDATE breach_event SET status = 'RESOLVED', resolved_at_utc = ? WHERE id = ?",
                Timestamp.from(resolvedAtUtc), id);
    }

    public List<BreachEvent> findByCrane(String craneId) {
        return jdbc.query("SELECT * FROM breach_event WHERE crane_id = ? ORDER BY id", MAPPER, craneId);
    }
}
