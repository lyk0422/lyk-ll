package com.example.starter.costmap.repo;

import com.example.starter.costmap.model.GridRegion;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 不可变快照及其冻结格点持久化。
 */
@Repository
public class SnapshotRepository {

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 快照元数据。 */
    public record SnapshotMeta(String snapshotId, String versionId, long seq) {
    }

    /** 插入快照头；snapshotId 主键冲突由数据库拒绝。 */
    public void insertSnapshot(String snapshotId, String versionId, long seq) {
        jdbc.update("INSERT INTO costmap_snapshot (snapshot_id, version_id, seq) VALUES (?, ?, ?)",
                snapshotId, versionId, seq);
    }

    /** 批量写入冻结格点；xycost 元素为 [x,y,cost]。 */
    public void insertCells(String snapshotId, List<int[]> xycost) {
        if (xycost.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO snapshot_cell (snapshot_id, x, y, cost)
                VALUES (?, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        int[] row = xycost.get(i);
                        ps.setString(1, snapshotId);
                        ps.setInt(2, row[0]);
                        ps.setInt(3, row[1]);
                        ps.setInt(4, row[2]);
                    }

                    @Override
                    public int getBatchSize() {
                        return xycost.size();
                    }
                });
    }

    /** 按ID查询快照头。 */
    public Optional<SnapshotMeta> findMeta(String snapshotId) {
        return jdbc.query(
                        "SELECT snapshot_id, version_id, seq FROM costmap_snapshot WHERE snapshot_id = ?",
                        (rs, n) -> new SnapshotMeta(rs.getString("snapshot_id"),
                                rs.getString("version_id"), rs.getLong("seq")),
                        snapshotId).stream()
                .findFirst();
    }

    /** 读取快照在区域内的冻结格点，元素 [x,y,cost]；与静态一致的格点不落行。 */
    public List<int[]> findCellsInRegion(String snapshotId, GridRegion region) {
        return jdbc.query("""
                        SELECT x, y, cost FROM snapshot_cell
                         WHERE snapshot_id = ?
                           AND x BETWEEN ? AND ?
                           AND y BETWEEN ? AND ?
                        """,
                (rs, n) -> new int[]{rs.getInt("x"), rs.getInt("y"), rs.getInt("cost")},
                snapshotId, region.xMin(), region.xMax(), region.yMin(), region.yMax());
    }
}
