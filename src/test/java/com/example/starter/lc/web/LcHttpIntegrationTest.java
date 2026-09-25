package com.example.starter.lc.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 端到端 HTTP 测试：交单→拒付→放弃不符点→付款合成示例的完整链路与 requestId 重放/异参 409。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LcHttpIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String baseUrl;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM discrepancy");
        jdbcTemplate.update("DELETE FROM submission_document");
        jdbcTemplate.update("DELETE FROM doc_submission");
        jdbcTemplate.update("DELETE FROM lc_credit");
        jdbcTemplate.update("DELETE FROM idempotent_record");
        baseUrl = "http://localhost:" + port;
    }

    @Test
    void fullPresentationRefusalWaiverPaymentFlowOverHttp() {
        post("/api/lc/credits", Map.of(
                "requestId", "web-lc-1",
                "lcNo", "LC-WEB-001",
                "applicant", "ACME TRADING LTD",
                "beneficiary", "SHENZHEN EXPORT CO",
                "currency", "USD",
                "totalAmount", new BigDecimal("10000.00"),
                "expiryDate", "2027-12-31"), HttpStatus.CREATED);

        ResponseEntity<Map> presented = post("/api/lc/presentations", Map.of(
                "requestId", "web-present-1",
                "lcNo", "LC-WEB-001",
                "drawAmount", new BigDecimal("8000.00"),
                "presentedAt", "2026-09-18T10:00:00Z",
                "documents", List.of(
                        Map.of("docType", "BILL_OF_LADING", "docRef", "BL-2026-001"),
                        Map.of("docType", "COMMERCIAL_INVOICE", "docRef", "INV-2026-009"))),
                HttpStatus.CREATED);
        String batchNo = (String) presented.getBody().get("batchNo");
        assertThat(batchNo).isEqualTo("LC-WEB-001-1");
        assertThat(presented.getBody().get("deadlineAt")).isEqualTo("2026-09-25T17:00:00Z");

        List<Map<String, String>> discrepancies = List.of(
                Map.of("docType", "BILL_OF_LADING", "description", "装船日期晚于信用证最迟装运日"),
                Map.of("docType", "COMMERCIAL_INVOICE", "description", "发票金额与汇票金额不一致"));

        Map<String, Object> refuseRequest = Map.of(
                "requestId", "web-refuse-1",
                "batchNo", batchNo,
                "refusedAt", "2026-09-25T16:59:59Z",
                "discrepancies", discrepancies);
        ResponseEntity<Map> refused = post("/api/lc/refusals", refuseRequest, HttpStatus.OK);
        assertThat(refused.getBody().get("status")).isEqualTo("REFUSED");

        // 同键同参重放：首次响应原样返回。
        ResponseEntity<Map> replayed = post("/api/lc/refusals", refuseRequest, HttpStatus.OK);
        assertThat(replayed.getBody().get("status")).isEqualTo("REFUSED");

        // 同键异参：409 且单据状态不变。
        ResponseEntity<Map> conflict = post("/api/lc/refusals", Map.of(
                "requestId", "web-refuse-1",
                "batchNo", batchNo,
                "refusedAt", "2026-09-25T16:59:58Z",
                "discrepancies", discrepancies), HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("errorCode")).isEqualTo("IDEMPOTENCY_CONFLICT");

        ResponseEntity<Map> waived = post("/api/lc/waivers", Map.of(
                "requestId", "web-waive-1",
                "batchNo", batchNo,
                "applicant", "ACME TRADING LTD",
                "waivedAt", "2026-09-25T17:30:00Z",
                "discrepancies", discrepancies), HttpStatus.OK);
        assertThat(waived.getBody().get("status")).isEqualTo("ACCEPTED");

        ResponseEntity<Map> paid = post("/api/lc/payments", Map.of(
                "requestId", "web-pay-1",
                "batchNo", batchNo,
                "paidAt", "2026-09-25T18:00:00Z"), HttpStatus.OK);
        assertThat(paid.getBody().get("status")).isEqualTo("PAID");

        // 查询信用证：可用额度核减 8000。
        ResponseEntity<Map> lc = get("/api/lc/credits/LC-WEB-001", HttpStatus.OK);
        assertThat(new BigDecimal(lc.getBody().get("availableAmount").toString()))
                .isEqualByComparingTo("2000.00");

        // 重复付款（新 requestId）：返回首次结果，额度不再变动。
        ResponseEntity<Map> paidAgain = post("/api/lc/payments", Map.of(
                "requestId", "web-pay-2",
                "batchNo", batchNo,
                "paidAt", "2026-09-26T09:00:00Z"), HttpStatus.OK);
        assertThat(paidAgain.getBody().get("status")).isEqualTo("PAID");
        assertThat(paidAgain.getBody().get("paidAt")).isEqualTo("2026-09-25T18:00:00Z");
    }

    @Test
    void lateRefusalAt1700Returns422AndBatchDeemedAccepted() {
        post("/api/lc/credits", Map.of(
                "requestId", "web-lc-late",
                "lcNo", "LC-WEB-LATE",
                "applicant", "ACME TRADING LTD",
                "beneficiary", "SHENZHEN EXPORT CO",
                "currency", "USD",
                "totalAmount", new BigDecimal("10000.00"),
                "expiryDate", "2027-12-31"), HttpStatus.CREATED);
        ResponseEntity<Map> presented = post("/api/lc/presentations", Map.of(
                "requestId", "web-present-late",
                "lcNo", "LC-WEB-LATE",
                "drawAmount", new BigDecimal("8000.00"),
                "presentedAt", "2026-09-18T10:00:00Z",
                "documents", List.of(
                        Map.of("docType", "BILL_OF_LADING", "docRef", "BL-1"))),
                HttpStatus.CREATED);
        String batchNo = (String) presented.getBody().get("batchNo");

        ResponseEntity<Map> refusal = post("/api/lc/refusals", Map.of(
                "requestId", "web-refuse-late",
                "batchNo", batchNo,
                "refusedAt", "2026-09-25T17:00:00Z",
                "discrepancies", List.of(
                        Map.of("docType", "BILL_OF_LADING", "description", "不符"))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refusal.getBody().get("errorCode")).isEqualTo("REFUSAL_TOO_LATE");

        ResponseEntity<Map> batch = get("/api/lc/presentations/" + batchNo, HttpStatus.OK);
        assertThat(batch.getBody().get("status")).isEqualTo("DEEMED_ACCEPTED");
    }

    @Test
    void validationErrorReturns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl + "/api/lc/credits",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("{\"requestId\":\"\",\"lcNo\":\"X\"}", headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errorCode")).isEqualTo("INVALID_REQUEST");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(String path, Object body, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl + path, org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> get(String path, HttpStatus expected) {
        ResponseEntity<Map> response = restTemplate.getForEntity(baseUrl + path, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response;
    }
}
