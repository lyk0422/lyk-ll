package com.example.starter.web;

import com.example.starter.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 通关核销 REST 入口测试：登记/绑定/台账经 HTTP 返回 200/400/404/422。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomsApiTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void fixClock() {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/customs" + path;
    }

    @Test
    void registerBindAndLedgerOverHttp() {
        Map<String, Object> registerBody = Map.of(
                "requestId", "api-c-1",
                "declarationNo", "CD-API-1",
                "enterpriseName", "API 测试厂",
                "productName", "API 测试苗",
                "validUntil", "2026-10-31",
                "totalQuantity", 100);
        ResponseEntity<Map> registered = post("/declarations", registerBody);
        assertEquals(HttpStatus.OK, registered.getStatusCode());
        assertEquals(1, registered.getBody().get("version"));
        assertEquals(100, registered.getBody().get("availableQuantity"));

        // 申报一个同企业品种的进口子批。
        Map<String, Object> declareBody = Map.of(
                "requestId", "api-c-1-d",
                "enterpriseName", "API 测试厂",
                "productName", "API 测试苗",
                "batchNo", "B-API-IMP-1",
                "documents", allDocuments(),
                "inspections", List.of(
                        Map.of("itemCode", "POTENCY", "measuredValue", "4.0"),
                        Map.of("itemCode", "STERILITY", "measuredValue", "无菌"),
                        Map.of("itemCode", "ABNORMAL_TOXICITY", "measuredValue", "0.10"),
                        Map.of("itemCode", "PH_VALUE", "measuredValue", "7.5"),
                        Map.of("itemCode", "MOISTURE", "measuredValue", "2.0")));
        ResponseEntity<Map> declared = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/batch-releases/declarations", declareBody, Map.class);
        assertEquals(HttpStatus.OK, declared.getStatusCode());

        Map<String, Object> bindBody = Map.of(
                "requestId", "api-c-1-b",
                "declarationNo", "CD-API-1",
                "batchNo", "B-API-IMP-1",
                "declaredQuantity", 40,
                "expectedVersion", 1,
                "occurredAt", "2026-09-07T02:00:00Z");
        ResponseEntity<Map> bound = post("/bindings", bindBody);
        assertEquals(HttpStatus.OK, bound.getStatusCode());
        assertEquals("RESERVED", bound.getBody().get("status"));

        ResponseEntity<Map> ledger = restTemplate.getForEntity(
                url("/declarations/CD-API-1"), Map.class);
        assertEquals(HttpStatus.OK, ledger.getStatusCode());
        assertEquals(40, ledger.getBody().get("reservedQuantity"));
        assertEquals(60, ledger.getBody().get("availableQuantity"));
        assertEquals(2, ledger.getBody().get("version"));

        ResponseEntity<List> reservations = restTemplate.getForEntity(
                url("/declarations/CD-API-1/reservations"), List.class);
        assertEquals(HttpStatus.OK, reservations.getStatusCode());
        assertEquals(1, reservations.getBody().size());
    }

    @Test
    void invalidRegisterParametersReturn400() {
        Map<String, Object> missingField = Map.of(
                "requestId", "api-c-2",
                "declarationNo", "CD-API-2",
                "enterpriseName", "API 测试厂",
                "productName", "API 测试苗",
                "validUntil", "2026-10-31",
                "totalQuantity", 0);
        ResponseEntity<Map> response = post("/declarations", missingField);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "可核销总数必须为正，非法参数 400");
    }

    @Test
    void unknownDeclarationReturns404AndBusinessRejectionReturns422() {
        ResponseEntity<Map> missing = restTemplate.getForEntity(
                url("/declarations/CD-UNKNOWN"), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());

        Map<String, Object> bindBody = Map.of(
                "requestId", "api-c-3",
                "declarationNo", "CD-UNKNOWN",
                "batchNo", "B-UNKNOWN",
                "declaredQuantity", 10,
                "expectedVersion", 1,
                "occurredAt", "2026-09-07T02:00:00Z");
        ResponseEntity<Map> bindMissing = post("/bindings", bindBody);
        assertEquals(HttpStatus.NOT_FOUND, bindMissing.getStatusCode());
    }

    private ResponseEntity<Map> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url(path), org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }
}
