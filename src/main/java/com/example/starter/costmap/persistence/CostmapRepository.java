package com.example.starter.costmap.persistence;

import com.example.starter.costmap.domain.ObstacleStatement;

import java.util.List;
import java.util.Optional;

/**
 * 代价地图持久化边界（JdbcTemplate + H2 MySQL 兼容模式实现）。
 */
public interface CostmapRepository {

    /** 插入新地图行；ID 冲突由唯一约束抛出，由上层转 VERSION_CONFLICT。 */
    void insertMap(CostmapPo po);

    /** 按 ID 查询地图（不加锁）。 */
    Optional<CostmapPo> findMap(String mapId);

    /** 按 ID 查询地图并对行加行锁（SELECT ... FOR UPDATE），须在事务内调用。 */
    Optional<CostmapPo> lockMap(String mapId);

    /** 覆盖当前动态视图缓存与版本序号（不改变 alloc_seq）。 */
    void updateLiveView(String mapId, int[] liveCells, long[] winnerCells, long liveSeq);

    /** 在行锁内分配下一个障碍物语句序号（alloc_seq 自增并返回新值）。 */
    long nextStatementSeq(String mapId);

    /** 幂等记录（即使障碍物行已被 TTL 删除仍保留）。 */
    Optional<IdempotencyRecord> findRequest(String mapId, String requestId);

    void insertRequest(String mapId, String requestId, String fingerprint, long appliedSeq, long now);

    /** 写入一条障碍物声明行。 */
    void insertStatement(String mapId, ObstacleStatement statement, String fingerprint);

    /** 查询当前未过期声明（expire_at &gt; nowMillis），按 seq 升序。 */
    List<ObstacleStatement> findActiveStatements(String mapId, long nowMillis);

    /** 查询已过期声明（expire_at &lt;= nowMillis），按 seq 升序；过期清理前用于增量定位。 */
    List<ObstacleStatement> findExpiredStatements(String mapId, long nowMillis);

    /** 物理删除已过期声明，返回删除行数；只能删除 expire_at &lt;= nowMillis 的行。 */
    int deleteExpiredStatements(String mapId, long nowMillis);

    /** 删除地图全部声明（恢复快照时使用）。 */
    int deleteAllStatements(String mapId);

    /** 发布快照：写快照头与全图固化数据。 */
    void insertSnapshot(SnapshotPo po);

    /** 批量复制快照时刻的活动声明存档。 */
    void insertSnapshotStatements(String mapId, String snapshotId, List<ObstacleStatement> active);

    Optional<SnapshotPo> findSnapshot(String mapId, String snapshotId);

    /** 快照内存档的声明数量。 */
    long countSnapshotStatements(String mapId, String snapshotId);

    List<ObstacleStatement> findSnapshotStatements(String mapId, String snapshotId);

    /** 恢复快照：以快照数据覆盖动态视图、序号高水位与活动声明集合，须在事务内调用。 */
    void restoreSnapshot(CostmapPo map, SnapshotPo snapshot, List<ObstacleStatement> statements);

    /** 分配并返回下一个快照序号（snapshot_seq 自增）。 */
    long nextSnapshotSeq(String mapId);

    /** 幂等记录行。 */
    record IdempotencyRecord(String requestId, String fingerprint, long appliedSeq, long createdAt) {
    }
}
