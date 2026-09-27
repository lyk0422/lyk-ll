package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 补液单仓储：运输批号全局唯一（uk_refill_batch），重复批号由唯一约束裁决。
 */
@Repository
public class RefillOrderRepository {

    private final JdbcTemplate jdbc;

    public RefillOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long stationId, Long tankId, String requestId, String batchNo,
                       BigDecimal quantityKg, BigDecimal concentrationMgM3,
                       String firstOperator, String secondOperator, LocalDateTime confirmedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO refill_order
                                (station_id, tank_id, request_id, batch_no, quantity_kg,
                                 concentration_mg_m3, first_operator, second_operator,
                                 confirmed_at, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setLong(2, tankId);
            ps.setString(3, requestId);
            ps.setString(4, batchNo);
            ps.setBigDecimal(5, quantityKg);
            ps.setBigDecimal(6, concentrationMgM3);
            ps.setString(7, firstOperator);
            ps.setString(8, secondOperator);
            ps.setTimestamp(9, Timestamp.valueOf(confirmedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public long countByBatchNo(String batchNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refill_order WHERE batch_no = ?", Long.class, batchNo);
        return count == null ? 0 : count;
    }
}
