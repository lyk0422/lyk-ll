package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.PilotPlanEntity;
import com.example.starter.domain.PlanStatus;

/**
 * 引航计划数据访问。
 */
@Repository
public class PlanRepository {

    private static final RowMapper<PilotPlanEntity> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static PilotPlanEntity map(ResultSet rs) throws SQLException {
        return new PilotPlanEntity(
                rs.getLong("id"),
                rs.getString("plan_no"),
                rs.getString("ship_name"),
                rs.getString("voyage_no"),
                PlanStatus.valueOf(rs.getString("status")),
                rs.getObject("pilot_id", Long.class),
                toLdt(rs.getTimestamp("tide_start")),
                toLdt(rs.getTimestamp("tide_end")),
                toLdt(rs.getTimestamp("applied_at")),
                toLdt(rs.getTimestamp("confirmed_at")),
                toLdt(rs.getTimestamp("started_at")),
                toLdt(rs.getTimestamp("settled_at")),
                toLdt(rs.getTimestamp("cancelled_at")),
                rs.getString("cancel_reason"),
                rs.getInt("version"));
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public long insert(String planNo, String shipName, String voyageNo, LocalDateTime appliedAt) {
        jdbc.update(
                "INSERT INTO pilot_plan (plan_no, ship_name, voyage_no, status, applied_at) VALUES (?,?,?,?,?)",
                planNo, shipName, voyageNo, PlanStatus.APPLIED.name(), Timestamp.valueOf(appliedAt));
        Long id = jdbc.queryForObject("SELECT id FROM pilot_plan WHERE plan_no = ?", Long.class, planNo);
        return id;
    }

    public Optional<PilotPlanEntity> findById(long id) {
        List<PilotPlanEntity> rows = jdbc.query("SELECT * FROM pilot_plan WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<PilotPlanEntity> findAll() {
        return jdbc.query("SELECT * FROM pilot_plan ORDER BY id", MAPPER);
    }

    /** 编排：绑定引航员、潮水窗口，状态 APPLIED -> ARRANGED，返回影响行数。 */
    public int arrange(long id, long pilotId, LocalDateTime tideStart, LocalDateTime tideEnd) {
        return jdbc.update(
                "UPDATE pilot_plan SET pilot_id = ?, tide_start = ?, tide_end = ?, status = ?, version = version + 1 "
                        + "WHERE id = ? AND status = ?",
                pilotId, Timestamp.valueOf(tideStart), Timestamp.valueOf(tideEnd),
                PlanStatus.ARRANGED.name(), id, PlanStatus.APPLIED.name());
    }

    /** 状态流转（ARRANGED -> CONFIRMED），同事务记录确认时间，返回影响行数。 */
    public int confirm(long id, LocalDateTime confirmedAt) {
        return jdbc.update(
                "UPDATE pilot_plan SET status = ?, confirmed_at = ?, version = version + 1 WHERE id = ? AND status = ?",
                PlanStatus.CONFIRMED.name(), Timestamp.valueOf(confirmedAt), id, PlanStatus.ARRANGED.name());
    }

    /** 状态流转（CONFIRMED -> EXECUTING），返回影响行数。 */
    public int start(long id, LocalDateTime startedAt) {
        return jdbc.update(
                "UPDATE pilot_plan SET status = ?, started_at = ?, version = version + 1 WHERE id = ? AND status = ?",
                PlanStatus.EXECUTING.name(), Timestamp.valueOf(startedAt), id, PlanStatus.CONFIRMED.name());
    }

    /** 状态流转（EXECUTING -> SETTLED），返回影响行数。 */
    public int settle(long id, LocalDateTime settledAt) {
        return jdbc.update(
                "UPDATE pilot_plan SET status = ?, settled_at = ?, version = version + 1 WHERE id = ? AND status = ?",
                PlanStatus.SETTLED.name(), Timestamp.valueOf(settledAt), id, PlanStatus.EXECUTING.name());
    }

    /** 取消（APPLIED/ARRANGED/CONFIRMED -> CANCELLED），返回影响行数。 */
    public int cancel(long id, LocalDateTime cancelledAt, String reason) {
        return jdbc.update(
                "UPDATE pilot_plan SET status = ?, cancelled_at = ?, cancel_reason = ?, version = version + 1 "
                        + "WHERE id = ? AND status IN (?, ?, ?)",
                PlanStatus.CANCELLED.name(), Timestamp.valueOf(cancelledAt), reason, id,
                PlanStatus.APPLIED.name(), PlanStatus.ARRANGED.name(), PlanStatus.CONFIRMED.name());
    }

    /** 改期：仅允许 CONFIRMED 状态更新潮水窗口，返回影响行数。 */
    public int reschedule(long id, LocalDateTime tideStart, LocalDateTime tideEnd) {
        return jdbc.update(
                "UPDATE pilot_plan SET tide_start = ?, tide_end = ?, version = version + 1 WHERE id = ? AND status = ?",
                Timestamp.valueOf(tideStart), Timestamp.valueOf(tideEnd), id, PlanStatus.CONFIRMED.name());
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM pilot_plan");
    }
}
