package com.example.starter.repo;

import java.util.Optional;

import com.example.starter.domain.RouteStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 线路与车辆数据访问。
 */
@Repository
public class RouteRepository {

    private final JdbcClient jdbc;

    public RouteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 线路记录。
     */
    public record RouteRow(long id, String routeCode, String name, long vehicleId,
                           int stationSpacingM, int roadGrade, int requiredSeats, String status) {
    }

    /**
     * 新建线路申请，返回自增ID。
     */
    public long insert(String routeCode, String name, long vehicleId, int stationSpacingM,
                       int roadGrade, int requiredSeats) {
        jdbc.sql("INSERT INTO route (route_code, name, vehicle_id, station_spacing_m, road_grade,"
                        + " required_seats, status) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(routeCode, name, vehicleId, stationSpacingM, roadGrade, requiredSeats,
                        RouteStatus.APPLIED.name())
                .update();
        return jdbc.sql("SELECT MAX(id) FROM route WHERE route_code = ?")
                .param(routeCode)
                .query(Long.class)
                .single();
    }

    public Optional<RouteRow> findById(long id) {
        return jdbc.sql("SELECT id, route_code, name, vehicle_id, station_spacing_m, road_grade,"
                        + " required_seats, status FROM route WHERE id = ?")
                .param(id)
                .query((rs, n) -> new RouteRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8)))
                .optional();
    }

    public void updateStatus(long id, RouteStatus status) {
        jdbc.sql("UPDATE route SET status = ? WHERE id = ?")
                .params(status.name(), id)
                .update();
    }

    /**
     * 车辆是否存在。
     */
    public boolean vehicleExists(long vehicleId) {
        return jdbc.sql("SELECT COUNT(*) FROM vehicle WHERE id = ?")
                .param(vehicleId)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * 车辆座位数。
     */
    public Optional<Integer> vehicleSeatCapacity(long vehicleId) {
        return jdbc.sql("SELECT seat_capacity FROM vehicle WHERE id = ?")
                .param(vehicleId)
                .query(Integer.class)
                .optional();
    }

    /**
     * 对车辆行加悲观写锁，用于串行化“同一车辆同一时刻仅一条执行中线路”的判定。
     */
    public void lockVehicle(long vehicleId) {
        jdbc.sql("SELECT id FROM vehicle WHERE id = ? FOR UPDATE")
                .param(vehicleId)
                .query(Long.class)
                .optional();
    }

    /**
     * 车辆当前执行中的线路数（不含 excludeRouteId）。
     */
    public long countInProgressByVehicle(long vehicleId, long excludeRouteId) {
        return jdbc.sql("SELECT COUNT(*) FROM route WHERE vehicle_id = ? AND status = ? AND id <> ?")
                .params(vehicleId, RouteStatus.IN_PROGRESS.name(), excludeRouteId)
                .query(Long.class)
                .single();
    }

    /**
     * 可接收改派学生的线路：非停运且不是来源线路，按ID升序取第一条。
     */
    public Optional<Long> findReassignmentTarget(long excludeRouteId) {
        return jdbc.sql("SELECT id FROM route WHERE id <> ? AND status IN (?, ?) ORDER BY id LIMIT 1")
                .params(excludeRouteId, RouteStatus.QUALIFIED.name(), RouteStatus.IN_PROGRESS.name())
                .query(Long.class)
                .optional();
    }

    /**
     * 线路当前学生数。
     */
    public long countStudents(long routeId) {
        return jdbc.sql("SELECT COUNT(*) FROM student WHERE route_id = ?")
                .param(routeId)
                .query(Long.class)
                .single();
    }
}
