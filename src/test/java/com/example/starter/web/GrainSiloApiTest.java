package com.example.starter.web;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.FixedClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端 HTTP 测试：走真实 REST 入口，校验状态码与业务 JSON。
 */
@Import(FixedClockConfig.class)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
class GrainSiloApiTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 9, 0, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 9, 26, 12, 0, 0);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void full_alarm_ventilation_retest_archive_flow_over_http() {
        post("/api/silos", Map.of(
                "code", "S1", "name", "一号仓", "grainType", "WHEAT",
                "safeTemperature", 20.0, "maxGradient", 2.0, "equilibriumHumidity", 70.0),
                HttpStatus.CREATED);
        post("/api/fans", Map.of("code", "F1", "name", "1号风机"), HttpStatus.CREATED);

        // 告警：23.0 > 20.0 安全温度
        ResponseEntity<Map> alarmResp = post("/api/alarms", Map.of(
                "requestId", "a-http-1", "siloCode", "S1", "measuredAt", T0.toString(),
                "readings", List.of(point("P1", 18.0), point("P2", 23.0))), HttpStatus.CREATED);
        assertEquals(Boolean.TRUE, alarmResp.getBody().get("alarmed"));
        assertEquals("ALARM", alarmResp.getBody().get("state"));

        // 通风令（外界湿度 65 < 平衡 70，允许）
        ResponseEntity<Map> ventResp = post("/api/ventilations", Map.of(
                "requestId", "v-http-1", "siloCode", "S1", "fanCode", "F1",
                "purpose", "COOLING", "ambientHumidity", 65.0,
                "startTime", T1.toString(), "endTime", T2.toString()), HttpStatus.CREATED);
        assertEquals("EXECUTING", ventResp.getBody().get("status"));
        assertEquals(1, ventResp.getBody().get("roundNo"));

        // 同键同参重放：200 语义下仍是首次结果（201）
        ResponseEntity<Map> replay = post("/api/ventilations", Map.of(
                "requestId", "v-http-1", "siloCode", "S1", "fanCode", "F1",
                "purpose", "COOLING", "ambientHumidity", 65.0,
                "startTime", T1.toString(), "endTime", T2.toString()), HttpStatus.CREATED);
        assertEquals(ventResp.getBody().get("operationId"), replay.getBody().get("operationId"));

        // 复测通过（20.0 恰等安全温，梯度 2.0 恰等上限）
        ResponseEntity<Map> retestResp = post("/api/retests", Map.of(
                "requestId", "rt-http-1", "siloCode", "S1", "measuredAt", T2.toString(),
                "readings", List.of(point("P1", 20.0), point("P2", 18.0))), HttpStatus.CREATED);
        assertEquals(Boolean.TRUE, retestResp.getBody().get("passed"));
        assertEquals("RETEST_PASS", retestResp.getBody().get("state"));

        // 归档
        ResponseEntity<Map> archiveResp = post("/api/archives",
                Map.of("requestId", "ar-http-1", "siloCode", "S1"), HttpStatus.CREATED);
        assertEquals(Boolean.TRUE, archiveResp.getBody().get("archived"));

        // 作业窗与复测可复算
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> windows = rest.getForObject(url("/api/silos/S1/windows"), List.class);
        assertEquals(1, windows.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> retests = rest.getForObject(url("/api/silos/S1/retests"), List.class);
        assertEquals(1, retests.size());
    }

    @Test
    void humidity_boundary_returns_422_and_conflict_returns_409_over_http() {
        post("/api/silos", Map.of(
                "code", "S2", "name", "二号仓", "grainType", "WHEAT",
                "safeTemperature", 20.0, "maxGradient", 2.0, "equilibriumHumidity", 70.0),
                HttpStatus.CREATED);
        post("/api/fans", Map.of("code", "F2", "name", "2号风机"), HttpStatus.CREATED);
        post("/api/alarms", Map.of(
                "requestId", "a-http-2", "siloCode", "S2", "measuredAt", T0.toString(),
                "readings", List.of(point("P1", 23.0))), HttpStatus.CREATED);

        // 外界湿度 70.5 > 70 平衡湿度：422
        ResponseEntity<Map> forbidden = post("/api/ventilations", Map.of(
                "requestId", "v-http-2a", "siloCode", "S2", "fanCode", "F2",
                "purpose", "COOLING", "ambientHumidity", 70.5,
                "startTime", T1.toString(), "endTime", T2.toString()), HttpStatus.UNPROCESSABLE_ENTITY);
        assertTrue(forbidden.getBody().get("message").toString().contains("禁止通风"));

        // 先成功一笔
        post("/api/ventilations", Map.of(
                "requestId", "v-http-2b", "siloCode", "S2", "fanCode", "F2",
                "purpose", "COOLING", "ambientHumidity", 60.0,
                "startTime", T1.toString(), "endTime", T2.toString()), HttpStatus.CREATED);
        // 同键异参：409
        ResponseEntity<Map> conflict = post("/api/ventilations", Map.of(
                "requestId", "v-http-2b", "siloCode", "S2", "fanCode", "F2",
                "purpose", "COOLING", "ambientHumidity", 55.0,
                "startTime", T1.toString(), "endTime", T2.toString()), HttpStatus.CONFLICT);
        assertEquals(409, conflict.getBody().get("status"));
    }

    @Test
    void unknown_silo_is_404_and_bad_purpose_is_400() {
        ResponseEntity<Map> notFound = post("/api/alarms", Map.of(
                "requestId", "a-http-3", "siloCode", "NOPE", "measuredAt", T0.toString(),
                "readings", List.of(point("P1", 23.0))), HttpStatus.NOT_FOUND);
        assertEquals(404, notFound.getBody().get("status"));

        post("/api/silos", Map.of(
                "code", "S3", "name", "三号仓", "grainType", "WHEAT",
                "safeTemperature", 20.0, "maxGradient", 2.0, "equilibriumHumidity", 70.0),
                HttpStatus.CREATED);
        post("/api/fans", Map.of("code", "F3", "name", "3号风机"), HttpStatus.CREATED);
        post("/api/alarms", Map.of(
                "requestId", "a-http-4", "siloCode", "S3", "measuredAt", T0.toString(),
                "readings", List.of(point("P1", 23.0))), HttpStatus.CREATED);

        // 非法 purpose：400
        ResponseEntity<Map> badRequest = post("/api/ventilations", Map.of(
                "requestId", "v-http-3", "siloCode", "S3", "fanCode", "F3",
                "purpose", "BOGUS", "ambientHumidity", 60.0,
                "startTime", T1.toString(), "endTime", T2.toString()), HttpStatus.BAD_REQUEST);
        assertEquals(400, badRequest.getBody().get("status"));

        // 无温度测点（只有湿度）：400
        ResponseEntity<Map> noTemp = post("/api/alarms", Map.of(
                "requestId", "a-http-5", "siloCode", "S3", "measuredAt", T0.toString(),
                "readings", List.of(Map.of("pointCode", "P9", "humidity", 55.0))),
                HttpStatus.BAD_REQUEST);
        assertFalse(noTemp.getStatusCode().is2xxSuccessful());
    }

    private Map<String, Object> point(String code, double temperature) {
        return Map.of("pointCode", code, "temperature", temperature);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(String path, Object body, HttpStatus expected) {
        ResponseEntity<Map> resp = rest.postForEntity(url(path), new HttpEntity<>(body), Map.class);
        assertEquals(expected, resp.getStatusCode(),
                "请求 " + path + " 状态码不符，响应: " + resp.getBody());
        return resp;
    }
}
