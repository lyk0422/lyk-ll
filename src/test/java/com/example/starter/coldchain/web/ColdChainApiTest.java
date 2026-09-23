package com.example.starter.coldchain.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端 HTTP 契约测试：真实随机端口 + 嵌入式 H2，覆盖主流程与失败分支。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ColdChainApiTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private String base;

    @BeforeEach
    void clean() {
        base = "http://localhost:" + port + "/api/shipments";
        jdbc.update("DELETE FROM handover_record");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM transport_segment");
        jdbc.update("DELETE FROM shipment");
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void fullLifecycleOverHttp() {
        // 1) 建货件。
        String createBody = """
                {
                  "shipmentId": "COLD-1",
                  "minTemp": 2.0,
                  "maxTemp": 8.0,
                  "maxExcursionMinutes": 10,
                  "maxSampleIntervalSeconds": 300,
                  "custodian": "Carrier-A",
                  "startTime": "2026-09-23T08:00:00"
                }
                """;
        ResponseEntity<JsonNode> created = rest.exchange(base, HttpMethod.POST,
                new HttpEntity<>(createBody, json()), JsonNode.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals("COLD-1", created.getBody().get("shipmentId").asText());
        assertEquals(1, created.getBody().get("currentVersion").asInt());

        // 重复建件 → 409。
        ResponseEntity<JsonNode> dup = rest.exchange(base, HttpMethod.POST,
                new HttpEntity<>(createBody, json()), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, dup.getStatusCode());

        // 2) 乱序写读数（序号 2 先到，序号 1 后到）。
        write(2, "2026-09-23T08:05:00", 5.0, HttpStatus.CREATED);
        ResponseEntity<JsonNode> r1 = write(1, "2026-09-23T08:00:00", 5.0, HttpStatus.CREATED);
        assertFalse(r1.getBody().get("replayed").asBoolean());

        // 同序号同参重放：201（幂等）且 replayed=true。
        ResponseEntity<JsonNode> replay = write(1, "2026-09-23T08:00:00", 5.0, HttpStatus.CREATED);
        assertTrue(replay.getBody().get("replayed").asBoolean());

        // 同序号改值 → 409。
        ResponseEntity<JsonNode> changed = write(1, "2026-09-23T08:00:00", 5.5, HttpStatus.CONFLICT);
        assertEquals(409, changed.getBody().get("status").asInt());

        // 3) 交接：CLEAR。
        String handoverBody = """
                {
                  "requestId": "HV-1",
                  "expectedVersion": 1,
                  "receiver": "Carrier-B",
                  "handoverAt": "2026-09-23T08:05:00",
                  "lastSequence": 2
                }
                """;
        ResponseEntity<JsonNode> hv = rest.exchange(base + "/COLD-1/handovers", HttpMethod.POST,
                new HttpEntity<>(handoverBody, json()), JsonNode.class);
        assertEquals(HttpStatus.CREATED, hv.getStatusCode());
        assertEquals("CLEAR", hv.getBody().get("status").asText());
        assertEquals(2, hv.getBody().get("newVersion").asInt());
        assertFalse(hv.getBody().get("replayed").asBoolean());

        // 同 requestId 同参重放：replayed=true。
        ResponseEntity<JsonNode> hvReplay = rest.exchange(base + "/COLD-1/handovers", HttpMethod.POST,
                new HttpEntity<>(handoverBody, json()), JsonNode.class);
        assertEquals(HttpStatus.CREATED, hvReplay.getStatusCode());
        assertTrue(hvReplay.getBody().get("replayed").asBoolean());

        // 同 requestId 改 lastSequence → 409。
        String conflictBody = handoverBody.replace("\"lastSequence\": 2", "\"lastSequence\": 9");
        ResponseEntity<JsonNode> hvConflict = rest.exchange(base + "/COLD-1/handovers", HttpMethod.POST,
                new HttpEntity<>(conflictBody, json()), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, hvConflict.getStatusCode());

        // 4) 分段证据查询：v1 CLEAR（读数按序号升序），v2 OPEN。
        ResponseEntity<JsonNode> evidence = rest.getForEntity(base + "/COLD-1/segments", JsonNode.class);
        assertEquals(HttpStatus.OK, evidence.getStatusCode());
        JsonNode segments = evidence.getBody();
        assertEquals(2, segments.size());
        assertEquals("CLEAR", segments.get(0).get("status").asText());
        assertEquals(1, segments.get(0).get("readings").get(0).get("sequenceNo").asInt());
        assertEquals(2, segments.get(0).get("readings").get(1).get("sequenceNo").asInt());
        assertEquals("OPEN", segments.get(1).get("status").asText());
        assertEquals(3, segments.get(1).get("firstSequence").asInt());
    }

    @Test
    void missingShipmentReturns404() {
        ResponseEntity<JsonNode> resp = rest.getForEntity(base + "/NO-SUCH", JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void beanValidationReturns400() {
        String body = """
                {
                  "shipmentId": "",
                  "minTemp": 8.0,
                  "maxTemp": 2.0,
                  "maxExcursionMinutes": 10,
                  "maxSampleIntervalSeconds": 300,
                  "custodian": "A"
                }
                """;
        ResponseEntity<JsonNode> resp = rest.exchange(base, HttpMethod.POST,
                new HttpEntity<>(body, json()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void incompleteSegmentIsReportedOverHttp() {
        String createBody = """
                {
                  "shipmentId": "COLD-2",
                  "minTemp": 2.0,
                  "maxTemp": 8.0,
                  "maxExcursionMinutes": 10,
                  "maxSampleIntervalSeconds": 300,
                  "custodian": "Carrier-A",
                  "startTime": "2026-09-23T08:00:00"
                }
                """;
        rest.exchange(base, HttpMethod.POST,
                new HttpEntity<>(createBody, json()), JsonNode.class);
        // 只有序号 1，交接声明末序号 2 → INCOMPLETE（仍是 201 原子冻结）。
        writeTo("COLD-2", 1, "2026-09-23T08:00:00", 5.0, HttpStatus.CREATED);
        String handoverBody = """
                {
                  "requestId": "HV-2",
                  "expectedVersion": 1,
                  "receiver": "Carrier-B",
                  "handoverAt": "2026-09-23T08:05:00",
                  "lastSequence": 2
                }
                """;
        ResponseEntity<JsonNode> hv = rest.exchange(base + "/COLD-2/handovers", HttpMethod.POST,
                new HttpEntity<>(handoverBody, json()), JsonNode.class);
        assertEquals(HttpStatus.CREATED, hv.getStatusCode());
        assertEquals("INCOMPLETE", hv.getBody().get("status").asText());
    }

    @Test
    void nonNumericJsonTokenIsRejectedAtHttpBoundary() {
        String createBody = """
                {
                  "shipmentId": "COLD-3",
                  "minTemp": 2.0,
                  "maxTemp": 8.0,
                  "maxExcursionMinutes": 10,
                  "maxSampleIntervalSeconds": 300,
                  "custodian": "Carrier-A",
                  "startTime": "2026-09-23T08:00:00"
                }
                """;
        rest.exchange(base, HttpMethod.POST,
                new HttpEntity<>(createBody, json()), JsonNode.class);
        // NaN 不是合法 JSON token，HTTP 边界按 400 拒绝；
        // 有限数值的服务层校验（Double.NaN/Infinity → 422）由 H2 服务测试覆盖。
        String readingBody = """
                {"sequenceNo": 1, "deviceNo": "DEV-1",
                 "sampledAt": "2026-09-23T08:00:00", "temperature": NaN}
                """;
        ResponseEntity<JsonNode> resp = rest.exchange(base + "/COLD-3/readings", HttpMethod.POST,
                new HttpEntity<>(readingBody, json()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    private ResponseEntity<JsonNode> write(long seq, String time, double temp, HttpStatus expected) {
        return writeTo("COLD-1", seq, time, temp, expected);
    }

    private ResponseEntity<JsonNode> writeTo(String shipmentId, long seq, String time,
                                             double temp, HttpStatus expected) {
        Map<String, Object> body = Map.of(
                "sequenceNo", seq,
                "deviceNo", "DEV-1",
                "sampledAt", time,
                "temperature", temp);
        ResponseEntity<JsonNode> resp = rest.exchange(base + "/" + shipmentId + "/readings",
                HttpMethod.POST, new HttpEntity<>(body, json()), JsonNode.class);
        assertEquals(expected, resp.getStatusCode());
        return resp;
    }
}
