package com.example.starter.fermentation.repository;

import com.example.starter.fermentation.domain.CurvePhase;
import com.example.starter.fermentation.domain.CurveSegment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 工艺曲线数据访问。曲线按版本管理，历史版本只读保留，支持按经过时长复算设定值。
 */
@Repository
public class CurveRepository {

    private static final RowMapper<CurveSegment> SEGMENT_MAPPER = (rs, rowNum) -> new CurveSegment(
            rs.getLong("id"),
            rs.getLong("curve_id"),
            CurvePhase.valueOf(rs.getString("phase")),
            rs.getInt("seq"),
            rs.getBigDecimal("setpoint_celsius"),
            rs.getInt("duration_hours"));

    private final JdbcTemplate jdbc;

    public CurveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询曲线当前生效（ACTIVE）的最新版本号。
     */
    public Optional<Integer> findActiveVersion(String curveCode) {
        return jdbc.query(
                "SELECT version FROM process_curve WHERE curve_code = ? AND status = 'ACTIVE'"
                        + " ORDER BY version DESC LIMIT 1",
                rs -> rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty(), curveCode);
    }

    /**
     * 查询指定版本的分段（按阶段过滤、序号升序）。
     */
    public List<CurveSegment> findSegments(String curveCode, int version, CurvePhase phase) {
        return jdbc.query(
                "SELECT s.* FROM curve_segment s JOIN process_curve c ON s.curve_id = c.id"
                        + " WHERE c.curve_code = ? AND c.version = ? AND s.phase = ? ORDER BY s.seq",
                SEGMENT_MAPPER, curveCode, version, phase.name());
    }

    /**
     * 按阶段经过时长（小时）复算设定值：分段按 seq 依次生效，超出末段后沿用末段设定值。
     *
     * @throws IllegalStateException 曲线缺少该阶段分段
     */
    public BigDecimal setpointAt(String curveCode, int version, CurvePhase phase, long elapsedHours) {
        List<CurveSegment> segments = findSegments(curveCode, version, phase);
        if (segments.isEmpty()) {
            throw new IllegalStateException(
                    "曲线 " + curveCode + " v" + version + " 缺少阶段 " + phase + " 的分段设定");
        }
        long remaining = elapsedHours;
        for (CurveSegment segment : segments) {
            if (remaining < segment.durationHours()) {
                return segment.setpointCelsius();
            }
            remaining -= segment.durationHours();
        }
        return segments.get(segments.size() - 1).setpointCelsius();
    }
}
