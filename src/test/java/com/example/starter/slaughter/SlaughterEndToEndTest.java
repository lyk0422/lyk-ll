package com.example.starter.slaughter;

import com.example.starter.slaughter.testsupport.TestClockConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 经真实 HTTP 入口的端到端合成示例：申报 → 宰前检疫 → 同步检验 → 判定（含化制）
 * → 化制回执勾稽闭环 → 出厂核销；并验证重放头与同键异参 409 的 HTTP 语义。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TestClockConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SlaughterEndToEndTest {

    private static final List<String> TABLES = List.of(
            "idempotent_request", "late_receipt_event", "inventory_mark", "receipt_head",
            "disposal_receipt", "batch_head", "inspection_chain", "slaughter_batch");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.example.starter.slaughter.testsupport.MutableClock clock;

    @BeforeEach
    void clean() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : TABLES) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        clock.setInstant(TestClockConfiguration.START);
    }

    private String url(String suffix) {
        return "http://localhost:" + port + "/api/slaughter/batches" + suffix;
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @org.junit.jupiter.api.Test
    void fullRenderedClosureExampleThroughHttp() {
        String batchNo = "B-E2E-1";

        // 1) 宰前申报（3 头）。
        ResponseEntity<String> declared = post(url(""), Map.of(
                "requestId", "e2e-declare",
                "batchNo", batchNo,
                "headNos", List.of("E001", "E002", "E003")));
        assertEquals(HttpStatus.CREATED, declared.getStatusCode());
        assertEquals("false", declared.getHeaders().getFirst("X-Idempotent-Replayed"));
        JsonNode declareJson = parse(declared.getBody());
        assertEquals("DECLARED", declareJson.path("status").asText());
        assertEquals(3, declareJson.path("headCount").asInt());

        // 同键同参重放：200（首次状态码保持 201）+ 重放头 true。
        ResponseEntity<String> replayed = post(url(""), Map.of(
                "requestId", "e2e-declare",
                "batchNo", batchNo,
                "headNos", List.of("E001", "E002", "E003")));
        assertEquals(HttpStatus.CREATED, replayed.getStatusCode());
        assertEquals("true", replayed.getHeaders().getFirst("X-Idempotent-Replayed"));

        // 同键异参：409。
        ResponseEntity<String> conflict = post(url(""), Map.of(
                "requestId", "e2e-declare",
                "batchNo", batchNo,
                "headNos", List.of("E001", "E002")));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("IDEMPOTENT_CONFLICT", parse(conflict.getBody()).path("code").asText());

        // 2) 宰前检疫合格。
        assertEquals(HttpStatus.OK, post(url("/" + batchNo + "/pre-quarantine"), Map.of(
                "requestId", "e2e-pq", "result", "PASS")).getStatusCode());

        // 3) 同步检验一一对应并收口。
        assertEquals(HttpStatus.OK, post(url("/" + batchNo + "/inspections"), Map.of(
                "requestId", "e2e-insp",
                "items", List.of(
                        Map.of("headNo", "E001", "result", "NORMAL"),
                        Map.of("headNo", "E002", "result", "SUSPECT"),
                        Map.of("headNo", "E003", "result", "NORMAL")))).getStatusCode());
        ResponseEntity<String> completed = post(url("/" + batchNo + "/inspections/complete"),
                Map.of("requestId", "e2e-complete"));
        assertEquals(HttpStatus.OK, completed.getStatusCode());
        assertEquals("INSPECTED", parse(completed.getBody()).path("status").asText());

        // 4) 判定：2 头合格、1 头化制。
        ResponseEntity<String> judged = post(url("/" + batchNo + "/judgments"), Map.of(
                "requestId", "e2e-judge",
                "items", List.of(
                        Map.of("headNo", "E001", "category", "QUALIFIED"),
                        Map.of("headNo", "E002", "category", "QUALIFIED"),
                        Map.of("headNo", "E003", "category", "RENDERED"))));
        assertEquals(HttpStatus.OK, judged.getStatusCode());
        JsonNode judgeJson = parse(judged.getBody());
        assertEquals(2, judgeJson.path("categoryCounts").path("QUALIFIED").asInt());
        assertEquals(1, judgeJson.path("categoryCounts").path("RENDERED").asInt());

        // 5) 化制回执勾稽恰等受理。
        ResponseEntity<String> receipt = post(url("/" + batchNo + "/receipts"), Map.of(
                "requestId", "e2e-receipt",
                "receiptNo", "RCP-E2E-1",
                "headNos", List.of("E003")));
        assertEquals(HttpStatus.OK, receipt.getStatusCode());
        JsonNode receiptJson = parse(receipt.getBody());
        assertTrue(receiptJson.path("accepted").asBoolean());
        assertFalse(receiptJson.path("late").asBoolean());
        assertEquals("RENDERED", receiptJson.path("category").asText());

        // 回执数与化制判定数勾稽（可复算）。
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_head WHERE batch_no = ? AND receipt_id IS NOT NULL",
                Integer.class, batchNo));

        // 6) 出厂核销终态；重复核销幂等成功。
        ResponseEntity<String> checkout = post(url("/" + batchNo + "/checkout"),
                Map.of("requestId", "e2e-checkout-1"));
        assertEquals(HttpStatus.OK, checkout.getStatusCode());
        assertEquals("CHECKED_OUT", parse(checkout.getBody()).path("status").asText());

        ResponseEntity<String> checkoutAgain = post(url("/" + batchNo + "/checkout"),
                Map.of("requestId", "e2e-checkout-2"));
        assertEquals(HttpStatus.OK, checkoutAgain.getStatusCode());
        assertEquals("true", checkoutAgain.getHeaders().getFirst("X-Idempotent-Replayed"));

        // 7) 台账复算。
        ResponseEntity<String> rec = rest.getForEntity(url("/" + batchNo + "/reconciliation"),
                String.class);
        assertEquals(HttpStatus.OK, rec.getStatusCode());
        assertTrue(parse(rec.getBody()).path("consistent").asBoolean());
    }

    private ResponseEntity<String> post(String url, Map<String, Object> body) {
        return rest.postForEntity(url, json(body), String.class);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
