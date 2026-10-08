package com.example.starter.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.HandoverStatus;

/**
 * 交班单数据访问：申请、签认、原子切换、撤回与核销竞争关闭。
 * 同计划只允许一条未结束（PENDING）交班，由业务层在计划行锁内保证。
 */
@Repository
public class HandoverRepository {

    /** 交班单行映射。 */
    public record HandoverRow(
            long id,
            long planId,
            long fromPilotId,
            long toPilotId,
            LocalDateTime handoverAt,
            HandoverStatus status,
            boolean fromConfirmed,
            boolean toConfirmed,
            String fromComment,
            String toComment,
            long planVersion,
            long version,
            LocalDateTime switchedAt) {
    }

    private static final RowMapper<HandoverRow> MAPPER = (rs, n) -> new HandoverRow(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getLong("from_pilot_id"),
            rs.getLong("to_pilot_id"),
            toLdt(rs, "handover_at"),
            HandoverStatus.valueOf(rs.getString("status")),
            rs.getBoolean("from_confirmed"),
            rs.getBoolean("to_confirmed"),
            rs.getString("from_comment"),
            rs.getString("to_comment"),
            rs.getLong("plan_version"),
            rs.getLong("version"),
            toLdt(rs, "switched_at"));

    private static LocalDateTime toLdt(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private final JdbcTemplate jdbc;

    public HandoverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入 PENDING 交班单，返回自增主键。 */
    public long insert(long planId, long fromPilotId, long toPilotId, LocalDateTime handoverAt,
                       long planVersion, LocalDateTime now) {
        org.springframework.jdbc.support.KeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO pilot_handover (plan_id, from_pilot_id, to_pilot_id, handover_at,"
                            + " status, from_confirmed, to_confirmed, plan_version, version,"
                            + " created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, 'PENDING', FALSE, FALSE, ?, 1, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, planId);
            ps.setLong(2, fromPilotId);
            ps.setLong(3, toPilotId);
            ps.setTimestamp(4, Timestamp.valueOf(handoverAt));
            ps.setLong(5, planVersion);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            ps.setTimestamp(7, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按主键查询。 */
    public Optional<HandoverRow> findById(long id) {
        return jdbc.query("SELECT * FROM pilot_handover WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按主键加行锁查询。 */
    public Optional<HandoverRow> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM pilot_handover WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /** 查询某计划当前未结束（PENDING）的交班单。 */
    public Optional<HandoverRow> findPendingByPlan(long planId) {
        return jdbc.query(
                "SELECT * FROM pilot_handover WHERE plan_id = ? AND status = 'PENDING'",
                MAPPER, planId).stream().findFirst();
    }

    /** 查询某计划已原子切换（SWITCHED）的交班单，用于计算实际责任段。 */
    public Optional<HandoverRow> findSwitchedByPlan(long planId) {
        return jdbc.query(
                "SELECT * FROM pilot_handover WHERE plan_id = ? AND status = 'SWITCHED'",
                MAPPER, planId).stream().findFirst();
    }

    /**
     * 出班/接班引航员签认（乐观版本）。column 为 from_confirmed 或 to_confirmed，
     * 仅当交班单仍为 PENDING 且版本等于 expectedVersion 时生效，返回受影响行数。
     */
    public int confirm(long handoverId, boolean fromSide, String comment,
                       long expectedVersion, LocalDateTime now) {
        String confirmColumn = fromSide ? "from_confirmed" : "to_confirmed";
        String commentColumn = fromSide ? "from_comment" : "to_comment";
        String sql = "UPDATE pilot_handover SET " + confirmColumn + " = TRUE, "
                + commentColumn + " = ?, version = version + 1, updated_at = ?"
                + " WHERE id = ? AND status = 'PENDING' AND version = ?";
        return jdbc.update(sql, comment, Timestamp.valueOf(now), handoverId, expectedVersion);
    }

    /**
     * 原子切换完成：PENDING 且版本一致时置 SWITCHED 并记录切换时间，版本加 1。
     * 返回受影响行数（0 表示状态已变或版本过期）。
     */
    public int markSwitched(long handoverId, long expectedVersion, LocalDateTime now) {
        return jdbc.update("UPDATE pilot_handover SET status = 'SWITCHED', switched_at = ?,"
                        + " plan_version = plan_version + 1, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND status = 'PENDING' AND version = ?",
                Timestamp.valueOf(now), Timestamp.valueOf(now), handoverId, expectedVersion);
    }

    /** 切换前置 WITHDRAWN（乐观版本），返回受影响行数。 */
    public int markWithdrawn(long handoverId, long expectedVersion, LocalDateTime now) {
        return jdbc.update("UPDATE pilot_handover SET status = 'WITHDRAWN',"
                        + " version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND status = 'PENDING' AND version = ?",
                Timestamp.valueOf(now), handoverId, expectedVersion);
    }

    /**
     * 核销竞争关闭：把某计划全部未结束（PENDING）交班单置 CLOSED，版本加 1。
     * 在计划行锁内调用，返回被关闭的交班单数量。
     */
    public int closePendingByPlan(long planId, LocalDateTime now) {
        List<HandoverRow> pending = jdbc.query(
                "SELECT * FROM pilot_handover WHERE plan_id = ? AND status = 'PENDING' FOR UPDATE",
                MAPPER, planId);
        for (HandoverRow row : pending) {
            jdbc.update("UPDATE pilot_handover SET status = 'CLOSED', version = version + 1,"
                            + " updated_at = ? WHERE id = ?",
                    Timestamp.valueOf(now), row.id());
        }
        return pending.size();
    }

    /** 查询某计划被核销竞争关闭时需要释放接班预占的 PENDING 交班单。 */
    public List<HandoverRow> findPendingByPlanForUpdate(long planId) {
        return jdbc.query(
                "SELECT * FROM pilot_handover WHERE plan_id = ? AND status = 'PENDING' FOR UPDATE",
                MAPPER, planId);
    }
}
