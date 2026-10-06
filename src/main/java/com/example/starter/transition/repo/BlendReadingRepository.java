package com.example.starter.transition.repo;

import com.example.starter.transition.model.BlendReading;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 实际掺配读数仓储：唯一约束 (order_no, sampled_at) 按订单与时刻去重。
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
        r.setWithinTolerance(rs.getInt("within_tolerance") != 0);
        r.setRequestId(rs.getString("request_id"));
        r.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return r;
    };

    private final JdbcTemplate jdbc;

    public BlendReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入读数；唯一约束 (order_no, sampled_at) 冲突时抛 DuplicateKeyException。 */
    public void insert(BlendReading r) {
        jdbc.update("""
                        INSERT INTO blend_reading
                            (order_no, version_no, sampled_at, actual_ratio_pct,
                             deviation_pct, within_tolerance, request_id, created_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """,
                r.getOrderNo(), r.getVersionNo(), Timestamp.valueOf(r.getSampledAt()),
                r.getActualRatioPct(), r.getDeviationPct(), r.isWithinTolerance() ? 1 : 0,
                r.getRequestId(), Timestamp.valueOf(r.getCreatedAt()));
    }

    /** 指定版本的读数按采样时刻升序返回，供连续超差判定与按版本复算。 */
    public List<BlendReading> findByOrderAndVersion(String orderNo, int versionNo) {
        return jdbc.query(
                "SELECT * FROM blend_reading WHERE order_no = ? AND version_no = ? ORDER BY sampled_at",
                MAPPER, orderNo, versionNo);
    }
}
