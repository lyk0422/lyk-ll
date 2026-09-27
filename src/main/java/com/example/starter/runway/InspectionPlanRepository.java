package com.example.starter.runway;

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
import java.util.Objects;
import java.util.Optional;

/**
 * 例行巡检计划数据访问：inspection_plan 表。
 * 状态变更均以“当前版本=期望版本”的乐观条件执行，成功后版本加1；live_flag 维护同跑道同窗唯一名额。
 */
@Repository
public class InspectionPlanRepository {

    private static final RowMapper<InspectionPlanRecord> MAPPER = (rs, rowNum) -> new InspectionPlanRecord(
            rs.getLong("id"),
            rs.getString("runway_id"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            rs.getTimestamp("planned_start_at").toInstant(),
            rs.getInt("duration_minutes"),
            PlanStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            (Integer) rs.getObject("frozen_calendar_version"),
            rs.getString("conflict_evidence"),
            (Integer) rs.getObject("live_flag"),
            (Long) rs.getObject("aborted_by_event_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public InspectionPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新计划：发布成功时 status=FROZEN、version=1、live_flag=1。
     */
    public InspectionPlanRecord insert(InspectionPlanRecord plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO inspection_plan (runway_id, window_start, window_end, planned_start_at,"
                            + " duration_minutes, status, version, frozen_calendar_version, conflict_evidence,"
                            + " live_flag, aborted_by_event_id, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, plan.runwayId());
            ps.setTimestamp(2, Timestamp.from(plan.windowStart()));
            ps.setTimestamp(3, Timestamp.from(plan.windowEnd()));
            ps.setTimestamp(4, Timestamp.from(plan.plannedStartAt()));
            ps.setInt(5, plan.durationMinutes());
            ps.setString(6, plan.status().name());
            ps.setInt(7, plan.version());
            if (plan.frozenCalendarVersion() == null) {
                ps.setNull(8, java.sql.Types.INTEGER);
            } else {
                ps.setInt(8, plan.frozenCalendarVersion());
            }
            ps.setString(9, plan.conflictEvidence());
            if (plan.liveFlag() == null) {
                ps.setNull(10, java.sql.Types.TINYINT);
            } else {
                ps.setInt(10, plan.liveFlag());
            }
            if (plan.abortedByEventId() == null) {
                ps.setNull(11, java.sql.Types.BIGINT);
            } else {
                ps.setLong(11, plan.abortedByEventId());
            }
            ps.setTimestamp(12, Timestamp.from(plan.createdAt()));
            ps.setTimestamp(13, Timestamp.from(plan.updatedAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return findById(id).orElseThrow();
    }

    public Optional<InspectionPlanRecord> findById(long id) {
        List<InspectionPlanRecord> rows = jdbc.query("SELECT * FROM inspection_plan WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<InspectionPlanRecord> findByStatus(PlanStatus status) {
        return jdbc.query("SELECT * FROM inspection_plan WHERE status = ? ORDER BY id", MAPPER, status.name());
    }

    /**
     * 查询某跑道上处于给定状态集合的计划（紧急接报抢占使用）。
     */
    public List<InspectionPlanRecord> findByRunwayAndStatuses(String runwayId, List<PlanStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        Object[] args = new Object[statuses.size() + 1];
        args[0] = runwayId;
        for (int i = 0; i < statuses.size(); i++) {
            args[i + 1] = statuses.get(i).name();
        }
        return jdbc.query("SELECT * FROM inspection_plan WHERE runway_id = ? AND status IN (" + placeholders
                + ") ORDER BY id", MAPPER, args);
    }

    /**
     * 乐观变更：仅当 id 存在且当前版本等于 expectedVersion 时更新状态/冻结版本/证据/占位/中止事件，
     * 同时版本加1。返回受影响行数：0 表示版本不匹配。
     */
    public int compareAndUpdate(InspectionPlanRecord plan, PlanStatus newStatus, Integer frozenCalendarVersion,
                                String conflictEvidence, Integer liveFlag, Long abortedByEventId,
                                Instant updatedAt) {
        org.springframework.jdbc.core.SqlParameterValue frozenParam = frozenCalendarVersion == null
                ? new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.INTEGER, null)
                : new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.INTEGER, frozenCalendarVersion);
        org.springframework.jdbc.core.SqlParameterValue liveFlagParam = liveFlag == null
                ? new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.TINYINT, null)
                : new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.TINYINT, liveFlag);
        org.springframework.jdbc.core.SqlParameterValue abortedParam = abortedByEventId == null
                ? new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.BIGINT, null)
                : new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.BIGINT, abortedByEventId);
        return jdbc.update(
                "UPDATE inspection_plan SET status = ?, frozen_calendar_version = ?, conflict_evidence = ?,"
                        + " live_flag = ?, aborted_by_event_id = ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ?",
                newStatus.name(), frozenParam, conflictEvidence, liveFlagParam, abortedParam,
                Timestamp.from(updatedAt), plan.id(), plan.version());
    }

    /**
     * 紧急抢占专用：无条件按当前主键更新（调用方已持有跑道排它锁并在事务内），版本加1。
     * 用于 FROZEN→CANCELLED 与 RUNNING→ABORTED。
     */
    public int preemptUpdate(long planId, PlanStatus newStatus, Integer liveFlag, Long abortedByEventId,
                             Instant updatedAt) {
        org.springframework.jdbc.core.SqlParameterValue liveFlagParam = liveFlag == null
                ? new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.TINYINT, null)
                : new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.TINYINT, liveFlag);
        org.springframework.jdbc.core.SqlParameterValue abortedParam = abortedByEventId == null
                ? new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.BIGINT, null)
                : new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.BIGINT, abortedByEventId);
        return jdbc.update(
                "UPDATE inspection_plan SET status = ?, live_flag = ?, aborted_by_event_id = ?,"
                        + " version = version + 1, updated_at = ? WHERE id = ?",
                newStatus.name(), liveFlagParam, abortedParam, Timestamp.from(updatedAt), planId);
    }
}
