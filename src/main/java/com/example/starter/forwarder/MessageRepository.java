package com.example.starter.forwarder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 消息表数据访问。所有写操作使用参数化 SQL。
 */
@Repository
public class MessageRepository {

    private static final RowMapper<MessageView> MAPPER = new RowMapper<>() {
        @Override
        public MessageView mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp inFlight = rs.getTimestamp("in_flight_since");
            return new MessageView(
                    rs.getLong("id"),
                    rs.getString("device_id"),
                    rs.getString("topic"),
                    rs.getLong("device_seq"),
                    rs.getInt("priority"),
                    rs.getString("dedup_key"),
                    rs.getString("payload"),
                    MessageStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempt_count"),
                    inFlight == null ? null : inFlight.toLocalDateTime(),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime());
        }
    };

    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条 PENDING 消息。
     */
    public void insert(IncomingMessage message, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO forwarder_message
                            (device_id, topic, device_seq, priority, dedup_key, payload,
                             status, attempt_count, in_flight_since, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, NULL, ?, ?)
                        """,
                message.deviceId(), message.topic(), message.deviceSeq(), message.priority(),
                message.dedupKey(), message.payload(), Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    public Optional<MessageView> findByDedupKey(String dedupKey) {
        List<MessageView> rows = jdbc.query(
                "SELECT * FROM forwarder_message WHERE dedup_key = ?", MAPPER, dedupKey);
        return rows.stream().findFirst();
    }

    public Optional<MessageView> findByDeviceAndSeq(String deviceId, long deviceSeq) {
        List<MessageView> rows = jdbc.query(
                "SELECT * FROM forwarder_message WHERE device_id = ? AND device_seq = ?",
                MAPPER, deviceId, deviceSeq);
        return rows.stream().findFirst();
    }

    /**
     * 统计未完结（PENDING + IN_FLIGHT）消息总数。
     */
    public long countUnfinished() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM forwarder_message WHERE status IN ('PENDING','IN_FLIGHT')",
                Long.class);
        return count == null ? 0 : count;
    }

    /**
     * 统计某 Topic 下可补发（PENDING）消息数。
     */
    public long countPendingByTopic(String topic) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM forwarder_message WHERE topic = ? AND status = 'PENDING'",
                Long.class, topic);
        return count == null ? 0 : count;
    }

    /**
     * 查询某 Topic 下最旧的 PENDING 消息（LATEST 覆盖、BOUNDED 挤出用）。
     */
    public List<MessageView> findPendingByTopicOldestFirst(String topic) {
        return jdbc.query("""
                        SELECT * FROM forwarder_message
                        WHERE topic = ? AND status = 'PENDING'
                        ORDER BY created_at ASC, id ASC
                        """, MAPPER, topic);
    }

    /**
     * 查找全局可驱逐记录：最低优先级中最旧且非 in-flight 的消息。
     */
    public Optional<MessageView> findEvictable() {
        List<MessageView> rows = jdbc.query("""
                SELECT * FROM forwarder_message
                WHERE status = 'PENDING'
                ORDER BY priority ASC, created_at ASC, id ASC
                LIMIT 1
                """, MAPPER);
        return rows.stream().findFirst();
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM forwarder_message WHERE id = ?", id);
    }

    /**
     * 拉取一批待补发消息：优先级从高到低，同优先级按设备本地序号升序。
     */
    public List<MessageView> findPendingForDrain(int limit) {
        return jdbc.query("""
                SELECT * FROM forwarder_message
                WHERE status = 'PENDING'
                ORDER BY priority DESC, device_seq ASC, id ASC
                LIMIT ?
                """, MAPPER, limit);
    }

    /**
     * 乐观地将 PENDING 置为 IN_FLIGHT；返回是否抢占成功（并发下只有一方成功）。
     */
    public boolean markInFlight(long id, LocalDateTime now) {
        int updated = jdbc.update("""
                UPDATE forwarder_message
                SET status = 'IN_FLIGHT', in_flight_since = ?, updated_at = ?,
                    attempt_count = attempt_count + 1
                WHERE id = ? AND status = 'PENDING'
                """, Timestamp.valueOf(now), Timestamp.valueOf(now), id);
        return updated == 1;
    }

    /**
     * 发送失败后回到 PENDING。
     */
    public void backToPending(long id, LocalDateTime now) {
        jdbc.update("""
                UPDATE forwarder_message
                SET status = 'PENDING', in_flight_since = NULL, updated_at = ?
                WHERE id = ? AND status = 'IN_FLIGHT'
                """, Timestamp.valueOf(now), id);
    }

    /**
     * 明确确认：仅当当前不是 ACKED 时置为 ACKED，保证重复 ACK 幂等。
     *
     * @return true 表示本次调用首次确认
     */
    public boolean markAcked(long id, LocalDateTime now) {
        int updated = jdbc.update("""
                UPDATE forwarder_message
                SET status = 'ACKED', in_flight_since = NULL, updated_at = ?
                WHERE id = ? AND status <> 'ACKED'
                """, Timestamp.valueOf(now), id);
        return updated == 1;
    }

    /**
     * 将超时（或服务重启后遗留）的 in-flight 消息恢复为 PENDING。
     *
     * @param olderThan 只恢复 in_flight_since 早于该时刻的记录；传 NULL 表示全部恢复
     * @return 恢复的记录
     */
    public List<MessageView> requeueInFlight(LocalDateTime olderThan, LocalDateTime now) {
        List<MessageView> stale;
        if (olderThan == null) {
            stale = jdbc.query(
                    "SELECT * FROM forwarder_message WHERE status = 'IN_FLIGHT'", MAPPER);
            jdbc.update("""
                    UPDATE forwarder_message
                    SET status = 'PENDING', in_flight_since = NULL, updated_at = ?
                    WHERE status = 'IN_FLIGHT'
                    """, Timestamp.valueOf(now));
        } else {
            stale = jdbc.query("""
                    SELECT * FROM forwarder_message
                    WHERE status = 'IN_FLIGHT' AND in_flight_since < ?
                    """, MAPPER, Timestamp.valueOf(olderThan));
            jdbc.update("""
                    UPDATE forwarder_message
                    SET status = 'PENDING', in_flight_since = NULL, updated_at = ?
                    WHERE status = 'IN_FLIGHT' AND in_flight_since < ?
                    """, Timestamp.valueOf(now), Timestamp.valueOf(olderThan));
        }
        return stale;
    }

    public List<MessageView> findAll() {
        return jdbc.query("SELECT * FROM forwarder_message ORDER BY id ASC", MAPPER);
    }

    public long countByStatus(MessageStatus status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM forwarder_message WHERE status = ?",
                Long.class, status.name());
        return count == null ? 0 : count;
    }
}
