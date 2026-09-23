package com.example.starter.audit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计平台 HTTP 入口测试：真实 Web 容器 + H2，验证状态码与响应语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskAuditControllerHttpTest {

    @TempDir
    static Path artifactDir;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:audit_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("audit.artifacts.dir", () -> artifactDir.toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM task_checkpoint");
        jdbc.update("DELETE FROM task_stage");
        jdbc.update("DELETE FROM task_run");
    }

    @Test
    void blankVersionReturns422ViaValidation() {
        ResponseEntity<String> response = postTask(Map.of(
                "requestId", "http-422",
                "codeVersion", "code-v1",
                "configVersion", "   ",
                "mapVersion", "map-v1",
                "rosbagVersion", "bag-v1",
                "seed", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("422").contains("configVersion");
    }

    @Test
    void createReturns201AndRunsAllStages() {
        ResponseEntity<JsonNode> response = postTaskJson(Map.of(
                "requestId", "http-201",
                "codeVersion", "code-v1",
                "configVersion", "config-v1",
                "mapVersion", "map-v1",
                "rosbagVersion", "bag-v1",
                "seed", 5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body.get("state").asText()).isEqualTo("SUCCESS");
        assertThat(body.get("resultHash").asText()).hasSize(64);
        assertThat(body.get("stages")).hasSize(4);
    }

    @Test
    void sameKeyDifferentParamsReturns409AndReplayReturnsOriginal() {
        postTask(Map.of(
                "requestId", "http-409",
                "codeVersion", "code-v1",
                "configVersion", "config-v1",
                "mapVersion", "map-v1",
                "rosbagVersion", "bag-v1",
                "seed", 5));

        ResponseEntity<String> conflict = postTask(Map.of(
                "requestId", "http-409",
                "codeVersion", "code-v2",
                "configVersion", "config-v1",
                "mapVersion", "map-v1",
                "rosbagVersion", "bag-v1",
                "seed", 5));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("409");

        ResponseEntity<JsonNode> replay = postTaskJson(Map.of(
                "requestId", "http-409",
                "codeVersion", "code-v1",
                "configVersion", "config-v1",
                "mapVersion", "map-v1",
                "rosbagVersion", "bag-v1",
                "seed", 5));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("state").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void recoveryAndCompareEndpointsWorkEndToEnd() throws Exception {
        ResponseEntity<JsonNode> failed = postTaskJson(Map.of(
                "requestId", "http-rec",
                "codeVersion", "code-v1",
                "configVersion", "config-v1",
                "mapVersion", "map-v1",
                "rosbagVersion", "bag-v1",
                "seed", 6,
                "faultPlan", "fail:planning"));
        long failedId = failed.getBody().get("id").asLong();

        ResponseEntity<JsonNode> recovered = restTemplate.postForEntity(
                url("/api/tasks/" + failedId + "/recover"), null, JsonNode.class);
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recovered.getBody().get("recovered").asBoolean()).isTrue();
        assertThat(recovered.getBody().get("resumedFrom").asText()).isEqualTo("localization");
        assertThat(recovered.getBody().get("task").get("state").asText()).isEqualTo("SUCCESS");

        ResponseEntity<JsonNode> other = postTaskJson(Map.of(
                "requestId", "http-cmp",
                "codeVersion", "code-v1",
                "configVersion", "config-v1",
                "mapVersion", "map-v1",
                "rosbagVersion", "bag-v1",
                "seed", 16));

        ResponseEntity<JsonNode> comparison = restTemplate.getForEntity(
                url("/api/tasks/" + failedId + "/compare/" + other.getBody().get("id").asLong()),
                JsonNode.class);
        assertThat(comparison.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(comparison.getBody().get("comparable").asBoolean()).isTrue();
        assertThat(comparison.getBody().get("bindingDiffs").get("seed").get("changed").asBoolean())
                .isTrue();
    }

    @Test
    void unknownTaskReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/tasks/999999"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> postTask(Map<String, Object> payload) {
        return restTemplate.postForEntity(url("/api/tasks"), jsonEntity(payload), String.class);
    }

    private ResponseEntity<JsonNode> postTaskJson(Map<String, Object> payload) {
        return restTemplate.postForEntity(url("/api/tasks"), jsonEntity(payload), JsonNode.class);
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(payload, headers);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
