package com.example.starter.costmap.repo;

import com.example.starter.costmap.dto.SnapshotView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 版本快照的 H2 存取：发布后不可变，主键 (map_id, version) 防重复发布。
 */
@Repository
public class SnapshotRepository {

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入快照；主键冲突时抛出 {@link org.springframework.dao.DuplicateKeyException}。
     */
    public void insert(String mapId, long version, int width, int height,
                       double resolution, double inflationRadius,
                       byte[] grid, Instant createdAt) {
        jdbc.update("""
                        INSERT INTO costmap_snapshot
                            (map_id, version, width, height, resolution, inflation_radius, grid, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                mapId, version, width, height, resolution, inflationRadius, grid,
                Timestamp.from(createdAt));
    }

    /**
     * 按地图与版本读取快照。
     */
    public Optional<SnapshotView> find(String mapId, long version) {
        return jdbc.query("""
                        SELECT map_id, version, width, height, resolution, inflation_radius, grid, created_at
                        FROM costmap_snapshot WHERE map_id = ? AND version = ?
                        """,
                (rs, rowNum) -> new SnapshotView(
                        rs.getString("map_id"),
                        rs.getLong("version"),
                        rs.getInt("width"),
                        rs.getInt("height"),
                        rs.getDouble("resolution"),
                        rs.getDouble("inflation_radius"),
                        toInts(rs.getBytes("grid")),
                        rs.getTimestamp("created_at").toInstant().toString()),
                mapId, version).stream().findFirst();
    }

    private static int[] toInts(byte[] grid) {
        int[] out = new int[grid.length];
        for (int i = 0; i < grid.length; i++) {
            out[i] = grid[i] & 0xFF;
        }
        return out;
    }
}
