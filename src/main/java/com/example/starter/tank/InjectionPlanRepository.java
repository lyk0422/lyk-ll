package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 受控加注计划仓储：状态流转只前进（RESERVED→EXECUTED/CANCELLED），
 * 行锁读取保证取消与回执并发时唯一计量。
 */
@Repository
public class InjectionPlanRepository {

    private final JdbcTemplate jdbc;

    public InjectionPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long stationId, Long tankId, Long taskId, String requestId,
                       BigDecimal plannedKg, String status, LocalDateTime plannedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO injection_plan
                                (station_id, tank_id, task_id, request_id, planned_kg, status,
                                 planned_at, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setLong(2, tankId);
            if (taskId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, taskId);
            }
            ps.setString(4, requestId);
            ps.setBigDecimal(5, plannedKg);
            ps.setString(6, status);
            ps.setTimestamp(7, Timestamp.valueOf(plannedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    /**
     * 事务内行级锁定读取计划；不存在返回 null。
     */
    public InjectionPlan selectForUpdate(Long planId) {
        return jdbc.query("""
                        SELECT id, station_id, tank_id, task_id, request_id, planned_kg,
                               actual_kg, status, planned_at
                        FROM injection_plan WHERE id = ? FOR UPDATE
                        """,
                rs -> rs.next() ? map(rs) : null,
                planId);
    }

    public void markExecuted(Long planId, BigDecimal actualKg, LocalDateTime executedAt) {
        jdbc.update("""
                        UPDATE injection_plan
                        SET status = 'EXECUTED', actual_kg = ?, executed_at = ?
                        WHERE id = ?
                        """,
                actualKg, Timestamp.valueOf(executedAt), planId);
    }

    public void markCancelled(Long planId, LocalDateTime cancelledAt) {
        jdbc.update("""
                        UPDATE injection_plan
                        SET status = 'CANCELLED', cancelled_at = ?
                        WHERE id = ?
                        """,
                Timestamp.valueOf(cancelledAt), planId);
    }

    public List<InjectionPlan> listByStation(Long stationId) {
        return jdbc.query("""
                        SELECT id, station_id, tank_id, task_id, request_id, planned_kg,
                               actual_kg, status, planned_at
                        FROM injection_plan WHERE station_id = ? ORDER BY id
                        """,
                (rs, rowNum) -> map(rs),
                stationId);
    }

    private InjectionPlan map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InjectionPlan(
                rs.getLong("id"),
                rs.getLong("station_id"),
                rs.getLong("tank_id"),
                (Long) rs.getObject("task_id"),
                rs.getString("request_id"),
                rs.getBigDecimal("planned_kg"),
                rs.getBigDecimal("actual_kg"),
                rs.getString("status"),
                rs.getTimestamp("planned_at").toLocalDateTime());
    }
}
