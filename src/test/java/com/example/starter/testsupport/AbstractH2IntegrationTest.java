package com.example.starter.survey.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * H2 集成测试基类：
 * 每个场景前按外键安全顺序清空数据，避免顺序依赖；时钟默认重置到场景起点。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockTestConfig.class)
public abstract class AbstractH2IntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected TestRestTemplate rest;
    @LocalServerPort
    protected int port;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM recognition_risk");
        jdbc.update("DELETE FROM recognition_binding");
        jdbc.update("DELETE FROM survey_item_completion");
        jdbc.update("DELETE FROM inspection_report_item");
        jdbc.update("DELETE FROM inspection_report");
        jdbc.update("DELETE FROM recognition_rule_item");
        jdbc.update("DELETE FROM recognition_rule");
        jdbc.update("DELETE FROM survey_event");
        jdbc.update("DELETE FROM defect");
        jdbc.update("DELETE FROM certificate");
        jdbc.update("DELETE FROM extension_request");
        jdbc.update("DELETE FROM survey");
        jdbc.update("DELETE FROM idempotent_record");
        jdbc.update("DELETE FROM window_ledger");
        jdbc.update("DELETE FROM ship");
        FixedClockTestConfig.CLOCK.setUtcDate(defaultClockDate());
    }

    /** 场景默认时钟日（UTC），子类可覆盖。 */
    protected LocalDate defaultClockDate() {
        return LocalDate.of(2026, 6, 15);
    }

    protected void setClockDate(LocalDate date) {
        FixedClockTestConfig.CLOCK.setUtcDate(date);
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected static java.time.Instant instantOf(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    protected JsonNode post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                url(path), new HttpEntity<>(body, headers), String.class);
        try {
            return new ObjectMapper().readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应解析失败: " + response.getBody(), e);
        }
    }

    protected JsonNode get(String path) {
        ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
        try {
            return new ObjectMapper().readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应解析失败: " + response.getBody(), e);
        }
    }

    protected ResponseEntity<String> postRaw(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(url(path), new HttpEntity<>(body, headers), String.class);
    }

    protected JsonNode postExpectStatus(String path, Object body, int expectedStatus) {
        ResponseEntity<String> response = postRaw(path, body);
        if (response.getStatusCode().value() != expectedStatus) {
            throw new AssertionError("期望 HTTP " + expectedStatus + "，实际 HTTP "
                    + response.getStatusCode().value() + "，响应: " + response.getBody());
        }
        try {
            return new ObjectMapper().readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应解析失败: " + response.getBody(), e);
        }
    }

    protected Map<String, Object> shipBody(String requestId, String imo,
                                           LocalDate anniversary, LocalDate delivery) {
        return Map.of(
                "requestId", requestId,
                "imo", imo,
                "name", "测试船-" + imo,
                "annualAnniversary", anniversary.toString(),
                "deliveryDate", delivery.toString());
    }

    protected Map<String, Object> applyBody(String requestId, String imo, String type) {
        return Map.of("requestId", requestId, "shipImo", imo, "type", type);
    }

    protected Map<String, Object> boardBody(String requestId, long surveyId,
                                            LocalDate completedDate,
                                            java.util.List<Map<String, Object>> defects) {
        return Map.of("requestId", requestId, "surveyId", surveyId,
                "completedDate", completedDate.toString(),
                "defects", defects == null ? java.util.List.of() : defects);
    }

    protected Map<String, Object> defectInput(String severity, String desc, LocalDate found) {
        return Map.of("severity", severity, "description", desc, "foundDate", found.toString());
    }

    protected Map<String, Object> certifyBody(String requestId, long surveyId) {
        return Map.of("requestId", requestId, "surveyId", surveyId);
    }

    protected Map<String, Object> rectifyBody(String requestId, long defectId,
                                              LocalDate submitDate) {
        return Map.of("requestId", requestId, "defectId", defectId,
                "submitDate", submitDate.toString());
    }

    protected Map<String, Object> extensionApplyBody(String requestId, long surveyId, int months) {
        return Map.of("requestId", requestId, "surveyId", surveyId, "months", months);
    }

    protected Map<String, Object> extensionApproveBody(String requestId, long extensionId) {
        return Map.of("requestId", requestId, "extensionId", extensionId);
    }

    // ---- 互认：规则 / 报告 / 申请 ----

    protected Map<String, Object> ruleBody(String requestId, int ruleVersion, String flagCode,
                                           int maxEvidenceDays, java.util.List<String> items) {
        return Map.of("requestId", requestId, "ruleVersion", ruleVersion,
                "flagCode", flagCode, "maxEvidenceDays", maxEvidenceDays,
                "items", items);
    }

    protected Map<String, Object> reportItem(String itemCode, String conclusion) {
        return Map.of("itemCode", itemCode, "conclusion", conclusion);
    }

    protected Map<String, Object> importReportBody(String requestId, String reportNo,
                                                   String shipImo, String flagCode,
                                                   java.time.Instant completedAt,
                                                   java.util.List<Map<String, Object>> items) {
        return Map.of("requestId", requestId, "reportNo", reportNo,
                "shipImo", shipImo, "flagCode", flagCode,
                "completedAt", completedAt.toString(),
                "attestation", "SYNTHETIC-ATTEST",
                "items", items);
    }

    protected Map<String, Object> correctReportBody(String requestId, String reportNo,
                                                    int expectedVersion,
                                                    java.time.Instant completedAt,
                                                    java.util.List<Map<String, Object>> items) {
        return Map.of("requestId", requestId, "reportNo", reportNo,
                "expectedVersion", expectedVersion,
                "completedAt", completedAt.toString(),
                "attestation", "SYNTHETIC-ATTEST",
                "items", items);
    }

    protected Map<String, Object> applyRecognitionBody(String requestId, long surveyId,
                                                       String reportNo, int ruleVersion) {
        return Map.of("requestId", requestId, "surveyId", surveyId,
                "reportNo", reportNo, "ruleVersion", ruleVersion);
    }

    protected Map<String, Object> boardBody(String requestId, long surveyId,
                                            LocalDate completedDate,
                                            java.util.List<Map<String, Object>> defects,
                                            java.util.List<String> completedItems) {
        return Map.of("requestId", requestId, "surveyId", surveyId,
                "completedDate", completedDate.toString(),
                "defects", defects == null ? java.util.List.of() : defects,
                "completedItems", completedItems == null ? java.util.List.of() : completedItems);
    }
}
