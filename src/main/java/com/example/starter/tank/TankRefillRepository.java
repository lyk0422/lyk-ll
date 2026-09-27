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

/**
 * 补液单仓储：运输批号唯一约束兜底并发重复入账。
 */
@Repository
public class TankRefillRepository {

    private static final String COLUMNS = "id, tank_id, transport_batch_no, quantity_kg, "
            + "concentration_mg_m3, first_operator, second_operator, received_at";

    private final JdbcTemplate jdbc;

    public TankRefillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入补液单；运输批号重复时抛出 DuplicateKeyException，由服务层裁决。
     */
    public Long insert(Long tankId, String requestId, String transportBatchNo, BigDecimal quantityKg,
                       BigDecimal concentrationMgM3, String firstOperator, String secondOperator,
                       LocalDateTime receivedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO tank_refill
                                (tank_id, request_id, transport_batch_no, quantity_kg,
                                 concentration_mg_m3, first_operator, second_operator,
                                 received_at, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, tankId);
            ps.setString(2, requestId);
            ps.setString(3, transportBatchNo);
            ps.setBigDecimal(4, quantityKg);
            ps.setBigDecimal(5, concentrationMgM3);
            ps.setString(6, firstOperator);
            ps.setString(7, secondOperator);
            ps.setTimestamp(8, Timestamp.valueOf(receivedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public List<TankRefill> listByTank(Long tankId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank_refill WHERE tank_id = ? ORDER BY id",
                (rs, rowNum) -> map(rs), tankId);
    }

    private TankRefill map(ResultSet rs) throws SQLException {
        return new TankRefill(
                rs.getLong("id"),
                rs.getLong("tank_id"),
                rs.getString("transport_batch_no"),
                rs.getBigDecimal("quantity_kg"),
                rs.getBigDecimal("concentration_mg_m3"),
                rs.getString("first_operator"),
                rs.getString("second_operator"),
                rs.getTimestamp("received_at").toLocalDateTime());
    }
}
