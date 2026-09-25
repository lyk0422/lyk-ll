package com.example.starter.web;

import com.example.starter.StarterApplication;
import com.example.starter.support.AbstractIntegrationTest;
import com.example.starter.support.TestClockConfig;
import org.junit.jupiter.api.DisplayName;
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

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端 HTTP 测试：真实 Web 容器 + H2，验证申报→双检→分级发证→注销全链路与状态码语义。
 */
@SpringBootTest(classes = {StarterApplication.class, TestClockConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CertificationApiTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Map<String, Object> declareBody(String requestId, String lotNo) {
        return Map.of(
                "requestId", requestId,
                "lotNo", lotNo,
                "applicant", "绿农种业有限公司",
                "varietyName", "郑单 958（合成）",
                "fieldCode", "T-0001（合成地块）",
                "areaMu", new BigDecimal("120.50"),
                "availableLabels", 6000);
    }

    @Test
    @DisplayName("完整链路：申报 201 → 双检 200 分级原种 → 发证 200 核减标签 → 注销 200 证书作废")
    @SuppressWarnings("unchecked")
    void fullLifecycleThroughHttp() {
        // 1) 申报
        ResponseEntity<Map> declared = restTemplate.postForEntity(url("/api/lots/declare"),
                json(declareBody("API-D-1", "LOT-5001")), Map.class);
        assertEquals(HttpStatus.CREATED, declared.getStatusCode());
        assertEquals("DECLARED", declared.getBody().get("status"));

        // 2) 田间 + 室内，恰好原种门槛
        ResponseEntity<Map> field = restTemplate.postForEntity(url("/api/lots/field-inspection"),
                json(Map.of("requestId", "API-F-1", "lotNo", "LOT-5001",
                        "purity", "99.90", "inspector", "检验员甲")), Map.class);
        assertEquals(HttpStatus.OK, field.getStatusCode());

        ResponseEntity<Map> lab = restTemplate.postForEntity(url("/api/lots/lab-inspection"),
                json(Map.of("requestId", "API-L-1", "lotNo", "LOT-5001",
                        "germinationRate", "90.00", "inspector", "检测员乙")), Map.class);
        assertEquals(HttpStatus.OK, lab.getStatusCode());
        assertEquals("QUALIFIED", lab.getBody().get("status"));
        assertEquals("ORIGINAL", lab.getBody().get("determinedGrade"));

        // 3) 发证：唯一证书号 + 标签核减到 0
        ResponseEntity<Map> certified = restTemplate.postForEntity(
                url("/api/lots/LOT-5001/certificate"), null, Map.class);
        assertEquals(HttpStatus.OK, certified.getStatusCode());
        assertEquals("CERTIFIED", certified.getBody().get("status"));
        assertEquals(0, certified.getBody().get("availableLabels"));
        Map<String, Object> cert = (Map<String, Object>) certified.getBody().get("certificate");
        assertEquals("VALID", cert.get("status"));
        assertEquals("ORIGINAL", cert.get("grade"));
        assertEquals(6000, ((Number) cert.get("labelCount")).intValue());
        assertTrue(((String) cert.get("certNo")).startsWith("CERT-"));

        // 4) 注销：终态、证书同步作废
        ResponseEntity<Map> cancelled = restTemplate.postForEntity(
                url("/api/lots/LOT-5001/cancel"), null, Map.class);
        assertEquals(HttpStatus.OK, cancelled.getStatusCode());
        assertEquals("CANCELLED", cancelled.getBody().get("status"));
        Map<String, Object> voidCert = (Map<String, Object>) cancelled.getBody().get("certificate");
        assertEquals("VOID", voidCert.get("status"));
        assertEquals("批次注销，证书同步作废", voidCert.get("voidReason"));

        // 重复注销幂等成功
        ResponseEntity<Map> cancelAgain = restTemplate.postForEntity(
                url("/api/lots/LOT-5001/cancel"), null, Map.class);
        assertEquals(HttpStatus.OK, cancelAgain.getStatusCode());
        assertEquals("CANCELLED", cancelAgain.getBody().get("status"));

        // 注销后检验拒绝 422
        ResponseEntity<Map> fieldAfterCancel = restTemplate.postForEntity(
                url("/api/lots/field-inspection"),
                json(Map.of("requestId", "API-F-X", "lotNo", "LOT-5001",
                        "purity", "99.99", "inspector", "检验员甲")), Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, fieldAfterCancel.getStatusCode());
    }

    @Test
    @DisplayName("HTTP 语义：同键异参 409 且无新批次；不存在 404；待定批次越级发证 422")
    void httpErrorSemantics() {
        restTemplate.postForEntity(url("/api/lots/declare"),
                json(declareBody("API-D-2", "LOT-5002")), Map.class);

        Map<String, Object> changed = declareBody("API-D-2", "LOT-5002");
        Map<String, Object> differentParams = new java.util.HashMap<>(changed);
        differentParams.put("applicant", "另一家申报主体");
        ResponseEntity<Map> conflict = restTemplate.postForEntity(url("/api/lots/declare"),
                json(differentParams), Map.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals(1, count("seed_lot", "lot_no = ?", "LOT-5002"));

        ResponseEntity<Map> notFound = restTemplate.getForEntity(url("/api/lots/LOT-MISSING"), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        // 一项不合格一项未回 → 待定 → 越级发证 422
        restTemplate.postForEntity(url("/api/lots/declare"),
                json(declareBody("API-D-3", "LOT-5003")), Map.class);
        restTemplate.postForEntity(url("/api/lots/field-inspection"),
                json(Map.of("requestId", "API-F-3", "lotNo", "LOT-5003",
                        "purity", "98.00", "inspector", "检验员甲")), Map.class);
        ResponseEntity<Map> prematureIssue = restTemplate.postForEntity(
                url("/api/lots/LOT-5003/certificate"), null, Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, prematureIssue.getStatusCode());
    }

    @Test
    @DisplayName("HTTP 语义：参数校验失败 400")
    void validationFailureReturns400() {
        ResponseEntity<Map> badRequest = restTemplate.postForEntity(url("/api/lots/declare"),
                json(Map.of("requestId", "", "lotNo", "LOT-BAD")), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, badRequest.getStatusCode());
    }
}
