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
 * 补液单仓储：运输批号唯一约束兜底并发重复入账。
 */
@Repository
public class RefillOrderRepository {

    private final JdbcTemplate jdbc;

    public RefillOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long stationId, Long tankId, String requestId, String transportBatchNo,
                       BigDecimal quantityKg, BigDecimal acceptConcentrationMgL,
                       String firstHandler, String secondHandler, LocalDateTime confirmedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO refill_order
                                (station_id, tank_id, request_id, transport_batch_no, quantity_kg,
                                 accept_concentration_mg_l, first_handler, second_handler,
                                 confirmed_at, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setLong(2, tankId);
            ps.setString(3, requestId);
            ps.setString(4, transportBatchNo);
            ps.setBigDecimal(5, quantityKg);
            ps.setBigDecimal(6, acceptConcentrationMgL);
            ps.setString(7, firstHandler);
            ps.setString(8, secondHandler);
            ps.setTimestamp(9, Timestamp.valueOf(confirmedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }
}
