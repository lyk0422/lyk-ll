package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 受控加注计划仓储：request_id 唯一约束兜底并发重复下达。
 */
@Repository
public class InjectionPlanRepository {

    private final JdbcTemplate jdbc;

    public InjectionPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long stationId, Long tankId, String requestId, String source, Long taskId,
                       BigDecimal requiredKg, String status, LocalDateTime plannedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO injection_plan
                                (station_id, tank_id, request_id, source, task_id, required_kg,
                                 status, planned_at, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setLong(2, tankId);
            ps.setString(3, requestId);
            ps.setString(4, source);
            if (taskId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, taskId);
            }
            ps.setBigDecimal(6, requiredKg);
            ps.setString(7, status);
            ps.setTimestamp(8, Timestamp.valueOf(plannedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    /**
     * 按单号行锁读取计划（回执/取消并发裁决）。
     */
    public Optional<PlanRow> findForUpdate(String requestId) {
        return jdbc.query("""
                        SELECT id, station_id, tank_id, request_id, required_kg, executed_kg, status
                        FROM injection_plan WHERE request_id = ? FOR UPDATE
                        """,
                rs -> rs.next() ? Optional.of(new PlanRow(
                        rs.getLong("id"),
                        rs.getLong("station_id"),
                        rs.getLong("tank_id"),
                        rs.getString("request_id"),
                        rs.getBigDecimal("required_kg"),
                        rs.getBigDecimal("executed_kg"),
                        rs.getString("status"))) : Optional.empty(),
                requestId);
    }

    public void markExecuted(Long planId, BigDecimal executedKg) {
        jdbc.update("""
                        UPDATE injection_plan
                        SET status = 'EXECUTED', executed_kg = ?, completed_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                executedKg, planId);
    }

    public void markCancelled(Long planId) {
        jdbc.update("""
                        UPDATE injection_plan
                        SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                planId);
    }

    /**
     * 计划行快照。
     */
    public record PlanRow(Long id, Long stationId, Long tankId, String requestId,
                          BigDecimal requiredKg, BigDecimal executedKg, String status) {
    }
}
