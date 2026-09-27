package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;

/**
 * 补液单仓储：运输批号与 requestId 双唯一，批号重复由唯一约束拒绝。
 */
@Repository
public class ReplenishmentOrderRepository {

    private final JdbcTemplate jdbc;

    public ReplenishmentOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(ReplenishmentOrder order) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO replenishment_order
                                (station_id, tank_id, request_id, transport_batch_no, quantity_kg,
                                 accepted_concentration_mg_m3, first_handler, second_handler,
                                 balance_before_kg, balance_after_kg, biz_time, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, order.stationId());
            ps.setLong(2, order.tankId());
            ps.setString(3, order.requestId());
            ps.setString(4, order.transportBatchNo());
            ps.setBigDecimal(5, order.quantityKg());
            ps.setBigDecimal(6, order.acceptedConcentrationMgM3());
            ps.setString(7, order.firstHandler());
            ps.setString(8, order.secondHandler());
            ps.setBigDecimal(9, order.balanceBeforeKg());
            ps.setBigDecimal(10, order.balanceAfterKg());
            ps.setTimestamp(11, Timestamp.valueOf(order.bizTime()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public boolean existsByBatchNo(String transportBatchNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM replenishment_order WHERE transport_batch_no = ?",
                Integer.class, transportBatchNo);
        return count != null && count > 0;
    }
}
