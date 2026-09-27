package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 加注计划仓储：状态流转（RESERVED→EXECUTED/CANCELLED）在储罐行锁内完成。
 */
@Repository
public class TankPlanRepository {

    private static final String COLUMNS = "id, tank_id, request_id, planned_at, required_qty_kg, "
            + "status, actual_qty_kg, executed_at, cancelled_at";

    private final JdbcTemplate jdbc;

    public TankPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long tankId, String requestId, LocalDateTime plannedAt,
                       BigDecimal requiredQtyKg, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO tank_plan
                                (tank_id, request_id, planned_at, required_qty_kg, status, created_at)
                            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, tankId);
            ps.setString(2, requestId);
            ps.setTimestamp(3, Timestamp.valueOf(plannedAt));
            ps.setBigDecimal(4, requiredQtyKg);
            ps.setString(5, status);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public Optional<TankPlan> findById(Long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank_plan WHERE id = ?",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), id);
    }

    /**
     * 事务内行级锁定读取，回执/取消并发时唯一计量。
     */
    public Optional<TankPlan> findByIdForUpdate(Long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank_plan WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), id);
    }

    public void markExecuted(Long id, BigDecimal actualQtyKg, LocalDateTime executedAt) {
        jdbc.update("""
                        UPDATE tank_plan
                        SET status = 'EXECUTED', actual_qty_kg = ?, executed_at = ?
                        WHERE id = ?
                        """,
                actualQtyKg, Timestamp.valueOf(executedAt), id);
    }

    public void markCancelled(Long id, LocalDateTime cancelledAt) {
        jdbc.update("""
                        UPDATE tank_plan
                        SET status = 'CANCELLED', cancelled_at = ?
                        WHERE id = ?
                        """,
                Timestamp.valueOf(cancelledAt), id);
    }

    public List<TankPlan> listByTank(Long tankId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank_plan WHERE tank_id = ? ORDER BY id",
                (rs, rowNum) -> map(rs), tankId);
    }

    private TankPlan map(ResultSet rs) throws SQLException {
        Timestamp executedAt = rs.getTimestamp("executed_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        return new TankPlan(
                rs.getLong("id"),
                rs.getLong("tank_id"),
                rs.getString("request_id"),
                rs.getTimestamp("planned_at").toLocalDateTime(),
                rs.getBigDecimal("required_qty_kg"),
                rs.getString("status"),
                rs.getBigDecimal("actual_qty_kg"),
                executedAt == null ? null : executedAt.toLocalDateTime(),
                cancelledAt == null ? null : cancelledAt.toLocalDateTime());
    }
}
