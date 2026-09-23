package com.example.starter;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调度 tick：单调序号、重复 tick 幂等、乱序与水位线拒绝、任一时刻最多一个在播素材。
 */
class TickApiTest extends AbstractApiTest {

    @Test
    void tick跨越节目边界生成单调序号() throws Exception {
        publishStandardPlaylist("ch-tick");

        JsonNode t1 = bodyOf(postJson("/api/ticks", """
                {"channelId": "ch-tick", "atMs": 100}
                """));
        assertEquals(1, t1.get("generatedSeq").asLong());
        assertEquals("digest-a", t1.get("onAir").get("mediaDigest").asText());
        assertEquals("OPEN", t1.get("onAir").get("status").asText());

        JsonNode t2 = bodyOf(postJson("/api/ticks", """
                {"channelId": "ch-tick", "atMs": 1500}
                """));
        assertEquals(2, t2.get("generatedSeq").asLong());
        assertEquals("digest-b", t2.get("onAir").get("mediaDigest").asText());

        // 第一条记录已在 1500 关闭
        JsonNode log = bodyOf(getJson("/api/channels/ch-tick/as-run"));
        assertEquals(2, log.size());
        assertEquals(1500, log.get(0).get("endMs").asLong());
        assertEquals("CLOSED", log.get(0).get("status").asText());
        assertTrue(log.get(1).get("endMs").isNull());

        // 序号单调递增
        assertTrue(log.get(1).get("seq").asLong() > log.get(0).get("seq").asLong());
    }

    @Test
    void 重复tick不重复生成记录() throws Exception {
        publishStandardPlaylist("ch-dup-tick");
        postJson("/api/ticks", """
                {"channelId": "ch-dup-tick", "atMs": 100}
                """);
        MvcResult again = postJson("/api/ticks", """
                {"channelId": "ch-dup-tick", "atMs": 100}
                """);
        assertEquals(200, statusOf(again));
        JsonNode body = bodyOf(again);
        assertTrue(body.get("duplicate").asBoolean());
        assertEquals("digest-a", body.get("onAir").get("mediaDigest").asText());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM as_run WHERE channel_id = 'ch-dup-tick'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void 同一时刻素材不变时不生成新记录() throws Exception {
        publishStandardPlaylist("ch-same");
        postJson("/api/ticks", """
                {"channelId": "ch-same", "atMs": 100}
                """);
        JsonNode t2 = bodyOf(postJson("/api/ticks", """
                {"channelId": "ch-same", "atMs": 200}
                """));
        assertFalse(t2.get("duplicate").asBoolean());
        assertTrue(t2.get("generatedSeq").isNull());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM as_run WHERE channel_id = 'ch-same'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void 空档时刻关闭在播且不生成记录() throws Exception {
        publishStandardPlaylist("ch-gap");
        postJson("/api/ticks", """
                {"channelId": "ch-gap", "atMs": 100}
                """);
        JsonNode t2 = bodyOf(postJson("/api/ticks", """
                {"channelId": "ch-gap", "atMs": 5000}
                """));
        assertTrue(t2.get("onAir").isNull());
        Integer openCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM as_run WHERE channel_id = 'ch-gap' AND status = 'OPEN'", Integer.class);
        assertEquals(0, openCount);
    }

    @Test
    void 乱序tick返回409() throws Exception {
        publishStandardPlaylist("ch-ooo");
        postJson("/api/ticks", """
                {"channelId": "ch-ooo", "atMs": 500}
                """);
        MvcResult result = postJson("/api/ticks", """
                {"channelId": "ch-ooo", "atMs": 400}
                """);
        assertEquals(409, statusOf(result));
    }

    @Test
    void tick早于水位线返回409() throws Exception {
        publishStandardPlaylist("ch-wm-tick");
        postJson("/api/ticks", """
                {"channelId": "ch-wm-tick", "atMs": 100}
                """);
        MvcResult wm = postJson("/api/channels/ch-wm-tick/watermark", """
                {"watermarkMs": 300}
                """);
        assertEquals(200, statusOf(wm));

        MvcResult result = postJson("/api/ticks", """
                {"channelId": "ch-wm-tick", "atMs": 250}
                """);
        assertEquals(409, statusOf(result));
    }

    @Test
    void 任一时刻最多一个在播素材() throws Exception {
        publishStandardPlaylist("ch-single");
        postJson("/api/ticks", """
                {"channelId": "ch-single", "atMs": 100}
                """);
        postJson("/api/ticks", """
                {"channelId": "ch-single", "atMs": 1200}
                """);
        postJson("/api/ticks", """
                {"channelId": "ch-single", "atMs": 1600}
                """);
        Integer openCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM as_run WHERE channel_id = 'ch-single' AND status = 'OPEN'", Integer.class);
        assertEquals(1, openCount);
    }
}
