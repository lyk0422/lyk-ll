package com.example.starter.flood.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 滚动调洪演算版本仓储；(warning_id, version_no) 唯一约束兜底并发版本分配。
 */
@Repository
public class RollingRoutingRepository {

    private final JdbcTemplate jdbcTemplate;

    public RollingRoutingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static final RowMapper<RollingRoutingPO> MAPPER = new RowMapper<>() {
        @Override
        public RollingRoutingPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            RollingRoutingPO po = new RollingRoutingPO();
            po.id = rs.getLong("id");
            po.rollingNo = rs.getString("rolling_no");
            po.warningId = rs.getLong("warning_id");
            po.pondId = rs.getLong("pond_id");
            po.versionNo = rs.getInt("version_no");
            po.requestId = rs.getString("request_id");
            long previousId = rs.getLong("previous_id");
            po.previousId = rs.wasNull() ? null : previousId;
            po.scheduledTime = rs.getTimestamp("scheduled_time").toLocalDateTime();
            po.currentWaterLevel = rs.getBigDecimal("current_water_level");
            po.currentStorage = rs.getBigDecimal("current_storage");
            po.maxStorage = rs.getBigDecimal("max_storage");
            po.availableCapacity = rs.getBigDecimal("available_capacity");
            po.inflowRate = rs.getBigDecimal("inflow_rate");
            po.periodInflowVolume = rs.getBigDecimal("period_inflow_volume");
            po.releaseCapacity = rs.getBigDecimal("release_capacity");
            po.requiredRelease = rs.getBigDecimal("required_release");
            po.projectedStorage = rs.getBigDecimal("projected_storage");
            po.releaseJudgment = rs.getString("release_judgment");
            po.dryBeachLength = rs.getBigDecimal("dry_beach_length");
            po.phreaticDepth = rs.getBigDecimal("phreatic_depth");
            po.dryBeachJudgment = rs.getString("dry_beach_judgment");
            po.phreaticJudgment = rs.getString("phreatic_judgment");
            po.dangerJudgment = rs.getString("danger_judgment");
            po.warningLevelAfter = rs.getString("warning_level_after");
            int planVersionAfter = rs.getInt("plan_version_after");
            po.planVersionAfter = rs.wasNull() ? null : planVersionAfter;
            po.inputSnapshot = rs.getString("input_snapshot");
            po.criterion = rs.getString("criterion");
            Timestamp created = rs.getTimestamp("created_at");
            po.createdAt = created == null ? null : created.toLocalDateTime();
            return po;
        }
    };

    public void insert(RollingRoutingPO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rolling_routing(rolling_no, warning_id, pond_id, version_no, "
                            + "request_id, previous_id, scheduled_time, current_water_level, "
                            + "current_storage, max_storage, available_capacity, inflow_rate, "
                            + "period_inflow_volume, release_capacity, required_release, "
                            + "projected_storage, release_judgment, dry_beach_length, "
                            + "phreatic_depth, dry_beach_judgment, phreatic_judgment, "
                            + "danger_judgment, warning_level_after, plan_version_after, "
                            + "input_snapshot, criterion, created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.rollingNo);
            ps.setLong(2, po.warningId);
            ps.setLong(3, po.pondId);
            ps.setInt(4, po.versionNo);
            ps.setString(5, po.requestId);
            ps.setObject(6, po.previousId);
            ps.setTimestamp(7, Timestamp.valueOf(po.scheduledTime));
            ps.setBigDecimal(8, po.currentWaterLevel);
            ps.setBigDecimal(9, po.currentStorage);
            ps.setBigDecimal(10, po.maxStorage);
            ps.setBigDecimal(11, po.availableCapacity);
            ps.setBigDecimal(12, po.inflowRate);
            ps.setBigDecimal(13, po.periodInflowVolume);
            ps.setBigDecimal(14, po.releaseCapacity);
            ps.setBigDecimal(15, po.requiredRelease);
            ps.setBigDecimal(16, po.projectedStorage);
            ps.setString(17, po.releaseJudgment);
            ps.setBigDecimal(18, po.dryBeachLength);
            ps.setBigDecimal(19, po.phreaticDepth);
            ps.setString(20, po.dryBeachJudgment);
            ps.setString(21, po.phreaticJudgment);
            ps.setString(22, po.dangerJudgment);
            ps.setString(23, po.warningLevelAfter);
            ps.setObject(24, po.planVersionAfter);
            ps.setString(25, po.inputSnapshot);
            ps.setString(26, po.criterion);
            ps.setTimestamp(27, Timestamp.valueOf(po.createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            po.id = key.longValue();
        }
    }

    public RollingRoutingPO findByNo(String rollingNo) {
        List<RollingRoutingPO> list = jdbcTemplate.query(
                "SELECT * FROM rolling_routing WHERE rolling_no = ?", MAPPER, rollingNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public RollingRoutingPO findById(Long id) {
        List<RollingRoutingPO> list = jdbcTemplate.query(
                "SELECT * FROM rolling_routing WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public RollingRoutingPO findByRequestId(String requestId) {
        List<RollingRoutingPO> list = jdbcTemplate.query(
                "SELECT * FROM rolling_routing WHERE request_id = ?", MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 处置链全部滚动演算版本，按版本号升序（旧版本保留可查）。
     */
    public List<RollingRoutingPO> findVersionsByWarningId(Long warningId) {
        return jdbcTemplate.query(
                "SELECT * FROM rolling_routing WHERE warning_id = ? ORDER BY version_no",
                MAPPER, warningId);
    }

    /**
     * 处置链最新滚动演算版本；无版本时返回 null。
     */
    public RollingRoutingPO findLatestByWarningId(Long warningId) {
        List<RollingRoutingPO> list = jdbcTemplate.query(
                "SELECT * FROM rolling_routing WHERE warning_id = ? "
                        + "ORDER BY version_no DESC FETCH FIRST 1 ROWS ONLY",
                MAPPER, warningId);
        return list.isEmpty() ? null : list.get(0);
    }

    public RollingRoutingPO findByWarningIdAndVersion(Long warningId, int versionNo) {
        List<RollingRoutingPO> list = jdbcTemplate.query(
                "SELECT * FROM rolling_routing WHERE warning_id = ? AND version_no = ?",
                MAPPER, warningId, versionNo);
        return list.isEmpty() ? null : list.get(0);
    }
}
