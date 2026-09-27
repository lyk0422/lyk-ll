package com.example.starter.web;

import com.example.starter.AbstractIntegrationTest;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 通关单 REST 入口测试：验证 200/400/404/409/422 状态码经 HTTP 层正确返回。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomsApiTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + "/api/customs-declarations" + path;
    }

    private String batchUrl(String path) {
        return "http://localhost:" + port + "/api/batch-releases" + path;
    }

    private ResponseEntity<Map> post(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
    }

    private Map<String, Object> registerBody(String requestId, String declarationNo, int total) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("declarationNo", declarationNo);
        body.put("enterpriseName", "合成生物制品厂");
        body.put("productName", "重组冠状病毒疫苗");
        body.put("validUntil", "2026-12-31T00:00:00Z");
        body.put("totalQuantity", total);
        return body;
    }

    @Test
    void registerAndQueryLedgerOverHttp() {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
        ResponseEntity<Map> registered = post(url(""), registerBody("api-c1", "CUS-API-1", 100));
        assertEquals(HttpStatus.OK, registered.getStatusCode());
        Map<?, ?> view = registered.getBody();
        assertNotNull(view);
        assertEquals("CUS-API-1", view.get("declarationNo"));
        assertEquals(1, view.get("version"));
        assertEquals(100, view.get("remainingQuantity"));

        ResponseEntity<Map> ledger = restTemplate.getForEntity(url("/CUS-API-1"), Map.class);
        assertEquals(HttpStatus.OK, ledger.getStatusCode());
        assertEquals(0, ledger.getBody().get("reservedQuantity"));
        assertEquals(0, ledger.getBody().get("consumedQuantity"));
    }

    @Test
    void invalidParametersReturn400() {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
        // 数量为 0：参数非法
        ResponseEntity<Map> zeroQuantity = post(url(""), registerBody("api-c2", "CUS-API-2", 0));
        assertEquals(HttpStatus.BAD_REQUEST, zeroQuantity.getStatusCode());

        // 缺有效截止时刻：参数非法
        Map<String, Object> missingValidUntil = registerBody("api-c3", "CUS-API-3", 100);
        missingValidUntil.remove("validUntil");
        ResponseEntity<Map> missing = post(url(""), missingValidUntil);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());

        // 绑定数量为负：参数非法
        post(url(""), registerBody("api-c4", "CUS-API-4", 100));
        ResponseEntity<Map> negative = post(url("/CUS-API-4/bindings"), Map.of(
                "requestId", "api-c5", "batchNo", "B-X", "declaredQuantity", -1, "expectedVersion", 1));
        assertEquals(HttpStatus.BAD_REQUEST, negative.getStatusCode());
    }

    @Test
    void notFoundAndBusinessRejectionReturn404And422() {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
        // 通关单不存在：404
        ResponseEntity<Map> missing = restTemplate.getForEntity(url("/CUS-NONE"), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());

        // 重复登记同一单号：422 业务拒绝
        post(url(""), registerBody("api-c6", "CUS-API-6", 100));
        ResponseEntity<Map> duplicate = post(url(""), registerBody("api-c7", "CUS-API-6", 100));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, duplicate.getStatusCode());

        // 绑定不存在的批次：404
        ResponseEntity<Map> noBatch = post(url("/CUS-API-6/bindings"), Map.of(
                "requestId", "api-c8", "batchNo", "B-NONE", "declaredQuantity", 10, "expectedVersion", 1));
        assertEquals(HttpStatus.NOT_FOUND, noBatch.getStatusCode());
    }

    @Test
    void versionConflictReturns409OverHttp() {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
        post(url(""), registerBody("api-c9", "CUS-API-9", 100));
        ResponseEntity<Map> stale = post(url("/CUS-API-9/revocation"), Map.of(
                "requestId", "api-c10", "expectedVersion", 7));
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
    }

    @Test
    void fullImportFlowOverHttpEndsWithConsumedLedger() {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
        post(url(""), registerBody("api-f1", "CUS-API-F", 80));

        // 申报进口批次并推进到公示期满生效
        Map<String, Object> declareBody = new HashMap<>();
        declareBody.put("requestId", "api-f2");
        declareBody.put("enterpriseName", "合成生物制品厂");
        declareBody.put("productName", "重组冠状病毒疫苗");
        declareBody.put("batchNo", "B-API-F");
        declareBody.put("documents", allDocuments());
        declareBody.put("inspections", List.of(
                Map.of("itemCode", "POTENCY", "measuredValue", "4.0"),
                Map.of("itemCode", "STERILITY", "measuredValue", "无菌"),
                Map.of("itemCode", "ABNORMAL_TOXICITY", "measuredValue", "0.10"),
                Map.of("itemCode", "PH_VALUE", "measuredValue", "7.5"),
                Map.of("itemCode", "MOISTURE", "measuredValue", "2.0")));
        assertEquals(HttpStatus.OK, post(batchUrl("/declarations"), declareBody).getStatusCode());
        assertEquals(HttpStatus.OK, post(batchUrl("/B-API-F/evaluation"), Map.of()).getStatusCode());

        // 绑定预占 50 支
        ResponseEntity<Map> bound = post(url("/CUS-API-F/bindings"), Map.of(
                "requestId", "api-f3", "batchNo", "B-API-F", "declaredQuantity", 50, "expectedVersion", 1));
        assertEquals(HttpStatus.OK, bound.getStatusCode());
        assertEquals(50, bound.getBody().get("reservedQuantity"));

        // 公示期满生效并签发
        clock.setFixed(Instant.parse("2026-09-14T00:00:00Z"));
        assertEquals(HttpStatus.OK,
                post(batchUrl("/B-API-F/publicity-settlement"), Map.of()).getStatusCode());
        ResponseEntity<Map> issued = post(batchUrl("/B-API-F/issues"), Map.of("requestId", "api-f4"));
        assertEquals(HttpStatus.OK, issued.getStatusCode());
        assertEquals("ISSUED", issued.getBody().get("status"));

        // 台账：预占转已核销
        ResponseEntity<Map> ledger = restTemplate.getForEntity(url("/CUS-API-F"), Map.class);
        assertEquals(0, ledger.getBody().get("reservedQuantity"));
        assertEquals(50, ledger.getBody().get("consumedQuantity"));
        assertEquals(30, ledger.getBody().get("remainingQuantity"));
    }
}
