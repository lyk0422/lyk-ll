package com.example.starter.dao;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 负荷恢复记录：必须与复电确认完成在同一事务写入（uk_load_order 保证一单一记录）。
 */
@Repository
public class LoadRecoveryDao {

    private final JdbcTemplate jdbc;

    public LoadRecoveryDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long orderId, BigDecimal restoredLoadKw, int recoveredUsers, Instant recordTime) {
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO load_recovery (order_id, restored_load_kw, recovered_users, record_time, create_time)"
                        + " VALUES (?, ?, ?, ?, ?)",
                orderId, restoredLoadKw, recoveredUsers, Timestamp.from(recordTime), Timestamp.from(now));
    }

    public Optional<LoadRow> findByOrderId(long orderId) {
        return jdbc.query(
                "SELECT order_id, restored_load_kw, recovered_users, record_time FROM load_recovery WHERE order_id = ?",
                (rs, n) -> new LoadRow(rs.getLong("order_id"),
                        rs.getBigDecimal("restored_load_kw"),
                        rs.getInt("recovered_users"),
                        rs.getTimestamp("record_time").toInstant()),
                orderId).stream().findFirst();
    }

    /**
     * 负荷恢复行。
     */
    public record LoadRow(long orderId, BigDecimal restoredLoadKw, int recoveredUsers, Instant recordTime) {
    }
}
