package com.example.starter.rosbag.repository;

import com.example.starter.rosbag.dto.MessageView;
import com.example.starter.rosbag.model.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息主体、查询索引与隔离表的数据访问。
 * 查询一律基于 rb_segment 粗筛 + rb_message_index 精过滤，不扫描全量消息。
 */
@Repository
public class MessageRepository {

    private static final RowMapper<Message> MESSAGE_MAPPER = (rs, n) -> new Message(
            rs.getLong("id"),
            rs.getString("topic"),
            rs.getLong("seq"),
            rs.getLong("timestamp_ns"),
            rs.getString("type_id"),
            rs.getString("payload"),
            rs.getLong("payload_checksum"),
            rs.getString("batch_no"),
            rs.getLong("segment_id"),
            rs.getBoolean("valid")
    );

    private static final String VIEW_COLUMNS =
            "m.id, m.topic, m.seq, m.timestamp_ns, m.type_id, m.payload, "
                    + "m.payload_checksum, m.batch_no, m.segment_id";

    private static final RowMapper<MessageView> VIEW_MAPPER = (rs, n) -> new MessageView(
            rs.getLong("id"),
            rs.getString("topic"),
            rs.getLong("seq"),
            rs.getLong("timestamp_ns"),
            rs.getString("type_id"),
            rs.getString("payload"),
            rs.getLong("payload_checksum"),
            rs.getString("batch_no"),
            rs.getLong("segment_id")
    );

    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 (topic, batchNo) 查找消息，用于同参重试幂等；不存在返回 null。
     */
    public Message findByTopicAndBatch(String topic, String batchNo) {
        List<Message> list = jdbc.query(
                "SELECT id, topic, seq, timestamp_ns, type_id, payload, payload_checksum, "
                        + "batch_no, segment_id, valid FROM rb_message WHERE topic = ? AND batch_no = ?",
                MESSAGE_MAPPER, topic, batchNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Topic 内当前最大有效序列号；无消息返回 null。
     */
    public Long findMaxValidSeq(String topic) {
        return jdbc.queryForObject(
                "SELECT MAX(seq) FROM rb_message WHERE topic = ? AND valid = TRUE",
                Long.class, topic);
    }

    /**
     * 插入一条有效消息并返回生成的主键。
     */
    public long insert(String topic, long seq, long timestampNs, String typeId, String payload,
                       long payloadChecksum, String batchNo, long segmentId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rb_message (topic, seq, timestamp_ns, type_id, payload, "
                            + "payload_checksum, batch_no, segment_id, valid) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, topic);
            ps.setLong(2, seq);
            ps.setLong(3, timestampNs);
            ps.setString(4, typeId);
            ps.setString(5, payload);
            ps.setLong(6, payloadChecksum);
            ps.setString(7, batchNo);
            ps.setLong(8, segmentId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入消息未获取到主键");
        }
        return key.longValue();
    }

    /**
     * 写入单条索引行。
     */
    public void insertIndex(long messageId, String topic, long segmentId, long timestampNs, long seq) {
        jdbc.update(
                "INSERT INTO rb_message_index (message_id, topic, segment_id, timestamp_ns, seq) "
                        + "VALUES (?, ?, ?, ?, ?)",
                messageId, topic, segmentId, timestampNs, seq);
    }

    /**
     * 删除某 Topic 的全部索引行（恢复重建前调用）。
     */
    public int deleteIndexForTopic(String topic) {
        return jdbc.update("DELETE FROM rb_message_index WHERE topic = ?", topic);
    }

    /**
     * 依据剩余有效消息整体重建某 Topic 的索引。
     */
    public int rebuildIndexForTopic(String topic) {
        return jdbc.update(
                "INSERT INTO rb_message_index (message_id, topic, segment_id, timestamp_ns, seq) "
                        + "SELECT id, topic, segment_id, timestamp_ns, seq FROM rb_message "
                        + "WHERE topic = ? AND valid = TRUE",
                topic);
    }

    /**
     * 按 seq 升序取出段内全部有效消息（含负载全文），恢复时逐条复验负载校验。
     */
    public List<Message> findValidFullBySegment(long segmentId) {
        return jdbc.query(
                "SELECT id, topic, seq, timestamp_ns, type_id, payload, payload_checksum, "
                        + "batch_no, segment_id, valid FROM rb_message "
                        + "WHERE segment_id = ? AND valid = TRUE ORDER BY seq",
                MESSAGE_MAPPER, segmentId);
    }

    /**
     * 将 Topic 内 seq 大于 keepUpToSeq 的有效消息搬移到隔离表并从主表删除。
     * keepUpToSeq 传 Long.MIN_VALUE 表示隔离该 Topic 的全部有效消息。
     *
     * @return 隔离（删除）的消息条数
     */
    public int quarantineTail(String topic, long keepUpToSeq, long nowNs, String reason) {
        int copied = jdbc.update(
                "INSERT INTO rb_message_quarantine (message_id, topic, seq, timestamp_ns, type_id, "
                        + "payload, payload_checksum, batch_no, segment_id, quarantined_at_ns, reason) "
                        + "SELECT id, topic, seq, timestamp_ns, type_id, payload, payload_checksum, "
                        + "batch_no, segment_id, ?, ? FROM rb_message "
                        + "WHERE topic = ? AND valid = TRUE AND seq > ?",
                nowNs, reason, topic, keepUpToSeq);
        jdbc.update(
                "DELETE FROM rb_message WHERE topic = ? AND valid = TRUE AND seq > ?",
                topic, keepUpToSeq);
        return copied;
    }

    /**
     * 故障注入：破坏指定消息负载并同步清空其记录的负载校验值，模拟 torn write。
     * 校验值置 0 保证其与任何真实负载的 CRC32 都不一致。
     */
    public int corruptPayload(String topic, long seq) {
        return jdbc.update(
                "UPDATE rb_message SET payload = CONCAT(COALESCE(payload, ''), '#CORRUPT#'), "
                        + "payload_checksum = 0 WHERE topic = ? AND seq = ? AND valid = TRUE",
                topic, seq);
    }

    /**
     * 故障注入：删除指定消息的索引行（消息保留），模拟索引损坏。
     */
    public int deleteIndexRow(String topic, long seq) {
        return jdbc.update(
                "DELETE FROM rb_message_index WHERE topic = ? AND seq = ?",
                topic, seq);
    }

    public long countQuarantined(String topic) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rb_message_quarantine WHERE topic = ?",
                Long.class, topic);
        return value == null ? 0L : value;
    }

    /**
     * 基于已定位的候选分段 + 索引表做时间/序列闭区间精过滤，按 (timestamp_ns, seq) 原顺序返回。
     * 不读取候选段之外的任何消息。
     */
    public List<MessageView> queryViaIndex(String topic, List<Long> candidateSegmentIds,
                                           Long fromNs, Long toNs, Long fromSeq, Long toSeq) {
        if (candidateSegmentIds.isEmpty()) {
            return new ArrayList<>();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT " + VIEW_COLUMNS + " FROM rb_message_index i "
                        + "JOIN rb_message m ON m.id = i.message_id "
                        + "WHERE i.topic = ? AND m.valid = TRUE");
        List<Object> args = new ArrayList<>();
        args.add(topic);

        String placeholders = String.join(",", candidateSegmentIds.stream().map(id -> "?").toList());
        sql.append(" AND i.segment_id IN (").append(placeholders).append(")");
        args.addAll(candidateSegmentIds);

        if (fromNs != null) {
            sql.append(" AND i.timestamp_ns >= ?");
            args.add(fromNs);
        }
        if (toNs != null) {
            sql.append(" AND i.timestamp_ns <= ?");
            args.add(toNs);
        }
        if (fromSeq != null) {
            sql.append(" AND i.seq >= ?");
            args.add(fromSeq);
        }
        if (toSeq != null) {
            sql.append(" AND i.seq <= ?");
            args.add(toSeq);
        }
        sql.append(" ORDER BY i.timestamp_ns, i.seq");
        return jdbc.query(sql.toString(), VIEW_MAPPER, args.toArray());
    }
}
