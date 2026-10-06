package com.example.starter.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.DirectiveRevision;
import com.example.starter.domain.PowerDirective;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 限功率指令及其轨迹的数据访问。
 */
@Repository
public class DirectiveRepository {

    private static final RowMapper<PowerDirective> DIRECTIVE_MAPPER = (rs, n) -> new PowerDirective(
            rs.getLong("id"),
            rs.getLong("transmitter_id"),
            rs.getString("status"),
            rs.getInt("current_limit_w"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("lifted_at") == null ? null : rs.getTimestamp("lifted_at").toLocalDateTime(),
            rs.getString("lift_ops_confirmer"),
            rs.getString("lift_monitor_confirmer"));

    private static final RowMapper<DirectiveRevision> REVISION_MAPPER = (rs, n) -> new DirectiveRevision(
            rs.getLong("id"),
            rs.getLong("directive_id"),
            rs.getObject("event_id", Long.class),
            rs.getString("change_type"),
            rs.getInt("limit_w_after"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getString("note"));

    private final JdbcTemplate jdbc;

    public DirectiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询发射机执行中的指令并加行锁（同事务内串行化指令变更）。
     */
    public Optional<PowerDirective> findExecutingForUpdate(long transmitterId) {
        return jdbc.query("SELECT * FROM power_directive WHERE transmitter_id = ? AND status = 'EXECUTING' FOR UPDATE",
                DIRECTIVE_MAPPER, transmitterId).stream().findFirst();
    }

    public Optional<PowerDirective> findExecuting(long transmitterId) {
        return jdbc.query("SELECT * FROM power_directive WHERE transmitter_id = ? AND status = 'EXECUTING'",
                DIRECTIVE_MAPPER, transmitterId).stream().findFirst();
    }

    public Optional<PowerDirective> findById(long id) {
        return jdbc.query("SELECT * FROM power_directive WHERE id = ?", DIRECTIVE_MAPPER, id).stream().findFirst();
    }

    /**
     * 查询发射机最近一条指令（不限状态），用于轨迹查询。
     */
    public Optional<PowerDirective> findLatest(long transmitterId) {
        return jdbc.query("SELECT * FROM power_directive WHERE transmitter_id = ? ORDER BY id DESC LIMIT 1",
                DIRECTIVE_MAPPER, transmitterId).stream().findFirst();
    }

    /**
     * 下达执行中指令。
     *
     * @return 新指令主键
     */
    public long insert(long transmitterId, int limitW, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO power_directive (transmitter_id, status, current_limit_w, created_at)"
                            + " VALUES (?, 'EXECUTING', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, transmitterId);
            ps.setInt(2, limitW);
            ps.setTimestamp(3, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void updateLimit(long directiveId, int newLimitW) {
        jdbc.update("UPDATE power_directive SET current_limit_w = ? WHERE id = ?", newLimitW, directiveId);
    }

    public void lift(long directiveId, LocalDateTime liftedAt, String opsConfirmer, String monitorConfirmer) {
        jdbc.update("UPDATE power_directive SET status = 'LIFTED', lifted_at = ?,"
                        + " lift_ops_confirmer = ?, lift_monitor_confirmer = ? WHERE id = ?",
                Timestamp.valueOf(liftedAt), opsConfirmer, monitorConfirmer, directiveId);
    }

    /**
     * 追加指令轨迹。
     *
     * @return 轨迹主键
     */
    public long insertRevision(long directiveId, Long eventId, String changeType, int limitWAfter,
                               LocalDateTime createdAt, String note) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO power_directive_revision (directive_id, event_id, change_type, limit_w_after, created_at, note)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, directiveId);
            if (eventId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, eventId);
            }
            ps.setString(3, changeType);
            ps.setInt(4, limitWAfter);
            ps.setTimestamp(5, Timestamp.valueOf(createdAt));
            ps.setString(6, note);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<DirectiveRevision> findRevisions(long directiveId) {
        return jdbc.query("SELECT * FROM power_directive_revision WHERE directive_id = ? ORDER BY id",
                REVISION_MAPPER, directiveId);
    }

    /**
     * 按事件查询确认时产生的指令轨迹，用于幂等重放时复原首次结果。
     */
    public Optional<DirectiveRevision> findIssueRevisionByEvent(long eventId) {
        return jdbc.query("SELECT * FROM power_directive_revision WHERE event_id = ? AND change_type IN ('ISSUE', 'TIGHTEN')"
                        + " ORDER BY id LIMIT 1",
                REVISION_MAPPER, eventId).stream().findFirst();
    }
}
