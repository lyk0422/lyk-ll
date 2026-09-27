package com.example.starter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 历史水下记录更正测试：已关闭潜次不被追改，另记独立差异，积分账/台账不受影响。
 */
class RecordCorrectionTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    private String postJson(String uri, String json) throws Exception {
        return mockMvc.perform(post(uri).contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void correctionAppendsIndependentDifference_doesNotRewriteClosedDiveOrLedger() throws Exception {
        // 走旧语义完整潜次并结案
        String closedId = completeLegacyDive("OLD-COR", 18, 50);
        assertEquals("CLOSED", diveRepository.get(closedId).status());

        // 更正 D001 的 BOTTOM：50 -> 46，差异 -4
        mockMvc.perform(post("/api/controlled-dives/corrections").contentType("application/json")
                        .content(Requests.correction(objectMapper, "COR-1", closedId, "D001",
                                "BOTTOM", 1, 46)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordedDurationMin").value(50))
                .andExpect(jsonPath("$.correctedDurationMin").value(46))
                .andExpect(jsonPath("$.deltaMin").value(-4))
                .andExpect(jsonPath("$.replayed").value(false));

        // 原记录历时不被追改（仍 50）、台账仍按原 50 计、潜次仍结案
        assertEquals(50, jdbc.queryForObject(
                "SELECT duration_min FROM dive_record WHERE dive_request_id='OLD-COR' "
                        + "AND diver_code='D001' AND phase='BOTTOM'", Integer.class).intValue());
        assertEquals(50, jdbc.queryForObject(
                "SELECT bottom_min FROM bottom_time_ledger WHERE dive_request_id='OLD-COR' "
                        + "AND diver_code='D001'", Integer.class).intValue());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(1) FROM diver_points_account", Integer.class).intValue());

        // 差异可查询
        mockMvc.perform(get("/api/controlled-dives/OLD-COR/corrections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deltaMin").value(-4));

        // 同键同参重放：不重复追加
        mockMvc.perform(post("/api/controlled-dives/corrections").contentType("application/json")
                        .content(Requests.correction(objectMapper, "COR-1", closedId, "D001",
                                "BOTTOM", 1, 46)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM dive_record_correction WHERE request_id='COR-1'", Integer.class).intValue());

        // 同键异参（改更正值）→ 409，差异仍只有原一条
        mockMvc.perform(post("/api/controlled-dives/corrections").contentType("application/json")
                        .content(Requests.correction(objectMapper, "COR-1", closedId, "D001",
                                "BOTTOM", 1, 47)))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM dive_record_correction WHERE dive_request_id='OLD-COR'",
                Integer.class).intValue());
    }

    /** 潜次未结案不允许更正（409）。 */
    @Test
    void correctionOnNonClosedDive_rejected() throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, "OLD-OPEN", "D001", "D002", 18, 50));
        mockMvc.perform(post("/api/controlled-dives/corrections").contentType("application/json")
                        .content(Requests.correction(objectMapper, "COR-OPEN", "OLD-OPEN", "D001",
                                "BOTTOM", 0, 46)))
                .andExpect(status().isConflict());
    }

    /** 已结案但不存在的生效记录 → 404，且不占键。 */
    @Test
    void correctionMissingRecord_404_doesNotOccupyKey() throws Exception {
        String closedId = completeLegacyDive("OLD-MISS", 18, 50);
        mockMvc.perform(post("/api/controlled-dives/corrections").contentType("application/json")
                        .content(Requests.correction(objectMapper, "COR-MISS", closedId, "D004",
                                "BOTTOM", 1, 46)))
                .andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM idempotency_record WHERE request_id='COR-MISS'", Integer.class).intValue());
    }

    /** 构造一个已成对执行、出水并恰满 2 小时结案的旧语义潜次。 */
    private String completeLegacyDive(String id, int depth, int bottom) throws Exception {
        int transit = (int) Math.ceil(depth / 18.0);
        postJson("/api/dives/apply", Requests.apply(objectMapper, id, "D001", "D002", depth, bottom));
        postJson("/api/dives/plans", Requests.plan(objectMapper, "PLAN-" + id, id, depth, bottom));
        postJson("/api/dives/records", Requests.record(objectMapper, id + "-D1", id, "D001",
                "DESCENT", 0, depth, transit, T0));
        postJson("/api/dives/records", Requests.record(objectMapper, id + "-D2", id, "D002",
                "DESCENT", 0, depth, transit, T0));
        postJson("/api/dives/records", Requests.record(objectMapper, id + "-B1", id, "D001",
                "BOTTOM", depth, depth, bottom, T0.plusSeconds(60L)));
        postJson("/api/dives/records", Requests.record(objectMapper, id + "-B2", id, "D002",
                "BOTTOM", depth, depth, bottom, T0.plusSeconds(60L)));
        postJson("/api/dives/records", Requests.record(objectMapper, id + "-A1", id, "D001",
                "ASCENT", depth, 0, transit, T0.plusSeconds(60L * (1 + bottom))));
        postJson("/api/dives/records", Requests.record(objectMapper, id + "-A2", id, "D002",
                "ASCENT", depth, 0, transit, T0.plusSeconds(60L * (1 + bottom))));
        Instant surfaced = T0.plusSeconds(60L * (bottom + transit + 1));
        postJson("/api/dives/surface", Requests.surface(objectMapper, "S-" + id, id, surfaced));
        postJson("/api/dives/close", Requests.close(objectMapper, "C-" + id, id,
                surfaced.plus(Duration.ofHours(2))));
        return id;
    }
}
