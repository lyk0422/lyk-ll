package com.example.starter.repo;

import com.example.starter.domain.BrakeCommand;
import org.springframework.dao.DuplicateKeyException;
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
 * 制动指令数据访问。breach_event_id 唯一约束保证同一越限事件只下发一次，重复触发返回首次指令。
 */
@Repository
public class BrakeRepository {

    private static final RowMapper<BrakeCommand> MAPPER = (rs, n) -> new BrakeCommand(
            rs.getLong("id"),
            rs.getLong("breach_event_id"),
            rs.getString("crane_id"),
            rs.getString("zone_id"),
            rs.getTimestamp("issued_at_utc").toInstant(),
            rs.getString("reason"));

    private final JdbcTemplate jdbc;

    public BrakeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等下发：若该越限事件已有指令则直接返回首次指令，否则插入并返回新指令。
     */
    public BrakeCommand issueOnce(long breachEventId, String craneId, String zoneId, Instant issuedAtUtc, String reason) {
        Optional<BrakeCommand> existing = findByBreachEventId(breachEventId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO brake_command (breach_event_id, crane_id, zone_id, issued_at_utc, reason)"
                                + " VALUES (?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, breachEventId);
                ps.setString(2, craneId);
                ps.setString(3, zoneId);
                ps.setTimestamp(4, Timestamp.from(issuedAtUtc));
                ps.setString(5, reason);
                return ps;
            }, keys);
            return new BrakeCommand(keys.getKey().longValue(), breachEventId, craneId, zoneId, issuedAtUtc, reason);
        } catch (DuplicateKeyException e) {
            // 并发下另一事务已下发，回读首次指令
            return findByBreachEventId(breachEventId).orElseThrow(() -> e);
        }
    }

    public Optional<BrakeCommand> findByBreachEventId(long breachEventId) {
        return jdbc.query("SELECT * FROM brake_command WHERE breach_event_id = ?", MAPPER, breachEventId)
                .stream().findFirst();
    }

    public List<BrakeCommand> findByCrane(String craneId) {
        return jdbc.query("SELECT * FROM brake_command WHERE crane_id = ? ORDER BY id", MAPPER, craneId);
    }
}
