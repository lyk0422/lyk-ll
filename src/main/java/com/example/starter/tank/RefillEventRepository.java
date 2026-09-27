package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * 待补液事件仓储：补液入账后可用量回到安全储备之上时批量关闭可满足事件。
 */
@Repository
public class RefillEventRepository {

    private final JdbcTemplate jdbc;

    public RefillEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long stationId, Long tankId, Long planId, Long taskId,
                       String source, BigDecimal deficitKg) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO refill_event
                                (station_id, tank_id, plan_id, task_id, source, status,
                                 deficit_kg, created_at)
                            VALUES (?, ?, ?, ?, ?, 'PENDING', ?, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setLong(2, tankId);
            if (planId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, planId);
            }
            if (taskId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, taskId);
            }
            ps.setString(5, source);
            ps.setBigDecimal(6, deficitKg);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public List<RefillEvent> listPendingByTank(Long tankId) {
        return jdbc.query("""
                        SELECT id, station_id, tank_id, plan_id, task_id, source, status,
                               deficit_kg, created_at
                        FROM refill_event WHERE tank_id = ? AND status = 'PENDING' ORDER BY id
                        """,
                (rs, rowNum) -> map(rs),
                tankId);
    }

    public List<RefillEvent> listByStation(Long stationId) {
        return jdbc.query("""
                        SELECT id, station_id, tank_id, plan_id, task_id, source, status,
                               deficit_kg, created_at
                        FROM refill_event WHERE station_id = ? ORDER BY id
                        """,
                (rs, rowNum) -> map(rs),
                stationId);
    }

    public long countPendingByTank(Long tankId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refill_event WHERE tank_id = ? AND status = 'PENDING'",
                Long.class, tankId);
        return count == null ? 0 : count;
    }

    public void close(Long eventId, String closedByRequestId) {
        jdbc.update("""
                        UPDATE refill_event
                        SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP,
                            closed_by_request_id = ?
                        WHERE id = ?
                        """,
                closedByRequestId, eventId);
    }

    private RefillEvent map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RefillEvent(
                rs.getLong("id"),
                rs.getLong("station_id"),
                rs.getLong("tank_id"),
                (Long) rs.getObject("plan_id"),
                (Long) rs.getObject("task_id"),
                rs.getString("source"),
                rs.getString("status"),
                rs.getBigDecimal("deficit_kg"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
