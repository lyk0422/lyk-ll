package com.example.starter.rosbag.repository;

import com.example.starter.rosbag.model.Segment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 逻辑分段数据访问。分段按 Topic 独立编号，维护边界、消息数、状态与段级校验。
 */
@Repository
public class SegmentRepository {

    private static final RowMapper<Segment> MAPPER = (rs, n) -> new Segment(
            rs.getLong("id"),
            rs.getString("topic"),
            rs.getLong("segment_no"),
            rs.getObject("start_ns", Long.class),
            rs.getObject("end_ns", Long.class),
            rs.getObject("start_seq", Long.class),
            rs.getObject("end_seq", Long.class),
            rs.getLong("message_count"),
            rs.getString("status"),
            rs.getLong("segment_checksum")
    );

    private final JdbcTemplate jdbc;

    public SegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按分段序号列出某 Topic 的全部分段（恢复时按序校验）。
     */
    public List<Segment> findByTopic(String topic) {
        return jdbc.query(
                "SELECT id, topic, segment_no, start_ns, end_ns, start_seq, end_seq, "
                        + "message_count, status, segment_checksum "
                        + "FROM rb_segment WHERE topic = ? ORDER BY segment_no",
                MAPPER, topic);
    }

    /**
     * 悲观锁定列出某 Topic 的全部分段，恢复事务内使用。
     */
    public List<Segment> findByTopicForUpdate(String topic) {
        return jdbc.query(
                "SELECT id, topic, segment_no, start_ns, end_ns, start_seq, end_seq, "
                        + "message_count, status, segment_checksum "
                        + "FROM rb_segment WHERE topic = ? ORDER BY segment_no FOR UPDATE",
                MAPPER, topic);
    }

    /**
     * 取某 Topic 当前可写分段并加行锁；不存在返回 null。
     */
    public Segment findOpenForUpdate(String topic) {
        List<Segment> list = jdbc.query(
                "SELECT id, topic, segment_no, start_ns, end_ns, start_seq, end_seq, "
                        + "message_count, status, segment_checksum "
                        + "FROM rb_segment WHERE topic = ? AND status = 'OPEN' ORDER BY segment_no DESC LIMIT 1 FOR UPDATE",
                MAPPER, topic);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 悲观锁定按主键取分段。
     */
    public Segment findByIdForUpdate(long segmentId) {
        List<Segment> list = jdbc.query(
                "SELECT id, topic, segment_no, start_ns, end_ns, start_seq, end_seq, "
                        + "message_count, status, segment_checksum "
                        + "FROM rb_segment WHERE id = ? FOR UPDATE",
                MAPPER, segmentId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 当前最大分段号；无分段返回 -1。
     */
    public long maxSegmentNo(String topic) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(segment_no), -1) FROM rb_segment WHERE topic = ?",
                Long.class, topic);
        return value == null ? -1L : value;
    }

    /**
     * 创建新的空 OPEN 分段。
     */
    public Segment createOpen(String topic, long segmentNo) {
        jdbc.update(
                "INSERT INTO rb_segment (topic, segment_no, message_count, status, segment_checksum) "
                        + "VALUES (?, ?, 0, 'OPEN', 0)",
                topic, segmentNo);
        Segment created = jdbc.queryForObject(
                "SELECT id, topic, segment_no, start_ns, end_ns, start_seq, end_seq, "
                        + "message_count, status, segment_checksum "
                        + "FROM rb_segment WHERE topic = ? AND segment_no = ?",
                MAPPER, topic, segmentNo);
        return created;
    }

    /**
     * 更新段的聚合边界、消息数与段级校验（状态不变）。
     */
    public void updateAggregates(long segmentId, Long startNs, Long endNs, Long startSeq, Long endSeq,
                                 long messageCount, long checksum) {
        jdbc.update(
                "UPDATE rb_segment SET start_ns = ?, end_ns = ?, start_seq = ?, end_seq = ?, "
                        + "message_count = ?, segment_checksum = ? WHERE id = ?",
                startNs, endNs, startSeq, endSeq, messageCount, checksum, segmentId);
    }

    /**
     * 更新段聚合信息并同时设置状态（恢复与封段使用）。
     */
    public void updateAggregatesAndStatus(long segmentId, Long startNs, Long endNs, Long startSeq, Long endSeq,
                                          long messageCount, long checksum, String status) {
        jdbc.update(
                "UPDATE rb_segment SET start_ns = ?, end_ns = ?, start_seq = ?, end_seq = ?, "
                        + "message_count = ?, segment_checksum = ?, status = ? WHERE id = ?",
                startNs, endNs, startSeq, endSeq, messageCount, checksum, status, segmentId);
    }

    /**
     * 依据边界粗筛候选分段：时间区间与序列区间均相交即命中；空段（边界为 null）不命中。
     * 查询先走该分段定位，再在索引表按候选段过滤，不读取全部消息。
     *
     * @param fromNs/toNs 纳秒闭区间，null 不限制
     * @param fromSeq/toSeq 序列号闭区间，null 不限制
     */
    public List<Segment> findCandidateSegments(String topic, Long fromNs, Long toNs,
                                               Long fromSeq, Long toSeq) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, topic, segment_no, start_ns, end_ns, start_seq, end_seq, "
                        + "message_count, status, segment_checksum FROM rb_segment WHERE topic = ? "
                        + "AND start_ns IS NOT NULL");
        List<Object> args = new ArrayList<>();
        args.add(topic);
        if (fromNs != null) {
            sql.append(" AND end_ns >= ?");
            args.add(fromNs);
        }
        if (toNs != null) {
            sql.append(" AND start_ns <= ?");
            args.add(toNs);
        }
        if (fromSeq != null) {
            sql.append(" AND end_seq >= ?");
            args.add(fromSeq);
        }
        if (toSeq != null) {
            sql.append(" AND start_seq <= ?");
            args.add(toSeq);
        }
        sql.append(" ORDER BY start_ns, segment_no");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
}
