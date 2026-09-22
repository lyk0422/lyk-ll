package com.example.starter.bag.repository;

import com.example.starter.bag.model.SegmentStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 逻辑分段数据访问：分段即最粗粒度索引，保存 Topic 内纳秒/序列号边界与段级链式校验。
 */
@Repository
public class SegmentRepository {

    /** 分段行（仓储内部使用）。 */
    public record SegmentRow(
            long id,
            String topic,
            int segIndex,
            Long startNs,
            Long endNs,
            Long startSeq,
            Long endSeq,
            long messageCount,
            int maxSize,
            SegmentStatus status,
            String checksum) {
    }

    private static final RowMapper<SegmentRow> MAPPER = (rs, n) -> new SegmentRow(
            rs.getLong("id"),
            rs.getString("topic"),
            rs.getInt("seg_index"),
            rs.getObject("start_ns", Long.class),
            rs.getObject("end_ns", Long.class),
            rs.getObject("start_seq", Long.class),
            rs.getObject("end_seq", Long.class),
            rs.getLong("message_count"),
            rs.getInt("max_size"),
            SegmentStatus.valueOf(rs.getString("status")),
            rs.getString("checksum"));

    private final NamedParameterJdbcTemplate jdbc;

    public SegmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入新分段，seg_index 由调用方按 Topic 内 MAX+1 计算。 */
    public long insert(String topic, int segIndex, int maxSize) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("topic", topic)
                .addValue("segIndex", segIndex)
                .addValue("maxSize", maxSize);
        jdbc.update("""
                INSERT INTO bag_segment (topic, seg_index, max_size, status, message_count)
                VALUES (:topic, :segIndex, :maxSize, 'OPEN', 0)
                """, params);
        return jdbc.queryForObject(
                "SELECT id FROM bag_segment WHERE topic = :topic AND seg_index = :segIndex",
                params, Long.class);
    }

    /** 悲观锁定位 Topic 内最新分段；无分段返回 null（锁定该 Topic 全部分段行后取首行）。 */
    public SegmentRow findLatestForUpdate(String topic) {
        List<SegmentRow> rows = jdbc.query("""
                        SELECT * FROM bag_segment
                        WHERE topic = :topic
                        ORDER BY seg_index DESC
                        FOR UPDATE
                        """,
                new MapSqlParameterSource("topic", topic), MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按主键查询（不加锁）。 */
    public SegmentRow findById(long id) {
        List<SegmentRow> rows = jdbc.query("SELECT * FROM bag_segment WHERE id = :id",
                new MapSqlParameterSource("id", id), MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按主键加行锁查询。 */
    public SegmentRow findByIdForUpdate(long id) {
        return jdbc.queryForObject("SELECT * FROM bag_segment WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), MAPPER);
    }

    /** Topic 内下一个分段序号（当前最大值 +1，无分段时为 0）。 */
    public int nextSegIndex(String topic) {
        return maxSegIndex(topic) + 1;
    }

    /** Topic 内当前最大分段序号，无分段时返回 -1。 */
    public int maxSegIndex(String topic) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seg_index), -1) FROM bag_segment WHERE topic = :topic",
                new MapSqlParameterSource("topic", topic), Integer.class);
        return max == null ? -1 : max;
    }

    /** Topic 是否存在待恢复（DAMAGED）分段。 */
    public boolean existsDamaged(String topic) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bag_segment WHERE topic = :topic AND status = 'DAMAGED'",
                new MapSqlParameterSource("topic", topic), Long.class);
        return count != null && count > 0;
    }

    /** 追加首条消息后的边界初始化。 */
    public void initBoundaries(long id, long timestampNs, long seq, String checksum) {
        jdbc.update("""
                        UPDATE bag_segment
                        SET start_ns = :ts, end_ns = :ts,
                            start_seq = :seq, end_seq = :seq,
                            message_count = 1, checksum = :checksum, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("ts", timestampNs)
                        .addValue("seq", seq)
                        .addValue("checksum", checksum));
    }

    /** 追加后续消息时按真实最小/最大值推进纳秒边界（时间戳不要求随序列号单调），序列号单调追加。 */
    public void extendBoundaries(long id, long timestampNs, long seq, long messageCount, String checksum) {
        jdbc.update("""
                        UPDATE bag_segment
                        SET start_ns = LEAST(start_ns, :ts), end_ns = GREATEST(end_ns, :ts),
                            start_seq = LEAST(start_seq, :seq), end_seq = GREATEST(end_seq, :seq),
                            message_count = :count, checksum = :checksum,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("ts", timestampNs)
                        .addValue("seq", seq)
                        .addValue("count", messageCount)
                        .addValue("checksum", checksum));
    }

    /** 封段或恢复时更新状态。 */
    public void updateStatus(long id, SegmentStatus status) {
        jdbc.update("UPDATE bag_segment SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status.name()));
    }

    /** 恢复后按完整前缀重写边界、计数、校验与状态。 */
    public void rewriteAfterRecovery(long id, Long startNs, Long endNs, Long startSeq, Long endSeq,
                                     long messageCount, String checksum, SegmentStatus status) {
        jdbc.update("""
                        UPDATE bag_segment
                        SET start_ns = :startNs, end_ns = :endNs,
                            start_seq = :startSeq, end_seq = :endSeq,
                            message_count = :count, checksum = :checksum,
                            status = :status, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("startNs", startNs)
                        .addValue("endNs", endNs)
                        .addValue("startSeq", startSeq)
                        .addValue("endSeq", endSeq)
                        .addValue("count", messageCount)
                        .addValue("checksum", checksum)
                        .addValue("status", status.name()));
    }

    /** 列出 Topic 全部分段（只读视图）。 */
    public List<SegmentRow> findByTopic(String topic) {
        return jdbc.query("SELECT * FROM bag_segment WHERE topic = :topic ORDER BY seg_index",
                new MapSqlParameterSource("topic", topic), MAPPER);
    }

    /** 按 (topic, segIndex) 定位单个分段，不存在返回 null。 */
    public SegmentRow findByTopicAndIndex(String topic, int segIndex) {
        List<SegmentRow> rows = jdbc.query(
                "SELECT * FROM bag_segment WHERE topic = :topic AND seg_index = :segIndex",
                new MapSqlParameterSource().addValue("topic", topic).addValue("segIndex", segIndex),
                MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 锁定全部待恢复（DAMAGED）分段，按 Topic 与序号排序。 */
    public List<SegmentRow> findDamagedForUpdate() {
        return jdbc.query("SELECT * FROM bag_segment WHERE status = 'DAMAGED' ORDER BY topic, seg_index FOR UPDATE",
                new MapSqlParameterSource(), MAPPER);
    }

    /**
     * 分段级索引裁剪：仅返回纳秒与序列号边界与查询范围相交的分段 id。
     * 空边界（无消息分段）不命中任何范围查询。
     */
    public List<Long> findCandidateSegmentIds(String topic, Long startNs, Long endNs,
                                              Long startSeq, Long endSeq) {
        return jdbc.queryForList("""
                        SELECT id FROM bag_segment
                        WHERE topic = :topic
                          AND status <> 'DAMAGED'
                          AND start_ns IS NOT NULL
                          AND (:startNs IS NULL OR end_ns >= :startNs)
                          AND (:endNs IS NULL OR start_ns <= :endNs)
                          AND (:startSeq IS NULL OR end_seq >= :startSeq)
                          AND (:endSeq IS NULL OR start_seq <= :endSeq)
                        ORDER BY seg_index
                        """,
                new MapSqlParameterSource()
                        .addValue("topic", topic)
                        .addValue("startNs", startNs)
                        .addValue("endNs", endNs)
                        .addValue("startSeq", startSeq)
                        .addValue("endSeq", endSeq),
                Long.class);
    }
}
