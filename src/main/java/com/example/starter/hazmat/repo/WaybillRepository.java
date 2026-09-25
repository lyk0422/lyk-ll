package com.example.starter.hazmat.repo;

import com.example.starter.hazmat.domain.WaybillStatus;
import com.example.starter.hazmat.model.Waybill;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 运单仓储；状态迁移均使用带前置状态条件的原子 UPDATE，保证并发下唯一终态。
 */
@Repository
public class WaybillRepository {

    private static final RowMapper<Waybill> MAPPER = WaybillRepository::map;

    private final JdbcTemplate jdbc;

    public WaybillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Waybill map(ResultSet rs, int i) throws SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new Waybill(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("vehicle_code"),
                rs.getInt("hazmat_level"),
                rs.getLong("route_id"),
                WaybillStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("planned_departure").toLocalDateTime(),
                rs.getInt("route_generation"),
                closedAt == null ? null : closedAt.toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    public long insert(String requestId, String fingerprint, String vehicleCode, int hazmatLevel,
                       long routeId, LocalDateTime plannedDeparture, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO waybill (request_id, fingerprint, vehicle_code, hazmat_level,"
                            + " route_id, status, planned_departure, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, vehicleCode);
            ps.setInt(4, hazmatLevel);
            ps.setLong(5, routeId);
            ps.setString(6, WaybillStatus.PENDING.name());
            ps.setTimestamp(7, Timestamp.valueOf(plannedDeparture));
            ps.setTimestamp(8, Timestamp.valueOf(now));
            ps.setTimestamp(9, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Waybill> findById(long id) {
        List<Waybill> rows = jdbc.query(
                "SELECT * FROM waybill WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<Waybill> findByRequestId(String requestId) {
        List<Waybill> rows = jdbc.query(
                "SELECT * FROM waybill WHERE request_id = ?", MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 查询车辆执行中（申请/核准/在途）的运单，用于“单车唯一在途”约束。
     */
    public List<Waybill> findActiveByVehicle(String vehicleCode) {
        return jdbc.query(
                "SELECT * FROM waybill WHERE vehicle_code = ?"
                        + " AND status IN ('PENDING', 'APPROVED', 'IN_TRANSIT')",
                MAPPER, vehicleCode);
    }

    public List<Waybill> findByStatus(WaybillStatus status) {
        return jdbc.query(
                "SELECT * FROM waybill WHERE status = ?", MAPPER, status.name());
    }

    /**
     * 原子状态迁移：仅当当前状态等于 expected 时更新为 target，返回受影响行数。
     */
    public int transition(long id, WaybillStatus expected, WaybillStatus target,
                          LocalDateTime now, boolean setClosedAt) {
        String sql = setClosedAt
                ? "UPDATE waybill SET status = ?, closed_at = ?, updated_at = ? WHERE id = ? AND status = ?"
                : "UPDATE waybill SET status = ?, updated_at = ? WHERE id = ? AND status = ?";
        if (setClosedAt) {
            return jdbc.update(sql, target.name(), Timestamp.valueOf(now), Timestamp.valueOf(now),
                    id, expected.name());
        }
        return jdbc.update(sql, target.name(), Timestamp.valueOf(now), id, expected.name());
    }

    /**
     * 改线生效时切换运单路线并推进路线代次（仅限在途运单）。
     */
    public int switchRoute(long id, long newRouteId, int newGeneration, LocalDateTime now) {
        return jdbc.update(
                "UPDATE waybill SET route_id = ?, route_generation = ?, updated_at = ?"
                        + " WHERE id = ? AND status = 'IN_TRANSIT'",
                newRouteId, newGeneration, Timestamp.valueOf(now), id);
    }
}
