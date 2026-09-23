package com.example.starter.costmap.repo;

import com.example.starter.costmap.domain.CellCost;
import com.example.starter.costmap.domain.MapMeta;
import com.example.starter.costmap.domain.ObstacleRow;
import com.example.starter.costmap.domain.Region;
import com.example.starter.costmap.domain.SnapshotCell;
import com.example.starter.costmap.domain.SourceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 动态 Costmap 的 H2 数据访问层（MODE=MySQL）。
 *
 * <p>所有 SQL 均参数化；时间戳统一为 Unix 毫秒列（BIGINT），业务时区 Asia/Shanghai
 * 仅影响展示。过期判定：{@code expires_at <= now} 即过期（存活半开区间 [applied, expires)）。</p>
 */
@Repository
public class CostmapRepository {

    private static final RowMapper<MapMeta> META_MAPPER = (rs, n) -> new MapMeta(
            rs.getString("map_id"),
            rs.getLong("version"),
            rs.getInt("width"),
            rs.getInt("height"),
            rs.getDouble("resolution"),
            rs.getDouble("origin_x"),
            rs.getDouble("origin_y"),
            rs.getInt("inflation_radius_cells"),
            rs.getDouble("cost_decay"),
            rs.getLong("created_at"));

    private static final RowMapper<CellCost> CELL_MAPPER = (rs, n) -> new CellCost(
            rs.getInt("x"), rs.getInt("y"), rs.getInt("cost"));

    private static final RowMapper<ObstacleRow> OBSTACLE_MAPPER = (rs, n) -> {
        long expiresValue = rs.getLong("expires_at");
        Long expiresAt = rs.wasNull() ? null : expiresValue;
        return new ObstacleRow(
                rs.getString("map_id"),
                rs.getString("request_id"),
                rs.getString("source_id"),
                SourceType.valueOf(rs.getString("source_type")),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("cost"),
                rs.getLong("revision"),
                rs.getLong("event_time"),
                expiresAt,
                rs.getLong("applied_at"),
                rs.getInt("superseded") == 1);
    };

    private final JdbcTemplate jdbc;

    public CostmapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询地图当前最新版本元数据；不存在返回 null。 */
    public MapMeta findLatestVersion(String mapId) {
        List<MapMeta> list = jdbc.query(
                "SELECT * FROM costmap_version WHERE map_id = ? ORDER BY version DESC LIMIT 1",
                META_MAPPER, mapId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询指定版本元数据；不存在返回 null。 */
    public MapMeta findVersion(String mapId, long version) {
        List<MapMeta> list = jdbc.query(
                "SELECT * FROM costmap_version WHERE map_id = ? AND version = ?",
                META_MAPPER, mapId, version);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 创建一个新的不可变地图版本行。 */
    public void insertVersion(MapMeta meta) {
        jdbc.update("""
                INSERT INTO costmap_version
                    (map_id, version, width, height, resolution, origin_x, origin_y,
                     inflation_radius_cells, cost_decay, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                meta.mapId(), meta.version(), meta.width(), meta.height(), meta.resolution(),
                meta.originX(), meta.originY(), meta.inflationRadiusCells(), meta.costDecay(),
                meta.createdAt());
    }

    /** 批量写入静态格栅单元（仅需写入非零单元）。 */
    public void insertStaticCells(String mapId, long version, List<CellCost> cells, long now) {
        jdbc.batchUpdate(
                "INSERT INTO costmap_static_cell (map_id, version, x, y, cost, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                cells, Math.max(1, cells.size()),
                (PreparedStatement ps, CellCost c) -> {
                    ps.setString(1, mapId);
                    ps.setLong(2, version);
                    ps.setInt(3, c.x());
                    ps.setInt(4, c.y());
                    ps.setInt(5, c.cost());
                    ps.setLong(6, now);
                });
    }

    /** 读取整个静态格栅的非零单元。 */
    public List<CellCost> loadStaticCells(String mapId, long version) {
        return jdbc.query(
                "SELECT x, y, cost FROM costmap_static_cell WHERE map_id = ? AND version = ?",
                CELL_MAPPER, mapId, version);
    }

    /** 读取区域内静态格栅非零单元。 */
    public List<CellCost> loadStaticCellsInRegion(String mapId, long version, Region region) {
        return jdbc.query("""
                SELECT x, y, cost FROM costmap_static_cell
                WHERE map_id = ? AND version = ?
                  AND x >= ? AND x < ? AND y >= ? AND y < ?
                """, CELL_MAPPER, mapId, version,
                region.x0(), region.x1(), region.y0(), region.y1());
    }

    /** 查询幂等记录；不存在返回 null。 */
    public IdempotencyRecord findIdempotency(String mapId, String requestId) {
        List<IdempotencyRecord> list = jdbc.query(
                "SELECT request_id, fingerprint, applied_revision, recorded_at "
                        + "FROM costmap_idempotency WHERE map_id = ? AND request_id = ?",
                (rs, n) -> new IdempotencyRecord(
                        rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)),
                mapId, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 插入幂等记录；唯一键冲突返回 false。 */
    @Transactional
    public boolean insertIdempotency(String mapId, String requestId, String fingerprint,
                                     long revision, long now) {
        int affected = jdbc.update("""
                INSERT INTO costmap_idempotency
                    (map_id, request_id, fingerprint, applied_revision, recorded_at)
                VALUES (?, ?, ?, ?, ?)
                """, mapId, requestId, fingerprint, revision, now);
        return affected == 1;
    }

    /** 读取某来源在区域内每格当前最大 revision；无数据时为 0。 */
    public long maxRevisionFor(String mapId, String sourceId, Region region) {
        Long max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) FROM costmap_obstacle
                WHERE map_id = ? AND source_id = ?
                  AND x >= ? AND x < ? AND y >= ? AND y < ?
                """, Long.class, mapId, sourceId,
                region.x0(), region.x1(), region.y0(), region.y1());
        return max == null ? 0L : max;
    }

    /** 写入一条障碍物记录，version 为写入时的当前地图版本（仅记录用途）。 */
    public void insertObstacle(long version, ObstacleRow row) {
        if (row.expiresAt() == null) {
            jdbc.update("""
                    INSERT INTO costmap_obstacle
                        (map_id, version, request_id, source_id, source_type, x, y, cost,
                         revision, event_time, expires_at, applied_at, superseded)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                    """,
                    row.mapId(), version, row.requestId(), row.sourceId(),
                    row.sourceType().name(), row.x(), row.y(), row.cost(), row.revision(),
                    row.eventTime(), row.appliedAt(), row.superseded() ? 1 : 0);
        } else {
            jdbc.update("""
                    INSERT INTO costmap_obstacle
                        (map_id, version, request_id, source_id, source_type, x, y, cost,
                         revision, event_time, expires_at, applied_at, superseded)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    row.mapId(), version, row.requestId(), row.sourceId(),
                    row.sourceType().name(), row.x(), row.y(), row.cost(), row.revision(),
                    row.eventTime(), row.expiresAt(), row.appliedAt(),
                    row.superseded() ? 1 : 0);
        }
    }

    /** 将同一来源在指定格上 revision 更小的记录标记为已取代。 */
    public int markSuperseded(String mapId, String sourceId, int x, int y, long newRevision) {
        return jdbc.update("""
                UPDATE costmap_obstacle
                SET superseded = 1
                WHERE map_id = ? AND source_id = ? AND x = ? AND y = ?
                  AND revision < ? AND superseded = 0
                """, mapId, sourceId, x, y, newRevision);
    }

    /** 将同一来源在区域内 revision 更小的所有记录批量标记为已取代。 */
    public int markSupersededInRegion(String mapId, String sourceId, Region region,
                                      long newRevision) {
        return jdbc.update("""
                UPDATE costmap_obstacle
                SET superseded = 1
                WHERE map_id = ? AND source_id = ?
                  AND x >= ? AND x < ? AND y >= ? AND y < ?
                  AND revision < ? AND superseded = 0
                """, mapId, sourceId,
                region.x0(), region.x1(), region.y0(), region.y1(), newRevision);
    }

    /** 判断地图 ID 是否已有任何版本（创建地图时去重）。 */
    public boolean existsAnyVersion(String mapId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM costmap_version WHERE map_id = ?", Integer.class, mapId);
        return n != null && n > 0;
    }

    /**
     * 读取包围盒内所有存活（未取代、未过期）动态障碍物。
     * 过期边界：{@code expires_at > now} 才存活。
     */
    public List<ObstacleRow> loadLiveObstacles(String mapId, Region bbox, long now) {
        return jdbc.query("""
                SELECT map_id, request_id, source_id, source_type, x, y, cost, revision,
                       event_time, expires_at, applied_at, superseded
                FROM costmap_obstacle
                WHERE map_id = ? AND superseded = 0
                  AND (expires_at IS NULL OR expires_at > ?)
                  AND x >= ? AND x < ? AND y >= ? AND y < ?
                """, OBSTACLE_MAPPER, mapId, now,
                bbox.x0(), bbox.x1(), bbox.y0(), bbox.y1());
    }

    /**
     * 物理删除过期障碍记录。
     *
     * <p>安全条件：只能删除同一来源同格旧 revision（{@code superseded = 1}）或已过期的当前
     * revision；更晚到期的记录 {@code expires_at > now} 绝不匹配，因此不会误删更新更晚的数据。</p>
     */
    public int deleteExpired(String mapId, long now) {
        return jdbc.update("""
                DELETE FROM costmap_obstacle
                WHERE map_id = ?
                  AND expires_at IS NOT NULL AND expires_at <= ?
                  AND (superseded = 1
                       OR revision = (
                           SELECT COALESCE(MAX(o2.revision), 0) FROM costmap_obstacle o2
                           WHERE o2.map_id = costmap_obstacle.map_id
                             AND o2.source_id = costmap_obstacle.source_id
                             AND o2.x = costmap_obstacle.x
                             AND o2.y = costmap_obstacle.y))
                """, mapId, now);
    }

    /** 插入快照主记录。 */
    public void insertSnapshot(String snapshotId, String mapId, long mapVersion, long now) {
        jdbc.update(
                "INSERT INTO costmap_snapshot (snapshot_id, map_id, map_version, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                snapshotId, mapId, mapVersion, now);
    }

    /** 查询快照主记录；不存在返回 null。 */
    public SnapshotRecord findSnapshot(String snapshotId) {
        List<SnapshotRecord> list = jdbc.query(
                "SELECT snapshot_id, map_id, map_version, created_at "
                        + "FROM costmap_snapshot WHERE snapshot_id = ?",
                (rs, n) -> new SnapshotRecord(
                        rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)),
                snapshotId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 批量写入快照单元（仅写任一非静态层非零或有获胜来源的单元）。 */
    public void insertSnapshotCells(String snapshotId, List<SnapshotCell> cells) {
        if (cells.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO costmap_snapshot_cell
                    (snapshot_id, x, y, static_cost, obstacle_cost, inflated_cost,
                     final_cost, winner_source_id, winner_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                cells, Math.max(1, cells.size()),
                (PreparedStatement ps, SnapshotCell c) -> {
                    ps.setString(1, snapshotId);
                    ps.setInt(2, c.x());
                    ps.setInt(3, c.y());
                    ps.setInt(4, c.staticCost());
                    if (c.obstacleCost() == null) {
                        ps.setNull(5, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(5, c.obstacleCost());
                    }
                    ps.setInt(6, c.inflatedCost());
                    ps.setInt(7, c.finalCost());
                    ps.setString(8, c.winnerSourceId());
                    ps.setString(9, c.winnerType());
                });
    }

    /** 读取快照在区域内的单元（缺失行视为全 0/无来源）。 */
    public List<SnapshotCell> loadSnapshotCellsInRegion(String snapshotId, Region region) {
        return jdbc.query("""
                SELECT x, y, static_cost, obstacle_cost, inflated_cost, final_cost,
                       winner_source_id, winner_type
                FROM costmap_snapshot_cell
                WHERE snapshot_id = ? AND x >= ? AND x < ? AND y >= ? AND y < ?
                """, (rs, n) -> {
            int obstacleCost = rs.getInt("obstacle_cost");
            Integer obstacle = rs.wasNull() ? null : obstacleCost;
            String winnerType = rs.getString("winner_type");
            return new SnapshotCell(
                    rs.getInt("x"), rs.getInt("y"),
                    rs.getInt("static_cost"), obstacle,
                    rs.getInt("inflated_cost"), rs.getInt("final_cost"),
                    rs.getString("winner_source_id"), winnerType);
        }, snapshotId, region.x0(), region.x1(), region.y0(), region.y1());
    }

    /** 幂等记录视图。 */
    public record IdempotencyRecord(String requestId, String fingerprint, long revision,
                                    long recordedAt) {
    }

    /** 快照主记录视图。 */
    public record SnapshotRecord(String snapshotId, String mapId, long mapVersion,
                                 long createdAt) {
    }

    /** 供测试校验使用的调试查询：某格障碍历史条数。 */
    public int countObstacleHistory(String mapId, String sourceId, int x, int y) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM costmap_obstacle "
                        + "WHERE map_id = ? AND source_id = ? AND x = ? AND y = ?",
                Integer.class, mapId, sourceId, x, y);
        return n == null ? 0 : n;
    }
}
