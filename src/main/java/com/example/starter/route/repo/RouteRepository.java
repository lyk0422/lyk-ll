package com.example.starter.route.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 路线请求、候选明细与匹配结果持久化。请求一旦写入不可变；同 requestId 由唯一约束兜底。
 */
@Repository
public class RouteRepository {

    /** 时间统一按 Asia/Shanghai 落库与回读（TIMESTAMP 不含时区） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final RowMapper<RouteRequestRow> REQUEST_MAPPER = (rs, rowNum) -> new RouteRequestRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("map_version"),
            rs.getString("input_hash"),
            rs.getDouble("start_x"),
            rs.getDouble("start_y"),
            rs.getDouble("start_heading"),
            rs.getDouble("end_x"),
            rs.getDouble("end_y"),
            rs.getDouble("end_heading"),
            rs.getString("state"),
            rs.getString("error_code"),
            toOffset(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<RouteCandidateRow> CANDIDATE_MAPPER = (rs, rowNum) ->
            new RouteCandidateRow(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("endpoint"),
                    rs.getInt("rank_no"),
                    rs.getString("segment_id"),
                    rs.getDouble("distance_m"),
                    rs.getDouble("heading_delta_deg"),
                    rs.getDouble("distance_score"),
                    rs.getDouble("heading_score"),
                    rs.getDouble("connectivity_score"),
                    rs.getDouble("total_score"),
                    rs.getInt("selected") == 1,
                    rs.getString("reject_reasons"));

    private static final RowMapper<RouteMatchRow> MATCH_MAPPER = (rs, rowNum) -> new RouteMatchRow(
            rs.getString("request_id"),
            rs.getString("start_segment_id"),
            rs.getString("end_segment_id"),
            rs.getString("path_node_ids"),
            rs.getString("path_segment_ids"),
            rs.getDouble("total_score"));

    private final JdbcTemplate jdbcTemplate;

    public RouteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertRequest(
            String requestId,
            String mapVersion,
            String inputHash,
            double startX,
            double startY,
            double startHeading,
            double endX,
            double endY,
            double endHeading,
            String state,
            String errorCode,
            OffsetDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO route_request (request_id, map_version, input_hash, "
                        + "start_x, start_y, start_heading, end_x, end_y, end_heading, "
                        + "state, error_code, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                requestId, mapVersion, inputHash,
                startX, startY, startHeading, endX, endY, endHeading,
                state, errorCode, Timestamp.valueOf(createdAt.atZoneSameInstant(ZONE).toLocalDateTime()));
    }

    public Optional<RouteRequestRow> findRequest(String requestId) {
        List<RouteRequestRow> rows = jdbcTemplate.query(
                "SELECT id, request_id, map_version, input_hash, "
                        + "start_x, start_y, start_heading, end_x, end_y, end_heading, "
                        + "state, error_code, created_at FROM route_request WHERE request_id = ?",
                REQUEST_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public void insertCandidates(String requestId, List<RouteCandidateRow> candidates) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO route_candidate (request_id, endpoint, rank_no, segment_id, "
                        + "distance_m, heading_delta_deg, distance_score, heading_score, "
                        + "connectivity_score, total_score, selected, reject_reasons) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                candidates, 200, (ps, c) -> {
                    ps.setString(1, requestId);
                    ps.setString(2, c.endpoint());
                    ps.setInt(3, c.rankNo());
                    ps.setString(4, c.segmentId());
                    ps.setDouble(5, c.distanceM());
                    ps.setDouble(6, c.headingDeltaDeg());
                    ps.setDouble(7, c.distanceScore());
                    ps.setDouble(8, c.headingScore());
                    ps.setDouble(9, c.connectivityScore());
                    ps.setDouble(10, c.totalScore());
                    ps.setInt(11, c.selected() ? 1 : 0);
                    ps.setString(12, c.rejectReasons());
                });
    }

    public List<RouteCandidateRow> findCandidates(String requestId) {
        return jdbcTemplate.query(
                "SELECT id, request_id, endpoint, rank_no, segment_id, "
                        + "distance_m, heading_delta_deg, distance_score, heading_score, "
                        + "connectivity_score, total_score, selected, reject_reasons "
                        + "FROM route_candidate WHERE request_id = ? ORDER BY endpoint, rank_no",
                CANDIDATE_MAPPER, requestId);
    }

    public void insertMatch(RouteMatchRow match) {
        jdbcTemplate.update(
                "INSERT INTO route_match (request_id, start_segment_id, end_segment_id, "
                        + "path_node_ids, path_segment_ids, total_score) VALUES (?, ?, ?, ?, ?, ?)",
                match.requestId(), match.startSegmentId(), match.endSegmentId(),
                match.pathNodeIds(), match.pathSegmentIds(), match.totalScore());
    }

    public Optional<RouteMatchRow> findMatch(String requestId) {
        List<RouteMatchRow> rows = jdbcTemplate.query(
                "SELECT request_id, start_segment_id, end_segment_id, "
                        + "path_node_ids, path_segment_ids, total_score "
                        + "FROM route_match WHERE request_id = ?",
                MATCH_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    private static OffsetDateTime toOffset(LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.atZone(ZONE).toOffsetDateTime();
    }
}
