package com.example.starter.route;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 路线请求的 JDBC 持久化；request_id 唯一约束支撑幂等。
 */
@Repository
public class RouteRequestRepository {

    private static final RowMapper<RouteRequestRecord> MAPPER = (rs, rowNum) -> new RouteRequestRecord(
            rs.getString("request_id"),
            rs.getString("map_version_id"),
            new MatchPoint(rs.getDouble("origin_x"), rs.getDouble("origin_y"), rs.getDouble("origin_heading")),
            new MatchPoint(rs.getDouble("dest_x"), rs.getDouble("dest_y"), rs.getDouble("dest_heading")),
            rs.getString("status"),
            rs.getString("error_code"),
            rs.getString("result_json"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public RouteRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RouteRequestRecord> findByRequestId(String requestId) {
        List<RouteRequestRecord> rows = jdbcTemplate.query(
                "SELECT request_id, map_version_id, origin_x, origin_y, origin_heading,"
                        + " dest_x, dest_y, dest_heading, status, error_code, result_json, created_at"
                        + " FROM route_request WHERE request_id = ?",
                MAPPER,
                requestId);
        return rows.stream().findFirst();
    }

    public void insert(RouteRequestRecord r) {
        jdbcTemplate.update(
                "INSERT INTO route_request (request_id, map_version_id, origin_x, origin_y, origin_heading,"
                        + " dest_x, dest_y, dest_heading, status, error_code, result_json, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                r.requestId(), r.mapVersionId(),
                r.origin().x(), r.origin().y(), r.origin().heading(),
                r.destination().x(), r.destination().y(), r.destination().heading(),
                r.status(), r.errorCode(), r.resultJson());
    }
}
