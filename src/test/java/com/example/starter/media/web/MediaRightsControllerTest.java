package com.example.starter.media.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 媒体版权 API 的 Web 层测试：真实 Spring MVC + 嵌入式 H2，验证状态码与 JSON 契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MediaRightsControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM play_schedule_item");
        jdbc.update("DELETE FROM play_schedule");
        jdbc.update("DELETE FROM rights_grant_version");
        jdbc.update("DELETE FROM rights_grant");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM rights_key_lock");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    @Test
    void createGrantReturns201AndGrantsAreQueryable() throws Exception {
        String body = """
                {
                  "requestId": "%s",
                  "assetId": "asset-web-1",
                  "region": "CN",
                  "platform": "WEB",
                  "windowStart": "2026-10-01T00:00:00Z",
                  "windowEnd": "2026-10-01T02:00:00Z",
                  "mode": "EXCLUSIVE"
                }
                """.formatted(rid());

        mockMvc.perform(post("/api/media/grants").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mode").value("EXCLUSIVE"))
                .andExpect(jsonPath("$.windowStart").value("2026-10-01T00:00:00Z"))
                .andExpect(jsonPath("$.windowEnd").value("2026-10-01T02:00:00Z"));
    }

    @Test
    void exclusiveOverlapReturns409() throws Exception {
        String first = """
                {
                  "requestId": "%s",
                  "assetId": "asset-web-2", "region": "CN", "platform": "WEB",
                  "windowStart": "2026-10-01T00:00:00Z", "windowEnd": "2026-10-01T03:00:00Z",
                  "mode": "EXCLUSIVE"
                }
                """.formatted(rid());
        mockMvc.perform(post("/api/media/grants").contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isCreated());

        String overlapping = """
                {
                  "requestId": "%s",
                  "assetId": "asset-web-2", "region": "CN", "platform": "WEB",
                  "windowStart": "2026-10-01T02:00:00Z", "windowEnd": "2026-10-01T04:00:00Z",
                  "mode": "EXCLUSIVE"
                }
                """.formatted(rid());
        mockMvc.perform(post("/api/media/grants").contentType(MediaType.APPLICATION_JSON).content(overlapping))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GRANT_WINDOW_CONFLICT"));
    }

    @Test
    void sameRequestIdSameParamsReplaysFirst201AndDifferentParamsReturn409() throws Exception {
        String requestId = rid();
        String body = """
                {
                  "requestId": "%s",
                  "assetId": "asset-web-3", "region": "CN", "platform": "WEB",
                  "windowStart": "2026-10-01T00:00:00Z", "windowEnd": "2026-10-01T02:00:00Z",
                  "mode": "EXCLUSIVE"
                }
                """.formatted(requestId);

        String firstBody = mockMvc.perform(
                        post("/api/media/grants").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // 同参重放：仍返回首次的 201 与同一响应体
        mockMvc.perform(post("/api/media/grants").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(result -> {
                    if (!result.getResponse().getContentAsString().equals(firstBody)) {
                        throw new AssertionError("重放响应体与首次结果不一致");
                    }
                });

        String different = body.replace("2026-10-01T02:00:00Z", "2026-10-01T03:00:00Z");
        mockMvc.perform(post("/api/media/grants").contentType(MediaType.APPLICATION_JSON).content(different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void scheduleSubmitPlaybackReceiptAndEvidenceEndToEnd() throws Exception {
        String grantBody = """
                {
                  "requestId": "%s",
                  "assetId": "asset-web-4", "region": "CN", "platform": "WEB",
                  "windowStart": "2026-10-01T00:00:00Z", "windowEnd": "2026-10-01T04:00:00Z",
                  "mode": "EXCLUSIVE"
                }
                """.formatted(rid());
        String grantId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(mockMvc.perform(
                                post("/api/media/grants").contentType(MediaType.APPLICATION_JSON).content(grantBody))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("grantId").asText();

        String scheduleBody = """
                {
                  "requestId": "%s",
                  "scheduleId": "sch-web-1",
                  "slots": [
                    {"assetId": "asset-web-4", "region": "CN", "platform": "WEB",
                     "slotStart": "2026-10-01T00:30:00Z", "slotEnd": "2026-10-01T01:30:00Z"}
                  ]
                }
                """.formatted(rid());
        mockMvc.perform(post("/api/media/schedules").contentType(MediaType.APPLICATION_JSON).content(scheduleBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.items[0].boundVersionNo").value(1))
                .andExpect(jsonPath("$.items[0].status").value("BOUND"));

        String receiptBody = """
                {"requestId": "%s", "scheduleId": "sch-web-1", "itemNo": 1}
                """.formatted(rid());
        mockMvc.perform(post("/api/media/playback/receipts")
                        .contentType(MediaType.APPLICATION_JSON).content(receiptBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleVersionNo").value(1))
                .andExpect(jsonPath("$.boundVersionNo").value(1))
                .andExpect(jsonPath("$.status").value("PLAYED"))
                .andExpect(jsonPath("$.playedAt").exists());

        mockMvc.perform(get("/api/media/schedules/sch-web-1/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemStatus").value("PLAYED"))
                .andExpect(jsonPath("$[0].grantId").value(grantId))
                .andExpect(jsonPath("$[0].grantWindowEnd").value("2026-10-01T04:00:00Z"));
    }

    @Test
    void uncoveredSlotRollsBackWholeSubmissionWith422() throws Exception {
        String grantBody = """
                {
                  "requestId": "%s",
                  "assetId": "asset-web-5", "region": "CN", "platform": "WEB",
                  "windowStart": "2026-10-01T00:00:00Z", "windowEnd": "2026-10-01T02:00:00Z",
                  "mode": "EXCLUSIVE"
                }
                """.formatted(rid());
        mockMvc.perform(post("/api/media/grants").contentType(MediaType.APPLICATION_JSON).content(grantBody))
                .andExpect(status().isCreated());

        String scheduleBody = """
                {
                  "requestId": "%s",
                  "scheduleId": "sch-web-bad",
                  "slots": [
                    {"assetId": "asset-web-5", "region": "CN", "platform": "WEB",
                     "slotStart": "2026-10-01T00:00:00Z", "slotEnd": "2026-10-01T01:00:00Z"},
                    {"assetId": "asset-web-5", "region": "CN", "platform": "WEB",
                     "slotStart": "2026-10-01T01:00:00Z", "slotEnd": "2026-10-01T03:00:00Z"}
                  ]
                }
                """.formatted(rid());
        mockMvc.perform(post("/api/media/schedules").contentType(MediaType.APPLICATION_JSON).content(scheduleBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SLOT_NOT_COVERED"));
    }

    @Test
    void playabilityCheckHonoursHalfOpenWindow() throws Exception {
        String grantBody = """
                {
                  "requestId": "%s",
                  "assetId": "asset-web-6", "region": "CN", "platform": "WEB",
                  "windowStart": "2026-10-01T00:00:00Z", "windowEnd": "2026-10-01T02:00:00Z",
                  "mode": "NON_EXCLUSIVE"
                }
                """.formatted(rid());
        mockMvc.perform(post("/api/media/grants").contentType(MediaType.APPLICATION_JSON).content(grantBody))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/media/playback/check").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetId": "asset-web-6", "region": "CN", "platform": "WEB",
                                 "at": "2026-10-01T02:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playable").value(false));

        mockMvc.perform(post("/api/media/playback/check").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetId": "asset-web-6", "region": "CN", "platform": "WEB",
                                 "at": "2026-10-01T01:59:59Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playable").value(true));
    }
}
