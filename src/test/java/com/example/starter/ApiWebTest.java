package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.support.TestTimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * HTTP 端到端测试：经真实 REST 入口走通拟票→审核→预发→逐项执行→遥信不符中止，
 * 以及 HTTP 层幂等重放与同键异参 409。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfig.class)
class ApiWebTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void 端到端_拟票审核预发执行归档() {
        var draftBody = Map.of(
                "requestId", "web-draft-1",
                "intervalId", "INT-1101",
                "title", "110kV 甲线由运行转冷备用",
                "createdBy", "拟票人甲",
                "steps", List.of(
                        Map.of("seq", 1, "deviceId", "BR-1101", "action", "OPEN_BREAKER",
                                "expectedState", "OFF"),
                        Map.of("seq", 2, "deviceId", "DIS-1101", "action", "OPEN_DISCONNECTOR",
                                "expectedState", "OPEN")));
        ResponseEntity<Map> draft = rest.postForEntity("/api/orders", draftBody, Map.class);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.OK);
        long orderId = ((Number) draft.getBody().get("orderId")).longValue();
        assertThat(draft.getBody().get("status")).isEqualTo("DRAFT");

        // 拟票幂等重放：同键同参返回首次结果
        ResponseEntity<Map> replay = rest.postForEntity("/api/orders", draftBody, Map.class);
        assertThat(replay.getBody().get("orderId")).isEqualTo(draft.getBody().get("orderId"));
        assertThat(count("switching_order", "1 = 1")).isEqualTo(1);

        // 同键异参：409
        var conflictBody = Map.of(
                "requestId", "web-draft-1",
                "intervalId", "INT-1101",
                "title", "另一张票",
                "createdBy", "拟票人甲",
                "steps", List.of(Map.of("seq", 1, "deviceId", "BR-1101",
                        "action", "OPEN_BREAKER", "expectedState", "OFF")));
        ResponseEntity<Map> conflict = rest.postForEntity("/api/orders", conflictBody, Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code")).isEqualTo("IDEMPOTENCY_CONFLICT");

        // 审核、预发
        assertThat(rest.postForEntity("/api/orders/" + orderId + "/review",
                Map.of("reviewedBy", "审核人乙"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/orders/" + orderId + "/issue",
                Map.of("requestId", "web-issue-1"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // 逐项执行（双人确认）
        var confirm1 = rest.postForEntity("/api/orders/" + orderId + "/steps/1/confirm",
                Map.of("requestId", "web-c1", "operator", "操作人A", "guardian", "监护人B"),
                Map.class);
        assertThat(confirm1.getStatusCode()).isEqualTo(HttpStatus.OK);
        var confirm2 = rest.postForEntity("/api/orders/" + orderId + "/steps/2/confirm",
                Map.of("requestId", "web-c2", "operator", "操作人A", "guardian", "监护人B"),
                Map.class);
        assertThat(confirm2.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 归档与重复归档
        assertThat(rest.postForEntity("/api/orders/" + orderId + "/archive", null, Map.class)
                .getBody().get("status")).isEqualTo("ARCHIVED");
        assertThat(rest.postForEntity("/api/orders/" + orderId + "/archive", null, Map.class)
                .getBody().get("status")).isEqualTo("ARCHIVED");

        // 详情与轨迹可查
        var detail = rest.getForEntity("/api/orders/" + orderId, Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        var trace = rest.getForEntity("/api/orders/" + orderId + "/trace", Map.class);
        assertThat(trace.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void 端到端_遥信不符中止操作() {
        long orderId = draftReviewIssue("web-abort", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));
        confirm(orderId, 1, "web-abort-c1", "操作人A", "监护人B");

        var telemetry = rest.postForEntity("/api/telemetry",
                Map.of("intervalId", "INT-1101", "deviceId", "BR-1101", "state", "ON",
                        "eventTime", Instant.parse("2026-09-26T01:00:00Z").toString()),
                Map.class);
        assertThat(telemetry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(telemetry.getBody().get("orderAborted")).isEqualTo(Boolean.TRUE);
        assertThat(orderStatus(orderId)).isEqualTo("ABORTED");

        // 重复遥信去重
        var dup = rest.postForEntity("/api/telemetry",
                Map.of("intervalId", "INT-1101", "deviceId", "BR-1101", "state", "ON",
                        "eventTime", Instant.parse("2026-09-26T01:00:00Z").toString()),
                Map.class);
        assertThat(dup.getBody().get("deduped")).isEqualTo(Boolean.TRUE);
    }
}
