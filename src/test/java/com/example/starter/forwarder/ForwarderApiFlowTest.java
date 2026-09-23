package com.example.starter.forwarder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 端到端 HTTP 流程测试：离线写入 → 恢复联网补发 → ACK 确认。
 * 使用独立的命名内存 H2 库（MySQL 兼容模式），随测试上下文关闭释放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:forwarder_ctx_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "forwarder.network-online=false",
        "forwarder.topic-policies.rosout=DROP"
})
class ForwarderApiFlowTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void offlineIngestThenRecoverThenAck() {
        // 1. 离线写入：FIFO 默认策略下入队缓存
        Map<String, Object> message = Map.of(
                "deviceId", "dev-1", "topic", "scan", "deviceSeq", 1,
                "priority", 5, "dedupKey", "api-k1", "payload", "synthetic");
        ResponseEntity<Map> ingest = rest.postForEntity("/api/forwarder/messages", message, Map.class);
        assertEquals(HttpStatus.OK, ingest.getStatusCode());
        assertEquals("QUEUED", ingest.getBody().get("outcome"));

        // 相同 dedupKey 幂等
        ResponseEntity<Map> dup = rest.postForEntity("/api/forwarder/messages", message, Map.class);
        assertEquals("DUPLICATE", dup.getBody().get("outcome"));

        // 2. DROP 策略的 Topic 离线丢弃并写审计
        Map<String, Object> dropped = Map.of(
                "deviceId", "dev-1", "topic", "rosout", "deviceSeq", 2,
                "priority", 1, "dedupKey", "api-k2");
        ResponseEntity<Map> dropResp = rest.postForEntity("/api/forwarder/messages", dropped, Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, dropResp.getStatusCode());
        assertEquals("DROPPED_BY_POLICY", dropResp.getBody().get("outcome"));

        List<Map<String, Object>> audit = rest.getForObject("/api/forwarder/audit", List.class);
        assertTrue(audit.stream().anyMatch(row -> "DROPPED_BY_POLICY".equals(row.get("eventType"))));

        // 离线时不补发
        List<Map<String, Object>> pending = rest.getForObject("/api/forwarder/messages", List.class);
        assertEquals(1, pending.size());
        assertEquals("PENDING", pending.get(0).get("status"));

        // 3. 恢复联网：立即补发，进入 in-flight
        ResponseEntity<Map> net = rest.postForEntity(
                "/api/forwarder/network", Map.of("online", true), Map.class);
        assertEquals(Boolean.TRUE, net.getBody().get("online"));
        assertEquals(1, ((Number) net.getBody().get("drained")).intValue());

        // 4. ACK 确认；重复 ACK 幂等
        ResponseEntity<Map> ack = rest.postForEntity(
                "/api/forwarder/acks", Map.of("dedupKey", "api-k1"), Map.class);
        assertEquals("ACKED", ack.getBody().get("outcome"));
        ResponseEntity<Map> ackAgain = rest.postForEntity(
                "/api/forwarder/acks", Map.of("dedupKey", "api-k1"), Map.class);
        assertEquals("DUPLICATE", ackAgain.getBody().get("outcome"));

        List<Map<String, Object>> finalMessages = rest.getForObject("/api/forwarder/messages", List.class);
        assertEquals("ACKED", finalMessages.get(0).get("status"));
    }
}
