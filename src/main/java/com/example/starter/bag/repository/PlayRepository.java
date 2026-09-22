package com.example.starter.bag.repository;

import com.example.starter.bag.repository.MessageRepository.MessageRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 回放记录数据访问。play_key 唯一约束保证重复请求不重复创建记录。
 */
@Repository
public class PlayRepository {

    /** 回放记录行。 */
    public record PlayRow(
            long id,
            String playKey,
            String topic,
            Long startNs,
            Long endNs,
            Long startSeq,
            Long endSeq,
            long messageCount,
            String replayChecksum) {
    }

    private static final RowMapper<PlayRow> MAPPER = (rs, n) -> new PlayRow(
            rs.getLong("id"),
            rs.getString("play_key"),
            rs.getString("topic"),
            rs.getObject("start_ns", Long.class),
            rs.getObject("end_ns", Long.class),
            rs.getObject("start_seq", Long.class),
            rs.getObject("end_seq", Long.class),
            rs.getLong("message_count"),
            rs.getString("replay_checksum"));

    private final NamedParameterJdbcTemplate jdbc;

    public PlayRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试创建回放记录；play_key 冲突时返回 false，由调用方回查既有记录。
     */
    public boolean tryInsert(PlayRow row) {
        try {
            jdbc.update("""
                            INSERT INTO bag_play
                                (play_key, topic, start_ns, end_ns, start_seq, end_seq,
                                 message_count, replay_checksum)
                            VALUES (:playKey, :topic, :startNs, :endNs, :startSeq, :endSeq,
                                    :messageCount, :replayChecksum)
                            """,
                    new MapSqlParameterSource()
                            .addValue("playKey", row.playKey())
                            .addValue("topic", row.topic())
                            .addValue("startNs", row.startNs())
                            .addValue("endNs", row.endNs())
                            .addValue("startSeq", row.startSeq())
                            .addValue("endSeq", row.endSeq())
                            .addValue("messageCount", row.messageCount())
                            .addValue("replayChecksum", row.replayChecksum()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 按幂等键查询既有回放记录，不存在返回 null。 */
    public PlayRow findByKey(String playKey) {
        List<PlayRow> rows = jdbc.query("SELECT * FROM bag_play WHERE play_key = :playKey",
                new MapSqlParameterSource("playKey", playKey), MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 首次回放后按原顺序保存命中消息的完整快照。 */
    public void insertItems(long playId, List<MessageRow> rowsInOrder) {
        if (rowsInOrder.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[rowsInOrder.size()];
        for (int i = 0; i < rowsInOrder.size(); i++) {
            MessageRow row = rowsInOrder.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("playId", playId)
                    .addValue("position", i)
                    .addValue("messageId", row.id())
                    .addValue("topic", row.topic())
                    .addValue("timestampNs", row.timestampNs())
                    .addValue("seq", row.seq())
                    .addValue("typeId", row.typeId())
                    .addValue("payload", row.payload())
                    .addValue("payloadChecksum", row.payloadChecksum())
                    .addValue("batchNo", row.batchNo())
                    .addValue("segmentId", row.segmentId());
        }
        jdbc.batchUpdate("""
                        INSERT INTO bag_play_item
                            (play_id, position, message_id, topic, timestamp_ns, seq, type_id,
                             payload, payload_checksum, batch_no, segment_id)
                        VALUES (:playId, :position, :messageId, :topic, :timestampNs, :seq, :typeId,
                                :payload, :payloadChecksum, :batchNo, :segmentId)
                        """, batch);
    }

    private static final RowMapper<MessageRow> ITEM_MAPPER = (rs, n) -> new MessageRow(
            rs.getLong("message_id"),
            rs.getString("topic"),
            rs.getLong("timestamp_ns"),
            rs.getLong("seq"),
            rs.getString("type_id"),
            rs.getBytes("payload"),
            rs.getString("payload_checksum"),
            null,
            rs.getString("batch_no"),
            rs.getLong("segment_id"),
            "COMMITTED");

    /** 按首次保存的顺序读取回放消息快照。 */
    public List<MessageRow> findItems(long playId) {
        return jdbc.query("""
                        SELECT message_id, topic, timestamp_ns, seq, type_id, payload,
                               payload_checksum, batch_no, segment_id
                        FROM bag_play_item WHERE play_id = :playId ORDER BY position
                        """,
                new MapSqlParameterSource("playId", playId), ITEM_MAPPER);
    }
}
