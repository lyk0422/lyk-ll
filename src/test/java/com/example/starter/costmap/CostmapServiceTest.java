package com.example.starter.costmap;

import com.example.starter.costmap.dto.CleanupResponse;
import com.example.starter.costmap.dto.CreateMapRequest;
import com.example.starter.costmap.dto.DiffResponse;
import com.example.starter.costmap.dto.MapView;
import com.example.starter.costmap.dto.ObstacleUpdateRequest;
import com.example.starter.costmap.dto.ObstacleUpdateResponse;
import com.example.starter.costmap.dto.SnapshotView;
import com.example.starter.costmap.error.CostmapException;
import com.example.starter.costmap.error.ErrorCode;
import com.example.starter.costmap.model.Region;
import com.example.starter.costmap.repo.ObstacleUpdateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代价地图服务测试：基于真实 H2（MySQL 兼容模式）验证主流程、失败分支、
 * TTL 边界、幂等与并发合并；时间相关场景使用可控时钟。
 */
@SpringBootTest
class CostmapServiceTest {

    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.utc("2026-09-23T00:00:00Z");
        }
    }

    @Autowired
    private CostmapService service;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObstacleUpdateRepository updates;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM obstacle_update");
        jdbc.update("DELETE FROM obstacle_request");
        jdbc.update("DELETE FROM costmap_snapshot");
    }

    private MapView createMap(int w, int h, double resolution, double inflationRadius) {
        return service.createMap(new CreateMapRequest(
                UUID.randomUUID().toString(), w, h, resolution, inflationRadius, null));
    }

    private ObstacleUpdateRequest obstacle(String requestId, String source, int priority,
                                           String key, int x, int y, int w, int h,
                                           int[] cells, long ts, Long ttlMillis) {
        return new ObstacleUpdateRequest(requestId, source, priority, key,
                new Region(x, y, w, h), cells, ts, ttlMillis);
    }

    private int cellAt(MapView view, int x, int y) {
        return view.grid()[y * view.width() + x];
    }

    @Test
    void createMapAppliesStaticLayerInflationAsVersionZero() {
        int[] staticGrid = new int[100];
        staticGrid[55] = 254; // (5,5)
        MapView view = service.createMap(new CreateMapRequest(
                UUID.randomUUID().toString(), 10, 10, 1.0, 2.0, staticGrid));
        assertEquals(0, view.version());
        assertEquals(254, cellAt(view, 5, 5));
        assertEquals(169, cellAt(view, 5, 6));
        assertEquals(0, cellAt(view, 0, 0));
    }

    @Test
    void createMapRejectsInvalidDimensions() {
        CostmapException ex = assertThrows(CostmapException.class, () ->
                service.createMap(new CreateMapRequest("m-bad", 0, 10, 1.0, 1.0, null)));
        assertEquals(ErrorCode.INVALID_REQUEST, ex.code());
    }

    @Test
    void obstacleUpdateRecomputesOnlyAffectedRegion() {
        MapView map = createMap(10, 10, 1.0, 1.0);
        ObstacleUpdateResponse response = service.applyObstacle(map.mapId(),
                obstacle("r1", "lidar", 0, null, 2, 2, 2, 1, new int[]{254, 254}, 1000, null));
        assertEquals(1, response.version());
        assertFalse(response.replayed());
        // 影响区域 = 更新区域外扩 1 格膨胀半径
        assertEquals(new Region(1, 1, 4, 3), response.affectedRegion());
        MapView view = service.currentView(map.mapId());
        assertEquals(254, cellAt(view, 2, 2));
        assertEquals(254, cellAt(view, 3, 2));
        assertEquals(127, cellAt(view, 2, 1)); // 膨胀邻域 d=1
        assertEquals(0, cellAt(view, 1, 1));   // 角点 d=sqrt(2) 超出 R=1
        assertEquals(0, cellAt(view, 9, 9));   // 影响区域外不变
        assertEquals(1, updates.count(map.mapId()));
    }

    @Test
    void overlappingObstaclesMergeDeterministically() {
        MapView map = createMap(10, 10, 1.0, 0.0);
        // 低优先级先到达、高优先级后到达，结果与顺序无关
        service.applyObstacle(map.mapId(),
                obstacle("r1", "lidar", 1, null, 4, 4, 1, 1, new int[]{254}, 100, null));
        service.applyObstacle(map.mapId(),
                obstacle("r2", "camera", 2, null, 4, 4, 1, 1, new int[]{100}, 50, null));
        MapView view = service.currentView(map.mapId());
        assertEquals(100, cellAt(view, 4, 4));
    }

    @Test
    void sameRequestIdWithSameParamsReplaysWithoutReapplying() {
        MapView map = createMap(10, 10, 1.0, 0.0);
        ObstacleUpdateRequest req = obstacle("r1", "lidar", 0, null, 1, 1, 1, 1,
                new int[]{254}, 100, null);
        ObstacleUpdateResponse first = service.applyObstacle(map.mapId(), req);
        ObstacleUpdateResponse replay = service.applyObstacle(map.mapId(), req);
        assertTrue(replay.replayed());
        assertEquals(first.version(), replay.version());
        assertEquals(1, service.currentView(map.mapId()).version());
        assertEquals(1, updates.count(map.mapId()));
    }

    @Test
    void sameRequestIdWithDifferentParamsReturnsConflict() {
        MapView map = createMap(10, 10, 1.0, 0.0);
        service.applyObstacle(map.mapId(),
                obstacle("r1", "lidar", 0, null, 1, 1, 1, 1, new int[]{254}, 100, null));
        CostmapException ex = assertThrows(CostmapException.class, () ->
                service.applyObstacle(map.mapId(),
                        obstacle("r1", "lidar", 0, null, 2, 2, 1, 1, new int[]{254}, 100, null)));
        assertEquals(ErrorCode.REQUEST_CONFLICT, ex.code());
    }

    @Test
    void ttlBoundaryExpiresAtExactExpiryInstant() {
        MapView map = createMap(10, 10, 1.0, 0.0);
        service.applyObstacle(map.mapId(),
                obstacle("r1", "lidar", 0, null, 3, 3, 1, 1, new int[]{254}, 100, 1000L));

        clock.advanceMillis(999);
        CleanupResponse before = service.cleanup(map.mapId());
        assertEquals(0, before.removed());
        assertEquals(254, cellAt(service.currentView(map.mapId()), 3, 3));

        // 到达过期时刻（expiresAt <= now）即清理
        clock.advanceMillis(1);
        CleanupResponse at = service.cleanup(map.mapId());
        assertEquals(1, at.removed());
        assertEquals(0, cellAt(service.currentView(map.mapId()), 3, 3));

        CleanupResponse after = service.cleanup(map.mapId());
        assertEquals(0, after.removed());
        assertEquals(at.version(), after.version());
    }

    @Test
    void cleanupOnlyRemovesExpiredRecordsAndKeepsNewerData() {
        MapView map = createMap(10, 10, 1.0, 0.0);
        service.applyObstacle(map.mapId(),
                obstacle("r1", "a", 0, null, 1, 1, 1, 1, new int[]{200}, 100, 500L));
        service.applyObstacle(map.mapId(),
                obstacle("r2", "b", 0, null, 5, 5, 1, 1, new int[]{201}, 100, 5000L));
        clock.advanceMillis(1000);
        CleanupResponse cleanup = service.cleanup(map.mapId());
        assertEquals(1, cleanup.removed());
        MapView view = service.currentView(map.mapId());
        assertEquals(0, cellAt(view, 1, 1));
        assertEquals(201, cellAt(view, 5, 5));
    }

    @Test
    void obstacleKeyReplacementKeepsNewerRecordSafeFromCleanup() {
        MapView map = createMap(10, 10, 1.0, 0.0);
        service.applyObstacle(map.mapId(),
                obstacle("r1", "s", 0, "k", 4, 4, 1, 1, new int[]{200}, 100, 1000L));
        // 同 (source, obstacleKey) 新更新替换旧记录，过期时刻更晚
        service.applyObstacle(map.mapId(),
                obstacle("r2", "s", 0, "k", 4, 4, 1, 1, new int[]{250}, 200, 5000L));
        clock.advanceMillis(2000); // 旧记录原本已过期，新记录未过期
        CleanupResponse cleanup = service.cleanup(map.mapId());
        assertEquals(0, cleanup.removed());
        assertEquals(250, cellAt(service.currentView(map.mapId()), 4, 4));
    }

    @Test
    void snapshotIsImmutableAgainstLaterUpdatesAndTtlCleanup() {
        MapView map = createMap(10, 10, 1.0, 0.0);
        service.applyObstacle(map.mapId(),
                obstacle("r1", "lidar", 0, null, 2, 2, 1, 1, new int[]{254}, 100, 1000L));
        SnapshotView snapshot = service.publishSnapshot(map.mapId(), null);
        assertEquals(1, snapshot.version());
        assertEquals(254, snapshot.grid()[2 * 10 + 2]);

        // 后续更新与 TTL 清理不影响已发布快照
        service.applyObstacle(map.mapId(),
                obstacle("r2", "lidar", 0, null, 7, 7, 1, 1, new int[]{254}, 200, null));
        clock.advanceMillis(2000);
        service.cleanup(map.mapId());

        SnapshotView reloaded = service.snapshot(map.mapId(), 1);
        assertEquals(254, reloaded.grid()[2 * 10 + 2]);
        assertEquals(0, cellAt(service.currentView(map.mapId()), 2, 2));
    }

    @Test
    void duplicateSnapshotPublishReturnsConflict() {
        MapView map = createMap(10, 10, 1.0, 0.0);
        service.publishSnapshot(map.mapId(), null);
        CostmapException ex = assertThrows(CostmapException.class, () ->
                service.publishSnapshot(map.mapId(), null));
        assertEquals(ErrorCode.SNAPSHOT_CONFLICT, ex.code());
    }

    @Test
    void restoreSnapshotFromH2ReplacesQueryView() {
        MapView map = createMap(10, 10, 1.0, 0.0);
        service.applyObstacle(map.mapId(),
                obstacle("r1", "lidar", 0, null, 2, 2, 1, 1, new int[]{254}, 100, null));
        service.publishSnapshot(map.mapId(), null);
        service.applyObstacle(map.mapId(),
                obstacle("r2", "lidar", 0, null, 5, 5, 1, 1, new int[]{254}, 200, null));
        assertEquals(2, service.currentView(map.mapId()).version());

        MapView restored = service.restoreSnapshot(map.mapId(), 1);
        assertEquals(3, restored.version());
        assertEquals(254, cellAt(restored, 2, 2));
        assertEquals(0, cellAt(restored, 5, 5));
        // 恢复视图与 H2 中快照内容一致
        SnapshotView snapshot = service.snapshot(map.mapId(), 1);
        for (int i = 0; i < snapshot.grid().length; i++) {
            assertEquals(snapshot.grid()[i], restored.grid()[i], "grid mismatch at " + i);
        }
    }

    @Test
    void missingSnapshotReturnsNotFound() {
        MapView map = createMap(10, 10, 1.0, 0.0);
        CostmapException ex = assertThrows(CostmapException.class, () ->
                service.snapshot(map.mapId(), 42));
        assertEquals(ErrorCode.SNAPSHOT_NOT_FOUND, ex.code());
    }

    @Test
    void diffReportsOnlyChangedCellsWithinRegion() {
        MapView map = createMap(10, 10, 1.0, 1.0);
        service.applyObstacle(map.mapId(),
                obstacle("r1", "lidar", 0, null, 5, 5, 1, 1, new int[]{254}, 100, null));

        DiffResponse full = service.diff(map.mapId(), 0, 1, null);
        assertFalse(full.changes().isEmpty());
        assertTrue(full.changes().stream()
                .anyMatch(c -> c.x() == 5 && c.y() == 5 && c.fromCost() == 0 && c.toCost() == 254));
        // 膨胀边界 d=1 的格子也在差异中
        assertTrue(full.changes().stream()
                .anyMatch(c -> c.x() == 5 && c.y() == 6 && c.toCost() == 127));

        // 远离更新区域的子区域无差异
        DiffResponse corner = service.diff(map.mapId(), 0, 1, new Region(0, 0, 2, 2));
        assertTrue(corner.changes().isEmpty());
    }

    @Test
    void diffRejectsInvalidRegionAndMissingVersion() {
        MapView map = createMap(10, 10, 1.0, 0.0);
        CostmapException regionEx = assertThrows(CostmapException.class, () ->
                service.diff(map.mapId(), 0, 0, new Region(8, 8, 5, 5)));
        assertEquals(ErrorCode.INVALID_REGION, regionEx.code());

        CostmapException versionEx = assertThrows(CostmapException.class, () ->
                service.diff(map.mapId(), 0, 9, null));
        assertEquals(ErrorCode.VERSION_NOT_FOUND, versionEx.code());
    }

    @Test
    void missingMapAndInvalidRegionReturnStableErrors() {
        CostmapException mapEx = assertThrows(CostmapException.class, () ->
                service.currentView("no-such-map"));
        assertEquals(ErrorCode.MAP_NOT_FOUND, mapEx.code());

        MapView map = createMap(10, 10, 1.0, 0.0);
        CostmapException regionEx = assertThrows(CostmapException.class, () ->
                service.applyObstacle(map.mapId(),
                        obstacle("r1", "s", 0, null, 9, 9, 2, 2, new int[]{1, 2, 3, 4}, 100, null)));
        assertEquals(ErrorCode.INVALID_REGION, regionEx.code());

        CostmapException versionEx = assertThrows(CostmapException.class, () ->
                service.versionView(map.mapId(), 7));
        assertEquals(ErrorCode.VERSION_NOT_FOUND, versionEx.code());
    }

    @Test
    void concurrentUpdatesOnSameCellMergeDeterministically() throws Exception {
        MapView map = createMap(10, 10, 1.0, 0.0);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ObstacleUpdateResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.applyObstacle(map.mapId(),
                        obstacle("req-" + n, "src-" + n, n, null, 5, 5, 1, 1,
                                new int[]{10 + n}, 1000, null));
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<ObstacleUpdateResponse> future : futures) {
            assertFalse(future.get(30, TimeUnit.SECONDS).replayed());
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        MapView view = service.currentView(map.mapId());
        // 优先级最大者（n=7）胜出，与到达顺序无关
        assertEquals(17, cellAt(view, 5, 5));
        assertEquals(threads, view.version());
        assertEquals(threads, updates.count(map.mapId()));
    }

    @Test
    void concurrentReplayOfSameRequestAppliesOnlyOnce() throws Exception {
        MapView map = createMap(10, 10, 1.0, 0.0);
        ObstacleUpdateRequest req = obstacle("req-shared", "lidar", 0, null, 3, 3, 1, 1,
                new int[]{254}, 100, null);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ObstacleUpdateResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.applyObstacle(map.mapId(), req);
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        long version = -1;
        int applied = 0;
        for (Future<ObstacleUpdateResponse> future : futures) {
            ObstacleUpdateResponse response = future.get(30, TimeUnit.SECONDS);
            if (version == -1) {
                version = response.version();
            }
            assertEquals(version, response.version());
            if (!response.replayed()) {
                applied++;
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, applied);
        assertEquals(1, updates.count(map.mapId()));
        assertEquals(1, service.currentView(map.mapId()).version());
        assertNotEquals(0, cellAt(service.currentView(map.mapId()), 3, 3));
    }
}
