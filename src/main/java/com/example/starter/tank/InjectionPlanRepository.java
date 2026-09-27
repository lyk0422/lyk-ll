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
 * 受控加注计划仓储：requestId 唯一，回执/取消以状态机条件更新保证唯一计量。
 */
@Repository
public class InjectionPlanRepository {

    private final JdbcTemplate jdbc;

    public InjectionPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_COLUMNS = """
            SELECT id, station_id, tank_id, request_id, source, biz_time, required_kg,
                   consumed_kg, released_kg, status, version, executed_at, cancelled_at
            FROM injection_plan
            """;

    public Long insertIssued(Long stationId, Long tankId, String requestId, String source,
                             LocalDateTime bizTime, BigDecimal requiredKg) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO injection_plan
                                (station_id, tank_id, request_id, source, biz_time, required_kg,
                                 consumed_kg, released_kg, status, version, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, 0, 0, 'ISSUED', 1, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setLong(2, tankId);
            ps.setString(3, requestId);
            ps.setString(4, source);
            ps.setTimestamp(5, Timestamp.valueOf(bizTime));
            ps.setBigDecimal(6, requiredKg);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public Optional<InjectionPlan> findByRequestIdForUpdate(String requestId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE request_id = ? FOR UPDATE",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), requestId);
    }

    public Optional<InjectionPlan> findByRequestId(String requestId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE request_id = ?",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), requestId);
    }

    /**
     * 回执：仅 ISSUED 计划可执行；条件更新 1 行表示本次抢占到唯一回执权，
     * 并发回执/取消中落败方得到 0 行，不重复扣量。
     */
    public int markExecuted(Long planId, String receiptRequestId, BigDecimal consumedKg,
                            BigDecimal releasedKg, LocalDateTime executedAt) {
        return jdbc.update("""
                        UPDATE injection_plan
                        SET status = 'EXECUTED', consumed_kg = ?, released_kg = ?,
                            executed_at = ?, receipt_request_id = ?
                        WHERE id = ? AND status = 'ISSUED'
                        """,
                consumedKg, releasedKg, Timestamp.valueOf(executedAt), receiptRequestId, planId);
    }

    /**
     * 取消：仅 ISSUED 计划可取消，释放全部尚未执行预占。
     */
    public int markCancelled(Long planId, String cancelRequestId, BigDecimal releasedKg,
                             LocalDateTime cancelledAt) {
        return jdbc.update("""
                        UPDATE injection_plan
                        SET status = 'CANCELLED', released_kg = ?, cancelled_at = ?,
                            cancel_request_id = ?
                        WHERE id = ? AND status = 'ISSUED'
                        """,
                releasedKg, Timestamp.valueOf(cancelledAt), cancelRequestId, planId);
    }

    public List<InjectionPlan> findOpenByTank(Long tankId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE tank_id = ? AND status = 'ISSUED' ORDER BY id ASC",
                (rs, rowNum) -> map(rs), tankId);
    }

    private InjectionPlan map(ResultSet rs) throws SQLException {
        return new InjectionPlan(
                rs.getLong("id"),
                rs.getLong("station_id"),
                rs.getLong("tank_id"),
                rs.getString("request_id"),
                rs.getString("source"),
                rs.getTimestamp("biz_time").toLocalDateTime(),
                rs.getBigDecimal("required_kg"),
                rs.getBigDecimal("consumed_kg"),
                rs.getBigDecimal("released_kg"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getTimestamp("executed_at") == null
                        ? null : rs.getTimestamp("executed_at").toLocalDateTime(),
                rs.getTimestamp("cancelled_at") == null
                        ? null : rs.getTimestamp("cancelled_at").toLocalDateTime());
    }
}
