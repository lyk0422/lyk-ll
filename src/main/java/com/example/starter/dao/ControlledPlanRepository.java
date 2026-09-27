package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 受控方案冻结快照访问：方案头 + 每名组员的版本/出水时刻/积分冻结项。
 *
 * <p>方案变更时旧受控快照同事务随旧 {@code dive_plan} 版本一起冻结清退，
 * 实际下潜只能消费当前版本。</p>
 */
@Repository
public class ControlledPlanRepository {

    private static final RowMapper<ControlledPlanRow> PLAN_MAPPER = (rs, n) -> new ControlledPlanRow(
            rs.getLong("id"),
            rs.getString("dive_request_id"),
            rs.getInt("plan_version"),
            rs.getString("plan_request_id"),
            rs.getTimestamp("at_utc").toInstant(),
            rs.getInt("team_allowed_min"),
            rs.getTimestamp("created_at_utc").toInstant());

    private static final RowMapper<ControlledPlanDiverRow> DIVER_MAPPER = (rs, n) ->
            new ControlledPlanDiverRow(
                    rs.getLong("id"),
                    rs.getString("dive_request_id"),
                    rs.getInt("plan_version"),
                    rs.getString("diver_code"),
                    rs.getInt("expected_account_version"),
                    rs.getTimestamp("last_surfaced_at_utc") == null ? null
                            : rs.getTimestamp("last_surfaced_at_utc").toInstant(),
                    rs.getInt("residual_score"),
                    rs.getInt("current_score"),
                    rs.getLong("surface_minutes"),
                    rs.getInt("allowed_min"),
                    rs.getInt("ndl_minutes"),
                    rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public ControlledPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPlan(ControlledPlanRow row) {
        jdbc.update("INSERT INTO controlled_plan "
                        + "(dive_request_id, plan_version, plan_request_id, at_utc, "
                        + "team_allowed_min, created_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                row.diveRequestId(), row.planVersion(), row.planRequestId(),
                Timestamp.from(row.atUtc()), row.teamAllowedMin(),
                Timestamp.from(row.createdAtUtc()));
    }

    public void insertDiver(ControlledPlanDiverRow row) {
        jdbc.update("INSERT INTO controlled_plan_diver "
                        + "(dive_request_id, plan_version, diver_code, expected_account_version, "
                        + "last_surfaced_at_utc, residual_score, current_score, surface_minutes, "
                        + "allowed_min, ndl_minutes, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.diveRequestId(), row.planVersion(), row.diverCode(),
                row.expectedAccountVersion(), ts(row.lastSurfacedAtUtc()), row.residualScore(),
                row.currentScore(), row.surfaceMinutes(), row.allowedMin(), row.ndlMinutes(),
                Timestamp.from(row.createdAtUtc()));
    }

    public Optional<ControlledPlanRow> findPlan(String diveRequestId, int planVersion) {
        return jdbc.query("SELECT * FROM controlled_plan WHERE dive_request_id = ? AND plan_version = ?",
                PLAN_MAPPER, diveRequestId, planVersion).stream().findFirst();
    }

    public List<ControlledPlanDiverRow> findDivers(String diveRequestId, int planVersion) {
        return jdbc.query("SELECT * FROM controlled_plan_diver WHERE dive_request_id = ? "
                        + "AND plan_version = ? ORDER BY diver_code",
                DIVER_MAPPER, diveRequestId, planVersion);
    }

    /** 方案重做：删除该潜次旧版本受控快照（与旧 dive_plan 冻结同事务）。 */
    public int deleteByDive(String diveRequestId) {
        jdbc.update("DELETE FROM controlled_plan_diver WHERE dive_request_id = ?", diveRequestId);
        return jdbc.update("DELETE FROM controlled_plan WHERE dive_request_id = ?", diveRequestId);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
