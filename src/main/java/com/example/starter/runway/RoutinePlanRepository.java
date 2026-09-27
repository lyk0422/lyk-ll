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
import java.util.Optional;

/**
 * 例行巡检计划数据访问：routine_plan 表。
 */
@Repository
public class RoutinePlanRepository {

    private static final RowMapper<RoutinePlan> MAPPER = (rs, rowNum) -> new RoutinePlan(
            rs.getLong("id"),
            rs.getString("runway_id"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            rs.getInt("duration_minutes"),
            rs.getInt("calendar_version"),
            rs.getInt("version"),
            RoutinePlanStatus.valueOf(rs.getString("status")),
            rs.getString("evidence"),
            (Integer) rs.getObject("slot_flag"),
            (Long) rs.getObject("emergency_event_id"),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getTimestamp("preempted_at") == null ? null : rs.getTimestamp("preempted_at").toInstant(),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public RoutinePlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入计划；slotFlag=1 时占用“同跑道同窗唯一获准”唯一索引。初始聚合版本为1。 */
    public RoutinePlan insert(RoutinePlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO routine_plan (runway_id, window_start, window_end, duration_minutes,"
                            + " calendar_version, version, status, evidence, slot_flag, emergency_event_id,"
                            + " started_at, completed_at, preempted_at, request_id, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, plan.runwayId());
            ps.setTimestamp(2, Timestamp.from(plan.windowStart()));
            ps.setTimestamp(3, Timestamp.from(plan.windowEnd()));
            ps.setInt(4, plan.durationMinutes());
            ps.setInt(5, plan.calendarVersion());
            ps.setInt(6, plan.version() <= 0 ? 1 : plan.version());
            ps.setString(7, plan.status().name());
            ps.setString(8, plan.evidence());
            if (plan.slotFlag() == null) {
                ps.setNull(9, Types.TINYINT);
            } else {
                ps.setInt(9, plan.slotFlag());
            }
            if (plan.emergencyEventId() == null) {
                ps.setNull(10, Types.BIGINT);
            } else {
                ps.setLong(10, plan.emergencyEventId());
            }
            ps.setTimestamp(11, plan.startedAt() == null ? null : Timestamp.from(plan.startedAt()));
            ps.setTimestamp(12, plan.completedAt() == null ? null : Timestamp.from(plan.completedAt()));
            ps.setTimestamp(13, plan.preemptedAt() == null ? null : Timestamp.from(plan.preemptedAt()));
            ps.setString(14, plan.requestId());
            ps.setTimestamp(15, Timestamp.from(plan.createdAt()));
            ps.setTimestamp(16, Timestamp.from(plan.updatedAt()));
            return ps;
        }, keyHolder);
        long id = Optional.ofNullable(keyHolder.getKey()).orElseThrow().longValue();
        return findById(id).orElseThrow();
    }

    public Optional<RoutinePlan> findById(long id) {
        List<RoutinePlan> rows = jdbc.query("SELECT * FROM routine_plan WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 按跑道列出全部计划（含终态），按时间与主键排序。 */
    public List<RoutinePlan> findByRunway(String runwayId) {
        return jdbc.query("SELECT * FROM routine_plan WHERE runway_id = ? ORDER BY window_start, id",
                MAPPER, runwayId);
    }

    /** 行级锁定取计划，供开始/完成/抢占等状态流转串行化。 */
    public Optional<RoutinePlan> findByIdForUpdate(long id) {
        List<RoutinePlan> rows = jdbc.query("SELECT * FROM routine_plan WHERE id = ? FOR UPDATE", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 取同跑道同窗、且仍占用唯一获准位（slot_flag=1）的计划。 */
    public List<RoutinePlan> findSlotHolders(String runwayId, Instant windowStart, Instant windowEnd) {
        return jdbc.query(
                "SELECT * FROM routine_plan WHERE runway_id = ? AND window_start = ? AND window_end = ?"
                        + " AND slot_flag = 1",
                MAPPER, runwayId, Timestamp.from(windowStart), Timestamp.from(windowEnd));
    }

    /**
     * 全量更新计划状态并把聚合乐观锁版本加1；终态同时释放唯一获准位（slot_flag 置 NULL）。
     */
    public void update(RoutinePlan plan) {
        boolean terminal = plan.status().isTerminal();
        jdbc.update("UPDATE routine_plan SET status = ?, slot_flag = CASE WHEN ? THEN NULL ELSE 1 END,"
                        + " calendar_version = ?, evidence = COALESCE(?, evidence),"
                        + " emergency_event_id = ?, started_at = ?, completed_at = ?, preempted_at = ?,"
                        + " version = version + 1, updated_at = ? WHERE id = ?",
                plan.status().name(), terminal, plan.calendarVersion(), plan.evidence(),
                plan.emergencyEventId() == null ? null : plan.emergencyEventId(),
                plan.startedAt() == null ? null : Timestamp.from(plan.startedAt()),
                plan.completedAt() == null ? null : Timestamp.from(plan.completedAt()),
                plan.preemptedAt() == null ? null : Timestamp.from(plan.preemptedAt()),
                Timestamp.from(plan.updatedAt()), plan.id());
    }

    /** 仅把未开始（获准）计划标记为待重审：保留原冻结版本号直到重审重新冻结。返回受影响行数。 */
    public int markApprovedStaleForReview(Instant updatedAt) {
        return jdbc.update("UPDATE routine_plan SET status = 'NEEDS_REVIEW', version = version + 1,"
                + " updated_at = ? WHERE status = 'APPROVED'", Timestamp.from(updatedAt));
    }

    /** 列出当前处于获准未开始状态的计划ID（日历发布前快照，用于界定本次受影响计划）。 */
    public List<Long> findApprovedIds() {
        return jdbc.queryForList("SELECT id FROM routine_plan WHERE status = 'APPROVED' ORDER BY id",
                Long.class);
    }

    /** 同跑道在指定时刻处于未终态（slot_flag=1）的计划，供紧急关闭抢占与开始校验。 */
    public List<RoutinePlan> findActiveByRunway(String runwayId) {
        return jdbc.query("SELECT * FROM routine_plan WHERE runway_id = ? AND slot_flag = 1 ORDER BY id FOR UPDATE",
                MAPPER, runwayId);
    }

    /** 取冻结在指定日历版本上的计划（日历影响查询）。 */
    public List<RoutinePlan> findByCalendarVersion(int calendarVersion) {
        return jdbc.query("SELECT * FROM routine_plan WHERE calendar_version = ? ORDER BY id",
                MAPPER, calendarVersion);
    }

    /** 取全部待重审计划（按主键排序）。 */
    public List<RoutinePlan> findNeedsReview() {
        return jdbc.query("SELECT * FROM routine_plan WHERE status = 'NEEDS_REVIEW' ORDER BY id", MAPPER);
    }
}
