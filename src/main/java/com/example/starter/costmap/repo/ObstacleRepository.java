package com.example.starter.costmap.repo;

import com.example.starter.costmap.model.GridRegion;
import com.example.starter.costmap.model.ObstacleEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 障碍物更新请求与行级障碍物单元格持久化。
 */
@Repository
public class ObstacleRepository {

    private final JdbcTemplate jdbc;

    public ObstacleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 已落库的更新请求记录。 */
    public record StoredUpdate(
            String requestId,
            String versionId,
            String sourceId,
            int priority,
            GridRegion region,
            long timestampMs,
            long ttlMs,
            String fingerprint,
            long seq) {
    }

    /** 按 requestId 查找历史请求（幂等重放/异参冲突判断）。 */
    public Optional<StoredUpdate> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT request_id, version_id, source_id, priority,
                       x_min, y_min, x_max, y_max, timestamp_ms, ttl_ms, fingerprint, seq
                  FROM obstacle_update WHERE request_id = ?
                """,
                (rs, n) -> new StoredUpdate(
                        rs.getString("request_id"),
                        rs.getString("version_id"),
                        rs.getString("source_id"),
                        rs.getInt("priority"),
                        new GridRegion(rs.getInt("x_min"), rs.getInt("y_min"),
                                rs.getInt("x_max"), rs.getInt("y_max")),
                        rs.getLong("timestamp_ms"),
                        rs.getLong("ttl_ms"),
                        rs.getString("fingerprint"),
                        rs.getLong("seq")),
                requestId).stream().findFirst();
    }

    /** 插入更新请求头；request_id 唯一约束兜底并发重放。 */
    public void insertUpdate(String versionId, ObstacleEvent event, String fingerprint, long seq) {
        jdbc.update("""
                INSERT INTO obstacle_update
                    (version_id, request_id, source_id, priority,
                     x_min, y_min, x_max, y_max, timestamp_ms, ttl_ms, expires_at,
                     fingerprint, seq)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                versionId,
                event.requestId(),
                event.sourceId(),
                event.priority(),
                event.region().xMin(),
                event.region().yMin(),
                event.region().xMax(),
                event.region().yMax(),
                event.timestampMs(),
                event.ttlMs(),
                event.expiresAt(),
                fingerprint,
                seq);
    }

    /**
     * 将一次更新覆盖区域内每个格点写为一行。
     * 行级幂等：主键 (version_id,x,y,request_id) 冲突时跳过，保证重放不会产生重复行。
     *
     * @return 逻辑覆盖格点数（供调用方计算重算范围）
     */
    public int insertCells(String versionId, ObstacleEvent event) {
        GridRegion r = event.region();
        int cols = r.xMax() - r.xMin() + 1;
        int rows = r.yMax() - r.yMin() + 1;
        int total = cols * rows;
        jdbc.batchUpdate("""
                MERGE INTO obstacle_cell
                    (version_id, x, y, request_id, source_id, priority, timestamp_ms, expires_at)
                KEY(version_id, x, y, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                        int x = r.xMin() + i % cols;
                        int y = r.yMin() + i / cols;
                        ps.setString(1, versionId);
                        ps.setInt(2, x);
                        ps.setInt(3, y);
                        ps.setString(4, event.requestId());
                        ps.setString(5, event.sourceId());
                        ps.setInt(6, event.priority());
                        ps.setLong(7, event.timestampMs());
                        ps.setLong(8, event.expiresAt());
                    }

                    @Override
                    public int getBatchSize() {
                        return total;
                    }
                });
        return total;
    }

    /**
     * 取出 box 区域内所有仍存在的障碍物行（过期行只由 cleanup 显式删除，查询前不做时间过滤，
     * 以保证代价缓存与中标来源展示一致）。
     * 每行：[x,y,priority,timestampMs,sourceId,requestId,expiresAt]。
     */
    public List<Object[]> findRowsInBox(String versionId, GridRegion box) {
        return jdbc.query("""
                SELECT x, y, priority, timestamp_ms, source_id, request_id, expires_at
                  FROM obstacle_cell
                 WHERE version_id = ?
                   AND x BETWEEN ? AND ?
                   AND y BETWEEN ? AND ?
                """,
                (rs, n) -> new Object[]{
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("priority"),
                        rs.getLong("timestamp_ms"),
                        rs.getString("source_id"),
                        rs.getString("request_id"),
                        rs.getLong("expires_at")},
                versionId, box.xMin(), box.xMax(), box.yMin(), box.yMax());
    }

    /** 查询存在已过期行（expires_at &lt; now）的格点坐标，元素 [x,y]，去重。 */
    public List<int[]> findExpiredCellPositions(String versionId, long nowMs) {
        return jdbc.query("""
                SELECT DISTINCT x, y FROM obstacle_cell
                 WHERE version_id = ? AND expires_at < ?
                 ORDER BY x, y
                """,
                (rs, n) -> new int[]{rs.getInt("x"), rs.getInt("y")},
                versionId, nowMs);
    }

    /**
     * 仅删除 expires_at &lt; now 的行；同一格点更新更晚（expires_at 更大）的数据一律保留。
     *
     * @return 删除行数
     */
    public int deleteExpired(String versionId, long nowMs) {
        return jdbc.update(
                "DELETE FROM obstacle_cell WHERE version_id = ? AND expires_at < ?",
                versionId, nowMs);
    }
}
