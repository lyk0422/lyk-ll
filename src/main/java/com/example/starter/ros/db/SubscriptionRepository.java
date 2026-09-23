package com.example.starter.ros.db;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 订阅者 ACK 游标数据访问。
 */
@Repository
public class SubscriptionRepository {

    private final JdbcTemplate jdbcTemplate;

    public SubscriptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 订阅者不存在时返回空（视为游标 0）。 */
    public Long findAckCursor(String subscriberId) {
        return jdbcTemplate.query(
                        "SELECT ack_cursor FROM subscription_ack WHERE subscriber_id = ?",
                        rs -> rs.next() ? rs.getLong("ack_cursor") : null,
                        subscriberId);
    }

    /** 保存订阅者已确认游标；仅允许首次写入或向前推进，绝不回退。 */
    public void saveAck(String subscriberId, long cursor) {
        Long existing = findAckCursor(subscriberId);
        if (existing == null) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO subscription_ack (subscriber_id, ack_cursor) VALUES (?, ?)",
                        subscriberId, cursor);
            } catch (DuplicateKeyException concurrentInsert) {
                advanceIfGreater(subscriberId, cursor);
            }
        } else {
            advanceIfGreater(subscriberId, cursor);
        }
    }

    private void advanceIfGreater(String subscriberId, long cursor) {
        jdbcTemplate.update(
                "UPDATE subscription_ack SET ack_cursor = ? WHERE subscriber_id = ? AND ack_cursor < ?",
                cursor, subscriberId, cursor);
    }
}
