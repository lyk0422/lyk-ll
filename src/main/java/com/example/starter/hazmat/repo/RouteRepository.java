package com.example.starter.hazmat.repo;

import com.example.starter.hazmat.model.RestrictionWindow;
import com.example.starter.hazmat.model.RouteInfo;
import com.example.starter.hazmat.model.RouteWaypoint;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 路线主数据、管制时段与途经点模板的只读仓储。
 */
@Repository
public class RouteRepository {

    private final JdbcTemplate jdbc;

    public RouteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RouteInfo> findById(long id) {
        List<RouteInfo> rows = jdbc.query(
                "SELECT id, code, name, pass_level FROM route WHERE id = ?",
                (rs, i) -> new RouteInfo(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name"), rs.getInt("pass_level")),
                id);
        return rows.stream().findFirst();
    }

    public Optional<RouteInfo> findByCode(String code) {
        List<RouteInfo> rows = jdbc.query(
                "SELECT id, code, name, pass_level FROM route WHERE code = ?",
                (rs, i) -> new RouteInfo(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name"), rs.getInt("pass_level")),
                code);
        return rows.stream().findFirst();
    }

    public List<RouteWaypoint> findWaypoints(long routeId) {
        return jdbc.query(
                "SELECT seq, name, offset_minutes FROM route_waypoint WHERE route_id = ? ORDER BY seq",
                (rs, i) -> new RouteWaypoint(rs.getInt("seq"), rs.getString("name"),
                        rs.getInt("offset_minutes")),
                routeId);
    }

    public List<RestrictionWindow> findRestrictions(long routeId) {
        return jdbc.query(
                "SELECT start_time, end_time FROM route_restriction WHERE route_id = ? ORDER BY start_time",
                (rs, i) -> new RestrictionWindow(rs.getTimestamp("start_time").toLocalDateTime(),
                        rs.getTimestamp("end_time").toLocalDateTime()),
                routeId);
    }
}
