package com.example.starter.dao;

import com.example.starter.domain.Grade;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 发电车流程记录（特级用户停电恰满 5 分钟触发）。
 */
@Repository
public class GeneratorTruckDao {

    private final JdbcTemplate jdbc;

    public GeneratorTruckDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsByOrder(long orderId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM generator_truck WHERE order_id = ?", Long.class, orderId);
        return n != null && n > 0;
    }

    public void insertTriggered(long orderId, Grade grade, Instant outageTime, Instant triggerTime, String note) {
        jdbc.update(
                "INSERT INTO generator_truck (order_id, grade, outage_time, trigger_time, status, note, create_time)"
                        + " VALUES (?, ?, ?, ?, 'TRIGGERED', ?, ?)",
                orderId, grade.name(), Timestamp.from(outageTime), Timestamp.from(triggerTime),
                note, Timestamp.from(Instant.now()));
    }

    public void markWithdrawn(long orderId) {
        jdbc.update("UPDATE generator_truck SET status = 'WITHDRAWN' WHERE order_id = ?", orderId);
    }

    public Optional<GeneratorRow> findByOrder(long orderId) {
        List<GeneratorRow> list = jdbc.query(
                "SELECT order_id, grade, outage_time, trigger_time, status FROM generator_truck WHERE order_id = ?",
                (rs, n) -> new GeneratorRow(rs.getLong("order_id"),
                        Grade.valueOf(rs.getString("grade")),
                        rs.getTimestamp("outage_time").toInstant(),
                        rs.getTimestamp("trigger_time").toInstant(),
                        rs.getString("status")),
                orderId);
        return list.stream().findFirst();
    }

    /**
     * 发电车行。
     */
    public record GeneratorRow(long orderId, Grade grade, Instant outageTime,
                              Instant triggerTime, String status) {
    }
}
