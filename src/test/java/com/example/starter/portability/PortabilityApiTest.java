package com.example.starter.portability;

import java.time.Instant;
import java.util.Map;

import com.example.starter.portability.support.AbstractPortabilityTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST 接口测试：通过真实 HTTP 入口走通申请-签码-确认-广播-查询全流程。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PortabilityApiTest extends AbstractPortabilityTest {

    private static final Instant WINDOW_TIME = Instant.parse("2026-09-25T02:30:00Z");

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("REST 主流程：申请、签码、确认、窗口内广播生效、事件按序查询")
    void fullFlowOverHttp() {
        insertProfile("13900000201", false, false, null);

        ResponseEntity<JsonNode> apply = rest.postForEntity("/api/portability/applications",
                Map.of("requestId", "http-apply-1", "msisdn", "13900000201"), JsonNode.class);
        assertThat(apply.getStatusCode()).isEqualTo(HttpStatus.OK);
        long flowId = apply.getBody().get("flowId").asLong();
        assertThat(apply.getBody().get("status").asText()).isEqualTo("APPLIED");

        ResponseEntity<JsonNode> issue = rest.postForEntity(
                "/api/portability/flows/{id}/auth-code",
                Map.of("requestId", "http-code-1"), JsonNode.class, flowId);
        assertThat(issue.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issue.getBody().get("authCode").asText()).hasSize(8);
        assertThat(issue.getBody().get("authCodeExpiresAt").asText()).startsWith("2026-09-26T10:00:00");

        ResponseEntity<JsonNode> confirm = rest.postForEntity(
                "/api/portability/flows/{id}/donor-confirm",
                Map.of("requestId", "http-confirm-1"), JsonNode.class, flowId);
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirm.getBody().get("status").asText()).isEqualTo("DONOR_CONFIRMED");

        clock.setInstant(WINDOW_TIME);
        ResponseEntity<JsonNode> broadcast = rest.postForEntity(
                "/api/portability/flows/{id}/broadcast",
                Map.of("requestId", "http-broadcast-1"), JsonNode.class, flowId);
        assertThat(broadcast.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(broadcast.getBody().get("status").asText()).isEqualTo("ACTIVATED");

        ResponseEntity<JsonNode> flow = rest.getForEntity(
                "/api/portability/flows/{id}", JsonNode.class, flowId);
        assertThat(flow.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(flow.getBody().get("status").asText()).isEqualTo("ACTIVATED");

        ResponseEntity<JsonNode> events = rest.getForEntity(
                "/api/portability/flows/{id}/events", JsonNode.class, flowId);
        assertThat(events.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(events.getBody()).hasSize(4);
        assertThat(events.getBody().get(3).get("eventType").asText()).isEqualTo("ACTIVATED");
    }

    @Test
    @DisplayName("REST 撤销：生效前撤销成功并进入终态")
    void cancelOverHttp() {
        insertProfile("13900000202", false, false, null);
        ResponseEntity<JsonNode> apply = rest.postForEntity("/api/portability/applications",
                Map.of("requestId", "http-apply-2", "msisdn", "13900000202"), JsonNode.class);
        long flowId = apply.getBody().get("flowId").asLong();

        ResponseEntity<JsonNode> cancel = rest.postForEntity(
                "/api/portability/flows/{id}/cancel",
                Map.of("requestId", "http-cancel-2"), JsonNode.class, flowId);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancel.getBody().get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("REST 参数校验：缺少 requestId 返回 400；流程不存在返回 404")
    void validationAndNotFound() {
        ResponseEntity<JsonNode> invalid = rest.postForEntity("/api/portability/applications",
                Map.of("msisdn", "13900000203"), JsonNode.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().get("code").asText()).isEqualTo("VALIDATION_FAILED");

        ResponseEntity<JsonNode> notFound = rest.exchange(
                "/api/portability/flows/{id}/auth-code", HttpMethod.POST,
                new HttpEntity<>(Map.of("requestId", "http-nf-1")), JsonNode.class, 999999L);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().get("code").asText()).isEqualTo("FLOW_NOT_FOUND");
    }
}
