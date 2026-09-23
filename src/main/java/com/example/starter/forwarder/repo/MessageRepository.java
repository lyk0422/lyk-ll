package com.example.starter.forwarder.repo;

import com.example.starter.forwarder.model.DeviceMessage;
import com.example.starter.forwarder.model.MessageStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 设备消息仓储：待发 / in-flight / 已确认状态全部持久化在 H2 文件库，
 * 所有状态迁移使用条件 UPDATE 保证并发与重复 ACK 安全。
 */
@Repository
public class MessageRepository {

    private static final String COLUMNS = "id, message_key, device_id, topic, seq, priority, dedup_key, "
            + "payload, status, received_at, inflight_deadline, dispatched_count, last_dispatched_at, acked_at";

    private final JdbcTemplate jdbcTemplate;

    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    static final RowMapper<DeviceMessage> MAPPER = new RowMapper<>() {
        @Override
        public DeviceMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
            DeviceMessage m = new DeviceMessage();
            m.setId(rs.getLong("id"));
            m.setMessageKey(rs.getString("message_key"));
            m.setDeviceId(rs.getString("device_id"));
            m.setTopic(rs.getString("topic"));
            m.setSeq(rs.getLong("seq"));
            m.setPriority(rs.getInt("priority"));
            m.setDedupKey(rs.getString("dedup_key"));
            m.setPayload(rs.getString("payload"));
            m.setStatus(MessageStatus.valueOf(rs.getString("status")));
            m.setReceivedAt(rs.getLong("received_at"));
            long deadline = rs.getLong("inflight_deadline");
            m.setInflightDeadline(rs.wasNull() ? null : deadline);
            m.setDispatchedCount(rs.getInt("dispatched_count"));
            long last = rs.getLong("last_dispatched_at");
            m.setLastDispatchedAt(rs.wasNull() ? null : last);
            long acked = rs.getLong("acked_at");
            m.setAckedAt(rs.wasNull() ? null : acked);
            return m;
        }
    };

    /** 按全局去重键查询任意状态的消息。 */
    public DeviceMessage findByDedupKey(String dedupKey) {
        List<DeviceMessage> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE dedup_key = ?", MAPPER, dedupKey);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 插入新消息，回填自增主键。 */
    public void insert(DeviceMessage message) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO device_message (message_key, device_id, topic, seq, priority, dedup_key, "
                            + "payload, status, received_at, inflight_deadline, dispatched_count, "
                            + "last_dispatched_at, acked_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, message.getMessageKey());
            ps.setString(2, message.getDeviceId());
            ps.setString(3, message.getTopic());
            ps.setLong(4, message.getSeq());
            ps.setInt(5, message.getPriority());
            ps.setString(6, message.getDedupKey());
            ps.setString(7, message.getPayload());
            ps.setString(8, message.getStatus().name());
            ps.setLong(9, message.getReceivedAt());
            if (message.getInflightDeadline() == null) {
                ps.setObject(10, null);
            } else {
                ps.setLong(10, message.getInflightDeadline());
            }
            ps.setInt(11, message.getDispatchedCount());
            if (message.getLastDispatchedAt() == null) {
                ps.setObject(12, null);
            } else {
                ps.setLong(12, message.getLastDispatchedAt());
            }
            if (message.getAckedAt() == null) {
                ps.setObject(13, null);
            } else {
                ps.setLong(13, message.getAckedAt());
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            message.setId(key.longValue());
        }
    }

    /** 当前待发 + in-flight 占用容量（已确认记录不占容量）。 */
    public int activeCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_message WHERE status IN ('PENDING','IN_FLIGHT')", Integer.class);
        return count == null ? 0 : count;
    }

    /** 查询某 Topic 下最旧的待发消息（LATEST/BOUNDED 驱逐用）。 */
    public DeviceMessage findOldestPendingByTopic(String topic) {
        List<DeviceMessage> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE topic = ? AND status = 'PENDING' "
                        + "ORDER BY id ASC LIMIT 1", MAPPER, topic);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询某 Topic 待发数量。 */
    public int countPendingByTopic(String topic) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_message WHERE topic = ? AND status = 'PENDING'",
                Integer.class, topic);
        return count == null ? 0 : count;
    }

    /**
     * 全局容量驱逐候选：优先级最低、其中最旧、且非 in-flight 的待发记录。
     * 已确认记录不占容量，in-flight 记录不允许驱逐。
     */
    public DeviceMessage findEvictionCandidate() {
        List<DeviceMessage> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE status = 'PENDING' "
                        + "ORDER BY priority ASC, id ASC LIMIT 1", MAPPER);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按主键删除（驱逐/覆盖场景）。 */
    public int deleteById(long id) {
        return jdbcTemplate.update("DELETE FROM device_message WHERE id = ?", id);
    }

    /**
     * 原子抢占一条待发消息为 in-flight；仅当记录仍为 PENDING 时成功，
     * 同时累加补发次数，保证并发调度不会重复发送。
     */
    public boolean markInFlightIfPending(long id, long deadline, long now) {
        int updated = jdbcTemplate.update(
                "UPDATE device_message SET status = 'IN_FLIGHT', inflight_deadline = ?, "
                        + "dispatched_count = dispatched_count + 1, last_dispatched_at = ? "
                        + "WHERE id = ? AND status = 'PENDING'",
                deadline, now, id);
        return updated > 0;
    }

    /**
     * 按补发顺序取出待发消息：优先级从高到低，同一优先级按设备 ID 分组、
     * 设备本地单调序号升序，序号相同按接入先后兜底；不加锁，由条件 UPDATE 抢占。
     */
    public List<DeviceMessage> findPendingOrdered(int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE status = 'PENDING' "
                        + "ORDER BY priority DESC, device_id ASC, seq ASC, id ASC LIMIT ?",
                MAPPER, limit);
    }

    /**
     * 精确确认一条 in-flight 消息。重复 ACK（已确认）、乱序 ACK（目标仍是 PENDING）
     * 或未知 dedupKey 都不会命中，也不会影响其他序号的记录。
     */
    public boolean acknowledgeIfInFlight(String dedupKey, long now) {
        int updated = jdbcTemplate.update(
                "UPDATE device_message SET status = 'ACKNOWLEDGED', acked_at = ?, inflight_deadline = NULL "
                        + "WHERE dedup_key = ? AND status = 'IN_FLIGHT'",
                now, dedupKey);
        return updated > 0;
    }

    /**
     * 发送上游失败：in-flight 记录回退为待发，等待下一轮重试，不计入补发次数回退以外的状态残留。
     */
    public int revertToPendingAfterSendFailure(long id) {
        return jdbcTemplate.update(
                "UPDATE device_message SET status = 'PENDING', inflight_deadline = NULL WHERE id = ?",
                id);
    }

    /** 查询全部在途消息（启动恢复审计用）。 */
    public List<DeviceMessage> findAllInFlight() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message WHERE status = 'IN_FLIGHT' ORDER BY id ASC", MAPPER);
    }

    /** 启动恢复：上次崩溃遗留的 in-flight 全部重置为 PENDING，允许重启后重试。 */
    public int resetStaleInFlightOnStartup() {
        return jdbcTemplate.update(
                "UPDATE device_message SET status = 'PENDING', inflight_deadline = NULL "
                        + "WHERE status = 'IN_FLIGHT'");
    }

    /**
     * 超时恢复：deadline 已过的 in-flight 记录重置为 PENDING，返回被重置的记录供审计。
     */
    public List<DeviceMessage> resetTimedOutInFlight(long now) {
        List<DeviceMessage> timedOut = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_message "
                        + "WHERE status = 'IN_FLIGHT' AND inflight_deadline IS NOT NULL AND inflight_deadline <= ?",
                MAPPER, now);
        if (!timedOut.isEmpty()) {
            jdbcTemplate.update(
                    "UPDATE device_message SET status = 'PENDING', inflight_deadline = NULL "
                            + "WHERE status = 'IN_FLIGHT' AND inflight_deadline IS NOT NULL AND inflight_deadline <= ?",
                    now);
        }
        return timedOut;
    }
}
