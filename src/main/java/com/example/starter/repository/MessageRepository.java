package com.example.starter.repository;

import com.example.starter.enums.MessageStatus;
import com.example.starter.model.DeviceMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备消息仓储：负责待发/in-flight/已确认消息的持久化与状态流转。
 */
@Repository
public class MessageRepository {

    private static final String COLUMNS =
            "id, message_id, device_id, topic, seq, priority, payload, status, attempts, "
                    + "enqueued_at, inflight_at, forwarded_at, acked_at";

    private static final MessageRowMapper ROW_MAPPER = new MessageRowMapper();

    /** 补发顺序：优先级降序，同优先级按设备本地序号升序，再以设备、主键做确定性兜底。 */
    private static final Comparator<DeviceMessage> DISPATCH_ORDER =
            Comparator.comparingInt(DeviceMessage::getPriority).reversed()
                    .thenComparingLong(DeviceMessage::getSeq)
                    .thenComparing(DeviceMessage::getDeviceId)
                    .thenComparingLong(DeviceMessage::getId);

    private final JdbcTemplate jdbcTemplate;

    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按 dedupKey 查找消息，不存在返回 null。 */
    public DeviceMessage findByMessageId(String messageId) {
        List<DeviceMessage> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE message_id = ?",
                ROW_MAPPER, messageId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 插入新消息。 */
    public void insert(DeviceMessage message) {
        jdbcTemplate.update(
                "INSERT INTO device_message "
                        + "(message_id, device_id, topic, seq, priority, payload, status, "
                        + "attempts, enqueued_at, inflight_at, forwarded_at, acked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                message.getMessageId(),
                message.getDeviceId(),
                message.getTopic(),
                message.getSeq(),
                message.getPriority(),
                message.getPayload(),
                message.getStatus().name(),
                message.getAttempts(),
                message.getEnqueuedAt(),
                message.getInflightAt(),
                message.getForwardedAt(),
                message.getAckedAt());
    }

    /** 更新消息状态与时间字段。 */
    public void updateStatus(DeviceMessage message) {
        jdbcTemplate.update(
                "UPDATE device_message SET status = ?, attempts = ?, inflight_at = ?, "
                        + "forwarded_at = ?, acked_at = ? WHERE id = ?",
                message.getStatus().name(),
                message.getAttempts(),
                message.getInflightAt(),
                message.getForwardedAt(),
                message.getAckedAt(),
                message.getId());
    }

    /**
     * 条件 ACK：仅当消息处于 INFLIGHT，或曾投递成功（PENDING 且 forwarded_at 非空，
     * 例如超时回收/崩溃恢复后迟到的 ACK）时确认。
     * 已 FORWARDED 的重复 ACK、从未投递消息的游离 ACK 均不改变数据。
     *
     * @return true 表示本次调用首次完成确认
     */
    public boolean confirmAcked(String messageId, long now) {
        int updated = jdbcTemplate.update(
                "UPDATE device_message SET status = 'FORWARDED', acked_at = ? "
                        + "WHERE message_id = ? AND ("
                        + "status = 'INFLIGHT' OR (status = 'PENDING' AND forwarded_at IS NOT NULL))",
                now, messageId);
        return updated > 0;
    }

    /** 将超时的 in-flight 消息回退为 PENDING，返回受影响条数。 */
    public int revertTimedOutInflight(long now, long timeoutMs) {
        return jdbcTemplate.update(
                "UPDATE device_message SET status = 'PENDING', inflight_at = NULL "
                        + "WHERE status = 'INFLIGHT' AND inflight_at IS NOT NULL AND inflight_at <= ?",
                now - timeoutMs);
    }

    /** 启动恢复：将所有 INFLIGHT 消息回退为 PENDING（崩溃后 ACK 可能丢失，允许重试）。 */
    public int recoverInflightOnStartup() {
        return jdbcTemplate.update(
                "UPDATE device_message SET status = 'PENDING', inflight_at = NULL "
                        + "WHERE status = 'INFLIGHT'");
    }

    /** 统计非 FORWARDED 状态（PENDING+INFLIGHT）消息数。 */
    public int countActive() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_message WHERE status IN ('PENDING', 'INFLIGHT')",
                Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 驱逐最低优先级中最旧且非 in-flight 的一条消息，返回被驱逐消息；无候选返回 null。
     */
    @Transactional
    public DeviceMessage evictLowestPriorityOldest() {
        List<DeviceMessage> candidates = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE status = 'PENDING' "
                        + "ORDER BY priority ASC, enqueued_at ASC, id ASC LIMIT 1 FOR UPDATE",
                ROW_MAPPER);
        if (candidates.isEmpty()) {
            return null;
        }
        DeviceMessage victim = candidates.get(0);
        jdbcTemplate.update("DELETE FROM device_message WHERE id = ?", victim.getId());
        return victim;
    }

    /** 按主题查询某状态下最旧的一条（LATEST 覆盖用），不存在返回 null。 */
    public DeviceMessage findOldestByTopicAndStatus(String topic, MessageStatus status) {
        List<DeviceMessage> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE topic = ? AND status = ? "
                        + "ORDER BY enqueued_at ASC, id ASC",
                ROW_MAPPER, topic, status.name());
        return list.isEmpty() ? null : list.get(0);
    }

    /** BOUNDED：查询主题下 PENDING 数量。 */
    public int countPendingByTopic(String topic) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_message WHERE topic = ? AND status = 'PENDING'",
                Integer.class, topic);
        return count == null ? 0 : count;
    }

    /** BOUNDED：删除主题下最旧的 PENDING 消息，返回被删除消息；无候选返回 null。 */
    @Transactional
    public DeviceMessage deleteOldestPendingByTopic(String topic) {
        List<DeviceMessage> candidates = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE topic = ? AND status = 'PENDING' "
                        + "ORDER BY enqueued_at ASC, id ASC LIMIT 1 FOR UPDATE",
                ROW_MAPPER, topic);
        if (candidates.isEmpty()) {
            return null;
        }
        DeviceMessage victim = candidates.get(0);
        jdbcTemplate.update("DELETE FROM device_message WHERE id = ?", victim.getId());
        return victim;
    }

    /** 删除指定消息。 */
    public void deleteById(long id) {
        jdbcTemplate.update("DELETE FROM device_message WHERE id = ?", id);
    }

    /**
     * 取出待投递批次：按优先级降序、同优先级按设备本地序号升序选定 PENDING 消息，
     * 并在同一事务内原子置为 INFLIGHT，避免多线程重复领取。
     */
    @Transactional
    public List<DeviceMessage> claimPendingBatch(int limit, long now) {
        List<DeviceMessage> candidates = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE status = 'PENDING' "
                        + "ORDER BY priority DESC, seq ASC, device_id ASC, id ASC LIMIT ? FOR UPDATE",
                ROW_MAPPER, limit);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(candidates.size());
        for (DeviceMessage message : candidates) {
            ids.add(message.getId());
        }
        int updated = jdbcTemplate.update(
                "UPDATE device_message SET status = 'INFLIGHT', attempts = attempts + 1, "
                        + "inflight_at = ? WHERE id IN (" + placeholders(ids.size()) + ")",
                ps -> {
                    ps.setLong(1, now);
                    for (int i = 0; i < ids.size(); i++) {
                        ps.setLong(2 + i, ids.get(i));
                    }
                });
        if (updated != ids.size()) {
            throw new IllegalStateException("领取待发消息数量与状态更新数量不一致: "
                    + ids.size() + " != " + updated);
        }
        List<DeviceMessage> claimed = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE id IN ("
                        + placeholders(ids.size()) + ")",
                ROW_MAPPER, ids.toArray());
        claimed.sort(DISPATCH_ORDER);
        return claimed;
    }

    /** 快照当前待发消息（按补发顺序），供状态查询与测试使用。 */
    public List<DeviceMessage> findPendingSnapshot() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE status = 'PENDING' "
                        + "ORDER BY priority DESC, seq ASC, device_id ASC, id ASC",
                ROW_MAPPER);
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static final class MessageRowMapper implements RowMapper<DeviceMessage> {
        @Override
        public DeviceMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
            DeviceMessage message = new DeviceMessage();
            message.setId(rs.getLong("id"));
            message.setMessageId(rs.getString("message_id"));
            message.setDeviceId(rs.getString("device_id"));
            message.setTopic(rs.getString("topic"));
            message.setSeq(rs.getLong("seq"));
            message.setPriority(rs.getInt("priority"));
            message.setPayload(rs.getString("payload"));
            message.setStatus(MessageStatus.valueOf(rs.getString("status")));
            message.setAttempts(rs.getInt("attempts"));
            message.setEnqueuedAt(rs.getLong("enqueued_at"));
            long inflight = rs.getLong("inflight_at");
            message.setInflightAt(rs.wasNull() ? null : inflight);
            long forwarded = rs.getLong("forwarded_at");
            message.setForwardedAt(rs.wasNull() ? null : forwarded);
            long acked = rs.getLong("acked_at");
            message.setAckedAt(rs.wasNull() ? null : acked);
            return message;
        }
    }
}
