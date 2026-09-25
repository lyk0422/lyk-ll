package com.example.starter.transition.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 迟到事件仓储：归档后到达的检验/复议数据只登记事件。
 */
@Repository
public class LateEventRepository {

    private final JdbcTemplate jdbc;

    public LateEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String orderNo, String rollNo, String eventType, String payload,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO late_event (order_no, roll_no, event_type, payload, recorded_at)
                        VALUES (?,?,?,?,?)
                        """,
                orderNo, rollNo, eventType, payload, Timestamp.valueOf(now));
    }

    public long countByOrderNo(String orderNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM late_event WHERE order_no = ?", Long.class, orderNo);
        return count == null ? 0 : count;
    }
}
