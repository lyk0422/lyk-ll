package com.example.starter.compliance.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.time.ComplianceClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/**
 * 真实 HTTP 入口端到端测试：提交→审查→批准→备案→标准更新→第 90 日复审全链路，
 * 以及 requestId 重放响应头、同键异参 409、注销后量产 410 等状态码语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:food_compliance_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
class ComplianceApiIntegrationTest {

    private static final LocalDate DAY_0 = LocalDate.of(2026, 3, 1);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ComplianceRepository repository;

    @Autowired
    private ComplianceClock clock;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clean() {
        repository.deleteBusinessData();
        repository.updateLimit("E202", "BEV01", new BigDecimal("0.500000"),
                System.currentTimeMillis());
        clock.reset();
    }

    /** 将可控时钟设置到某 UTC 日期正午，避免 UTC 日界抖动。 */
    private void setUtcDate(LocalDate date) {
        clock.setFixed(date.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC));
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/compliance" + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(String requestId, Map<String, Object> fields) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(fields);
        body.put("requestId", requestId);
        return body;
    }

    @Test
    void fullHttpWorkflowWithReplayConflictAndCancellation() throws Exception {
        setUtcDate(DAY_0);
        String requestId = Fixtures.requestId();
        Map<String, Object> formula = body(requestId, Map.of(
                "productCode", "P-HTTP",
                "productName", "HTTP 全链路产品",
                "categoryCode", "BEV01",
                "version", 1,
                "additives", List.of(Map.of("additiveCode", "E202", "amount", new BigDecimal("0.3"))),
                "ingredients", List.of()));

        // 1. 提交
        ResponseEntity<String> submit = restTemplate.postForEntity(url("/formulas"),
                new HttpEntity<>(mapper.writeValueAsString(formula), jsonHeaders()), String.class);
        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode submitted = mapper.readTree(submit.getBody());
        long formulaId = submitted.get("formulaId").asLong();
        assertThat(submitted.get("state").asText()).isEqualTo("SUBMITTED");

        // 2. 同键重放：同参返回首次结果，响应头标记重放。
        ResponseEntity<String> replay = restTemplate.postForEntity(url("/formulas"),
                new HttpEntity<>(mapper.writeValueAsString(formula), jsonHeaders()), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        assertThat(mapper.readTree(replay.getBody()).get("replayed").asBoolean()).isTrue();
        assertThat(mapper.readTree(replay.getBody()).get("formulaId").asLong()).isEqualTo(formulaId);

        // 3. 同键异参：409 且错误码稳定。
        Map<String, Object> conflictParams = body(requestId, Map.of(
                "productCode", "P-HTTP",
                "productName", "HTTP 全链路产品",
                "categoryCode", "BEV01",
                "version", 1,
                "additives", List.of(Map.of("additiveCode", "E202", "amount", new BigDecimal("0.45"))),
                "ingredients", List.of()));
        ResponseEntity<String> conflict = restTemplate.postForEntity(url("/formulas"),
                new HttpEntity<>(mapper.writeValueAsString(conflictParams), jsonHeaders()),
                String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(conflict.getBody()).get("code").asText())
                .isEqualTo("REQUEST_PARAM_CONFLICT");

        // 4. 审查通过
        ResponseEntity<String> review = post("/formulas/review",
                body(Fixtures.requestId(), Map.of("formulaId", formulaId)));
        assertThat(review.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode reviewNode = mapper.readTree(review.getBody());
        assertThat(reviewNode.get("report").get("passed").asBoolean()).isTrue();

        // 5. 批准（版本冻结）
        ResponseEntity<String> approve = post("/formulas/approve",
                body(Fixtures.requestId(), Map.of("formulaId", formulaId)));
        assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(approve.getBody()).get("state").asText()).isEqualTo("APPROVED");

        // 6. 量产备案 ACTIVE
        ResponseEntity<String> filed = post("/filings",
                body(Fixtures.requestId(), Map.of("formulaId", formulaId)));
        assertThat(filed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode filingNode = mapper.readTree(filed.getBody());
        long filingId = filingNode.get("filingId").asLong();
        assertThat(filingNode.get("status").asText()).isEqualTo("ACTIVE");

        // 7. 标准收紧 0.5→0.4：受影响备案进入复审观察。
        ResponseEntity<String> standard = post("/standards",
                body(Fixtures.requestId(), Map.of(
                        "additiveCode", "E202",
                        "categoryCode", "BEV01",
                        "maxAmount", new BigDecimal("0.4"))));
        assertThat(standard.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode standardNode = mapper.readTree(standard.getBody());
        assertThat(standardNode.get("watchFilingIds").get(0).asLong()).isEqualTo(filingId);

        ResponseEntity<String> watched = restTemplate.getForEntity(url("/filings/" + filingId),
                String.class);
        JsonNode watchedNode = mapper.readTree(watched.getBody());
        assertThat(watchedNode.get("status").asText()).isEqualTo("REVIEW_WATCH");
        assertThat(watchedNode.get("daysRemaining").asLong()).isEqualTo(90L);

        // 8. 恰第 90 个 UTC 自然日复审受理并恢复 ACTIVE（0.3 ≤ 0.4）。
        setUtcDate(DAY_0.plusDays(90));
        ResponseEntity<String> reReview = post("/filings/review",
                body(Fixtures.requestId(), Map.of("filingId", filingId)));
        assertThat(reReview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(reReview.getBody()).get("status").asText()).isEqualTo("ACTIVE");

        // 9. 注销为终态，再次注销幂等；注销后量产上报 410。
        String cancelKey = Fixtures.requestId();
        Map<String, Object> cancelBody = body(cancelKey, Map.of("filingId", filingId));
        ResponseEntity<String> cancel = post("/filings/cancel", cancelBody);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> cancelReplay = post("/filings/cancel", cancelBody);
        assertThat(cancelReplay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(cancelReplay.getBody()).get("replayed").asBoolean()).isTrue();

        ResponseEntity<String> production = post("/production-records",
                body(Fixtures.requestId(), Map.of(
                        "filingId", filingId,
                        "batchNo", "B-HTTP-1",
                        "quantityKg", new BigDecimal("12.5"))));
        assertThat(production.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(mapper.readTree(production.getBody()).get("code").asText())
                .isEqualTo("FILING_CANCELLED");

        // 10. 版本轨迹可复算、查询稳定。
        ResponseEntity<String> trajectory =
                restTemplate.getForEntity(url("/products/P-HTTP/trajectory"), String.class);
        assertThat(trajectory.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode trajectoryNode = mapper.readTree(trajectory.getBody());
        assertThat(trajectoryNode.get("formulas")).hasSize(1);
        assertThat(trajectoryNode.get("filings")).hasSize(1);
        ResponseEntity<String> trajectoryAgain =
                restTemplate.getForEntity(url("/products/P-HTTP/trajectory"), String.class);
        assertThat(trajectoryAgain.getBody()).isEqualTo(trajectory.getBody());
    }

    @Test
    void carriedOverViolationReturnsPassedFalseAndWholeOrderRejected() throws Exception {
        Map<String, Object> formula = body(Fixtures.requestId(), Map.of(
                "productCode", "P-HTTP-FAIL",
                "productName", "带入超限产品",
                "categoryCode", "BEV01",
                "version", 1,
                "additives", List.of(),
                "ingredients", List.of(Map.of(
                        "ingredientCode", "ING_SAUCE", "ratio", new BigDecimal("0.3")))));
        ResponseEntity<String> submit = post("/formulas", formula);
        long formulaId = mapper.readTree(submit.getBody()).get("formulaId").asLong();

        ResponseEntity<String> review = post("/formulas/review",
                body(Fixtures.requestId(), Map.of("formulaId", formulaId)));
        assertThat(review.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode report = mapper.readTree(review.getBody()).get("report");
        assertThat(report.get("passed").asBoolean()).isFalse();
        assertThat(report.get("items").get(0).get("reason").asText()).isEqualTo("OVER_LIMIT");

        // 违例配方不能批准：409。
        ResponseEntity<String> approve = post("/formulas/approve",
                body(Fixtures.requestId(), Map.of("formulaId", formulaId)));
        assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private ResponseEntity<String> post(String path, Map<String, Object> requestBody)
            throws Exception {
        return restTemplate.postForEntity(url(path),
                new HttpEntity<>(mapper.writeValueAsString(requestBody), jsonHeaders()),
                String.class);
    }
}
