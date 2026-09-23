package com.example.starter;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配器 ACK：幂等、可迟到、只确认对应序号、不改写当前状态。
 */
class AckApiTest extends AbstractApiTest {

    @Test
    void ack确认记录且重复ack幂等() throws Exception {
        publishStandardPlaylist("ch-ack");
        postJson("/api/ticks", """
                {"channelId": "ch-ack", "atMs": 100}
                """);
        clock.fixAt(7777);

        JsonNode acked = bodyOf(postJson("/api/as-run/ack", """
                {"channelId": "ch-ack", "asRunSequence": 1}
                """));
        assertTrue(acked.get("acked").asBoolean());
        assertEquals(7777, acked.get("ackedAtMs").asLong());

        // 重复 ACK：返回首次确认结果，ackedAtMs 不变
        clock.fixAt(9999);
        JsonNode again = bodyOf(postJson("/api/as-run/ack", """
                {"channelId": "ch-ack", "asRunSequence": 1}
                """));
        assertTrue(again.get("acked").asBoolean());
        assertEquals(7777, again.get("ackedAtMs").asLong());
    }

    @Test
    void 迟到ack只确认对应记录不改写当前状态() throws Exception {
        publishStandardPlaylist("ch-late");
        postJson("/api/ticks", """
                {"channelId": "ch-late", "atMs": 100}
                """);
        postJson("/api/ticks", """
                {"channelId": "ch-late", "atMs": 1200}
                """);

        // 迟到的 ACK 确认已关闭的 seq 1
        JsonNode late = bodyOf(postJson("/api/as-run/ack", """
                {"channelId": "ch-late", "asRunSequence": 1}
                """));
        assertTrue(late.get("acked").asBoolean());
        assertEquals("CLOSED", late.get("status").asText());

        // 当前在播仍是 seq 2，未被改写
        JsonNode log = bodyOf(getJson("/api/channels/ch-late/as-run"));
        assertEquals(2, log.size());
        assertEquals("OPEN", log.get(1).get("status").asText());
        assertEquals("digest-b", log.get(1).get("mediaDigest").asText());
        assertFalse(log.get(1).get("acked").asBoolean());
    }

    @Test
    void ack被取代记录可确认但不影响有效日志() throws Exception {
        publishStandardPlaylist("ch-sup");
        postJson("/api/ticks", """
                {"channelId": "ch-sup", "atMs": 100}
                """);
        postJson("/api/ticks", """
                {"channelId": "ch-sup", "atMs": 1200}
                """);
        // 插播从 500 开始，取代 seq 2（itemB）
        postJson("/api/breaks/start", """
                {"channelId": "ch-sup", "requestId": "brk-sup", "priority": 5,
                 "startMs": 500, "mediaDigest": "live-sup"}
                """);

        JsonNode acked = bodyOf(postJson("/api/as-run/ack", """
                {"channelId": "ch-sup", "asRunSequence": 2}
                """));
        assertTrue(acked.get("acked").asBoolean());
        assertEquals("SUPERSEDED", acked.get("status").asText());

        // 有效日志不含被取代记录，在播仍是插播
        JsonNode log = bodyOf(getJson("/api/channels/ch-sup/as-run"));
        assertEquals(2, log.size());
        assertEquals("BREAK", log.get(1).get("kind").asText());
        assertEquals("OPEN", log.get(1).get("status").asText());
    }

    @Test
    void ack未知序号返回404() throws Exception {
        MvcResult result = postJson("/api/as-run/ack", """
                {"channelId": "ch-none", "asRunSequence": 42}
                """);
        assertEquals(404, statusOf(result));
    }
}
