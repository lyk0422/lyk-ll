package com.example.starter.web;

import com.example.starter.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 进口通关核销 REST 入口测试：验证 200/400/404/409/422 状态码经 HTTP 正确返回。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomsApiTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + "/api/customs/declarations" + path;
    }

    private String reviewUrl(String path) {
        return "http://localhost:" + port + "/api/batch-releases" + path;
    }

    @Test
    void registerBindAndLedgerOverHttp() {
        clock.setFixed(Instant.parse("2026-09-01T00:00:00Z"));
        ResponseEntity<Map> registered = post("", registerBody("api-c1", "CIQ-API-1", 100));
        assertEquals(HttpStatus.OK, registered.getStatusCode());
        assertEquals(1, registered.getBody().get("version"));
        assertEquals(100, registered.getBody().get("availableDoses"));

        ResponseEntity<Map> declared = restTemplate.postForEntity(reviewUrl("/declarations"), Map.of(
                "requestId", "api-c-d1",
                "enterpriseName", "合成生物制品厂",
                "productName", "重组冠状病毒疫苗",
                "batchNo", "B-API-C1",
                "documents", allDocuments(),
                "inspections", List.of(
                        Map.of("itemCode", "POTENCY", "measuredValue", "4.0"),
                        Map.of("itemCode", "STERILITY", "measuredValue", "无菌"),
                        Map.of("itemCode", "ABNORMAL_TOXICITY", "measuredValue", "0.10"),
                        Map.of("itemCode", "PH_VALUE", "measuredValue", "7.5"),
                        Map.of("itemCode", "MOISTURE", "measuredValue", "2.0"))), Map.class);
        assertEquals(HttpStatus.OK, declared.getStatusCode());

        ResponseEntity<Map> bound = post("/CIQ-API-1/bindings", Map.of(
                "requestId", "api-c-b1",
                "expectedVersion", 1,
                "batchNo", "B-API-C1",
                "declaredDoses", 40));
        assertEquals(HttpStatus.OK, bound.getStatusCode());
        assertEquals(40, bound.getBody().get("reservedDoses"));
        assertEquals(2, bound.getBody().get("version"));

        ResponseEntity<Map> ledger = restTemplate.getForEntity(url("/CIQ-API-1"), Map.class);
        assertEquals(HttpStatus.OK, ledger.getStatusCode());
        assertEquals(40, ledger.getBody().get("reservedDoses"));
        assertEquals(60, ledger.getBody().get("availableDoses"));
        List<?> bindings = (List<?>) ledger.getBody().get("bindings");
        assertEquals(1, bindings.size());
        assertEquals("B-API-C1", ((Map<?, ?>) bindings.get(0)).get("batchNo"));
    }

    @Test
    void invalidParametersReturn400() {
        clock.setFixed(Instant.parse("2026-09-01T00:00:00Z"));
        ResponseEntity<Map> negative = post("", Map.of(
                "requestId", "api-c2",
                "declarationNo", "CIQ-API-2",
                "enterpriseName", "合成生物制品厂",
                "productName", "重组冠状病毒疫苗",
                "validUntil", "2026-12-31T23:59:59Z",
                "totalDoses", 0));
        assertEquals(HttpStatus.BAD_REQUEST, negative.getStatusCode(), "数量非正必须 400");
        assertEquals("VALIDATION_FAILED", negative.getBody().get("error"));

        ResponseEntity<Map> missing = post("", Map.of("requestId", "api-c3"));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
    }

    @Test
    void unknownDeclarationReturns404() {
        ResponseEntity<Map> ledger = restTemplate.getForEntity(url("/CIQ-NONE"), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, ledger.getStatusCode());
        assertEquals("DECLARATION_NOT_FOUND", ledger.getBody().get("error"));
    }

    @Test
    void versionConflictAndBusinessRejectionOverHttp() {
        clock.setFixed(Instant.parse("2026-09-01T00:00:00Z"));
        post("", registerBody("api-c4", "CIQ-API-4", 10));

        ResponseEntity<Map> versionConflict = post("/CIQ-API-4/revision", Map.of(
                "requestId", "api-c-r1",
                "expectedVersion", 9,
                "newTotalDoses", 20));
        assertEquals(HttpStatus.CONFLICT, versionConflict.getStatusCode());
        assertEquals("VERSION_CONFLICT", versionConflict.getBody().get("error"));

        ResponseEntity<Map> notIncreasing = post("/CIQ-API-4/revision", Map.of(
                "requestId", "api-c-r2",
                "expectedVersion", 1,
                "newTotalDoses", 5));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, notIncreasing.getStatusCode());
        assertEquals("REVISION_NOT_INCREASING", notIncreasing.getBody().get("error"));
    }

    private Map<String, Object> registerBody(String requestId, String declarationNo, int totalDoses) {
        return Map.of(
                "requestId", requestId,
                "declarationNo", declarationNo,
                "enterpriseName", "合成生物制品厂",
                "productName", "重组冠状病毒疫苗",
                "validUntil", "2026-12-31T23:59:59Z",
                "totalDoses", totalDoses);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map> post(String path, Object body) {
        return restTemplate.postForEntity(url(path), body, Map.class);
    }
}
