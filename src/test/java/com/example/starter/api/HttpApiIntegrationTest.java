package com.example.starter.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 端到端 HTTP（真实 Web 容器 + H2）测试：状态码、同键同参/异参幂等、422 规则失败与复算详情。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:diving_http_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class HttpApiIntegrationTest {

    private static final MutableClock CLOCK = new MutableClock();

    @TestConfiguration
    static class ClockCfg {
        @Bean
        @Primary
        Clock clock() {
            return CLOCK;
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private String base;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM diver_time_ledger");
        jdbc.update("DELETE FROM segment_pair");
        jdbc.update("DELETE FROM dive_segment");
        jdbc.update("DELETE FROM pair_lock");
        jdbc.update("DELETE FROM vitals_record");
        jdbc.update("DELETE FROM dive_event");
        jdbc.update("DELETE FROM dive_plan");
        jdbc.update("DELETE FROM active_dive_group");
        jdbc.update("DELETE FROM dive_operation");
        CLOCK.setEpochSeconds(1_800_000_000L);
        base = "http://localhost:" + port + "/api/diving";
    }

    @Test
    void endToEndOverHttpWithStatusCodesAndReconciliation() {
        // 申请：18m/56 恰 NDL → 201。
        ResponseEntity<JsonNode> created = rest.postForEntity(base + "/applications",
                Map.of("requestId", "h-app-1", "diverA", "da", "diverB", "db",
                        "depthM", 18, "bottomMinutes", 56), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode plan = created.getBody();
        assertThat(plan.path("atLimit").asBoolean()).isTrue();
        String opNo = plan.path("operationNo").asText();

        // 同键同参重放 → 201 + 同一潜次。
        ResponseEntity<JsonNode> replay = rest.postForEntity(base + "/applications",
                Map.of("requestId", "h-app-1", "diverA", "da", "diverB", "db",
                        "depthM", 18, "bottomMinutes", 56), JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().path("operationNo").asText()).isEqualTo(opNo);

        // 同键异参 → 409。
        ResponseEntity<JsonNode> conflict = rest.postForEntity(base + "/applications",
                Map.of("requestId", "h-app-1", "diverA", "da", "diverB", "db",
                        "depthM", 20, "bottomMinutes", 40), JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().path("code").asText())
                .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH");

        // 超过 NDL → 422。
        ResponseEntity<JsonNode> overNdl = rest.postForEntity(base + "/applications",
                Map.of("requestId", "h-app-over", "diverA", "da", "diverB", "db",
                        "depthM", 18, "bottomMinutes", 57), JsonNode.class);
        assertThat(overNdl.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(overNdl.getBody().path("code").asText()).isEqualTo("NDL_EXCEEDED");

        // 校验失败 → 400。
        ResponseEntity<JsonNode> invalid = rest.postForEntity(base + "/applications",
                Map.of("requestId", "h-app-bad", "diverA", "da", "diverB", "db",
                        "depthM", 18), JsonNode.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 成对下潜（恰 18m/min）。
        postSegments("h-sd-1", "h-sd-2", opNo, "DESCEND", 0, 18, 0L, 60L);
        // 成对水底 120 秒。
        postSegments("h-sb-1", "h-sb-2", opNo, "BOTTOM", 18, 18, 60L, 180L);

        // 出水 + 恰满 2 小时结案。
        ResponseEntity<JsonNode> exited = rest.postForEntity(base + "/exits",
                Map.of("requestId", "h-exit", "operationNo", opNo, "exitAt", 180L),
                JsonNode.class);
        assertThat(exited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exited.getBody().path("observationDueAt").asLong()).isEqualTo(180L + 7200L);

        ResponseEntity<JsonNode> tooEarly = rest.postForEntity(base + "/closures",
                Map.of("requestId", "h-close-early", "operationNo", opNo,
                        "closedAt", 180L + 7199L), JsonNode.class);
        assertThat(tooEarly.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooEarly.getBody().path("code").asText()).isEqualTo("OBSERVATION_NOT_COMPLETE");

        ResponseEntity<JsonNode> closed = rest.postForEntity(base + "/closures",
                Map.of("requestId", "h-close", "operationNo", opNo, "closedAt", 180L + 7200L),
                JsonNode.class);
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody().path("status").asText()).isEqualTo("CLOSED");

        // 复算详情：剖面 5 步中含 BOTTOM；4 条生效记录、4 行台账、含 CLOSED 事件。
        ResponseEntity<JsonNode> detail = rest.getForEntity(
                base + "/operations/" + opNo, JsonNode.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode d = detail.getBody();
        assertThat(d.path("status").asText()).isEqualTo("CLOSED");
        assertThat(d.path("profile").size()).isGreaterThanOrEqualTo(3);
        assertThat(d.path("segments").size()).isEqualTo(4);
        for (JsonNode seg : d.path("segments")) {
            assertThat(seg.path("pairState").asText()).isEqualTo("EFFECTIVE");
            assertThat(seg.path("rateAlarm").asBoolean()).isFalse();
        }
        assertThat(d.path("ledger").size()).isEqualTo(4);
        assertThat(d.path("events").toString()).contains("CLOSED");
    }

    private void postSegments(String reqA, String reqB, String opNo, String phase,
                              int startDepth, int endDepth, long start, long end) {
        Map<String, Object> bodyA = Map.of("requestId", reqA, "operationNo", opNo, "diverId", "da",
                "phase", phase, "startDepthM", startDepth, "endDepthM", endDepth,
                "startedAt", start, "endedAt", end);
        Map<String, Object> bodyB = Map.of("requestId", reqB, "operationNo", opNo, "diverId", "db",
                "phase", phase, "startDepthM", startDepth, "endDepthM", endDepth,
                "startedAt", start, "endedAt", end);
        ResponseEntity<JsonNode> a = rest.postForEntity(base + "/segments", bodyA, JsonNode.class);
        ResponseEntity<JsonNode> b = rest.postForEntity(base + "/segments", bodyB, JsonNode.class);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getBody().path("effective").asBoolean()).isTrue();
    }
}
