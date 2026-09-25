package com.example.starter.cutover.web;

import static com.example.starter.cutover.CutoverFixtures.DEVICE_SW1;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 真实 HTTP 入口端到端测试：JSON 序列化、409 状态码、申请->审批->执行->回退->终态拒绝封板。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CutoverControllerIT {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 12, 1, 2, 0);

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;

    private final HttpHeaders json = new HttpHeaders();

    @BeforeEach
    void setUp() {
        json.setContentType(MediaType.APPLICATION_JSON);
        jdbc.update("DELETE FROM cutover_event");
        jdbc.update("DELETE FROM cutover_step");
        jdbc.update("DELETE FROM cutover_request");
    }

    private ResponseEntity<String> post(String path, Object body) {
        return rest.postForEntity(path, new HttpEntity<>(body, json), String.class);
    }

    private Map<String, Object> cutoverBody(String id, String priority,
                                            LocalDateTime s, LocalDateTime e) {
        return Map.of(
                "requestId", id,
                "title", "夜间割接-" + id,
                "deviceCode", DEVICE_SW1,
                "priority", priority,
                "windowStart", s.toString(),
                "windowEnd", e.toString(),
                "steps", List.of(
                        Map.of("name", "停流量", "type", "NORMAL"),
                        Map.of("name", "硬检查", "type", "HARDCHECK")));
    }

    @Test
    @DisplayName("HTTP 端到端：成功流转封板；同键异参返回 409")
    void httpEndToEndAndConflict409() throws Exception {
        LocalDateTime end = BASE.plusHours(2);
        ResponseEntity<String> created = post("/api/cutovers",
                cutoverBody("W-1", "MEDIUM", BASE, end));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode createdNode = mapper.readTree(created.getBody());
        assertThat(createdNode.path("request").path("status").asText()).isEqualTo("SUBMITTED");

        // 同键同参重放：200 + replayed=true
        ResponseEntity<String> replay = post("/api/cutovers",
                cutoverBody("W-1", "MEDIUM", BASE, end));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(replay.getBody()).path("replayed").asBoolean()).isTrue();

        // 同键异参：409 Conflict
        ResponseEntity<String> conflict = post("/api/cutovers",
                cutoverBody("W-1", "HIGH", BASE, end));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("不一致");

        // 影响分析 -> 审批 -> 执行
        ResponseEntity<String> analyzed = post("/api/cutovers/W-1/impact-analysis", null);
        assertThat(analyzed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(analyzed.getBody()).path("request").path("impactSystems"))
                .hasSize(3);

        assertThat(post("/api/cutovers/approve", Map.of(
                "requestUid", "w-ap", "requestId", "W-1", "approved", true))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/cutovers/execute", Map.of(
                "requestUid", "w-ex", "requestId", "W-1", "startTime", BASE.toString()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // 第一步成功，第二步硬性检查失败 -> ROLLED_BACK
        assertThat(post("/api/cutovers/steps/report", Map.of(
                "requestUid", "w-r1", "requestId", "W-1", "seqNo", 1,
                "success", true, "reportedAt", BASE.plusMinutes(10).toString()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> failed = post("/api/cutovers/steps/report", Map.of(
                "requestUid", "w-r2", "requestId", "W-1", "seqNo", 2,
                "success", false, "reportedAt", BASE.plusMinutes(20).toString()));
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(failed.getBody()).path("request").path("status").asText())
                .isEqualTo("ROLLED_BACK");

        // 回退后封板 -> 422，终态唯一
        ResponseEntity<String> sealDenied = post("/api/cutovers/seal",
                Map.of("requestUid", "w-seal", "requestId", "W-1"));
        assertThat(sealDenied.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 事件可复算
        ResponseEntity<String> events = rest.getForEntity(
                "/api/cutovers/W-1/events", String.class);
        assertThat(events.getBody()).contains("ROLLBACK_TRIGGER");
    }

    @Test
    @DisplayName("HTTP 校验：窗口超 4 小时返回 422")
    void httpWindowTooLong() {
        ResponseEntity<String> resp = post("/api/cutovers",
                cutoverBody("W-2", "LOW", BASE, BASE.plusMinutes(241)));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody()).contains("240");
    }
}
