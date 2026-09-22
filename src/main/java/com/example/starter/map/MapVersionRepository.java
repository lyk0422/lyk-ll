package com.example.starter.map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 地图版本与路段的 JDBC 持久化。
 */
@Repository
public class MapVersionRepository {

    /**
     * 版本元信息。
     *
     * @param status    版本状态
     * @param createdAt 发布时间（Asia/Shanghai）
     */
    public record VersionMeta(MapVersionStatus status, LocalDateTime createdAt) {
    }

    private static final RowMapper<MapSegment> SEGMENT_MAPPER = (rs, rowNum) -> new MapSegment(
            rs.getString("segment_id"),
            rs.getString("start_node"),
            rs.getString("end_node"),
            rs.getDouble("start_x"),
            rs.getDouble("start_y"),
            rs.getDouble("end_x"),
            rs.getDouble("end_y"),
            rs.getBoolean("active"));

    private final JdbcTemplate jdbcTemplate;

    public MapVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertVersion(String versionId, MapVersionStatus status) {
        jdbcTemplate.update(
                "INSERT INTO map_version (version_id, status, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                versionId, status.name());
    }

    public void insertSegment(String versionId, MapSegment s) {
        jdbcTemplate.update(
                "INSERT INTO map_segment (version_id, segment_id, start_node, end_node,"
                        + " start_x, start_y, end_x, end_y, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                versionId, s.segmentId(), s.startNode(), s.endNode(),
                s.startX(), s.startY(), s.endX(), s.endY(), s.active());
    }

    public Optional<MapVersionStatus> findStatus(String versionId) {
        return findMeta(versionId).map(VersionMeta::status);
    }

    public Optional<VersionMeta> findMeta(String versionId) {
        List<VersionMeta> rows = jdbcTemplate.query(
                "SELECT status, created_at FROM map_version WHERE version_id = ?",
                (rs, rowNum) -> new VersionMeta(
                        MapVersionStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                versionId);
        return rows.stream().findFirst();
    }

    public List<MapSegment> findSegments(String versionId) {
        return jdbcTemplate.query(
                "SELECT segment_id, start_node, end_node, start_x, start_y, end_x, end_y, active"
                        + " FROM map_segment WHERE version_id = ? ORDER BY segment_id",
                SEGMENT_MAPPER,
                versionId);
    }

    public void updateStatus(String versionId, MapVersionStatus status) {
        jdbcTemplate.update(
                "UPDATE map_version SET status = ? WHERE version_id = ?",
                status.name(), versionId);
    }
}
