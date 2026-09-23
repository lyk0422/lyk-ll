package com.example.starter.costmap.cost;

import com.example.starter.costmap.ConflictException;
import com.example.starter.costmap.CostmapClock;
import com.example.starter.costmap.InvalidRegionException;
import com.example.starter.costmap.VersionNotFoundException;
import com.example.starter.costmap.domain.CellCost;
import com.example.starter.costmap.domain.CellView;
import com.example.starter.costmap.domain.ObstacleInput;
import com.example.starter.costmap.domain.Region;
import com.example.starter.costmap.domain.SourceType;
import com.example.starter.costmap.domain.UpdateResult;
import com.example.starter.costmap.repo.CostmapRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于嵌入式 H2（MODE=MySQL）的端到端业务测试：覆盖静态层、重叠障碍合并、
 * TTL 边界与清理、区域增量取数、幂等、版本错误、快照差异与 H2 恢复视图。
 */
@SpringBootTest
class CostmapServiceH2Test {

    private static final String MAP = "map-h2";

    @Autowired
    private CostmapService service;
    @Autowired
    private CostmapRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CostmapClock clock;
    @Autowired
    private PlatformTransactionManager txManager;

    private long t;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM costmap_snapshot_cell");
        jdbc.update("DELETE FROM costmap_snapshot");
        jdbc.update("DELETE FROM costmap_obstacle");
        jdbc.update("DELETE FROM costmap_idempotency");
        jdbc.update("DELETE FROM costmap_static_cell");
        jdbc.update("DELETE FROM costmap_version");
        t = 1_000L;
        clock.setSource(() -> t);
    }

    private void createDefaultMap() {
        service.createMap(new CostmapService.CreateMapCommand(
                MAP, 10, 10, 0.1, 0.0, 0.0, 2, 0.5,
                List.of(new CellCost(0, 0, 100), new CellCost(9, 9, 50))));
    }

    private ObstacleInput input(String requestId, String sourceId, SourceType type,
                                Region region, int cost, long eventTime, Long ttl,
                                Long expectedVersion) {
        return new ObstacleInput(requestId, sourceId, type, region, cost,
                eventTime, ttl, expectedVersion);
    }

    private ObstacleInput simple(String requestId, String sourceId, SourceType type,
                                 int x, int y, int cost, Long ttl) {
        return input(requestId, sourceId, type, new Region(x, y, x + 1, y + 1),
                cost, t, ttl, null);
    }

    private static CellView.CellViewItem cell(CellView view, int x, int y) {
        return view.cells().stream()
                .filter(c -> c.x() == x && c.y() == y)
                .findFirst()
                .orElseThrow(() -> new AssertionError("cell not in view: " + x + "," + y));
    }

    @Test
    @DisplayName("静态格栅不可变且致命格参与膨胀")
    void staticMapAndStaticInflation() {
        createDefaultMap();
        CellView view = service.viewRegion(MAP, new Region(0, 0, 3, 1));

        assertEquals(100, cell(view, 0, 0).finalCost());
        assertEquals(50, cell(view, 1, 0).inflatedCost(), "静态致命格 1 格处膨胀 50");
        assertEquals(50, cell(view, 1, 0).finalCost());
        assertEquals(25, cell(view, 2, 0).inflatedCost(), "静态致命格 2 格处膨胀 25");
        assertNull(cell(view, 1, 0).winnerSourceId(), "膨胀格无动态获胜来源");
    }

    @Test
    @DisplayName("多来源同格按优先级、时间戳、来源 ID 决胜")
    void overlappingObstaclesMergeDeterministically() {
        createDefaultMap();

        // 高代价但低优先级的传感器，不能压过 SAFETY
        service.updateObstacle(MAP, simple("r1", "sensor-a", SourceType.SENSOR, 5, 5, 100, null));
        service.updateObstacle(MAP, simple("r2", "safe-a", SourceType.SAFETY, 5, 5, 60, null));

        CellView view = service.viewRegion(MAP, new Region(5, 5, 6, 6));
        CellView.CellViewItem winner = cell(view, 5, 5);
        assertEquals(60, winner.obstacleCost());
        assertEquals("safe-a", winner.winnerSourceId());
        assertEquals("SAFETY", winner.winnerType());
        assertEquals(60, winner.finalCost(), "获胜障碍代价 60，低优先级传感器不得叠加");

        // 同优先级新时间戳者胜
        service.updateObstacle(MAP, simple("r3", "sensor-b", SourceType.SENSOR, 6, 5, 70, null));
        CellView v2 = service.viewRegion(MAP, new Region(6, 5, 7, 6));
        assertEquals("sensor-b", cell(v2, 6, 5).winnerSourceId());
    }

    @Test
    @DisplayName("同优先级同时间戳按来源 ID 字典序决胜")
    void tieBreaksBySourceId() {
        createDefaultMap();
        t = 5_000L;
        service.updateObstacle(MAP, simple("r1", "zzz", SourceType.GENERIC, 4, 4, 80, null));
        service.updateObstacle(MAP, simple("r2", "aaa", SourceType.GENERIC, 4, 4, 90, null));

        CellView view = service.viewRegion(MAP, new Region(4, 4, 5, 5));
        assertEquals("aaa", cell(view, 4, 4).winnerSourceId());
        assertEquals(90, cell(view, 4, 4).obstacleCost());
    }

    @Test
    @DisplayName("同一 requestId 同参重放不重复应用")
    void sameRequestReplayIsIdempotent() {
        createDefaultMap();
        ObstacleInput first = new ObstacleInput("r1", "sensor-a", SourceType.SENSOR,
                new Region(7, 7, 8, 8), 100, 1_000L, null, null);

        UpdateResult applied = service.updateObstacle(MAP, first);
        UpdateResult replay = service.updateObstacle(MAP, first);

        assertFalse(applied.replay());
        assertTrue(replay.replay());
        assertEquals(applied.revision(), replay.revision());

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM costmap_obstacle WHERE request_id = 'r1'",
                Integer.class);
        assertEquals(1, rowCount, "重放不得重复写入障碍行");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM costmap_idempotency WHERE request_id = 'r1'",
                Integer.class);
        assertEquals(1, idemCount);
    }

    @Test
    @DisplayName("同一 requestId 异参重放返回 409")
    void sameRequestDifferentParamsConflicts() {
        createDefaultMap();
        service.updateObstacle(MAP, new ObstacleInput("r9", "sensor-a", SourceType.SENSOR,
                new Region(7, 7, 8, 8), 100, 1_000L, null, null));

        ObstacleInput different = new ObstacleInput("r9", "sensor-a", SourceType.SENSOR,
                new Region(7, 7, 8, 8), 80, 1_000L, null, null);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.updateObstacle(MAP, different));
        assertEquals("IDEMPOTENCY_CONFLICT", ex.code());
    }

    @Test
    @DisplayName("TTL 半开区间边界：expires_at 时刻即过期，清理不误删更晚数据")
    void ttlBoundaryAndSafeCleanup() {
        createDefaultMap();
        // t=1000，TTL 500 → expires_at=1500
        service.updateObstacle(MAP, simple("ttl1", "sensor-a", SourceType.SENSOR, 3, 3, 100, 500L));

        t = 1_499;
        assertEquals(100, cell(service.viewRegion(MAP, new Region(3, 3, 4, 4)), 3, 3)
                .obstacleCost(), "过期前一刻仍存活");

        t = 1_500;
        assertNull(cell(service.viewRegion(MAP, new Region(3, 3, 4, 4)), 3, 3)
                .obstacleCost(), "expires_at 时刻恰好过期");

        // 同一来源更晚的更新（3200 才过期），另一来源更远期数据
        t = 1_200;
        service.updateObstacle(MAP, simple("ttl2", "sensor-a", SourceType.SENSOR, 3, 3, 90, 2_000L));
        service.updateObstacle(MAP, simple("ttl3", "sensor-b", SourceType.SENSOR, 4, 4, 70, 10_000L));

        t = 1_600;
        int deleted = service.cleanupExpired(MAP);
        assertTrue(deleted >= 1, "清理应物理删除 rev1 旧版本，实际 " + deleted);
        Integer oldHistory = jdbc.queryForObject(
                "SELECT COUNT(*) FROM costmap_obstacle WHERE request_id = 'ttl1'", Integer.class);
        assertEquals(0, oldHistory, "同一障碍的过期旧版本应被删除");

        CellView after = service.viewRegion(MAP, new Region(3, 3, 5, 5));
        assertEquals(90, cell(after, 3, 3).obstacleCost(), "更晚到期的 rev2 不得被误删");
        assertEquals(70, cell(after, 4, 4).obstacleCost(), "其他来源更晚数据不得被误删");

        t = 3_200;
        assertNull(cell(service.viewRegion(MAP, new Region(3, 3, 4, 4)), 3, 3)
                .obstacleCost(), "rev2 到期后失效");
        int deleted2 = service.cleanupExpired(MAP);
        assertTrue(deleted2 >= 1);
        Integer rev2Rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM costmap_obstacle WHERE request_id = 'ttl2'", Integer.class);
        assertEquals(0, rev2Rows);
    }

    @Test
    @DisplayName("障碍更新只写受影响格且膨胀按查询区域+半径局部重算")
    void incrementalRegionRecompute() {
        createDefaultMap();
        // 只更新 (5,5) 一个格
        service.updateObstacle(MAP, simple("inc1", "sensor-a", SourceType.SENSOR, 5, 5, 100, null));

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM costmap_obstacle WHERE map_id = ?", Integer.class, MAP);
        assertEquals(1, rows, "只重算/写入受影响区域，不重建整图 100 格");

        // 远离障碍与静态墙的区域完全无代价（(2..3,7..8) 距 (5,5) 超过 2 格半径）
        CellView far = service.viewRegion(MAP, new Region(2, 7, 4, 9));
        assertTrue(far.cells().stream().allMatch(c -> c.finalCost() == 0),
                "半径外区域不应有膨胀代价");

        // 边缘区域也能正确收到局部膨胀：(3,5) 距 (5,5) 恰好 2 格
        CellView edge = service.viewRegion(MAP, new Region(3, 5, 5, 6));
        assertEquals(25, cell(edge, 3, 5).inflatedCost());
        assertEquals(50, cell(edge, 4, 5).inflatedCost());
        assertNull(cell(edge, 3, 5).obstacleCost());
    }

    @Test
    @DisplayName("矩形区域更新覆盖其中每一格")
    void rectangularUpdateCoversAllCells() {
        createDefaultMap();
        service.updateObstacle(MAP, input("rect1", "sensor-a", SourceType.SENSOR,
                new Region(2, 2, 4, 4), 100, 1_000L, null, null));

        CellView view = service.viewRegion(MAP, new Region(2, 2, 4, 4));
        assertEquals(4, view.cells().size());
        assertTrue(view.cells().stream().allMatch(c -> c.obstacleCost() == 100));
    }

    @Test
    @DisplayName("地图缺失 404、非法区域 400、版本冲突 409")
    void stableErrorsForMissingMapInvalidRegionAndVersionConflict() {
        createDefaultMap();

        VersionNotFoundException notFound = assertThrows(VersionNotFoundException.class,
                () -> service.viewRegion("no-such-map", new Region(0, 0, 1, 1)));
        assertEquals("VERSION_NOT_FOUND", notFound.code());

        InvalidRegionException reversed = assertThrows(InvalidRegionException.class,
                () -> service.updateObstacle(MAP, input("bad1", "s", SourceType.SENSOR,
                        new Region(5, 5, 2, 2), 10, 1L, null, null)));
        assertEquals("INVALID_REGION", reversed.code());

        InvalidRegionException outside = assertThrows(InvalidRegionException.class,
                () -> service.viewRegion(MAP, new Region(8, 8, 12, 12)));
        assertEquals("INVALID_REGION", outside.code());

        ConflictException versionConflict = assertThrows(ConflictException.class,
                () -> service.updateObstacle(MAP, input("bad2", "s", SourceType.SENSOR,
                        new Region(1, 1, 2, 2), 10, 1L, null, 99L)));
        assertEquals("VERSION_CONFLICT", versionConflict.code());
    }

    @Test
    @DisplayName("重复创建地图与重复快照 ID 返回 409")
    void duplicateMapAndSnapshotConflict() {
        createDefaultMap();
        assertThrows(ConflictException.class, this::createDefaultMap);

        service.publishSnapshot(MAP, "snap-dup");
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.publishSnapshot(MAP, "snap-dup"));
        assertEquals("SNAPSHOT_EXISTS", ex.code());
    }

    @Test
    @DisplayName("快照发布后不受后续更新与 TTL 清理影响，并支持差异查询与 H2 恢复")
    void snapshotImmutabilityDiffAndRecovery() {
        createDefaultMap();
        service.updateObstacle(MAP, simple("o1", "sensor-a", SourceType.SENSOR, 5, 5, 100, null));
        CostmapService.SnapshotInfo snap1 = service.publishSnapshot(MAP, "snap-1");
        assertEquals(1L, snap1.mapVersion());

        // 发布后：新增障碍 + 旧障碍被同来源更新取代并 TTL 过期清理
        service.updateObstacle(MAP, simple("o2", "sensor-b", SourceType.SENSOR, 6, 5, 100, null));
        service.updateObstacle(MAP, input("o3", "sensor-a", SourceType.SENSOR,
                new Region(5, 5, 6, 6), 100, 2_000L, 100L, null));
        t = 5_000;
        service.cleanupExpired(MAP);

        // 快照视图保持发布时刻内容
        CellView snapView = service.viewSnapshotRegion("snap-1", new Region(5, 5, 7, 6));
        assertEquals(100, cell(snapView, 5, 5).obstacleCost());
        assertNull(cell(snapView, 6, 5).obstacleCost(), "后续新增障碍不得出现在旧快照");

        // 快照-实时差异
        CostmapService.RegionDiff diffLive = service.diffRegion(
                MAP, "snap-1", null, new Region(5, 5, 7, 6));
        Map<String, CostmapService.RegionDiff.DiffCell> diffMap = new HashMap<>();
        diffLive.changedCells().forEach(d -> diffMap.put(d.x() + "," + d.y(), d));
        // 发布时 (6,5) 只收到 (5,5) 障碍 1 格外的膨胀代价 50；之后变为真实障碍 100
        assertEquals(50, diffMap.get("6,5").fromCost());
        assertEquals(100, diffMap.get("6,5").toCost());
        assertEquals("LIVE@1", diffLive.toSnapshot());

        // 第二个快照与快照-快照差异
        CostmapService.SnapshotInfo snap2 = service.publishSnapshot(MAP, "snap-2");
        assertNotNull(snap2);
        CostmapService.RegionDiff diffSnaps = service.diffRegion(
                MAP, "snap-1", "snap-2", new Region(5, 5, 7, 6));
        assertTrue(diffSnaps.changedCells().stream()
                .anyMatch(d -> d.x() == 6 && d.y() == 5));

        // 不存在的快照 → 404
        assertThrows(VersionNotFoundException.class,
                () -> service.viewSnapshotRegion("nope", new Region(0, 0, 1, 1)));

        // 同一 JVM 内从 H2 恢复查询视图：用新的服务实例读取已持久化快照
        CostmapClock recoveredClock = new CostmapClock();
        recoveredClock.setSource(() -> t);
        CostmapService recovered = new CostmapServiceImpl(repository, recoveredClock, txManager);
        CellView recoveredView = recovered.viewSnapshotRegion("snap-1", new Region(5, 5, 6, 6));
        assertEquals(1L, recoveredView.version());
        assertEquals(100, cell(recoveredView, 5, 5).finalCost());
    }

    @Test
    @DisplayName("不同来源并发更新同一格：全部成功且按确定性规则合并")
    void concurrentDifferentSourcesSameCell() throws Exception {
        createDefaultMap();
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<UpdateResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            String source = String.format("s%02d", i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return service.updateObstacle(MAP,
                        simple("c-" + source, source, SourceType.SAFETY, 5, 5, 50, null));
            }));
        }
        ready.await();
        start.countDown();
        for (Future<UpdateResult> f : futures) {
            assertNotNull(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        CellView view = service.viewRegion(MAP, new Region(5, 5, 6, 6));
        // 同类型同时间戳 → sourceId 字典序最小者胜
        assertEquals("s00", cell(view, 5, 5).winnerSourceId());
        Integer liveRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM costmap_obstacle WHERE x = 5 AND y = 5 AND superseded = 0",
                Integer.class);
        assertEquals(8, liveRows, "每个来源各自保留一条存活记录，查询时再决胜");
    }

    @Test
    @DisplayName("同一来源并发更新同一格：revision 串行化且只有一条存活记录")
    void concurrentSameSourceSameCellSerializesRevision() throws Exception {
        createDefaultMap();
        int threads = 10;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<UpdateResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return service.updateObstacle(MAP, simple(
                        "same-" + idx, "only-source", SourceType.SENSOR, 5, 5, 10 + idx, null));
            }));
        }
        ready.await();
        start.countDown();
        TreeSet<Long> revisions = new TreeSet<>();
        for (Future<UpdateResult> f : futures) {
            revisions.add(f.get(30, TimeUnit.SECONDS).revision());
        }
        pool.shutdown();

        assertEquals(threads, revisions.size(), "每次成功更新获得唯一单调 revision");
        Integer live = jdbc.queryForObject(
                "SELECT COUNT(*) FROM costmap_obstacle "
                        + "WHERE source_id = 'only-source' AND x = 5 AND y = 5 AND superseded = 0",
                Integer.class);
        assertEquals(1, live, "旧 revision 全部被取代，只剩最新一条存活");
        assertEquals((long) threads, revisions.last(), "最大 revision 等于成功更新次数");
    }
}
