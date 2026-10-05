package com.example.starter.forestry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.forestry.support.BusinessClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 真实 HTTP 端到端测试（随机端口 + H2）：验证状态码、JSON 字段与申请→审核→发证→
 * 采伐确认→更新验收→归档完整链路，以及 422/409/400 错误语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebApiEndToEndTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BusinessClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM restriction_list");
        jdbc.update("DELETE FROM renewal_obligation");
        jdbc.update("DELETE FROM quota_ledger");
        jdbc.update("DELETE FROM harvest_license");
        jdbc.update("DELETE FROM harvest_application");
        clock.setToday(LocalDate.of(2026, 3, 1));
    }

    private HttpEntity<String> json(String rawJson) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new HttpEntity<>(rawJson, headers);
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    @Test
    void fullFlowOverHttp() throws Exception {
        // 1. 申请（马尾松单株蓄积 0.5：40 株 × 0.5 = 20.00 立方米，设计值与复核口径一致）
        var applyReq = """
                {"requestId":"web-apply","operatorId":"OP-W","subplotId":"SP-001",
                 "speciesCode":"PINUS_MASSONIANA","harvestType":"MAIN","treeCount":40,"designedVolume":20.00}
                """;
        ResponseEntity<String> created = rest.postForEntity(url("/api/applications"),
                json(applyReq), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode app = body(created);
        assertThat(app.get("status").asText()).isEqualTo("SUBMITTED");
        String appId = app.get("id").asText();

        // 2. 审核通过
        var reviewReq = """
                {"approved":true,"comment":"材料齐全"}
                """;
        ResponseEntity<String> reviewed = rest.postForEntity(
                url("/api/applications/" + appId + "/review"),
                json(reviewReq), String.class);
        assertThat(reviewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(reviewed).get("status").asText()).isEqualTo("REVIEW_PASSED");

        // 3. 发证
        var issueReq = """
                {"requestId":"web-issue","applicationId":"%s"}
                """.formatted(appId);
        ResponseEntity<String> licensed = rest.postForEntity(url("/api/licenses"),
                json(issueReq), String.class);
        assertThat(licensed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode license = body(licensed);
        assertThat(license.get("status").asText()).isEqualTo("ISSUED");
        assertThat(license.get("expireDate").asText()).isEqualTo("2026-05-30");
        String licenseId = license.get("id").asText();

        // 4. 采伐确认（恰第 90 日）
        clock.setToday(LocalDate.of(2026, 5, 30));
        var confirmReq = """
                {"requestId":"web-confirm","licenseId":"%s"}
                """.formatted(licenseId);
        ResponseEntity<String> confirmed = rest.postForEntity(url("/api/licenses/confirm"),
                json(confirmReq), String.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(confirmed).get("status").asText()).isEqualTo("HARVESTED");

        // 5. 更新验收（合格率恰达 0.85）
        clock.setToday(LocalDate.of(2027, 3, 1));
        var accReq = """
                {"requestId":"web-acc","applicationId":"%s","qualifiedCount":85,"totalCount":100}
                """.formatted(appId);
        ResponseEntity<String> accepted = rest.postForEntity(url("/api/renewals/acceptance"),
                json(accReq), String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(accepted).get("status").asText()).isEqualTo("PASSED");
        assertThat(new BigDecimal(body(accepted).get("qualifiedRate").asText()))
                .isEqualByComparingTo("0.8500");

        // 6. 归档
        ResponseEntity<String> archived = rest.postForEntity(
                url("/api/applications/" + appId + "/archive"), null, String.class);
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(archived).get("status").asText()).isEqualTo("ARCHIVED");
    }

    @Test
    void protectedZoneApplyReturns422() throws Exception {
        var req = """
                {"requestId":"web-422","operatorId":"OP-W","subplotId":"SP-005",
                 "speciesCode":"PINUS_MASSONIANA","harvestType":"MAIN","treeCount":20,"designedVolume":10.00}
                """;
        ResponseEntity<String> response = rest.postForEntity(url("/api/applications"),
                json(req), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body(response).get("errorCode").asText()).isEqualTo("PROTECTED_ZONE_FORBIDDEN");
    }

    @Test
    void sameKeyDifferentParamsReturns409() {
        var req1 = """
                {"requestId":"web-409","operatorId":"OP-W","subplotId":"SP-001",
                 "speciesCode":"PINUS_MASSONIANA","harvestType":"MAIN","treeCount":20,"designedVolume":10.00}
                """;
        var req2 = """
                {"requestId":"web-409","operatorId":"OP-W","subplotId":"SP-001",
                 "speciesCode":"PINUS_MASSONIANA","harvestType":"MAIN","treeCount":22,"designedVolume":11.00}
                """;
        rest.postForEntity(url("/api/applications"), json(req1), String.class);
        ResponseEntity<String> response = rest.postForEntity(url("/api/applications"),
                json(req2), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void correctionAndQuotaLedgerOverHttp() throws Exception {
        // 设计值 9.50 与复核口径（20 株 × 0.5 = 10.00）不一致：不予受理（待补正）
        var applyReq = """
                {"requestId":"web-corr-apply","operatorId":"OP-W","subplotId":"SP-001",
                 "speciesCode":"PINUS_MASSONIANA","harvestType":"MAIN","treeCount":20,"designedVolume":9.50}
                """;
        ResponseEntity<String> created = rest.postForEntity(url("/api/applications"),
                json(applyReq), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode app = body(created);
        assertThat(app.get("status").asText()).isEqualTo("CORRECTION_REQUIRED");
        assertThat(new BigDecimal(app.get("volume").asText())).isEqualByComparingTo("10.00");
        String appId = app.get("id").asText();

        // 一次补正为一致口径：受理
        var corrReq = """
                {"requestId":"web-corr-fix","treeCount":20,"designedVolume":10.00}
                """;
        ResponseEntity<String> corrected = rest.postForEntity(
                url("/api/applications/" + appId + "/correction"), json(corrReq), String.class);
        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(corrected).get("status").asText()).isEqualTo("SUBMITTED");

        // 补正受理后审核发证，台账双口径占用 20 株 / 10.00 立方米
        rest.postForEntity(url("/api/applications/" + appId + "/review"),
                json("{\"approved\":true,\"comment\":\"OK\"}"), String.class);
        var issueReq = """
                {"requestId":"web-corr-issue","applicationId":"%s"}
                """.formatted(appId);
        ResponseEntity<String> licensed = rest.postForEntity(url("/api/licenses"),
                json(issueReq), String.class);
        assertThat(licensed.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> ledger = rest.getForEntity(
                url("/api/quota/ledger?year=2026"), String.class);
        assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode tiers = body(ledger);
        JsonNode pinus = null;
        for (JsonNode tier : tiers) {
            if ("PINUS_MASSONIANA".equals(tier.get("speciesCode").asText())) {
                pinus = tier;
            }
        }
        assertThat(pinus).isNotNull();
        assertThat(pinus.get("heldTrees").asInt()).isEqualTo(20);
        assertThat(new BigDecimal(pinus.get("heldVolume").asText())).isEqualByComparingTo("10.00");
        assertThat(pinus.get("remainingTrees").asInt()).isEqualTo(230);
        // 重复查询稳定
        assertThat(rest.getForEntity(url("/api/quota/ledger?year=2026"), String.class).getBody())
                .isEqualTo(ledger.getBody());
    }

    @Test
    void invalidBeanReturns400() {        var req = """
                {"requestId":"","operatorId":"OP-W","subplotId":"SP-001",
                 "speciesCode":"PINUS_MASSONIANA","harvestType":"MAIN","treeCount":20,"designedVolume":10.00}
                """;
        ResponseEntity<String> response = rest.postForEntity(url("/api/applications"),
                json(req), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
