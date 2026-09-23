package com.example.starter.costmap.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态 Costmap HTTP 接口端到端测试（真实 Spring MVC + 嵌入式 H2），
 * 校验稳定状态码与错误码：200/201/400/404/409。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CostmapControllerHttpTest {

    private static final String MAP = "map-http";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private String base;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM costmap_snapshot_cell");
        jdbc.update("DELETE FROM costmap_snapshot");
        jdbc.update("DELETE FROM costmap_obstacle");
        jdbc.update("DELETE FROM costmap_idempotency");
        jdbc.update("DELETE FROM costmap_static_cell");
        jdbc.update("DELETE FROM costmap_version");
        base = "http://localhost:" + port + "/api/costmaps";
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @DisplayName("创建地图 201 → 更新障碍 200 → 同参重放 200 且 replay=true")
    void createUpdateReplay() {
        String createBody = """
                {
                  "mapId": "%s",
                  "width": 8, "height": 8, "resolution": 0.1,
                  "originX": 0.0, "originY": 0.0,
                  "inflationRadiusCells": 1, "costDecay": 0.5,
                  "staticCells": [{"x": 0, "y": 0, "cost": 100}]
                }
                """.formatted(MAP);

        ResponseEntity<String> created = rest.exchange(base, HttpMethod.POST,
                new HttpEntity<>(createBody, jsonHeaders()), String.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        String updateBody = """
                {
                  "requestId": "req-1", "sourceId": "sensor-1", "sourceType": "SENSOR",
                  "region": {"x0": 3, "y0": 3, "x1": 4, "y1": 4},
                  "cost": 100, "eventTime": 1000, "ttlMillis": null
                }
                """;
        ResponseEntity<String> updated = rest.exchange(base + "/" + MAP + "/obstacles",
                HttpMethod.POST, new HttpEntity<>(updateBody, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, updated.getStatusCode());
        assertNotNull(updated.getBody());
        assertTrue(updated.getBody().contains("\"replay\":false"));

        ResponseEntity<String> replay = rest.exchange(base + "/" + MAP + "/obstacles",
                HttpMethod.POST, new HttpEntity<>(updateBody, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertTrue(replay.getBody().contains("\"replay\":true"), "同参重放返回 replay=true");
    }

    @Test
    @DisplayName("同 requestId 异参重放返回 409 IDEMPOTENCY_CONFLICT")
    void idempotencyConflict() {
        createMinimalMap();
        postObstacle("""
                {"requestId": "req-x", "sourceId": "s1", "sourceType": "SENSOR",
                 "region": {"x0": 1, "y0": 1, "x1": 2, "y1": 2},
                 "cost": 100, "eventTime": 1000}
                """, HttpStatus.OK);

        ResponseEntity<String> conflict = postObstacle("""
                {"requestId": "req-x", "sourceId": "s1", "sourceType": "SENSOR",
                 "region": {"x0": 1, "y0": 1, "x1": 2, "y1": 2},
                 "cost": 40, "eventTime": 1000}
                """, HttpStatus.CONFLICT);
        assertTrue(conflict.getBody().contains("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("非法区域 400、无法识别来源类型 400、地图缺失 404、版本冲突 409、报文损坏 400")
    void stableErrorStatuses() {
        createMinimalMap();

        ResponseEntity<String> reversed = postObstacle("""
                {"requestId": "bad-region", "sourceId": "s1", "sourceType": "SENSOR",
                 "region": {"x0": 5, "y0": 5, "x1": 2, "y1": 2},
                 "cost": 10, "eventTime": 1000}
                """, HttpStatus.BAD_REQUEST);
        assertTrue(reversed.getBody().contains("INVALID_REGION"));

        ResponseEntity<String> badType = postObstacle("""
                {"requestId": "bad-type", "sourceId": "s1", "sourceType": "RADAR",
                 "region": {"x0": 1, "y0": 1, "x1": 2, "y1": 2},
                 "cost": 10, "eventTime": 1000}
                """, HttpStatus.BAD_REQUEST);
        assertTrue(badType.getBody().contains("BAD_REQUEST"));

        ResponseEntity<String> missingMap = rest.exchange(
                base + "/ghost/obstacles", HttpMethod.POST,
                new HttpEntity<>("""
                        {"requestId": "r", "sourceId": "s1", "sourceType": "SENSOR",
                         "region": {"x0": 0, "y0": 0, "x1": 1, "y1": 1},
                         "cost": 10, "eventTime": 1000}
                        """, jsonHeaders()), String.class);
        assertEquals(HttpStatus.NOT_FOUND, missingMap.getStatusCode());
        assertTrue(missingMap.getBody().contains("VERSION_NOT_FOUND"));

        ResponseEntity<String> versionConflict = postObstacle("""
                {"requestId": "bad-version", "sourceId": "s1", "sourceType": "SENSOR",
                 "region": {"x0": 1, "y0": 1, "x1": 2, "y1": 2},
                 "cost": 10, "eventTime": 1000, "expectedVersion": 42}
                """, HttpStatus.CONFLICT);
        assertTrue(versionConflict.getBody().contains("VERSION_CONFLICT"));

        ResponseEntity<String> malformed = rest.exchange(
                base + "/" + MAP + "/obstacles", HttpMethod.POST,
                new HttpEntity<>("{not-json", jsonHeaders()), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        assertTrue(malformed.getBody().contains("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("视图、快照、快照恢复与差异查询主流程")
    void viewSnapshotAndDiffFlow() {
        createMinimalMap();
        postObstacle("""
                {"requestId": "flow-1", "sourceId": "s1", "sourceType": "SENSOR",
                 "region": {"x0": 2, "y0": 2, "x1": 3, "y1": 3},
                 "cost": 100, "eventTime": 1000}
                """, HttpStatus.OK);

        ResponseEntity<String> view = rest.exchange(
                base + "/" + MAP + "/view", HttpMethod.POST,
                new HttpEntity<>("""
                        {"x0": 2, "y0": 2, "x1": 3, "y1": 3}
                        """, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        assertTrue(view.getBody().contains("\"finalCost\":100"));

        ResponseEntity<String> snapshot = rest.exchange(
                base + "/" + MAP + "/snapshots/snap-a", HttpMethod.POST,
                new HttpEntity<>(jsonHeaders()), String.class);
        assertEquals(HttpStatus.CREATED, snapshot.getStatusCode());

        ResponseEntity<String> snapView = rest.exchange(
                base + "/snapshots/snap-a/view", HttpMethod.POST,
                new HttpEntity<>("""
                        {"x0": 2, "y0": 2, "x1": 3, "y1": 3}
                        """, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, snapView.getStatusCode());
        assertTrue(snapView.getBody().contains("\"finalCost\":100"));

        ResponseEntity<String> diff = rest.exchange(
                base + "/" + MAP + "/diff?fromSnapshot=snap-a", HttpMethod.POST,
                new HttpEntity<>("""
                        {"x0": 0, "y0": 0, "x1": 8, "y1": 8}
                        """, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, diff.getStatusCode());
        assertTrue(diff.getBody().contains("\"changedCells\":[]"),
                "无后续更新时快照与实时视图应无差异");
    }

    private void createMinimalMap() {
        String body = """
                {
                  "mapId": "%s",
                  "width": 8, "height": 8, "resolution": 0.1,
                  "originX": 0.0, "originY": 0.0,
                  "inflationRadiusCells": 1, "costDecay": 0.5,
                  "staticCells": []
                }
                """.formatted(MAP);
        ResponseEntity<String> created = rest.exchange(base, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
    }

    private ResponseEntity<String> postObstacle(String body, HttpStatus expected) {
        ResponseEntity<String> response = rest.exchange(
                base + "/" + MAP + "/obstacles", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertEquals(expected, response.getStatusCode(),
                "unexpected status; body=" + response.getBody());
        return response;
    }
}
