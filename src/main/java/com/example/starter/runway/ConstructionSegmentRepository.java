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
import java.util.Objects;
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
            rs.getInt("start_m"),
            rs.getInt("end_m"),
            ConstructionSegmentStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public ConstructionSegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入施工段，初始状态 LOCKED。 */
    public ConstructionSegment insert(ConstructionSegment segment) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO construction_segment (order_id, segment_no, start_m, end_m, status,"
                            + " completed_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, segment.orderId());
            ps.setInt(2, segment.segmentNo());
            ps.setInt(3, segment.startMeters());
            ps.setInt(4, segment.endMeters());
            ps.setString(5, segment.status().name());
            ps.setTimestamp(6, segment.completedAt() == null ? null : Timestamp.from(segment.completedAt()));
            ps.setTimestamp(7, Timestamp.from(segment.createdAt()));
            ps.setTimestamp(8, Timestamp.from(segment.updatedAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
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

    /** 行级锁定取施工单的指定段号，供验收提交串行化。 */
    public Optional<ConstructionSegment> findByOrderIdAndSegmentNoForUpdate(long orderId, int segmentNo) {
        List<ConstructionSegment> rows = jdbc.query(
                "SELECT * FROM construction_segment WHERE order_id = ? AND segment_no = ? FOR UPDATE",
                MAPPER, orderId, segmentNo);
        return rows.stream().findFirst();
    }

    /** 删除施工单全部段（仅未生效单据修改时调用）。 */
    public void deleteByOrderId(long orderId) {
        jdbc.update("DELETE FROM construction_segment WHERE order_id = ?", orderId);
    }

    /** 标记段验收完成。 */
    public void markCompleted(long segmentId, Instant completedAt, Instant updatedAt) {
        jdbc.update("UPDATE construction_segment SET status = 'COMPLETED', completed_at = ?, updated_at = ?"
                        + " WHERE id = ?",
                Timestamp.from(completedAt), Timestamp.from(updatedAt), segmentId);
    }

    /**
     * 查询某跑道当前处于闭锁中的段（所属施工单 ACTIVE 且段 LOCKED），供可用区间并集计算与闭锁校验。
     */
    public List<ConstructionSegment> findLockedByRunway(String runwayId) {
        return jdbc.query("SELECT s.* FROM construction_segment s"
                        + " JOIN construction_order o ON o.id = s.order_id"
                        + " WHERE o.runway_id = ? AND o.status = 'ACTIVE' AND s.status = 'LOCKED'"
                        + " ORDER BY s.start_m, s.id",
                MAPPER, runwayId);
    }

    /** 查询某跑道是否存在闭锁中的段（只读）。 */
    public boolean existsLockedByRunway(String runwayId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM construction_segment s"
                        + " JOIN construction_order o ON o.id = s.order_id"
                        + " WHERE o.runway_id = ? AND o.status = 'ACTIVE' AND s.status = 'LOCKED'",
                Integer.class, runwayId);
        return count != null && count > 0;
    }
}
