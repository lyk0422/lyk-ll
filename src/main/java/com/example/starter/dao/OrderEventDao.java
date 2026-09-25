package com.example.starter.dao;

import com.example.starter.domain.ActorType;
import com.example.starter.domain.EventType;
import com.example.starter.po.OrderEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 工单轨迹访问。
 */
@Repository
public class OrderEventDao {

    private final JdbcTemplate jdbc;

    public OrderEventDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(long orderId, EventType type, Instant eventTime, ActorType actor, String detail) {
        append(orderId, type, eventTime, actor, detail, null);
    }

    public void append(long orderId, EventType type, Instant eventTime, ActorType actor,
                       String detail, Long relatedOrderId) {
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO order_event (order_id, event_type, event_time, actor, detail, related_order_id, create_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                orderId, type.name(), Timestamp.from(eventTime), actor.name(),
                truncate(detail), relatedOrderId, Timestamp.from(now));
    }

    public List<OrderEvent> findByOrderId(long orderId) {
        return jdbc.query(
                "SELECT id, order_id, event_type, event_time, actor, detail, related_order_id, create_time"
                        + " FROM order_event WHERE order_id = ? ORDER BY event_time, id",
                (rs, n) -> {
                    OrderEvent e = new OrderEvent();
                    e.setId(rs.getLong("id"));
                    e.setOrderId(rs.getLong("order_id"));
                    e.setEventType(EventType.valueOf(rs.getString("event_type")));
                    Timestamp ts = rs.getTimestamp("event_time");
                    e.setEventTime(ts == null ? null : ts.toInstant());
                    e.setActor(ActorType.valueOf(rs.getString("actor")));
                    e.setDetail(rs.getString("detail"));
                    long related = rs.getLong("related_order_id");
                    e.setRelatedOrderId(rs.wasNull() ? null : related);
                    Timestamp ct = rs.getTimestamp("create_time");
                    e.setCreateTime(ct == null ? null : ct.toInstant());
                    return e;
                },
                orderId);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
