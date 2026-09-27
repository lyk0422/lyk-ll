package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 待补液事件仓储：补液入账后按生成顺序关闭可满足的事件。
 */
@Repository
public class TankRefillEventRepository {

    private static final String COLUMNS = "id, tank_id, source, plan_id, required_qty_kg, "
            + "deficit_qty_kg, status, created_at, closed_at, closed_by_refill_id";

    private final JdbcTemplate jdbc;

    public TankRefillEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long tankId, String source, Long planId,
                       BigDecimal requiredQtyKg, BigDecimal deficitQtyKg) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO tank_refill_event
                                (tank_id, source, plan_id, required_qty_kg, deficit_qty_kg,
                                 status, created_at)
                            VALUES (?, ?, ?, ?, ?, 'OPEN', CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, tankId);
            ps.setString(2, source);
            if (planId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, planId);
            }
            ps.setBigDecimal(4, requiredQtyKg);
            ps.setBigDecimal(5, deficitQtyKg);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    /**
     * 事务内行级锁定读取全部待补液事件（按生成顺序），补液入账时关闭可满足者。
     */
    public List<TankRefillEvent> findOpenForUpdate(Long tankId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM tank_refill_event WHERE tank_id = ? AND status = 'OPEN'"
                        + " ORDER BY id FOR UPDATE",
                (rs, rowNum) -> map(rs), tankId);
    }

    public void close(Long id, Long refillId) {
        jdbc.update("""
                        UPDATE tank_refill_event
                        SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP, closed_by_refill_id = ?
                        WHERE id = ?
                        """,
                refillId, id);
    }

    public List<TankRefillEvent> listByTank(Long tankId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank_refill_event WHERE tank_id = ? ORDER BY id",
                (rs, rowNum) -> map(rs), tankId);
    }

    private TankRefillEvent map(ResultSet rs) throws SQLException {
        java.sql.Timestamp closedAt = rs.getTimestamp("closed_at");
        return new TankRefillEvent(
                rs.getLong("id"),
                rs.getLong("tank_id"),
                rs.getString("source"),
                (Long) rs.getObject("plan_id"),
                rs.getBigDecimal("required_qty_kg"),
                rs.getBigDecimal("deficit_qty_kg"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                closedAt == null ? null : closedAt.toLocalDateTime(),
                (Long) rs.getObject("closed_by_refill_id"));
    }
}
