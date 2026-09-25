package com.example.starter.food;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.StarterApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.jdbc.Sql;

/**
 * 真实 HTTP 入口端到端测试（随机端口 + H2）：验证状态码、JSON 结构与幂等 409 语义。
 */
@SpringBootTest(classes = StarterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig.class)
@Sql(scripts = "/reset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class HttpApiTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper mapper;

    private String url(String path) {
        return "http://localhost:" + port + "/api/food" + path;
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recipeBody(String requestId, String product, String version,
                                           String amount, String category) {
        return Map.of(
                "requestId", requestId,
                "productCode", product,
                "recipeVersion", version,
                "categoryCode", category,
                "additives", amount == null ? List.of()
                        : List.of(Map.of("additiveCode", "P001", "amount", new BigDecimal(amount))),
                "ingredients", List.of());
    }

    /** 提交 -> 审查 -> 批准 -> 备案 -> 备案查询 全链路 200，字段语义正确。 */
    @Test
    void fullWorkflowOverHttp() throws Exception {
        ResponseEntity<String> submit = rest.postForEntity(url("/recipes"),
                json(recipeBody("http-1", "PRD-HTTP", "v1", "0.500000", "BEV-01")), String.class);
        assertEquals(HttpStatus.OK, submit.getStatusCode());
        JsonNode recipe = mapper.readTree(submit.getBody());
        long recipeId = recipe.get("id").asLong();
        assertEquals("SUBMITTED", recipe.get("status").asText());

        ResponseEntity<String> review = rest.postForEntity(url("/recipes/" + recipeId + "/review"),
                json(Map.of("requestId", "http-rev-1", "standardVersion", "V1")), String.class);
        assertEquals(HttpStatus.OK, review.getStatusCode());
        JsonNode reviewed = mapper.readTree(review.getBody());
        assertEquals("COMPLIANT", reviewed.get("status").asText());
        assertTrue(reviewed.get("compliant").asBoolean());
        JsonNode item = reviewed.get("reviewItems").get(0);
        assertTrue(item.get("pass").asBoolean());
        assertEquals(0, item.get("ratio").decimalValue().compareTo(BigDecimal.ONE));

        ResponseEntity<String> approve = rest.postForEntity(url("/recipes/" + recipeId + "/approve"),
                json(Map.of("requestId", "http-app-1")), String.class);
        assertEquals(HttpStatus.OK, approve.getStatusCode());
        assertEquals("APPROVED", mapper.readTree(approve.getBody()).get("status").asText());

        ResponseEntity<String> filing = rest.postForEntity(url("/filings"),
                json(Map.of("requestId", "http-file-1", "recipeId", recipeId)), String.class);
        assertEquals(HttpStatus.OK, filing.getStatusCode());
        JsonNode f = mapper.readTree(filing.getBody());
        assertEquals("ACTIVE", f.get("status").asText());
        long filingId = f.get("id").asLong();

        ResponseEntity<String> got = rest.getForEntity(url("/filings/" + filingId), String.class);
        assertEquals(HttpStatus.OK, got.getStatusCode());
        assertEquals("v1", mapper.readTree(got.getBody()).get("recipeVersion").asText());
    }

    /** 超限量配方审查：审查接口仍 200 返回判定结果，但 compliant=false、状态 REJECTED。 */
    @Test
    void rejectedReviewOverHttp() throws Exception {
        ResponseEntity<String> submit = rest.postForEntity(url("/recipes"),
                json(recipeBody("http-2", "PRD-HTTP2", "v1", "9.000000", "BEV-01")), String.class);
        long recipeId = mapper.readTree(submit.getBody()).get("id").asLong();

        ResponseEntity<String> review = rest.postForEntity(url("/recipes/" + recipeId + "/review"),
                json(Map.of("requestId", "http-rev-2", "standardVersion", "V1")), String.class);
        assertEquals(HttpStatus.OK, review.getStatusCode());
        JsonNode body = mapper.readTree(review.getBody());
        assertEquals("REJECTED", body.get("status").asText());
        assertFalse(body.get("compliant").asBoolean());
        assertFalse(body.get("reviewItems").get(0).get("pass").asBoolean());
    }

    /** 同键异参 HTTP 409。 */
    @Test
    void sameKeyDifferentParamsReturns409() {
        rest.postForEntity(url("/recipes"),
                json(recipeBody("http-3", "PRD-HTTP3", "v1", "0.100000", "BEV-01")), String.class);
        ResponseEntity<String> conflict = rest.postForEntity(url("/recipes"),
                json(recipeBody("http-3", "PRD-HTTP3", "v1", "0.200000", "BEV-01")), String.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
    }

    /** 注销终态：注销后量产上报 HTTP 409；重复注销 200。 */
    @Test
    void cancelThenProductionReturns409() {
        ResponseEntity<String> submit = rest.postForEntity(url("/recipes"),
                json(recipeBody("http-4", "PRD-HTTP4", "v1", "0.100000", "BEV-01")), String.class);
        long recipeId = parseId(submit);
        rest.postForEntity(url("/recipes/" + recipeId + "/review"),
                json(Map.of("requestId", "http-rev-4", "standardVersion", "V1")), String.class);
        rest.postForEntity(url("/recipes/" + recipeId + "/approve"),
                json(Map.of("requestId", "http-app-4")), String.class);
        ResponseEntity<String> filing = rest.postForEntity(url("/filings"),
                json(Map.of("requestId", "http-file-4", "recipeId", recipeId)), String.class);
        long filingId = parseId(filing);

        ResponseEntity<String> cancel = rest.postForEntity(url("/filings/" + filingId + "/cancel"),
                json(Map.of("requestId", "http-cancel-4")), String.class);
        assertEquals(HttpStatus.OK, cancel.getStatusCode());
        ResponseEntity<String> cancelAgain = rest.postForEntity(url("/filings/" + filingId + "/cancel"),
                json(Map.of("requestId", "http-cancel-4")), String.class);
        assertEquals(HttpStatus.OK, cancelAgain.getStatusCode());

        ResponseEntity<String> report = rest.postForEntity(
                url("/filings/" + filingId + "/production-reports"),
                json(Map.of("requestId", "http-rep-4", "batchNo", "B1",
                        "quantityKg", new BigDecimal("3"))), String.class);
        assertEquals(HttpStatus.CONFLICT, report.getStatusCode());
    }

    private long parseId(ResponseEntity<String> response) {
        try {
            return mapper.readTree(response.getBody()).get("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
