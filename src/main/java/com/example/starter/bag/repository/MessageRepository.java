package com.example.starter.bag.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 消息数据访问。范围查询必须由调用方先通过 {@link SegmentRepository#findCandidateSegmentIds}
 * 定位命中分段，再经 bag_msg_index 索引过滤，禁止全表载入后内存过滤。
 */
@Repository
public class MessageRepository {

    /** 消息行（仓储内部使用，含负载字节）。 */
    public record MessageRow(
            long id,
            String topic,
            long timestampNs,
            long seq,
            String typeId,
            byte[] payload,
            String payloadChecksum,
            String chainValue,
            String batchNo,
            long segmentId,
            String status) {
    }

    private static final RowMapper<MessageRow> MAPPER = (rs, n) -> new MessageRow(
            rs.getLong("id"),
            rs.getString("topic"),
            rs.getLong("timestamp_ns"),
            rs.getLong("seq"),
            rs.getString("type_id"),
            rs.getBytes("payload"),
            rs.getString("payload_checksum"),
            rs.getString("chain_value"),
            rs.getString("batch_no"),
            rs.getLong("segment_id"),
            rs.getString("status"));

    private final NamedParameterJdbcTemplate jdbc;

    public MessageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入一条已提交消息，chainValue 为该消息在段内的链式校验锚点。 */
    public void insertCommitted(String topic, long timestampNs, long seq, String typeId,
                                byte[] payload, String payloadChecksum, String chainValue,
                                String batchNo, long segmentId) {
        jdbc.update("""
                        INSERT INTO bag_message
                            (topic, timestamp_ns, seq, type_id, payload, payload_checksum,
                             chain_value, batch_no, segment_id, status)
                        VALUES (:topic, :timestampNs, :seq, :typeId, :payload, :payloadChecksum,
                                :chainValue, :batchNo, :segmentId, 'COMMITTED')
                        """,
                new MapSqlParameterSource()
                        .addValue("topic", topic)
                        .addValue("timestampNs", timestampNs)
                        .addValue("seq", seq)
                        .addValue("typeId", typeId)
                        .addValue("payload", payload)
                        .addValue("payloadChecksum", payloadChecksum)
                        .addValue("chainValue", chainValue)
                        .addValue("batchNo", batchNo)
                        .addValue("segmentId", segmentId));
    }

    /**
     * 故障模拟：直接插入尾消息，不写索引、不写链锚点、不推进段链，模拟写入中断后的不完整数据。
     * 序列号仍遵守 Topic 内唯一约束。
     */
    public long insertIncompleteTail(String topic, long timestampNs, long seq, String typeId,
                                     byte[] payload, String payloadChecksum, String batchNo, long segmentId) {
        insertCommitted(topic, timestampNs, seq, typeId, payload, payloadChecksum,
                null, batchNo, segmentId);
        return jdbc.queryForObject(
                "SELECT id FROM bag_message WHERE topic = :topic AND seq = :seq",
                new MapSqlParameterSource().addValue("topic", topic).addValue("seq", seq),
                Long.class);
    }

    /** 同批次同参幂等查询：按 (batchNo, topic, seq) 定位既有消息，不存在返回 null。 */
    public MessageRow findByBatchKey(String batchNo, String topic, long seq) {
        List<MessageRow> rows = jdbc.query("""
                        SELECT * FROM bag_message
                        WHERE batch_no = :batchNo AND topic = :topic AND seq = :seq
                        """,
                new MapSqlParameterSource()
                        .addValue("batchNo", batchNo)
                        .addValue("topic", topic)
                        .addValue("seq", seq),
                MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Topic 内已提交的最大序列号，无消息返回 null。 */
    public Long findMaxCommittedSeq(String topic) {
        List<Long> values = jdbc.queryForList("""
                        SELECT MAX(seq) FROM bag_message
                        WHERE topic = :topic AND status = 'COMMITTED'
                        """,
                new MapSqlParameterSource("topic", topic), Long.class);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * 经二级索引执行组合范围查询：先限定候选分段，再在索引上按 Topic/纳秒/序列号过滤，
     * 最后回表取负载，结果严格按 Topic 内序列号原序返回。
     */
    public List<MessageRow> queryViaIndex(List<Long> segmentIds, String topic,
                                          Long startNs, Long endNs, Long startSeq, Long endSeq) {
        return jdbc.query("""
                        SELECT m.* FROM bag_msg_index i
                        JOIN bag_message m ON m.id = i.message_id
                        WHERE i.topic = :topic
                          AND m.status = 'COMMITTED'
                          AND i.segment_id IN (:segmentIds)
                          AND (:startNs IS NULL OR i.timestamp_ns >= :startNs)
                          AND (:endNs IS NULL OR i.timestamp_ns <= :endNs)
                          AND (:startSeq IS NULL OR i.seq >= :startSeq)
                          AND (:endSeq IS NULL OR i.seq <= :endSeq)
                        ORDER BY i.seq
                        """,
                new MapSqlParameterSource()
                        .addValue("segmentIds", segmentIds)
                        .addValue("topic", topic)
                        .addValue("startNs", startNs)
                        .addValue("endNs", endNs)
                        .addValue("startSeq", startSeq)
                        .addValue("endSeq", endSeq),
                MAPPER);
    }

    /** 恢复用：加行锁并按序列号原序取出段内全部消息（含待隔离尾消息）。 */
    public List<MessageRow> findAllBySegmentForUpdate(long segmentId) {
        return jdbc.query("""
                        SELECT * FROM bag_message
                        WHERE segment_id = :segmentId
                        ORDER BY seq
                        FOR UPDATE
                        """,
                new MapSqlParameterSource("segmentId", segmentId), MAPPER);
    }

    /**
     * 恢复隔离：在同一事务内把不完整消息原样复制到 bag_quarantine 并从消息表删除，
     * 既保留现场，又释放 Topic 序列号占用，允许后续按原序号重新写入。
     */
    public int quarantineByIds(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", messageIds);
        jdbc.update("""
                        INSERT INTO bag_quarantine
                            (original_message_id, topic, timestamp_ns, seq, type_id, payload,
                             payload_checksum, chain_value, batch_no, segment_id, reason)
                        SELECT id, topic, timestamp_ns, seq, type_id, payload,
                               payload_checksum, chain_value, batch_no, segment_id, 'INCOMPLETE_TAIL'
                        FROM bag_message
                        WHERE id IN (:ids) AND status = 'COMMITTED'
                        """,
                params);
        return jdbc.update("DELETE FROM bag_message WHERE id IN (:ids)", params);
    }
}
