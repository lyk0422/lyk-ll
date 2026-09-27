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
 */
@Repository
public class InspectionPlanRepository {

    private static final RowMapper<InspectionPlan> MAPPER = (rs, rowNum) -> new InspectionPlan(
            rs.getLong("id"),
            rs.getString("runway_id"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            rs.getInt("inspection_minutes"),
            rs.getString("flight_windows"),
            rs.getInt("calendar_version"),
            rs.getString("conflict_evidence"),
            PlanStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getString("request_id"),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
            rs.getString("completed_items"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public InspectionPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入计划；activeFlag 为 1 时占用“同跑道同窗唯一非终态计划”唯一索引。
     */
    public InspectionPlan insert(InspectionPlan plan, Integer activeFlag) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO inspection_plan (runway_id, window_start, window_end, inspection_minutes,"
                            + " flight_windows, calendar_version, conflict_evidence, status, version,"
                            + " active_flag, request_id, started_at, completed_items, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, plan.runwayId());
            ps.setTimestamp(2, Timestamp.from(plan.windowStart()));
            ps.setTimestamp(3, Timestamp.from(plan.windowEnd()));
            ps.setInt(4, plan.inspectionMinutes());
            ps.setString(5, plan.flightWindows());
            ps.setInt(6, plan.calendarVersion());
            ps.setString(7, plan.conflictEvidence());
            ps.setString(8, plan.status().name());
            ps.setInt(9, plan.version());
            if (activeFlag == null) {
                ps.setNull(10, java.sql.Types.TINYINT);
            } else {
                ps.setInt(10, activeFlag);
            }
            ps.setString(11, plan.requestId());
            if (plan.startedAt() == null) {
                ps.setNull(12, java.sql.Types.TIMESTAMP);
            } else {
                ps.setTimestamp(12, Timestamp.from(plan.startedAt()));
            }
            ps.setString(13, plan.completedItems());
            ps.setTimestamp(14, Timestamp.from(plan.createdAt()));
            ps.setTimestamp(15, Timestamp.from(plan.updatedAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return findById(id).orElseThrow();
    }

    public Optional<InspectionPlan> findById(long id) {
        List<InspectionPlan> rows = jdbc.query("SELECT * FROM inspection_plan WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 乐观锁更新：仅当当前版本等于 expectedVersion 时生效；terminal 为真时释放唯一占位。
     *
     * @return 是否更新成功（版本不匹配返回 false）
     */
    public boolean updateWithVersion(long id, int expectedVersion, PlanStatus status, int calendarVersion,
                                     Instant startedAt, String completedItems, boolean terminal,
                                     Instant updatedAt) {
        int updated = jdbc.update("UPDATE inspection_plan SET status = ?, calendar_version = ?,"
                        + " started_at = ?, completed_items = ?, version = version + 1,"
                        + " active_flag = CASE WHEN ? THEN NULL ELSE active_flag END, updated_at = ?"
                        + " WHERE id = ? AND version = ?",
                status.name(), calendarVersion,
                startedAt == null ? null : Timestamp.from(startedAt),
                completedItems, terminal, Timestamp.from(updatedAt), id, expectedVersion);
        return updated == 1;
    }

    /**
     * 查询跑道全部非终态计划（获准/待重审/进行中），供紧急抢占与占用校验。
     */
    public List<InspectionPlan> findActiveByRunwayId(String runwayId) {
        return jdbc.query("SELECT * FROM inspection_plan WHERE runway_id = ?"
                        + " AND status IN ('APPROVED','PENDING_REVIEW','IN_PROGRESS') ORDER BY id",
                MAPPER, runwayId);
    }

    /**
     * 查询受日历版本影响的活动计划：冻结版本与当前版本不一致的非终态计划。
     */
    public List<InspectionPlan> findImpactedByRunwayId(String runwayId, int currentVersion) {
        return jdbc.query("SELECT * FROM inspection_plan WHERE runway_id = ?"
                        + " AND status IN ('APPROVED','PENDING_REVIEW','IN_PROGRESS')"
                        + " AND calendar_version <> ? ORDER BY id",
                MAPPER, runwayId, currentVersion);
    }
}
