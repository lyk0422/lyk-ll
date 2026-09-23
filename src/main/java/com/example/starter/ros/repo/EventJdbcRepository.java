package com.example.starter.ros.repo;

import com.example.starter.ros.dto.ParamEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/** 参数事件流与订阅者 ACK 位置数据访问。 */
@Repository
public class EventJdbcRepository {

    private static final RowMapper<ParamEvent> MAPPER = (rs, n) -> new ParamEvent(
            rs.getLong("cursor"),
            rs.getString("transaction_id"),
            rs.getString("type"),
            rs.getString("node"),
            rs.getString("payload"),
            rs.getLong("created_at")
    );

    private final JdbcTemplate jdbc;

    public EventJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条事务事件，游标由数据库自增生成并回填；必须在事务内调用以保证与提交同生共死。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ParamEvent append(String transactionId, String type, String node, String payload, long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ros_param_event (transaction_id, type, node, payload, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, transactionId);
            ps.setString(2, type);
            ps.setString(3, node);
            ps.setString(4, payload);
            ps.setLong(5, createdAtMs);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long cursor = key == null ? -1L : key.longValue();
        return new ParamEvent(cursor, transactionId, type, node, payload, createdAtMs);
    }

    /** 查询游标严格大于 afterCursor 的事件，按游标升序。 */
    public List<ParamEvent> findAfter(long afterCursor, int limit) {
        return jdbc.query(
                "SELECT cursor, transaction_id, type, node, payload, created_at"
                        + " FROM ros_param_event WHERE cursor > ? ORDER BY cursor ASC LIMIT ?",
                MAPPER, afterCursor, limit);
    }

    /** 订阅者确认游标；仅保存该订阅者见过的最大游标，不允许回退。 */
    public void ack(String subscriberId, long cursor) {
        int updated = jdbc.update(
                "UPDATE ros_event_ack SET cursor = ?, updated_at = ?"
                        + " WHERE subscriber_id = ? AND cursor < ?",
                cursor, System.currentTimeMillis(), subscriberId, cursor);
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO ros_event_ack (subscriber_id, cursor, updated_at) VALUES (?, ?, ?)",
                        subscriberId, cursor, System.currentTimeMillis());
            } catch (DuplicateKeyException concurrent) {
                // 并发首次 ACK：行已由其他线程建立，仅在新游标更大时推进
                jdbc.update(
                        "UPDATE ros_event_ack SET cursor = ?, updated_at = ?"
                                + " WHERE subscriber_id = ? AND cursor < ?",
                        cursor, System.currentTimeMillis(), subscriberId, cursor);
            }
        }
    }

    /** 订阅者已确认游标；从未确认返回 0。 */
    public long getAck(String subscriberId) {
        List<Long> cursors = jdbc.query(
                "SELECT cursor FROM ros_event_ack WHERE subscriber_id = ?",
                (rs, n) -> rs.getLong("cursor"), subscriberId);
        return cursors.isEmpty() ? 0L : cursors.get(0);
    }
}
