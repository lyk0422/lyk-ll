package com.example.starter;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结算水位线：单调推进、越过水位线的记录不可修改。
 */
class WatermarkApiTest extends AbstractApiTest {

    @Test
    void 水位线只能单调前进() throws Exception {
        publishStandardPlaylist("ch-wm");
        MvcResult first = postJson("/api/channels/ch-wm/watermark", """
                {"watermarkMs": 500}
                """);
        assertEquals(200, statusOf(first));

        MvcResult regress = postJson("/api/channels/ch-wm/watermark", """
                {"watermarkMs": 400}
                """);
        assertEquals(409, statusOf(regress));

        Integer watermark = jdbcTemplate.queryForObject(
                "SELECT watermark_ms FROM channel_state WHERE channel_id = 'ch-wm'", Integer.class);
        assertEquals(500, watermark);
    }

    @Test
    void 插播结束早于水位线返回409() throws Exception {
        publishStandardPlaylist("ch-wm-end");
        postJson("/api/ticks", """
                {"channelId": "ch-wm-end", "atMs": 100}
                """);
        postJson("/api/breaks/start", """
                {"channelId": "ch-wm-end", "requestId": "brk-1", "priority": 5,
                 "startMs": 200, "mediaDigest": "live-1"}
                """);
        postJson("/api/channels/ch-wm-end/watermark", """
                {"watermarkMs": 500}
                """);

        MvcResult rejected = postJson("/api/breaks/end", """
                {"channelId": "ch-wm-end", "requestId": "brk-1", "endMs": 400}
                """);
        assertEquals(409, statusOf(rejected));

        // 水位线之后的结束合法
        MvcResult accepted = postJson("/api/breaks/end", """
                {"channelId": "ch-wm-end", "requestId": "brk-1", "endMs": 600}
                """);
        assertEquals(200, statusOf(accepted));
    }

    @Test
    void 越过水位线后重复tick仍幂等但不产生修改() throws Exception {
        publishStandardPlaylist("ch-wm-dup");
        postJson("/api/ticks", """
                {"channelId": "ch-wm-dup", "atMs": 100}
                """);
        postJson("/api/channels/ch-wm-dup/watermark", """
                {"watermarkMs": 500}
                """);
        // 同一时刻重复 tick：幂等重放，不触发水位线拒绝
        JsonNode dup = bodyOf(postJson("/api/ticks", """
                {"channelId": "ch-wm-dup", "atMs": 100}
                """));
        assertTrue(dup.get("duplicate").asBoolean());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM as_run WHERE channel_id = 'ch-wm-dup'", Integer.class);
        assertEquals(1, count);
    }
}
