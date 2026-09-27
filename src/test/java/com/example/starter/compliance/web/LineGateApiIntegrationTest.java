package com.example.starter.compliance.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.time.ComplianceClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
 * 清线凭证门禁真实 HTTP 端到端测试：登记线路/过敏原→确认凭证→受控量产主流程，
 * 以及 400/404/409/422 状态码语义、requestId 重放响应头与旧入口兼容。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:food_compliance_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
class LineGateApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ComplianceRepository repository;

    @Autowired
    private ComplianceClock clock;

    @BeforeEach
    void clean() {
        repository.deleteBusinessData();
        repository.updateLimit("E202", "BEV01", new BigDecimal("0.500000"),
                System.currentTimeMillis());
        clock.reset();
    }

    @Test
    void controlledProductionGateEndToEnd() throws Exception {
        // 登记生产线与两版配方过敏原：前批 {MILK}，新批 {}。
        ResponseEntity<JsonNode> line = post("/lines",
                Map.of("lineCode", "LINE-HTTP"));
        assertThat(line.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(line.getBody().get("occupancyVersion").asInt()).isEqualTo(1);

        long prevFiling = approvedAndFiled("P-HTTP-PREV", List.of("MILK"));
        long newFiling = approvedAndFiled("P-HTTP-NEW", List.of());

        // 第一批：无需凭证。
        ResponseEntity<JsonNode> first = post("/lines/production-records",
                productionBody("LINE-HTTP", prevFiling, "B-H1", 1, null));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("occupancyVersion").asInt()).isEqualTo(1);

        // 切换：前批 MILK 未声明，无凭证 422。
        ResponseEntity<JsonNode> rejected = post("/lines/production-records",
                productionBody("LINE-HTTP", newFiling, "B-H2", 2, null));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejected.getBody().get("code").asText())
                .isEqualTo("CLEANING_VOUCHER_REQUIRED");

        // 确认凭证（双人、固定时刻与占用版本 2）。
        long cleanedAt = clock.nowMillis() - 60_000;
        ResponseEntity<JsonNode> voucher = post("/cleaning-vouchers", Map.of(
                "lineCode", "LINE-HTTP",
                "allergens", List.of("MILK"),
                "cleanedAt", cleanedAt,
                "expectedOccupancyVersion", 2,
                "operator", "op-http",
                "reviewer", "rev-http"));
        assertThat(voucher.getStatusCode()).isEqualTo(HttpStatus.OK);
        long voucherId = voucher.getBody().get("voucherId").asLong();
        assertThat(voucher.getBody().get("effectiveStatus").asText()).isEqualTo("ISSUED");

        // 引用凭证受控量产：原子写记录、推进版本、核销凭证。
        ResponseEntity<JsonNode> accepted = post("/lines/production-records",
                productionBody("LINE-HTTP", newFiling, "B-H2", 2, voucherId));
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody().get("voucherId").asLong()).isEqualTo(voucherId);

        ResponseEntity<JsonNode> lineAfter = get("/lines/LINE-HTTP");
        assertThat(lineAfter.getBody().get("occupancyVersion").asInt()).isEqualTo(3);
        ResponseEntity<JsonNode> voucherAfter = get("/cleaning-vouchers/" + voucherId);
        assertThat(voucherAfter.getBody().get("status").asText()).isEqualTo("CONSUMED");

        // 旧入口（未指定生产线）保持兼容：不经过门禁。
        ResponseEntity<JsonNode> legacy = post("/production-records", Map.of(
                "filingId", newFiling,
                "batchNo", "B-LEGACY",
                "quantityKg", 1));
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void httpErrorSemanticsAndIdempotentReplayHeader() throws Exception {
        // 参数非法 400：缺 requestId。
        ResponseEntity<JsonNode> badRequest = postRaw("/lines", Map.of("lineCode", "LINE-E1"));
        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        post("/lines", Map.of("lineCode", "LINE-E1"));
        // 对象不存在 404。
        ResponseEntity<JsonNode> notFound = post("/cleaning-vouchers", Map.of(
                "lineCode", "LINE-MISSING",
                "allergens", List.of("MILK"),
                "cleanedAt", clock.nowMillis(),
                "expectedOccupancyVersion", 1,
                "operator", "op",
                "reviewer", "rev"));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().get("code").asText()).isEqualTo("LINE_NOT_FOUND");

        // 业务拒绝 422：操作人与复核人相同。
        ResponseEntity<JsonNode> unprocessable = post("/cleaning-vouchers", Map.of(
                "lineCode", "LINE-E1",
                "allergens", List.of("MILK"),
                "cleanedAt", clock.nowMillis(),
                "expectedOccupancyVersion", 1,
                "operator", "same",
                "reviewer", "same"));
        assertThat(unprocessable.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 占用版本冲突 409。
        ResponseEntity<JsonNode> conflict = post("/cleaning-vouchers", Map.of(
                "lineCode", "LINE-E1",
                "allergens", List.of("MILK"),
                "cleanedAt", clock.nowMillis(),
                "expectedOccupancyVersion", 9,
                "operator", "op",
                "reviewer", "rev"));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // requestId 幂等：同键同参重放带 X-Idempotent-Replay 头，同键异参 409。
        String requestId = Fixtures.requestId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lineCode", "LINE-E2");
        ResponseEntity<JsonNode> first = post("/lines", body, requestId);
        assertThat(first.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("false");
        ResponseEntity<JsonNode> replay = post("/lines", body, requestId);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        assertThat(replay.getBody().get("replayed").asBoolean()).isTrue();

        ResponseEntity<JsonNode> paramConflict = post("/lines",
                Map.of("lineCode", "LINE-E3"), requestId);
        assertThat(paramConflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(paramConflict.getBody().get("code").asText())
                .isEqualTo("REQUEST_PARAM_CONFLICT");
    }

    // ---------------- HTTP 辅助 ----------------

    private String url(String path) {
        return "http://localhost:" + port + "/api/compliance" + path;
    }

    private ResponseEntity<JsonNode> post(String path, Map<String, Object> fields) {
        return post(path, fields, Fixtures.requestId());
    }

    private ResponseEntity<JsonNode> post(String path, Map<String, Object> fields,
            String requestId) {
        Map<String, Object> body = new LinkedHashMap<>(fields);
        body.put("requestId", requestId);
        return postRaw(path, body);
    }

    private ResponseEntity<JsonNode> postRaw(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url(path), org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path) {
        return restTemplate.getForEntity(url(path), JsonNode.class);
    }

    private Map<String, Object> productionBody(String lineCode, long filingId, String batchNo,
            int occupancyVersion, Long voucherId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lineCode", lineCode);
        body.put("filingId", filingId);
        body.put("batchNo", batchNo);
        body.put("quantityKg", 5);
        body.put("expectedOccupancyVersion", occupancyVersion);
        if (voucherId != null) {
            body.put("voucherId", voucherId);
        }
        return body;
    }

    /** 经 HTTP 完成 提交→审查→批准→备案→过敏原登记，返回备案主键。 */
    private long approvedAndFiled(String productCode, List<String> allergens) throws Exception {
        Map<String, Object> formula = new LinkedHashMap<>();
        formula.put("productCode", productCode);
        formula.put("productName", productCode + "-产品");
        formula.put("categoryCode", "BEV01");
        formula.put("version", 1);
        formula.put("additives", List.of(Map.of("additiveCode", "E202", "amount", 0.2)));
        formula.put("ingredients", List.of());
        ResponseEntity<JsonNode> submitted = post("/formulas", formula);
        long formulaId = submitted.getBody().get("formulaId").asLong();

        post("/formulas/review", Map.of("formulaId", formulaId));
        post("/formulas/approve", Map.of("formulaId", formulaId));
        if (allergens != null) {
            ResponseEntity<JsonNode> registered = post("/formulas/allergens", Map.of(
                    "formulaId", formulaId,
                    "allergens", allergens));
            assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<JsonNode> filed = post("/filings", Map.of("formulaId", formulaId));
        assertThat(filed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return filed.getBody().get("filingId").asLong();
    }
}
