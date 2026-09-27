package com.example.starter.compliance.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.time.ComplianceClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 换配方清线受控入口真实 HTTP 端到端测试：
 * 登记过敏原/生产线、双人凭证、受控量产门禁（需凭证切换）、查询证据链、400/404/422 与重放响应头。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:food_compliance_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
class ClearanceApiIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

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
        clock.reset();
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/compliance" + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) throws Exception {
        return restTemplate.postForEntity(url(path),
                new HttpEntity<>(mapper.writeValueAsString(body), jsonHeaders()), String.class);
    }

    private static Map<String, Object> body(Map<String, Object> fields) {
        Map<String, Object> map = new LinkedHashMap<>(fields);
        map.putIfAbsent("requestId", key());
        return map;
    }

    private long approveAndFile(String product, int version) throws Exception {
        ResponseEntity<String> submit = post("/formulas", body(Map.of(
                "productCode", product,
                "productName", product + "-产品",
                "categoryCode", "BEV01",
                "version", version,
                "additives", List.of(Map.of("additiveCode", "E202", "amount", new BigDecimal("0.3"))),
                "ingredients", List.of())));
        long formulaId = mapper.readTree(submit.getBody()).get("formulaId").asLong();
        post("/formulas/review", body(Map.of("formulaId", formulaId)));
        post("/formulas/approve", body(Map.of("formulaId", formulaId)));
        ResponseEntity<String> filed = post("/filings", body(Map.of("formulaId", formulaId)));
        return mapper.readTree(filed.getBody()).get("filingId").asLong();
    }

    private long formulaIdOf(long filingId) {
        return repository.findFilingById(filingId).orElseThrow().formulaId();
    }

    @Test
    void controlledProductionGateOverHttp() throws Exception {
        String product = "P-HTTP-CLEAR";
        long filing1 = approveAndFile(product, 1);
        long formula1 = formulaIdOf(filing1);

        // 登记 v1 过敏原 [花生, 乳]。
        ResponseEntity<String> reg1 = post("/formulas/allergens", body(Map.of(
                "formulaId", formula1,
                "allergens", List.of("AL_PEANUT", "AL_MILK"))));
        assertThat(reg1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(reg1.getBody()).get("version").asInt()).isEqualTo(1);

        // 登记生产线。
        ResponseEntity<String> line = post("/lines", body(Map.of(
                "lineCode", "L-HTTP", "lineName", "HTTP 线")));
        assertThat(line.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 首批无需凭证（不传 certificateId 即视为空）。
        ResponseEntity<String> first = post("/controlled-productions", body(new LinkedHashMap<>(
                Map.of(
                "lineCode", "L-HTTP", "filingId", filing1, "batchNo", "B-1",
                "quantityKg", new BigDecimal("100"), "occupiedVersion", 1,
                "occurredAt", T0.toEpochMilli()))));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // v2 只声明乳；花生成为带入过敏原。
        long filing2 = approveAndFile(product, 2);
        long formula2 = formulaIdOf(filing2);
        post("/formulas/allergens", body(Map.of(
                "formulaId", formula2,
                "allergens", List.of("AL_MILK"))));

        // 不传凭证 → 422 CERTIFICATE_REQUIRED。
        Map<String, Object> missingMap = new LinkedHashMap<>();
        missingMap.put("lineCode", "L-HTTP");
        missingMap.put("filingId", filing2);
        missingMap.put("batchNo", "B-2");
        missingMap.put("quantityKg", new BigDecimal("100"));
        missingMap.put("occupiedVersion", 2);
        missingMap.put("occurredAt", T0.toEpochMilli() + 3_600_000L);
        missingMap.put("certificateId", null);
        ResponseEntity<String> missing = post("/controlled-productions", body(missingMap));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(missing.getBody()).get("code").asText())
                .isEqualTo("CERTIFICATE_REQUIRED");

        // 操作人=复核人 → 422。
        ResponseEntity<String> samePerson = post("/clearance-certificates", body(Map.of(
                "lineCode", "L-HTTP", "operatorId", "SAME", "reviewerId", "SAME",
                "cleanedAt", T0.toEpochMilli(), "confirmedAt", T0.toEpochMilli(),
                "coveredAllergens", List.of("AL_PEANUT"))));
        assertThat(samePerson.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 双人确认凭证（覆盖花生）。
        long confirmedAt = T0.toEpochMilli() + 3_600_000L;
        Map<String, Object> certBody = body(Map.of(
                "lineCode", "L-HTTP", "operatorId", "OP-1", "reviewerId", "RV-1",
                "cleanedAt", confirmedAt - 600_000L, "confirmedAt", confirmedAt,
                "coveredAllergens", List.of("AL_PEANUT")));
        ResponseEntity<String> cert = post("/clearance-certificates", certBody);
        assertThat(cert.getStatusCode()).isEqualTo(HttpStatus.OK);
        long certId = mapper.readTree(cert.getBody()).get("certificateId").asLong();

        // 同键同参重放凭证确认：返回首次冻结结果（同 certId），重放头为 true，不新增凭证。
        ResponseEntity<String> certReplay = post("/clearance-certificates", certBody);
        assertThat(certReplay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(certReplay.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        assertThat(mapper.readTree(certReplay.getBody()).get("certificateId").asLong())
                .isEqualTo(certId);

        // 恰满 2 小时失效：occurredAt = confirmedAt + 2h → 422。
        ResponseEntity<String> expired = post("/controlled-productions", body(Map.of(
                "lineCode", "L-HTTP", "filingId", filing2, "batchNo", "B-EXP",
                "quantityKg", new BigDecimal("100"), "occupiedVersion", 2,
                "occurredAt", confirmedAt + 2L * 3_600_000L, "certificateId", certId)));
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(expired.getBody()).get("code").asText())
                .isEqualTo("CERTIFICATE_EXPIRED");

        // 有效期内（+1h）受理成功并核销凭证。
        Map<String, Object> prodBody = body(Map.of(
                "lineCode", "L-HTTP", "filingId", filing2, "batchNo", "B-2",
                "quantityKg", new BigDecimal("100"), "occupiedVersion", 2,
                "occurredAt", confirmedAt + 3_600_000L, "certificateId", certId));
        ResponseEntity<String> accepted = post("/controlled-productions", prodBody);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode acceptedNode = mapper.readTree(accepted.getBody());
        assertThat(acceptedNode.get("certificateId").asLong()).isEqualTo(certId);
        assertThat(acceptedNode.get("occupiedVersion").asInt()).isEqualTo(2);

        // 证据链查询：线路两笔量产、凭证已核销、占用版本推进到 3。
        ResponseEntity<String> history = restTemplate.getForEntity(
                url("/lines/L-HTTP/controlled-productions"), String.class);
        assertThat(mapper.readTree(history.getBody())).hasSize(2);
        ResponseEntity<String> certView = restTemplate.getForEntity(
                url("/clearance-certificates/" + certId), String.class);
        JsonNode certNode = mapper.readTree(certView.getBody());
        assertThat(certNode.get("status").asText()).isEqualTo("CONSUMED");
        assertThat(certNode.get("validUntil").asLong()).isEqualTo(confirmedAt + 2L * 3_600_000L);
        ResponseEntity<String> lineView = restTemplate.getForEntity(url("/lines/L-HTTP"),
                String.class);
        assertThat(mapper.readTree(lineView.getBody()).get("occupiedVersion").asInt()).isEqualTo(3);

        // 非法参数 400（缺精确时刻）与对象不存在 404。
        ResponseEntity<String> badRequest = post("/controlled-productions", Map.of(
                "requestId", key(),
                "lineCode", "L-HTTP", "filingId", filing2, "batchNo", "B-400",
                "quantityKg", new BigDecimal("1"), "occupiedVersion", 3));
        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> notFound = restTemplate.getForEntity(url("/lines/NO-LINE"),
                String.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 原未指定生产线的入口仍可用（保持兼容）。
        ResponseEntity<String> legacy = post("/production-records", Map.of(
                "requestId", key(),
                "filingId", filing2, "batchNo", "B-LEGACY",
                "quantityKg", new BigDecimal("5")));
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
