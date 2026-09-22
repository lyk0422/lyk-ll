package com.example.starter.costmap.service;

import com.example.starter.costmap.model.GridRegion;
import com.example.starter.costmap.support.CostMath;
import com.example.starter.costmap.support.TimeSource;
import com.example.starter.costmap.web.ApiException;
import com.example.starter.costmap.web.dto.CellChange;
import com.example.starter.costmap.web.dto.CleanupResponse;
import com.example.starter.costmap.web.dto.CreateMapRequest;
import com.example.starter.costmap.web.dto.MapResponse;
import com.example.starter.costmap.web.dto.ObstacleUpdateRequest;
import com.example.starter.costmap.web.dto.RegionCostResponse;
import com.example.starter.costmap.web.dto.RestoreViewResponse;
import com.example.starter.costmap.web.dto.SnapshotResponse;
import com.example.starter.costmap.web.dto.UpdateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于 H2（MODE=MySQL）的核心业务测试：真实建表、真实事务与唯一约束，
 * 覆盖重叠障碍合并、TTL 边界、区域增量、幂等、并发合并、快照差异与恢复。
 */
@SpringBootTest
@ActiveProfiles("test")
class CostmapServiceH2Test {

    private static final int W = 10;
    private static final int H = 10;
    private static final long T0 = 10_000L;

    @Autowired
    private CostmapService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TimeSource timeSource;

    private String mapId;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM snapshot_cell");
        jdbc.update("DELETE FROM costmap_snapshot");
        jdbc.update("DELETE FROM dynamic_cost");
        jdbc.update("DELETE FROM obstacle_cell");
        jdbc.update("DELETE FROM obstacle_update");
        jdbc.update("DELETE FROM map_version");
        timeSource.setFixed(T0);
        MapResponse map = service.createMap(new CreateMapRequest(
                null, W, H, 1.0, 2.0, 0.5, null));
        mapId = map.versionId();
    }

    @AfterEach
    void resetClock() {
        timeSource.reset();
    }

    private ObstacleUpdateRequest obstacle(String reqId, String source, Integer priority,
                                           int x1, int y1, int x2, int y2,
                                           Long ts, long ttl) {
        return new ObstacleUpdateRequest(reqId, source, priority, x1, y1, x2, y2, ts, ttl, null);
    }

    private int cost(RegionCostResponse r, int x, int y) {
        return r.costs()[y - r.yMin()][x - r.xMin()];
    }

    private String owner(RegionCostResponse r, int x, int y) {
        return r.owners()[y - r.yMin()][x - r.xMin()];
    }

    private RegionCostResponse query(int x1, int y1, int x2, int y2) {
        return service.queryRegion(mapId, new GridRegion(x1, y1, x2, y2));
    }

    // ---------- 主流程：更新 + 仅增量重算 + 膨胀代价 ----------

    @Test
    void updateAppliesLethalAndInflationOnlyWithinIncrementalBox() {
        UpdateResponse resp = service.updateObstacle(mapId,
                obstacle("r1", "lidar", 0, 5, 5, 5, 5, T0, 60_000L));

        assertFalse(resp.replayed());
        assertEquals(1, resp.seq());
        // 受影响区域 = 障碍(1格) 外扩膨胀半径 2 格 → 5x5 = 25，而不是整图 100
        assertEquals(3, resp.rxMin());
        assertEquals(7, resp.rxMax());
        assertEquals(25, resp.recomputedCells());
        Integer dynamicRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dynamic_cost WHERE version_id = ?", Integer.class, mapId);
        assertEquals(25, dynamicRows, "只能写入受影响区域，不得重建整图");

        RegionCostResponse all = query(0, 0, 9, 9);
        assertEquals(CostMath.LETHAL, cost(all, 5, 5));
        assertEquals("lidar", owner(all, 5, 5));
        // 膨胀环：距离 1 格与 2 格按公开公式衰减
        int expected1 = (int) Math.round(253 * Math.exp(-0.5));
        int expected2 = (int) Math.round(253 * Math.exp(-1.0));
        assertEquals(expected1, cost(all, 6, 5));
        assertEquals(expected2, cost(all, 7, 5));
        // 膨胀环格点未被占据，无 owner
        assertNull(owner(all, 6, 5));
        // 半径外格点保持静态 0
        assertEquals(0, cost(all, 0, 0));
        assertEquals(0, cost(all, 8, 5));
    }

    @Test
    void secondUpdateRecomputesOnlyItsOwnIncrementalBox() {
        service.updateObstacle(mapId, obstacle("r1", "a", 0, 1, 1, 1, 1, T0, 60_000L));
        UpdateResponse resp2 = service.updateObstacle(mapId,
                obstacle("r2", "b", 0, 8, 8, 8, 8, T0 + 1, 60_000L));
        assertEquals(2, resp2.seq());
        // 第二次只重算 (6..9,6..9) = 16 格
        assertEquals(6, resp2.rxMin());
        assertEquals(9, resp2.rxMax());
        assertEquals(16, resp2.recomputedCells());

        RegionCostResponse all = query(0, 0, 9, 9);
        assertEquals(CostMath.LETHAL, cost(all, 1, 1));
        assertEquals(CostMath.LETHAL, cost(all, 8, 8));
    }

    // ---------- 重叠障碍：确定性合并 ----------

    @Test
    void overlappingObstaclesMergeByPriorityThenTimestampThenSourceId() {
        // 第一决胜键：priority 降序
        service.updateObstacle(mapId, obstacle("r-a", "alpha", 1, 5, 5, 5, 5, T0, 60_000L));
        service.updateObstacle(mapId, obstacle("r-b", "beta", 2, 5, 5, 5, 5, T0, 60_000L));
        RegionCostResponse r = query(5, 5, 5, 5);
        assertEquals("beta", owner(r, 5, 5), "高优先级必须中标");

        // 同优先级：timestamp 新者中标
        service.updateObstacle(mapId, obstacle("r-c", "gamma", 2, 3, 3, 3, 3, T0 + 100, 60_000L));
        service.updateObstacle(mapId, obstacle("r-d", "delta", 2, 3, 3, 3, 3, T0 + 200, 60_000L));
        assertEquals("delta", owner(query(3, 3, 3, 3), 3, 3), "时间戳更新必须中标");

        // 同优先级同时间戳：sourceId 字典序升序中标
        service.updateObstacle(mapId, obstacle("r-e", "zeta", 5, 7, 1, 7, 1, T0, 60_000L));
        service.updateObstacle(mapId, obstacle("r-f", "eta", 5, 7, 1, 7, 1, T0, 60_000L));
        assertEquals("eta", owner(query(7, 1, 7, 1), 7, 1), "sourceId 字典序更小者中标");
    }

    // ---------- TTL 边界与安全清理 ----------

    @Test
    void cleanupAtExactExpiryKeepsRowAndAfterExpiryRemovesOnlyOldRows() {
        service.updateObstacle(mapId, obstacle("r1", "a", 0, 2, 2, 2, 2, T0, 1_000L));

        // expires_at = 11000；删除条件为严格小于，边界时刻不得删除
        CleanupResponse atBoundary = service.cleanup(mapId, 11_000L);
        assertEquals(0, atBoundary.removedCells());
        assertEquals(CostMath.LETHAL, cost(query(2, 2, 2, 2), 2, 2));

        // 过期后清理：格点回到静态代价 0，且只重算膨胀盒
        CleanupResponse after = service.cleanup(mapId, 11_001L);
        assertEquals(1, after.removedCells());
        assertEquals(25, after.recomputedCells());
        assertEquals(0, cost(query(2, 2, 2, 2), 2, 2));
        assertNull(owner(query(2, 2, 2, 2), 2, 2));
    }

    @Test
    void cleanupMustNotDeleteLaterDataAndFallsBackToNextWinner() {
        // 同格：高优先级但更早过期的行，与低优先级但更晚过期的行
        service.updateObstacle(mapId, obstacle("early", "hi", 10, 3, 3, 3, 3, T0, 100L));
        service.updateObstacle(mapId, obstacle("later", "lo", 1, 3, 3, 3, 3, T0, 5_000L));
        // 另一格：完全在过期范围外的数据
        service.updateObstacle(mapId, obstacle("far", "far-src", 0, 8, 8, 8, 8, T0, 9_000L));

        assertEquals("hi", owner(query(3, 3, 3, 3), 3, 3));

        CleanupResponse resp = service.cleanup(mapId, T0 + 101);
        assertEquals(1, resp.removedCells(), "只删除 early 的 1 行");
        // 更晚过期的低优先级行接管，格点仍致命
        RegionCostResponse r = query(3, 3, 8, 8);
        assertEquals("lo", owner(r, 3, 3), "旧赢家删除后由下一行接管，不能误删更晚数据");
        assertEquals(CostMath.LETHAL, cost(r, 3, 3));
        assertEquals("far-src", owner(r, 8, 8), "未过期数据必须保留");
        assertEquals(CostMath.LETHAL, cost(r, 8, 8));
    }

    // ---------- 幂等与冲突 ----------

    @Test
    void sameRequestIdReplayIsIdempotentAndDifferentParamsConflict() {
        ObstacleUpdateRequest first = obstacle("idem-1", "a", 0, 4, 4, 4, 4, T0, 60_000L);
        UpdateResponse r1 = service.updateObstacle(mapId, first);
        assertEquals(1, r1.seq());

        UpdateResponse replay = service.updateObstacle(mapId,
                obstacle("idem-1", "a", 0, 4, 4, 4, 4, T0, 60_000L));
        assertTrue(replay.replayed());
        assertEquals(1, replay.seq(), "重放不得增加 seq");
        assertEquals(0, replay.recomputedCells(), "重放不得触发重算");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM obstacle_update WHERE request_id = 'idem-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM obstacle_cell WHERE request_id = 'idem-1'", Integer.class));

        ObstacleUpdateRequest mutated = obstacle("idem-1", "a", 0, 4, 4, 5, 5, T0, 60_000L);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.updateObstacle(mapId, mutated));
        assertEquals("IDEMPOTENCY_CONFLICT", ex.code());
    }

    @Test
    void expectedSeqMismatchReturnsVersionConflict() {
        service.updateObstacle(mapId, obstacle("r1", "a", 0, 1, 1, 1, 1, T0, 60_000L));
        ObstacleUpdateRequest stale = new ObstacleUpdateRequest(
                "r2", "b", 0, 2, 2, 2, 2, T0, 60_000L, 0L);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.updateObstacle(mapId, stale));
        assertEquals("VERSION_CONFLICT", ex.code());
    }

    @Test
    void sameRequestIdAcrossDifferentMapsConflictsWithoutSeqGap() {
        // requestId 全局唯一：在另一地图版本复用同一 requestId 必须报 409，且不产生 seq 空洞
        String otherMap = service.createMap(
                new CreateMapRequest(null, 4, 4, 1.0, 1.0, 1.0, null)).versionId();
        service.updateObstacle(otherMap, obstacle("global-id", "x", 0, 0, 0, 0, 0, T0, 60_000L));

        ApiException ex = assertThrows(ApiException.class, () -> service.updateObstacle(mapId,
                obstacle("global-id", "y", 0, 1, 1, 1, 1, T0, 60_000L)));
        assertEquals("IDEMPOTENCY_CONFLICT", ex.code());

        // 失败请求不得让 otherMap 的 seq 出现空洞：下一条正常更新应申领 seq=2
        UpdateResponse next = service.updateObstacle(otherMap,
                obstacle("other-r2", "x", 0, 1, 1, 1, 1, T0, 60_000L));
        assertEquals(2, next.seq());
    }

    // ---------- 非法输入与缺失版本 ----------

    @Test
    void invalidRegionsAndMissingMapProduceStableErrors() {
        ApiException reversed = assertThrows(ApiException.class, () ->
                service.queryRegion(mapId, new GridRegion(5, 5, 2, 2)));
        assertEquals("INVALID_REGION", reversed.code());

        ApiException outOfBounds = assertThrows(ApiException.class, () ->
                service.updateObstacle(mapId,
                        obstacle("rx", "a", 0, 0, 0, W, H, T0, 60_000L)));
        assertEquals("INVALID_REGION", outOfBounds.code());

        ApiException missing = assertThrows(ApiException.class, () ->
                service.queryRegion("no-such-map", new GridRegion(0, 0, 0, 0)));
        assertEquals("MAP_NOT_FOUND", missing.code());
    }

    // ---------- 快照、差异、恢复视图 ----------

    @Test
    void snapshotIsImmutableAndRestoredViewReadsFrozenData() {
        service.updateObstacle(mapId, obstacle("r1", "a", 0, 4, 4, 4, 4, T0, 500L));
        SnapshotResponse snap = service.publishSnapshot(mapId, "snap-1");
        assertEquals(1, snap.seq());

        // 快照发布后：新增障碍物并让旧数据过期清理，实时视图变化
        service.updateObstacle(mapId, obstacle("r2", "b", 0, 6, 6, 6, 6, T0 + 200, 5_000L));
        service.cleanup(mapId, T0 + 501);

        RegionCostResponse live = query(4, 4, 6, 6);
        assertEquals(0, cost(live, 4, 4), "实时视图中旧障碍已过期");
        assertEquals(CostMath.LETHAL, cost(live, 6, 6));

        RegionCostResponse frozen = service.queryRef(mapId, "snap-1", new GridRegion(4, 4, 6, 6));
        assertEquals("snap-1", frozen.snapshotId());
        assertEquals(1, frozen.seq());
        assertEquals(CostMath.LETHAL, cost(frozen, 4, 4), "快照必须保持发布时刻代价");
        // (6,6) 到 (4,4) 距离 √8≈2.83 格 > 膨胀半径 2 格，发布时刻冻结值为 0；
        // 快照发布后 r2 的致命更新不得影响快照。
        int frozen66 = cost(frozen, 6, 6);
        assertEquals(0, frozen66, "膨胀半径外格点快照应保持静态值 0");

        // 区域差异：snap-1 -> live
        CellChange.DiffResponse diff = service.diff(mapId, "snap-1", "live", new GridRegion(4, 4, 6, 6));
        assertTrue(diff.changedCount() > 0);
        assertTrue(diff.changes().stream().anyMatch(c -> c.x() == 4 && c.y() == 4
                && c.fromCost() == CostMath.LETHAL && c.toCost() == 0));
        assertTrue(diff.changes().stream().anyMatch(c -> c.x() == 6 && c.y() == 6
                && c.fromCost() == frozen66 && c.toCost() == CostMath.LETHAL));
        // 差异稳定排序：按 y,x
        for (int i = 1; i < diff.changes().size(); i++) {
            CellChange prev = diff.changes().get(i - 1);
            CellChange cur = diff.changes().get(i);
            assertTrue(prev.y() < cur.y() || prev.y() == cur.y() && prev.x() <= cur.x());
        }

        // 从 H2 快照恢复查询视图，数据仍为冻结值
        RestoreViewResponse restored = service.restoreView("snap-1");
        RegionCostResponse viaView = service.queryViewById(restored.viewId(), new GridRegion(4, 4, 6, 6));
        assertEquals(restored.viewId(), viaView.viewId());
        assertEquals("snap-1", viaView.snapshotId());
        assertEquals(CostMath.LETHAL, cost(viaView, 4, 4));
        assertEquals(frozen66, cost(viaView, 6, 6), "恢复视图必须读取快照冻结值");

        assertEquals("SNAPSHOT_NOT_FOUND",
                assertThrows(ApiException.class, () -> service.restoreView("nope")).code());
        assertEquals("VIEW_NOT_FOUND",
                assertThrows(ApiException.class, () ->
                        service.queryViewById("view-nope", new GridRegion(0, 0, 0, 0))).code());
    }

    @Test
    void duplicateSnapshotIdConflicts() {
        service.publishSnapshot(mapId, "dup");
        assertEquals("SNAPSHOT_EXISTS",
                assertThrows(ApiException.class, () -> service.publishSnapshot(mapId, "dup")).code());
    }

    // ---------- 并发合并 ----------

    @Test
    void concurrentDistinctUpdatesAllApplyWithDeterministicWinner() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UpdateResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return service.updateObstacle(mapId, obstacle(
                        "c-" + idx, "src-" + idx, idx, 7, 7, 7, 7, T0 + idx, 60_000L));
            }));
        }
        ready.await();
        start.countDown();

        Set<Long> seqs = new HashSet<>();
        for (Future<UpdateResponse> f : futures) {
            UpdateResponse r = f.get(10, TimeUnit.SECONDS);
            assertFalse(r.replayed());
            assertTrue(seqs.add(r.seq()), "seq 必须唯一且连续");
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(threads, seqs.size());
        for (long s = 1; s <= threads; s++) {
            assertTrue(seqs.contains(s), "seq 必须覆盖 1..N，不能丢更新");
        }
        // 确定性赢家：优先级最高的 src-7（时间戳也更新，决胜结果一致可预期）
        assertEquals("src-7", owner(query(7, 7, 7, 7), 7, 7));
        assertEquals(CostMath.LETHAL, cost(query(7, 7, 7, 7), 7, 7));
    }

    @Test
    void concurrentSameRequestIdAppliesExactlyOnce() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    UpdateResponse r = service.updateObstacle(mapId,
                            obstacle("same-id", "src", 0, 2, 2, 2, 2, T0, 60_000L));
                    if (r.replayed()) {
                        replayed.incrementAndGet();
                    } else {
                        applied.incrementAndGet();
                        assertEquals(1, r.seq());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, applied.get(), "同 requestId 并发只能应用一次");
        assertEquals(threads - 1, replayed.get(), "其余并发请求必须表现为幂等重放");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT seq FROM map_version WHERE version_id = ?", Long.class, mapId),
                "seq 只能自增一次");
    }
}
