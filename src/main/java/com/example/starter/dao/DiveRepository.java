package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 潜次主表访问。
 *
 * <p>{@code active_team_slot} 上有唯一索引：潜次未结案时等于潜水组键，结案后置空，
 * 因此同一潜水组同时刻只允许一个进行中潜次，并发申请由数据库唯一受理。</p>
 */
@Repository
public class DiveRepository {

    private static final RowMapper<DiveRow> MAPPER = (rs, n) -> new DiveRow(
            rs.getString("request_id"),
            rs.getString("apply_fingerprint"),
            rs.getString("team_key"),
            rs.getString("active_team_slot"),
            rs.getString("diver_a_code"),
            rs.getString("diver_b_code"),
            rs.getInt("planned_depth_m"),
            rs.getInt("planned_bottom_min"),
            rs.getString("status"),
            rs.getInt("current_plan_version"),
            rs.getTimestamp("surfaced_at_utc") == null ? null
                    : rs.getTimestamp("surfaced_at_utc").toInstant(),
            rs.getBoolean("symptomatic"),
            rs.getTimestamp("closed_at_utc") == null ? null
                    : rs.getTimestamp("closed_at_utc").toInstant(),
            rs.getBoolean("points_controlled"),
            rs.getTimestamp("controlled_started_at_utc") == null ? null
                    : rs.getTimestamp("controlled_started_at_utc").toInstant(),
            rs.getTimestamp("created_at_utc").toInstant(),
            rs.getTimestamp("updated_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public DiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insert(DiveRow row) {
        return jdbc.update("INSERT INTO dive "
                        + "(request_id, apply_fingerprint, team_key, active_team_slot, "
                        + "diver_a_code, diver_b_code, planned_depth_m, planned_bottom_min, "
                        + "status, current_plan_version, surfaced_at_utc, symptomatic, "
                        + "closed_at_utc, points_controlled, controlled_started_at_utc, "
                        + "created_at_utc, updated_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.requestId(), row.applyFingerprint(), row.teamKey(), row.activeTeamSlot(),
                row.diverACode(), row.diverBCode(), row.plannedDepthM(), row.plannedBottomMin(),
                row.status(), row.currentPlanVersion(), ts(row.surfacedAtUtc()),
                row.symptomatic(), ts(row.closedAtUtc()), row.pointsControlled(),
                ts(row.controlledStartedAtUtc()), ts(row.createdAtUtc()),
                ts(row.updatedAtUtc()));
    }

    public Optional<DiveRow> find(String requestId) {
        return jdbc.query("SELECT * FROM dive WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public DiveRow get(String requestId) {
        return find(requestId).orElseThrow(() ->
                new IllegalArgumentException("潜次不存在: " + requestId));
    }

    /**
     * 查某潜水员最近一次已确认出水的受控潜次（按出水时刻倒序）。
     * 积分消减、间隔门禁与方案冻结来源均以此为准；无则返回空。
     */
    public Optional<DiveRow> findLatestControlledSurfaced(String diverCode) {
        return jdbc.query("SELECT * FROM dive WHERE points_controlled = TRUE "
                        + "AND surfaced_at_utc IS NOT NULL "
                        + "AND (diver_a_code = ? OR diver_b_code = ?) "
                        + "ORDER BY surfaced_at_utc DESC, updated_at_utc DESC LIMIT 1",
                MAPPER, diverCode, diverCode).stream().findFirst();
    }

    public void updateStatus(String requestId, String status, Instant now) {
        jdbc.update("UPDATE dive SET status = ?, updated_at_utc = ? WHERE request_id = ?",
                status, ts(now), requestId);
    }

    /** 受控潜次实际下潜重查通过：记录实际下潜时刻并进入 DIVING。 */
    public void markControlledStarted(String requestId, Instant startedAt, Instant now) {
        jdbc.update("UPDATE dive SET controlled_started_at_utc = ?, status = 'DIVING', "
                        + "updated_at_utc = ? WHERE request_id = ?",
                ts(startedAt), ts(now), requestId);
    }

    /** 出水登记。 */
    public void markSurfaced(String requestId, Instant surfacedAt, String status, Instant now) {
        jdbc.update("UPDATE dive SET surfaced_at_utc = ?, status = ?, updated_at_utc = ? "
                        + "WHERE request_id = ?",
                ts(surfacedAt), status, ts(now), requestId);
    }

    /** 观察期出现症状：冻结结案。 */
    public void markSymptomatic(String requestId, Instant now) {
        jdbc.update("UPDATE dive SET symptomatic = TRUE, status = 'FROZEN', updated_at_utc = ? "
                        + "WHERE request_id = ?",
                ts(now), requestId);
    }

    /** 结案：终态，同时释放潜水组进行中占用槽。 */
    public void markClosed(String requestId, Instant closedAt, Instant now) {
        jdbc.update("UPDATE dive SET status = 'CLOSED', closed_at_utc = ?, active_team_slot = NULL, "
                        + "updated_at_utc = ? WHERE request_id = ?",
                ts(closedAt), ts(now), requestId);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
