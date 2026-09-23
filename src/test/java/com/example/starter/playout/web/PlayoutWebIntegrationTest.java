package com.example.starter.playout.web;

import com.example.starter.playout.clock.SyntheticClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 入口集成测试：真实 Spring MVC + H2，覆盖状态码、冻结结果回放与参数校验。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlayoutWebIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private SyntheticClock clock;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-03-01T00:00:00Z"));
    }

    private String channel() {
        return "web-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("播单发布 201 语义走 200；无效节目体 400；重复版本 409")
    void publishHttpStatuses() throws Exception {
        String ch = channel();
        String body = mapper.writeValueAsString(Map.of(
                "channel", ch,
                "version", "v1",
                "items", List.of(Map.of(
                        "itemId", "A",
                        "materialDigest", "sha256:aaa",
                        "start", "2026-03-01T00:00:00Z",
                        "end", "2026-03-01T01:00:00Z"))));
        mvc.perform(post("/api/channels/playlists/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").isNumber())
                .andExpect(jsonPath("$.items[0].itemId").value("A"));

        String overlapping = mapper.writeValueAsString(Map.of(
                "channel", ch,
                "version", "v-bad",
                "items", List.of(
                        Map.of("itemId", "B", "materialDigest", "d",
                                "start", "2026-03-01T00:00:00Z",
                                "end", "2026-03-01T00:10:01Z"),
                        Map.of("itemId", "C", "materialDigest", "d",
                                "start", "2026-03-01T00:10:00Z",
                                "end", "2026-03-01T00:20:00Z"))));
        mvc.perform(post("/api/channels/playlists/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(overlapping))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").exists());

        mvc.perform(post("/api/channels/playlists/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("tick、插播、恢复、ACK、as-run 查询完整主流程的 HTTP 语义")
    void fullHttpFlow() throws Exception {
        String ch = channel();
        String publish = mapper.writeValueAsString(Map.of(
                "channel", ch, "version", "v1",
                "items", List.of(
                        Map.of("itemId", "A", "materialDigest", "digest-A",
                                "start", "2026-03-01T00:00:00Z",
                                "end", "2026-03-01T00:30:00Z"),
                        Map.of("itemId", "B", "materialDigest", "digest-B",
                                "start", "2026-03-01T00:30:00Z",
                                "end", "2026-03-01T01:00:00Z"))));
        mvc.perform(post("/api/channels/playlists/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(publish))
                .andExpect(status().isOk());

        mvc.perform(post("/api/channels/tick")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"" + ch + "\",\"time\":\"2026-03-01T00:05:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].itemId").value("A"))
                .andExpect(jsonPath("$.segments[0].asRunSequence").value(1));

        String liveStart = mapper.writeValueAsString(Map.of(
                "channel", ch, "requestId", "news-flash",
                "start", "2026-03-01T00:10:00Z", "priority", 10));
        mvc.perform(post("/api/channels/live/start")
                        .contentType(MediaType.APPLICATION_JSON).content(liveStart))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        // 同键同参：200 冻结回放。
        mvc.perform(post("/api/channels/live/start")
                        .contentType(MediaType.APPLICATION_JSON).content(liveStart))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 同键异参：409。
        String liveStartChanged = mapper.writeValueAsString(Map.of(
                "channel", ch, "requestId", "news-flash",
                "start", "2026-03-01T00:11:00Z", "priority", 10));
        mvc.perform(post("/api/channels/live/start")
                        .contentType(MediaType.APPLICATION_JSON).content(liveStartChanged))
                .andExpect(status().isConflict());

        String liveEnd = mapper.writeValueAsString(Map.of(
                "channel", ch, "requestId", "news-flash",
                "end", "2026-03-01T00:20:00Z"));
        mvc.perform(post("/api/channels/live/end")
                        .contentType(MediaType.APPLICATION_JSON).content(liveEnd))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endKind").value("ENDED"));

        mvc.perform(post("/api/channels/tick")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"" + ch + "\",\"time\":\"2026-03-01T00:40:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(3))
                .andExpect(jsonPath("$.segments[0].kind").value("LIVE"))
                .andExpect(jsonPath("$.segments[1].itemId").value("A"))
                .andExpect(jsonPath("$.segments[2].itemId").value("B"));

        String ack = mapper.writeValueAsString(Map.of(
                "channel", ch, "ackKey", "ack-2", "asRunSequence", 2));
        mvc.perform(post("/api/channels/ack")
                        .contentType(MediaType.APPLICATION_JSON).content(ack))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.ackState").value("ACKED"));
        mvc.perform(post("/api/channels/ack")
                        .contentType(MediaType.APPLICATION_JSON).content(ack))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true))
                .andExpect(jsonPath("$.accepted").value(true));

        mvc.perform(get("/api/channels/" + ch + "/as-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].kind").value("ITEM"))
                .andExpect(jsonPath("$[1].kind").value("LIVE"))
                .andExpect(jsonPath("$[1].ackState").value("ACKED"))
                .andExpect(jsonPath("$[2].itemId").value("A"))
                .andExpect(jsonPath("$[3].itemId").value("B"));
    }

    @Test
    @DisplayName("水位线之前的插播开始 HTTP 409，且冻结失败结果可重放")
    void liveBeforeWatermarkHttp409AndReplay() throws Exception {
        String ch = channel();
        String publish = mapper.writeValueAsString(Map.of(
                "channel", ch, "version", "v1",
                "items", List.of(Map.of("itemId", "A", "materialDigest", "d",
                        "start", "2026-03-01T00:00:00Z", "end", "2026-03-01T02:00:00Z"))));
        mvc.perform(post("/api/channels/playlists/publish")
                .contentType(MediaType.APPLICATION_JSON).content(publish)).andExpect(status().isOk());
        mvc.perform(post("/api/channels/tick")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"" + ch + "\",\"time\":\"2026-03-01T00:10:00Z\"}"))
                .andExpect(status().isOk());

        String late = mapper.writeValueAsString(Map.of(
                "channel", ch, "requestId", "late",
                "start", "2026-03-01T00:09:00Z", "priority", 10));
        mvc.perform(post("/api/channels/live/start")
                        .contentType(MediaType.APPLICATION_JSON).content(late))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        mvc.perform(post("/api/channels/live/start")
                        .contentType(MediaType.APPLICATION_JSON).content(late))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("缺失必填字段返回 400；畸形时间返回 400")
    void validationErrors() throws Exception {
        mvc.perform(post("/api/channels/playlists/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"c\",\"version\":\"v\",\"items\":[]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/channels/tick")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"c\",\"time\":\"not-a-timestamp\"}"))
                .andExpect(status().isBadRequest());
    }
}
