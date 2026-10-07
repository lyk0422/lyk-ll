package com.example.starter.transition.repo;

import com.example.starter.transition.model.FeedBatch;
import com.example.starter.transition.model.FeedBatchItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 投料申请单与明细仓储：批号唯一，批内卷号唯一（不同回收卷）。
 */
@Repository
public class FeedBatchRepository {

    private static final RowMapper<FeedBatch> BATCH_MAPPER = (rs, n) -> {
        FeedBatch b = new FeedBatch();
        b.setId(rs.getLong("id"));
        b.setFeedNo(rs.getString("feed_no"));
        b.setOrderNo(rs.getString("order_no"));
        b.setVersionNo(rs.getInt("version_no"));
        b.setTotalPulpKg(rs.getBigDecimal("total_pulp_kg"));
        b.setTargetRatioPct(rs.getBigDecimal("target_ratio_pct"));
        b.setRequiredKg(rs.getBigDecimal("required_kg"));
        b.setActualKg(rs.getBigDecimal("actual_kg"));
        b.setRollCount(rs.getInt("roll_count"));
        b.setRequestId(rs.getString("request_id"));
        b.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return b;
    };

    private static final RowMapper<FeedBatchItem> ITEM_MAPPER = (rs, n) -> {
        FeedBatchItem item = new FeedBatchItem();
        item.setId(rs.getLong("id"));
        item.setFeedNo(rs.getString("feed_no"));
        item.setRollNo(rs.getString("roll_no"));
        item.setWeightKg(rs.getBigDecimal("weight_kg"));
        return item;
    };

    private final JdbcTemplate jdbc;

    public FeedBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertBatch(FeedBatch batch, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO feed_batch
                            (feed_no, order_no, version_no, total_pulp_kg, target_ratio_pct,
                             required_kg, actual_kg, roll_count, request_id, created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """,
                batch.getFeedNo(), batch.getOrderNo(), batch.getVersionNo(),
                batch.getTotalPulpKg(), batch.getTargetRatioPct(),
                batch.getRequiredKg(), batch.getActualKg(), batch.getRollCount(),
                batch.getRequestId(), Timestamp.valueOf(now));
    }

    public void insertItem(FeedBatchItem item) {
        jdbc.update("""
                        INSERT INTO feed_batch_item (feed_no, roll_no, weight_kg)
                        VALUES (?,?,?)
                        """,
                item.getFeedNo(), item.getRollNo(), item.getWeightKg());
    }

    public Optional<FeedBatch> findByFeedNo(String feedNo) {
        List<FeedBatch> list = jdbc.query(
                "SELECT * FROM feed_batch WHERE feed_no = ?", BATCH_MAPPER, feedNo);
        return list.stream().findFirst();
    }

    public List<FeedBatchItem> findItemsByFeedNo(String feedNo) {
        return jdbc.query(
                "SELECT * FROM feed_batch_item WHERE feed_no = ? ORDER BY id",
                ITEM_MAPPER, feedNo);
    }

    /** 某卷的全部消费凭据（勾稽用；卷改判后仍保留可查）。 */
    public List<FeedBatchItem> findItemsByRollNo(String rollNo) {
        return jdbc.query(
                "SELECT * FROM feed_batch_item WHERE roll_no = ? ORDER BY id",
                ITEM_MAPPER, rollNo);
    }

    /** 某卷的消费凭据（关联批次取订单号与投料时间），供额度勾稽查询。 */
    public List<ConsumptionRecord> findConsumptionsByRollNo(String rollNo) {
        return jdbc.query("""
                        SELECT i.feed_no, b.order_no, i.weight_kg, b.created_at
                        FROM feed_batch_item i JOIN feed_batch b ON b.feed_no = i.feed_no
                        WHERE i.roll_no = ? ORDER BY i.id
                        """,
                (rs, n) -> new ConsumptionRecord(rs.getString("feed_no"), rs.getString("order_no"),
                        rs.getBigDecimal("weight_kg"), rs.getTimestamp("created_at").toLocalDateTime()),
                rollNo);
    }

    /**
     * 消费凭据投影：投料批号、订单号、公斤数与投料时间（UTC）。
     */
    public record ConsumptionRecord(String feedNo, String orderNo,
                                    java.math.BigDecimal weightKg,
                                    LocalDateTime createdAt) {
    }

    public List<FeedBatch> findByOrderNo(String orderNo) {
        return jdbc.query(
                "SELECT * FROM feed_batch WHERE order_no = ? ORDER BY id",
                BATCH_MAPPER, orderNo);
    }
}
