package com.example.starter.web;

import com.example.starter.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 HTTP 入口测试：验证 200/400/403/409 状态码经 REST 层正确返回。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchReleaseApiTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + "/api/batch-releases" + path;
    }

    @Test
    void declareWithMissingDocumentsReturnsNotifiedCorrectionPayload() {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
        List<String> docs = new java.util.ArrayList<>(allDocuments());
        docs.remove("LABEL_INSERT");
        Map<String, Object> body = Map.of(
                "requestId", "api-1",
                "enterpriseName", "API 测试厂",
                "productName", "API 测试苗",
                "batchNo", "B-API-1",
                "documents", docs,
                "inspections", List.of(
                        Map.of("itemCode", "POTENCY", "measuredValue", "4.0"),
                        Map.of("itemCode", "STERILITY", "measuredValue", "无菌"),
                        Map.of("itemCode", "ABNORMAL_TOXICITY", "measuredValue", "0.10"),
                        Map.of("itemCode", "PH_VALUE", "measuredValue", "7.5"),
                        Map.of("itemCode", "MOISTURE", "measuredValue", "2.0")));

        ResponseEntity<Map> response = post("/declarations", body);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> view = response.getBody();
        assertEquals("PENDING_CORRECTION", view.get("status"));
        assertEquals(List.of("LABEL_INSERT"), view.get("missingDocuments"));
        assertEquals("2026-09-14", view.get("correctionDeadline"));
    }

    @Test
    void idempotentReplayOverHttpReturnsFirstResultAndConflictReturns409() {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
        Map<String, Object> body = Map.of(
                "requestId", "api-2",
                "enterpriseName", "API 测试厂",
                "productName", "API 测试苗",
                "batchNo", "B-API-2",
                "documents", allDocuments(),
                "inspections", List.of(
                        Map.of("itemCode", "POTENCY", "measuredValue", "4.0"),
                        Map.of("itemCode", "STERILITY", "measuredValue", "无菌"),
                        Map.of("itemCode", "ABNORMAL_TOXICITY", "measuredValue", "0.10"),
                        Map.of("itemCode", "PH_VALUE", "measuredValue", "7.5"),
                        Map.of("itemCode", "MOISTURE", "measuredValue", "2.0")));
        ResponseEntity<Map> first = post("/declarations", body);
        ResponseEntity<Map> replay = post("/declarations", body);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertEquals(first.getBody().get("reviewId"), replay.getBody().get("reviewId"));

        Map<String, Object> tampered = new java.util.HashMap<>(body);
        tampered.put("inspections", List.of(
                Map.of("itemCode", "POTENCY", "measuredValue", "5.0"),
                Map.of("itemCode", "STERILITY", "measuredValue", "无菌"),
                Map.of("itemCode", "ABNORMAL_TOXICITY", "measuredValue", "0.10"),
                Map.of("itemCode", "PH_VALUE", "measuredValue", "7.5"),
                Map.of("itemCode", "MOISTURE", "measuredValue", "2.0")));
        ResponseEntity<Map> conflict = post("/declarations", tampered);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", conflict.getBody().get("error"));
    }

    @Test
    void invalidJsonAndMissingFieldsReturn400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> malformed = restTemplate.exchange(url("/declarations"),
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("{ not json", headers), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());

        ResponseEntity<Map> invalid = post("/declarations",
                Map.of("requestId", "api-3", "documents", List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals("VALIDATION_FAILED", invalid.getBody().get("error"));
    }

    @Test
    void unknownBatchReturns404() {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("/B-NOT-EXIST"), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("REVIEW_NOT_FOUND", response.getBody().get("error"));
    }

    @Test
    void suspensionReturns403OverHttp() {
        // 连续 3 批不合格后第 4 次申报必须 403
        for (int i = 1; i <= 3; i++) {
            String batch = "B-API-SUS-" + i;
            post("/declarations", declareBody("api-sus-" + i, "暂停厂", "暂停苗", batch, "3.0"));
            ResponseEntity<Map> eval = restTemplate.postForEntity(
                    url("/" + batch + "/evaluation"), null, Map.class);
            assertEquals(HttpStatus.OK, eval.getStatusCode());
        }
        ResponseEntity<Map> blocked = post("/declarations",
                declareBody("api-sus-4", "暂停厂", "暂停苗", "B-API-SUS-4", "3.0"));
        assertEquals(HttpStatus.FORBIDDEN, blocked.getStatusCode());
        assertEquals("ENTERPRISE_SUSPENDED", blocked.getBody().get("error"));
    }

    private Map<String, Object> declareBody(String requestId, String enterprise, String product,
                                            String batchNo, String potency) {
        return Map.of(
                "requestId", requestId,
                "enterpriseName", enterprise,
                "productName", product,
                "batchNo", batchNo,
                "documents", allDocuments(),
                "inspections", List.of(
                        Map.of("itemCode", "POTENCY", "measuredValue", potency),
                        Map.of("itemCode", "STERILITY", "measuredValue", "无菌"),
                        Map.of("itemCode", "ABNORMAL_TOXICITY", "measuredValue", "0.10"),
                        Map.of("itemCode", "PH_VALUE", "measuredValue", "7.5"),
                        Map.of("itemCode", "MOISTURE", "measuredValue", "2.0")));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map> post(String path, Object body) {
        return restTemplate.postForEntity(url(path), body, Map.class);
    }
}
