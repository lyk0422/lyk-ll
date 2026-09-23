package com.example.starter.calibration.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 通过真实 HTTP 入口验证状态码与 JSON 语义：422 错误标定、409 灰度冲突整批回滚、
 * 未参与车辆隔离、回滚恢复、resolutionId 历史追踪。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CalibrationControllerHttpTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM resolution_record");
        jdbc.update("DELETE FROM active_canary_vehicle");
        jdbc.update("DELETE FROM canary_vehicle");
        jdbc.update("DELETE FROM canary_release");
        jdbc.update("DELETE FROM calibration_version");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return response.getBody();
    }

    private Map<String, Object> versionRequest(String version, double fx, boolean allVehicles,
            List<String> vehicles) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("key", "front-camera");
        request.put("sensorType", "CAMERA");
        request.put("version", version);
        request.put("payload", Map.of(
                "cameraIntrinsics", Map.of(
                        "fx", fx, "fy", fx, "cx", 640.0, "cy", 360.0,
                        "distortionCoeffs", List.of(0.0, 0.0, 0.0)),
                "extrinsics", Map.of(
                        "rotation", List.of(
                                List.of(1.0, 0.0, 0.0),
                                List.of(0.0, 1.0, 0.0),
                                List.of(0.0, 0.0, 1.0)),
                        "translation", List.of(0.1, 0.2, 1.5))));
        request.put("effectiveFrom", OffsetDateTime.ofInstant(FROM, ZoneOffset.UTC).toString());
        request.put("allVehicles", allVehicles);
        request.put("vehicles", vehicles);
        request.put("sensorId", "cam-front-01");
        request.put("operator", "tester");
        return request;
    }

    @Test
    void fullHttpLifecycleFromPublishToRollback() {
        // 1. 发布 v1 → 审批 → 全量生效
        ResponseEntity<Map> created = rest.postForEntity(
                url("/api/calibrations/versions"),
                versionRequest("v1", 1000.0, true, List.of()), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(created).get("status")).isEqualTo("DRAFT");

        rest.postForEntity(url("/api/calibrations/front-camera/versions/v1/approve"),
                Map.of("operator", "reviewer"), Map.class);
        rest.postForEntity(url("/api/calibrations/front-camera/versions/v1/activate"),
                Map.of("operator", "release-bot"), Map.class);

        // 2. 错误标定（焦距为 0）返回 422，且未创建可审批版本
        ResponseEntity<Map> bad = rest.postForEntity(
                url("/api/calibrations/versions"),
                versionRequest("bad", 0.0, true, List.of()), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) body(bad).get("message")).contains("焦距");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_version WHERE version = 'bad'", Integer.class))
                .isZero();

        // 3. 创建并审批灰度版本 v2
        rest.postForEntity(url("/api/calibrations/versions"),
                versionRequest("v2", 1050.0, true, List.of()), Map.class);
        rest.postForEntity(url("/api/calibrations/front-camera/versions/v2/approve"),
                Map.of("operator", "reviewer"), Map.class);

        // 4. 灰度 v2 到 car-a、car-b
        Map<String, Object> canaryRequest = Map.of(
                "key", "front-camera", "version", "v2",
                "vehicles", List.of("car-a", "car-b"), "operator", "ops");
        ResponseEntity<Map> canary = rest.postForEntity(
                url("/api/calibrations/canary-releases"), canaryRequest, Map.class);
        assertThat(canary.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number releaseId = (Number) body(canary).get("releaseId");

        // 5. 第二次灰度包含已占用的 car-b：409，整批回滚
        rest.postForEntity(url("/api/calibrations/versions"),
                versionRequest("v3", 1100.0, true, List.of()), Map.class);
        rest.postForEntity(url("/api/calibrations/front-camera/versions/v3/approve"),
                Map.of("operator", "reviewer"), Map.class);
        Map<String, Object> conflicting = Map.of(
                "key", "front-camera", "version", "v3",
                "vehicles", List.of("car-b", "car-c"), "operator", "ops");
        ResponseEntity<Map> conflict = rest.postForEntity(
                url("/api/calibrations/canary-releases"), conflicting, Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) body(conflict).get("message")).contains("全部回滚");
        // 回滚后只有第一个发布单与两辆活跃灰度车
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canary_release", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM active_canary_vehicle", Integer.class)).isEqualTo(2);

        // 6. 解析：灰度车 v2、未参与车 v1
        Map<String, Object> resolveQuery = Map.of(
                "key", "front-camera", "sensorId", "cam-front-01",
                "vehicleId", "car-a", "time",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).toString());
        ResponseEntity<Map> resolvedA =
                rest.postForEntity(url("/api/calibrations/resolve"), resolveQuery, Map.class);
        assertThat(resolvedA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(resolvedA).get("selectedVersion")).isEqualTo("v2");
        assertThat(body(resolvedA).get("canaryHit")).isEqualTo(true);
        String resolutionId = (String) body(resolvedA).get("resolutionId");

        Map<String, Object> resolveC = new LinkedHashMap<>(resolveQuery);
        resolveC.put("vehicleId", "car-c");
        ResponseEntity<Map> resolvedC =
                rest.postForEntity(url("/api/calibrations/resolve"), resolveC, Map.class);
        assertThat(body(resolvedC).get("selectedVersion")).isEqualTo("v1");
        assertThat(body(resolvedC).get("canaryHit")).isEqualTo(false);

        // 7. 回滚灰度
        ResponseEntity<Map> rolledBack = rest.postForEntity(
                url("/api/calibrations/canary-releases/" + releaseId + "/rollback"),
                Map.of("operator", "sre", "reason", "融合异常"), Map.class);
        assertThat(rolledBack.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(rolledBack).get("status")).isEqualTo("ROLLED_BACK");

        ResponseEntity<Map> afterRollback = rest.postForEntity(
                url("/api/calibrations/resolve"),
                Map.of("key", "front-camera", "sensorId", "cam-front-01",
                        "vehicleId", "car-a"),
                Map.class);
        assertThat(body(afterRollback).get("selectedVersion")).isEqualTo("v1");

        // 8. 历史 resolutionId 仍指向当时的 v2 快照
        ResponseEntity<Map> history = rest.getForEntity(
                url("/api/calibrations/resolutions/" + resolutionId), Map.class);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(history).get("selectedVersion")).isEqualTo("v2");
    }

    @Test
    void unknownVersionReturns404() {
        ResponseEntity<Map> response = rest.getForEntity(
                url("/api/calibrations/missing/versions/nope"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingRequiredFieldReturns400() {
        ResponseEntity<Map> response = rest.postForEntity(
                url("/api/calibrations/versions"), Map.of("sensorType", "CAMERA"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
