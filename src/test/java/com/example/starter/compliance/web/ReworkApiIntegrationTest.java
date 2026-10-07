package com.example.starter.compliance.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.time.ComplianceClock;
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

/**
 * 返工料投用真实 HTTP 测试：登记返工料（冻结来源配方版本与过敏原集合）、
 * 100 公斤量产投 20 公斤返工料（恰等 20% 上限）成功、超比例 422、料账勾稽查询。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:food_compliance_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
class ReworkApiIntegrationTest {

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
                "additives", List.of(Map.of("additiveCode", "E300", "amount",
                        new BigDecimal("0.2"))),
                "ingredients", List.of()))).getBody()).get("formulaId").asLong();
        post(api("/formulas/review"), body(Map.of("formulaId", formulaId)));
        post(api("/formulas/approve"), body(Map.of("formulaId", formulaId)));
        return mapper.readTree(post(api("/filings"), body(Map.of("formulaId", formulaId)))
                .getBody()).get("filingId").asLong();
    }

    @Test
    void hundredKgProductionWithTwentyKgReworkOverHttp() throws Exception {
        // 1. 来源含乳配方量产成功，产出返工料来源记录。
        long sourceFiling = approveAndFile("P-HSRC", 1);
        post(lc("/formula-allergens"), body(Map.of(
                "productCode", "P-HSRC", "formulaVersion", 1,
                "allergens", List.of("ALG_MILK"), "expectedVersion", 0, "registeredAt", T0)));
        post(lc("/lines"), body(Map.of(
                "lineCode", "L-HSRC", "lineName", "来源线", "registeredAt", T0)));
        ResponseEntity<String> source = post(lc("/controlled-productions"), body(Map.of(
                "filingId", sourceFiling, "lineCode", "L-HSRC", "occupiedVersion", 1,
                "batchNo", "B-SRC", "quantityKg", new BigDecimal("50"),
                "reportedAt", T0 + 1000)));
        assertThat(source.getStatusCode()).isEqualTo(HttpStatus.OK);
        long sourceRecordId =
                mapper.readTree(source.getBody()).get("productionRecordId").asLong();

        // 2. 登记返工料 RW-HTTP-1：冻结来源配方版本与过敏原集合，可用 25 公斤。
        ResponseEntity<String> lot = post(lc("/rework-lots"), body(Map.of(
                "lotCode", "RW-HTTP-1", "productionRecordId", sourceRecordId,
                "productCode", "P-HSRC", "availableKg", new BigDecimal("25"),
                "expectedVersion", 0, "registeredAt", T0 + 2000)));
        assertThat(lot.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode lotNode = mapper.readTree(lot.getBody());
        assertThat(lotNode.get("version").asInt()).isEqualTo(1);
        assertThat(lotNode.get("allergens").get(0).asText()).isEqualTo("ALG_MILK");
        assertThat(lotNode.get("balanceKg").asText()).isEqualTo("25");

        // 3. 目标配方声明乳；100 公斤量产投 20 公斤返工料（恰等 20% 上限）→ 200。
        long targetFiling = approveAndFile("P-HTGT", 1);
        post(lc("/formula-allergens"), body(Map.of(
                "productCode", "P-HTGT", "formulaVersion", 1,
                "allergens", List.of("ALG_MILK"), "expectedVersion", 0, "registeredAt", T0)));
        post(lc("/lines"), body(Map.of(
                "lineCode", "L-HTGT", "lineName", "目标线", "registeredAt", T0)));
        ResponseEntity<String> accepted = post(lc("/controlled-productions"), body(Map.of(
                "filingId", targetFiling, "lineCode", "L-HTGT", "occupiedVersion", 1,
                "batchNo", "B-100", "quantityKg", new BigDecimal("100"),
                "reportedAt", T0 + 3000,
                "reworkInputs", List.of(Map.of("lotCode", "RW-HTTP-1",
                        "quantityKg", new BigDecimal("20"))))));
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode acceptedNode = mapper.readTree(accepted.getBody());
        assertThat(acceptedNode.get("reworkConsumptions")).hasSize(1);
        assertThat(acceptedNode.get("reworkConsumptions").get(0).get("lotCode").asText())
                .isEqualTo("RW-HTTP-1");
        assertThat(acceptedNode.get("reworkConsumptions").get(0).get("quantityKg").asText())
                .isEqualTo("20");

        // 4. 料账勾稽：原量 25、消费 20、余额 5、版本 2。
        ResponseEntity<String> ledger =
                restTemplate.getForEntity(lc("/rework-lots/RW-HTTP-1"), String.class);
        assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode ledgerNode = mapper.readTree(ledger.getBody());
        assertThat(ledgerNode.get("lot").get("availableKg").asText()).isEqualTo("25");
        assertThat(ledgerNode.get("lot").get("consumedKg").asText()).isEqualTo("20");
        assertThat(ledgerNode.get("lot").get("balanceKg").asText()).isEqualTo("5");
        assertThat(ledgerNode.get("lot").get("version").asInt()).isEqualTo(2);
        assertThat(ledgerNode.get("lot").get("productionRecordId").asLong())
                .isEqualTo(sourceRecordId);
        assertThat(ledgerNode.get("consumptions")).hasSize(1);

        // 5. 20.001 公斤超 20% 上限 → 422，精确比较不靠舍入。
        ResponseEntity<String> exceeded = post(lc("/controlled-productions"), body(Map.of(
                "filingId", targetFiling, "lineCode", "L-HTGT", "occupiedVersion", 2,
                "batchNo", "B-101", "quantityKg", new BigDecimal("100"),
                "reportedAt", T0 + 4000,
                "reworkInputs", List.of(Map.of("lotCode", "RW-HTTP-1",
                        "quantityKg", new BigDecimal("20.001"))))));
        assertThat(exceeded.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(exceeded.getBody()).get("code").asText())
                .isEqualTo("REWORK_RATIO_EXCEEDED");
    }
}
