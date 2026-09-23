package com.example.starter.rosconfig.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 事务幂等记录、配置事件流、订阅者确认游标的数据访问。
 */
@Repository
public class EventRepository {

    /** 仅提交成功的事务产生该类事件 */
    public static final String EVENT_COMMITTED = "CONFIG_COMMITTED";

    private final JdbcTemplate jdbc;

    public EventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等事务记录状态 */
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_REJECTED = "REJECTED";

    /** 幂等事务记录（首次处理结果，失败/成功都留存以便同参重放）。 */
    public record TransactionRecord(String transactionId, String requestHash,
                                    String status, String responseText) {
    }

    /**
     * 尝试插入 PROCESSING 事务记录以抢占幂等键；插入成功返回 true，键已存在返回 false。
     */
    public boolean tryInsertProcessing(String transactionId, String requestHash, long now) {
        try {
            jdbc.update("INSERT INTO transaction_record(transaction_id, request_hash, status, response_text, created_at, updated_at) "
                            + "VALUES (?, ?, ?, NULL, ?, ?)",
                    transactionId, requestHash, STATUS_PROCESSING, now, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 将处理中记录推进到终态（COMMITTED/REJECTED）并保存结果 JSON。 */
    public void finishRecord(String transactionId, String status, String responseJson, long now) {
        jdbc.update("UPDATE transaction_record SET status = ?, response_text = ?, updated_at = ? "
                        + "WHERE transaction_id = ?",
                status, responseJson, now, transactionId);
    }

    /** 读取已有事务记录；不存在返回 null。 */
    public TransactionRecord findRecord(String transactionId) {
        return jdbc.query("SELECT transaction_id, request_hash, status, response_text "
                        + "FROM transaction_record WHERE transaction_id = ?",
                rs -> rs.next()
                        ? new TransactionRecord(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4))
                        : null, transactionId);
    }

    /** 删除处理中占位记录（非预期异常后释放幂等键）。 */
    public int deleteRecord(String transactionId) {
        return jdbc.update("DELETE FROM transaction_record WHERE transaction_id = ?", transactionId);
    }

    /**
     * 写入一条提交事件并返回自增单调游标；须与版本/参数值写入处于同一数据库事务内。
     */
    public long insertEvent(String transactionId, String eventType, String payloadJson, long createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO config_event(transaction_id, event_type, payload, created_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, transactionId);
            ps.setString(2, eventType);
            ps.setString(3, payloadJson);
            ps.setLong(4, createdAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("event cursor not generated");
        }
        return key.longValue();
    }

    /** 事件行。 */
    public record EventRow(long cursor, String transactionId, String eventType, String payload) {
    }

    /** 读取游标严格大于 afterCursor 的事件（最多 limit 条），按游标升序。 */
    public List<EventRow> findEventsAfter(long afterCursor, int limit) {
        return jdbc.query("SELECT cursor, transaction_id, event_type, payload FROM config_event "
                        + "WHERE cursor > ? ORDER BY cursor ASC LIMIT ?",
                (rs, n) -> new EventRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                afterCursor, limit);
    }

    /** 订阅者已确认游标；未确认过返回 0。 */
    public long getAckedCursor(String subscriberId) {
        Long v = jdbc.query("SELECT acked_cursor FROM subscriber_cursor WHERE subscriber_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, subscriberId);
        return v == null ? 0L : v;
    }

    /** 保存订阅者确认位置（单调不回退）。 */
    public void saveAckedCursor(String subscriberId, long cursor) {
        jdbc.update("MERGE INTO subscriber_cursor(subscriber_id, acked_cursor) KEY(subscriber_id) "
                        + "VALUES (?, GREATEST(COALESCE((SELECT acked_cursor FROM subscriber_cursor WHERE subscriber_id = ?), 0), ?))",
                subscriberId, subscriberId, cursor);
    }
}
