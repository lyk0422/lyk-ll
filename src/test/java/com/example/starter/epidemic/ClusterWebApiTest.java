package com.example.starter.epidemic;

import com.example.starter.epidemic.service.ClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚集性分析真实 HTTP 入口测试：恰 3 例触发 → 协查单幂等/409 → 结论回写 → 关闭终态幂等。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClusterWebApiTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-20T00:00:00Z");
    private static final String SITE_ADDR = "示范市第一小学 3 班";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ClockService clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM investigation_order");
        jdbc.update("DELETE FROM cluster_event_card");
        jdbc.update("DELETE FROM cluster_event");
        jdbc.update("DELETE FROM card_exposure");
        jdbc.update("DELETE FROM special_correction");
        jdbc.update("DELETE FROM audit_trail");
        jdbc.update("DELETE FROM card_version");
        jdbc.update("DELETE FROM card");
        clock.setFixed(BASE_TIME);
    }

    @Test
    void httpClusterFlow_threeReportsTrigger_investigationAndClose() {
        // 1) 恰 3 例触发：前 2 例无事件，第 3 例生成聚集事件
        submitAt("web-c1", BASE_TIME);
        submitAt("web-c2", BASE_TIME.plusSeconds(3600));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cluster_event", Integer.class));
        submitAt("web-c3", BASE_TIME.plusSeconds(7200));

        ResponseEntity<List> events = rest.getForEntity(url("/api/clusters"), List.class);
        assertEquals(HttpStatus.OK, events.getStatusCode());
        assertEquals(1, events.getBody().size(), "恰 3 例必须生成一个聚集事件");
        Number eventId = (Number) ((Map<?, ?>) events.getBody().get(0)).get("id");

        ResponseEntity<Map> event = get("/api/clusters/" + eventId);
        assertEquals("OPEN", event.getBody().get("status"));
        assertEquals(BASE_TIME.toString(), event.getBody().get("firstCaseAt"));
        assertEquals(BASE_TIME.plusSeconds(7200).toString(), event.getBody().get("lastCaseAt"));
        assertEquals(3, ((List<?>) event.getBody().get("cards")).size(), "事件须记录涉及卡号");

        // 2) 生成协查单 → 同键同参重放 → 同键异参 409
        Map<String, Object> invBody = new LinkedHashMap<>();
        invBody.put("requestId", "web-inv-1");
        invBody.put("institutionCode", "H002");
        invBody.put("note", "现场核实");
        ResponseEntity<Map> inv = post("/api/clusters/" + eventId + "/investigations", invBody);
        assertEquals(HttpStatus.OK, inv.getStatusCode());
        Number invId = (Number) inv.getBody().get("id");
        assertEquals("IN_PROGRESS", inv.getBody().get("status"));

        ResponseEntity<Map> replay = post("/api/clusters/" + eventId + "/investigations", invBody);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertEquals(invId.intValue(), ((Number) replay.getBody().get("id")).intValue(),
                "同键同参重放必须返回首次协查单");

        Map<String, Object> altered = new LinkedHashMap<>(invBody);
        altered.put("institutionCode", "H001");
        assertEquals(HttpStatus.CONFLICT,
                post("/api/clusters/" + eventId + "/investigations", altered).getStatusCode(),
                "同键异参必须 409");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM investigation_order", Integer.class),
                "409 不得产生新协查单");

        // 3) 结论回写：事件状态同事务推进
        ResponseEntity<Map> concluded = post("/api/clusters/investigations/" + invId + "/conclude",
                Map.of("conclusion", "确认聚集，已处置", "result", "CONFIRMED"));
        assertEquals(HttpStatus.OK, concluded.getStatusCode());
        assertEquals("CONCLUDED", concluded.getBody().get("status"));
        assertEquals("CONFIRMED", get("/api/clusters/" + eventId).getBody().get("status"));

        // 4) 关闭终态：重复关闭幂等成功
        assertEquals(HttpStatus.OK, post("/api/clusters/" + eventId + "/close", null).getStatusCode());
        ResponseEntity<Map> reclose = post("/api/clusters/" + eventId + "/close", null);
        assertEquals(HttpStatus.OK, reclose.getStatusCode());
        assertEquals("CLOSED", reclose.getBody().get("status"));

        // 5) 不存在的事件：404
        assertEquals(HttpStatus.NOT_FOUND, get("/api/clusters/999999").getStatusCode());
    }

    private void submitAt(String tag, Instant reportedAt) {
        clock.setFixed(reportedAt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-" + tag);
        body.put("patientId", "P-" + tag);
        body.put("patientName", "合成患者-" + tag);
        body.put("age", 8);
        body.put("gender", "F");
        body.put("diseaseCode", "B02");
        body.put("caseType", "CONFIRMED");
        body.put("onsetDate", "2026-09-19");
        body.put("diagnosisTime", reportedAt.minusSeconds(1800).toString());
        body.put("institutionCode", "H001");
        body.put("reporter", "张报告");
        body.put("address", SITE_ADDR);
        body.put("phone", "13800000000");
        ResponseEntity<Map> response = post("/api/cards/submit", body);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody().get("cardNo"));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> post(String path, Object body) {
        return rest.postForEntity(url(path), body, Map.class);
    }

    private ResponseEntity<Map> get(String path) {
        return rest.getForEntity(url(path), Map.class);
    }
}
