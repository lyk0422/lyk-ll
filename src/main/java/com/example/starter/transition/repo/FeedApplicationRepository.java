package com.example.starter.transition.repo;

import com.example.starter.transition.model.FeedApplication;
import com.example.starter.transition.model.FeedConsumption;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 投料申请与消耗明细仓储：单头与明细同事务写入，已消费凭据永久保留。
 */
@Repository
public class FeedApplicationRepository {

    private static final RowMapper<FeedApplication> APP_MAPPER = (rs, n) -> {
        FeedApplication f = new FeedApplication();
        f.setId(rs.getLong("id"));
        f.setFeedNo(rs.getString("feed_no"));
        f.setOrderNo(rs.getString("order_no"));
        f.setBlendVersionNo(rs.getInt("blend_version_no"));
        f.setTotalPulpKg(rs.getBigDecimal("total_pulp_kg"));
        f.setRequiredRecycleKg(rs.getBigDecimal("required_recycle_kg"));
        f.setActualRecycleKg(rs.getBigDecimal("actual_recycle_kg"));
        f.setRequestId(rs.getString("request_id"));
        f.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return f;
    };

    private static final RowMapper<FeedConsumption> CONSUMPTION_MAPPER = (rs, n) -> {
        FeedConsumption c = new FeedConsumption();
        c.setId(rs.getLong("id"));
        c.setFeedNo(rs.getString("feed_no"));
        c.setRollNo(rs.getString("roll_no"));
        c.setKg(rs.getBigDecimal("kg"));
        c.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return c;
    };

    private final JdbcTemplate jdbc;

    public FeedApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertApplication(FeedApplication feed) {
        jdbc.update("""
                        INSERT INTO feed_application
                            (feed_no, order_no, blend_version_no, total_pulp_kg,
                             required_recycle_kg, actual_recycle_kg, request_id, created_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """,
                feed.getFeedNo(), feed.getOrderNo(), feed.getBlendVersionNo(),
                feed.getTotalPulpKg(), feed.getRequiredRecycleKg(), feed.getActualRecycleKg(),
                feed.getRequestId(), Timestamp.valueOf(feed.getCreatedAt()));
    }

    public void insertConsumption(FeedConsumption consumption) {
        jdbc.update("""
                        INSERT INTO feed_consumption (feed_no, roll_no, kg, created_at)
                        VALUES (?,?,?,?)
                        """,
                consumption.getFeedNo(), consumption.getRollNo(), consumption.getKg(),
                Timestamp.valueOf(consumption.getCreatedAt()));
    }

    public Optional<FeedApplication> findApplication(String feedNo) {
        List<FeedApplication> list = jdbc.query(
                "SELECT * FROM feed_application WHERE feed_no = ?", APP_MAPPER, feedNo);
        return list.stream().findFirst();
    }

    public List<FeedConsumption> findConsumptionsByFeedNo(String feedNo) {
        return jdbc.query(
                "SELECT * FROM feed_consumption WHERE feed_no = ? ORDER BY id",
                CONSUMPTION_MAPPER, feedNo);
    }

    /** 按回收卷查询消耗凭据（含投料单号与订单号），供额度勾稽。 */
    public List<ConsumptionWithOrder> findConsumptionsByRollNo(String rollNo) {
        return jdbc.query("""
                        SELECT c.feed_no, f.order_no, c.kg, c.created_at
                        FROM feed_consumption c
                        JOIN feed_application f ON f.feed_no = c.feed_no
                        WHERE c.roll_no = ?
                        ORDER BY c.id
                        """,
                (rs, n) -> new ConsumptionWithOrder(
                        rs.getString("feed_no"), rs.getString("order_no"),
                        rs.getBigDecimal("kg"), rs.getTimestamp("created_at").toLocalDateTime()),
                rollNo);
    }

    /** 消耗凭据携订单号的投影。 */
    public record ConsumptionWithOrder(String feedNo, String orderNo,
                                       java.math.BigDecimal kg, LocalDateTime createdAt) {
    }
}
