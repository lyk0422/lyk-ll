package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.CommandStatus;
import com.example.starter.domain.ReductionCommand;

/**
 * 降掺指令访问，(plan_id, hour_start) 唯一，触发→下发→确认/升级。
 */
@Repository
public class CommandRepository {

    private static final RowMapper<ReductionCommand> MAPPER = (ResultSet rs, int n) -> new ReductionCommand(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getObject("hour_start", LocalDateTime.class),
            rs.getString("pollutant"),
            rs.getBigDecimal("hour_avg"),
            CommandStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("target_rate"),
            rs.getObject("triggered_at", LocalDateTime.class),
            rs.getObject("issued_at", LocalDateTime.class),
            rs.getObject("confirmed_at", LocalDateTime.class),
            rs.getObject("escalated_at", LocalDateTime.class),
            rs.getString("confirm_request_id"));

    private final JdbcTemplate jdbc;

    public CommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 小时超标触发指令；同小时重复评估不重复建单。 */
    public int insertIfAbsent(ReductionCommand cmd) {
        return jdbc.update("""
                INSERT INTO reduction_command
                    (plan_id, hour_start, pollutant, hour_avg, status, target_rate, triggered_at)
                SELECT ?, ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM reduction_command WHERE plan_id = ? AND hour_start = ?)
                """,
                cmd.planId(), Timestamp.valueOf(cmd.hourStart()), cmd.pollutant(), cmd.hourAvg(),
                cmd.status().name(), cmd.targetRate(), Timestamp.valueOf(cmd.triggeredAt()),
                cmd.planId(), Timestamp.valueOf(cmd.hourStart()));
    }

    public ReductionCommand findByPlanAndHour(long planId, LocalDateTime hourStart) {
        List<ReductionCommand> list = jdbc.query("""
                SELECT id, plan_id, hour_start, pollutant, hour_avg, status, target_rate,
                       triggered_at, issued_at, confirmed_at, escalated_at, confirm_request_id
                FROM reduction_command WHERE plan_id = ? AND hour_start = ?
                """, MAPPER, planId, Timestamp.valueOf(hourStart));
        return list.isEmpty() ? null : list.get(0);
    }

    public List<ReductionCommand> findByPlanAndDate(long planId, java.time.LocalDate date) {
        return jdbc.query("""
                SELECT id, plan_id, hour_start, pollutant, hour_avg, status, target_rate,
                       triggered_at, issued_at, confirmed_at, escalated_at, confirm_request_id
                FROM reduction_command
                WHERE plan_id = ? AND hour_start >= ? AND hour_start < ?
                ORDER BY hour_start
                """, MAPPER, planId,
                Timestamp.valueOf(date.atStartOfDay()),
                Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
    }

    /** 查方案全部降掺指令（跨日场景，如时钟推进后的下发/确认）。 */
    public List<ReductionCommand> findAllByPlan(long planId) {
        return jdbc.query("""
                SELECT id, plan_id, hour_start, pollutant, hour_avg, status, target_rate,
                       triggered_at, issued_at, confirmed_at, escalated_at, confirm_request_id
                FROM reduction_command WHERE plan_id = ? ORDER BY hour_start
                """, MAPPER, planId);
    }

    /** 已下发且尚未确认/升级的指令，供超时升级扫描。 */
    public List<ReductionCommand> findIssuedOverdue(LocalDateTime deadline) {
        return jdbc.query("""
                SELECT id, plan_id, hour_start, pollutant, hour_avg, status, target_rate,
                       triggered_at, issued_at, confirmed_at, escalated_at, confirm_request_id
                FROM reduction_command
                WHERE status = 'ISSUED' AND issued_at <= ?
                ORDER BY issued_at
                """, MAPPER, Timestamp.valueOf(deadline));
    }

    public int markIssued(long id, LocalDateTime issuedAt) {
        return jdbc.update(
                "UPDATE reduction_command SET status = 'ISSUED', issued_at = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'TRIGGERED'",
                Timestamp.valueOf(issuedAt), id);
    }

    public int markConfirmed(long id, String requestId, LocalDateTime confirmedAt) {
        return jdbc.update("""
                UPDATE reduction_command
                SET status = 'CONFIRMED', confirmed_at = ?, confirm_request_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('ISSUED', 'ESCALATED')
                """, Timestamp.valueOf(confirmedAt), requestId, id);
    }

    public int markEscalated(long id, LocalDateTime escalatedAt) {
        return jdbc.update(
                "UPDATE reduction_command SET status = 'ESCALATED', escalated_at = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'ISSUED'",
                Timestamp.valueOf(escalatedAt), id);
    }
}
