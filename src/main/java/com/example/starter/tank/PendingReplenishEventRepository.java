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
 * 待补液事件仓储：补液入账后在同一事务关闭“加入后可满足”的开放事件。
 * 来源请求号唯一，保证同一被拦截计划不会重复生成事件。
 */
@Repository
public class PendingReplenishEventRepository {

    private final JdbcTemplate jdbc;

    public PendingReplenishEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_COLUMNS = """
            SELECT id, station_id, tank_id, source_request_id, source, required_kg,
                   status, closed_order_id, created_at, closed_at
            FROM pending_replenish_event
            """;

    public Long insertOpen(Long stationId, Long tankId, String sourceRequestId, String source,
                           BigDecimal requiredKg) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO pending_replenish_event
                                (station_id, tank_id, source_request_id, source, required_kg,
                                 status, created_at)
                            VALUES (?, ?, ?, ?, ?, 'OPEN', CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setLong(2, tankId);
            ps.setString(3, sourceRequestId);
            ps.setString(4, source);
            ps.setBigDecimal(5, requiredKg);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    /**
     * 行级锁定读取储罐全部开放事件，供补液入账时在同一事务内判定并关闭。
     */
    public List<PendingReplenishEvent> findOpenByTankForUpdate(Long tankId) {
        return jdbc.query(SELECT_COLUMNS
                        + " WHERE tank_id = ? AND status = 'OPEN' ORDER BY id ASC FOR UPDATE",
                (rs, rowNum) -> map(rs), tankId);
    }

    /**
     * 条件关闭：仅开放事件可关闭，返回受影响行数。
     */
    public int close(Long eventId, Long closedOrderId) {
        return jdbc.update("""
                        UPDATE pending_replenish_event
                        SET status = 'CLOSED', closed_order_id = ?, closed_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status = 'OPEN'
                        """,
                closedOrderId, eventId);
    }

    public long countOpen(Long tankId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pending_replenish_event WHERE tank_id = ? AND status = 'OPEN'",
                Long.class, tankId);
        return count == null ? 0 : count;
    }

    private PendingReplenishEvent map(ResultSet rs) throws SQLException {
        return new PendingReplenishEvent(
                rs.getLong("id"),
                rs.getLong("station_id"),
                rs.getLong("tank_id"),
                rs.getString("source_request_id"),
                rs.getString("source"),
                rs.getBigDecimal("required_kg"),
                rs.getString("status"),
                (Long) rs.getObject("closed_order_id"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("closed_at") == null
                        ? null : rs.getTimestamp("closed_at").toLocalDateTime());
    }
}
