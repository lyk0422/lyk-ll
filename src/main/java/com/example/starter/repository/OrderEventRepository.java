package com.example.starter.repository;

import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 调度单事件流水数据访问；迟到确认等只记事件、不改结论。
 */
@Repository
public class OrderEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加调度单事件。
     */
    public void append(long orderId, String eventType, String detail, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO order_event (order_id, event_type, detail, created_at) VALUES (?,?,?,?)",
                orderId, eventType, detail, StationRepository.toOffsetDateTime(now));
    }

    /**
     * 统计某调度单指定类型事件数，供测试断言。
     */
    public int countByOrderAndType(long orderId, String eventType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_event WHERE order_id = ? AND event_type = ?",
                Integer.class, orderId, eventType);
        return count == null ? 0 : count;
    }
}
