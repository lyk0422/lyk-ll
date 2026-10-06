package com.example.starter.transition.repo;

import com.example.starter.transition.model.BlendReading;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 实际掺配读数仓储：唯一索引 (order_no, sampled_at) 按订单与采样时刻去重。
 */
@Repository
public class BlendReadingRepository {

    private static final RowMapper<BlendReading> MAPPER = (rs, n) -> {
        BlendReading r = new BlendReading();
        r.setId(rs.getLong("id"));
        r.setOrderNo(rs.getString("order_no"));
        r.setVersionNo(rs.getInt("version_no"));
        r.setSampledAt(rs.getTimestamp("sampled_at").toLocalDateTime());
        r.setActualRatioPct(rs.getBigDecimal("actual_ratio_pct"));
        r.setDeviationPct(rs.getBigDecimal("deviation_pct"));
        r.setOutOfTolerance(rs.getInt("out_of_tolerance") != 0);
        r.setRequestId(rs.getString("request_id"));
        r.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return r;
    };

    private final JdbcTemplate jdbc;

    public BlendReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记读数。
     *
     * @return true 登记成功；false 同一订单同一采样时刻已存在读数（去重冲突）
     */
    public boolean tryInsert(BlendReading reading) {
        try {
            jdbc.update("""
                            INSERT INTO blend_reading
                                (order_no, version_no, sampled_at, actual_ratio_pct,
                                 deviation_pct, out_of_tolerance, request_id, created_at)
                            VALUES (?,?,?,?,?,?,?,?)
                            """,
                    reading.getOrderNo(), reading.getVersionNo(),
                    Timestamp.valueOf(reading.getSampledAt()), reading.getActualRatioPct(),
                    reading.getDeviationPct(), reading.isOutOfTolerance() ? 1 : 0,
                    reading.getRequestId(), Timestamp.valueOf(reading.getCreatedAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 按采样时刻升序的读数轨迹，供复算。 */
    public List<BlendReading> findByOrderNo(String orderNo) {
        return jdbc.query(
                "SELECT * FROM blend_reading WHERE order_no = ? ORDER BY sampled_at, id",
                MAPPER, orderNo);
    }

    /** 供测试核对读数数。 */
    public long countByOrderNo(String orderNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_reading WHERE order_no = ?", Long.class, orderNo);
        return count == null ? 0 : count;
    }
}
