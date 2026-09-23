package com.example.starter.costmap.persistence;

import com.example.starter.costmap.domain.CellRegion;
import com.example.starter.costmap.domain.GridCodec;
import com.example.starter.costmap.domain.ObstacleStatement;
import com.example.starter.costmap.domain.StatementKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 基于 {@link JdbcTemplate} 的 H2（MODE=MySQL）持久化实现。
 *
 * <p>写路径均由服务层事务包裹；{@link #lockMap} 使用 SELECT ... FOR UPDATE 串行化
 * 同一地图的并发障碍物更新，配合唯一约束保证幂等与合并的确定性。</p>
 */
@Repository
public class JdbcCostmapRepository implements CostmapRepository {

    private static final String MAP_COLUMNS =
            "id, width, height, resolution, origin_x, origin_y, "
                    + "inscribed_radius, inflation_radius, cost_scaling_factor, "
                    + "static_data, live_data, winner_data, live_seq, alloc_seq, snapshot_seq, created_at";

    private final JdbcTemplate jdbcTemplate;

    public JdbcCostmapRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<CostmapPo> MAP_MAPPER = (rs, n) -> {
        int width = rs.getInt("width");
        int height = rs.getInt("height");
        int length = width * height;
        return new CostmapPo(
                rs.getString("id"),
                width,
                height,
                rs.getDouble("resolution"),
                rs.getDouble("origin_x"),
                rs.getDouble("origin_y"),
                rs.getDouble("inscribed_radius"),
                rs.getDouble("inflation_radius"),
                rs.getDouble("cost_scaling_factor"),
                GridCodec.decodeInts(rs.getBytes("static_data"), length),
                GridCodec.decodeInts(rs.getBytes("live_data"), length),
                GridCodec.decodeLongs(rs.getBytes("winner_data"), length),
                rs.getLong("live_seq"),
                rs.getLong("alloc_seq"),
                rs.getLong("snapshot_seq"),
                rs.getLong("created_at"));
    };

    private static final RowMapper<ObstacleStatement> STATEMENT_MAPPER = (rs, n) -> new ObstacleStatement(
            rs.getLong("seq"),
            rs.getString("request_id"),
            rs.getString("source_id"),
            rs.getString("obstacle_id"),
            rs.getInt("priority"),
            StatementKind.valueOf(rs.getString("kind")),
            new CellRegion(rs.getInt("x0"), rs.getInt("y0"), rs.getInt("x1"), rs.getInt("y1")),
            rs.getLong("event_time"),
            rs.getLong("ttl_millis"),
            rs.getLong("expire_at"),
            rs.getLong("created_at"));

    @Override
    public void insertMap(CostmapPo po) {
        int length = po.width() * po.height();
        jdbcTemplate.update("INSERT INTO costmap (id, width, height, resolution, origin_x, origin_y, "
                        + "inscribed_radius, inflation_radius, cost_scaling_factor, "
                        + "static_data, live_data, winner_data, live_seq, alloc_seq, snapshot_seq, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                po.id(), po.width(), po.height(), po.resolution(), po.originX(), po.originY(),
                po.inscribedRadius(), po.inflationRadius(), po.costScalingFactor(),
                GridCodec.encodeInts(po.staticCells()),
                GridCodec.encodeInts(po.liveCells()),
                GridCodec.encodeLongs(po.winnerCells()),
                po.liveSeq(), po.allocSeq(), po.snapshotSeq(), po.createdAt());
    }

    @Override
    public Optional<CostmapPo> findMap(String mapId) {
        List<CostmapPo> list = jdbcTemplate.query(
                "SELECT " + MAP_COLUMNS + " FROM costmap WHERE id = ?", MAP_MAPPER, mapId);
        return list.stream().findFirst();
    }

    @Override
    public Optional<CostmapPo> lockMap(String mapId) {
        List<CostmapPo> list = jdbcTemplate.query(
                "SELECT " + MAP_COLUMNS + " FROM costmap WHERE id = ? FOR UPDATE", MAP_MAPPER, mapId);
        return list.stream().findFirst();
    }

    @Override
    public void updateLiveView(String mapId, int[] liveCells, long[] winnerCells, long liveSeq) {
        jdbcTemplate.update(
                "UPDATE costmap SET live_data = ?, winner_data = ?, live_seq = ? WHERE id = ?",
                GridCodec.encodeInts(liveCells), GridCodec.encodeLongs(winnerCells), liveSeq, mapId);
    }

    @Override
    public long nextStatementSeq(String mapId) {
        jdbcTemplate.update("UPDATE costmap SET alloc_seq = alloc_seq + 1 WHERE id = ?", mapId);
        Long seq = jdbcTemplate.queryForObject(
                "SELECT alloc_seq FROM costmap WHERE id = ?", Long.class, mapId);
        return seq == null ? 0L : seq;
    }

    @Override
    public Optional<IdempotencyRecord> findRequest(String mapId, String requestId) {
        List<IdempotencyRecord> list = jdbcTemplate.query(
                "SELECT request_id, param_fingerprint, applied_seq, created_at "
                        + "FROM obstacle_request WHERE map_id = ? AND request_id = ?",
                (rs, n) -> new IdempotencyRecord(
                        rs.getString("request_id"),
                        rs.getString("param_fingerprint"),
                        rs.getLong("applied_seq"),
                        rs.getLong("created_at")),
                mapId, requestId);
        return list.stream().findFirst();
    }

    @Override
    public void insertRequest(String mapId, String requestId, String fingerprint, long appliedSeq, long now) {
        jdbcTemplate.update(
                "INSERT INTO obstacle_request (map_id, request_id, param_fingerprint, applied_seq, created_at) "
                        + "VALUES (?,?,?,?,?)",
                mapId, requestId, fingerprint, appliedSeq, now);
    }

    @Override
    public void insertStatement(String mapId, ObstacleStatement s, String fingerprint) {
        jdbcTemplate.update("INSERT INTO obstacle_statement (map_id, request_id, source_id, obstacle_id, "
                        + "priority, kind, x0, y0, x1, y1, event_time, ttl_millis, expire_at, "
                        + "param_fingerprint, seq, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                mapId, s.requestId(), s.sourceId(), s.obstacleId(), s.priority(),
                s.kind().name(), s.region().x0(), s.region().y0(), s.region().x1(), s.region().y1(),
                s.eventTime(), s.ttlMillis(), s.expireAt(), fingerprint, s.seq(), s.createdAt());
    }

    @Override
    public List<ObstacleStatement> findActiveStatements(String mapId, long nowMillis) {
        return jdbcTemplate.query(
                "SELECT " + STATEMENT_COLUMNS + " FROM obstacle_statement "
                        + "WHERE map_id = ? AND expire_at > ? ORDER BY seq ASC",
                STATEMENT_MAPPER, mapId, nowMillis);
    }

    @Override
    public List<ObstacleStatement> findExpiredStatements(String mapId, long nowMillis) {
        return jdbcTemplate.query(
                "SELECT " + STATEMENT_COLUMNS + " FROM obstacle_statement "
                        + "WHERE map_id = ? AND expire_at <= ? ORDER BY seq ASC",
                STATEMENT_MAPPER, mapId, nowMillis);
    }

    @Override
    public int deleteExpiredStatements(String mapId, long nowMillis) {
        return jdbcTemplate.update(
                "DELETE FROM obstacle_statement WHERE map_id = ? AND expire_at <= ?",
                mapId, nowMillis);
    }

    @Override
    public int deleteAllStatements(String mapId) {
        return jdbcTemplate.update("DELETE FROM obstacle_statement WHERE map_id = ?", mapId);
    }

    @Override
    public void insertSnapshot(SnapshotPo po) {
        int length = po.width() * po.height();
        jdbcTemplate.update("INSERT INTO costmap_snapshot (map_id, snapshot_id, seq, label, width, height, "
                        + "live_seq, alloc_seq, cell_data, winner_data, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                po.mapId(), po.snapshotId(), po.seq(), po.label(), po.width(), po.height(),
                po.liveSeq(), po.allocSeq(),
                GridCodec.encodeInts(po.cellData()),
                GridCodec.encodeLongs(po.winnerData()),
                po.createdAt());
    }

    @Override
    public void insertSnapshotStatements(String mapId, String snapshotId, List<ObstacleStatement> active) {
        if (active.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO snapshot_statement (map_id, snapshot_id, request_id, source_id, obstacle_id, "
                        + "priority, kind, x0, y0, x1, y1, event_time, ttl_millis, expire_at, stmt_seq) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                active, active.size(), (PreparedStatement ps, ObstacleStatement s) -> {
                    ps.setString(1, mapId);
                    ps.setString(2, snapshotId);
                    ps.setString(3, s.requestId());
                    ps.setString(4, s.sourceId());
                    ps.setString(5, s.obstacleId());
                    ps.setInt(6, s.priority());
                    ps.setString(7, s.kind().name());
                    ps.setInt(8, s.region().x0());
                    ps.setInt(9, s.region().y0());
                    ps.setInt(10, s.region().x1());
                    ps.setInt(11, s.region().y1());
                    ps.setLong(12, s.eventTime());
                    ps.setLong(13, s.ttlMillis());
                    ps.setLong(14, s.expireAt());
                    ps.setLong(15, s.seq());
                });
    }

    private static final RowMapper<SnapshotPo> SNAPSHOT_MAPPER = (rs, n) -> {
        int width = rs.getInt("width");
        int height = rs.getInt("height");
        int length = width * height;
        return new SnapshotPo(
                rs.getString("map_id"),
                rs.getString("snapshot_id"),
                rs.getLong("seq"),
                rs.getString("label"),
                width,
                height,
                rs.getLong("live_seq"),
                rs.getLong("alloc_seq"),
                GridCodec.decodeInts(rs.getBytes("cell_data"), length),
                GridCodec.decodeLongs(rs.getBytes("winner_data"), length),
                rs.getLong("created_at"));
    };

    @Override
    public Optional<SnapshotPo> findSnapshot(String mapId, String snapshotId) {
        List<SnapshotPo> list = jdbcTemplate.query(
                "SELECT * FROM costmap_snapshot WHERE map_id = ? AND snapshot_id = ?",
                SNAPSHOT_MAPPER, mapId, snapshotId);
        return list.stream().findFirst();
    }

    @Override
    public long countSnapshotStatements(String mapId, String snapshotId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM snapshot_statement WHERE map_id = ? AND snapshot_id = ?",
                Long.class, mapId, snapshotId);
        return count == null ? 0L : count;
    }

    @Override
    public List<ObstacleStatement> findSnapshotStatements(String mapId, String snapshotId) {        return jdbcTemplate.query(
                "SELECT request_id, source_id, obstacle_id, priority, kind, x0, y0, x1, y1, "
                        + "event_time, ttl_millis, expire_at, stmt_seq AS seq, ? AS created_at "
                        + "FROM snapshot_statement WHERE map_id = ? AND snapshot_id = ? ORDER BY stmt_seq ASC",
                STATEMENT_MAPPER, 0L, mapId, snapshotId);
    }

    @Override
    @Transactional
    public void restoreSnapshot(CostmapPo map, SnapshotPo snapshot, List<ObstacleStatement> statements) {
        deleteAllStatements(map.id());
        for (ObstacleStatement s : statements) {
            jdbcTemplate.update("INSERT INTO obstacle_statement (map_id, request_id, source_id, obstacle_id, "
                            + "priority, kind, x0, y0, x1, y1, event_time, ttl_millis, expire_at, "
                            + "param_fingerprint, seq, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    map.id(), s.requestId(), s.sourceId(), s.obstacleId(), s.priority(),
                    s.kind().name(), s.region().x0(), s.region().y0(), s.region().x1(), s.region().y1(),
                    s.eventTime(), s.ttlMillis(), s.expireAt(), "restored", s.seq(), s.createdAt());
        }
        long newAllocSeq = Math.max(map.allocSeq(), snapshot.allocSeq());
        jdbcTemplate.update(
                "UPDATE costmap SET live_data = ?, winner_data = ?, live_seq = ?, alloc_seq = ? WHERE id = ?",
                GridCodec.encodeInts(snapshot.cellData()),
                GridCodec.encodeLongs(snapshot.winnerData()),
                snapshot.liveSeq(), newAllocSeq, map.id());
    }

    @Override
    public long nextSnapshotSeq(String mapId) {
        jdbcTemplate.update("UPDATE costmap SET snapshot_seq = snapshot_seq + 1 WHERE id = ?", mapId);
        Long seq = jdbcTemplate.queryForObject(
                "SELECT snapshot_seq FROM costmap WHERE id = ?", Long.class, mapId);
        return seq == null ? 0L : seq;
    }

    private static final String STATEMENT_COLUMNS =
            "request_id, source_id, obstacle_id, priority, kind, x0, y0, x1, y1, "
                    + "event_time, ttl_millis, expire_at, seq, created_at";
}
