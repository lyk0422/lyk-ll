package com.example.starter.costmap.service;

import com.example.starter.costmap.support.AbstractH2IntegrationTest;
import com.example.starter.costmap.web.ApiException;
import com.example.starter.costmap.web.ErrorCode;
import com.example.starter.costmap.web.dto.CellRegionResponse;
import com.example.starter.costmap.web.dto.CreateMapRequest;
import com.example.starter.costmap.web.dto.ObstacleRequest;
import com.example.starter.costmap.web.dto.ObstacleResponse;
import com.example.starter.costmap.web.dto.PurgeResponse;
import com.example.starter.costmap.web.dto.RegionDiffResponse;
import com.example.starter.costmap.web.dto.RegionDto;
import com.example.starter.costmap.web.dto.RestoreResponse;
import com.example.starter.costmap.web.dto.SnapshotRequest;
import com.example.starter.costmap.web.dto.SnapshotResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2（MODE=MySQL）端到端服务测试：建图、重叠障碍合并、幂等重放/409、
 * TTL 边界清理不误删新版本、快照不可变、区域差异与快照恢复。
 */
class CostmapServiceH2Test extends AbstractH2IntegrationTest {

    private static final int W = 10;
    private static final int H = 10;

    @Autowired
    private CostmapService service;

    private String createEmptyMap(String mapId) {
        CreateMapRequest req = new CreateMapRequest(mapId, W, H, 0.1d, 0.0d, 0.0d,
                1.0d, 3.0d, 2.0d, null);
        return service.createMap(req).mapId();
    }

    private ObstacleRequest obstacle(String requestId, String source, String obstacle,
                                     int priority, String kind,
                                     int x0, int y0, int x1, int y1,
                                     Long eventTime, long ttl) {
        return new ObstacleRequest(requestId, source, obstacle, priority, kind,
                new RegionDto(x0, y0, x1, y1), eventTime, ttl);
    }

    private int cost(CellRegionResponse resp, int x, int y) {
        return resp.cells().stream()
                .filter(c -> c.x() == x && c.y() == y)
                .findFirst().orElseThrow()
                .cost();
    }

    @Test
    void createMapPersistsStaticLethalAndInflation() {
        int[] staticCells = new int[W * H];
        staticCells[5 * W + 5] = 254;
        CreateMapRequest req = new CreateMapRequest("m-static", W, H, 0.1d, 0, 0,
                1.0d, 3.0d, 2.0d, staticCells);
        service.createMap(req);

        CellRegionResponse region =
                service.queryRegion("m-static", new RegionDto(5, 5, 6, 5));
        assertThat(cost(region, 5, 5)).isEqualTo(254);
        assertThat(cost(region, 6, 5)).isEqualTo(253);
    }

    @Test
    void missingMapAndInvalidRegionReturnStableErrors() {
        assertThatThrownBy(() -> service.queryRegion("nope", new RegionDto(0, 0, 1, 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MAP_NOT_FOUND));

        createEmptyMap("m-err");
        assertThatThrownBy(() -> service.queryRegion("m-err", new RegionDto(0, 0, W, 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REGION));
    }

    @Test
    void overlappingObstaclesMergeByDeterministicPolicy() {
        String mapId = createEmptyMap("m-merge");
        long t = clock().nowMillis();

        // 低优先级先到，高优先级后到，重叠格 (5,5) 由高优先级来源获胜
        service.applyObstacle(mapId, obstacle("r1", "sonar", "a", 1, "OCCUPY",
                5, 5, 5, 5, t, 100_000L));
        service.applyObstacle(mapId, obstacle("r2", "lidar", "b", 5, "OCCUPY",
                5, 5, 5, 5, t, 100_000L));

        CellRegionResponse region = service.queryRegion(mapId, new RegionDto(5, 5, 5, 5));
        assertThat(cost(region, 5, 5)).isEqualTo(254);

        // 同优先级、更晚观测的声明即使序号更大也胜（时间戳决胜）
        service.applyObstacle(mapId, obstacle("r3", "sonar", "c", 5, "OCCUPY",
                5, 5, 5, 5, t + 50_000L, 100_000L));
        region = service.queryRegion(mapId, new RegionDto(5, 5, 5, 5));
        assertThat(cost(region, 5, 5)).isEqualTo(254);
    }

    @Test
    void idempotentReplayIsNotReappliedAndDifferentParamsConflict() {
        String mapId = createEmptyMap("m-idem");
        long t = clock().nowMillis();
        ObstacleRequest first = obstacle("dup-req", "lidar", "a", 2, "OCCUPY",
                3, 3, 3, 3, t, 100_000L);

        ObstacleResponse applied = service.applyObstacle(mapId, first);
        assertThat(applied.replayed()).isFalse();
        long firstSeq = applied.statementSeq();

        // 同参重放：不分配新序号、不推进 liveSeq
        ObstacleResponse replayed = service.applyObstacle(mapId, first);
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.statementSeq()).isEqualTo(firstSeq);
        assertThat(replayed.liveSeq()).isEqualTo(applied.liveSeq());

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM obstacle_statement WHERE map_id = ? AND request_id = ?",
                Integer.class, mapId, "dup-req");
        assertThat(rowCount).isEqualTo(1);

        // 异参重放：409
        ObstacleRequest different = obstacle("dup-req", "lidar", "a", 2, "OCCUPY",
                4, 4, 4, 4, t, 100_000L);
        assertThatThrownBy(() -> service.applyObstacle(mapId, different))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void ttlPurgeRemovesOnlyExpiredVersionsNotNewerData() {
        String mapId = createEmptyMap("m-ttl");
        long t = clock().nowMillis();

        // 同一障碍物 obstacle-x：旧版本 eventTime=t, ttl=10000（t+10000 过期）；
        // 新版本 eventTime=t+5000, ttl=10000（t+15000 过期）。
        service.applyObstacle(mapId, obstacle("old-ver", "lidar", "obstacle-x", 2, "OCCUPY",
                4, 4, 4, 4, t, 10_000L));
        clock().advance(5_000L);
        service.applyObstacle(mapId, obstacle("new-ver", "lidar", "obstacle-x", 2, "OCCUPY",
                4, 4, 4, 4, t + 5_000L, 10_000L));
        assertThat(service.queryRegion(mapId, new RegionDto(4, 4, 4, 4))
                .cells().get(0).cost()).isEqualTo(254);

        // t+10000 边界：旧版本 expire_at == now，按 <= 清理；新版本仍未过期
        clock().setNow(t + 10_000L);
        PurgeResponse purge = service.purgeExpired(mapId);
        assertThat(purge.removedVersions()).isEqualTo(1);
        assertThat(service.queryRegion(mapId, new RegionDto(4, 4, 4, 4))
                .cells().get(0).cost()).isEqualTo(254);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM obstacle_statement WHERE map_id = ?", Integer.class, mapId);
        assertThat(remaining).isEqualTo(1);

        // 再次清理无过期项：liveSeq 不推进
        long liveSeqAfterPurge = service.getMap(mapId).liveSeq();
        PurgeResponse second = service.purgeExpired(mapId);
        assertThat(second.removedVersions()).isZero();
        assertThat(second.liveSeq()).isEqualTo(liveSeqAfterPurge);

        // t+15000 之后新版本才过期
        clock().setNow(t + 15_001L);
        PurgeResponse last = service.purgeExpired(mapId);
        assertThat(last.removedVersions()).isEqualTo(1);
        assertThat(service.queryRegion(mapId, new RegionDto(4, 4, 4, 4))
                .cells().get(0).cost()).isZero();
    }

    @Test
    void snapshotIsImmutableAndDiffAndRestoreWork() {
        String mapId = createEmptyMap("m-snap");
        long t = clock().nowMillis();
        service.applyObstacle(mapId, obstacle("s1", "lidar", "a", 2, "OCCUPY",
                2, 2, 2, 2, t, 1_000_000L));

        SnapshotResponse snapshot =
                service.publishSnapshot(mapId, new SnapshotRequest("snap-1", "baseline"));
        assertThat(snapshot.seq()).isEqualTo(1L);
        long snapshotLiveSeq = snapshot.liveSeq();

        // 快照发布后再更新
        service.applyObstacle(mapId, obstacle("s2", "lidar", "b", 2, "OCCUPY",
                7, 7, 7, 7, t, 1_000_000L));

        // 快照视图不受后续更新影响；差异查询能定位新障碍格
        CellRegionResponse snapView = service.querySnapshotRegion(
                mapId, "snap-1", new RegionDto(7, 7, 7, 7));
        assertThat(cost(snapView, 7, 7)).isZero();
        RegionDiffResponse diff = service.diffRegion(
                mapId, "snap-1", new RegionDto(0, 0, 9, 9));
        assertThat(diff.entries()).isNotEmpty();
        assertThat(diff.entries())
                .anyMatch(e -> e.x() == 7 && e.y() == 7 && e.snapshotCost() == 0 && e.liveCost() == 254);
        assertThat(diff.snapshotSeq()).isEqualTo(1L);
        assertThat(diff.liveSeq()).isGreaterThan(snapshotLiveSeq);

        // TTL 清理同样不影响快照：把 (2,2) 声明强制过期清理后，快照仍保留 254
        clock().setNow(t + 2_000_000L);
        service.purgeExpired(mapId);
        CellRegionResponse snapAfterPurge = service.querySnapshotRegion(
                mapId, "snap-1", new RegionDto(2, 2, 2, 2));
        assertThat(cost(snapAfterPurge, 2, 2)).isEqualTo(254);

        // 从 H2 保存的快照恢复当前查询视图
        RestoreResponse restored = service.restoreSnapshot(mapId, "snap-1");
        assertThat(restored.restoredLiveSeq()).isEqualTo(snapshotLiveSeq);
        assertThat(restored.restoredStatementCount()).isEqualTo(1L);
        CellRegionResponse liveAfterRestore =
                service.queryRegion(mapId, new RegionDto(2, 2, 7, 7));
        assertThat(cost(liveAfterRestore, 2, 2)).isEqualTo(254);
        assertThat(cost(liveAfterRestore, 7, 7)).isZero();

        // 恢复后新语句序号在原高水位之上单调递增
        clock().setNow(t + 3_000_000L);
        ObstacleResponse afterRestore = service.applyObstacle(mapId,
                obstacle("s3", "lidar", "c", 2, "OCCUPY", 8, 8, 8, 8,
                        t + 3_000_000L, 1_000_000L));
        assertThat(afterRestore.statementSeq()).isGreaterThan(2L);
    }

    @Test
    void purgeAfterRestoreRemovesExpiredArchivedStatementButSnapshotStaysIntact() {
        String mapId = createEmptyMap("m-restore-ttl");
        long t = clock().nowMillis();
        service.applyObstacle(mapId, obstacle("a1", "lidar", "a", 2, "OCCUPY",
                3, 3, 3, 3, t, 10_000L));
        service.publishSnapshot(mapId, new SnapshotRequest("snap-old", null));

        // 推进到过期后清理，再恢复旧快照（存档语句此刻已过期）
        clock().setNow(t + 20_000L);
        service.purgeExpired(mapId);
        service.restoreSnapshot(mapId, "snap-old");
        assertThat(service.queryRegion(mapId, new RegionDto(3, 3, 3, 3))
                .cells().get(0).cost()).isEqualTo(254);

        // 清理恢复回来的过期语句：当前视图回到 0，但不可变快照仍为 254
        PurgeResponse purge = service.purgeExpired(mapId);
        assertThat(purge.removedVersions()).isEqualTo(1);
        assertThat(service.queryRegion(mapId, new RegionDto(3, 3, 3, 3))
                .cells().get(0).cost()).isZero();
        CellRegionResponse snap = service.querySnapshotRegion(
                mapId, "snap-old", new RegionDto(3, 3, 3, 3));
        assertThat(cost(snap, 3, 3)).isEqualTo(254);
    }

    @Test
    void duplicateMapAndSnapshotIdsConflict() {
        createEmptyMap("m-dup");
        assertThatThrownBy(() -> createEmptyMap("m-dup"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));

        service.publishSnapshot("m-dup", new SnapshotRequest("snap-x", null));
        assertThatThrownBy(() -> service.publishSnapshot("m-dup",
                        new SnapshotRequest("snap-x", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
    }
}
