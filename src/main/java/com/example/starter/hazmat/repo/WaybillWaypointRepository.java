package com.example.starter.hazmat.repo;

import com.example.starter.hazmat.domain.WaypointStatus;
import com.example.starter.hazmat.model.WaybillWaypoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 运单途经点实例仓储；同一运单改线后按 generation 保留多代途经点。
 */
@Repository
public class WaybillWaypointRepository {

    private static final RowMapper<WaybillWaypoint> MAPPER = WaybillWaypointRepository::map;

    private final JdbcTemplate jdbc;

    public WaybillWaypointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static WaybillWaypoint map(ResultSet rs, int i) throws SQLException {
        Timestamp checkin = rs.getTimestamp("checkin_time");
        return new WaybillWaypoint(
                rs.getLong("id"),
                rs.getLong("waybill_id"),
                rs.getInt("generation"),
                rs.getInt("seq"),
                rs.getTimestamp("planned_pass_time").toLocalDateTime(),
                checkin == null ? null : checkin.toLocalDateTime(),
                WaypointStatus.valueOf(rs.getString("status")));
    }

    public void insert(long waybillId, int generation, int seq, LocalDateTime plannedPassTime) {
        jdbc.update(
                "INSERT INTO waybill_waypoint (waybill_id, generation, seq, planned_pass_time, status)"
                        + " VALUES (?, ?, ?, ?, 'PENDING')",
                waybillId, generation, seq, Timestamp.valueOf(plannedPassTime));
    }

    /**
     * 某运单当前代次的全部途经点（用于视图展示与复算）。
     */
    public List<WaybillWaypoint> findCurrentByWaybill(long waybillId, int generation) {
        return jdbc.query(
                "SELECT * FROM waybill_waypoint WHERE waybill_id = ? AND generation = ? ORDER BY seq",
                MAPPER, waybillId, generation);
    }

    public Optional<WaybillWaypoint> findByWaybillGenSeq(long waybillId, int generation, int seq) {
        List<WaybillWaypoint> rows = jdbc.query(
                "SELECT * FROM waybill_waypoint WHERE waybill_id = ? AND generation = ? AND seq = ?",
                MAPPER, waybillId, generation, seq);
        return rows.stream().findFirst();
    }

    /**
     * 当前代次仍待打卡的途经点（计划通过时刻升序）。
     */
    public List<WaybillWaypoint> findOpen(long waybillId, int generation) {
        return jdbc.query(
                "SELECT * FROM waybill_waypoint WHERE waybill_id = ? AND generation = ?"
                        + " AND status IN ('PENDING', 'TIMEOUT') ORDER BY seq",
                MAPPER, waybillId, generation);
    }

    /**
     * 打卡落位：仅当代次与序号匹配且仍 PENDING/TIMEOUT 时更新为 CHECKED，返回受影响行数。
     */
    public int markChecked(long waybillId, int generation, int seq, LocalDateTime checkinTime) {
        return jdbc.update(
                "UPDATE waybill_waypoint SET status = 'CHECKED', checkin_time = ?"
                        + " WHERE waybill_id = ? AND generation = ? AND seq = ?"
                        + " AND status IN ('PENDING', 'TIMEOUT')",
                Timestamp.valueOf(checkinTime), waybillId, generation, seq);
    }

    /**
     * 超时标记：仅当仍 PENDING 时更新为 TIMEOUT，返回受影响行数。
     */
    public int markTimeout(long waybillId, int generation, int seq) {
        return jdbc.update(
                "UPDATE waybill_waypoint SET status = 'TIMEOUT'"
                        + " WHERE waybill_id = ? AND generation = ? AND seq = ? AND status = 'PENDING'",
                waybillId, generation, seq);
    }

    /**
     * 改线生效时终止指定旧代次未打卡途经点。
     */
    public int cancelOpenWaypoints(long waybillId, int generation) {
        return jdbc.update(
                "UPDATE waybill_waypoint SET status = 'CANCELLED'"
                        + " WHERE waybill_id = ? AND generation = ?"
                        + " AND status IN ('PENDING', 'TIMEOUT')",
                waybillId, generation);
    }

    /**
     * 当前代次超时（偏离预警）途经点数量，用于失联升级复算。
     */
    public long countTimeout(long waybillId, int generation) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM waybill_waypoint WHERE waybill_id = ? AND generation = ?"
                        + " AND status = 'TIMEOUT'",
                Long.class, waybillId, generation);
        return count == null ? 0 : count;
    }
}
