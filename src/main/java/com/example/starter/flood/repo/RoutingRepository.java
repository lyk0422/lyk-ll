package com.example.starter.flood.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 调洪演算记录仓储。
 */
@Repository
public class RoutingRepository {

    private final JdbcTemplate jdbcTemplate;

    public RoutingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static final RowMapper<RoutingPO> MAPPER = new RowMapper<>() {
        @Override
        public RoutingPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            RoutingPO po = new RoutingPO();
            po.id = rs.getLong("id");
            po.routingNo = rs.getString("routing_no");
            po.pondId = rs.getLong("pond_id");
            long warningId = rs.getLong("warning_id");
            po.warningId = rs.wasNull() ? null : warningId;
            po.requestId = rs.getString("request_id");
            po.currentWaterLevel = rs.getBigDecimal("current_water_level");
            po.currentStorage = rs.getBigDecimal("current_storage");
            po.maxStorage = rs.getBigDecimal("max_storage");
            po.availableCapacity = rs.getBigDecimal("available_capacity");
            po.designFloodVolume = rs.getBigDecimal("design_flood_volume");
            po.capacityJudgment = rs.getString("capacity_judgment");
            po.dryBeachLength = rs.getBigDecimal("dry_beach_length");
            po.phreaticDepth = rs.getBigDecimal("phreatic_depth");
            po.dryBeachJudgment = rs.getString("dry_beach_judgment");
            po.phreaticJudgment = rs.getString("phreatic_judgment");
            po.dangerJudgment = rs.getString("danger_judgment");
            po.version = rs.getInt("version");
            po.triggerType = rs.getString("trigger_type");
            po.inflowHydrograph = rs.getString("inflow_hydrograph");
            long prevRoutingId = rs.getLong("prev_routing_id");
            po.prevRoutingId = rs.wasNull() ? null : prevRoutingId;
            po.inputSnapshot = rs.getString("input_snapshot");
            po.criterion = rs.getString("criterion");
            Timestamp created = rs.getTimestamp("created_at");
            po.createdAt = created == null ? null : created.toLocalDateTime();
            return po;
        }
    };

    public void insert(RoutingPO po) {
        jdbcTemplate.update(
                "INSERT INTO flood_routing(routing_no, pond_id, warning_id, request_id, "
                        + "current_water_level, current_storage, max_storage, available_capacity, "
                        + "design_flood_volume, capacity_judgment, dry_beach_length, phreatic_depth, "
                        + "dry_beach_judgment, phreatic_judgment, danger_judgment, version, "
                        + "trigger_type, inflow_hydrograph, prev_routing_id, input_snapshot, "
                        + "criterion, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                po.routingNo, po.pondId, po.warningId, po.requestId,
                po.currentWaterLevel, po.currentStorage, po.maxStorage, po.availableCapacity,
                po.designFloodVolume, po.capacityJudgment, po.dryBeachLength, po.phreaticDepth,
                po.dryBeachJudgment, po.phreaticJudgment, po.dangerJudgment, po.version,
                po.triggerType, po.inflowHydrograph, po.prevRoutingId, po.inputSnapshot,
                po.criterion, Timestamp.valueOf(po.createdAt));
    }

    public RoutingPO findByNo(String routingNo) {
        List<RoutingPO> list = jdbcTemplate.query(
                "SELECT * FROM flood_routing WHERE routing_no = ?", MAPPER, routingNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public RoutingPO findById(Long id) {
        List<RoutingPO> list = jdbcTemplate.query(
                "SELECT * FROM flood_routing WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public RoutingPO findByRequestId(String requestId) {
        List<RoutingPO> list = jdbcTemplate.query(
                "SELECT * FROM flood_routing WHERE request_id = ?", MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查询处置链最新版本演算（滚动重算的基准版本）。
     */
    public RoutingPO findLatestByWarningId(Long warningId) {
        List<RoutingPO> list = jdbcTemplate.query(
                "SELECT * FROM flood_routing WHERE warning_id = ? ORDER BY version DESC, id DESC "
                        + "LIMIT 1", MAPPER, warningId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查询处置链全部演算版本（旧版本保留可查，按版本号升序）。
     */
    public List<RoutingPO> findByWarningIdOrderByVersion(Long warningId) {
        return jdbcTemplate.query(
                "SELECT * FROM flood_routing WHERE warning_id = ? ORDER BY version, id",
                MAPPER, warningId);
    }
}
