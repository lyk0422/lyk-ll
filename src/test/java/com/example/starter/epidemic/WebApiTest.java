package com.example.starter.epidemic;

import com.example.starter.epidemic.service.ClockService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 HTTP 入口测试：验证状态码语义（200/409/422/404）与端到端 JSON 流转。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebApiTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-20T00:00:00Z");

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

    @org.junit.jupiter.api.Test
    void httpEndToEnd_happyPath_statusCodesAndSemantics() {
        // 1) 初报（甲类，诊断后 1 小时提交，及时）
        Map<String, Object> submit = submitBody("http-req-1", "P-HTTP", "A01",
                BASE_TIME.minusSeconds(3600), null);
        ResponseEntity<Map> created = post("/api/cards/submit", submit);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        String cardNo = (String) created.getBody().get("cardNo");
        assertNotNull(cardNo);
        assertEquals(false, created.getBody().get("late"));

        // 2) 同键异参：409
        Map<String, Object> conflict = submitBody("http-req-1", "P-HTTP", "A01",
                BASE_TIME.minusSeconds(3600), null);
        conflict.put("address", "篡改后的住址");
        assertEquals(HttpStatus.CONFLICT, post("/api/cards/submit", conflict).getStatusCode());

        // 3) 迟报缺原因：422
        Map<String, Object> lateNoReason = submitBody("http-req-late", "P-LATE", "A01",
                BASE_TIME.minusSeconds(2 * 3600 + 1), null);
        ResponseEntity<Map> lateResp = post("/api/cards/submit", lateNoReason);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, lateResp.getStatusCode());
        assertTrue(String.valueOf(lateResp.getBody().get("message")).contains("迟报"));

        // 4) 初审 → 终审归档
        assertEquals(HttpStatus.OK, post("/api/cards/" + cardNo + "/review",
                Map.of("reviewer", "auditor-01", "comment", "同意")).getStatusCode());
        ResponseEntity<Map> archived = post("/api/cards/" + cardNo + "/archive",
                Map.of("reviewer", "final-01", "comment", "终审"));
        assertEquals(HttpStatus.OK, archived.getStatusCode());
        assertEquals("ARCHIVED", archived.getBody().get("status"));

        // 5) 归档后普通订正：422
        Map<String, Object> correction = new LinkedHashMap<>();
        correction.put("requestId", "http-req-corr");
        correction.put("cardNo", cardNo);
        correction.put("patientName", "归档后改名");
        correction.put("changeReason", "试图订正");
        ResponseEntity<Map> corrResp = post("/api/cards/correct", correction);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, corrResp.getStatusCode());

        // 6) 特殊订正发起 → 两名不同审核员确认 → 生效
        Map<String, Object> special = new LinkedHashMap<>();
        special.put("requestId", "http-req-special");
        special.put("cardNo", cardNo);
        special.put("diseaseCode", "A02");
        special.put("reason", "疫情复核更正为霍乱");
        special.put("initiator", "cdc-user-1");
        ResponseEntity<Map> started = post("/api/cards/special", special);
        assertEquals(HttpStatus.OK, started.getStatusCode());
        Number specialId = (Number) started.getBody().get("specialId");
        assertEquals("WAITING_SECOND", started.getBody().get("status"));

        ResponseEntity<Map> first = post("/api/cards/special/" + specialId + "/confirm",
                Map.of("reviewer", "auditor-01"));
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals("WAITING_SECOND", first.getBody().get("status"));

        // 同人二次确认：422
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                post("/api/cards/special/" + specialId + "/confirm",
                        Map.of("reviewer", "auditor-01")).getStatusCode());

        ResponseEntity<Map> second = post("/api/cards/special/" + specialId + "/confirm",
                Map.of("reviewer", "auditor-02"));
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertEquals("EFFECTIVE", second.getBody().get("status"));

        ResponseEntity<Map> finalCard = get("/api/cards/" + cardNo);
        assertEquals(HttpStatus.OK, finalCard.getStatusCode());
        assertEquals("A02", finalCard.getBody().get("diseaseCode"));
        assertEquals(2, ((Number) finalCard.getBody().get("versionNo")).intValue());

        // 7) 不存在卡片：404
        assertEquals(HttpStatus.NOT_FOUND, get("/api/cards/C-not-exist").getStatusCode());
    }

    private Map<String, Object> submitBody(String requestId, String patientId, String diseaseCode,
                                           Instant diagnosis, String lateReason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("patientId", patientId);
        body.put("patientName", "合成患者-" + patientId + "-" + UUID.randomUUID().toString().substring(0, 4));
        body.put("age", 35);
        body.put("gender", "M");
        body.put("diseaseCode", diseaseCode);
        body.put("caseType", "CONFIRMED");
        body.put("onsetDate", "2026-09-19");
        body.put("diagnosisTime", diagnosis.toString());
        body.put("institutionCode", "H001");
        body.put("reporter", "张报告");
        body.put("address", "示范市合成路 1 号");
        body.put("phone", "13800000000");
        if (lateReason != null) {
            body.put("lateReason", lateReason);
        }
        return body;
    }

    private ResponseEntity<Map> post(String path, Object body) {
        return rest.postForEntity("http://localhost:" + port + path, body, Map.class);
    }

    private ResponseEntity<Map> get(String path) {
        return rest.getForEntity("http://localhost:" + port + path, Map.class);
    }
}
