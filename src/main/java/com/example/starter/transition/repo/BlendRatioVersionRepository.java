package com.example.starter.transition.repo;

import com.example.starter.transition.model.BlendRatioVersion;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 回抄浆掺配比例版本仓储：版本号单内自增；
 * 唯一索引 (order_no, source, created_at) 保证同一订单同时刻只允许一次执行中调整。
 */
@Repository
public class BlendRatioVersionRepository {

    private static final RowMapper<BlendRatioVersion> MAPPER = (rs, n) -> {
        BlendRatioVersion v = new BlendRatioVersion();
        v.setId(rs.getLong("id"));
        v.setOrderNo(rs.getString("order_no"));
        v.setVersionNo(rs.getInt("version_no"));
        v.setTargetRatioPct(rs.getBigDecimal("target_ratio_pct"));
        v.setSource(rs.getString("source"));
        v.setRequestId(rs.getString("request_id"));
        v.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return v;
    };

    private final JdbcTemplate jdbc;

    public BlendRatioVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记新版本。
     *
     * @return true 登记成功；false 同一订单同一来源同时刻已存在版本（唯一索引冲突）
     */
    public boolean tryInsert(BlendRatioVersion version) {
        try {
            jdbc.update("""
                            INSERT INTO blend_ratio_version
                                (order_no, version_no, target_ratio_pct, source, request_id, created_at)
                            VALUES (?,?,?,?,?,?)
                            """,
                    version.getOrderNo(), version.getVersionNo(), version.getTargetRatioPct(),
                    version.getSource(), version.getRequestId(), Timestamp.valueOf(version.getCreatedAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 当前最新版本。 */
    public Optional<BlendRatioVersion> findLatest(String orderNo) {
        List<BlendRatioVersion> list = jdbc.query(
                "SELECT * FROM blend_ratio_version WHERE order_no = ? ORDER BY version_no DESC LIMIT 1",
                MAPPER, orderNo);
        return list.stream().findFirst();
    }

    /** 版本链（按版本号升序），供复算。 */
    public List<BlendRatioVersion> findByOrderNo(String orderNo) {
        return jdbc.query(
                "SELECT * FROM blend_ratio_version WHERE order_no = ? ORDER BY version_no",
                MAPPER, orderNo);
    }

    /** 下一版本号 = 当前最大版本号 + 1，无版本时为 1。 */
    public int nextVersionNo(String orderNo) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM blend_ratio_version WHERE order_no = ?",
                Integer.class, orderNo);
        return (max == null ? 0 : max) + 1;
    }

    /** 供测试核对版本数。 */
    public long countByOrderNo(String orderNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version WHERE order_no = ?", Long.class, orderNo);
        return count == null ? 0 : count;
    }

    /** 供测试核对指定时刻的版本登记。 */
    public long countAdjustAt(String orderNo, LocalDateTime createdAt) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version WHERE order_no = ? AND source = 'ADJUST' AND created_at = ?",
                Long.class, orderNo, Timestamp.valueOf(createdAt));
        return count == null ? 0 : count;
    }
}
