package com.example.starter.battery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 电池台账数据访问。
 */
@Repository
public class BatteryRepository {

    private static final RowMapper<Battery> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public BatteryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Battery map(ResultSet rs) throws SQLException {
        Integer soc = rs.getObject("soc", Integer.class);
        Integer health = rs.getObject("health", Integer.class);
        Long batch = rs.getObject("inspection_batch", Long.class);
        return new Battery(
                rs.getLong("id"),
                rs.getString("code"),
                BatteryStatus.valueOf(rs.getString("status")),
                rs.getString("station_id"),
                rs.getString("vehicle_id"),
                soc,
                health,
                batch,
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    /** 建档插入，返回生成主键；编码冲突时抛出重复键异常。 */
    public long insert(String code, BatteryStatus status, String stationId, long now) {
        jdbc.update(
                "INSERT INTO battery (code, status, station_id, created_at, updated_at) VALUES (?,?,?,?,?)",
                code, status.name(), stationId, now, now);
        Long id = jdbc.queryForObject("SELECT id FROM battery WHERE code = ?", Long.class, code);
        return id == null ? -1L : id;
    }

    public Optional<Battery> findByCode(String code) {
        return jdbc.query("SELECT * FROM battery WHERE code = ?", MAPPER, code)
                .stream().findFirst();
    }

    /** 入站：仅允许在车辆侧（IN_USE）的电池回站。 */
    public int inboundFromVehicle(String code, String stationId, long now) {
        return jdbc.update(
                "UPDATE battery SET status = ?, station_id = ?, vehicle_id = NULL, updated_at = ? "
                        + "WHERE code = ? AND status = ?",
                BatteryStatus.PENDING_INSPECTION.name(), stationId, now,
                code, BatteryStatus.IN_USE.name());
    }

    /** 检测结论落库：仅允许在库待检或充电待修状态提交新批次。 */
    public int applyInspection(String code, int soc, int health, long batch,
                               BatteryStatus newStatus, long now) {
        return jdbc.update(
                "UPDATE battery SET soc = ?, health = ?, inspection_batch = ?, status = ?, updated_at = ? "
                        + "WHERE code = ? AND status IN (?, ?)",
                soc, health, batch, newStatus.name(), now,
                code, BatteryStatus.PENDING_INSPECTION.name(), BatteryStatus.CHARGING.name());
    }

    /** 换出：仅上架可换状态可换出，条件更新保证并发唯一成功。 */
    public int swapOutIfListed(String code, String vehicleId, long now) {
        return jdbc.update(
                "UPDATE battery SET status = ?, vehicle_id = ?, station_id = NULL, updated_at = ? "
                        + "WHERE code = ? AND status = ?",
                BatteryStatus.IN_USE.name(), vehicleId, now,
                code, BatteryStatus.LISTED.name());
    }

    /** 调拨发出：仅上架可换状态可发起调拨。 */
    public int markInTransitIfListed(String code, long now) {
        return jdbc.update(
                "UPDATE battery SET status = ?, station_id = NULL, updated_at = ? "
                        + "WHERE code = ? AND status = ?",
                BatteryStatus.IN_TRANSIT.name(), now,
                code, BatteryStatus.LISTED.name());
    }

    /** 到站签收：在途电池进入调入站待检。 */
    public int signInTransit(String code, String toStation, long now) {
        return jdbc.update(
                "UPDATE battery SET status = ?, station_id = ?, updated_at = ? "
                        + "WHERE code = ? AND status = ?",
                BatteryStatus.PENDING_INSPECTION.name(), toStation, now,
                code, BatteryStatus.IN_TRANSIT.name());
    }

    /** 在途超时转异常。 */
    public int markExceptionIfInTransit(String code, long now) {
        return jdbc.update(
                "UPDATE battery SET status = ?, updated_at = ? WHERE code = ? AND status = ?",
                BatteryStatus.TRANSFER_EXCEPTION.name(), now,
                code, BatteryStatus.IN_TRANSIT.name());
    }

    /** 核销后电池回到调出站待检。 */
    public int writeOffException(String code, String fromStation, long now) {
        return jdbc.update(
                "UPDATE battery SET status = ?, station_id = ?, updated_at = ? "
                        + "WHERE code = ? AND status = ?",
                BatteryStatus.PENDING_INSPECTION.name(), fromStation, now,
                code, BatteryStatus.TRANSFER_EXCEPTION.name());
    }
}
