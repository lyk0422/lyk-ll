package com.example.starter.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.testsupport.TestClockConfig;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 刷新令牌 API 的端到端 HTTP 契约测试（真实 H2 + 随机端口）。
 */
@SpringBootTest(
        classes = {com.example.starter.StarterApplication.class, TestClockConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefreshTokenControllerIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
        jdbcTemplate.update("DELETE FROM security_event");
        jdbcTemplate.update("DELETE FROM rotation_request");
        jdbcTemplate.update("DELETE FROM refresh_token");
        jdbcTemplate.update("DELETE FROM token_family");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<String> response) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(response.getBody(), Map.class);
    }

    @Test
    void fullLifecycleRotateReplayAndQueryEventsOverHttp() throws Exception {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);

        // 1. 创建会话 → 201
        ResponseEntity<String> created = restTemplate.postForEntity(
                base + "/api/sessions",
                new HttpEntity<>(Map.of("userId", "user-1", "clientId", "client-1"), json),
                String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> session = body(created);
        String familyId = (String) session.get("familyId");
        String rootToken = (String) session.get("refreshToken");
        assertThat(familyId).isNotBlank();
        assertThat(rootToken).isNotBlank();

        // 2. 轮换 → 200，得到子令牌
        ResponseEntity<String> rotated = restTemplate.postForEntity(
                base + "/api/tokens/rotate",
                new HttpEntity<>(Map.of(
                        "requestId", "req-1",
                        "refreshToken", rootToken,
                        "clientId", "client-1"), json),
                String.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> rotation = body(rotated);
        String childToken = (String) rotation.get("refreshToken");
        assertThat(rotation.get("replayed")).isEqualTo(false);

        // 3. 安全重试 → 200，replayed=true，同一子令牌
        ResponseEntity<String> retry = restTemplate.postForEntity(
                base + "/api/tokens/rotate",
                new HttpEntity<>(Map.of(
                        "requestId", "req-1",
                        "refreshToken", rootToken,
                        "clientId", "client-1"), json),
                String.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(retry).get("refreshToken")).isEqualTo(childToken);
        assertThat(body(retry).get("replayed")).isEqualTo(true);

        // 4. 同键改客户端 → 409
        ResponseEntity<String> conflict = restTemplate.postForEntity(
                base + "/api/tokens/rotate",
                new HttpEntity<>(Map.of(
                        "requestId", "req-1",
                        "refreshToken", rootToken,
                        "clientId", "other-client"), json),
                String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(conflict).get("error")).isEqualTo("REQUEST_CONFLICT");

        // 5. 异键重放已消费令牌 → 401
        ResponseEntity<String> replay = restTemplate.postForEntity(
                base + "/api/tokens/rotate",
                new HttpEntity<>(Map.of(
                        "requestId", "req-attacker",
                        "refreshToken", rootToken,
                        "clientId", "client-1"), json),
                String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 6. 族状态 → 已撤销
        ResponseEntity<String> status = restTemplate.getForEntity(
                base + "/api/families/" + familyId, String.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> statusBody = body(status);
        assertThat(statusBody.get("status")).isEqualTo("REVOKED");
        assertThat(statusBody.get("revokeReason")).isEqualTo("REPLAY_DETECTED");
        assertThat(((Number) statusBody.get("activeTokenCount")).intValue()).isZero();

        // 7. 安全事件查询 → 恰好一条 REPLAY_DETECTED
        ResponseEntity<String> events = restTemplate.getForEntity(
                base + "/api/families/" + familyId + "/events", String.class);
        assertThat(events.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> eventList = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(events.getBody(), List.class);
        assertThat(eventList).hasSize(1);
        Map<?, ?> event = (Map<?, ?>) eventList.get(0);
        assertThat(event.get("eventType")).isEqualTo("REPLAY_DETECTED");
        assertThat(event.get("requestId")).isEqualTo("req-attacker");

        // 8. 登出已撤销族 → 204，无副作用
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> logout = restTemplate.exchange(
                base + "/api/logout",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("familyId", familyId, "clientId", "client-1"), headers),
                String.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void logoutOnActiveFamilyReturns204() throws Exception {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> created = restTemplate.postForEntity(
                base + "/api/sessions",
                new HttpEntity<>(Map.of("userId", "user-2", "clientId", "client-2"), json),
                String.class);
        String familyId = (String) body(created).get("familyId");

        ResponseEntity<String> logout = restTemplate.exchange(
                base + "/api/logout",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("familyId", familyId, "clientId", "client-2"), json),
                String.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(body(
                restTemplate.getForEntity(base + "/api/families/" + familyId, String.class))
                .get("status")).isEqualTo("REVOKED");
    }

    @Test
    void invalidRequestBodyReturns400() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                base + "/api/sessions",
                new HttpEntity<>(Map.of("userId", "", "clientId", "client-x"), json),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
