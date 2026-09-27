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
 * 待补液事件仓储：补液入账后按可满足性关闭，关闭不自动重发旧加注命令。
 */
@Repository
public class RefillEventRepository {

    private final JdbcTemplate jdbc;

    public RefillEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long stationId, Long tankId, Long planId,
                       BigDecimal requiredKg, BigDecimal shortageKg) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO refill_event
                                (station_id, tank_id, plan_id, required_kg, shortage_kg,
                                 status, created_at)
                            VALUES (?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setLong(2, tankId);
            if (planId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, planId);
            }
            ps.setBigDecimal(4, requiredKg);
            ps.setBigDecimal(5, shortageKg);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    /**
     * 门站全部待补液事件（按创建顺序）。
     */
    public List<EventRow> findPending(Long tankId) {
        return jdbc.query("""
                        SELECT id, required_kg FROM refill_event
                        WHERE tank_id = ? AND status = 'PENDING' ORDER BY id
                        """,
                (rs, rowNum) -> new EventRow(rs.getLong("id"), rs.getBigDecimal("required_kg")),
                tankId);
    }

    public List<Long> findPendingIds(Long tankId) {
        return jdbc.query("""
                        SELECT id FROM refill_event
                        WHERE tank_id = ? AND status = 'PENDING' ORDER BY id
                        """,
                (rs, rowNum) -> rs.getLong("id"),
                tankId);
    }

    public void close(Long eventId, Long refillId, LocalDateTime closedAt) {
        jdbc.update("""
                        UPDATE refill_event
                        SET status = 'CLOSED', closed_by_refill_id = ?, closed_at = ?
                        WHERE id = ?
                        """,
                refillId, Timestamp.valueOf(closedAt), eventId);
    }

    /**
     * 待补液事件行：可满足性按 required_kg 与当前可用量重新判定。
     */
    public record EventRow(Long id, BigDecimal requiredKg) {
    }
}
