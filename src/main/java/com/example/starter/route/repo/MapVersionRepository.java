package com.example.starter.route.repo;

import com.example.starter.route.api.PublishMapRequest;
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
 * 地图版本快照持久化：版本行不可变更新，废弃仅修改状态；节点与路段按版本整体写入。
 */
@Repository
public class MapVersionRepository {

    /** 时间统一按 Asia/Shanghai 落库与回读（TIMESTAMP 不含时区） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final RowMapper<MapVersionRow> VERSION_MAPPER = (rs, rowNum) -> new MapVersionRow(
            rs.getLong("id"),
            rs.getString("version"),
            rs.getString("status"),
            toOffset(rs.getObject("published_at", LocalDateTime.class)),
            toOffset(rs.getObject("deprecated_at", LocalDateTime.class)));

    private static final RowMapper<MapNodeRow> NODE_MAPPER = (rs, rowNum) -> new MapNodeRow(
            rs.getLong("id"),
            rs.getString("version"),
            rs.getString("node_id"),
            rs.getDouble("x"),
            rs.getDouble("y"));

    private static final RowMapper<MapSegmentRow> SEGMENT_MAPPER = (rs, rowNum) -> new MapSegmentRow(
            rs.getLong("id"),
            rs.getString("version"),
            rs.getString("segment_id"),
            rs.getString("from_node_id"),
            rs.getString("to_node_id"),
            rs.getDouble("length_m"),
            rs.getInt("valid") == 1);

    private final JdbcTemplate jdbcTemplate;

    public MapVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsByVersion(String version) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM map_version WHERE version = ?", Integer.class, version);
        return count != null && count > 0;
    }

    public void insertVersion(String version, String status, OffsetDateTime publishedAt) {
        jdbcTemplate.update(
                "INSERT INTO map_version (version, status, published_at) VALUES (?, ?, ?)",
                version, status,
                Timestamp.valueOf(publishedAt.atZoneSameInstant(ZONE).toLocalDateTime()));
    }

    public Optional<MapVersionRow> findVersion(String version) {
        List<MapVersionRow> rows = jdbcTemplate.query(
                "SELECT id, version, status, published_at, deprecated_at FROM map_version WHERE version = ?",
                VERSION_MAPPER, version);
        return rows.stream().findFirst();
    }

    /**
     * 将 ACTIVE 版本置为 DEPRECATED；仅影响状态，不触碰节点/路段快照。
     *
     * @return 受影响行数；0 表示版本不存在或已废弃
     */
    public int markDeprecated(String version, OffsetDateTime deprecatedAt) {
        return jdbcTemplate.update(
                "UPDATE map_version SET status = 'DEPRECATED', deprecated_at = ? "
                        + "WHERE version = ? AND status = 'ACTIVE'",
                Timestamp.from(deprecatedAt.toInstant()), version);
    }

    public void insertNodes(String version, List<PublishMapRequest.NodeSpec> nodes) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO map_node (version, node_id, x, y) VALUES (?, ?, ?, ?)",
                nodes, 100, (ps, node) -> {
                    ps.setString(1, version);
                    ps.setString(2, node.nodeId());
                    ps.setDouble(3, node.x());
                    ps.setDouble(4, node.y());
                });
    }

    public void insertSegments(String version, List<PublishMapRequest.SegmentSpec> segments) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO map_segment (version, segment_id, from_node_id, to_node_id, length_m, valid) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                segments, 100, (ps, segment) -> {
                    ps.setString(1, version);
                    ps.setString(2, segment.segmentId());
                    ps.setString(3, segment.fromNodeId());
                    ps.setString(4, segment.toNodeId());
                    ps.setDouble(5, segment.lengthM());
                    ps.setInt(6, Boolean.FALSE.equals(segment.valid()) ? 0 : 1);
                });
    }

    public List<MapNodeRow> findNodes(String version) {
        return jdbcTemplate.query(
                "SELECT id, version, node_id, x, y FROM map_node WHERE version = ? ORDER BY id",
                NODE_MAPPER, version);
    }

    public int countNodes(String version) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM map_node WHERE version = ?", Integer.class, version);
        return count == null ? 0 : count;
    }

    public List<MapSegmentRow> findSegments(String version) {
        return jdbcTemplate.query(
                "SELECT id, version, segment_id, from_node_id, to_node_id, length_m, valid "
                        + "FROM map_segment WHERE version = ? ORDER BY id",
                SEGMENT_MAPPER, version);
    }

    public int countSegments(String version) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM map_segment WHERE version = ?", Integer.class, version);
        return count == null ? 0 : count;
    }

    private static OffsetDateTime toOffset(LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.atZone(ZONE).toOffsetDateTime();
    }
}
