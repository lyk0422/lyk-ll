package com.example.starter.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.DirectiveRow;
import com.example.starter.domain.DirectiveStatus;

/**
 * 限功率指令数据访问。executing_key 唯一约束保证同一发射机同时刻仅一条 EXECUTING 指令。
 */
@Repository
public class DirectiveRepository {

    private static final RowMapper<DirectiveRow> MAPPER = (rs, n) -> new DirectiveRow(
            rs.getLong("id"),
            rs.getLong("event_id"),
            rs.getLong("transmitter_id"),
            rs.getInt("limit_watts"),
            DirectiveStatus.valueOf(rs.getString("status")),
            rs.getObject("actual_power_watts") == null ? null : rs.getInt("actual_power_watts"),
            rs.getString("confirmed_by"),
            rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("superseded_at") == null ? null : rs.getTimestamp("superseded_at").toInstant(),
            rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant());

    private static final String COLUMNS =
            "id, event_id, transmitter_id, limit_watts, status, actual_power_watts, confirmed_by,"
                    + " confirmed_at, created_at, superseded_at, released_at";

    private final JdbcTemplate jdbc;

    public DirectiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertExecuting(long eventId, long transmitterId, int limitWatts, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO power_directive (event_id, transmitter_id, limit_watts, status,"
                            + " executing_key, created_at) VALUES (?, ?, ?, 'EXECUTING', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, eventId);
            ps.setLong(2, transmitterId);
            ps.setInt(3, limitWatts);
            ps.setLong(4, transmitterId);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<DirectiveRow> findById(long id) {
        List<DirectiveRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM power_directive WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<DirectiveRow> findExecutingByTransmitter(long transmitterId) {
        List<DirectiveRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM power_directive"
                        + " WHERE transmitter_id = ? AND status = 'EXECUTING'",
                MAPPER, transmitterId);
        return rows.stream().findFirst();
    }

    public List<DirectiveRow> findByTransmitter(long transmitterId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM power_directive WHERE transmitter_id = ? ORDER BY id",
                MAPPER, transmitterId);
    }

    public void supersede(long directiveId, Instant supersededAt) {
        jdbc.update(
                "UPDATE power_directive SET status = 'SUPERSEDED', executing_key = NULL,"
                        + " superseded_at = ? WHERE id = ?",
                Timestamp.from(supersededAt), directiveId);
    }

    public void release(long directiveId, Instant releasedAt) {
        jdbc.update(
                "UPDATE power_directive SET status = 'RELEASED', executing_key = NULL,"
                        + " released_at = ? WHERE id = ?",
                Timestamp.from(releasedAt), directiveId);
    }

    /**
     * 执行确认：记录实际上报功率与确认人。
     */
    public void confirmExecution(long directiveId, int actualPowerWatts, String confirmedBy, Instant confirmedAt) {
        jdbc.update(
                "UPDATE power_directive SET actual_power_watts = ?, confirmed_by = ?, confirmed_at = ?"
                        + " WHERE id = ?",
                actualPowerWatts, confirmedBy, Timestamp.from(confirmedAt), directiveId);
    }
}
