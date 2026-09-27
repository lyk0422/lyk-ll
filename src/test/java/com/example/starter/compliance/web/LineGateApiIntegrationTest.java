package com.example.starter.compliance.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.repo.LineGateRepository;
import com.example.starter.compliance.service.ComplianceService;
import com.example.starter.compliance.service.LineGateService;
import com.example.starter.compliance.time.ComplianceClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
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
 * 清线凭证门禁真实 HTTP 端到端测试：登记过敏原/生产线、双人确认凭证、受控量产主流程，
 * 以及 400/404/409/422 状态码语义、幂等重放头与查询端点。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:food_compliance_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
class LineGateApiIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ComplianceRepository repository;

    @Autowired
    private LineGateRepository gateRepository;

    @Autowired
    private ComplianceClock clock;

    @Autowired
    private ComplianceService complianceService;

    @Autowired
    private LineGateService gateService;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clean() {
        repository.deleteBusinessData();
        gateRepository.deleteGateBusinessData();
        clock.setFixed(T0);
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/compliance" + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> post(String path, Object body) throws Exception {
        return restTemplate.postForEntity(url(path),
                new HttpEntity<>(mapper.writeValueAsString(body), jsonHeaders()), String.class);
    }

    private Map<String, Object> body(String requestId, Map<String, Object> fields) {
        Map<String, Object> body = new LinkedHashMap<>(fields);
        body.put("requestId", requestId);
        return body;
    }

    /** 与 {@link #body} 相同，但允许字段值为 null（如无凭证受理时 certificateId 省略为 null）。 */
    private Map<String, Object> bodyWithNulls(String requestId, Object... kv) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            body.put((String) kv[i], kv[i + 1]);
        }
        body.put("requestId", requestId);
        return body;
    }

    private long approvedFiledFormula(String productCode, String... allergens) {
        long formulaId = complianceService.submitFormula(Fixtures.formula(productCode, 1,
                List.of(Fixtures.additive("E300", "0.1")), List.of())).body().formulaId();
        complianceService.reviewFormula(Fixtures.ref(formulaId));
        complianceService.approveFormula(Fixtures.ref(formulaId));
        complianceService.fileFiling(Fixtures.file(formulaId));
        if (allergens.length > 0) {
            gateService.registerFormulaAllergens(
                    Fixtures.allergens(formulaId, 0, List.of(allergens)));
        }
        return formulaId;
    }

    private long filingIdOf(long formulaId) {
        return repository.findFilingByFormulaId(formulaId).orElseThrow().id();
    }

    @Test
    void fullGateWorkflowOverHttpWithReplayAndStatusCodes() throws Exception {
        long milkEggFormula = approvedFiledFormula("P-GATE-A", "MILK", "EGG");
        long milkOnlyFormula = approvedFiledFormula("P-GATE-B", "MILK");
        long milkEggFiling = filingIdOf(milkEggFormula);
        long milkOnlyFiling = filingIdOf(milkOnlyFormula);

        // 1. 登记生产线：占用版本初始为 1。
        ResponseEntity<String> line = post("/production-lines",
                body(Fixtures.requestId(), Map.of("lineCode", "L-HTTP", "lineName", "一号线")));
        assertThat(line.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(line.getBody()).get("occupancyVersion").asInt()).isEqualTo(1);

        // 2. 首产无需凭证。
        String firstKey = Fixtures.requestId();
        Map<String, Object> firstProduction = bodyWithNulls(firstKey,
                "lineCode", "L-HTTP",
                "expectedVersion", 1,
                "filingId", milkEggFiling,
                "certificateId", null,
                "batchNo", "B-1",
                "quantityKg", new BigDecimal("120"),
                "occurredAt", T0.toEpochMilli());
        ResponseEntity<String> first = post("/controlled-productions", firstProduction);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode firstNode = mapper.readTree(first.getBody());
        assertThat(firstNode.get("occupancyVersion").asInt()).isEqualTo(1);
        // 同键重放：同一记录、重放头。
        ResponseEntity<String> replay = post("/controlled-productions", firstProduction);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        assertThat(mapper.readTree(replay.getBody()).get("recordId").asLong())
                .isEqualTo(firstNode.get("recordId").asLong());

        // 3. 切到仅含 MILK 的新批，缺凭证：422。
        ResponseEntity<String> missing = post("/controlled-productions",
                bodyWithNulls(Fixtures.requestId(),
                        "lineCode", "L-HTTP",
                        "expectedVersion", 2,
                        "filingId", milkOnlyFiling,
                        "certificateId", null,
                        "batchNo", "B-2",
                        "quantityKg", new BigDecimal("80"),
                        "occurredAt", T0.toEpochMilli() + 60_000));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(missing.getBody()).get("code").asText())
                .isEqualTo("CERTIFICATE_REQUIRED");

        // 4. 同一人操作与复核：422。
        ResponseEntity<String> samePerson = post("/cleaning-certificates",
                body(Fixtures.requestId(), Map.of(
                        "lineCode", "L-HTTP",
                        "operatorId", "u1",
                        "reviewerId", "u1",
                        "cleanedAt", T0.toEpochMilli(),
                        "allergenCodes", List.of("EGG"))));
        assertThat(samePerson.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(samePerson.getBody()).get("code").asText())
                .isEqualTo("CLEANING_SAME_PERSON");

        // 5. 双人确认覆盖 EGG 的凭证。
        ResponseEntity<String> cert = post("/cleaning-certificates",
                body(Fixtures.requestId(), Map.of(
                        "lineCode", "L-HTTP",
                        "operatorId", "u1",
                        "reviewerId", "u2",
                        "cleanedAt", T0.toEpochMilli(),
                        "allergenCodes", List.of("EGG"))));
        assertThat(cert.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode certNode = mapper.readTree(cert.getBody());
        long certificateId = certNode.get("certificateId").asLong();
        assertThat(certNode.get("expectedVersion").asInt()).isEqualTo(2);
        assertThat(certNode.get("valid").asBoolean()).isTrue();

        // 6. 恰满 2 小时：凭证失效，量产 422。
        long exactTwoHours = T0.toEpochMilli() + LineGateService.CERTIFICATE_TTL_MILLIS;
        ResponseEntity<String> expired = post("/controlled-productions",
                body(Fixtures.requestId(), Map.of(
                        "lineCode", "L-HTTP",
                        "expectedVersion", 2,
                        "filingId", milkOnlyFiling,
                        "certificateId", certificateId,
                        "batchNo", "B-EXPIRED",
                        "quantityKg", new BigDecimal("80"),
                        "occurredAt", exactTwoHours)));
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(expired.getBody()).get("code").asText())
                .isEqualTo("CERTIFICATE_EXPIRED");

        // 7. 窗口内差 1 毫秒受理成功，凭证核销、线路推进到 3。
        ResponseEntity<String> accepted = post("/controlled-productions",
                body(Fixtures.requestId(), Map.of(
                        "lineCode", "L-HTTP",
                        "expectedVersion", 2,
                        "filingId", milkOnlyFiling,
                        "certificateId", certificateId,
                        "batchNo", "B-2",
                        "quantityKg", new BigDecimal("80"),
                        "occurredAt", exactTwoHours - 1)));
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(accepted.getBody()).get("certificateId").asLong())
                .isEqualTo(certificateId);

        // 8. 凭证已核销，重复使用：422；查询凭证状态为 CONSUMED。
        ResponseEntity<String> certView =
                restTemplate.getForEntity(url("/cleaning-certificates/" + certificateId), String.class);
        assertThat(mapper.readTree(certView.getBody()).get("status").asText()).isEqualTo("CONSUMED");

        // 9. 错误语义：参数非法 400、线路不存在 404、占用版本不匹配 409。
        ResponseEntity<String> badRequest = post("/controlled-productions",
                bodyWithNulls(Fixtures.requestId(),
                        "lineCode", "L-HTTP",
                        "expectedVersion", 0,
                        "filingId", milkOnlyFiling,
                        "certificateId", null,
                        "batchNo", "B",
                        "quantityKg", new BigDecimal("1"),
                        "occurredAt", T0.toEpochMilli()));
        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> notFound = post("/controlled-productions",
                bodyWithNulls(Fixtures.requestId(),
                        "lineCode", "NO-LINE",
                        "expectedVersion", 1,
                        "filingId", milkOnlyFiling,
                        "certificateId", null,
                        "batchNo", "B",
                        "quantityKg", new BigDecimal("1"),
                        "occurredAt", T0.toEpochMilli()));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> versionConflict = post("/controlled-productions",
                bodyWithNulls(Fixtures.requestId(),
                        "lineCode", "L-HTTP",
                        "expectedVersion", 99,
                        "filingId", milkOnlyFiling,
                        "certificateId", null,
                        "batchNo", "B",
                        "quantityKg", new BigDecimal("1"),
                        "occurredAt", T0.toEpochMilli()));
        assertThat(versionConflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(versionConflict.getBody()).get("code").asText())
                .isEqualTo("LINE_VERSION_CONFLICT");

        // 10. 线路查询：占用版本为 3，两笔受控记录。
        ResponseEntity<String> lineView =
                restTemplate.getForEntity(url("/production-lines/L-HTTP"), String.class);
        assertThat(mapper.readTree(lineView.getBody()).get("occupancyVersion").asInt()).isEqualTo(3);
        ResponseEntity<String> productions = restTemplate.getForEntity(
                url("/production-lines/L-HTTP/controlled-productions"), String.class);
        assertThat(mapper.readTree(productions.getBody())).hasSize(2);
    }

    @Test
    void allergenRegistrationOverHttpEnforcesVersioningAndUnknownCode() throws Exception {
        long formulaId = approvedFiledFormula("P-GATE-C");

        // 首次版本必须为 0：传 1 返回 409。
        ResponseEntity<String> wrongFirst = post("/formula-allergens",
                body(Fixtures.requestId(), Map.of(
                        "formulaId", formulaId,
                        "expectedVersion", 1,
                        "allergenCodes", List.of("MILK"))));
        assertThat(wrongFirst.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 首次成功，版本 1。
        ResponseEntity<String> first = post("/formula-allergens",
                body(Fixtures.requestId(), Map.of(
                        "formulaId", formulaId,
                        "expectedVersion", 0,
                        "allergenCodes", List.of("MILK", "EGG"))));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(first.getBody()).get("version").asInt()).isEqualTo(1);

        // 未知过敏原：400。
        ResponseEntity<String> unknown = post("/formula-allergens",
                body(Fixtures.requestId(), Map.of(
                        "formulaId", formulaId,
                        "expectedVersion", 1,
                        "allergenCodes", List.of("UNKNOWN_X"))));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(unknown.getBody()).get("code").asText())
                .isEqualTo("ALLERGEN_NOT_FOUND");

        // 查询返回已登记集合（字典序）。
        ResponseEntity<String> view =
                restTemplate.getForEntity(url("/formula-allergens/" + formulaId), String.class);
        JsonNode codes = mapper.readTree(view.getBody()).get("allergenCodes");
        assertThat(codes.get(0).asText()).isEqualTo("EGG");
        assertThat(codes.get(1).asText()).isEqualTo("MILK");
    }
}
