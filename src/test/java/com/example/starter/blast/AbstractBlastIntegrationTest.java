package com.example.starter.blast;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2（MODE=MySQL）集成测试基类：每个用例前清空业务表并复位矿区冻结状态，
 * 保证用例间无顺序依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractBlastIntegrationTest {

    protected static final String MINE = "MINE-001";

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM late_reading_event");
        jdbc.update("DELETE FROM freeze_event");
        jdbc.update("DELETE FROM effect_evaluation");
        jdbc.update("DELETE FROM unfreeze_review");
        jdbc.update("DELETE FROM vibration_reading");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM blast_application");
        jdbc.update("UPDATE mine SET frozen = FALSE, frozen_at = NULL, frozen_reason = NULL, "
                + "unfrozen_at = NULL, unfreeze_reviewer = NULL, version = 0");
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected record ApplyBody(String requestId, String mineCode, String zoneCode, String applicant,
                               String plannedTime, BigDecimal chargePerDelayKg) {
    }

    protected record FilingBody(String requestId, Long applicationId, String detonateTime) {
    }

    protected record ReadingBody(Long applicationId, String zoneCode, String pointCode,
                                 String sampleTime, BigDecimal velocityMmS) {
    }

    protected record ApprovalBody(String reviewer, Boolean approved, String reason) {
    }

    protected record UnfreezeBody(String requestId, String reviewer, Boolean confirmed) {
    }

    protected record EvaluationBody(String requestId, Long applicationId, String evaluator,
                                    String conclusion) {
    }

    protected org.springframework.http.ResponseEntity<JsonNode> post(String path, Object body) {
        return http.postForEntity(url(path), body, JsonNode.class);
    }

    protected long applyOk(String requestId, String zone, Instant planned, BigDecimal charge) {
        var resp = post("/api/blast/applications",
                new ApplyBody(requestId, MINE, zone, "申请人甲", planned.toString(), charge));
        assertThat(resp.getStatusCode().value())
                .as("申请应当受理: %s", resp.getBody())
                .isEqualTo(201);
        return resp.getBody().get("id").asLong();
    }

    protected long approveOk(long applicationId) {
        var resp = post("/api/blast/applications/" + applicationId + "/approval",
                new ApprovalBody("审批人乙", true, null));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return applicationId;
    }

    protected void filingOk(String requestId, long applicationId, Instant detonate) {
        var resp = post("/api/blast/filings",
                new FilingBody(requestId, applicationId, detonate.toString()));
        assertThat(resp.getStatusCode().value())
                .as("起爆备案应成功: %s", resp.getBody())
                .isEqualTo(200);
        assertThat(resp.getBody().get("status").asText()).isEqualTo("FILED");
    }

    protected String applicationStatus(long applicationId) {
        return jdbc.queryForObject(
                "SELECT status FROM blast_application WHERE id = ?", String.class, applicationId);
    }

    protected boolean mineFrozen() {
        return jdbc.queryForObject("SELECT frozen FROM mine WHERE mine_code = ?",
                Boolean.class, MINE);
    }
}
