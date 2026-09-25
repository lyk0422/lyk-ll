package com.example.starter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 主流程与关键边界：恰免减压极限、速率恰 18、两人成组、观察恰满 2 小时、结案终态。
 */
class DiveWorkflowTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    private String postJson(String uri, String json) throws Exception {
        return mockMvc.perform(post(uri).contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
    }

    /** 完整合法潜次：18 米恰 50 分钟（恰 NDL）、速率恰 18、成对、观察恰满 2 小时结案。 */
    @Test
    void fullHappyFlow_exactLimits_closesAfterExactlyTwoHours() throws Exception {
        mockMvc.perform(post("/api/dives/apply").contentType("application/json")
                        .content(Requests.apply(objectMapper, "DIVE-1", "D001", "D002", 18, 50)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.teamKey").value("D001|D002"));

        // 恰免减压极限：18 米 NDL=50，底时间恰 50 合法
        mockMvc.perform(post("/api/dives/plans").contentType("application/json")
                        .content(Requests.plan(objectMapper, "PLAN-1", "DIVE-1", 18, 50)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.ndlLimitMin").value(50))
                .andExpect(jsonPath("$.replayed").value(false));

        // 下潜 0→18 米历时 1 分钟，速率恰 18：单人不生效
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "R-D1", "DIVE-1", "D001",
                                "DESCENT", 0, 18, 1, T0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(false))
                .andExpect(jsonPath("$.pairedWith").doesNotExist());
        // 配对手成对生效，速率恰 18 不告警
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "R-D2", "DIVE-1", "D002",
                                "DESCENT", 0, 18, 1, T0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(true))
                .andExpect(jsonPath("$.pairedWith").value("D001"))
                .andExpect(jsonPath("$.speedAlert").value(false))
                .andExpect(jsonPath("$.rateMMin").value("18.00"));

        // 水底成对 50 分钟，累计 50
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "R-B1", "DIVE-1", "D001",
                                "BOTTOM", 18, 18, 50, T0.plusSeconds(60))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.effective").value(false));
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "R-B2", "DIVE-1", "D002",
                                "BOTTOM", 18, 18, 50, T0.plusSeconds(60))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(true))
                .andExpect(jsonPath("$.cumulativeBottomMin24h").value(50));

        // 上升成对，速率恰 18
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "R-A1", "DIVE-1", "D001",
                                "ASCENT", 18, 0, 1, T0.plusSeconds(60 * 51))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "R-A2", "DIVE-1", "D002",
                                "ASCENT", 18, 0, 1, T0.plusSeconds(60 * 51))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.speedAlert").value(false));

        Instant surfaced = T0.plusSeconds(60 * 55);
        mockMvc.perform(post("/api/dives/surface").contentType("application/json")
                        .content(Requests.surface(objectMapper, "S-1", "DIVE-1", surfaced)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SURFACED"))
                .andExpect(jsonPath("$.observationDueAtUtc").value(surfaced.plus(Duration.ofHours(2)).toString()));

        // 差 1 分钟不满 2 小时，禁止结案
        mockMvc.perform(post("/api/dives/close").contentType("application/json")
                        .content(Requests.close(objectMapper, "C-1", "DIVE-1",
                                surfaced.plus(Duration.ofHours(2)).minusSeconds(60))))
                .andExpect(status().isConflict());

        // 恰满 2 小时结案
        mockMvc.perform(post("/api/dives/close").contentType("application/json")
                        .content(Requests.close(objectMapper, "C-1", "DIVE-1",
                                surfaced.plus(Duration.ofHours(2)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.terminal").value(true));

        // 复算视图：速率全部一致、无告警、台账在限内
        mockMvc.perform(get("/api/dives/DIVE-1/recompute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRatesConsistent").value(true))
                .andExpect(jsonPath("$.alerts.length()").value(0))
                .andExpect(jsonPath("$.cumulativeRecompute[0].withinLimit").value(true));
    }

    /** 超过免减压极限 1 分钟 → 422，不出方案。 */
    @Test
    void exceedingNdlByOne_rejected() throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, "DIVE-NDL", "D001", "D002", 18, 50));
        mockMvc.perform(post("/api/dives/plans").contentType("application/json")
                        .content(Requests.plan(objectMapper, "PLAN-NDL", "DIVE-NDL", 18, 51)))
                .andExpect(status().isUnprocessableEntity());
    }

    /** 超速：24 米 1 分钟下潜速率 24＞18，成对后落告警并复算可见。 */
    @Test
    void overSpeedRaisesAlert_andSingleDiverRecordIneffective() throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, "DIVE-SP", "D001", "D002", 24, 30));
        postJson("/api/dives/plans", Requests.plan(objectMapper, "PLAN-SP", "DIVE-SP", 24, 30));

        // 仅 D001 单人超速下潜：不生效、无告警、无台账
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "R-SP1", "DIVE-SP", "D001",
                                "DESCENT", 0, 24, 1, T0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(false))
                .andExpect(jsonPath("$.speedAlert").value(false));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(1) FROM speed_alert", Integer.class).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(1) FROM bottom_time_ledger", Integer.class).intValue());

        // 配对手（合规速率 12m/min）成对后，D001 的超速仍被复算告警
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "R-SP2", "DIVE-SP", "D002",
                                "DESCENT", 0, 24, 2, T0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(true));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(1) FROM speed_alert WHERE diver_code='D001' "
                        + "AND rate_m_min > 18", Integer.class).intValue());

        mockMvc.perform(get("/api/dives/DIVE-SP/recompute"))
                .andExpect(jsonPath("$.alerts[0].diverCode").value("D001"))
                .andExpect(jsonPath("$.alerts[0].rateMMin").value("24.00"));
    }

    /** 同键重放返回首次结果；同键异参 409 且不改变潜次状态。 */
    @Test
    void sameKeyReplay_andConflictOnDifferentParams() throws Exception {
        String body = Requests.apply(objectMapper, "IDEM-1", "D001", "D002", 18, 50);
        mockMvc.perform(post("/api/dives/apply").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(false));
        // 同键同参重放
        mockMvc.perform(post("/api/dives/apply").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(true));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(1) FROM dive", Integer.class).intValue());

        // 同键异参（改深度）→ 409，潜次计划深度不变
        mockMvc.perform(post("/api/dives/apply").contentType("application/json")
                        .content(Requests.apply(objectMapper, "IDEM-1", "D001", "D002", 24, 30)))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(18,
                jdbc.queryForObject("SELECT planned_depth_m FROM dive WHERE request_id='IDEM-1'",
                        Integer.class).intValue());
    }

    /** 观察期出现症状：启动加压舱预案、状态 FROZEN、结案被冻结。 */
    @Test
    void symptomFreezesClose_andStartsChamberProtocol() throws Exception {
        prepareDivingDive("DIVE-FZ", "D001", "D002", 18, 50, T0);
        Instant surfaced = T0.plusSeconds(60 * 55);
        postJson("/api/dives/surface", Requests.surface(objectMapper, "S-FZ", "DIVE-FZ", surfaced));

        mockMvc.perform(post("/api/dives/vitals").contentType("application/json")
                        .content(Requests.vitals(objectMapper, "V-FZ", "DIVE-FZ", true,
                                surfaced.plusSeconds(60 * 30))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.chamberProtocolStarted").value(true));

        mockMvc.perform(post("/api/dives/close").contentType("application/json")
                        .content(Requests.close(objectMapper, "C-FZ", "DIVE-FZ",
                                surfaced.plus(Duration.ofHours(3)))))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(1,
                eventRepository.countByType("DIVE-FZ", "CHAMBER_PROTOCOL"));
    }

    /** 结案终态：重复结案幂等成功；结案后迟到体征只记迟到事件，状态不变。 */
    @Test
    void closedIsTerminal_repeatedCloseIdempotent_lateVitalsRecordedOnly() throws Exception {
        prepareDivingDive("DIVE-CL", "D001", "D002", 18, 50, T0);
        Instant surfaced = T0.plusSeconds(60 * 55);
        postJson("/api/dives/surface", Requests.surface(objectMapper, "S-CL", "DIVE-CL", surfaced));
        postJson("/api/dives/close", Requests.close(objectMapper, "C-CL", "DIVE-CL",
                surfaced.plus(Duration.ofHours(2))));

        // 换一个操作 requestId 重复结案，仍幂等成功
        mockMvc.perform(post("/api/dives/close").contentType("application/json")
                        .content(Requests.close(objectMapper, "C-CL-2", "DIVE-CL",
                                surfaced.plus(Duration.ofHours(5)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminal").value(true))
                .andExpect(jsonPath("$.replayed").value(true));

        // 迟到体征：只记事件，状态仍 CLOSED
        mockMvc.perform(post("/api/dives/vitals").contentType("application/json")
                        .content(Requests.vitals(objectMapper, "V-LATE", "DIVE-CL", true,
                                surfaced.plus(Duration.ofHours(6)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.lateVitals").value(true))
                .andExpect(jsonPath("$.chamberProtocolStarted").value(false));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                eventRepository.countByType("DIVE-CL", "LATE_VITALS"));
        org.junit.jupiter.api.Assertions.assertEquals("CLOSED",
                diveRepository.get("DIVE-CL").status());
    }

    /** 状态流转后重放旧操作（方案/记录/出水）仍返回首次结果，不因当前阶段误报 409。 */
    @Test
    void replayAfterStateAdvanced_returnsFirstResult() throws Exception {
        prepareDivingDive("DIVE-RP", "D003", "D004", 18, 50, T0);
        // 潜次已 DIVING：重放方案与下单人记录请求
        mockMvc.perform(post("/api/dives/plans").contentType("application/json")
                        .content(Requests.plan(objectMapper, "PLAN-DIVE-RP", "DIVE-RP", 18, 50)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "RD-DIVE-RP-1", "DIVE-RP", "D003",
                                "DESCENT", 0, 18, 1, T0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.phase").value("DESCENT"));

        Instant surfaced = T0.plusSeconds(60 * 55);
        postJson("/api/dives/surface", Requests.surface(objectMapper, "S-RP", "DIVE-RP", surfaced));
        // 已 SURFACED：重放出水请求仍返回首次结果
        mockMvc.perform(post("/api/dives/surface").contentType("application/json")
                        .content(Requests.surface(objectMapper, "S-RP", "DIVE-RP", surfaced)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.status").value("SURFACED"));
    }

    /** 两个不同潜水员才可成组；同一人不可申请。 */
    @Test
    void sameDiverTwice_rejected() throws Exception {        mockMvc.perform(post("/api/dives/apply").contentType("application/json")
                        .content(Requests.apply(objectMapper, "DIVE-SAME", "D001", "D001", 18, 50)))
                .andExpect(status().isBadRequest());
    }

    /** 构造一个已成对执行完 BOTTOM（状态 DIVING）的潜次，供出水/观察类用例复用。 */
    private void prepareDivingDive(String id, String a, String b, int depth, int bottom,
                                   Instant base) throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, id, a, b, depth, bottom));
        postJson("/api/dives/plans", Requests.plan(objectMapper, "PLAN-" + id, id, depth, bottom));
        int transit = (int) Math.ceil(depth / 18.0);
        postJson("/api/dives/records", Requests.record(objectMapper, "RD-" + id + "-1", id, a,
                "DESCENT", 0, depth, transit, base));
        postJson("/api/dives/records", Requests.record(objectMapper, "RD-" + id + "-2", id, b,
                "DESCENT", 0, depth, transit, base));
        postJson("/api/dives/records", Requests.record(objectMapper, "RB-" + id + "-1", id, a,
                "BOTTOM", depth, depth, bottom, base.plusSeconds(60L * transit)));
        postJson("/api/dives/records", Requests.record(objectMapper, "RB-" + id + "-2", id, b,
                "BOTTOM", depth, depth, bottom, base.plusSeconds(60L * transit)));
        postJson("/api/dives/records", Requests.record(objectMapper, "RA-" + id + "-1", id, a,
                "ASCENT", depth, 0, transit, base.plusSeconds(60L * (transit + bottom))));
        postJson("/api/dives/records", Requests.record(objectMapper, "RA-" + id + "-2", id, b,
                "ASCENT", depth, 0, transit, base.plusSeconds(60L * (transit + bottom))));
    }
}
