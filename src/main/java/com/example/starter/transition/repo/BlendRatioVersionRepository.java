package com.example.starter.transition.repo;

import com.example.starter.transition.model.BlendRatioVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 回抄浆掺配比例版本仓储：版本链按 (order_no, version_no) 唯一，与改品种单号绑定留档。
 */
@Repository
public class BlendRatioVersionRepository {

    private static final RowMapper<BlendRatioVersion> MAPPER = (rs, n) -> {
        BlendRatioVersion v = new BlendRatioVersion();
        v.setId(rs.getLong("id"));
        v.setOrderNo(rs.getString("order_no"));
        v.setVersionNo(rs.getInt("version_no"));
        v.setTargetRatioPct(rs.getBigDecimal("target_ratio_pct"));
        v.setRatioDeltaPct(rs.getBigDecimal("ratio_delta_pct"));
        v.setAdjustMode(rs.getString("adjust_mode"));
        v.setRequestId(rs.getString("request_id"));
        v.setDecidedAt(rs.getTimestamp("decided_at").toLocalDateTime());
        return v;
    };

    private final JdbcTemplate jdbc;

    public BlendRatioVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入新版本；唯一约束 (order_no, version_no) 冲突时抛 DuplicateKeyException。 */
    public void insert(BlendRatioVersion v) {
        jdbc.update("""
                        INSERT INTO blend_ratio_version
                            (order_no, version_no, target_ratio_pct, ratio_delta_pct,
                             adjust_mode, request_id, decided_at)
                        VALUES (?,?,?,?,?,?,?)
                        """,
                v.getOrderNo(), v.getVersionNo(), v.getTargetRatioPct(), v.getRatioDeltaPct(),
                v.getAdjustMode(), v.getRequestId(), Timestamp.valueOf(v.getDecidedAt()));
    }

    public Optional<BlendRatioVersion> find(String orderNo, int versionNo) {
        List<BlendRatioVersion> list = jdbc.query(
                "SELECT * FROM blend_ratio_version WHERE order_no = ? AND version_no = ?",
                MAPPER, orderNo, versionNo);
        return list.stream().findFirst();
    }

    /** 版本链按版本号升序返回，供按版本复算实际掺配轨迹。 */
    public List<BlendRatioVersion> findByOrderNo(String orderNo) {
        return jdbc.query(
                "SELECT * FROM blend_ratio_version WHERE order_no = ? ORDER BY version_no",
                MAPPER, orderNo);
    }
}
