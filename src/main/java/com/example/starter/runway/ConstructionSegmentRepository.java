package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 施工段数据访问：construction_segment 表。
 */
@Repository
public class ConstructionSegmentRepository {

    private static final RowMapper<ConstructionSegment> MAPPER = (rs, rowNum) -> new ConstructionSegment(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getInt("segment_no"),
            rs.getInt("start_meter"),
            rs.getInt("end_meter"),
            ConstructionSegmentStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("cleared_at") == null ? null : rs.getTimestamp("cleared_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public ConstructionSegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入施工段。 */
    public ConstructionSegment insert(ConstructionSegment segment) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO construction_segment (order_id, segment_no, start_meter, end_meter, status,"
                            + " cleared_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, segment.orderId());
            ps.setInt(2, segment.segmentNo());
            ps.setInt(3, segment.startMeter());
            ps.setInt(4, segment.endMeter());
            ps.setString(5, segment.status().name());
            ps.setTimestamp(6, segment.clearedAt() == null ? null : Timestamp.from(segment.clearedAt()));
            ps.setTimestamp(7, Timestamp.from(segment.createdAt()));
            ps.setTimestamp(8, Timestamp.from(segment.updatedAt()));
            return ps;
        }, keyHolder);
        long id = Optional.ofNullable(keyHolder.getKey()).orElseThrow().longValue();
        return findById(id).orElseThrow();
    }

    public Optional<ConstructionSegment> findById(long id) {
        List<ConstructionSegment> rows = jdbc.query("SELECT * FROM construction_segment WHERE id = ?",
                MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 按施工单列出全部段，按段号排序。 */
    public List<ConstructionSegment> findByOrderId(long orderId) {
        return jdbc.query("SELECT * FROM construction_segment WHERE order_id = ? ORDER BY segment_no",
                MAPPER, orderId);
    }

    /** 行级锁定取施工单内指定段号的段，供验收串行化。 */
    public Optional<ConstructionSegment> findByOrderIdAndSegmentNoForUpdate(long orderId, int segmentNo) {
        List<ConstructionSegment> rows = jdbc.query(
                "SELECT * FROM construction_segment WHERE order_id = ? AND segment_no = ? FOR UPDATE",
                MAPPER, orderId, segmentNo);
        return rows.stream().findFirst();
    }

    /** 删除施工单全部段（未生效单修改时整体替换）。 */
    public void deleteByOrderId(long orderId) {
        jdbc.update("DELETE FROM construction_segment WHERE order_id = ?", orderId);
    }

    /** 施工单生效：全部段由 PENDING 转 LOCKED。 */
    public void lockByOrderId(long orderId, Instant updatedAt) {
        jdbc.update("UPDATE construction_segment SET status = 'LOCKED', updated_at = ?"
                        + " WHERE order_id = ? AND status = 'PENDING'",
                Timestamp.from(updatedAt), orderId);
    }

    /** 验收完成：段转 CLEARED 并记录解除时刻。 */
    public void clearSegment(long segmentId, Instant clearedAt, Instant updatedAt) {
        jdbc.update("UPDATE construction_segment SET status = 'CLEARED', cleared_at = ?, updated_at = ?"
                        + " WHERE id = ?",
                Timestamp.from(clearedAt), Timestamp.from(updatedAt), segmentId);
    }

    /** 该跑道活动闭锁区间（已生效单的 LOCKED 段），供可用区间并集计算。 */
    public List<ConstructionSegment> findLockedByRunway(String runwayId) {
        return jdbc.query("SELECT s.* FROM construction_segment s"
                        + " JOIN construction_order o ON o.id = s.order_id"
                        + " WHERE o.runway_id = ? AND o.status = 'EFFECTIVE' AND s.status = 'LOCKED'"
                        + " ORDER BY s.start_meter, s.id",
                MAPPER, runwayId);
    }

    /** 该跑道活动闭锁段数量：>0 表示存在活动施工闭锁。 */
    public int countLockedByRunway(String runwayId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM construction_segment s"
                        + " JOIN construction_order o ON o.id = s.order_id"
                        + " WHERE o.runway_id = ? AND o.status = 'EFFECTIVE' AND s.status = 'LOCKED'",
                Integer.class, runwayId);
        return count == null ? 0 : count;
    }
}
