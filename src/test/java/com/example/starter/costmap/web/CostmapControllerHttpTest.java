package com.example.starter.costmap.web;

import com.example.starter.costmap.support.TimeSource;
import com.example.starter.costmap.web.dto.CellChange;
import com.example.starter.costmap.web.dto.CreateMapRequest;
import com.example.starter.costmap.web.dto.MapResponse;
import com.example.starter.costmap.web.dto.ObstacleUpdateRequest;
import com.example.starter.costmap.web.dto.RegionCostResponse;
import com.example.starter.costmap.web.dto.RegionRequest;
import com.example.starter.costmap.web.dto.RestoreViewResponse;
import com.example.starter.costmap.web.dto.SnapshotResponse;
import com.example.starter.costmap.web.dto.UpdateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端 HTTP 测试（真实 Web 容器 + H2）：稳定错误码结构、完整合成业务链路
 * （建图 → 障碍更新 → 区域查询 → 快照 → 更新/清理 → 差异 → 恢复视图查询）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CostmapControllerHttpTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TimeSource timeSource;

    private String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
        jdbc.update("DELETE FROM snapshot_cell");
        jdbc.update("DELETE FROM costmap_snapshot");
        jdbc.update("DELETE FROM dynamic_cost");
        jdbc.update("DELETE FROM obstacle_cell");
        jdbc.update("DELETE FROM obstacle_update");
        jdbc.update("DELETE FROM map_version");
        timeSource.setFixed(1_000_000L);
    }

    @AfterEach
    void tearDown() {
        timeSource.reset();
    }

    @Test
    void fullSyntheticWorkflowOverHttp() {
        // 1) 创建 8x6 地图，分辨率 0.5 米，膨胀半径 1.0 米（=2 格），衰减 1.0
        ResponseEntity<MapResponse> created = rest.postForEntity(base + "/api/maps",
                new CreateMapRequest("m-http", 8, 6, 0.5, 1.0, 1.0, null), MapResponse.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals("m-http", created.getBody().versionId());

        String obstacleUrl = base + "/api/maps/m-http/obstacles";

        // 2) 合成障碍物更新：激光雷达上报 (3,2)-(4,3) 矩形障碍
        ObstacleUpdateRequest lidar = new ObstacleUpdateRequest(
                "req-1001", "lidar-front", 10, 3, 2, 4, 3, 1_000_000L, 60_000L, null);
        ResponseEntity<UpdateResponse> u1 = rest.postForEntity(obstacleUrl, lidar, UpdateResponse.class);
        assertEquals(HttpStatus.OK, u1.getStatusCode());
        assertFalse(u1.getBody().replayed());
        assertEquals(1, u1.getBody().seq());
        // 增量盒 = 区域 (3,2)-(4,3) 外扩 2 格并裁剪 → (1,0)-(6,5)，共 36 格，非整图 48 格
        assertEquals(36, u1.getBody().recomputedCells());

        // 3) 同 requestId 同参重放：200 且 replayed=true，seq 不变
        ResponseEntity<UpdateResponse> replay = rest.postForEntity(obstacleUrl, lidar, UpdateResponse.class);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertTrue(replay.getBody().replayed());
        assertEquals(1, replay.getBody().seq());

        // 4) 同 requestId 异参：409 + 稳定错误码
        ObstacleUpdateRequest mutated = new ObstacleUpdateRequest(
                "req-1001", "lidar-front", 10, 3, 2, 5, 3, 1_000_000L, 60_000L, null);
        ResponseEntity<Map> conflict = rest.postForEntity(obstacleUrl, mutated, Map.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getBody().get("code"));

        // 5) 区域查询：障碍格 254 且 owner 为激光来源；膨胀环为非零非致命代价
        ResponseEntity<RegionCostResponse> region = rest.postForEntity(
                base + "/api/maps/m-http/regions/query",
                new RegionRequest(0, 0, 7, 5), RegionCostResponse.class);
        assertEquals(HttpStatus.OK, region.getStatusCode());
        RegionCostResponse costs = region.getBody();
        assertEquals(254, costs.costs()[2][3]);
        assertEquals(254, costs.costs()[3][4]);
        assertEquals("lidar-front", costs.owners()[2][3]);
        int ring = costs.costs()[2][5]; // (5,2) 距障碍 (4,2) 为 1 格 = 0.5 米
        assertTrue(ring > 0 && ring < 254, "膨胀环应衰减非致命，实际=" + ring);

        // 6) 发布快照
        ResponseEntity<SnapshotResponse> snap = rest.postForEntity(
                base + "/api/maps/m-http/snapshots?snapshotId=snap-http", null, SnapshotResponse.class);
        assertEquals(HttpStatus.OK, snap.getStatusCode());
        assertEquals(1, snap.getBody().seq());

        // 7) 快照之后：新来源覆盖同区域（更高优先级），随后两批障碍均过期并清理
        ObstacleUpdateRequest camera = new ObstacleUpdateRequest(
                "req-1002", "camera", 20, 3, 2, 4, 3, 1_000_100L, 60_000L, null);
        ResponseEntity<UpdateResponse> u2 = rest.postForEntity(obstacleUrl, camera, UpdateResponse.class);
        assertEquals(2, u2.getBody().seq());
        assertEquals("camera", regionCostsAt(3, 2, 4, 3).owners()[0][0], "高优先级来源必须接管");

        // lidar 到期 1_060_000、camera 到期 1_060_100；1_060_101 时两者均过期
        rest.postForEntity(base + "/api/maps/m-http/cleanup?atMs=1060101", null, Object.class);

        // 8) 差异查询：snap-http vs live
        ResponseEntity<CellChange.DiffResponse> diff = rest.exchange(
                base + "/api/maps/m-http/diff?from=snap-http&to=live",
                HttpMethod.POST, new HttpEntity<>(new RegionRequest(3, 2, 4, 3)),
                CellChange.DiffResponse.class);
        assertEquals(HttpStatus.OK, diff.getStatusCode());
        assertNotNull(diff.getBody());
        // 清理后实时障碍消失，快照仍为 254
        assertTrue(diff.getBody().changedCount() >= 4);
        CellChange first = diff.getBody().changes().get(0);
        assertEquals(254, first.fromCost());
        assertEquals(0, first.toCost());

        // 9) 从 H2 快照恢复视图并查询：仍读到发布时刻 254
        ResponseEntity<RestoreViewResponse> restored = rest.postForEntity(
                base + "/api/snapshots/snap-http/restore-view", null, RestoreViewResponse.class);
        assertEquals(HttpStatus.CREATED, restored.getStatusCode());
        String viewId = restored.getBody().viewId();
        ResponseEntity<RegionCostResponse> viewRegion = rest.getForEntity(
                base + "/api/views/" + viewId + "?xMin=3&yMin=2&xMax=4&yMax=3",
                RegionCostResponse.class);
        assertEquals(HttpStatus.OK, viewRegion.getStatusCode());
        assertEquals("snap-http", viewRegion.getBody().snapshotId());
        assertArrayEquals(new int[]{254, 254}, viewRegion.getBody().costs()[0]);
        assertArrayEquals(new int[]{254, 254}, viewRegion.getBody().costs()[1]);
    }

    @Test
    void stableErrorCodesOverHttp() {
        // 地图缺失 → 404 MAP_NOT_FOUND
        ResponseEntity<Map> missing = rest.postForEntity(
                base + "/api/maps/nope/regions/query",
                new RegionRequest(0, 0, 0, 0), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("MAP_NOT_FOUND", missing.getBody().get("code"));

        rest.postForEntity(base + "/api/maps",
                new CreateMapRequest("m-err", 4, 4, 1.0, 1.0, 1.0, null), MapResponse.class);

        // 非法区域（越界）→ 400 INVALID_REGION
        ResponseEntity<Map> badRegion = rest.postForEntity(
                base + "/api/maps/m-err/obstacles",
                new ObstacleUpdateRequest("r", "s", 0, 0, 0, 9, 9, null, 1000L, null), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, badRegion.getStatusCode());
        assertEquals("INVALID_REGION", badRegion.getBody().get("code"));

        // Bean 校验失败 → 400 INVALID_REQUEST
        ResponseEntity<Map> invalid = rest.postForEntity(
                base + "/api/maps/m-err/obstacles",
                new ObstacleUpdateRequest("", "s", 0, 0, 0, 1, 1, null, 1000L, null), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals("INVALID_REQUEST", invalid.getBody().get("code"));

        // expectedSeq 冲突 → 409 VERSION_CONFLICT
        rest.postForEntity(base + "/api/maps/m-err/obstacles",
                new ObstacleUpdateRequest("r1", "s", 0, 0, 0, 0, 0, 1_000_000L, 60_000L, null),
                UpdateResponse.class);
        ResponseEntity<Map> stale = rest.postForEntity(
                base + "/api/maps/m-err/obstacles",
                new ObstacleUpdateRequest("r2", "s", 0, 0, 0, 0, 0, 1_000_000L, 60_000L, 0L), Map.class);
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
        assertEquals("VERSION_CONFLICT", stale.getBody().get("code"));

        // 快照缺失恢复 → 404 SNAPSHOT_NOT_FOUND
        ResponseEntity<Map> noSnap = rest.postForEntity(
                base + "/api/snapshots/ghost/restore-view", null, Map.class);
        assertEquals(HttpStatus.NOT_FOUND, noSnap.getStatusCode());
        assertEquals("SNAPSHOT_NOT_FOUND", noSnap.getBody().get("code"));
    }

    private RegionCostResponse regionCostsAt(int x1, int y1, int x2, int y2) {
        return rest.postForEntity(base + "/api/maps/m-http/regions/query",
                new RegionRequest(x1, y1, x2, y2), RegionCostResponse.class).getBody();
    }
}
