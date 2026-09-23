package com.example.starter.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.adapter.SyntheticUpstreamAdapter;
import com.example.starter.config.ForwarderProperties;
import com.example.starter.testsupport.TestClockConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * REST 端到端测试：覆盖断网缓存、恢复补发、ACK 幂等与审计查询的真实 HTTP 主流程。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfiguration.class)
class ForwarderControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SyntheticUpstreamAdapter upstreamAdapter;
    @Autowired
    private ForwarderProperties properties;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM audit_record");
        jdbcTemplate.execute("DELETE FROM device_message");
        jdbcTemplate.execute("DELETE FROM topic_policy");
        upstreamAdapter.setOnline(true);
        upstreamAdapter.setAutoAck(false);
        upstreamAdapter.clearDeliveryLog();
        properties.setQueueCapacity(100);
    }

    @Test
    @SuppressWarnings("unchecked")
    void offlineIngestRecoveryDispatchAckAndAuditEndToEnd() {
        // 1. 断网。
        ResponseEntity<Map> offline = restTemplate.postForEntity(
                "/api/network?online=false", null, Map.class);
        assertThat(offline.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(offline.getBody()).containsEntry("online", false);

        // 2. 配置 DROP 主题并验证丢弃不入队、有审计。
        restTemplate.postForEntity("/api/topics/cmd-drop/policy",
                Map.of("strategy", "DROP"), Map.class);
        ResponseEntity<Map> dropped = restTemplate.postForEntity("/api/messages",
                messageBody("dev1", "cmd-drop", 1, 5, "drop-1"), Map.class);
        assertThat(dropped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dropped.getBody()).containsEntry("outcome", "DROPPED");

        // 3. FIFO 主题（默认）离线写入两条不同优先级消息。
        restTemplate.postForEntity("/api/messages",
                messageBody("dev1", "telemetry", 1, 1, "tel-low"), Map.class);
        restTemplate.postForEntity("/api/messages",
                messageBody("dev1", "telemetry", 2, 9, "tel-high"), Map.class);

        // 4. 重复 dedupKey 幂等。
        ResponseEntity<Map> duplicate = restTemplate.postForEntity("/api/messages",
                messageBody("dev1", "telemetry", 9, 9, "tel-low"), Map.class);
        assertThat(duplicate.getBody()).containsEntry("outcome", "DUPLICATE");

        // 5. 待发队列有两条，顺序按补发排序（高优先级在前）。
        ResponseEntity<List> pendingBefore = restTemplate.getForEntity(
                "/api/messages/pending", List.class);
        assertThat(pendingBefore.getBody()).hasSize(2);
        assertThat(((Map<?, ?>) pendingBefore.getBody().get(0)).get("messageId"))
                .isEqualTo("tel-high");

        // 6. 恢复联网并手动补发。
        restTemplate.postForEntity("/api/network?online=true", null, Map.class);
        ResponseEntity<Map> dispatch = restTemplate.postForEntity(
                "/api/dispatch", null, Map.class);
        assertThat(dispatch.getBody()).containsEntry("delivered", 2);

        // 7. ACK 乱序：先确认低优先级，再重复确认，验证幂等返回。
        ResponseEntity<Map> ackLow = restTemplate.postForEntity("/api/acks",
                Map.of("dedupKey", "tel-low"), Map.class);
        assertThat(ackLow.getBody()).containsEntry("confirmed", true);
        ResponseEntity<Map> ackLowAgain = restTemplate.postForEntity("/api/acks",
                Map.of("dedupKey", "tel-low"), Map.class);
        assertThat(ackLowAgain.getBody()).containsEntry("confirmed", false);
        ResponseEntity<Map> ackHigh = restTemplate.postForEntity("/api/acks",
                Map.of("dedupKey", "tel-high"), Map.class);
        assertThat(ackHigh.getBody()).containsEntry("confirmed", true);

        // 8. 上游投递顺序为高优先级先于低优先级。
        assertThat(upstreamAdapter.getDeliveredMessageIds())
                .containsExactly("tel-high", "tel-low");

        // 9. 审计记录包含 DROP 事件，且不含任何“伪装转发”。
        ResponseEntity<List> audits = restTemplate.getForEntity("/api/audits", List.class);
        List<?> auditList = audits.getBody();
        assertThat(auditList).hasSize(1);
        assertThat(((Map<?, ?>) auditList.get(0)).get("eventType")).isEqualTo("DROP_POLICY");
        assertThat(((Map<?, ?>) auditList.get(0)).get("messageId")).isEqualTo("drop-1");
    }

    @Test
    void capacityExhaustionReturns503AndPersistsAudit() {
        properties.setQueueCapacity(1);
        restTemplate.postForEntity("/api/messages",
                messageBody("dev1", "t", 1, 0, "only-one"), Map.class);
        // 首条消息进入 in-flight，占满容量；in-flight 不可被驱逐。
        restTemplate.postForEntity("/api/dispatch", null, Map.class);
        ResponseEntity<Map> rejected = restTemplate.postForEntity("/api/messages",
                messageBody("dev1", "t", 2, 0, "second"), Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat((String) rejected.getBody().get("message")).contains("second");

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE event_type = 'REJECT_CAPACITY'",
                Integer.class);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void invalidMessageRequestReturns400() {
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/messages",
                Map.of("deviceId", "dev1"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private Map<String, Object> messageBody(String deviceId, String topic, long seq,
                                            int priority, String key) {
        return Map.of(
                "dedupKey", key,
                "deviceId", deviceId,
                "topic", topic,
                "seq", seq,
                "priority", priority,
                "payload", "synthetic-" + key);
    }
}
