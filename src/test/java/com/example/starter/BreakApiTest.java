package com.example.starter;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直播插播：优先级压过、恢复排播不补播、水位线、幂等冻结与同键异参。
 */
class BreakApiTest extends AbstractApiTest {

    @Test
    void 插播压过排播并在结束后恢复且不补播() throws Exception {
        publishStandardPlaylist("ch-break");
        postJson("/api/ticks", """
                {"channelId": "ch-break", "atMs": 100}
                """);

        JsonNode started = bodyOf(postJson("/api/breaks/start", """
                {"channelId": "ch-break", "requestId": "brk-1", "priority": 5,
                 "startMs": 300, "mediaDigest": "live-news"}
                """));
        assertEquals("ACTIVE", started.get("status").asText());
        long breakSeq = started.get("asRunSeq").asLong();
        assertTrue(breakSeq > 0);

        JsonNode ended = bodyOf(postJson("/api/breaks/end", """
                {"channelId": "ch-break", "requestId": "brk-1", "endMs": 1200}
                """));
        assertEquals("ENDED", ended.get("status").asText());
        assertTrue(ended.get("resumedSeq").asLong() > breakSeq);

        JsonNode log = bodyOf(getJson("/api/channels/ch-break/as-run"));
        assertEquals(3, log.size());
        // 排播 itemA 在 300 被截断
        assertEquals("SCHEDULED", log.get(0).get("kind").asText());
        assertEquals(100, log.get(0).get("startMs").asLong());
        assertEquals(300, log.get(0).get("endMs").asLong());
        // 插播覆盖 [300,1200)
        assertEquals("BREAK", log.get(1).get("kind").asText());
        assertEquals(300, log.get(1).get("startMs").asLong());
        assertEquals(1200, log.get(1).get("endMs").asLong());
        // 1200 时刻恢复应播素材 itemB（不补播 itemA 被覆盖部分）
        assertEquals("SCHEDULED", log.get(2).get("kind").asText());
        assertEquals("itemB", log.get(2).get("itemId").asText());
        assertEquals(1200, log.get(2).get("startMs").asLong());
        assertEquals("OPEN", log.get(2).get("status").asText());
    }

    @Test
    void 更高优先级可压过当前插播() throws Exception {
        publishStandardPlaylist("ch-prio");
        postJson("/api/ticks", """
                {"channelId": "ch-prio", "atMs": 100}
                """);
        postJson("/api/breaks/start", """
                {"channelId": "ch-prio", "requestId": "low", "priority": 3,
                 "startMs": 200, "mediaDigest": "live-low"}
                """);
        JsonNode high = bodyOf(postJson("/api/breaks/start", """
                {"channelId": "ch-prio", "requestId": "high", "priority": 7,
                 "startMs": 400, "mediaDigest": "live-high"}
                """));
        assertEquals("ACTIVE", high.get("status").asText());

        JsonNode log = bodyOf(getJson("/api/channels/ch-prio/as-run"));
        // 低优先级插播在 400 被截断，高优先级接管
        assertEquals("BREAK", log.get(1).get("kind").asText());
        assertEquals("low", log.get(1).get("breakRequestId").asText());
        assertEquals(400, log.get(1).get("endMs").asLong());
        assertEquals("high", log.get(2).get("breakRequestId").asText());
        assertEquals("OPEN", log.get(2).get("status").asText());
    }

    @Test
    void 较低优先级与同优先级较大requestId被拒绝() throws Exception {
        publishStandardPlaylist("ch-rej");
        postJson("/api/ticks", """
                {"channelId": "ch-rej", "atMs": 100}
                """);
        postJson("/api/breaks/start", """
                {"channelId": "ch-rej", "requestId": "brk-m", "priority": 5,
                 "startMs": 200, "mediaDigest": "live-m"}
                """);

        MvcResult lower = postJson("/api/breaks/start", """
                {"channelId": "ch-rej", "requestId": "brk-low", "priority": 4,
                 "startMs": 300, "mediaDigest": "live-low"}
                """);
        assertEquals(409, statusOf(lower));

        MvcResult samePrioLargerId = postJson("/api/breaks/start", """
                {"channelId": "ch-rej", "requestId": "brk-z", "priority": 5,
                 "startMs": 300, "mediaDigest": "live-z"}
                """);
        assertEquals(409, statusOf(samePrioLargerId));

        // 同优先级更小 requestId 可压过
        JsonNode winner = bodyOf(postJson("/api/breaks/start", """
                {"channelId": "ch-rej", "requestId": "brk-a", "priority": 5,
                 "startMs": 300, "mediaDigest": "live-a"}
                """));
        assertEquals("ACTIVE", winner.get("status").asText());
    }

    @Test
    void 插播开始早于水位线返回409() throws Exception {
        publishStandardPlaylist("ch-wm-brk");
        postJson("/api/ticks", """
                {"channelId": "ch-wm-brk", "atMs": 100}
                """);
        postJson("/api/channels/ch-wm-brk/watermark", """
                {"watermarkMs": 500}
                """);
        MvcResult result = postJson("/api/breaks/start", """
                {"channelId": "ch-wm-brk", "requestId": "brk-wm", "priority": 9,
                 "startMs": 400, "mediaDigest": "live-wm"}
                """);
        assertEquals(409, statusOf(result));
        // 水位线未被修改，插播未写入
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM break_request WHERE channel_id = 'ch-wm-brk'", Integer.class);
        assertEquals(0, count);
    }

    @Test
    void 同键同参返回首次冻结结果() throws Exception {
        publishStandardPlaylist("ch-idem");
        postJson("/api/ticks", """
                {"channelId": "ch-idem", "atMs": 100}
                """);
        String request = """
                {"channelId": "ch-idem", "requestId": "brk-idem", "priority": 5,
                 "startMs": 200, "mediaDigest": "live-idem"}
                """;
        JsonNode first = bodyOf(postJson("/api/breaks/start", request));
        JsonNode second = bodyOf(postJson("/api/breaks/start", request));
        assertEquals(first.get("asRunSeq").asLong(), second.get("asRunSeq").asLong());
        assertEquals(first.toString(), second.toString());

        // 幂等重放没有生成额外 as-run 记录
        Integer breakRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM as_run WHERE channel_id = 'ch-idem' AND kind = 'BREAK'",
                Integer.class);
        assertEquals(1, breakRecords);
    }

    @Test
    void 同键异参返回409() throws Exception {
        publishStandardPlaylist("ch-fp");
        postJson("/api/ticks", """
                {"channelId": "ch-fp", "atMs": 100}
                """);
        postJson("/api/breaks/start", """
                {"channelId": "ch-fp", "requestId": "brk-fp", "priority": 5,
                 "startMs": 200, "mediaDigest": "live-fp"}
                """);
        MvcResult differentStart = postJson("/api/breaks/start", """
                {"channelId": "ch-fp", "requestId": "brk-fp", "priority": 5,
                 "startMs": 300, "mediaDigest": "live-fp"}
                """);
        assertEquals(409, statusOf(differentStart));

        MvcResult differentPriority = postJson("/api/breaks/start", """
                {"channelId": "ch-fp", "requestId": "brk-fp", "priority": 6,
                 "startMs": 200, "mediaDigest": "live-fp"}
                """);
        assertEquals(409, statusOf(differentPriority));
    }

    @Test
    void 结束幂等与结束时间冲突() throws Exception {
        publishStandardPlaylist("ch-end");
        postJson("/api/ticks", """
                {"channelId": "ch-end", "atMs": 100}
                """);
        postJson("/api/breaks/start", """
                {"channelId": "ch-end", "requestId": "brk-end", "priority": 5,
                 "startMs": 200, "mediaDigest": "live-end"}
                """);
        String endRequest = """
                {"channelId": "ch-end", "requestId": "brk-end", "endMs": 600}
                """;
        JsonNode first = bodyOf(postJson("/api/breaks/end", endRequest));
        JsonNode second = bodyOf(postJson("/api/breaks/end", endRequest));
        assertEquals(first.toString(), second.toString());

        MvcResult conflict = postJson("/api/breaks/end", """
                {"channelId": "ch-end", "requestId": "brk-end", "endMs": 700}
                """);
        assertEquals(409, statusOf(conflict));

        MvcResult notFound = postJson("/api/breaks/end", """
                {"channelId": "ch-end", "requestId": "brk-none", "endMs": 600}
                """);
        assertEquals(404, statusOf(notFound));
    }

    @Test
    void 插播改写未结算的历史asrun记录() throws Exception {
        publishStandardPlaylist("ch-rewrite");
        postJson("/api/ticks", """
                {"channelId": "ch-rewrite", "atMs": 100}
                """);
        postJson("/api/ticks", """
                {"channelId": "ch-rewrite", "atMs": 1200}
                """);
        // 插播开始于 500：晚于水位线 0，但早于已生成的部分记录
        postJson("/api/breaks/start", """
                {"channelId": "ch-rewrite", "requestId": "brk-rw", "priority": 5,
                 "startMs": 500, "mediaDigest": "live-rw"}
                """);
        JsonNode log = bodyOf(getJson("/api/channels/ch-rewrite/as-run"));
        // itemA [100,500) 截断保留；itemB 从 1200 开始的记录被取代；插播 [500,...)
        assertEquals(2, log.size());
        assertEquals("itemA", log.get(0).get("itemId").asText());
        assertEquals(500, log.get(0).get("endMs").asLong());
        assertEquals("BREAK", log.get(1).get("kind").asText());
        assertEquals(500, log.get(1).get("startMs").asLong());

        // 被取代的记录仍保留（供迟到 ACK），但不进入有效日志
        Integer superseded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM as_run WHERE channel_id = 'ch-rewrite' AND status = 'SUPERSEDED'",
                Integer.class);
        assertEquals(1, superseded);
    }
}
