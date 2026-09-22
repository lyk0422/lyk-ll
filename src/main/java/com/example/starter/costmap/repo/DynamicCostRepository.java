package com.example.starter.costmap.repo;

import com.example.starter.costmap.model.GridRegion;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * 动态合并代价缓存：只持久化被增量重算波及的格点。
 */
@Repository
public class DynamicCostRepository {

    private final JdbcTemplate jdbc;

    public DynamicCostRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 批量 upsert 重算结果；xycost 元素为 [x,y,cost]。 */
    public void upsertCosts(String versionId, List<int[]> xycost, long seq) {
        if (xycost.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                MERGE INTO dynamic_cost (version_id, x, y, cost, updated_seq)
                KEY(version_id, x, y)
                VALUES (?, ?, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        int[] row = xycost.get(i);
                        ps.setString(1, versionId);
                        ps.setInt(2, row[0]);
                        ps.setInt(3, row[1]);
                        ps.setInt(4, row[2]);
                        ps.setLong(5, seq);
                    }

                    @Override
                    public int getBatchSize() {
                        return xycost.size();
                    }
                });
    }

    /** 读取区域内已缓存的代价，元素为 [x,y,cost,updatedSeq]。 */
    public List<int[]> findCostsInRegion(String versionId, GridRegion region) {
        return jdbc.query("""
                SELECT x, y, cost, updated_seq FROM dynamic_cost
                 WHERE version_id = ?
                   AND x BETWEEN ? AND ?
                   AND y BETWEEN ? AND ?
                """,
                (rs, n) -> new int[]{
                        rs.getInt("x"), rs.getInt("y"),
                        rs.getInt("cost"), (int) rs.getLong("updated_seq")},
                versionId, region.xMin(), region.xMax(), region.yMin(), region.yMax());
    }

    /** 全量读取（快照发布用），元素为 [x,y,cost]。 */
    public List<int[]> findAllCosts(String versionId) {
        return jdbc.query(
                "SELECT x, y, cost FROM dynamic_cost WHERE version_id = ? ORDER BY y, x",
                (rs, n) -> new int[]{rs.getInt("x"), rs.getInt("y"), rs.getInt("cost")},
                versionId);
    }
}
