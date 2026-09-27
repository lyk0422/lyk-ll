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
 * 例行计划数据访问：routine_plan 表；存活行 active_flag=1，终态置 NULL。
 */
@Repository
public class RoutinePlanRepository {

    private static final RowMapper<RoutinePlan> MAPPER = (rs, rowNum) -> new RoutinePlan(
            rs.getLong("id"),
            rs.getString("runway_id"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            rs.getInt("duration_minutes"),
            rs.getInt("frozen_calendar_version"),
            RoutinePlanStatus.valueOf(rs.getString("status")),
            (Integer) rs.getObject("active_flag"),
            rs.getString("conflict_evidence"),
            rs.getInt("version"),
            rs.getString("request_id"),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getTimestamp("preempted_at") == null ? null : rs.getTimestamp("preempted_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public RoutinePlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入计划，存活行占用 (runway_id, window_start, window_end, active_flag) 唯一索引。
     */
    public RoutinePlan insert(RoutinePlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO routine_plan (runway_id, window_start, window_end, duration_minutes,"
                            + " frozen_calendar_version, status, active_flag, conflict_evidence, version,"
                            + " request_id, started_at, completed_at, preempted_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, plan.runwayId());
            ps.setTimestamp(2, Timestamp.from(plan.windowStart()));
            ps.setTimestamp(3, Timestamp.from(plan.windowEnd()));
            ps.setInt(4, plan.durationMinutes());
            ps.setInt(5, plan.frozenCalendarVersion());
            ps.setString(6, plan.status().name());
            ps.setObject(7, plan.activeFlag(), Types.TINYINT);
            ps.setString(8, plan.conflictEvidence());
            ps.setInt(9, plan.version());
            ps.setString(10, plan.requestId());
            ps.setTimestamp(11, plan.startedAt() == null ? null : Timestamp.from(plan.startedAt()));
            ps.setTimestamp(12, plan.completedAt() == null ? null : Timestamp.from(plan.completedAt()));
            ps.setTimestamp(13, plan.preemptedAt() == null ? null : Timestamp.from(plan.preemptedAt()));
            ps.setTimestamp(14, Timestamp.from(plan.createdAt()));
            ps.setTimestamp(15, Timestamp.from(plan.updatedAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return findById(id).orElseThrow();
    }

    public Optional<RoutinePlan> findById(long id) {
        List<RoutinePlan> rows = jdbc.query("SELECT * FROM routine_plan WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 同跑道同窗的存活计划（active_flag=1）。
     */
    public Optional<RoutinePlan> findLive(String runwayId, Instant windowStart, Instant windowEnd) {
        List<RoutinePlan> rows = jdbc.query("SELECT * FROM routine_plan WHERE runway_id = ?"
                + " AND window_start = ? AND window_end = ? AND active_flag = 1", MAPPER,
                runwayId, Timestamp.from(windowStart), Timestamp.from(windowEnd));
        return rows.stream().findFirst();
    }

    /**
     * 某跑道全部存活计划，按窗口开始时刻排序。
     */
    public List<RoutinePlan> findLiveByRunway(String runwayId) {
        return jdbc.query("SELECT * FROM routine_plan WHERE runway_id = ? AND active_flag = 1"
                + " ORDER BY window_start, id", MAPPER, runwayId);
    }

    public List<RoutinePlan> findByRunway(String runwayId) {
        return jdbc.query("SELECT * FROM routine_plan WHERE runway_id = ? ORDER BY window_start, id",
                MAPPER, runwayId);
    }

    /**
     * 乐观更新：仅当 id 与 expectedVersion 同时匹配时写入，版本号加 1；返回受影响行数（0 表示版本冲突）。
     */
    public int casUpdate(long id, int expectedVersion, RoutinePlanStatus status, boolean clearActiveFlag,
                         Integer frozenCalendarVersion, Instant startedAt, Instant completedAt,
                         Instant preemptedAt, String conflictEvidence, Instant updatedAt) {
        return jdbc.update("UPDATE routine_plan SET status = ?,"
                        + " active_flag = CASE WHEN ? THEN NULL ELSE active_flag END,"
                        + " frozen_calendar_version = COALESCE(?, frozen_calendar_version),"
                        + " started_at = COALESCE(?, started_at),"
                        + " completed_at = COALESCE(?, completed_at),"
                        + " preempted_at = COALESCE(?, preempted_at),"
                        + " conflict_evidence = COALESCE(?, conflict_evidence),"
                        + " version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ?",
                status.name(), clearActiveFlag, frozenCalendarVersion,
                startedAt == null ? null : Timestamp.from(startedAt),
                completedAt == null ? null : Timestamp.from(completedAt),
                preemptedAt == null ? null : Timestamp.from(preemptedAt),
                conflictEvidence, Timestamp.from(updatedAt), id, expectedVersion);
    }

    /**
     * 紧急关闭抢占：将指定跑道存活且未开始（APPROVED/NEEDS_REVIEW）计划批量置为 CANCELLED。
     */
    public int cancelLiveNotStarted(String runwayId, Instant at) {
        return jdbc.update("UPDATE routine_plan SET status = 'CANCELLED', active_flag = NULL,"
                + " preempted_at = ?, version = version + 1, updated_at = ?"
                + " WHERE runway_id = ? AND active_flag = 1 AND status IN ('APPROVED','NEEDS_REVIEW')",
                Timestamp.from(at), Timestamp.from(at), runwayId);
    }

    /**
     * 紧急关闭抢占：将指定跑道进行中（IN_PROGRESS）计划批量置为 ABORTED，保留已完成检查项。
     */
    public int abortInProgress(String runwayId, Instant at) {
        return jdbc.update("UPDATE routine_plan SET status = 'ABORTED', active_flag = NULL,"
                + " preempted_at = ?, version = version + 1, updated_at = ?"
                + " WHERE runway_id = ? AND active_flag = 1 AND status = 'IN_PROGRESS'",
                Timestamp.from(at), Timestamp.from(at), runwayId);
    }

    /**
     * 日历升版前查询将被置为待重审的计划ID：存活、APPROVED、冻结版本旧于新版本。
     */
    public List<Long> findStaleApprovedIds(int newVersion) {
        return jdbc.queryForList("SELECT id FROM routine_plan WHERE active_flag = 1 AND status = 'APPROVED'"
                + " AND frozen_calendar_version < ? ORDER BY id", Long.class, newVersion);
    }

    /**
     * 日历升版：将所有跑道尚未开始（APPROVED）且冻结版本旧于新版本的计划置为 NEEDS_REVIEW。
     */
    public int markStalePlansForReview(int newVersion, Instant at) {
        return jdbc.update("UPDATE routine_plan SET status = 'NEEDS_REVIEW', version = version + 1,"
                + " updated_at = ? WHERE active_flag = 1 AND status = 'APPROVED'"
                + " AND frozen_calendar_version < ?", Timestamp.from(at), newVersion);
    }
}
