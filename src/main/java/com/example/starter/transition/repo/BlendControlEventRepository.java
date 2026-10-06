package com.example.starter.transition.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 掺配控制事件仓储：失控暂停转人工与复核恢复留痕。
 */
@Repository
public class BlendControlEventRepository {

    private final JdbcTemplate jdbc;

    public BlendControlEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String orderNo, String eventType, String requestId,
                       String reviewer, String detail, LocalDateTime eventTime) {
        jdbc.update("""
                        INSERT INTO blend_control_event
                            (order_no, event_type, request_id, reviewer, detail, event_time)
                        VALUES (?,?,?,?,?,?)
                        """,
                orderNo, eventType, requestId, reviewer, detail, Timestamp.valueOf(eventTime));
    }

    /** 供测试核对事件数。 */
    public long countByOrderNoAndType(String orderNo, String eventType) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_event WHERE order_no = ? AND event_type = ?",
                Long.class, orderNo, eventType);
        return count == null ? 0 : count;
    }
}
