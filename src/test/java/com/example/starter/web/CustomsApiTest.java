package com.example.starter.web;

import com.example.starter.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进口通关流程真实 HTTP 入口测试：验证 200/400/404/409/422 经 REST 层正确返回，
 * 并端到端跑通 登记→绑定→签发核销→台账查询；同时确认国内批次不受通关门禁影响。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomsApiTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant ISSUE_AT = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-31T00:00:00Z");

    private String customsUrl(String path) {
        return "http://localhost:" + port + "/api/customs" + path;
    }

    private String releaseUrl(String path) {
        return "http://localhost:" + port + "/api/batch-releases" + path;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> registerBody(String requestId, String no, int total) {
        return Map.of(
                "requestId", requestId,
                "customsNo", no,
                "enterpriseName", IMPORT_ENTERPRISE,
                "productName", IMPORT_PRODUCT,
                "validUntil", VALID_UNTIL.toString(),
                "totalQuantity", total,
                "at", T0.toString());
    }

    @Test
    void registerBindIssueAndLedgerEndToEndOverHttp() {
        // 登记
        ResponseEntity<Map> created = restTemplate.postForEntity(
                customsUrl("/declarations"), registerBody("api-reg", "CI-HTTP", 100), Map.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        assertEquals(1, created.getBody().get("version"));
        assertEquals("ACTIVE", created.getBody().get("status"));

        // 准备一个可签发的进口子批
        clock.setFixed(T0);
        post(releaseUrl("/declarations"), Map.of(
                "requestId", "api-declare",
                "enterpriseName", IMPORT_ENTERPRISE,
                "productName", IMPORT_PRODUCT,
                "batchNo", "B-HTTP",
                "documents", allDocuments(),
                "inspections", List.of(
                        Map.of("itemCode", "POTENCY", "measuredValue", "4.0"),
                        Map.of("itemCode", "STERILITY", "measuredValue", "无菌"),
                        Map.of("itemCode", "ABNORMAL_TOXICITY", "measuredValue", "0.10"),
                        Map.of("itemCode", "PH_VALUE", "measuredValue", "7.5"),
                        Map.of("itemCode", "MOISTURE", "measuredValue", "2.0"))));
        restTemplate.postForEntity(releaseUrl("/B-HTTP/evaluation"), null, Map.class);
        clock.setFixed(ISSUE_AT);
        restTemplate.postForEntity(releaseUrl("/B-HTTP/publicity-settlement"), null, Map.class);

        // 绑定预占 30
        ResponseEntity<Map> bound = post(customsUrl("/declarations/CI-HTTP/bindings"), Map.of(
                "requestId", "api-bind",
                "customsNo", "CI-HTTP",
                "batchNo", "B-HTTP",
                "declaredQuantity", 30,
                "expectedVersion", 1,
                "at", ISSUE_AT.toString()));
        assertEquals(HttpStatus.OK, bound.getStatusCode());
        assertEquals(2, bound.getBody().get("version"));
        assertEquals(30, bound.getBody().get("reservedQuantity"));

        // 签发：原子转核销
        ResponseEntity<Map> issued = post(releaseUrl("/B-HTTP/issues"), Map.of("requestId", "api-issue"));
        assertEquals(HttpStatus.OK, issued.getStatusCode());

        // 台账：已核销 30、预占 0、余量 70
        ResponseEntity<Map> ledger = restTemplate.getForEntity(
                customsUrl("/declarations/CI-HTTP/ledger"), Map.class);
        assertEquals(HttpStatus.OK, ledger.getStatusCode());
        assertEquals(0, ledger.getBody().get("reservedQuantity"));
        assertEquals(30, ledger.getBody().get("writtenOffQuantity"));
        assertEquals(70, ledger.getBody().get("availableQuantity"));
        List<?> bindings = (List<?>) ledger.getBody().get("bindings");
        assertEquals(1, bindings.size());
        assertEquals("WRITTEN_OFF", ((Map<?, ?>) bindings.get(0)).get("status"));
    }

    @Test
    void invalidRegisterReturns400AndUnknownLedgerReturns404() {
        // 总数非正：400
        ResponseEntity<Map> invalid = post(customsUrl("/declarations"), Map.of(
                "requestId", "api-bad",
                "customsNo", "CI-BAD",
                "enterpriseName", IMPORT_ENTERPRISE,
                "productName", IMPORT_PRODUCT,
                "validUntil", VALID_UNTIL.toString(),
                "totalQuantity", 0,
                "at", T0.toString()));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals("VALIDATION_FAILED", invalid.getBody().get("error"));

        // 台账不存在：404
        ResponseEntity<Map> missing = restTemplate.getForEntity(
                customsUrl("/declarations/NO-SUCH/ledger"), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("CUSTOMS_NOT_FOUND", missing.getBody().get("error"));
    }

    @Test
    void businessRejectionReturns422AndVersionConflictReturns409OverHttp() {
        // 总量 10 的通关单，绑定 11 支：超余量 422
        post(customsUrl("/declarations"), registerBody("api-422-reg", "CI-422-HTTP", 10));
        prepareOneIssuableImportBatch("B-422-HTTP", "api-422-d");
        ResponseEntity<Map> overflow = post(customsUrl("/declarations/CI-422-HTTP/bindings"), Map.of(
                "requestId", "api-422-bind",
                "customsNo", "CI-422-HTTP",
                "batchNo", "B-422-HTTP",
                "declaredQuantity", 11,
                "expectedVersion", 1,
                "at", T0.toString()));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, overflow.getStatusCode());
        assertEquals("INSUFFICIENT_CUSTOMS_QUANTITY", overflow.getBody().get("error"));
        assertEquals(0, restTemplate.getForEntity(
                customsUrl("/declarations/CI-422-HTTP/ledger"), Map.class).getBody().get("reservedQuantity"),
                "422 失败不得预占");

        // 另一张通关单：绑定不存在的子批 404
        post(customsUrl("/declarations"), registerBody("api-cap-reg", "CI-CAP-HTTP", 100));
        ResponseEntity<Map> noBatch = post(customsUrl("/declarations/CI-CAP-HTTP/bindings"), Map.of(
                "requestId", "api-bind-nobatch",
                "customsNo", "CI-CAP-HTTP",
                "batchNo", "B-NO-HTTP",
                "declaredQuantity", 10,
                "expectedVersion", 1,
                "at", T0.toString()));
        assertEquals(HttpStatus.NOT_FOUND, noBatch.getStatusCode());
        assertEquals("REVIEW_NOT_FOUND", noBatch.getBody().get("error"));

        // 成功绑定一次（版本推进到 2，预占 10）；随后同键改支数重放：409 幂等指纹冲突。
        prepareOneIssuableImportBatch("B-VC-HTTP", "api-vc-d");
        post(customsUrl("/declarations/CI-CAP-HTTP/bindings"), Map.of(
                "requestId", "api-bind-ok",
                "customsNo", "CI-CAP-HTTP",
                "batchNo", "B-VC-HTTP",
                "declaredQuantity", 10,
                "expectedVersion", 1,
                "at", T0.toString()));
        // 同键异参重放冲突：409
        ResponseEntity<Map> conflict = post(customsUrl("/declarations/CI-CAP-HTTP/bindings"), Map.of(
                "requestId", "api-bind-ok",
                "customsNo", "CI-CAP-HTTP",
                "batchNo", "B-VC-HTTP",
                "declaredQuantity", 11,
                "expectedVersion", 1,
                "at", T0.toString()));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", conflict.getBody().get("error"));
        assertTrue(((Number) restTemplate.getForEntity(
                customsUrl("/declarations/CI-CAP-HTTP/ledger"), Map.class).getBody().get("reservedQuantity"))
                .intValue() == 10, "同键异参不得改变首次预占");
    }

    /** 经 service 准备一个可签发（公示期满生效）的进口子批，供 HTTP 通关接口绑定。 */
    private void prepareOneIssuableImportBatch(String batchNo, String declareId) {
        clock.setFixed(T0);
        prepareIssueableImportBatch(declareId, batchNo);
        clock.setFixed(ISSUE_AT);
        reviewService.settlePublicity(batchNo);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map> post(String url, Object body) {
        return restTemplate.postForEntity(url, body, Map.class);
    }
}
