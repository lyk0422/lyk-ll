package com.example.starter.bag.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 二级索引数据访问。索引可独立删除与重建，是恢复操作的核心对象。
 */
@Repository
public class MessageIndexRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public MessageIndexRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 为一条消息写入索引行。 */
    public void insert(long messageId, String topic, long timestampNs, long seq, long segmentId) {
        jdbc.update("""
                        INSERT INTO bag_msg_index (message_id, topic, timestamp_ns, seq, segment_id)
                        VALUES (:messageId, :topic, :timestampNs, :seq, :segmentId)
                        """,
                new MapSqlParameterSource()
                        .addValue("messageId", messageId)
                        .addValue("topic", topic)
                        .addValue("timestampNs", timestampNs)
                        .addValue("seq", seq)
                        .addValue("segmentId", segmentId));
    }

    /** 故障模拟：删除整段索引行。 */
    public int deleteBySegmentId(long segmentId) {
        return jdbc.update("DELETE FROM bag_msg_index WHERE segment_id = :segmentId",
                new MapSqlParameterSource("segmentId", segmentId));
    }

    /** 恢复：删除段内全部索引行后基于保留的完整前缀重建。 */
    @Transactional
    public int rebuildForSegment(long segmentId) {
        jdbc.update("DELETE FROM bag_msg_index WHERE segment_id = :segmentId",
                new MapSqlParameterSource("segmentId", segmentId));
        return jdbc.update("""
                        INSERT INTO bag_msg_index (message_id, topic, timestamp_ns, seq, segment_id)
                        SELECT id, topic, timestamp_ns, seq, segment_id
                        FROM bag_message
                        WHERE segment_id = :segmentId AND status = 'COMMITTED'
                        ORDER BY seq
                        """,
                new MapSqlParameterSource("segmentId", segmentId));
    }

    /** 统计段内索引行数（恢复校验用）。 */
    public long countBySegmentId(long segmentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bag_msg_index WHERE segment_id = :segmentId",
                new MapSqlParameterSource("segmentId", segmentId), Long.class);
    }

    /** 列出段内缺失索引的已提交消息 id（恢复完整性自检用）。 */
    public List<Long> findCommittedIdsWithoutIndex(long segmentId) {
        return jdbc.queryForList("""
                        SELECT m.id FROM bag_message m
                        LEFT JOIN bag_msg_index i ON i.message_id = m.id
                        WHERE m.segment_id = :segmentId AND m.status = 'COMMITTED' AND i.id IS NULL
                        ORDER BY m.seq
                        """,
                new MapSqlParameterSource("segmentId", segmentId), Long.class);
    }
}
