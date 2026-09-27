package com.example.starter.compliance.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.time.ComplianceClock;

/**
 * 清线门禁新增受控入口真实 HTTP 测试：
 * 换配方含遗留过敏原时无凭证 422、双人确认凭证后 200、参数非法 400、对象不存在 404；
 * 原未指定生产线入口 /production-records 保持可用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:food_compliance_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
class LineControlApiIntegrationTest {

    private static final long T0 = 1_800_000_000_000L;

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

    private String api(String path) {
        return "http://localhost:" + port + "/api/compliance" + path;
    }

    private String lc(String path) {
        return api("/line-control" + path);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> post(String url, Map<String, Object> body) throws Exception {
        return restTemplate.postForEntity(url,
                new HttpEntity<>(mapper.writeValueAsString(body), jsonHeaders()), String.class);
    }

    private Map<String, Object> body(Map<String, Object> fields) {
        Map<String, Object> map = new LinkedHashMap<>(fields);
        map.putIfAbsent("requestId", java.util.UUID.randomUUID().toString());
        return map;
    }

    private long approveAndFile(String productCode, int version) throws Exception {
        long formulaId = mapper.readTree(post(api("/formulas"), body(Map.of(
                "productCode", productCode,
                "productName", productCode + "-产品",
                "categoryCode", "BEV01",
                "version", version,
                "additives", List.of(Map.of("additiveCode", "E300", "amount", new BigDecimal("0.2"))),
                "ingredients", List.of()))).getBody()).get("formulaId").asLong();
        post(api("/formulas/review"), body(Map.of("formulaId", formulaId)));
        post(api("/formulas/approve"), body(Map.of("formulaId", formulaId)));
        return mapper.readTree(post(api("/filings"), body(Map.of("formulaId", formulaId)))
                .getBody()).get("filingId").asLong();
    }

    @Test
    void fullLineControlHttpFlowAndErrorCodes() throws Exception {
        long filingMilk = approveAndFile("P-H1", 1);
        long filingEgg = approveAndFile("P-H2", 1);

        // 1. 登记两版本过敏原。
        ResponseEntity<String> reg1 = post(lc("/formula-allergens"), body(Map.of(
                "productCode", "P-H1", "formulaVersion", 1,
                "allergens", List.of("ALG_MILK"), "expectedVersion", 0, "registeredAt", T0)));
        assertThat(reg1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(reg1.getBody()).get("version").asInt()).isEqualTo(1);
        post(lc("/formula-allergens"), body(Map.of(
                "productCode", "P-H2", "formulaVersion", 1,
                "allergens", List.of("ALG_EGG"), "expectedVersion", 0, "registeredAt", T0)));

        // 2. 登记线路，最近量产版本为含乳配方。
        ResponseEntity<String> line = post(lc("/lines"), body(Map.of(
                "lineCode", "L-H", "lineName", "HTTP 线",
                "lastProductCode", "P-H1", "lastFormulaVersion", 1, "registeredAt", T0)));
        assertThat(line.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(line.getBody()).get("occupiedVersion").asInt()).isEqualTo(1);

        // 3. 换含蛋配方：乳为遗留过敏原，未带凭证 → 422。
        ResponseEntity<String> rejected = post(lc("/controlled-productions"), body(Map.of(
                "filingId", filingEgg, "lineCode", "L-H", "occupiedVersion", 1,
                "batchNo", "B-1", "quantityKg", new BigDecimal("10"),
                "reportedAt", T0 + 1000)));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(rejected.getBody()).get("code").asText())
                .isEqualTo("CLEARANCE_CERTIFICATE_REQUIRED");

        // 4. 双人确认清线凭证（不同人员）。
        ResponseEntity<String> samePerson = post(lc("/clearance-certificates"), body(Map.of(
                "certificateNo", "CERT-BAD", "lineCode", "L-H",
                "allergens", List.of("ALG_MILK"),
                "operatorCode", "OP-1", "reviewerCode", "OP-1",
                "clearedAt", T0 + 500, "confirmedAt", T0 + 800)));
        assertThat(samePerson.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> cert = post(lc("/clearance-certificates"), body(Map.of(
                "certificateNo", "CERT-1", "lineCode", "L-H",
                "allergens", List.of("ALG_MILK"),
                "operatorCode", "OP-1", "reviewerCode", "RV-1",
                "clearedAt", T0 + 500, "confirmedAt", T0 + 800)));
        assertThat(cert.getStatusCode()).isEqualTo(HttpStatus.OK);
        long certificateId = mapper.readTree(cert.getBody()).get("certificateId").asLong();

        // 5. 携带凭证受控量产成功；线路占用版本推进为 2、最近版本更新。
        ResponseEntity<String> accepted = post(lc("/controlled-productions"), body(Map.of(
                "filingId", filingEgg, "lineCode", "L-H", "occupiedVersion", 1,
                "batchNo", "B-2", "quantityKg", new BigDecimal("10"),
                "certificateId", certificateId, "reportedAt", T0 + 1000)));
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode acceptedNode = mapper.readTree(accepted.getBody());
        assertThat(acceptedNode.get("occupiedVersion").asInt()).isEqualTo(1);

        ResponseEntity<String> lineAfter =
                restTemplate.getForEntity(lc("/lines/L-H"), String.class);
        JsonNode lineNode = mapper.readTree(lineAfter.getBody());
        assertThat(lineNode.get("occupiedVersion").asInt()).isEqualTo(2);
        assertThat(lineNode.get("lastProductCode").asText()).isEqualTo("P-H2");
        assertThat(lineNode.get("lastFormulaVersion").asInt()).isEqualTo(1);

        // 凭证已核销。
        ResponseEntity<String> certAfter =
                restTemplate.getForEntity(lc("/clearance-certificates/" + certificateId),
                        String.class);
        assertThat(mapper.readTree(certAfter.getBody()).get("status").asText())
                .isEqualTo("CONSUMED");

        // 6. 参数非法 400 / 不存在 404。
        ResponseEntity<String> invalid = post(lc("/controlled-productions"), body(Map.of(
                "filingId", filingEgg, "lineCode", "L-H", "occupiedVersion", 0,
                "batchNo", "B", "quantityKg", new BigDecimal("10"),
                "reportedAt", T0 + 2000)));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> missingLine = post(lc("/controlled-productions"), body(Map.of(
                "filingId", filingEgg, "lineCode", "L-NOPE", "occupiedVersion", 1,
                "batchNo", "B", "quantityKg", new BigDecimal("10"),
                "reportedAt", T0 + 2000)));
        assertThat(missingLine.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 7. 原未指定生产线入口仍兼容可用。
        ResponseEntity<String> legacy = post(api("/production-records"), body(Map.of(
                "filingId", filingEgg, "batchNo", "B-LEGACY",
                "quantityKg", new BigDecimal("3"))));
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
